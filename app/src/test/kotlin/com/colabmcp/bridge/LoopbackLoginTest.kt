package com.colabmcp.bridge

import com.colabmcp.core.colab.GoogleAuth
import com.colabmcp.core.util.MemorySecretStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression: on a fresh install Android blocked the app's network while the browser was in
 * front, so the token exchange failed with "Unable to resolve host oauth2.googleapis.com".
 * The code must be kept and the exchange finished once the app is back in the foreground.
 */
class LoopbackLoginTest {
    @Test
    fun finishesSignInAfterNetworkComesBack() {
        val calls = AtomicInteger()
        var online = false
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"user@example.com"}""".toByteArray())
        val tokenJson = """{"access_token":"at","expires_in":3600,"refresh_token":"rt","scope":"openid","id_token":"h.$payload.s"}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            if (!online) throw UnknownHostException("Unable to resolve host \"oauth2.googleapis.com\": No address associated with hostname")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(tokenJson.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = GoogleAuth(MemorySecretStore(), http)
        val login = LoopbackLogin(auth)
        val url = login.start { throw AssertionError("must not report a result while offline") }

        // The browser follows Google's redirect back to the app's loopback server.
        val q = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val redirect = q.getValue("redirect_uri")
        val page = OkHttpClient().newCall(Request.Builder().url("${redirect}?state=${q.getValue("state")}&code=4%2Fabc").build()).execute().use { it.body!!.string() }
        assertTrue(page, page.contains("Almost done"))
        assertEquals(false, auth.isSignedIn)

        // Back in the app: network works again.
        online = true
        val done = CountDownLatch(1)
        var result: Result<GoogleAuth.Account>? = null
        login.finishDeferred {
            result = it
            done.countDown()
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals("user@example.com", result!!.getOrThrow().email)
        assertTrue(auth.isSignedIn)
        assertTrue(calls.get() >= 2)
    }
}
