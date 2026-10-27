package com.colabmcp.core.mcp

import com.colabmcp.core.colab.ColabException
import com.colabmcp.core.colab.ColabManager
import com.colabmcp.core.colab.Execution
import com.colabmcp.core.colab.Hardware
import com.colabmcp.core.colab.OutputFormatter
import com.colabmcp.core.util.bool
import com.colabmcp.core.util.int
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** MCP tools that mirror what the Colab CLI (and the Colab UI) can do. */
class ColabTools(private val m: ColabManager) {

    companion object {
        const val INSTRUCTIONS = """This server controls the user's own Google Colab account (via the Colab MCP Bridge app on their phone).
Typical flow:
1. colab_list_sessions to see running runtimes (VMs).
2. If none fits, colab_create_session with hardware CPU, T4, L4, G4, A100, H100, TPU_V5E1 or TPU_V6E1 (GPU/TPU availability depends on the user's Colab plan; fall back to T4 or CPU if refused).
3. colab_run_code runs Python like a notebook cell; variables and imports persist between calls on the same session. Use language="bash" for shell commands. Plots/images are returned as images.
4. Long jobs return a job_id while still running; poll with colab_get_job_output, stop with colab_interrupt.
5. Files live under /content (colab_list_files / colab_read_file / colab_write_file). Use colab_mount_drive for Google Drive.
6. Running runtimes consume the user's compute units: call colab_stop_session when the work is done, unless the user wants to keep it."""

        private val HARDWARE = listOf("CPU", "T4", "L4", "G4", "A100", "H100", "TPU_V5E1", "TPU_V6E1")
        private const val DEFAULT_WAIT = 45
        private const val MAX_WAIT = 290

        private val SESSION_PROP = "session" to mapOf(
            "type" to "string",
            "description" to "Session name from colab_list_sessions. Optional when only one session exists (defaults to the most recently used).",
        )
    }

    private fun JsonObject.waitMs(key: String, default: Int): Long = ((int(key) ?: default).coerceIn(0, MAX_WAIT)) * 1000L

    private fun result(exec: Execution, maxChars: Int = 20_000): ToolResult {
        val f = OutputFormatter.format(exec, maxChars)
        val content = ArrayList<JsonObject>()
        content.add(ToolResult.textContent(f.text))
        f.images.forEach { content.add(ToolResult.imageContent(it.base64, it.mimeType)) }
        return ToolResult(content, isError = exec.state == Execution.State.ERROR)
    }

    private fun bashWrapper(script: String): String {
        val literal = JsonPrimitive(script).toString()
        return """
import subprocess as _sp
_p = _sp.Popen(['bash', '-c', $literal], stdout=_sp.PIPE, stderr=_sp.STDOUT, text=True, bufsize=1, cwd='/content')
try:
    for _line in _p.stdout:
        print(_line, end='', flush=True)
    _rc = _p.wait()
except KeyboardInterrupt:
    _p.kill()
    raise
print(f'[exit code {_rc}]')
        """.trimIndent()
    }

