package com.samford.doctor.testing

import com.samford.doctor.core.Hashing
import com.samford.doctor.data.remote.AddendumOutcome
import com.samford.doctor.data.remote.AddendumRequest
import com.samford.doctor.data.remote.AddendumResponse
import com.samford.doctor.data.remote.ApiError
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.CompleteOutcome
import com.samford.doctor.data.remote.CompleteVisitRequest
import com.samford.doctor.data.remote.CompleteVisitResponse
import com.samford.doctor.data.remote.CompletedDocumentRefDto
import com.samford.doctor.data.remote.Conditional
import com.samford.doctor.data.remote.DeliveryDocumentDto
import com.samford.doctor.data.remote.DeliveryDto
import com.samford.doctor.data.remote.DiscoveryDoc
import com.samford.doctor.data.remote.EventsDto
import com.samford.doctor.data.remote.LabDto
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.remote.PatientDto
import com.samford.doctor.data.remote.PersonaDto
import com.samford.doctor.data.remote.PlainErrors
import com.samford.doctor.data.remote.QueueDto
import com.samford.doctor.data.remote.QueueEntryDto
import com.samford.doctor.data.remote.RecipientDto
import com.samford.doctor.data.remote.RecordDto
import com.samford.doctor.data.remote.SessionDto
import com.samford.doctor.data.remote.SessionRequest
import com.samford.doctor.data.remote.StartVisitDto
import com.samford.doctor.data.remote.WalkInRequest

/**
 * In-memory hospital that follows the mobile API v1 semantics the tablet depends on:
 * idempotent replays by key, content-addressed blobs with hash checks, MISSING_BLOBS, visits
 * that must be started before completion, addenda against the latest version, and 401s.
 */
class FakeHospital : MobileApi {
    val calls = mutableListOf<String>()
    val blobs = HashMap<String, ByteArray>()
    val completions = LinkedHashMap<String, CompleteVisitResponse>()
    val completionBodies = HashMap<String, CompleteVisitRequest>()
    val addenda = LinkedHashMap<String, AddendumResponse>()
    val addendumBodies = HashMap<String, AddendumRequest>()
    val documentVersions = HashMap<String, Int>()
    val startedVisits = HashSet<String>()
    private val walkInsByKey = HashMap<String, QueueEntryDto>()
    var queueEntries = mutableListOf<QueueEntryDto>()
    var online = true
    var authValid = true

    /** Thrown by the next mutating call (once). */
    var failNext: ApiError? = null

    /** The next completion is stored but the reply is lost (simulates a dropped connection). */
    var loseNextReply = false

    /** The next completion reports these hashes as missing (and forgets them). */
    var forceMissingOnce: List<String>? = null

    val personas = listOf(
        PersonaDto("doctor-a", "doctor-a", "Dr. Test A", "MBBS, MD", "TEST-001", "General medicine"),
        PersonaDto("doctor-b", "doctor-b", "Dr. Test B", "MBBS, MS", "TEST-002", "Surgery"),
    )
    val labs = listOf(LabDto("central-lab", "Central Lab", "PATHOLOGY"), LabDto("radiology", "Radiology", "IMAGING"))

    val completeCalls: Int get() = calls.count { it.startsWith("complete:") }
    val distinctCompletions: Int get() = completions.size

    private fun reachable() {
        if (!online) throw ApiException(PlainErrors.network())
    }

    private fun mutating(name: String) {
        reachable()
        failNext?.let {
            failNext = null
            calls += "$name:FAILED"
            throw ApiException(it)
        }
        if (!authValid) throw ApiException(ApiError("TOKEN_EXPIRED", "Please sign in again.", retryable = false, httpStatus = 401))
    }

    override suspend fun discover(discoveryUrl: String): DiscoveryDoc {
        reachable()
        return DiscoveryDoc(schema = "samford-endpoint/1", status = "online", apiBaseUrl = "http://fake.local/api/mobile/v1")
    }

    override suspend fun meta(apiBase: String) = reachable()

    override suspend fun personas(apiBase: String?): List<PersonaDto> {
        reachable()
        return personas
    }

    override suspend fun signIn(request: SessionRequest, idempotencyKey: String): SessionDto {
        reachable()
        authValid = true
        calls += "signIn:${request.personaId}"
        return SessionDto("token-${request.personaId}", doctor = personas.first { it.personaId == request.personaId })
    }

    override suspend fun labs(): List<LabDto> {
        reachable()
        return labs
    }

    override suspend fun queue(date: String, etag: String?): Conditional<QueueDto> {
        reachable()
        return Conditional(QueueDto(date, "doctor-a", queueEntries), "\"q-${queueEntries.size}\"", notModified = false)
    }

    override suspend fun startVisit(queueEntryId: String, idempotencyKey: String): StartVisitDto {
        mutating("start")
        calls += "start:$queueEntryId:$idempotencyKey"
        startedVisits += queueEntryId
        return StartVisitDto(queueEntryId, queueEntryId, "IN_CONSULTATION", "2026-11-21T04:00:00Z")
    }

