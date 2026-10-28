package com.colabmcp.core

import com.colabmcp.core.colab.Execution
import com.colabmcp.core.colab.Hardware
import com.colabmcp.core.colab.OutputFormatter
import com.colabmcp.core.http.HttpResponse
import com.colabmcp.core.http.HttpServer
import com.colabmcp.core.oauth.OAuthServer
import com.colabmcp.core.tunnel.SshTunnel
import com.colabmcp.core.tunnel.TunnelType
import com.colabmcp.core.util.MemorySecretStore
import com.colabmcp.core.util.jsonOf
import com.colabmcp.core.util.parseJsonObject
import com.colabmcp.core.util.sha256B64Url
import com.colabmcp.core.util.str
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelUrlTest {
    @Test
    fun localhostRunIgnoresAdminLinks() {
        val banner = """
            ===============================================================================
            Welcome to localhost.run!
            To set up and manage custom domains go to https://admin.localhost.run/
            More details on custom domains (and how to enable subdomains of your custom
            domain) at https://localhost.run/docs/custom-domains
            ** your connection id is 1234, please mention it if you send me a message about an issue. **
            4f3a2b1c9d8e7f.lhr.life tunneled with tls termination, https://4f3a2b1c9d8e7f.lhr.life
        """.trimIndent()
        assertEquals("https://4f3a2b1c9d8e7f.lhr.life", SshTunnel.findUrl(TunnelType.LOCALHOST_RUN, banner))
        assertNull(SshTunnel.findUrl(TunnelType.LOCALHOST_RUN, "Welcome to localhost.run! https://admin.localhost.run/"))
    }

    @Test
    fun serveoUsesForwardingLineWithColors() {
        val banner = "To claim a subdomain register at https://console.serveo.net/ssh/keys\r\n" +
            "\u001B[32mForwarding HTTP traffic from https://abc123.serveousercontent.com\u001B[0m\r\n"
        assertEquals("https://abc123.serveousercontent.com", SshTunnel.findUrl(TunnelType.SERVEO, banner))
    }

    @Test
    fun pinggyIgnoresDashboard() {
        val banner = "You are not authenticated. Upgrade at https://dashboard.pinggy.io\nhttp://rnabc-1-2-3-4.a.free.pinggy.link\nhttps://rnabc-1-2-3-4.a.free.pinggy.link\n"
        assertEquals("https://rnabc-1-2-3-4.a.free.pinggy.link", SshTunnel.findUrl(TunnelType.PINGGY, banner))
        // Current free domains (seen live in October 2026).
        val current = "You are not authenticated. Your tunnel will expire in 60 minutes. Upgrade to Pinggy Pro to get unrestricted tunnels. " +
            "https://dashboard.pinggy.io https://efero-136-70-235-187.free.pinggy.net https://rfvul-136-70-235-187.run.pinggy-free.link"
        assertEquals("https://efero-136-70-235-187.free.pinggy.net", SshTunnel.findUrl(TunnelType.PINGGY, current))
    }
}

class HardwareTest {
    @Test
    fun parsesNames() {
        assertEquals(Hardware.T4, Hardware.parse("t4"))
        assertEquals(Hardware.CPU, Hardware.parse(null))
        assertEquals(Hardware.TPU_V6E1, Hardware.parse("TPU"))
        assertEquals(Hardware.A100, Hardware.parse("gpu_a100"))
        val err = runCatching { Hardware.parse("V100") }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "unknown hardware must not silently fall back")
    }
}

class OutputFormatterTest {
    @Test
    fun collapsesProgressBars() {
        assertEquals("100%|####|\ndone", OutputFormatter.collapseCarriageReturns("10%|#   |\r50%|##  |\r100%|####|\ndone"))
    }

    @Test
    fun htmlTablesBecomeText() {
        val text = OutputFormatter.htmlToText("<table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>2</td></tr></table>")
        assertTrue(text.contains("a\tb") && text.contains("1\t2"), text)
    }

    @Test
    fun runningJobExplainsHowToContinue() {
        val e = Execution("job-1-x", "gpu", "print(1)", "python")
        val f = OutputFormatter.format(e)
        assertTrue(f.text.contains("colab_get_job_output"))
    }
}

