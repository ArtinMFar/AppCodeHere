package com.colabmcp.core.colab

import com.colabmcp.core.util.Log
import com.colabmcp.core.util.PRETTY_JSON
import com.colabmcp.core.util.arr
import com.colabmcp.core.util.bool
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.long
import com.colabmcp.core.util.obj
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.randomToken
import com.colabmcp.core.util.str
import com.colabmcp.core.util.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A Colab runtime (VM) tracked by name, like a `colab new -s NAME` session. */
class ColabSession(
    val name: String,
    val endpoint: String,
    val hardware: Hardware,
    val highMem: Boolean,
    val createdAt: Long,
    @Volatile var proxy: ProxyInfo,
    @Volatile var kernelId: String?,
) {
    @Volatile var lastUsed: Long = System.currentTimeMillis()
}

/**
 * Owns Colab sessions, their kernels and running jobs. This is the equivalent of the
 * Colab CLI's local state (sessions.json + history), kept in memory and on disk.
 */
class ColabManager(
    val auth: GoogleAuth,
    val api: ColabApi,
    private val stateFile: File?,
) {
    companion object {
        private const val TAG = "ColabManager"
        private const val MAX_JOBS = 300
    }

    /** Ping the runtime periodically so Colab does not reclaim it while the bridge runs. */
    @Volatile var keepAlive: Boolean = true

    /** Automatically stop sessions with no activity for this many minutes (0 = never). */
    @Volatile var idleStopMinutes: Int = 60

    /** Called when the VM needs the user to open a URL (Drive/GCP authorization). */
    @Volatile var onActionRequired: ((title: String, url: String) -> Unit)? = null

    /** Called whenever sessions change (for UI refresh). */
    @Volatile var onChange: (() -> Unit)? = null

    private val sessions = ConcurrentHashMap<String, ColabSession>()
    private val kernels = ConcurrentHashMap<String, KernelClient>()
    private val jobs = LinkedHashMap<String, Execution>()
    private val jobCounter = AtomicInteger(0)
    private var scheduler: ScheduledExecutorService? = null
    @Volatile private var loaded = false

    // ---------------------------------------------------------------- persistence

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val f = stateFile ?: return
            if (!f.exists()) return
            try {
                val o = parseJsonObject(f.readText())
                o.arr("sessions")?.forEach { e ->
                    val s = e as? JsonObject ?: return@forEach
                    val name = s.str("name") ?: return@forEach
                    sessions[name] = ColabSession(
                        name = name,
                        endpoint = s.str("endpoint") ?: return@forEach,
                        hardware = runCatching { Hardware.valueOf(s.str("hardware") ?: "CPU") }.getOrDefault(Hardware.CPU),
                        highMem = s.bool("high_mem") == true,
                        createdAt = s.long("created_at") ?: System.currentTimeMillis(),
                        proxy = ProxyInfo("", "", 0),
                        kernelId = s.str("kernel_id"),
                    ).also { it.lastUsed = s.long("last_used") ?: it.createdAt }
                }
                jobCounter.set((o.long("job_counter") ?: 0).toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Could not read session state", e)
            }
        }
    }

    private fun save() {
        val f = stateFile ?: return
        try {
            val arr = sessions.values.sortedBy { it.createdAt }.map {
                jsonOf(
                    "name" to it.name,
                    "endpoint" to it.endpoint,
                    "hardware" to it.hardware.name,
                    "high_mem" to it.highMem,
                    "created_at" to it.createdAt,
                    "last_used" to it.lastUsed,
                    "kernel_id" to it.kernelId,
                )
            }
            f.parentFile?.mkdirs()
            f.writeText(PRETTY_JSON.encodeToString(JsonObject.serializer(), JsonObject(mapOf("sessions" to JsonArray(arr), "job_counter" to JsonPrimitive(jobCounter.get())))))
        } catch (e: Exception) {
            Log.w(TAG, "Could not save session state", e)
        }
        onChange?.invoke()
    }

    // ---------------------------------------------------------------- sessions

    /** Reconciles local sessions with the backend (like `colab sessions`). */
    fun sync(): List<ColabSession> {
        load()
        val assignments = api.listAssignments()
        val byEndpoint = assignments.associateBy { it.endpoint }
        var changed = false
        for ((name, s) in sessions.toMap()) {
            val a = byEndpoint[s.endpoint]
            if (a == null) {
                Log.i(TAG, "Session '$name' no longer exists on Colab; removing")
                sessions.remove(name)
                kernels.remove(name)?.close()
                changed = true
            } else {
                s.proxy = a.proxy
            }
        }
        val known = sessions.values.map { it.endpoint }.toSet()
        for (a in assignments) {
            if (a.endpoint in known) continue
            // A runtime created elsewhere (Colab web UI, CLI): adopt it under a readable name.
            val name = uniqueName(a.hardware.name.lowercase().replace('_', '-'))
            sessions[name] = ColabSession(name, a.endpoint, a.hardware, a.highMem, System.currentTimeMillis(), a.proxy, null)
            changed = true
        }
        if (changed) save()
        return list()
    }

    fun list(): List<ColabSession> {
        load()
        return sessions.values.sortedBy { it.createdAt }
    }

    private fun uniqueName(base: String): String {
        if (!sessions.containsKey(base)) return base
        var i = 2
        while (sessions.containsKey("$base-$i")) i++
        return "$base-$i"
    }

    fun create(name: String?, hardware: Hardware, highMem: Boolean): ColabSession {
        load()
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() }?.replace(Regex("[^A-Za-z0-9_.-]"), "-")
        if (wanted != null && sessions.containsKey(wanted)) throw IllegalArgumentException("A session named '$wanted' already exists.")
        Log.i(TAG, "Allocating ${hardware.label}${if (highMem) " (high-RAM)" else ""} runtime...")
        val a = api.assign(hardware, highMem)
        val finalName = wanted ?: uniqueName(hardware.name.lowercase().replace('_', '-'))
        val s = ColabSession(finalName, a.endpoint, a.hardware.takeIf { it != Hardware.CPU } ?: hardware, highMem, System.currentTimeMillis(), a.proxy, null)
        sessions[finalName] = s
        save()
        Log.i(TAG, "Session '$finalName' ready (${s.hardware.label})")
        ensureScheduler()
        return s
    }

    fun stop(name: String) {
        load()
        val s = sessions[name] ?: throw IllegalArgumentException("No session named '$name'. Sessions: ${sessions.keys.joinToString().ifEmpty { "none" }}")
        kernels.remove(name)?.let {
            initializedKernels.remove(it)
            it.close()
        }
        try {
            api.unassign(s.endpoint)
        } catch (e: ColabException) {
            if (e.status != 404) throw e
        }
        sessions.remove(name)
        save()
        Log.i(TAG, "Session '$name' stopped")
    }

    fun stopAll(): List<String> {
        val names = sync().map { it.name }
        names.forEach { runCatching { stop(it) }.onFailure { e -> Log.w(TAG, "Stopping $it failed", e) } }
        return names
    }

    /** Picks the named session, or the only/most recently used one when no name is given. */
    fun resolve(name: String?): ColabSession {
        load()
        if (!name.isNullOrBlank()) {
            sessions[name]?.let { return it }
            sync()
            return sessions[name] ?: throw IllegalArgumentException("No session named '$name'. Active sessions: ${sessions.keys.joinToString().ifEmpty { "none (create one with colab_create_session)" }}")
        }
        if (sessions.isEmpty()) sync()
        return when (sessions.size) {
            0 -> throw IllegalArgumentException("There is no active Colab session. Create one first with colab_create_session (choose CPU or a GPU).")
            1 -> sessions.values.first()
            else -> sessions.values.maxBy { it.lastUsed }
        }
    }

    fun proxyFor(s: ColabSession): ProxyInfo {
        if (s.proxy.token.isEmpty() || s.proxy.expiresAtMillis - System.currentTimeMillis() < 5 * 60 * 1000) {
            val a = api.listAssignments().firstOrNull { it.endpoint == s.endpoint }
                ?: run {
                    sessions.remove(s.name)
                    kernels.remove(s.name)?.close()
                    save()
                    throw ColabException("Session '${s.name}' no longer exists on Colab (it was stopped or reclaimed). Create a new one.")
                }
            s.proxy = a.proxy
        }
        return s.proxy
    }

    private val initializedKernels = ConcurrentHashMap.newKeySet<KernelClient>()

    /** Kernel client for a session; [prepare] also makes sure user code runs from /content. */
    fun kernel(s: ColabSession, prepare: Boolean = false): KernelClient {
        val kc = kernels.computeIfAbsent(s.name) {
            KernelClient(
                proxy = { proxyFor(s) },
                kernelId = s.kernelId,
                colabRequestHandler = { kc, msg, exec -> handleColabRequest(s, kc, msg, exec) },
            )
        }
        if (kc.kernelId == null) kc.ensureKernel()
        if (prepare && initializedKernels.add(kc)) {
            // Like the Colab CLI, run user code from /content.
            val init = Execution("init", s.name, "import os as _os\nif _os.path.isdir('/content'): _os.chdir('/content')", "init")
            try {
                kc.execute(init, allowStdin = false, silent = true)
            } catch (e: Exception) {
                initializedKernels.remove(kc)
                throw e
            }
        }
        if (s.kernelId != kc.kernelId) {
            s.kernelId = kc.kernelId
            save()
        }
        return kc
    }

    fun contents(s: ColabSession): ContentsClient = ContentsClient({ proxyFor(s) })

    // ---------------------------------------------------------------- execution

    fun newJobId(): String = "job-${jobCounter.incrementAndGet()}-${randomToken(3).lowercase().replace(Regex("[^a-z0-9]"), "x")}"

    /** Starts code on the session's kernel and waits up to [waitMs] for it to finish. */
    fun run(
        sessionName: String?,
        code: String,
        label: String = "python",
        waitMs: Long = 60_000,
        allowStdin: Boolean = true,
        displayCode: String = code,
    ): Execution {
        val s = resolve(sessionName)
        s.lastUsed = System.currentTimeMillis()
        val exec = Execution(newJobId(), s.name, code, label, displayCode)
        val kc = kernel(s, prepare = true)
        synchronized(jobs) {
            jobs[exec.jobId] = exec
            while (jobs.size > MAX_JOBS) jobs.remove(jobs.keys.first())
        }
        kc.execute(exec, allowStdin)
        ensureScheduler()
        // Return early when the user has to act (input prompt or authorization link).
        val deadline = System.currentTimeMillis() + waitMs
        while (!exec.finished && exec.actionUrl == null && exec.state != Execution.State.WAITING_FOR_INPUT) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            exec.await(minOf(left, 500))
        }
        s.lastUsed = System.currentTimeMillis()
        return exec
    }

    fun job(id: String): Execution = synchronized(jobs) { jobs[id.trim()] }
        ?: throw IllegalArgumentException("Unknown job id '$id'. Recent jobs: ${recentJobs(null, 5).joinToString { it.jobId }.ifEmpty { "none" }}")

    fun recentJobs(session: String?, limit: Int): List<Execution> = synchronized(jobs) {
        jobs.values.filter { session == null || it.session == session }.takeLast(limit)
    }

    fun sendInput(jobId: String, value: String): Execution {
        val exec = job(jobId)
        if (exec.state != Execution.State.WAITING_FOR_INPUT) throw IllegalStateException("Job $jobId is not waiting for input (state: ${exec.state.label}).")
        val s = resolve(exec.session)
        kernel(s).sendInput(exec, value)
        return exec
    }

    fun interrupt(sessionName: String?): ColabSession {
        val s = resolve(sessionName)
        kernel(s).interrupt()
        return s
    }

    fun restartKernel(sessionName: String?): ColabSession {
        val s = resolve(sessionName)
        kernel(s).restart()
        return s
    }

    // ---------------------------------------------------------------- VM-side Google auth (Drive / GCP)

    private val ephemeralAuthTypes = setOf("dfs_ephemeral", "auth_user_ephemeral")

    private fun handleColabRequest(s: ColabSession, kc: KernelClient, msg: JsonObject, exec: Execution?): Boolean {
        val request = msg.obj("content")?.obj("request") ?: return false
        val authType = request.str("authType") ?: return false
        if (authType !in ephemeralAuthTypes && !authType.endsWith("_ephemeral")) return false
        Thread {
            try {
                var r = api.propagateCredentials(s.endpoint, authType, dryRun = true)
                if (!r.success) {
                    val url = r.authorizationUrl
                    if (url == null) {
                        exec?.addNote("Colab refused to propagate credentials ($authType).")
                        return@Thread
                    }
                    val what = if (authType.startsWith("dfs")) "Google Drive access" else "Google Cloud access"
                    exec?.actionUrl = url
                    exec?.addNote("Waiting up to 10 minutes for the user to allow $what using the authorization link.")
                    onActionRequired?.invoke("Colab needs $what", url)
                    val deadline = System.currentTimeMillis() + 10 * 60 * 1000
                    while (System.currentTimeMillis() < deadline && exec?.finished != true) {
                        Thread.sleep(5000)
                        r = api.propagateCredentials(s.endpoint, authType, dryRun = true)
                        if (r.success) break
                    }
                    if (!r.success) {
                        exec?.addNote("Timed out waiting for authorization.")
                        return@Thread
                    }
                    exec?.actionUrl = null
                }
                val done = api.propagateCredentials(s.endpoint, authType, dryRun = false)
                if (!done.success) Log.w(TAG, "Credential propagation did not report success")
                kc.sendColabReply(msg)
                Log.i(TAG, "Propagated $authType credentials to '${s.name}'")
            } catch (e: Exception) {
                Log.w(TAG, "Credential propagation failed", e)
                exec?.addNote("Credential propagation failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    // ---------------------------------------------------------------- keep-alive & idle shutdown

    private fun ensureScheduler() = synchronized(this) {
        if (scheduler != null) return@synchronized
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "colab-maintenance").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ maintenance() }, 1, 4, TimeUnit.MINUTES)
        }
    }

    fun startMaintenance() = ensureScheduler()

    fun shutdown() {
        synchronized(this) {
            scheduler?.shutdownNow()
            scheduler = null
        }
        kernels.values.forEach { it.close() }
        kernels.clear()
    }

    private fun maintenance() {
        try {
            if (!auth.isSignedIn || sessions.isEmpty()) return
            val now = System.currentTimeMillis()
            for (s in sessions.values.toList()) {
                val kc = kernels[s.name]
                val busy = kc?.isBusy == true
                val lastActive = maxOf(s.lastUsed, kc?.lastActivity ?: 0)
                if (busy) s.lastUsed = now
                val idleMin = (now - lastActive) / 60_000
                if (!busy && idleStopMinutes > 0 && idleMin >= idleStopMinutes) {
                    Log.i(TAG, "Session '${s.name}' idle for ${idleMin}m; stopping it to save compute units")
                    runCatching { stop(s.name) }.onFailure { Log.w(TAG, "Auto-stop failed", it) }
                    continue
                }
                if (keepAlive) api.keepAlive(s.endpoint)
            }
            sync()
        } catch (e: Exception) {
            Log.w(TAG, "Maintenance failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- notebook export

    fun notebookJson(sessionName: String?): String {
        val s = resolve(sessionName)
        val cells = recentJobs(s.name, MAX_JOBS).map { exec ->
            val outputs = exec.outputs().map { o ->
                when (o) {
                    is Output.Stream -> jsonOf("output_type" to "stream", "name" to o.name, "text" to o.text.toString())
                    is Output.Data -> jsonOf(
                        "output_type" to if (o.isResult) "execute_result" else "display_data",
                        "data" to o.data,
                        "metadata" to emptyMap<String, String>(),
                    ).let { if (o.isResult) JsonObject(it + ("execution_count" to toJson(exec.executionCount))) else it }
                    is Output.Error -> jsonOf("output_type" to "error", "ename" to o.ename, "evalue" to o.evalue, "traceback" to o.traceback)
                }
            }
            jsonOf(
                "cell_type" to "code",
                "execution_count" to exec.executionCount,
                "metadata" to emptyMap<String, String>(),
                "source" to exec.displayCode,
                "outputs" to outputs,
            ).let { if (exec.executionCount == null) JsonObject(it + ("execution_count" to kotlinx.serialization.json.JsonNull)) else it }
        }
        val nb = jsonOf(
            "nbformat" to 4,
            "nbformat_minor" to 5,
            "metadata" to mapOf(
                "kernelspec" to mapOf("name" to "python3", "display_name" to "Python 3", "language" to "python"),
                "language_info" to mapOf("name" to "python"),
                "accelerator" to s.hardware.api,
                "colab" to mapOf("provenance" to emptyList<String>()),
            ),
            "cells" to cells,
        )
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), nb)
    }
}