    override suspend fun createWalkIn(request: WalkInRequest, idempotencyKey: String): QueueEntryDto {
        mutating("walkin")
        calls += "walkin:$idempotencyKey"
        return walkInsByKey.getOrPut(idempotencyKey) {
            val id = "visit-walkin-${walkInsByKey.size + 1}"
            QueueEntryDto(id, id, "W${walkInsByKey.size + 1}", "WAITING", patient = PatientDto("p-$id", "UH-$id", request.name, request.age, request.sex), demoWalkIn = true)
        }
    }

    override suspend fun patientRecord(patientId: String, activeVisitId: String?, etag: String?): Conditional<RecordDto> {
        reachable()
        return Conditional(RecordDto(PatientDto(patientId, name = "Patient $patientId")), "\"r1\"", false)
    }

    override suspend fun blobExists(sha256: String): Boolean {
        reachable()
        return sha256 in blobs
    }

    override suspend fun putBlob(sha256: String, contentType: String, bytes: ByteArray) {
        mutating("put")
        if (Hashing.sha256Hex(bytes) != sha256) throw ApiException(ApiError("HASH_MISMATCH", "Hash mismatch", false, 422))
        calls += "put:$sha256"
        blobs[sha256] = bytes
    }

    override suspend fun getBlob(sha256: String): ByteArray {
        reachable()
        return blobs[sha256] ?: throw ApiException(ApiError("NOT_FOUND", "Not found", false, 404))
    }

    override suspend fun completeVisit(visitId: String, idempotencyKey: String, request: CompleteVisitRequest): CompleteOutcome {
        mutating("complete")
        calls += "complete:$visitId:$idempotencyKey"
        completions[idempotencyKey]?.let { previous ->
            if (completionBodies[idempotencyKey] != request) {
                throw ApiException(ApiError("IDEMPOTENCY_KEY_REUSED", "Different body for the same key", false, 422))
            }
            return CompleteOutcome.Accepted(previous)
        }
        if (visitId !in startedVisits) throw ApiException(ApiError("VISIT_NOT_STARTED", "The visit was not started.", false, 409))
        forceMissingOnce?.let { forced ->
            forceMissingOnce = null
            forced.forEach { blobs.remove(it) }
            return CompleteOutcome.MissingBlobs(forced)
        }
        val missing = request.documents.flatMap { d -> d.pages.map { it.pngSha256 } + d.pdfSha256 + listOfNotNull(d.inkSha256) }.filter { it !in blobs }
        if (missing.isNotEmpty()) return CompleteOutcome.MissingBlobs(missing.distinct())
        val response = CompleteVisitResponse(
            bundleId = request.bundleId,
            visitId = visitId,
            visitStatus = "COMPLETED",
            receivedAt = "2026-11-21T05:00:00Z",
            recordHash = "bundle-hash",
            documents = request.documents.mapIndexed { i, d -> CompletedDocumentRefDto(d.clientDocId, "doc-$visitId-$i", 1, d.type, d.title, "rh-$i") },
        )
        response.documents.forEach { documentVersions[it.documentId] = 1 }
        completions[idempotencyKey] = response
        completionBodies[idempotencyKey] = request
        if (loseNextReply) {
            loseNextReply = false
            throw ApiException(PlainErrors.network())
        }
        return CompleteOutcome.Accepted(response)
    }

    override suspend fun addAddendum(documentId: String, request: AddendumRequest): AddendumOutcome {
        mutating("addendum")
        calls += "addendum:$documentId:${request.clientAddendumId}"
        addenda[request.clientAddendumId]?.let { return AddendumOutcome.Accepted(it) }
        val latest = documentVersions[documentId] ?: throw ApiException(ApiError("NOT_FOUND", "Unknown document", false, 404))
        if (request.baseVersion != latest) throw ApiException(ApiError("NOT_LATEST_VERSION", "A newer version exists.", false, 409))
        val missing = (request.pages.map { it.pngSha256 } + request.pdfSha256 + listOfNotNull(request.inkSha256)).filter { it !in blobs }
        if (missing.isNotEmpty()) return AddendumOutcome.MissingBlobs(missing.distinct())
        val response = AddendumResponse(documentId, latest + 1, latest, "rh-add", "2026-11-21T06:00:00Z")
        documentVersions[documentId] = latest + 1
        addenda[request.clientAddendumId] = response
        addendumBodies[request.clientAddendumId] = request
        return AddendumOutcome.Accepted(response)
    }

    override suspend fun delivery(visitId: String): DeliveryDto {
        reachable()
        val docs = completions.values.filter { it.visitId == visitId }.flatMap { it.documents }
        return DeliveryDto(
            visitId,
            docs.map { d ->
                DeliveryDocumentDto(
                    d.documentId, documentVersions[d.documentId] ?: 1, d.type, d.title,
                    buildList {
                        if (d.type == "PRESCRIPTION") add(RecipientDto("PHARMACY", null, "Pharmacy", "DISPENSED", 1, "2026-11-21T05:30:00Z"))
                        add(RecipientDto("PATIENT", null, "Patient app", "VIEWED", 1, "2026-11-21T05:31:00Z"))
                    },
                )
            },
        )
    }

    override suspend fun events(cursor: String?): EventsDto {
        reachable()
        return EventsDto()
    }
}
