package com.samford.doctor.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.AppContainer
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.repo.ServerStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The demo server sits behind a tunnel whose address changes on restart; the tablet must follow. */
@RunWith(AndroidJUnit4::class)
class ServerRediscoveryTest {
    private val oldTunnel = MockWebServer().apply { start() }
    private val newTunnel = MockWebServer().apply { start() }
    private val gist = MockWebServer().apply { start() }

    @Volatile private var oldTunnelAlive = true
    @Volatile private var published = oldTunnel

    private fun api(server: MockWebServer) = server.url("/api/mobile/v1").toString()

    private fun hospital(alive: () -> Boolean) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            // A tunnel that has moved answers with the proxy's own error page, not the API envelope.
            !alive() -> MockResponse().setResponseCode(530).setBody("<html>error 1033</html>")
            request.path!!.endsWith("/meta") -> MockResponse().setBody("""{"apiVersion":"1.0.0","synthetic":true}""")
            request.path!!.endsWith("/doctor/labs") -> MockResponse().setBody("""{"labs":[]}""")
            else -> MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"Not found","retryable":false}}""")
        }
    }

    @After
    fun tearDown() {
        oldTunnel.shutdown()
        newTunnel.shutdown()
        gist.shutdown()
    }

    @Test
    fun aMovedTunnelIsFollowedThroughDiscovery() = runBlocking {
        oldTunnel.dispatcher = hospital { oldTunnelAlive }
        newTunnel.dispatcher = hospital { true }
        gist.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody("""{"schema":"samford-endpoint/1","status":"online","apiBaseUrl":"${api(published)}","synthetic":true}""")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val c = AppContainer(context, db = SamfordDb.inMemory(context), discoveryUrl = gist.url("/samford-endpoint.json").toString(), kickSync = {}, settingsName = "rediscovery-" + UUID.randomUUID())
        c.restore()
        assertEquals(ServerStatus.Online(api(oldTunnel), ServerStatus.Source.DISCOVERY), c.server.refresh())

        // The backend restarts behind a new tunnel and the gist is updated.
        oldTunnelAlive = false
        published = newTunnel
        try {
            c.api.labs()
            fail("the moved tunnel should not answer")
        } catch (e: ApiException) {
            assertTrue(e.error.retryable)
        }
        assertFalse("a foreign error page must drop the server to unreachable", c.server.isOnline)

        assertEquals(ServerStatus.Online(api(newTunnel), ServerStatus.Source.DISCOVERY), c.server.refresh())
        assertEquals(emptyList<Any>(), c.api.labs())
    }

    @Test
    fun anApiErrorWithItsEnvelopeKeepsTheServerOnline() = runBlocking {
        oldTunnel.dispatcher = hospital { true }
        gist.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody("""{"schema":"samford-endpoint/1","status":"online","apiBaseUrl":"${api(oldTunnel)}"}""")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val c = AppContainer(context, db = SamfordDb.inMemory(context), discoveryUrl = gist.url("/samford-endpoint.json").toString(), kickSync = {}, settingsName = "rediscovery-" + UUID.randomUUID())
        c.restore()
        c.server.refresh()
        try {
            c.api.delivery("missing-visit")
            fail()
        } catch (e: ApiException) {
            assertEquals("Not found", e.error.message)
        }
        assertTrue(c.server.isOnline)
    }
}
