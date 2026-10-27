package com.colabmcp.bridge

import android.content.Intent
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.colabmcp.core.tunnel.TunnelType
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.obj
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.sha256B64Url
import com.colabmcp.core.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsSignInAndServerControls() {
        // Signed out shows the sign-in button, signed in shows the ADC export.
        compose.onAllNodes(hasText("Sign in with Google") or hasText("Show ADC")).onFirst().assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Start MCP"))
        compose.onNodeWithText("Start MCP").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Public link (tunnel)"))
        compose.onNodeWithText(TunnelType.NGROK.label).assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Keep running in the background"))
        compose.onNodeWithText("Restart after reboot").assertExists()
    }
}

/**
 * Runs the real foreground service on the JVM (x86) and drives it like Gemini would:
 * OAuth (DCR + PKCE + approval code) and MCP tool calls against the live Colab account.
 * Needs COLAB_TEST_ADC=/path/to/application_default_credentials.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeServiceLiveTest {
    private val http = OkHttpClient.Builder().followRedirects(false).readTimeout(300, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    @Test
    fun serviceServesColabOverMcp() {
        val adc = System.getenv("COLAB_TEST_ADC")
        assumeTrue("COLAB_TEST_ADC not set", adc != null && File(adc).exists())
        val account = Graph.auth.importAdc(File(adc!!).readText())
        assertNotNull(account.email)

        Graph.settings.tunnelType = TunnelType.NONE
        Graph.settings.port = 18765
        val controller = Robolectric.buildService(BridgeService::class.java, Intent().setAction(BridgeService.ACTION_START)).create()
        controller.startCommand(0, 1)
        val deadline = System.currentTimeMillis() + 20_000
        while (Graph.status.value?.running != true && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(true, Graph.status.value?.running)
        assertTrue(BridgeService.isRunning)
        val base = "http://127.0.0.1:18765"

        // Unauthenticated -> 401 with resource metadata.
        http.newCall(Request.Builder().url("$base/mcp").post("{}".toRequestBody(json)).build()).execute().use {
            assertEquals(401, it.code)
            assertTrue(it.header("WWW-Authenticate")!!.contains("resource_metadata"))
        }

        // Dynamic client registration + authorization with the approval code + token.
        val redirect = "https://oauth-redirect.googleusercontent.com/r/user_bound_custom-mcp-1-colab"
        val reg = post("$base/register", jsonOf("client_name" to "Gemini", "redirect_uris" to listOf(redirect), "token_endpoint_auth_method" to "none"))
        val clientId = reg.str("client_id")!!
        val verifier = "v".repeat(50)
        val form = FormBody.Builder()
            .add("response_type", "code").add("client_id", clientId).add("redirect_uri", redirect).add("state", "st")
            .add("code_challenge", sha256B64Url(verifier)).add("code_challenge_method", "S256")
            .add("pin", Graph.pin.value).add("action", "approve").build()
        val location = http.newCall(Request.Builder().url("$base/authorize").post(form).build()).execute().use {
            assertEquals(302, it.code)
            it.header("Location")!!
        }
        val code = URLDecoder.decode(location.substringAfter("code=").substringBefore('&'), "UTF-8")
        val token = http.newCall(
            Request.Builder().url("$base/token").post(
                FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirect)
                    .add("code_verifier", verifier).add("client_id", clientId).build(),
            ).build(),
        ).execute().use { parseJsonObject(it.body!!.string()).str("access_token")!! }

        // MCP: initialize, list tools, call Colab.
        val init = mcp(base, token, "initialize", jsonOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "robolectric", "version" to "1")))
        assertEquals("2025-06-18", init.obj("result")!!.str("protocolVersion"))
        val tools = mcp(base, token, "tools/list", JsonObject(emptyMap())).obj("result")!!["tools"] as JsonArray
        assertTrue(tools.size >= 18)
        val acct = callTool(base, token, "colab_account", JsonObject(emptyMap()))
        println(acct)
        assertTrue(acct.contains(account.email!!))
        val sessions = callTool(base, token, "colab_list_sessions", JsonObject(emptyMap()))
        println(sessions)
        if (!sessions.startsWith("No active")) {
            val out = callTool(base, token, "colab_run_code", jsonOf("code" to "import platform\nprint('from robolectric', 6*7, platform.machine())"))
            println(out)
            assertTrue(out.contains("from robolectric 42"))
        }

        // The Stop button / notification action.
        controller.get().onStartCommand(Intent().setAction(BridgeService.ACTION_STOP), 0, 2)
        assertNull(Graph.bridge)
        assertEquals(false, Graph.settings.desiredRunning)
    }

    private fun post(url: String, body: JsonObject): JsonObject =
        http.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()).execute().use { parseJsonObject(it.body!!.string()) }

    private var id = 0

    private fun mcp(base: String, token: String, method: String, params: JsonObject): JsonObject {
        val body = jsonOf("jsonrpc" to "2.0", "id" to ++id, "method" to method, "params" to params)
        return http.newCall(
            Request.Builder().url("$base/mcp").header("Authorization", "Bearer $token")
                .header("Accept", "application/json, text/event-stream").post(body.toString().toRequestBody(json)).build(),
        ).execute().use {
            assertEquals(200, it.code)
            parseJsonObject(it.body!!.string())
        }
    }

    private fun callTool(base: String, token: String, name: String, args: JsonObject): String {
        val r = mcp(base, token, "tools/call", jsonOf("name" to name, "arguments" to args)).obj("result")!!
        return (r["content"] as JsonArray).joinToString("\n") { ((it as JsonObject)["text"] as? JsonPrimitive)?.content ?: "" }
    }
}
