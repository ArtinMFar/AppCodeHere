package com.colabmcp.core.oauth

import com.colabmcp.core.http.HttpRequest
import com.colabmcp.core.http.HttpResponse
import com.colabmcp.core.util.Http
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.SecretStore
import com.colabmcp.core.util.arr
import com.colabmcp.core.util.constantTimeEquals
import com.colabmcp.core.util.htmlEscape
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.long
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.randomDigits
import com.colabmcp.core.util.randomToken
import com.colabmcp.core.util.sha256B64Url
import com.colabmcp.core.util.sha256Hex
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal OAuth 2.1 authorization server for MCP clients (Gemini custom apps, Claude, etc.):
 * RFC 9728 protected-resource metadata, RFC 8414 server metadata, RFC 7591 dynamic client
 * registration, client ID metadata documents, PKCE, refresh tokens. A person approves each new
 * connection by typing the approval code shown in the phone app.
 */
class OAuthServer(private val store: SecretStore) {
    companion object {
        private const val TAG = "OAuth"
        private const val KEY = "oauth_state"
        private const val ACCESS_TTL_SECONDS = 7L * 24 * 3600
        const val SCOPE = "colab"
    }

    data class Client(
        val id: String,
        val secretHash: String?,
        val name: String,
        val redirectUris: List<String>,
        val createdAt: Long,
        var lastUsed: Long = 0,
    )

    private data class Code(
        val clientId: String,
        val redirectUri: String,
        val challenge: String?,
        val method: String,
        val resource: String?,
        val expiresAt: Long,
    )

    private data class Token(val clientId: String, val expiresAt: Long)

    private val lock = Any()
    private val clients = LinkedHashMap<String, Client>()
    private val accessTokens = HashMap<String, Token>() // sha256 -> token
    private val refreshTokens = HashMap<String, Token>()
    private val codes = ConcurrentHashMap<String, Code>()
    @Volatile private var pin: String = randomDigits(6)
    @Volatile private var failedAttempts = 0
    @Volatile private var lockedUntil = 0L

    /** Called when the approval code or the client list changes. */
    @Volatile var onChange: (() -> Unit)? = null

    init {
        load()
    }

    fun currentPin(): String = pin

    fun regeneratePin() {
        pin = randomDigits(6)
        failedAttempts = 0
        onChange?.invoke()
    }

    fun clients(): List<Client> = synchronized(lock) { clients.values.toList() }

    fun revokeAll() {
        synchronized(lock) {
            clients.clear()
            accessTokens.clear()
            refreshTokens.clear()
            codes.clear()
            save()
        }
        regeneratePin()
        Log.i(TAG, "All MCP client connections revoked")
    }

    // ------------------------------------------------------------------ persistence