class HttpServerBindTest {
    /** Regression: on Android getLoopbackAddress() is ::1 while tunnels forward to 127.0.0.1. */
    @Test
    fun bindsIpv4Loopback() {
        val server = HttpServer(0, false) { HttpResponse.text(200, "ok") }
        server.start()
        try {
            assertEquals("127.0.0.1", server.boundAddress?.hostAddress)
            java.net.Socket("127.0.0.1", server.boundPort).use { s ->
                s.getOutputStream().write("GET /x?bad=%zz HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n".toByteArray())
                val reply = s.getInputStream().bufferedReader().readText()
                assertTrue(reply.startsWith("HTTP/1.1 400"), reply)
            }
        } finally {
            server.stop()
        }
    }
}

class OAuthAndHttpTest {
    private val http = OkHttpClient.Builder().followRedirects(false).build()

    @Test
    fun fullAuthorizationCodeFlowWithChunkedBody() {
        val oauth = OAuthServer(MemorySecretStore())
        val server = HttpServer(0, false) { req ->
            when (req.path) {
                "/register" -> oauth.register(req)
                "/authorize" -> oauth.authorize(req)
                "/token" -> oauth.token(req)
                "/.well-known/oauth-authorization-server" -> oauth.authorizationServerMetadata(req)
                "/mcp" -> if (oauth.validateBearer(req) != null) HttpResponse.text(200, "ok") else oauth.unauthorized(req, req.header("authorization") != null)
                else -> HttpResponse.text(404, "")
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.boundPort}"
            val meta = get("$base/.well-known/oauth-authorization-server")
            assertEquals("$base/register", meta.str("registration_endpoint"))

            // Registration body sent chunked (no Content-Length) to exercise the server's parser.
            val regJson = jsonOf("client_name" to "Gemini", "redirect_uris" to listOf("https://oauth-redirect.googleusercontent.com/r/x", "javascript:alert(1)")).toString()
            val chunked = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8(regJson)
                }
            }
            val reg = http.newCall(Request.Builder().url("$base/register").post(chunked).build()).execute().use {
                assertEquals(201, it.code)
                parseJsonObject(it.body!!.string())
            }
            val clientId = reg.str("client_id")!!
            val redirect = "https://oauth-redirect.googleusercontent.com/r/x"
            val verifier = "verifier-" + "x".repeat(40)

            fun authorize(pin: String) = http.newCall(
                Request.Builder().url("$base/authorize").post(
                    FormBody.Builder().add("client_id", clientId).add("redirect_uri", redirect).add("state", "s1")
                        .add("code_challenge", sha256B64Url(verifier)).add("code_challenge_method", "S256")
                        .add("response_type", "code").add("pin", pin).add("action", "approve").build(),
                ).build(),
            ).execute()

            authorize("999999".takeIf { it != oauth.currentPin() } ?: "111111").use { assertEquals(401, it.code) }
            val pin = oauth.currentPin()
            val location = authorize(pin).use {
                assertEquals(302, it.code)
                it.header("Location")!!
            }
            assertTrue(location.startsWith("$redirect?code="))
            assertTrue(oauth.currentPin() != pin, "approval code rotates after use")
            val code = URLDecoder.decode(location.substringAfter("code=").substringBefore('&'), "UTF-8")

            // Wrong verifier fails, right one succeeds.
            fun token(v: String) = http.newCall(
                Request.Builder().url("$base/token").post(
                    FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirect)
                        .add("client_id", clientId).add("code_verifier", v).build(),
                ).build(),
            ).execute()
            token("wrong").use { assertEquals(400, it.code) }
            // The code was consumed by the failed attempt (single use), so register a fresh one.
            val location2 = authorize(oauth.currentPin()).use { it.header("Location")!! }
            val code2 = URLDecoder.decode(location2.substringAfter("code=").substringBefore('&'), "UTF-8")
            val tok = http.newCall(
                Request.Builder().url("$base/token").post(
                    FormBody.Builder().add("grant_type", "authorization_code").add("code", code2).add("redirect_uri", redirect)
                        .add("client_id", clientId).add("code_verifier", verifier).build(),
                ).build(),
            ).execute().use { parseJsonObject(it.body!!.string()) }
            val access = tok.str("access_token")
            assertNotNull(access)

            http.newCall(Request.Builder().url("$base/mcp").post("{}".toRequestBody()).build()).execute().use {
                assertEquals(401, it.code)
                assertTrue(it.header("WWW-Authenticate")!!.contains("resource_metadata="))
            }
            http.newCall(Request.Builder().url("$base/mcp").header("Authorization", "Bearer $access").post("{}".toRequestBody()).build()).execute().use {
                assertEquals(200, it.code)
            }
        } finally {
            server.stop()
        }
    }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { parseJsonObject(it.body!!.string()) }
}
