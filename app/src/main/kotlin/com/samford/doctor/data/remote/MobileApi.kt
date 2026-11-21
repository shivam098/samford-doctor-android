package com.samford.doctor.data.remote

data class Conditional<T>(val value: T?, val etag: String?, val notModified: Boolean)

sealed interface CompleteOutcome {
    data class Accepted(val response: CompleteVisitResponse) : CompleteOutcome
    data class MissingBlobs(val hashes: List<String>) : CompleteOutcome
}

sealed interface AddendumOutcome {
    data class Accepted(val response: AddendumResponse) : AddendumOutcome
    data class MissingBlobs(val hashes: List<String>) : AddendumOutcome
}

/** The doctor-facing slice of the Samford mobile API v1. All failures throw [ApiException]. */
interface MobileApi {
    suspend fun discover(discoveryUrl: String): DiscoveryDoc

    /** GET {apiBase}/meta: validates a candidate server. */
    suspend fun meta(apiBase: String)
    suspend fun personas(apiBase: String? = null): List<PersonaDto>
    suspend fun signIn(request: SessionRequest, idempotencyKey: String): SessionDto
    suspend fun labs(): List<LabDto>
    suspend fun queue(date: String, etag: String?): Conditional<QueueDto>
    suspend fun startVisit(queueEntryId: String, idempotencyKey: String): StartVisitDto
    suspend fun createWalkIn(request: WalkInRequest, idempotencyKey: String): QueueEntryDto
    suspend fun patientRecord(patientId: String, activeVisitId: String?, etag: String?): Conditional<RecordDto>
    suspend fun blobExists(sha256: String): Boolean
    suspend fun putBlob(sha256: String, contentType: String, bytes: ByteArray)
    suspend fun getBlob(sha256: String): ByteArray
    suspend fun completeVisit(visitId: String, idempotencyKey: String, request: CompleteVisitRequest): CompleteOutcome
    suspend fun addAddendum(documentId: String, request: AddendumRequest): AddendumOutcome
    suspend fun delivery(visitId: String): DeliveryDto
    suspend fun events(cursor: String?): EventsDto
}