    private fun load() = synchronized(lock) {
        val raw = store.get(KEY) ?: return@synchronized
        try {
            val o = parseJsonObject(raw)
            o.arr("clients")?.forEach { e ->
                val c = e as? JsonObject ?: return@forEach
                val id = c.str("id") ?: return@forEach
                clients[id] = Client(
                    id, c.str("secret_hash"), c.str("name") ?: "MCP client",
                    c.arr("redirect_uris")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    c.long("created_at") ?: 0, c.long("last_used") ?: 0,
                )
            }
            o.arr("access")?.forEach { e ->
                val t = e as? JsonObject ?: return@forEach
                accessTokens[t.str("h") ?: return@forEach] = Token(t.str("c") ?: "", t.long("e") ?: 0)
            }
            o.arr("refresh")?.forEach { e ->
                val t = e as? JsonObject ?: return@forEach
                refreshTokens[t.str("h") ?: return@forEach] = Token(t.str("c") ?: "", t.long("e") ?: Long.MAX_VALUE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load OAuth state", e)
        }
    }

    private fun save() = synchronized(lock) {
        val now = System.currentTimeMillis()
        accessTokens.entries.removeIf { it.value.expiresAt < now }
        val o = jsonOf(
            "clients" to clients.values.map {
                jsonOf("id" to it.id, "secret_hash" to it.secretHash, "name" to it.name, "redirect_uris" to it.redirectUris, "created_at" to it.createdAt, "last_used" to it.lastUsed)
            },
            "access" to accessTokens.map { (h, t) -> jsonOf("h" to h, "c" to t.clientId, "e" to t.expiresAt) },
            "refresh" to refreshTokens.map { (h, t) -> jsonOf("h" to h, "c" to t.clientId, "e" to t.expiresAt) },
        )
        store.put(KEY, o.toString())
        onChange?.invoke()
    }

    // ------------------------------------------------------------------ helpers

    /** Public tunnel URL, used when a tunnel rewrites the Host header to the local address. */
    @Volatile var publicBaseUrl: String? = null

    fun baseUrl(req: HttpRequest): String {
        val host = req.header("x-forwarded-host")?.substringBefore(',')?.trim() ?: req.header("host") ?: "localhost"
        val proxied = req.header("x-forwarded-for") != null || req.header("x-forwarded-proto") != null
        publicBaseUrl?.let { if (proxied && isLocalHost(host)) return it }
        val proto = req.header("x-forwarded-proto")?.substringBefore(',')?.trim()
            ?: if (isLocalHost(host)) "http" else "https"
        return "$proto://$host"
    }

    private fun isLocalHost(host: String): Boolean {
        val h = host.substringBefore(':').lowercase()
        return h == "localhost" || h.startsWith("127.") || h == "[::1]" || h.startsWith("10.") || h.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h)
    }

    private fun cors(resp: HttpResponse): HttpResponse = resp
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Allow-Headers", "Authorization, Content-Type, Mcp-Session-Id, MCP-Protocol-Version")
        .header("Access-Control-Expose-Headers", "Mcp-Session-Id, WWW-Authenticate")
        .header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")

    private fun oauthError(status: Int, error: String, description: String) =
        cors(HttpResponse.json(status, jsonOf("error" to error, "error_description" to description)).header("Cache-Control", "no-store"))

    fun protectedResourceMetadata(req: HttpRequest, resourcePath: String = "/mcp"): HttpResponse {
        val base = baseUrl(req)
        return cors(
            HttpResponse.json(
                200,
                jsonOf(
                    "resource" to base + resourcePath,
                    "authorization_servers" to listOf(base),
                    "bearer_methods_supported" to listOf("header"),
                    "scopes_supported" to listOf(SCOPE),
                    "resource_name" to "Google Colab via Colab MCP Bridge",
                ),
            ),
        )
    }

    fun authorizationServerMetadata(req: HttpRequest): HttpResponse {
        val base = baseUrl(req)
        return cors(
            HttpResponse.json(
                200,
                jsonOf(
                    "issuer" to base,
                    "authorization_endpoint" to "$base/authorize",
                    "token_endpoint" to "$base/token",
                    "registration_endpoint" to "$base/register",
                    "revocation_endpoint" to "$base/revoke",
                    "response_types_supported" to listOf("code"),
                    "response_modes_supported" to listOf("query"),
                    "grant_types_supported" to listOf("authorization_code", "refresh_token"),
                    "code_challenge_methods_supported" to listOf("S256", "plain"),
                    "token_endpoint_auth_methods_supported" to listOf("none", "client_secret_post", "client_secret_basic"),
                    "revocation_endpoint_auth_methods_supported" to listOf("none", "client_secret_post", "client_secret_basic"),
                    "scopes_supported" to listOf(SCOPE),
                    "client_id_metadata_document_supported" to true,
                    "subject_types_supported" to listOf("public"),
                    "id_token_signing_alg_values_supported" to listOf("none"),
                    "service_documentation" to "https://github.com/ArtinMFar/AppCodeHere",
                ),
            ),
        )
    }

    fun preflight(): HttpResponse = cors(HttpResponse.empty(204)).header("Access-Control-Max-Age", "86400")

    /** Checks a Bearer token for the MCP endpoint; returns the client id or null. */
    fun validateBearer(req: HttpRequest): String? {
        val h = req.header("authorization") ?: return null
        if (!h.startsWith("Bearer ", ignoreCase = true)) return null
        val token = h.substring(7).trim()
        val t = synchronized(lock) { accessTokens[sha256Hex(token)] } ?: return null
        if (t.expiresAt < System.currentTimeMillis()) return null
        synchronized(lock) {
            clients[t.clientId]?.let { c ->
                if (System.currentTimeMillis() - c.lastUsed > 60_000) {
                    c.lastUsed = System.currentTimeMillis()
                    save()
                }
            }
        }
        return t.clientId
    }

    fun unauthorized(req: HttpRequest, invalid: Boolean, resourcePath: String = "/mcp"): HttpResponse {
        val base = baseUrl(req)
        val challenge = buildString {
            append("Bearer realm=\"colab-mcp\", resource_metadata=\"$base/.well-known/oauth-protected-resource$resourcePath\", scope=\"$SCOPE\"")
            if (invalid) append(", error=\"invalid_token\", error_description=\"The access token is invalid or expired\"")
        }
        return cors(HttpResponse.json(401, jsonOf("error" to if (invalid) "invalid_token" else "unauthorized", "error_description" to "Authorization required")))
            .header("WWW-Authenticate", challenge)
    }

    // ------------------------------------------------------------------ registration

    private fun validRedirect(uri: String): Boolean {
        val lower = uri.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("file:") || '#' in uri) return false
        if (lower.startsWith("https://")) return true
        if (lower.startsWith("http://")) {
            val host = uri.toHttpUrlOrNull()?.host ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }
        // Private-use URI schemes for native apps (e.g. cursor://, vscode://).
        return Regex("^[a-z][a-z0-9+.-]*:/.+").matches(lower)
    }

