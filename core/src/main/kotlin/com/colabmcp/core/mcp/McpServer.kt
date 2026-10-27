package com.colabmcp.core.mcp

import com.colabmcp.core.http.HttpRequest
import com.colabmcp.core.http.HttpResponse
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.obj
import com.colabmcp.core.util.parseJson
import com.colabmcp.core.util.randomToken
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ToolResult(val content: List<JsonObject>, val isError: Boolean = false) {
    companion object {
        fun text(text: String, isError: Boolean = false) = ToolResult(listOf(textContent(text)), isError)
        fun error(text: String) = text(text, true)
        fun textContent(text: String) = jsonOf("type" to "text", "text" to text)
        fun imageContent(base64: String, mimeType: String) = jsonOf("type" to "image", "data" to base64, "mimeType" to mimeType)
    }
}

class Tool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = false,
    val destructive: Boolean = false,
    val handler: (JsonObject) -> ToolResult,
) {
    fun descriptor(): JsonObject = jsonOf(
        "name" to name,
        "title" to title,
        "description" to description,
        "inputSchema" to inputSchema,
        "annotations" to mapOf(
            "title" to title,
            "readOnlyHint" to readOnly,
            "destructiveHint" to destructive,
            "openWorldHint" to true,
        ),
    )
}

/** Builds a JSON schema object for tool inputs. */
fun schema(required: List<String> = emptyList(), vararg props: Pair<String, Map<String, Any?>>): JsonObject = jsonOf(
    "type" to "object",
    "properties" to props.toMap(),
    "required" to required,
    "additionalProperties" to false,
)

/**
 * MCP server over the Streamable HTTP transport (single JSON responses; GET streams are
 * not offered). Supports protocol revisions 2024-11-05 through 2025-11-25.
 */
class McpServer(
    private val tools: () -> List<Tool>,
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String,
) {
    companion object {
        private const val TAG = "MCP"
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }

    /** Invoked for every tool call so the UI can show activity. */
    @Volatile var onToolCall: ((name: String, ok: Boolean, millis: Long) -> Unit)? = null

    fun handle(req: HttpRequest): HttpResponse = when (req.method) {
        "POST" -> handlePost(req)
        "DELETE" -> HttpResponse.empty(204)
        "GET" -> HttpResponse.text(405, "This server does not offer an SSE stream; use POST.").header("Allow", "POST, DELETE")
        else -> HttpResponse.text(405, "Method not allowed").header("Allow", "POST, DELETE")
    }

    private fun handlePost(req: HttpRequest): HttpResponse {
        val parsed = try {
            parseJson(req.bodyText)
        } catch (e: Exception) {
            return HttpResponse.json(400, error(JsonNull, -32700, "Parse error"))
        }
        var sessionId: String? = null
        val responses = ArrayList<JsonElement>()
        val messages = if (parsed is JsonArray) parsed.toList() else listOf(parsed)
        for (m in messages) {
            val msg = m as? JsonObject ?: continue
            val method = msg.str("method") ?: continue // responses from the client are ignored
            val id = msg["id"]
            val params = msg.obj("params") ?: JsonObject(emptyMap())
            if (id == null || id is JsonNull) {
                Log.d(TAG, "notification $method")
                continue
            }
            if (method == "initialize") sessionId = randomToken(16)
            responses.add(dispatch(id, method, params))
        }
        if (responses.isEmpty()) return HttpResponse.empty(202)
        val body: JsonElement = if (parsed is JsonArray) JsonArray(responses) else responses.first()
        val resp = HttpResponse.json(200, body)
        sessionId?.let { resp.header("Mcp-Session-Id", it) }
        return resp
    }

    private fun dispatch(id: JsonElement, method: String, params: JsonObject): JsonObject = try {
        when (method) {
            "initialize" -> {
                val requested = params.str("protocolVersion")
                val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
                val client = params.obj("clientInfo")
                Log.i(TAG, "Client connected: ${client?.str("name") ?: "unknown"} ${client?.str("version") ?: ""} (protocol $version)")
                result(
                    id,
                    jsonOf(
                        "protocolVersion" to version,
                        "capabilities" to mapOf(
                            "tools" to mapOf("listChanged" to false),
                            "resources" to emptyMap<String, Any>(),
                            "prompts" to emptyMap<String, Any>(),
                        ),
                        "serverInfo" to mapOf("name" to serverName, "title" to "Google Colab (via phone bridge)", "version" to serverVersion),
                        "instructions" to instructions,
                    ),
                )
            }
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, jsonOf("tools" to tools().map { it.descriptor() }))
            "tools/call" -> callTool(id, params)
            "resources/list" -> result(id, jsonOf("resources" to emptyList<Any>()))
            "resources/templates/list" -> result(id, jsonOf("resourceTemplates" to emptyList<Any>()))
            "prompts/list" -> result(id, jsonOf("prompts" to emptyList<Any>()))
            "logging/setLevel" -> result(id, JsonObject(emptyMap()))
            "completion/complete" -> result(id, jsonOf("completion" to mapOf("values" to emptyList<String>(), "hasMore" to false)))
            else -> error(id, -32601, "Method not found: $method")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error handling $method", e)
        error(id, -32603, e.message ?: "Internal error")
    }

    private fun callTool(id: JsonElement, params: JsonObject): JsonObject {
        val name = params.str("name") ?: return error(id, -32602, "Missing tool name")
        val tool = tools().firstOrNull { it.name == name } ?: return error(id, -32602, "Unknown tool: $name")
        val args = params.obj("arguments") ?: JsonObject(emptyMap())
        val started = System.currentTimeMillis()
        Log.i(TAG, "call $name ${args.toString().take(200)}")
        val res = try {
            tool.handler(args)
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "Invalid arguments")
        } catch (e: IllegalStateException) {
            ToolResult.error(e.message ?: "Invalid state")
        } catch (e: Exception) {
            Log.w(TAG, "Tool $name failed", e)
            ToolResult.error("${e.javaClass.simpleName}: ${e.message}")
        }
        val ms = System.currentTimeMillis() - started
        Log.i(TAG, "done $name ${if (res.isError) "error" else "ok"} in ${ms}ms")
        onToolCall?.invoke(name, !res.isError, ms)
        return result(id, jsonOf("content" to res.content, "isError" to res.isError))
    }

    private fun result(id: JsonElement, result: JsonObject) =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "result" to result))

    private fun error(id: JsonElement, code: Int, message: String) =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "error" to jsonOf("code" to code, "message" to message)))
}
