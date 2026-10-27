package com.colabmcp.core.colab

import com.colabmcp.core.util.Http
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.arr
import com.colabmcp.core.util.bool
import com.colabmcp.core.util.int
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.obj
import com.colabmcp.core.util.parseJson
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed class Output {
    data class Stream(val name: String, val text: StringBuilder) : Output()
    data class Data(val data: Map<String, String>, val displayId: String?, val isResult: Boolean) : Output()
    data class Error(val ename: String, val evalue: String, val traceback: List<String>) : Output()
}

/** One cell execution on a Colab kernel. Outputs stream in while the code runs. */
class Execution(
    val jobId: String,
    val session: String,
    val code: String,
    val label: String,
    /** What to show in history/notebooks (e.g. the bash script rather than its Python wrapper). */
    val displayCode: String = code,
) {
    @Volatile var msgId: String = ""
    @Volatile var state: State = State.QUEUED
    @Volatile var executionCount: Int? = null
    @Volatile var inputPrompt: String? = null
    @Volatile var inputParent: JsonObject? = null
    /** Set when the VM needs the user to approve something in a browser (e.g. Drive access). */
    @Volatile var actionUrl: String? = null
    val startedAt: Long = System.currentTimeMillis()
    @Volatile var finishedAt: Long = 0
    private val outputs = ArrayList<Output>()
    private var clearPending = false
    private val latch = CountDownLatch(1)
    internal var gotReply = false
    internal var gotIdle = false

    enum class State(val label: String, val finished: Boolean) {
        QUEUED("queued", false),
        RUNNING("running", false),
        WAITING_FOR_INPUT("waiting_for_input", false),
        OK("ok", true),
        ERROR("error", true),
        ABORTED("aborted", true),
        DISCONNECTED("disconnected", true),
    }

    @Volatile private var completed = false

    /** True once the kernel has fully finished this cell (reply + idle received). */
    val finished: Boolean get() = completed

    fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)

    fun outputs(): List<Output> = synchronized(this) { outputs.map { if (it is Output.Stream) it.copy(text = StringBuilder(it.text)) else it } }

    internal fun finish(newState: State) {
        if (completed) return
        completed = true
        state = newState
        finishedAt = System.currentTimeMillis()
        latch.countDown()
    }

    internal fun addStream(name: String, text: String) = synchronized(this) {
        applyPendingClear()
        val last = outputs.lastOrNull()
        if (last is Output.Stream && last.name == name) last.text.append(text) else outputs.add(Output.Stream(name, StringBuilder(text)))
        trimIfHuge()
    }

    internal fun addData(data: Map<String, String>, displayId: String?, isResult: Boolean, update: Boolean) = synchronized(this) {
        if (update && displayId != null) {
            val idx = outputs.indexOfFirst { it is Output.Data && it.displayId == displayId }
            if (idx >= 0) {
                outputs[idx] = Output.Data(data, displayId, isResult)
                return@synchronized
            }
        }
        applyPendingClear()
        outputs.add(Output.Data(data, displayId, isResult))
        trimIfHuge()
    }

    internal fun addError(ename: String, evalue: String, tb: List<String>) = synchronized(this) {
        applyPendingClear()
        outputs.add(Output.Error(ename, evalue, tb))
    }

    internal fun addNote(text: String) = addStream("stderr", "[colab-mcp-bridge] $text\n")

    internal fun clear(wait: Boolean) = synchronized(this) {
        if (wait) clearPending = true else outputs.clear()
    }

    private fun applyPendingClear() {
        if (clearPending) {
            outputs.clear()
            clearPending = false
        }
    }

    /** Keeps memory bounded for chatty long-running jobs (training loops etc.). */
    private fun trimIfHuge() {
        val maxChars = 2_000_000
        var total = outputs.sumOf { if (it is Output.Stream) it.text.length else 0 }
        if (total <= maxChars) return
        for (o in outputs) {
            if (o is Output.Stream && total > maxChars) {
                val drop = minOf(o.text.length - 1000, total - maxChars).coerceAtLeast(0)
                o.text.delete(0, drop)
                o.text.insert(0, "...[older output dropped]...\n")
                total -= drop
            }
        }
        while (outputs.count { it is Output.Data } > 50) {
            outputs.removeAt(outputs.indexOfFirst { it is Output.Data })
        }
    }
}

