package com.colabmcp.core.http

import com.colabmcp.core.util.Log
import kotlinx.serialization.json.JsonElement
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val body: ByteArray,
    val remote: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val bodyText: String get() = String(body, Charsets.UTF_8)

    /** Parses an application/x-www-form-urlencoded body. */
    fun form(): Map<String, String> = parseQuery(bodyText)

    /** Form fields, falling back to query parameters (some clients send both). */
    fun param(name: String): String? = form()[name] ?: query[name]

    companion object {
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            k to v
        }
    }
}

class HttpResponse(val status: Int, val body: ByteArray = ByteArray(0), contentType: String? = null) {
    val headers = LinkedHashMap<String, String>()

    init {
        if (contentType != null) headers["Content-Type"] = contentType
    }

    fun header(name: String, value: String): HttpResponse = apply { headers[name] = value }

    companion object {
        fun json(status: Int, json: JsonElement) = HttpResponse(status, json.toString().toByteArray(), "application/json")
        fun text(status: Int, text: String) = HttpResponse(status, text.toByteArray(), "text/plain; charset=utf-8")
        fun html(status: Int, html: String) = HttpResponse(status, html.toByteArray(), "text/html; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("X-Frame-Options", "DENY")
        fun redirect(location: String) = HttpResponse(302).header("Location", location).header("Cache-Control", "no-store")
        fun empty(status: Int) = HttpResponse(status)
    }
}

fun interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse
}

/**
 * Small dependency-free HTTP/1.1 server (keep-alive, Content-Length and chunked request
 * bodies). Runs on both Android and the JVM.
 */
class HttpServer(
    private val port: Int,
    private val bindAll: Boolean,
    private val handler: HttpHandler,
) {
    companion object {
        private const val TAG = "HttpServer"
        private const val MAX_BODY = 25 * 1024 * 1024
        private const val MAX_HEADER_LINE = 16 * 1024
        private val REASONS = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content", 302 to "Found",
            400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 413 to "Payload Too Large", 429 to "Too Many Requests",
            500 to "Internal Server Error", 503 to "Service Unavailable",
        )
    }

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile var boundPort: Int = port
        private set
    @Volatile var boundAddress: InetAddress? = null
        private set

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        // Bind 127.0.0.1 explicitly: on Android InetAddress.getLoopbackAddress() is ::1, and the
        // tunnels forward to 127.0.0.1 (ngrok then reported ERR_NGROK_3004).
        ss.bind(java.net.InetSocketAddress(InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1"), port), 64)
        boundPort = ss.localPort
        boundAddress = ss.inetAddress
        server = ss
        val executor = ThreadPoolExecutor(4, 64, 60, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "http-worker").apply { isDaemon = true }
        }
        pool = executor
        Thread({
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    try {
                        executor.execute { serve(s) }
                    } catch (e: Exception) {
                        s.close()
                    }
                } catch (e: IOException) {
                    if (!ss.isClosed) Log.w(TAG, "accept failed", e)
                }
            }
        }, "http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "Listening on ${if (bindAll) "0.0.0.0" else "127.0.0.1"}:$boundPort")
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        server = null
        pool = null
    }

    val isRunning: Boolean get() = server?.isClosed == false

    private fun serve(socket: Socket) {
        sockets.add(socket)
        try {
            socket.soTimeout = 75_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val remote = socket.inetAddress?.hostAddress ?: "?"
            var served = 0
            while (!socket.isClosed && served < 1000) {
                val requestLine = readLine(input) ?: break
                if (requestLine.isEmpty()) continue
                val parts = requestLine.split(' ')
                if (parts.size < 3) {
                    write(output, HttpResponse.text(400, "Bad request line"), false)
                    break
                }
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                if (headers["expect"]?.equals("100-continue", true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    output.flush()
                }
                val body = try {
                    readBody(input, headers)
                } catch (e: PayloadTooLarge) {
                    write(output, HttpResponse.text(413, "Payload too large"), false)
                    break
                }
                val target = parts[1]
                val keepAlive = parts[2] == "HTTP/1.1" && headers["connection"]?.equals("close", true) != true
                // Always answer: a dropped connection shows up as a vague gateway error in tunnels.
                val (method, resp) = try {
                    val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
                    val query = if ('?' in target) HttpRequest.parseQuery(target.substringAfter('?')) else emptyMap()
                    val req = HttpRequest(parts[0].uppercase(), path, query, headers, body, remote)
                    req.method to try {
                        handler.handle(req)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Handler error for ${req.method} ${req.path}", e)
                        HttpResponse.text(500, "Internal error: ${e.javaClass.simpleName}: ${e.message}")
                    }
                } catch (e: IllegalArgumentException) {
                    parts[0] to HttpResponse.text(400, "Malformed request URL")
                }
                Log.d(TAG, "$method ${target.substringBefore('?')} -> ${resp.status}")
                write(output, resp, keepAlive, head = method == "HEAD")
                served++
                if (!keepAlive) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: SocketException) {
        } catch (e: IOException) {
            Log.d(TAG, "connection error: ${e.message}")
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private class PayloadTooLarge : IOException()

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: throw IOException("eof in chunk size")
                val size = sizeLine.substringBefore(';').trim().toInt(16)
                if (size == 0) {
                    while (true) {
                        val trailer = readLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                if (out.size() + size > MAX_BODY) throw PayloadTooLarge()
                out.write(readExactly(input, size))
                readLine(input)
            }
            return out.toByteArray()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) throw PayloadTooLarge()
        return if (len > 0) readExactly(input, len) else ByteArray(0)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("unexpected eof")
            off += r
        }
        return buf
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > MAX_HEADER_LINE) throw IOException("header line too long")
        }
        return sb.toString("ISO-8859-1")
    }

    private fun write(out: OutputStream, resp: HttpResponse, keepAlive: Boolean, head: Boolean = false) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(REASONS[resp.status] ?: "Status").append("\r\n")
        resp.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n") }
        sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!head) out.write(resp.body)
        out.flush()
    }
}