    fun register(req: HttpRequest): HttpResponse {
        val body = try {
            parseJsonObject(req.bodyText)
        } catch (e: Exception) {
            return oauthError(400, "invalid_client_metadata", "Body must be a JSON object")
        }
        val redirects = body.arr("redirect_uris")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        if (redirects.isEmpty()) return oauthError(400, "invalid_redirect_uri", "redirect_uris is required")
        val accepted = redirects.filter(::validRedirect)
        if (accepted.isEmpty()) return oauthError(400, "invalid_redirect_uri", "No acceptable redirect_uris")
        val method = body.str("token_endpoint_auth_method") ?: "client_secret_basic".takeIf { body.str("client_secret") != null } ?: "none"
        val id = "mcp_" + randomToken(18)
        val secret = if (method == "none") null else randomToken(32)
        val name = body.str("client_name")?.take(80) ?: "MCP client"
        val client = Client(id, secret?.let { sha256Hex(it) }, name, accepted, System.currentTimeMillis())
        synchronized(lock) {
            clients[id] = client
            // Keep the list bounded: drop the oldest never-used registrations.
            while (clients.size > 50) {
                val victim = clients.values.firstOrNull { it.lastUsed == 0L } ?: clients.values.first()
                clients.remove(victim.id)
            }
            save()
        }
        Log.i(TAG, "Registered client '$name' (${accepted.size} redirect URI(s))")
        val resp = mutableMapOf<String, Any?>(
            "client_id" to id,
            "client_id_issued_at" to client.createdAt / 1000,
            "client_name" to name,
            "redirect_uris" to accepted,
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to method,
            "scope" to SCOPE,
        )
        if (secret != null) {
            resp["client_secret"] = secret
            resp["client_secret_expires_at"] = 0
        }
        return cors(HttpResponse.json(201, com.colabmcp.core.util.toJson(resp)).header("Cache-Control", "no-store"))
    }

