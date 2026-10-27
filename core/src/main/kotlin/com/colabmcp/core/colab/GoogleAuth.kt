package com.colabmcp.core.colab

import com.colabmcp.core.util.Http
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.PRETTY_JSON
import com.colabmcp.core.util.SecretStore
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.long
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.randomToken
import com.colabmcp.core.util.sha256B64Url
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

class AuthException(message: String) : Exception(message)

/**
 * Google sign-in for the Colab backend.
 *
 * Uses the same public "installed app" OAuth client that the official Colab CLI ships
 * (google-colab-cli's oauth_config.json, which is the Cloud SDK client), so the resulting
 * refresh token is a normal `authorized_user` Application Default Credential.
 */
class GoogleAuth(
    private val store: SecretStore,
    private val http: OkHttpClient = Http.client,
) {
    companion object {
        const val CLIENT_ID = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com"
        const val CLIENT_SECRET = "d-FL95Q19q7MQmFpd7hHD0Ty"
        const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
        const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val TOKENINFO_URI = "https://oauth2.googleapis.com/tokeninfo"
        const val REVOKE_URI = "https://oauth2.googleapis.com/revoke"

        /** Google-hosted page that shows the code for copy/paste (same as `gcloud auth application-default login`). */
        const val REMOTE_REDIRECT_URI = "https://sdk.cloud.google.com/applicationdefaultauthcode.html"

        /** Scope set used by the Colab CLI's oauth2 mode. */
        val SCOPES = listOf(
            "openid",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/colaboratory",
            "https://www.googleapis.com/auth/drive.file",
        )

        private const val KEY = "google_auth"
    }

    data class PendingLogin(val url: String, val redirectUri: String, val state: String, val verifier: String)

    data class Account(val email: String?, val scopes: List<String>)

    private data class Creds(
        val clientId: String,
        val clientSecret: String,
        val refreshToken: String,
        val email: String?,
        val scopes: List<String>,
    )

    private val lock = Any()
    private var accessToken: String? = null
    private var accessTokenExpiry = 0L

    /** Called whenever the signed-in account changes (sign-in, import, sign-out). */
    @Volatile
    var onChange: (() -> Unit)? = null

    private fun loadCreds(): Creds? {
        val raw = store.get(KEY) ?: return null
        return try {
            val o = parseJsonObject(raw)
            Creds(
                clientId = o.str("client_id") ?: CLIENT_ID,
                clientSecret = o.str("client_secret") ?: CLIENT_SECRET,
                refreshToken = o.str("refresh_token") ?: return null,
                email = o.str("email"),
                scopes = o.str("scopes")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCreds(c: Creds?) {
        store.put(
            KEY,
            c?.let {
                jsonOf(
                    "client_id" to it.clientId,
                    "client_secret" to it.clientSecret,
                    "refresh_token" to it.refreshToken,
                    "email" to it.email,
                    "scopes" to it.scopes.joinToString(" "),
                ).toString()
            },
        )
    }

    val isSignedIn: Boolean get() = loadCreds() != null
    val account: Account? get() = loadCreds()?.let { Account(it.email, it.scopes) }

    fun beginLogin(redirectUri: String = REMOTE_REDIRECT_URI): PendingLogin {
        val verifier = randomToken(48)
        val state = randomToken(16)
        val url = AUTH_URI.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("code_challenge", sha256B64Url(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .apply { if (redirectUri == REMOTE_REDIRECT_URI) addQueryParameter("token_usage", "remote") }
            .build()
            .toString()
        return PendingLogin(url, redirectUri, state, verifier)
    }

    /** Accepts either the bare code shown by Google or a full redirect URL containing `code=`. */
    fun completeLogin(pending: PendingLogin, codeOrUrl: String): Account {
        var code = codeOrUrl.trim()
        if (code.contains("code=")) {
            val query = code.substringAfter('?', code)
            val params = query.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            params["state"]?.let { if (it != pending.state) throw AuthException("Sign-in state mismatch; please start again.") }
            code = params["code"] ?: throw AuthException("No authorization code found in the pasted text.")
        }
        val body = FormBody.Builder()
            .add("code", code)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("redirect_uri", pending.redirectUri)
            .add("grant_type", "authorization_code")
            .add("code_verifier", pending.verifier)
            .build()
        val resp = tokenRequest(body)
        val refresh = resp.str("refresh_token") ?: throw AuthException("Google did not return a refresh token.")
        val scopes = resp.str("scope")?.split(' ') ?: SCOPES
        val email = resp.str("id_token")?.let { emailFromIdToken(it) }
        synchronized(lock) {
            accessToken = resp.str("access_token")
            accessTokenExpiry = System.currentTimeMillis() + (resp.long("expires_in") ?: 3600) * 1000
            saveCreds(Creds(CLIENT_ID, CLIENT_SECRET, refresh, email ?: tokenInfoEmail(accessToken!!), scopes))
        }
        Log.i("GoogleAuth", "Signed in as $email")
        onChange?.invoke()
        return account!!
    }

    /** Imports an `authorized_user` ADC JSON (e.g. ~/.config/gcloud/application_default_credentials.json). */
    fun importAdc(json: String): Account {
        val o = try {
            parseJsonObject(json)
        } catch (e: Exception) {
            throw AuthException("That is not valid JSON.")
        }
        if (o.str("type") != "authorized_user") throw AuthException("Only 'authorized_user' ADC files are supported (from gcloud auth application-default login).")
        val creds = Creds(
            clientId = o.str("client_id") ?: throw AuthException("ADC is missing client_id"),
            clientSecret = o.str("client_secret") ?: throw AuthException("ADC is missing client_secret"),
            refreshToken = o.str("refresh_token") ?: throw AuthException("ADC is missing refresh_token"),
            email = null,
            scopes = emptyList(),
        )
        val resp = refresh(creds)
        val token = resp.str("access_token")!!
        val info = tokenInfo(token)
        val scopes = info?.str("scope")?.split(' ') ?: emptyList()
        if (scopes.isNotEmpty() && scopes.none { it.endsWith("/colaboratory") }) {
            throw AuthException("These credentials lack the colaboratory scope. Re-create them with --scopes=openid,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/userinfo.email,https://www.googleapis.com/auth/colaboratory")
        }
        synchronized(lock) {
            accessToken = token
            accessTokenExpiry = System.currentTimeMillis() + (resp.long("expires_in") ?: 3600) * 1000
            saveCreds(creds.copy(email = info?.str("email") ?: o.str("account"), scopes = scopes))
        }
        onChange?.invoke()
        return account!!
    }

    /** Exports the signed-in credential as an `authorized_user` ADC JSON document. */
    fun exportAdcJson(): String? {
        val c = loadCreds() ?: return null
        val o = jsonOf(
            "account" to (c.email ?: ""),
            "client_id" to c.clientId,
            "client_secret" to c.clientSecret,
            "refresh_token" to c.refreshToken,
            "type" to "authorized_user",
            "universe_domain" to "googleapis.com",
        )
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), o)
    }

    fun signOut(revoke: Boolean = true) {
        val c = loadCreds()
        synchronized(lock) {
            accessToken = null
            accessTokenExpiry = 0
            saveCreds(null)
        }
        if (revoke && c != null) {
            try {
                http.newCall(Request.Builder().url(REVOKE_URI).post(FormBody.Builder().add("token", c.refreshToken).build()).build()).execute().close()
            } catch (e: Exception) {
                Log.w("GoogleAuth", "Token revoke failed", e)
            }
        }
        onChange?.invoke()
    }

    /** Returns a valid access token, refreshing it when it expires within five minutes. */
    fun accessToken(forceRefresh: Boolean = false): String = synchronized(lock) {
        val now = System.currentTimeMillis()
        val cached = accessToken
        if (!forceRefresh && cached != null && accessTokenExpiry - now > 5 * 60 * 1000) return cached
        val creds = loadCreds() ?: throw AuthException("Not signed in to Google. Open the Colab MCP Bridge app and sign in.")
        val resp = refresh(creds)
        accessToken = resp.str("access_token")
        accessTokenExpiry = now + (resp.long("expires_in") ?: 3600) * 1000
        accessToken!!
    }

    private fun refresh(c: Creds): JsonObject {
        val body = FormBody.Builder()
            .add("client_id", c.clientId)
            .add("client_secret", c.clientSecret)
            .add("refresh_token", c.refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        return tokenRequest(body)
    }

    private fun tokenRequest(body: FormBody): JsonObject {
        http.newCall(Request.Builder().url(TOKEN_URI).post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = try {
                parseJsonObject(text)
            } catch (e: Exception) {
                throw AuthException("Google token endpoint returned HTTP ${r.code}")
            }
            if (!r.isSuccessful || o.str("access_token") == null) {
                val err = o.str("error") ?: "HTTP ${r.code}"
                if (err == "invalid_grant") {
                    throw AuthException("Google sign-in expired or was revoked (invalid_grant). Sign in again in the app.")
                }
                throw AuthException("Google token error: $err ${o.str("error_description") ?: ""}".trim())
            }
            return o
        }
    }

    private fun tokenInfo(accessToken: String): JsonObject? = try {
        val url = TOKENINFO_URI.toHttpUrl().newBuilder().addQueryParameter("access_token", accessToken).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) parseJsonObject(r.body!!.string()) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun tokenInfoEmail(accessToken: String): String? = tokenInfo(accessToken)?.str("email")

    private fun emailFromIdToken(idToken: String): String? = try {
        val payload = idToken.split('.')[1]
        parseJsonObject(String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))).str("email")
    } catch (e: Exception) {
        null
    }
}
