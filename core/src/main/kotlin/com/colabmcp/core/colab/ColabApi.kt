package com.colabmcp.core.colab

import com.colabmcp.core.util.Http
import com.colabmcp.core.util.Log
import com.colabmcp.core.util.bool
import com.colabmcp.core.util.double
import com.colabmcp.core.util.int
import com.colabmcp.core.util.long
import com.colabmcp.core.util.obj
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

class ColabException(message: String, val status: Int = 0, val body: String? = null) : Exception(message)

/** Hardware a Colab runtime can be assigned. `api` is the backend accelerator name. */
enum class Hardware(val api: String, val variant: String, val label: String) {
    CPU("NONE", "DEFAULT", "CPU"),
    T4("T4", "GPU", "T4 GPU"),
    L4("L4", "GPU", "L4 GPU"),
    G4("G4", "GPU", "G4 GPU"),
    A100("A100", "GPU", "A100 GPU"),
    H100("H100", "GPU", "H100 GPU"),
    TPU_V5E1("V5E1", "TPU", "TPU v5e-1"),
    TPU_V6E1("V6E1", "TPU", "TPU v6e-1");

    /** L4 and TPUs only exist in one (high-memory) shape. */
    val singleShape: Boolean get() = this == L4 || this == TPU_V5E1 || this == TPU_V6E1

    companion object {
        fun parse(value: String?): Hardware {
            if (value.isNullOrBlank()) return CPU
            val v = value.trim().uppercase().replace("-", "_").replace(" ", "_")
            return when (v) {
                "CPU", "NONE", "DEFAULT" -> CPU
                "T4", "GPU_T4" -> T4
                "L4", "GPU_L4" -> L4
                "G4", "GPU_G4" -> G4
                "A100", "GPU_A100" -> A100
                "H100", "GPU_H100" -> H100
                "V5E1", "TPU_V5E1", "TPU_V5E", "V5E" -> TPU_V5E1
                "V6E1", "TPU_V6E1", "TPU_V6E", "V6E", "TPU" -> TPU_V6E1
                "GPU" -> T4
                else -> throw IllegalArgumentException("Unknown hardware '$value'. Use one of: ${entries.joinToString { it.name }}")
            }
        }

        fun fromApi(accelerator: String?): Hardware = entries.firstOrNull { it.api == accelerator } ?: CPU
    }
}

data class ProxyInfo(val url: String, val token: String, val expiresAtMillis: Long)

data class Assignment(
    val endpoint: String,
    val hardware: Hardware,
    val highMem: Boolean,
    val proxy: ProxyInfo,
)

data class CcuInfo(val balance: Double, val ratePerHour: Double, val assignments: Int)

/**
 * Port of the Colab CLI's backend client (colab_cli/client.py): talks to the
 * `/tun/m` session backend on colab.research.google.com.
 */