    fun all(): List<Tool> = listOf(
        Tool(
            "colab_account", "Colab account status",
            "Shows the signed-in Google account, Colab compute-unit balance, hourly usage rate and active runtimes.",
            schema(), readOnly = true,
        ) {
            val email = m.auth.account?.email ?: "unknown"
            val ccu = m.api.ccuInfo()
            val sessions = m.sync()
            ToolResult.text(
                buildString {
                    append("Signed in as $email\n")
                    append("Compute units: %.2f available, current usage %.2f/hour\n".format(ccu.balance, ccu.ratePerHour))
                    append("Active runtimes: ${sessions.size}\n")
                    sessions.forEach { append(" - ${it.name}: ${it.hardware.label}${if (it.highMem) " (High-RAM)" else ""}\n") }
                },
            )
        },
        Tool(
            "colab_list_sessions", "List Colab runtimes",
            "Lists the user's active Colab runtimes (VMs) with hardware, idle/busy state and recent jobs.",
            schema(), readOnly = true,
        ) {
            val sessions = m.sync()
            if (sessions.isEmpty()) return@Tool ToolResult.text("No active Colab runtimes. Create one with colab_create_session.")
            val now = System.currentTimeMillis()
            ToolResult.text(
                sessions.joinToString("\n") { s ->
                    val jobs = m.recentJobs(s.name, 3)
                    val running = jobs.filter { !it.finished }
                    buildString {
                        append("${s.name}: ${s.hardware.label}${if (s.highMem) " High-RAM" else ""}, ")
                        append(if (running.isNotEmpty()) "BUSY (${running.joinToString { it.jobId }})" else "idle")
                        append(", up ${(now - s.createdAt) / 60000} min, last used ${(now - s.lastUsed) / 60000} min ago")
                        if (jobs.isNotEmpty()) append("\n   recent jobs: ${jobs.joinToString { "${it.jobId}=${it.state.label}" }}")
                    }
                },
            )
        },
        Tool(
            "colab_create_session", "Create Colab runtime",
            "Allocates a new Colab runtime (VM). hardware: CPU (default), GPUs T4/L4/G4/A100/H100, or TPUs TPU_V5E1/TPU_V6E1. " +
                "Availability depends on the user's Colab plan and capacity; if a GPU is refused, try T4 or CPU. Takes up to a minute.",
            schema(
                emptyList(),
                "hardware" to mapOf("type" to "string", "enum" to HARDWARE, "description" to "CPU, a GPU type or a TPU type. Default CPU."),
                "name" to mapOf("type" to "string", "description" to "Optional short name for the session, e.g. 'train'. Defaults to the hardware name."),
                "high_ram" to mapOf("type" to "boolean", "description" to "Request a High-RAM machine (Colab Pro/Pro+). Default false."),
            ),
        ) { a ->
            val hw = Hardware.parse(a.str("hardware"))
            val s = m.create(a.str("name"), hw, a.bool("high_ram") == true)
            ToolResult.text("Created session '${s.name}' with ${s.hardware.label}${if (s.highMem) " (High-RAM)" else ""}. Run code with colab_run_code(session=\"${s.name}\", ...). Remember to colab_stop_session when finished.")
        },
        Tool(
            "colab_stop_session", "Stop Colab runtime",
            "Stops (releases) a Colab runtime so it no longer uses compute units. All its state and files under /content are lost. Set all=true to stop every runtime.",
            schema(
                emptyList(),
                SESSION_PROP,
                "all" to mapOf("type" to "boolean", "description" to "Stop all runtimes."),
            ),
            destructive = true,
        ) { a ->
            if (a.bool("all") == true) {
                val stopped = m.stopAll()
                return@Tool ToolResult.text(if (stopped.isEmpty()) "No runtimes were running." else "Stopped: ${stopped.joinToString()}")
            }
            val name = a.str("session") ?: m.resolve(null).name
            m.stop(name)
            ToolResult.text("Stopped session '$name'.")
        },
        Tool(
            "colab_run_code", "Run code on Colab",
            "Runs code on a Colab runtime and returns stdout/stderr, results, errors and images (plots). " +
                "Python runs in a persistent IPython kernel (state persists across calls; !shell and %magics work). " +
                "language='bash' runs a shell script in /content. If the code is still running after timeout_seconds, " +
                "the partial output and a job_id are returned and the code keeps running; use colab_get_job_output to continue waiting.",
            schema(
                listOf("code"),
                "code" to mapOf("type" to "string", "description" to "Python code (or a bash script when language='bash')."),
                SESSION_PROP,
                "language" to mapOf("type" to "string", "enum" to listOf("python", "bash"), "description" to "Default python."),
                "timeout_seconds" to mapOf("type" to "integer", "description" to "How long to wait for completion before returning partial output (default $DEFAULT_WAIT, max $MAX_WAIT). The code is not killed."),
            ),
        ) { a ->
            val code = a.str("code") ?: throw IllegalArgumentException("code is required")
            val lang = a.str("language")?.lowercase() ?: "python"
            val bash = lang == "bash" || lang == "shell" || lang == "sh"
            val toRun = if (bash) bashWrapper(code) else code
            result(m.run(a.str("session"), toRun, if (bash) "bash" else "python", a.waitMs("timeout_seconds", DEFAULT_WAIT), displayCode = if (bash) "%%bash\n$code" else code))
        },
        Tool(
            "colab_get_job_output", "Get job output",
            "Returns the output of a job started by colab_run_code (or other tools), waiting up to wait_seconds for it to finish.",
            schema(
                listOf("job_id"),
                "job_id" to mapOf("type" to "string"),
                "wait_seconds" to mapOf("type" to "integer", "description" to "Max seconds to wait for completion (default 30, max $MAX_WAIT)."),
            ),
            readOnly = true,
        ) { a ->
            val exec = m.job(a.str("job_id") ?: throw IllegalArgumentException("job_id is required"))
            if (!exec.finished && exec.state != Execution.State.WAITING_FOR_INPUT) exec.await(a.waitMs("wait_seconds", 30))
            result(exec)
        },
        Tool(
            "colab_send_input", "Send keyboard input",
            "Answers an input() prompt for a job whose state is waiting_for_input.",
            schema(
                listOf("job_id", "text"),
                "job_id" to mapOf("type" to "string"),
                "text" to mapOf("type" to "string", "description" to "The line to type (without newline)."),
            ),
        ) { a ->
            val exec = m.sendInput(a.str("job_id") ?: "", a.str("text") ?: "")
            exec.await(15_000)
            result(exec)
        },
        Tool(
            "colab_interrupt", "Interrupt running code",
            "Interrupts the code currently running on a session (like the stop button in Colab). Kernel state is kept.",
            schema(emptyList(), SESSION_PROP),
        ) { a ->
            val s = m.interrupt(a.str("session"))
            ToolResult.text("Interrupt sent to '${s.name}'.")
        },
        Tool(
            "colab_restart_kernel", "Restart Python kernel",
            "Restarts the Python kernel of a session (clears all variables/imports; files are kept). Useful after installing packages that need a restart.",
            schema(emptyList(), SESSION_PROP),
            destructive = true,
        ) { a ->
            val s = m.restartKernel(a.str("session"))
            ToolResult.text("Kernel of '${s.name}' restarted.")
        },
        Tool(
            "colab_install_packages", "Install Python packages",
            "Installs pip packages on the runtime (uses uv, falls back to pip), e.g. ['transformers', 'accelerate==1.2.0'].",
            schema(
                listOf("packages"),
                "packages" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "pip requirement specifiers"),
                SESSION_PROP,
                "timeout_seconds" to mapOf("type" to "integer", "description" to "Default 120, max $MAX_WAIT."),
            ),
        ) { a ->
            val pkgs = (a["packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }?.filter { it.isNotEmpty() }
            if (pkgs.isNullOrEmpty()) throw IllegalArgumentException("packages must be a non-empty list")
            val args = pkgs.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            val script = "(uv pip install --system $args && echo 'Installation complete (uv)') || (python -m pip install $args && echo 'Installation complete (pip)')"
            result(m.run(a.str("session"), bashWrapper(script), "install", a.waitMs("timeout_seconds", 120), displayCode = "!pip install $args"))
        },
        Tool(
            "colab_list_files", "List files",
            "Lists files and folders on the runtime. Paths are absolute (/content/...) or relative to /content.",
            schema(emptyList(), "path" to mapOf("type" to "string", "description" to "Directory, default /content"), SESSION_PROP),
            readOnly = true,
        ) { a ->
            val s = m.resolve(a.str("session"))
            val path = a.str("path") ?: "/content"
            val entries = m.contents(s).list(path)
            if (entries.isEmpty()) return@Tool ToolResult.text("$path is empty.")
            ToolResult.text(
                "Contents of $path on '${s.name}':\n" + entries.joinToString("\n") {
                    if (it.type == "directory") "  [dir]  ${it.name}/" else "  ${humanSize(it.size)}\t${it.name}"
                },
            )
        },
        Tool(
            "colab_read_file", "Read a file",
            "Reads a file from the runtime. Text is returned as text (truncated to max_chars); images are returned as images.",
            schema(
                listOf("path"),
                "path" to mapOf("type" to "string"),
                SESSION_PROP,
                "max_chars" to mapOf("type" to "integer", "description" to "Default 20000."),
            ),
            readOnly = true,
        ) { a ->
            val s = m.resolve(a.str("session"))
            val f = m.contents(s).read(a.str("path") ?: throw IllegalArgumentException("path is required"))
            val lower = f.path.lowercase()
            val imageType = when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".webp") -> "image/webp"
                else -> null
            }
            if (imageType != null && f.bytes.size < 3_000_000) {
                return@Tool ToolResult(listOf(ToolResult.textContent("${f.path} (${humanSize(f.bytes.size.toLong())})"), ToolResult.imageContent(Base64.getEncoder().encodeToString(f.bytes), imageType)))
            }
            val text = String(f.bytes, Charsets.UTF_8)
            val binary = text.count { it == '�' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') } > text.length / 20 + 1
            if (binary) return@Tool ToolResult.text("${f.path} is a binary file (${humanSize(f.bytes.size.toLong())}). Use colab_run_code to inspect it.")
            val max = (a.int("max_chars") ?: 20_000).coerceIn(100, 200_000)
            ToolResult.text("${f.path} (${humanSize(f.bytes.size.toLong())}):\n" + com.colabmcp.core.util.truncateMiddle(text, max, 0.7))
        },
        Tool(
            "colab_write_file", "Write a file",
            "Creates or overwrites a file on the runtime (parent folders are created). Use base64=true for binary content.",
            schema(
                listOf("path", "content"),
                "path" to mapOf("type" to "string"),
                "content" to mapOf("type" to "string"),
                "base64" to mapOf("type" to "boolean", "description" to "content is base64-encoded bytes"),
                SESSION_PROP,
            ),
        ) { a ->
            val s = m.resolve(a.str("session"))
            val path = a.str("path") ?: throw IllegalArgumentException("path is required")
            val content = a.str("content") ?: ""
            val bytes = if (a.bool("base64") == true) Base64.getMimeDecoder().decode(content) else content.toByteArray()
            val c = m.contents(s)
            try {
                c.write(path, bytes)
            } catch (e: ColabException) {
                // Parent folder probably missing: create it and retry.
                val parts = c.normalize(path).split('/').dropLast(1)
                for (i in 2..parts.size) runCatching { c.mkdir(parts.take(i).joinToString("/")) }
                c.write(path, bytes)
            }
            ToolResult.text("Wrote ${humanSize(bytes.size.toLong())} to /${c.normalize(path)} on '${s.name}'.")
        },
        Tool(
            "colab_delete_file", "Delete a file",
            "Deletes a file (or empty folder) on the runtime.",
            schema(listOf("path"), "path" to mapOf("type" to "string"), SESSION_PROP),
            destructive = true,
        ) { a ->
            val s = m.resolve(a.str("session"))
            val c = m.contents(s)
            c.delete(a.str("path") ?: throw IllegalArgumentException("path is required"))
            ToolResult.text("Deleted /${c.normalize(a.str("path")!!)}.")
        },
        Tool(
            "colab_system_info", "Runtime hardware info",
            "Shows the runtime's GPU/TPU (nvidia-smi), CPU count, RAM, disk space and Python/PyTorch versions.",
            schema(emptyList(), SESSION_PROP),
            readOnly = true,
        ) { a ->
            val code = """
import os, sys, shutil, subprocess, platform, glob
print('Python', sys.version.split()[0], '|', platform.platform())
print('CPU cores:', os.cpu_count())
try:
    import psutil
    _vm = psutil.virtual_memory()
    print(f'RAM: {_vm.total/1e9:.1f} GB total, {_vm.available/1e9:.1f} GB available')
except Exception:
    pass
_du = shutil.disk_usage('/content')
print(f'Disk /content: {_du.free/1e9:.1f} GB free of {_du.total/1e9:.1f} GB')
if shutil.which('nvidia-smi'):
    print(subprocess.run(['nvidia-smi', '--query-gpu=name,memory.total,memory.used,utilization.gpu,driver_version', '--format=csv'], capture_output=True, text=True).stdout.strip())
else:
    print('GPU: none')
_tpu = glob.glob('/dev/accel*') or glob.glob('/dev/vfio/*')
print('TPU devices:', len(_tpu) if _tpu else 'none')
try:
    import torch
    print('torch', torch.__version__, '| CUDA available:', torch.cuda.is_available())
except Exception:
    pass
            """.trimIndent()
            result(m.run(a.str("session"), code, "system_info", 60_000))
        },
        Tool(
            "colab_mount_drive", "Mount Google Drive",
            "Mounts the user's Google Drive on the runtime (default /content/drive). The first time, the user must open an authorization link that this tool returns; show it to them.",
            schema(emptyList(), "path" to mapOf("type" to "string", "description" to "Mount point, default /content/drive"), SESSION_PROP),
        ) { a ->
            val path = a.str("path") ?: "/content/drive"
            val code = "from google.colab import drive\ndrive.mount(${JsonPrimitive(path)})"
            result(m.run(a.str("session"), code, "drivemount", 40_000))
        },
        Tool(
            "colab_authenticate_gcp", "Authenticate runtime for Google Cloud",
            "Gives the runtime the user's Google Cloud credentials (like google.colab.auth.authenticate_user()) so code can use BigQuery, GCS, Vertex AI, gcloud. May return a link the user must open.",
            schema(emptyList(), SESSION_PROP),
        ) { a ->
            val code = "from google.colab import auth\nauth.authenticate_user()\nprint('Authenticated for Google Cloud.')"
            result(m.run(a.str("session"), code, "auth", 40_000))
        },
        Tool(
            "colab_notebook_url", "Open runtime in browser",
            "Returns a URL that opens the Colab web UI attached to this runtime, so the user can watch or continue the work in a browser.",
            schema(emptyList(), SESSION_PROP),
            readOnly = true,
        ) { a ->
            val s = m.resolve(a.str("session"))
            ToolResult.text("Open this link to attach the Colab web UI to '${s.name}':\n${m.api.browserUrl(s.endpoint)}")
        },
        Tool(
            "colab_session_history", "Recent cells",
            "Shows the most recent code cells run on a session through this bridge, with their status and a short output preview.",
            schema(emptyList(), SESSION_PROP, "limit" to mapOf("type" to "integer", "description" to "Default 10")),
            readOnly = true,
        ) { a ->
            val s = m.resolve(a.str("session"))
            val jobs = m.recentJobs(s.name, (a.int("limit") ?: 10).coerceIn(1, 50))
            if (jobs.isEmpty()) return@Tool ToolResult.text("No cells have been run on '${s.name}' through the bridge yet.")
            ToolResult.text(
                jobs.joinToString("\n\n") { j ->
                    val out = OutputFormatter.format(j, 800, includeImages = false).text.substringAfter("--- output ---\n")
                    "### ${j.jobId} [${j.state.label}] (${j.label})\n```\n${j.displayCode.take(600)}\n```\n$out"
                },
            )
        },
        Tool(
            "colab_export_notebook", "Save history as notebook",
            "Saves the cells run on a session as a Jupyter notebook (.ipynb) on the runtime. Use a path under /content/drive/MyDrive/ (after colab_mount_drive) to keep it in Google Drive.",
            schema(emptyList(), "path" to mapOf("type" to "string", "description" to "Default /content/colab_mcp_session.ipynb"), SESSION_PROP),
        ) { a ->
            val s = m.resolve(a.str("session"))
            val path = a.str("path") ?: "/content/colab_mcp_session.ipynb"
            val c = m.contents(s)
            c.write(path, m.notebookJson(s.name).toByteArray())
            ToolResult.text("Saved notebook to /${c.normalize(path)} on '${s.name}'.")
        },
    )

    private fun humanSize(bytes: Long?): String {
        if (bytes == null) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, units[i])
    }
}
