package com.samford.doctor.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of [MobileApi]: bearer token plus X-Device-Id (no cookies), an
 * Idempotency-Key on every POST, If-None-Match on cacheable reads, and plain-language errors.
 */
class HttpMobileApi(
    private val baseProvider: () -> String?,
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    private val appVersionCode: Int,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = ApiJson.json,
    private val requestJson: Json = ApiJson.requestJson,
    /**
     * Told the URL of a request that the hospital API didn't answer: no connection, or an error
     * page without the API's error envelope (e.g. a tunnel that has moved). Lets the app re-run
     * discovery instead of retrying a dead address forever.
     */
    private val onUnreachable: (url: String) -> Unit = {},
) : MobileApi {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String, base: String? = baseProvider(), query: Map<String, String?> = emptyMap()): HttpUrl {
        val root = base?.let(::normalizeApiBase) ?: throw ApiException(PlainErrors.noServer)
        val parsed = (root + path).toHttpUrlOrNull() ?: throw ApiException(PlainErrors.noServer)
        val builder = parsed.newBuilder()
        query.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun request(url: HttpUrl, auth: Boolean = true): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Samford-App", "doctor-android")
            .header("X-Samford-App-Version", appVersionCode.toString())
        if (auth) {
            tokenProvider()?.let { b.header("Authorization", "Bearer $it") }
            deviceIdProvider()?.let { b.header("X-Device-Id", it) }
        }
        return b
    }

    /**
     * Executes [request] and runs [handle] on the IO dispatcher: reading a response body is network
     * I/O too, so it must never happen on the caller's (often main) thread.
     */
    private suspend fun <R> call(request: Request, handle: (Response) -> R): R = try {
        runInterruptible(Dispatchers.IO) { client.newCall(request).execute().use(handle) }
    } catch (e: IOException) {
        onUnreachable(request.url.toString())
        throw ApiException(PlainErrors.network())
    }

    private suspend fun <T> send(request: Request, serializer: KSerializer<T>): T = call(request) { response ->
        val body = response.body.string()
        if (!response.isSuccessful) throw ApiException(errorFrom(response, body))
        decode(body, serializer, response.code)
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>, status: Int): T = try {
        json.decodeFromString(serializer, body)
    } catch (e: Exception) {
        throw ApiException(PlainErrors.badResponse(status))
    }

    private fun envelope(body: String): ErrorEnvelope? =
        runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body) }.getOrNull()

    private fun errorFrom(response: Response, body: String): ApiError {
        val err = envelope(body)?.error
        // HEAD responses carry no body, so only a GET/POST/PUT without the envelope is a foreign answer.
        if (err == null && response.request.method != "HEAD") onUnreachable(response.request.url.toString())
        val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
        return PlainErrors.fromServer(response.code, err?.code.orEmpty(), err?.message.orEmpty(), err?.retryable ?: false, retryAfterMs)
    }

    private fun postJson(url: HttpUrl, body: String, idempotencyKey: String): Request =
        request(url).header("Idempotency-Key", idempotencyKey).post(body.toRequestBody(jsonType)).build()

    override suspend fun discover(discoveryUrl: String): DiscoveryDoc {
        val u = discoveryUrl.toHttpUrlOrNull() ?: throw ApiException(PlainErrors.noServer)
        // Discovery lives on a CDN that caches for minutes; never send credentials there.
        return send(Request.Builder().url(u).header("Accept", "application/json").get().build(), DiscoveryDoc.serializer())
    }

    override suspend fun meta(apiBase: String) {
        call(request(url("/meta", apiBase), auth = false).get().build()) { response ->
            if (!response.isSuccessful) throw ApiException(errorFrom(response, response.body.string()))
        }
    }

    override suspend fun personas(apiBase: String?): List<PersonaDto> =
        send(request(url("/doctor/personas", apiBase ?: baseProvider()), auth = false).get().build(), PersonasEnvelope.serializer()).personas

    override suspend fun signIn(request: SessionRequest, idempotencyKey: String): SessionDto =
        send(postJson(url("/doctor/sessions"), requestJson.encodeToString(SessionRequest.serializer(), request), idempotencyKey), SessionDto.serializer())

    override suspend fun labs(): List<LabDto> =
        send(request(url("/doctor/labs")).get().build(), LabsEnvelope.serializer()).labs

    override suspend fun queue(date: String, etag: String?): Conditional<QueueDto> =
        conditionalGet(url("/doctor/queue", query = mapOf("date" to date)), etag, QueueDto.serializer())

    override suspend fun startVisit(queueEntryId: String, idempotencyKey: String): StartVisitDto =
        send(
            postJson(url("/doctor/queue/${seg(queueEntryId)}/start"), requestJson.encodeToString(StartVisitRequest.serializer(), StartVisitRequest()), idempotencyKey),
            StartVisitDto.serializer(),
        )

    override suspend fun createWalkIn(request: WalkInRequest, idempotencyKey: String): QueueEntryDto =
        send(postJson(url("/doctor/walk-ins"), json.encodeToString(WalkInRequest.serializer(), request), idempotencyKey), QueueEntryDto.serializer())

    override suspend fun patientRecord(patientId: String, activeVisitId: String?, etag: String?): Conditional<RecordDto> =
        conditionalGet(url("/doctor/patients/${seg(patientId)}/record", query = mapOf("visitId" to activeVisitId)), etag, RecordDto.serializer())

    private suspend fun <T> conditionalGet(u: HttpUrl, etag: String?, serializer: KSerializer<T>): Conditional<T> {
        val b = request(u).get()
        etag?.let { b.header("If-None-Match", it) }
        return call(b.build()) { response ->
            if (response.code == 304) {
                Conditional<T>(null, etag, notModified = true)
            } else {
                val body = response.body.string()
                if (!response.isSuccessful) throw ApiException(errorFrom(response, body))
                Conditional(decode(body, serializer, response.code), response.header("ETag"), notModified = false)
            }
        }
    }

    override suspend fun blobExists(sha256: String): Boolean = call(request(url("/blobs/$sha256")).head().build()) { response ->
        when {
            response.isSuccessful -> true
            response.code == 404 -> false
            else -> throw ApiException(errorFrom(response, ""))
        }
    }

    override suspend fun putBlob(sha256: String, contentType: String, bytes: ByteArray) {
        val req = request(url("/blobs/$sha256")).put(bytes.toRequestBody(contentType.toMediaType())).build()
        call(req) { response ->
            if (!response.isSuccessful) throw ApiException(errorFrom(response, response.body.string()))
        }
    }

    override suspend fun getBlob(sha256: String): ByteArray = call(request(url("/blobs/$sha256")).get().build()) { response ->
        if (!response.isSuccessful) throw ApiException(errorFrom(response, response.body.string()))
        response.body.bytes()
    }

    override suspend fun completeVisit(visitId: String, idempotencyKey: String, request: CompleteVisitRequest): CompleteOutcome {
        val body = requestJson.encodeToString(CompleteVisitRequest.serializer(), request)
        return call(postJson(url("/doctor/visits/${seg(visitId)}/complete"), body, idempotencyKey)) { response ->
            val text = response.body.string()
            val missing = missingBlobs(response.code, text)
            when {
                missing != null -> CompleteOutcome.MissingBlobs(missing)
                !response.isSuccessful -> throw ApiException(errorFrom(response, text))
                else -> CompleteOutcome.Accepted(decode(text, CompleteVisitResponse.serializer(), response.code))
            }
        }
    }

    override suspend fun addAddendum(documentId: String, request: AddendumRequest): AddendumOutcome {
        val body = requestJson.encodeToString(AddendumRequest.serializer(), request)
        // The contract requires Idempotency-Key == clientAddendumId.
        return call(postJson(url("/doctor/documents/${seg(documentId)}/addenda"), body, request.clientAddendumId)) { response ->
            val text = response.body.string()
            val missing = missingBlobs(response.code, text)
            when {
                missing != null -> AddendumOutcome.MissingBlobs(missing)
                !response.isSuccessful -> throw ApiException(errorFrom(response, text))
                else -> AddendumOutcome.Accepted(decode(text, AddendumResponse.serializer(), response.code))
            }
        }
    }

    private fun missingBlobs(status: Int, body: String): List<String>? {
        if (status != 409) return null
        val error = envelope(body)?.error ?: return null
        if (error.code != "MISSING_BLOBS") return null
        return error.details?.missing?.map { it.sha256 }?.takeIf { it.isNotEmpty() }
    }

    override suspend fun delivery(visitId: String): DeliveryDto =
        send(request(url("/doctor/visits/${seg(visitId)}/delivery")).get().build(), DeliveryDto.serializer())

    override suspend fun events(cursor: String?): EventsDto =
        send(request(url("/doctor/events", query = mapOf("cursor" to cursor, "limit" to "200"))).get().build(), EventsDto.serializer())

    private fun seg(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val API_PREFIX = "/api/mobile/v1"

        /**
         * Returns the full API base (".../api/mobile/v1") for "https://host", "host:4320",
         * "https://host/" or an already-full base. Null for text that isn't a URL.
         */
        fun normalizeApiBase(raw: String): String? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            val full = if (withScheme.endsWith(API_PREFIX)) withScheme else withScheme + API_PREFIX
            val parsed = full.toHttpUrlOrNull() ?: return null
            return if (parsed.host.isBlank()) null else full
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