class ColabApi(
    private val auth: GoogleAuth,
    private val http: OkHttpClient = Http.client,
    private val domain: String = "https://colab.research.google.com",
) {
    companion object {
        private const val XSSI_PREFIX = ")]}'"
        private const val TAG = "ColabApi"
        const val CLIENT_AGENT = "colab-cli"

        /** Same transform as uuid_to_web_safe_base64 in the CLI. */
        fun notebookHash(uuid: UUID = UUID.randomUUID()): String {
            val s = uuid.toString()
            return s.replace('-', '_') + ".".repeat(44 - s.length)
        }

        fun stripXssi(body: String): String {
            val t = body.trimStart()
            return if (t.startsWith(XSSI_PREFIX)) t.substring(XSSI_PREFIX.length).trimStart() else body
        }
    }

    private fun tunUrl(path: String): HttpUrl.Builder =
        "$domain/tun/m/$path".toHttpUrl().newBuilder().addQueryParameter("authuser", "0")

    private fun call(
        url: HttpUrl,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        retryAuth: Boolean = true,
    ): String {
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${auth.accessToken()}")
            .header("Accept", "application/json")
            .header("X-Colab-Client-Agent", CLIENT_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, body ?: ByteArray(0).toRequestBody(null))
        }
        http.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 && retryAuth) {
                auth.accessToken(forceRefresh = true)
                return call(url, method, headers, body, retryAuth = false)
            }
            if (!r.isSuccessful) {
                Log.d(TAG, "$method ${url.encodedPath} -> ${r.code}: ${text.take(500)}")
                throw ColabException("Colab backend returned HTTP ${r.code} for ${url.encodedPath}", r.code, text)
            }
            return stripXssi(text)
        }
    }

    private fun callJson(url: HttpUrl, method: String = "GET", headers: Map<String, String> = emptyMap(), body: RequestBody? = null): JsonObject {
        val text = call(url, method, headers, body)
        return if (text.isBlank()) JsonObject(emptyMap()) else parseJsonObject(text)
    }

    fun ccuInfo(): CcuInfo {
        val o = callJson(tunUrl("ccu-info").build())
        return CcuInfo(o.double("currentBalance") ?: 0.0, o.double("consumptionRateHourly") ?: 0.0, o.int("assignmentsCount") ?: 0)
    }

    fun listAssignments(): List<Assignment> {
        val o = callJson(tunUrl("assignments").build())
        val arr = o["assignments"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseAssignment) }
    }

    private fun parseProxy(o: JsonObject?): ProxyInfo? {
        o ?: return null
        val token = o.str("token") ?: return null
        val url = o.str("url") ?: return null
        val expires = o.long("tokenExpiresInSeconds") ?: 3600
        return ProxyInfo(url, token, System.currentTimeMillis() + expires * 1000)
    }

    private fun parseAssignment(o: JsonObject): Assignment? {
        val endpoint = o.str("endpoint") ?: return null
        val proxy = parseProxy(o.obj("runtimeProxyInfo")) ?: return null
        val shape = o["machineShape"]
        val highMem = when (shape) {
            is JsonPrimitive -> shape.content == "1" || shape.content.equals("HIGH_RAM", true)
            else -> false
        }
        return Assignment(endpoint, Hardware.fromApi(o.str("accelerator")), highMem, proxy)
    }

    private fun assignUrl(nbh: String, hw: Hardware, highMem: Boolean): HttpUrl {
        val b = tunUrl("assign").addQueryParameter("nbh", nbh)
        if (hw != Hardware.CPU) {
            b.addQueryParameter("variant", hw.variant)
            b.addQueryParameter("accelerator", hw.api)
        } else {
            b.addQueryParameter("variant", "DEFAULT")
            b.addQueryParameter("accelerator", "NONE")
        }
        if (highMem && !hw.singleShape) b.addQueryParameter("shape", "hm")
        return b.build()
    }

    /** Allocates a new runtime (`colab new`). */
    fun assign(hw: Hardware, highMem: Boolean = false): Assignment {
        val nbh = notebookHash()
        val url = assignUrl(nbh, hw, highMem)
        val first = try {
            callJson(url)
        } catch (e: ColabException) {
            throw translateAssignError(e, hw)
        }
        parseAssignment(first)?.let { return it }
        var xsrf = first.str("token") ?: throw ColabException("Unexpected assign response: $first")
        val retries = 3
        var attempt = 0
        while (true) {
            try {
                val res = callJson(url, "POST", mapOf("X-Goog-Colab-Token" to xsrf))
                return parseAssignment(res) ?: throw ColabException("Unexpected assignment response: $res")
            } catch (e: Exception) {
                val status = (e as? ColabException)?.status ?: 0
                val retryable = e is SocketTimeoutException || status in setOf(502, 503, 504)
                if (!retryable || attempt >= retries) {
                    throw if (e is ColabException) translateAssignError(e, hw) else e
                }
                val delay = 10_000L * (1 shl attempt)
                Log.w(TAG, "Assignment failed (${e.message}); retrying in ${delay / 1000}s")
                Thread.sleep(delay)
                attempt++
                // Reconcile an uncertain POST before submitting again.
                val again = callJson(url)
                parseAssignment(again)?.let { return it }
                xsrf = again.str("token") ?: xsrf
            }
        }
    }

    private fun translateAssignError(e: ColabException, hw: Hardware): ColabException = when {
        e.status == 412 -> ColabException(
            "Colab refused the allocation (HTTP 412): too many active sessions, or a temporary usage/capacity limit for ${hw.label}. Stop a session, wait and retry, or pick different hardware.",
            e.status, e.body,
        )
        e.status == 400 && hw != Hardware.CPU -> ColabException(
            "Colab rejected ${hw.label} (HTTP 400): this account has no quota/entitlement for it right now. Try T4, or CPU.",
            e.status, e.body,
        )
        else -> e
    }

    /** Releases a runtime (`colab stop`). */
    fun unassign(endpoint: String) {
        val url = tunUrl("unassign/$endpoint").build()
        val o = callJson(url)
        val token = o.str("token") ?: throw ColabException("Unexpected unassign response")
        call(url, "POST", mapOf("X-Goog-Colab-Token" to token))
    }

    /** Best-effort liveness ping used by Colab frontends while a notebook is open. */
    fun keepAlive(endpoint: String): Boolean = try {
        call(tunUrl("$endpoint/keep-alive/").build(), headers = mapOf("X-Colab-Tunnel" to "Google"))
        true
    } catch (e: Exception) {
        Log.d(TAG, "keep-alive failed for $endpoint: ${e.message}")
        false
    }

    /**
     * Credentials propagation used by drive.mount() / auth.authenticate_user() on the VM
     * (port of the CLI's drivefs hook). Returns null on success, or a URL the user must
     * open to grant access first.
     */
    fun propagateCredentials(endpoint: String, authType: String, dryRun: Boolean): PropagationResult {
        val url = "$domain/tun/m/credentials-propagation/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("authuser", "0")
            .addQueryParameter("authtype", authType)
            .addQueryParameter("version", "2")
            .addQueryParameter("dryrun", dryRun.toString())
            .addQueryParameter("propagate", "true")
            .addQueryParameter("record", "false")
            .build()
        val tokenResp = callJson(url)
        val xsrf = tokenResp.str("token")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file_id", "empty.ipynb").build()
        val res = callJson(url, "POST", xsrf?.let { mapOf("X-Goog-Colab-Token" to it) } ?: emptyMap(), form)
        return PropagationResult(res.bool("success") == true, res.str("unauthorized_redirect_uri"))
    }

    data class PropagationResult(val success: Boolean, val authorizationUrl: String?)

    /** URL that opens the Colab web UI attached to an existing runtime (`colab url`). */
    fun browserUrl(endpoint: String): String {
        val backendPath = "/tun/m/$endpoint"
        val dbu = java.net.URLEncoder.encode(backendPath, "UTF-8")
        return "$domain/notebooks/empty.ipynb?dbu=$dbu#datalabBackendUrl=$domain$backendPath"
    }
}

fun isNetworkError(e: Throwable): Boolean = e is IOException && e !is ColabException
