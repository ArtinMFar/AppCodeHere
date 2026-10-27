package com.colabmcp.core.colab

import com.colabmcp.core.util.Http
import com.colabmcp.core.util.arr
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.long
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

data class RemoteEntry(val name: String, val path: String, val type: String, val size: Long?, val modified: String?)

class RemoteFile(val path: String, val bytes: ByteArray, val mimetype: String?)

/** Jupyter contents API on the VM, mirroring colab_cli/contents.py. */
class ContentsClient(private val proxy: () -> ProxyInfo, private val http: OkHttpClient = Http.client) {

    /** Accepts `/content/x`, `content/x`, or a path relative to /content. */
    fun normalize(path: String): String {
        val p = path.trim().ifEmpty { "/content" }
        return when {
            p.startsWith("/") -> p.trimStart('/')
            p == "content" || p.startsWith("content/") -> p
            else -> "content/$p"
        }.trimEnd('/')
    }

    private fun request(path: String, params: Map<String, String> = emptyMap()): Request.Builder {
        val p = proxy()
        val b = (p.url.trimEnd('/') + "/api/contents/").toHttpUrl().newBuilder()
        normalize(path).split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        b.addQueryParameter("authuser", "0").addQueryParameter("colab-runtime-proxy-token", p.token)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return Request.Builder().url(b.build())
            .header("X-Colab-Runtime-Proxy-Token", p.token)
            .header("X-Colab-Client-Agent", ColabApi.CLIENT_AGENT)
    }

    private fun exec(b: Request.Builder, path: String): String {
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 404) throw ColabException("Not found on the VM: /${normalize(path)}", 404)
            if (!r.isSuccessful) throw ColabException("File operation failed (HTTP ${r.code}): ${body.take(300)}", r.code, body)
            return body
        }
    }

    fun list(path: String): List<RemoteEntry> {
        val o = parseJsonObject(exec(request(path, mapOf("content" to "1")).get(), path))
        if (o.str("type") != "directory") {
            return listOf(entry(o))
        }
        return o.arr("content")?.mapNotNull { (it as? JsonObject)?.let(::entry) }?.sortedWith(compareBy({ it.type != "directory" }, { it.name })) ?: emptyList()
    }

    private fun entry(o: JsonObject) = RemoteEntry(
        name = o.str("name") ?: "",
        path = "/" + (o.str("path") ?: ""),
        type = o.str("type") ?: "file",
        size = o.long("size"),
        modified = o.str("last_modified"),
    )

    fun read(path: String): RemoteFile {
        val o = parseJsonObject(exec(request(path, mapOf("content" to "1", "format" to "base64", "type" to "file")).get(), path))
        if (o.str("type") == "directory") throw ColabException("/${normalize(path)} is a directory; use colab_list_files.")
        val content = o.str("content") ?: ""
        val bytes = if (o.str("format") == "base64") Base64.getMimeDecoder().decode(content) else content.toByteArray()
        return RemoteFile("/" + normalize(path), bytes, o.str("mimetype"))
    }

    fun write(path: String, bytes: ByteArray) {
        val norm = normalize(path)
        val payload = jsonOf(
            "name" to norm.substringAfterLast('/'),
            "path" to norm,
            "type" to "file",
            "format" to "base64",
            "content" to Base64.getEncoder().encodeToString(bytes),
        )
        exec(request(path).put(payload.toString().toRequestBody("application/json".toMediaType())), path)
    }

    fun mkdir(path: String) {
        val payload = jsonOf("type" to "directory")
        exec(request(path).put(payload.toString().toRequestBody("application/json".toMediaType())), path)
    }

    fun delete(path: String) {
        exec(request(path).delete(), path)
    }
}
