package com.colabmcp.bridge

import com.colabmcp.core.colab.GoogleAuth
import com.colabmcp.core.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Google sign-in through the phone's browser with a loopback redirect
 * (http://127.0.0.1:PORT/), the same flow `gcloud auth application-default login` uses.
 */
class LoopbackLogin(private val auth: GoogleAuth) {
    private var server: ServerSocket? = null

    /** Returns the URL to open in the browser; [onResult] is called on a background thread. */
    fun start(onResult: (Result<GoogleAuth.Account>) -> Unit): String {
        cancel()
        val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        ss.soTimeout = 10 * 60 * 1000
        server = ss
        val pending = auth.beginLogin("http://127.0.0.1:${ss.localPort}/")
        thread(name = "loopback-login") {
            try {
                while (!ss.isClosed) {
                    val socket = ss.accept()
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val requestLine = reader.readLine() ?: return@use
                        val target = requestLine.split(' ').getOrNull(1) ?: "/"
                        if (!target.startsWith("/?")) {
                            s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        val query = target.substringAfter('?')
                        val params = query.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                        val error = params["error"]
                        val result = if (error != null) {
                            Result.failure(Exception("Google sign-in was cancelled ($error)"))
                        } else {
                            runCatching { auth.completeLogin(pending, "?$query") }
                        }
                        val ok = result.isSuccess
                        val html = """
                            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                            <title>Colab MCP Bridge</title><style>body{font-family:system-ui,sans-serif;text-align:center;padding:48px 16px}
                            a{display:inline-block;margin-top:24px;padding:14px 22px;border-radius:12px;background:#f9ab00;color:#000;text-decoration:none;font-weight:600}</style></head>
                            <body><h2>${if (ok) "Signed in to Colab MCP Bridge" else "Sign-in failed"}</h2>
                            <p>${if (ok) "You can close this tab and return to the app." else (result.exceptionOrNull()?.message ?: "").replace("<", "&lt;")}</p>
                            <a href="colabmcp://signed-in">Return to the app</a></body></html>
                        """.trimIndent().toByteArray()
                        s.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray() + html,
                        )
                        s.getOutputStream().flush()
                        onResult(result)
                        cancel()
                    }
                }
            } catch (e: SocketTimeoutException) {
                onResult(Result.failure(Exception("Sign-in timed out")))
                cancel()
            } catch (e: Exception) {
                if (server != null) Log.w("LoopbackLogin", "Login server error", e)
            }
        }
        return pending.url
    }

    fun cancel() {
        val s = server
        server = null
        runCatching { s?.close() }
    }
}
