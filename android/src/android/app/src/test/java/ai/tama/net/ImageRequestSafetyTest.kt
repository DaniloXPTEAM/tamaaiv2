package ai.tama.net

import io.github.nanomuse.net.*

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No real image provider is contacted; the servers are local fixtures. */
class ImageRequestSafetyTest {
    private fun post(url: String): Request = Request.Builder().url(url)
        .post("{\"prompt\":\"fixture\"}".toRequestBody("application/json".toMediaType()).singleUseImageBody())
        .build()

    @Test fun `503 Retry After zero and 408 cannot replay a billable body`() {
        val server = MockWebServer()
        server.start()
        try {
            for (status in listOf(503, 408)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
                val client = OkHttpClient().singleUseImageClient()
                val request = post(server.url("/images").toString())
                assertTrue(request.body!!.isOneShot())
                assertFalse(client.retryOnConnectionFailure)
                client.newCall(request).execute().use { assertEquals(status, it.code) }
            }
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("POST", request.method)
                assertEquals("{\"prompt\":\"fixture\"}", request.body.readUtf8())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun `redirects cannot move the request or follow up with a GET`() {
        val source = MockWebServer()
        val destination = MockWebServer()
        source.start()
        destination.start()
        try {
            for (status in listOf(302, 307, 308)) {
                source.enqueue(MockResponse().setResponseCode(status)
                    .setHeader("Location", destination.url("/other")))
                val client = OkHttpClient().singleUseImageClient()
                assertFalse(client.followRedirects)
                assertFalse(client.followSslRedirects)
                client.newCall(post(source.url("/images").toString())).execute().use {
                    assertEquals(status, it.code)
                }
            }
            assertEquals(3, source.requestCount)
            assertEquals(0, destination.requestCount)
        } finally {
            source.shutdown()
            destination.shutdown()
        }
    }

    @Test fun `a server authentication challenge never invokes an authenticator or repeats a post`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=fixture"))
            var calls = 0
            val base = OkHttpClient.Builder().authenticator(Authenticator { _, response ->
                calls++
                response.request.newBuilder().post("again".toRequestBody(null)).build()
            }).build()
            base.singleUseImageClient().newCall(post(server.url("/images").toString())).execute().use {
                assertEquals(401, it.code)
            }
            assertEquals(0, calls)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `proxy authentication remains available for CONNECT but cannot change its method or host`() {
        var calls = 0
        val request = Request.Builder().url("https://image-fixture.invalid/").method("CONNECT", null).build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(407).message("Proxy Authentication Required").build()
        val good = connectOnlyImageProxyAuthenticator(Authenticator { _, challenge ->
            calls++
            challenge.request.newBuilder().header("Proxy-Authorization", "Basic fixture").build()
        })
        assertEquals("Basic fixture", good.authenticate(null, response)?.header("Proxy-Authorization"))
        assertEquals(1, calls)
        val bad = connectOnlyImageProxyAuthenticator(Authenticator { _, challenge ->
            challenge.request.newBuilder().url("https://other.invalid/").build()
        })
        assertEquals(null, bad.authenticate(null, response))
    }

    @Test fun `an HTTP proxy authentication challenge cannot replay a submitted body`() {
        val proxy = MockWebServer()
        proxy.start()
        try {
            proxy.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=fixture"))
            var authentications = 0
            val base = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
                .proxyAuthenticator(Authenticator { _, response ->
                    authentications++
                    // A misbehaving authenticator could otherwise replace the one-shot
                    // body with a repeatable one, bypassing OkHttp's follow-up check.
                    response.request.newBuilder().post("again".toRequestBody(null)).build()
                }).build()
            val client = base.singleUseImageClient()
            client.newCall(post("http://image-fixture.invalid/images")).execute().use {
                assertEquals(407, it.code)
            }
            assertEquals(0, authentications)
            assertEquals(1, proxy.requestCount)
            assertEquals("{\"prompt\":\"fixture\"}", requireNotNull(proxy.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8())
        } finally {
            proxy.shutdown()
        }
    }
}
