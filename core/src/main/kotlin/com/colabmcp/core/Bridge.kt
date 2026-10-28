package com.colabmcp.core

import com.colabmcp.core.colab.ColabManager
import com.colabmcp.core.colab.GoogleAuth
import com.colabmcp.core.http.HttpRequest
import com.colabmcp.core.http.HttpResponse
import com.colabmcp.core.http.HttpServer
import com.colabmcp.core.mcp.ColabTools
import com.colabmcp.core.mcp.McpServer
import com.colabmcp.core.oauth.OAuthServer
import com.colabmcp.core.tunnel.TunnelConfig
import com.colabmcp.core.tunnel.TunnelStatus
import com.colabmcp.core.tunnel.TunnelSupervisor
import com.colabmcp.core.tunnel.TunnelType
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.SecretStore
import com.colabmcp.core.util.constantTimeEquals
import com.colabmcp.core.util.htmlEscape
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.randomToken
import java.io.File

data class BridgeConfig(
    val port: Int = 8765,
    /** Listen on all interfaces (LAN access) instead of loopback only. */
    val bindAll: Boolean = false,
    val tunnel: TunnelConfig = TunnelConfig(TunnelType.NONE),
    val dataDir: File? = null,
    /** Also serve MCP without OAuth on a secret, unguessable path (for clients that cannot do OAuth). */
    val secretUrlEnabled: Boolean = false,
)

data class BridgeStatus(
    val running: Boolean,
    val localUrl: String?,
    val publicUrl: String?,
    val tunnel: TunnelStatus,
    val toolCalls: Int,
    val lastTool: String?,
    val secretUrl: String?,
) {
    /** The URL to paste into Gemini's "Add a custom app" dialog. */
    val mcpUrl: String? get() = publicUrl?.let { "$it/mcp" }

    fun summary(): String = buildString {
        append(if (running) "running" else "stopped")
        localUrl?.let { append(" | local $it/mcp") }
        append(" | tunnel ${tunnel.state}")
        mcpUrl?.let { append(" | MCP URL: $it") }
        tunnel.message?.let { append(" | $it") }
    }
}

/** HTTP server + MCP + OAuth + tunnel, i.e. everything that turns Colab into an MCP link. */
class Bridge(
    val auth: GoogleAuth,
    val manager: ColabManager,
    private val store: SecretStore,
    val config: BridgeConfig,
) {
    companion object {
        const val VERSION = "1.0.2"
        private const val TAG = "Bridge"
    }

    val oauth = OAuthServer(store)
    private val tools = ColabTools(manager)
    private val toolList by lazy { tools.all() }
    val mcp = McpServer({ toolList }, "colab-mcp-bridge", VERSION, ColabTools.INSTRUCTIONS)
    private var http: HttpServer? = null
    private var supervisor: TunnelSupervisor? = null
    private val secretPath: String = store.get("mcp_secret_path") ?: randomToken(24).also { store.put("mcp_secret_path", it) }

    @Volatile var listener: ((BridgeStatus) -> Unit)? = null
    @Volatile var status = BridgeStatus(false, null, null, TunnelStatus(TunnelStatus.State.OFF, null, null), 0, null, null)
        private set

    private fun update(transform: (BridgeStatus) -> BridgeStatus) {
        synchronized(this) { status = transform(status) }
        listener?.invoke(status)
    }

    fun start() {
        if (http != null) return
        val server = HttpServer(config.port, config.bindAll, ::route)
        server.start()
        http = server
        val local = "http://127.0.0.1:${server.boundPort}"
        mcp.onToolCall = { name, _, _ -> update { it.copy(toolCalls = it.toolCalls + 1, lastTool = name) } }
        update { it.copy(running = true, localUrl = local, secretUrl = null) }
        manager.startMaintenance()
        supervisor = TunnelSupervisor(config.tunnel, server.boundPort, store) { ts ->
            if (ts.url != null) oauth.publicBaseUrl = ts.url
            update { st ->
                st.copy(
                    tunnel = ts,
                    publicUrl = ts.url ?: if (ts.state == TunnelStatus.State.ONLINE) st.publicUrl else null,
                    secretUrl = if (config.secretUrlEnabled) (ts.url ?: local) + "/mcp/$secretPath" else null,
                )
            }
        }.also { it.start() }
        if (config.secretUrlEnabled) update { it.copy(secretUrl = "$local/mcp/$secretPath") }
        Log.i(TAG, "Bridge started on $local")
    }

    fun stop() {
        supervisor?.stop()
        supervisor = null
        http?.stop()
        http = null
        manager.shutdown()
        update { it.copy(running = false, localUrl = null, publicUrl = null, tunnel = TunnelStatus(TunnelStatus.State.OFF, null, null)) }
        Log.i(TAG, "Bridge stopped")
    }

    fun reconnectTunnel() = supervisor?.reconnectNow()

    private fun route(req: HttpRequest): HttpResponse {
        val path = req.path.trimEnd('/').ifEmpty { "/" }
        if (req.method == "OPTIONS") return oauth.preflight()
        return when {
            path == "/.well-known/oauth-protected-resource" || path == "/.well-known/oauth-protected-resource/mcp" ->
                oauth.protectedResourceMetadata(req)
            path.startsWith("/.well-known/oauth-authorization-server") || path.startsWith("/.well-known/openid-configuration") ||
                path == "/mcp/.well-known/openid-configuration" || path == "/mcp/.well-known/oauth-authorization-server" ->
                oauth.authorizationServerMetadata(req)
            path == "/register" && req.method == "POST" -> oauth.register(req)
            path == "/authorize" -> oauth.authorize(req)
            path == "/token" && req.method == "POST" -> oauth.token(req)
            path == "/revoke" && req.method == "POST" -> oauth.revoke(req)
            path == "/mcp" -> {
                val clientId = oauth.validateBearer(req)
                if (clientId == null) {
                    oauth.unauthorized(req, invalid = req.header("authorization") != null)
                } else {
                    withCors(mcp.handle(req))
                }
            }
            path.startsWith("/mcp/") && config.secretUrlEnabled && constantTimeEquals(path.removePrefix("/mcp/"), secretPath) ->
                withCors(mcp.handle(req))
            path == "/health" -> HttpResponse.json(200, jsonOf("ok" to true, "signed_in" to auth.isSignedIn, "version" to VERSION))
            path == "/" && req.method == "GET" -> HttpResponse.html(200, homePage(req))
            else -> HttpResponse.text(404, "Not found")
        }
    }

    private fun withCors(r: HttpResponse) = r
        .header("Access-Control-Allow-Origin", "*")
        .header("Access-Control-Expose-Headers", "Mcp-Session-Id, WWW-Authenticate")

    private fun homePage(req: HttpRequest): String {
        val base = oauth.baseUrl(req)
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Colab MCP Bridge</title><style>body{font-family:system-ui,sans-serif;max-width:560px;margin:40px auto;padding:0 16px;line-height:1.5}
            code{background:rgba(127,127,127,.15);padding:2px 6px;border-radius:6px;word-break:break-all}</style></head><body>
            <h1>Colab MCP Bridge is running</h1>
            <p>Add this URL as a custom app (MCP server) in Gemini or another MCP client:</p>
            <p><code>${htmlEscape(base)}/mcp</code></p>
            <p>You will be asked for the approval code shown in the app on your phone.</p>
            </body></html>
        """.trimIndent()
    }
}
