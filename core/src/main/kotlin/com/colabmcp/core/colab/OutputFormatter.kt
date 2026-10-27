package com.colabmcp.core.colab

import com.colabmcp.core.util.stripAnsi
import com.colabmcp.core.util.truncateMiddle

/** Text + images ready to hand back to an MCP client. */
data class FormattedOutput(val text: String, val images: List<Image>) {
    data class Image(val mimeType: String, val base64: String)
}

object OutputFormatter {
    private const val MAX_IMAGES = 4
    private const val MAX_IMAGE_B64 = 2_000_000

    /** Renders carriage-return progress bars (tqdm, pip) the way a terminal would show them. */
    fun collapseCarriageReturns(text: String): String {
        if ('\r' !in text) return text
        return text.split('\n').joinToString("\n") { line ->
            val trimmed = line.trimEnd('\r')
            if ('\r' in trimmed) trimmed.substringAfterLast('\r') else trimmed
        }
    }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|h[1-6]|li)>"), "\n")
        .replace(Regex("(?i)</t[dh]>"), "\t")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    fun format(exec: Execution, maxChars: Int = 20_000, includeImages: Boolean = true): FormattedOutput {
        val images = ArrayList<FormattedOutput.Image>()
        val body = StringBuilder()
        var lastKind = ""
        var omittedImages = 0
        for (o in exec.outputs()) {
            when (o) {
                is Output.Stream -> {
                    val kind = o.name
                    if (kind == "stderr" && lastKind != "stderr") body.append(if (body.isEmpty() || body.endsWith("\n")) "" else "\n").append("[stderr]\n")
                    if (kind != "stderr" && lastKind == "stderr") body.append(if (body.endsWith("\n")) "" else "\n").append("[stdout]\n")
                    body.append(collapseCarriageReturns(stripAnsi(o.text.toString())))
                    lastKind = kind
                }
                is Output.Data -> {
                    if (body.isNotEmpty() && !body.endsWith("\n")) body.append('\n')
                    val img = o.data.entries.firstOrNull { it.key.startsWith("image/") && it.key != "image/svg+xml" }
                    if (img != null) {
                        val b64 = img.value.replace(Regex("\\s"), "")
                        if (includeImages && images.size < MAX_IMAGES && b64.length <= MAX_IMAGE_B64) {
                            images.add(FormattedOutput.Image(img.key, b64))
                            body.append("[${img.key} output #${images.size} attached below]\n")
                        } else {
                            omittedImages++
                            body.append("[${img.key} output omitted]\n")
                        }
                    }
                    val text = o.data["text/plain"]
                        ?: o.data["text/markdown"]
                        ?: o.data["text/html"]?.let { htmlToText(it) }
                        ?: o.data["application/json"]
                        ?: o.data["image/svg+xml"]?.let { "[SVG image, ${it.length} chars]" }
                    if (text != null && !(img != null && text.startsWith("<Figure"))) {
                        if (o.isResult) body.append("Out[${exec.executionCount ?: ""}]: ")
                        body.append(stripAnsi(text)).append('\n')
                    }
                    lastKind = "data"
                }
                is Output.Error -> {
                    if (body.isNotEmpty() && !body.endsWith("\n")) body.append('\n')
                    body.append("[error] ${o.ename}: ${o.evalue}\n")
                    if (o.traceback.isNotEmpty()) {
                        val tb = stripAnsi(o.traceback.joinToString("\n"))
                        body.append(truncateMiddle(tb, 6000, 0.3)).append('\n')
                    }
                    lastKind = "error"
                }
            }
        }

        val elapsed = ((if (exec.finishedAt > 0) exec.finishedAt else System.currentTimeMillis()) - exec.startedAt) / 1000.0
        val head = StringBuilder()
        head.append("Job ${exec.jobId} on session '${exec.session}': ${exec.state.label}")
        exec.executionCount?.let { head.append(" (cell [$it])") }
        head.append(" after %.1fs".format(elapsed)).append('\n')
        when (exec.state) {
            Execution.State.QUEUED, Execution.State.RUNNING ->
                head.append("Still running. Call colab_get_job_output with job_id=\"${exec.jobId}\" to wait for more output, or colab_interrupt to stop it.\n")
            Execution.State.WAITING_FOR_INPUT ->
                head.append("The code is waiting for keyboard input. Prompt: ${exec.inputPrompt ?: ""}\nReply with colab_send_input(job_id=\"${exec.jobId}\", text=...).\n")
            else -> {}
        }
        exec.actionUrl?.let { head.append("ACTION REQUIRED: the user must open $it and approve access. Show them this link.\n") }
        if (omittedImages > 0) head.append("($omittedImages image output(s) omitted)\n")
        val out = if (body.isBlank()) "(no output)" else truncateMiddle(body.toString().trimEnd(), maxChars)
        head.append("--- output ---\n").append(out)
        return FormattedOutput(head.toString(), images)
    }
}
