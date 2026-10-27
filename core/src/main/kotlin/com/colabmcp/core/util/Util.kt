package com.colabmcp.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

val JSON = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
val PRETTY_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

fun parseJson(text: String): JsonElement = JSON.parseToJsonElement(text)
fun parseJsonObject(text: String): JsonObject = parseJson(text) as? JsonObject
    ?: throw IllegalArgumentException("Expected a JSON object")

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() }
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Converts plain Kotlin values (maps, lists, strings, numbers, booleans) into a JsonElement. */
fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    is Array<*> -> JsonArray(value.map { toJson(it) })
    else -> JsonPrimitive(value.toString())
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to toJson(v) })

private val random = SecureRandom()

fun randomToken(bytes: Int = 32): String {
    val b = ByteArray(bytes)
    random.nextBytes(b)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

fun randomDigits(count: Int): String = buildString { repeat(count) { append(random.nextInt(10)) } }

fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
fun sha256B64Url(input: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(input))
fun sha256Hex(input: String): String = sha256(input).joinToString("") { "%02x".format(it) }

fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
fun stripAnsi(s: String): String = ANSI.replace(s, "")

/** Keeps the head and tail of very long text so the model sees both the start and the latest output. */
fun truncateMiddle(text: String, max: Int, headFraction: Double = 0.25): String {
    if (text.length <= max) return text
    val head = (max * headFraction).toInt()
    val tail = max - head
    val dropped = text.length - head - tail
    return text.substring(0, head) + "\n... [$dropped characters truncated] ...\n" + text.substring(text.length - tail)
}

fun htmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

object Http {
    /** Shared client; long read timeout because Colab VM allocation can take a while. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Minimal logging abstraction so the core works on both the JVM and Android. */
interface Logger {
    fun log(level: Level, tag: String, message: String, error: Throwable? = null)

    enum class Level { DEBUG, INFO, WARN, ERROR }
}

object Log {
    @Volatile
    var sink: Logger = object : Logger {
        override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) {
            if (level == Logger.Level.DEBUG && System.getenv("COLAB_MCP_DEBUG") == null) return
            System.err.println("[${level.name.first()}] $tag: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
        }
    }

    fun d(tag: String, msg: String) = sink.log(Logger.Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = sink.log(Logger.Level.INFO, tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) = sink.log(Logger.Level.WARN, tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = sink.log(Logger.Level.ERROR, tag, msg, e)
}
