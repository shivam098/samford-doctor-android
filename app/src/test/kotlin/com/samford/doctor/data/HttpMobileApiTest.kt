package com.samford.doctor.data

import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.CompleteOutcome
import com.samford.doctor.data.remote.CompleteVisitRequest
import com.samford.doctor.data.remote.HttpMobileApi
import com.samford.doctor.data.remote.PlainErrors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The HTTP client against the mobile API v1 conventions (headers, envelopes, 409 MISSING_BLOBS, ETag). */
class HttpMobileApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = HttpMobileApi(
        baseProvider = { server.url("/").toString().trimEnd('/') },
        tokenProvider = { "tok-123" },
        deviceIdProvider = { "device-abcdef12" },
        appVersionCode = 1,
    )

    @After
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun baseUrlIsNormalisedToTheFullApiBase() {
        assertEquals("https://host.example/api/mobile/v1", HttpMobileApi.normalizeApiBase("host.example"))
        assertEquals("http://10.0.2.2:4320/api/mobile/v1", HttpMobileApi.normalizeApiBase("http://10.0.2.2:4320/api/mobile/v1/"))
        assertEquals("http://192.168.1.5:4320/api/mobile/v1", HttpMobileApi.normalizeApiBase(" http://192.168.1.5:4320 "))
        assertNull(HttpMobileApi.normalizeApiBase("not a url"))
    }

    @Test
    fun completionSendsAuthDeviceAndIdempotencyHeaders() = runBlocking {
        server.enqueue(json(201, """{"bundleId":"b-1","visitId":"v-1","visitStatus":"COMPLETED","receivedAt":"2026-11-21T05:00:00Z","recordHash":"h","documents":[{"clientDocId":"d1","documentId":"doc-1","version":1}],"extra":"ignored"}"""))
        val out = api.completeVisit("v-1", "b-1", CompleteVisitRequest("b-1", "2026-11-21T04:59:00Z", emptyList()))
        assertTrue(out is CompleteOutcome.Accepted)
        val recorded = server.takeRequest()
        assertEquals("/api/mobile/v1/doctor/visits/v-1/complete", recorded.path)
        assertEquals("Bearer tok-123", recorded.getHeader("Authorization"))
        assertEquals("device-abcdef12", recorded.getHeader("X-Device-Id"))
        assertEquals("b-1", recorded.getHeader("Idempotency-Key"))
        assertEquals("doctor-android", recorded.getHeader("X-Samford-App"))
        assertNull(recorded.getHeader("Cookie"))
    }

    @Test
    fun missingBlobsAreReadFromErrorDetails() = runBlocking {
        server.enqueue(json(409, """{"error":{"code":"MISSING_BLOBS","message":"Upload these first","retryable":true,"details":{"missing":[{"sha256":"aaa","mediaType":"image/png","role":"PAGE"}]},"requestId":"r1"}}"""))
        val out = api.completeVisit("v-1", "b-1", CompleteVisitRequest("b-1", "t", emptyList()))
        assertEquals(CompleteOutcome.MissingBlobs(listOf("aaa")), out)
    }

    @Test
    fun errorsArePlainLanguageAndTechnicalTextIsHidden() = runBlocking {
        server.enqueue(json(422, """{"error":{"code":"ROUTING_INVALID","message":"Labs can only receive investigations.","retryable":false}}"""))
        try {
            api.labs()
            fail()
        } catch (e: ApiException) {
            assertEquals("Labs can only receive investigations.", e.error.message)
            assertFalse(e.error.retryable)
        }
        server.enqueue(json(500, """{"error":{"code":"INTERNAL","message":"SqliteException: SELECT * FROM visits\n    at db.js:10","retryable":true}}"""))
        try {
            api.labs()
            fail()
        } catch (e: ApiException) {
            assertEquals(PlainErrors.fallback(500), e.error.message)
            assertTrue(e.error.retryable)
        }
        server.enqueue(json(401, """{"error":{"code":"DEVICE_MISMATCH","message":"Please sign in again.","retryable":false}}"""))
        try {
            api.labs()
            fail()
        } catch (e: ApiException) {
            assertTrue(e.error.isAuth)
        }
        server.enqueue(json(429, """{"error":{"code":"RATE_LIMITED","message":"Slow down","retryable":true}}""").setHeader("Retry-After", "12"))
        try {
            api.labs()
            fail()
        } catch (e: ApiException) {
            assertEquals(12_000L, e.error.retryAfterMs)
        }
    }

    @Test
    fun wrappedListsAndConditionalGets() = runBlocking {
        server.enqueue(json(200, """{"personas":[{"personaId":"doctor-a","doctorId":"doctor-a","name":"Dr A","qualification":"MBBS","registrationNo":"R1","department":"GM","departmentId":"gm"}]}"""))
        assertEquals("doctor-a", api.personas().single().personaId)
        assertNull(server.takeRequest().getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(304))
        val queue = api.queue("2026-11-21", "\"etag-1\"")
        assertTrue(queue.notModified)
        assertEquals("\"etag-1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun responseBodiesAreReadOffTheCallersThread() {
        val readers = java.util.Collections.synchronizedList(ArrayList<String>())
        val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body
            val probe = object : okio.ForwardingSource(original.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    readers += Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }
            response.newBuilder().body(probe.buffer().asResponseBody(original.contentType(), original.contentLength())).build()
        }.build()
        val offMain = HttpMobileApi({ server.url("/").toString() }, { "tok" }, { "device-abcdef12" }, 1, client)
        server.enqueue(json(200, """{"date":"2026-11-21","doctorId":"doctor-a","entries":[]}"""))
        server.enqueue(json(200, """{"labs":[]}"""))
        // A single-thread "caller" stands in for the main thread, where Android forbids network reads.
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "caller-main") }
        try {
            runBlocking(caller.asCoroutineDispatcher()) {
                offMain.queue("2026-11-21", null)
                offMain.labs()
            }
        } finally {
            caller.shutdown()
        }
        assertTrue(readers.isNotEmpty())
        assertTrue(readers.toString(), readers.none { it.startsWith("caller-main") })
    }

    @Test
    fun queueAcceptsNumericTokensAndBooleanPriority() = runBlocking {
        server.enqueue(json(200, """{"date":"2026-11-21","doctorId":"doctor-a","entries":[{"queueEntryId":"visit-a","visitId":"visit-a","token":1,"status":"WAITING","priority":false,"returning":true,"patient":{"patientId":"patient-a","uhid":"SMP-A","name":"Demo patient A","age":36,"sex":"Female","allergies":[{"substance":"Penicillin","reaction":"Sample rash","certainty":"Confirmed"}]},"vitals":null}]}"""))
        val entry = api.queue("2026-11-21", null).value!!.entries.single()
        assertEquals("1", entry.token)
        assertEquals("Penicillin (Sample rash)", entry.patient.allergies.single().display())
    }

    @Test
    fun requestBodiesOmitNullsAndUnknownKeys() {
        val body = com.samford.doctor.data.remote.ApiJson.requestJson.encodeToString(
            CompleteVisitRequest.serializer(),
            CompleteVisitRequest(
                "b0b0b0b0-0000-4000-8000-000000000001", "2026-11-21T05:00:00Z",
                listOf(
                    com.samford.doctor.data.remote.CompleteDocumentDto(
                        "d0d0d0d0-0000-4000-8000-000000000001", "PRESCRIPTION", "Prescription",
                        listOf(com.samford.doctor.data.remote.PageRefDto(0, "a".repeat(64))),
                        "b".repeat(64), "c".repeat(64), "samford-ink/1",
                        com.samford.doctor.model.Routing(pharmacy = true, labs = emptyList(), patient = true),
                        com.samford.doctor.data.remote.HintsDto(medicines = listOf(com.samford.doctor.data.remote.MedicineHintDto("Tab X"))),
                        "2026-11-21T05:00:00Z",
                    ),
                ),
            ),
        )
        assertFalse(body, body.contains("null"))
        assertTrue(body.contains("\"pages\":[{\"index\":0,\"pngSha256\":\"${"a".repeat(64)}\",\"widthPx\":1500,\"heightPx\":2121}]"))
        assertTrue(body.contains("\"inkFormat\":\"samford-ink/1\""))
    }

    @Test
    fun startVisitConfirmsIdentity() = runBlocking {
        server.enqueue(json(200, """{"queueEntryId":"v-9","visitId":"v-9","status":"IN_CONSULTATION","startedAt":"2026-11-21T04:00:00Z"}"""))
        assertEquals("v-9", api.startVisit("v-9", "start-local-1").visitId)
        val r = server.takeRequest()
        assertEquals("""{"identityConfirmed":true}""", r.body.readUtf8())
        assertEquals("start-local-1", r.getHeader("Idempotency-Key"))
    }

    @Test
    fun unreachableServerIsAPlainRetryableError() = runBlocking {
        server.shutdown()
        try {
            api.labs()
            fail()
        } catch (e: ApiException) {
            assertEquals(PlainErrors.NETWORK, e.error.code)
            assertTrue(e.error.retryable)
        }
    }
}
