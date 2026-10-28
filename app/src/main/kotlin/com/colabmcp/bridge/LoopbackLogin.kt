package com.colabmcp.bridge

import com.colabmcp.core.colab.GoogleAuth
import com.colabmcp.core.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Google sign-in through the phone's browser with a loopback redirect
 * (http://127.0.0.1:PORT/), the same flow `gcloud auth application-default login` uses.
 *
 * While the browser is in front, Android may block the app's network (Data Saver, background
 * restrictions, a fresh install without the battery exemption). The browser can still deliver
 * the code over loopback, so when the token exchange cannot reach Google the code is kept and
 * the exchange is finished by [finishDeferred] once the app is back in the foreground.
 */
class LoopbackLogin(private val auth: GoogleAuth) {
    companion object {
        private const val TAG = "LoopbackLogin"

        /** Kept at process level so it survives the activity being recreated while in the browser. */
        @Volatile private var deferred: Pair<GoogleAuth.PendingLogin, String>? = null
        private val finishing = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private var server: ServerSocket? = null

    /** Returns the URL to open in the browser; [onResult] is called on a background thread. */
    fun start(onResult: (Result<GoogleAuth.Account>) -> Unit): String {
        cancel()
        deferred = null
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
                        // A network error means the code never reached Google, so it is still valid.
                        val offline = result.exceptionOrNull() is IOException
                        if (offline) {
                            deferred = pending to "?$query"
                            Log.i(TAG, "No network for the app in the background; finishing sign-in when the app is reopened")
                        }
                        val (title, text) = when {
                            result.isSuccess -> "Signed in to Colab MCP Bridge" to "You can close this tab and return to the app."
                            offline -> "Almost done" to "Tap the button to return to the app; it finishes signing in there."
                            else -> "Sign-in failed" to (result.exceptionOrNull()?.message ?: "").replace("<", "&lt;")
                        }
                        val html = """
                            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                            <title>Colab MCP Bridge</title><style>body{font-family:system-ui,sans-serif;text-align:center;padding:48px 16px}
                            a{display:inline-block;margin-top:24px;padding:14px 22px;border-radius:12px;background:#f9ab00;color:#000;text-decoration:none;font-weight:600}</style></head>
                            <body><h2>$title</h2><p>$text</p>
                            <a href="colabmcp://signed-in">Return to the app</a></body></html>
                        """.trimIndent().toByteArray()
                        s.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray() + html,
                        )
                        s.getOutputStream().flush()
                        if (!offline) onResult(result)
                        cancel()
                    }
                }
            } catch (e: SocketTimeoutException) {
                onResult(Result.failure(Exception("Sign-in timed out")))
                cancel()
            } catch (e: Exception) {
                if (server != null) Log.w(TAG, "Login server error", e)
            }
        }
        return pending.url
    }

    /** Completes a sign-in whose token exchange was blocked while the app was in the background. */
    fun finishDeferred(onResult: (Result<GoogleAuth.Account>) -> Unit) {
        val (pending, response) = deferred ?: return
        if (!finishing.compareAndSet(false, true)) return
        thread(name = "finish-login") {
            try {
                finishLocked(pending, response, onResult)
            } finally {
                finishing.set(false)
            }
        }
    }

    private fun finishLocked(pending: GoogleAuth.PendingLogin, response: String, onResult: (Result<GoogleAuth.Account>) -> Unit) {
        var result = runCatching { auth.completeLogin(pending, response) }
        // The network can take a moment to come back after the app returns to the foreground.
        for (attempt in 1..3) {
            if (result.exceptionOrNull() !is IOException) break
            Thread.sleep(1500L * attempt)
            result = runCatching { auth.completeLogin(pending, response) }
        }
        if (result.exceptionOrNull() is IOException) {
            onResult(Result.failure(Exception("Still can't reach Google from the app: ${result.exceptionOrNull()?.message}. Check that the app may use data (Data Saver / background data), then reopen it.")))
            return
        }
        deferred = null
        onResult(result)
    }

    fun cancel() {
        val s = server
        server = null
        runCatching { s?.close() }
    }
}