/**
 * Jupyter kernel client over Colab's runtime proxy (WebSocket + REST), equivalent to
 * jupyter_kernel_client as used by the Colab CLI.
 */
class KernelClient(
    private val proxy: () -> ProxyInfo,
    var kernelId: String?,
    private val colabRequestHandler: (KernelClient, JsonObject, Execution?) -> Boolean,
    private val http: OkHttpClient = Http.client,
) {
    companion object {
        private const val TAG = "Kernel"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    val sessionId: String = UUID.randomUUID().toString()
    private val lock = Object()
    private var ws: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closedByUser = false
    private val byMsgId = ConcurrentHashMap<String, Execution>()
    @Volatile var lastActivity: Long = System.currentTimeMillis()
        private set

    private fun restRequest(path: String): Request.Builder {
        val p = proxy()
        val url = (p.url.trimEnd('/') + path).toHttpUrl().newBuilder()
            .addQueryParameter("authuser", "0")
            .build()
        return Request.Builder().url(url)
            .header("Authorization", "Bearer ${p.token}")
            .header("X-Colab-Runtime-Proxy-Token", p.token)
            .header("X-Colab-Client-Agent", ColabApi.CLIENT_AGENT)
    }

    private fun rest(builder: Request.Builder): String {
        http.newCall(builder.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ColabException("Runtime returned HTTP ${r.code} for ${r.request.url.encodedPath}", r.code, body)
            return body
        }
    }

    /** Reuses the session's kernel when it still exists, otherwise starts a new python3 kernel. */
    fun ensureKernel(): String = synchronized(lock) {
        val existing = try {
            (parseJson(rest(restRequest("/api/kernels").get())) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("id") } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Listing kernels failed: ${e.message}")
            emptyList()
        }
        val id = kernelId?.takeIf { it in existing } ?: existing.firstOrNull() ?: run {
            val body = jsonOf("name" to "python3").toString().toRequestBody(JSON_TYPE)
            val created = parseJson(rest(restRequest("/api/kernels").post(body))) as JsonObject
            created.str("id") ?: throw ColabException("Kernel start returned no id")
        }
        if (id != kernelId) {
            Log.i(TAG, "Using kernel $id")
            kernelId = id
            disconnect()
        }
        id
    }

    private val connectLock = Any()

    fun ensureConnected() {
        synchronized(connectLock) { connectLocked() }
    }

    private fun connectLocked() {
        synchronized(lock) {
            if (connected && ws != null) return
        }
        val id = ensureKernel()
        val p = proxy()
        val wsUrl = p.url.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/api/kernels/$id/channels?session_id=$sessionId&token=${java.net.URLEncoder.encode(p.token, "UTF-8")}"
        val opened = CountDownLatch(1)
        var failure: Throwable? = null
        val request = Request.Builder().url(wsUrl)
            .header("X-Colab-Runtime-Proxy-Token", p.token)
            .header("X-Colab-Client-Agent", ColabApi.CLIENT_AGENT)
            .header("Authorization", "Bearer ${p.token}")
            .build()
        closedByUser = false
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.w(TAG, "Bad kernel message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDisconnected(webSocket, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                opened.countDown()
                onDisconnected(webSocket, t)
            }
        })
        if (!opened.await(60, TimeUnit.SECONDS) || !connected) {
            socket.cancel()
            throw ColabException("Could not connect to the Colab kernel: ${failure?.message ?: "timeout"}")
        }
        synchronized(lock) { ws = socket }
    }

    private fun onDisconnected(socket: WebSocket, error: Throwable?) {
        synchronized(lock) {
            if (ws != null && ws !== socket) return
            ws = null
            connected = false
        }
        if (closedByUser) return
        val pending = byMsgId.values.filter { !it.finished }
        Log.w(TAG, "Kernel socket closed (${error?.message ?: "normal"}); ${pending.size} running job(s)")
        if (pending.isEmpty()) return
        // Reconnect with the same session id so the server can replay buffered output.
        Thread {
            for (attempt in 1..5) {
                try {
                    Thread.sleep(2000L * attempt)
                    ensureConnected()
                    return@Thread
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                }
            }
            pending.forEach {
                it.addNote("Lost connection to the kernel; the code may still be running on the VM.")
                it.finish(Execution.State.DISCONNECTED)
                byMsgId.remove(it.msgId)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun message(msgType: String, channel: String, content: JsonObject, parent: JsonObject? = null): Pair<String, String> {
        val msgId = UUID.randomUUID().toString().replace("-", "")
        val header = jsonOf(
            "msg_id" to msgId,
            "username" to "colab-mcp",
            "session" to sessionId,
            "date" to Instant.now().toString(),
            "msg_type" to msgType,
            "version" to "5.3",
        )
        val msg = JsonObject(
            mapOf(
                "header" to header,
                "parent_header" to (parent ?: JsonObject(emptyMap())),
                "metadata" to JsonObject(emptyMap()),
                "content" to content,
                "buffers" to JsonArray(emptyList()),
                "channel" to JsonPrimitive(channel),
            ),
        )
        return msgId to msg.toString()
    }

    private fun send(text: String) {
        val socket = synchronized(lock) { ws } ?: throw ColabException("Kernel socket is not connected")
        if (!socket.send(text)) throw ColabException("Failed to send to kernel")
    }

    fun execute(exec: Execution, allowStdin: Boolean = true, silent: Boolean = false) {
        ensureConnected()
        val content = jsonOf(
            "code" to exec.code,
            "silent" to silent,
            "store_history" to !silent,
            "user_expressions" to emptyMap<String, String>(),
            "allow_stdin" to allowStdin,
            "stop_on_error" to true,
        )
        val (msgId, text) = message("execute_request", "shell", content)
        exec.msgId = msgId
        byMsgId[msgId] = exec
        lastActivity = System.currentTimeMillis()
        try {
            send(text)
        } catch (e: Exception) {
            byMsgId.remove(msgId)
            throw e
        }
    }

    fun sendInput(exec: Execution, value: String) {
        val parent = exec.inputParent ?: throw IllegalStateException("This job is not waiting for input")
        val (_, text) = message("input_reply", "stdin", jsonOf("value" to value), parent)
        send(text)
        exec.inputPrompt = null
        exec.inputParent = null
        exec.state = Execution.State.RUNNING
    }

    /** Replies to a colab_request (e.g. after Drive credentials were propagated). */
    fun sendColabReply(request: JsonObject, extra: Map<String, Any?> = emptyMap()) {
        val colabMsgId = request.obj("metadata")?.get("colab_msg_id")
        val value = JsonObject(mapOf("type" to JsonPrimitive("colab_reply"), "colab_msg_id" to (colabMsgId ?: JsonPrimitive(""))) + extra.mapValues { com.colabmcp.core.util.toJson(it.value) })
        val (_, text) = message("input_reply", "stdin", JsonObject(mapOf("value" to value)), request.obj("header"))
        send(text)
    }

    private fun handleMessage(text: String) {
        val msg = parseJson(text) as? JsonObject ?: return
        val header = msg.obj("header")
        val msgType = header?.str("msg_type") ?: msg.str("msg_type") ?: return
        val parentId = msg.obj("parent_header")?.str("msg_id")
        val content = msg.obj("content") ?: JsonObject(emptyMap())
        val exec = parentId?.let { byMsgId[it] }
        lastActivity = System.currentTimeMillis()
        Log.d(TAG, "<- ${msg.str("channel")}/$msgType parent=${parentId?.take(8)} ${content.toString().take(120)}")

        if (msgType == "colab_request") {
            // These arrive without a parent header; attribute them to the cell that is running.
            val owner = exec ?: byMsgId.values.filter { !it.finished }.maxByOrNull { it.startedAt }
            if (!colabRequestHandler(this, msg, owner)) {
                val type = content.obj("request")?.str("authType") ?: content.str("type") ?: "unknown"
                owner?.addNote("The code requested a browser-only Colab feature ($type) that is not available through the bridge.")
                Log.w(TAG, "Unhandled colab_request: ${text.take(300)}")
            }
            return
        }
        if (exec == null) return

        when (msgType) {
            "status" -> {
                when (content.str("execution_state")) {
                    "busy" -> if (exec.state == Execution.State.QUEUED) exec.state = Execution.State.RUNNING
                    "idle" -> {
                        exec.gotIdle = true
                        maybeFinish(exec)
                    }
                }
            }
            "execute_input" -> {
                exec.executionCount = content.int("execution_count") ?: exec.executionCount
                if (exec.state == Execution.State.QUEUED) exec.state = Execution.State.RUNNING
            }
            "stream" -> exec.addStream(content.str("name") ?: "stdout", content.str("text") ?: "")
            "execute_result", "display_data", "update_display_data" -> {
                val data = content.obj("data")?.mapNotNull { (k, v) ->
                    when (v) {
                        is JsonPrimitive -> k to v.content
                        is JsonArray -> k to v.joinToString("") { (it as? JsonPrimitive)?.content ?: it.toString() }
                        else -> k to v.toString()
                    }
                }?.toMap() ?: emptyMap()
                val displayId = content.obj("transient")?.str("display_id")
                if (msgType == "execute_result") exec.executionCount = content.int("execution_count") ?: exec.executionCount
                exec.addData(data, displayId, msgType == "execute_result", msgType == "update_display_data")
            }
            "error" -> exec.addError(
                content.str("ename") ?: "Error",
                content.str("evalue") ?: "",
                content.arr("traceback")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            )
            "clear_output" -> exec.clear(content.bool("wait") == true)
            "input_request" -> {
                exec.inputPrompt = content.str("prompt") ?: ""
                exec.inputParent = header
                exec.state = Execution.State.WAITING_FOR_INPUT
            }
            "execute_reply" -> {
                exec.gotReply = true
                exec.executionCount = content.int("execution_count") ?: exec.executionCount
                when (content.str("status")) {
                    "error" -> {
                        if (exec.outputs().none { it is Output.Error }) {
                            exec.addError(content.str("ename") ?: "Error", content.str("evalue") ?: "", content.arr("traceback")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList())
                        }
                        exec.state = Execution.State.ERROR
                    }
                    "aborted" -> exec.state = Execution.State.ABORTED
                }
                maybeFinish(exec)
                // Some proxies drop the trailing idle status; don't wait forever for it.
                if (!exec.finished) {
                    Thread {
                        Thread.sleep(3000)
                        exec.gotIdle = true
                        maybeFinish(exec)
                    }.apply { isDaemon = true }.start()
                }
            }
        }
    }

    private fun maybeFinish(exec: Execution) {
        if (!exec.gotReply || !exec.gotIdle || exec.finished) return
        val final = when (exec.state) {
            Execution.State.ERROR -> Execution.State.ERROR
            Execution.State.ABORTED -> Execution.State.ABORTED
            else -> if (exec.outputs().any { it is Output.Error }) Execution.State.ERROR else Execution.State.OK
        }
        exec.finish(final)
        byMsgId.remove(exec.msgId)
    }

    fun interrupt() {
        val id = kernelId ?: ensureKernel()
        rest(restRequest("/api/kernels/$id/interrupt").post(ByteArray(0).toRequestBody(null)))
    }

    fun restart() {
        val id = kernelId ?: ensureKernel()
        rest(restRequest("/api/kernels/$id/restart").post(ByteArray(0).toRequestBody(null)))
        byMsgId.values.forEach {
            it.addNote("Kernel restarted.")
            it.finish(Execution.State.ABORTED)
        }
        byMsgId.clear()
    }

    fun shutdownKernel() {
        val id = kernelId ?: return
        try {
            rest(restRequest("/api/kernels/$id").delete())
        } catch (e: Exception) {
            Log.d(TAG, "Kernel shutdown failed: ${e.message}")
        }
    }

    fun disconnect() {
        val socket = synchronized(lock) {
            val s = ws
            ws = null
            connected = false
            s
        }
        closedByUser = true
        socket?.close(1000, "bye")
    }

    fun close() {
        disconnect()
        byMsgId.values.forEach { it.finish(Execution.State.DISCONNECTED) }
        byMsgId.clear()
    }

    val isBusy: Boolean get() = byMsgId.values.any { !it.finished }
}