    /** Resolves registered clients, or fetches a Client ID Metadata Document for URL client ids. */
    private fun lookupClient(clientId: String): Client? {
        synchronized(lock) { clients[clientId] }?.let { return it }
        if (!clientId.startsWith("https://")) return null
        return try {
            Http.client.newCall(Request.Builder().url(clientId).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                if (body.contentLength() > 64 * 1024) return null
                val text = body.string().takeIf { it.length <= 64 * 1024 } ?: return null
                val o = parseJsonObject(text)
                if (o.str("client_id") != clientId) return null
                val redirects = o.arr("redirect_uris")?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter(::validRedirect) ?: return null
                Client(clientId, null, o.str("client_name") ?: clientId.toHttpUrlOrNull()?.host ?: "MCP client", redirects, System.currentTimeMillis()).also {
                    synchronized(lock) {
                        clients[clientId] = it
                        save()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client metadata document fetch failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ authorization

    fun authorize(req: HttpRequest): HttpResponse {
        val p = if (req.method == "POST") req.form() else req.query
        val clientId = p["client_id"] ?: return HttpResponse.html(400, page("Invalid request", "<p>Missing client_id.</p>"))
        val client = lookupClient(clientId)
            ?: return HttpResponse.html(400, page("Unknown app", "<p>This app is not registered with this server. Remove and re-add the connection in your MCP client.</p>"))
        val redirectUri = p["redirect_uri"] ?: client.redirectUris.singleOrNull()
            ?: return HttpResponse.html(400, page("Invalid request", "<p>Missing redirect_uri.</p>"))
        if (redirectUri !in client.redirectUris) {
            return HttpResponse.html(400, page("Invalid redirect", "<p>The redirect URI is not registered for this app.</p>"))
        }
        val state = p["state"]
        fun redirectWith(params: Map<String, String?>): HttpResponse {
            val sep = if ('?' in redirectUri) '&' else '?'
            val q = params.filterValues { it != null }.map { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }.joinToString("&")
            return HttpResponse.redirect(redirectUri + sep + q)
        }
        if (p["response_type"] != null && p["response_type"] != "code") {
            return redirectWith(mapOf("error" to "unsupported_response_type", "state" to state))
        }
        val challenge = p["code_challenge"]
        val method = p["code_challenge_method"] ?: if (challenge != null) "plain" else "none"
        if (challenge == null && client.secretHash == null) {
            return redirectWith(mapOf("error" to "invalid_request", "error_description" to "PKCE code_challenge is required", "state" to state))
        }

        if (req.method == "POST") {
            if (p["action"] == "deny") {
                return redirectWith(mapOf("error" to "access_denied", "state" to state, "iss" to baseUrl(req)))
            }
            val now = System.currentTimeMillis()
            if (now < lockedUntil) {
                return HttpResponse.html(429, consentPage(req, client, p, "Too many wrong codes. Wait a minute and try again."))
            }
            val entered = (p["pin"] ?: "").filter { it.isDigit() }
            if (!constantTimeEquals(entered, pin)) {
                failedAttempts++
                if (failedAttempts >= 5) {
                    lockedUntil = now + 60_000
                    regeneratePin()
                    Log.w(TAG, "5 wrong approval codes; locked for a minute and generated a new code")
                }
                return HttpResponse.html(401, consentPage(req, client, p, "Wrong approval code. Check the Colab MCP Bridge app."))
            }
            failedAttempts = 0
            val code = randomToken(32)
            codes[code] = Code(client.id, redirectUri, challenge, method, p["resource"], now + 10 * 60_000)
            regeneratePin()
            Log.i(TAG, "Approved connection for '${client.name}'")
            return redirectWith(mapOf("code" to code, "state" to state, "iss" to baseUrl(req)))
        }
        return HttpResponse.html(200, consentPage(req, client, p, null))
    }

    private fun consentPage(req: HttpRequest, client: Client, p: Map<String, String>, error: String?): String {
        val hidden = listOf("client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "scope", "resource", "response_type")
            .mapNotNull { k -> p[k]?.let { "<input type=\"hidden\" name=\"$k\" value=\"${htmlEscape(it)}\">" } }
            .joinToString("\n")
        val redirectHost = p["redirect_uri"]?.toHttpUrlOrNull()?.host ?: p["redirect_uri"]?.substringBefore(':') ?: "?"
        val err = error?.let { "<p class=\"err\">${htmlEscape(it)}</p>" } ?: ""
        return page(
            "Connect to your Colab",
            """
            <p><b>${htmlEscape(client.name)}</b> wants to control your Google Colab account through the Colab MCP Bridge on your phone
            (create GPU/CPU runtimes, run code, read and write files).</p>
            <p class="muted">It will be sent back to <code>${htmlEscape(redirectHost)}</code>.</p>
            $err
            <form method="post" action="${baseUrl(req)}/authorize">
              $hidden
              <label for="pin">Approval code shown in the app</label>
              <input id="pin" name="pin" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9 ]*" maxlength="7" autofocus placeholder="123456">
              <div class="row">
                <button type="submit" name="action" value="approve">Allow</button>
                <button type="submit" name="action" value="deny" class="secondary" formnovalidate>Deny</button>
              </div>
            </form>
            """.trimIndent(),
        )
    }

    private fun page(title: String, body: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${htmlEscape(title)}</title>
        <style>
          :root{color-scheme:light dark;--bg:#f6f7fb;--card:#fff;--fg:#1d1d1f;--muted:#666;--accent:#f9ab00;--err:#c5221f}
          @media (prefers-color-scheme:dark){:root{--bg:#121212;--card:#1e1e1e;--fg:#eee;--muted:#aaa;--err:#f28b82}}
          body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
          .card{max-width:440px;margin:40px auto;padding:24px;background:var(--card);border-radius:16px;box-shadow:0 2px 12px rgba(0,0,0,.12)}
          h1{font-size:1.3rem;margin:0 0 12px} .muted{color:var(--muted);font-size:.9rem} .err{color:var(--err);font-weight:600}
          label{display:block;margin:16px 0 6px;font-weight:600}
          input[name=pin]{width:100%;box-sizing:border-box;font-size:1.8rem;letter-spacing:.4em;text-align:center;padding:10px;border-radius:10px;border:1px solid #888;background:transparent;color:inherit}
          .row{display:flex;gap:12px;margin-top:18px} button{flex:1;padding:12px;border:0;border-radius:10px;font-size:1rem;font-weight:600;background:var(--accent);color:#000}
          button.secondary{background:transparent;border:1px solid #888;color:inherit} code{word-break:break-all}
        </style></head><body><div class="card"><h1>${htmlEscape(title)}</h1>$body</div></body></html>
    """.trimIndent()

    // ------------------------------------------------------------------ token endpoint

    private fun clientCredentials(req: HttpRequest): Pair<String?, String?> {
        val basic = req.header("authorization")
        if (basic != null && basic.startsWith("Basic ", true)) {
            val decoded = String(Base64.getDecoder().decode(basic.substring(6).trim()))
            val id = java.net.URLDecoder.decode(decoded.substringBefore(':'), "UTF-8")
            val secret = java.net.URLDecoder.decode(decoded.substringAfter(':', ""), "UTF-8")
            return id to secret
        }
        return req.param("client_id") to req.param("client_secret")
    }

    private fun authenticateClient(req: HttpRequest): Client? {
        val (id, secret) = clientCredentials(req)
        val client = id?.let { lookupClient(it) } ?: return null
        if (client.secretHash != null) {
            if (secret == null || !constantTimeEquals(sha256Hex(secret), client.secretHash)) return null
        }
        return client
    }

    fun token(req: HttpRequest): HttpResponse {
        val client = authenticateClient(req) ?: return oauthError(401, "invalid_client", "Unknown client or bad client credentials")
        return when (req.param("grant_type")) {
            "authorization_code" -> {
                val code = codes.remove(req.param("code") ?: "") ?: return oauthError(400, "invalid_grant", "Invalid or expired authorization code")
                if (code.expiresAt < System.currentTimeMillis() || code.clientId != client.id) return oauthError(400, "invalid_grant", "Invalid authorization code")
                val redirect = req.param("redirect_uri")
                if (redirect != null && redirect != code.redirectUri) return oauthError(400, "invalid_grant", "redirect_uri mismatch")
                if (code.challenge != null) {
                    val verifier = req.param("code_verifier") ?: return oauthError(400, "invalid_grant", "code_verifier required")
                    val computed = if (code.method.equals("S256", true)) sha256B64Url(verifier) else verifier
                    if (!constantTimeEquals(computed, code.challenge)) return oauthError(400, "invalid_grant", "PKCE verification failed")
                }
                issueTokens(client, includeRefresh = true, null)
            }
            "refresh_token" -> {
                val rt = req.param("refresh_token") ?: return oauthError(400, "invalid_request", "refresh_token required")
                val t = synchronized(lock) { refreshTokens[sha256Hex(rt)] }
                if (t == null || t.clientId != client.id) return oauthError(400, "invalid_grant", "Invalid refresh token")
                issueTokens(client, includeRefresh = false, rt)
            }
            else -> oauthError(400, "unsupported_grant_type", "Unsupported grant_type")
        }
    }

    private fun issueTokens(client: Client, includeRefresh: Boolean, existingRefresh: String?): HttpResponse {
        val access = randomToken(32)
        val refresh = if (includeRefresh) randomToken(32) else existingRefresh
        synchronized(lock) {
            accessTokens[sha256Hex(access)] = Token(client.id, System.currentTimeMillis() + ACCESS_TTL_SECONDS * 1000)
            if (includeRefresh && refresh != null) refreshTokens[sha256Hex(refresh)] = Token(client.id, Long.MAX_VALUE)
            client.lastUsed = System.currentTimeMillis()
            save()
        }
        return cors(
            HttpResponse.json(
                200,
                jsonOf(
                    "access_token" to access,
                    "token_type" to "Bearer",
                    "expires_in" to ACCESS_TTL_SECONDS,
                    "refresh_token" to refresh,
                    "scope" to SCOPE,
                ),
            ).header("Cache-Control", "no-store").header("Pragma", "no-cache"),
        )
    }

    fun revoke(req: HttpRequest): HttpResponse {
        val token = req.param("token") ?: return cors(HttpResponse.empty(200))
        val h = sha256Hex(token)
        synchronized(lock) {
            accessTokens.remove(h)
            refreshTokens.remove(h)
            save()
        }
        return cors(HttpResponse.empty(200))
    }
}
