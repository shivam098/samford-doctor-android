package com.samford.doctor.sync

import com.samford.doctor.core.Clock
import com.samford.doctor.data.BlobStore
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.BundleKind
import com.samford.doctor.data.db.DeliveryEntity
import com.samford.doctor.data.db.EventEntity
import com.samford.doctor.data.db.OutboxEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.remote.AddendumOutcome
import com.samford.doctor.data.remote.AddendumRequest
import com.samford.doctor.data.remote.ApiError
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.data.remote.CompleteOutcome
import com.samford.doctor.data.remote.CompleteVisitRequest
import com.samford.doctor.data.remote.CompleteVisitResponse
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.remote.WalkInRequest
import com.samford.doctor.data.repo.ServerRepository
import com.samford.doctor.data.repo.SessionRepository
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.model.DeliveryState
import com.samford.doctor.model.Patient
import com.samford.doctor.model.RecipientKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncRunResult(val attempted: Int, val received: Int, val retryLater: Boolean, val needsDoctor: Int)

fun OutboxEntity.toRecord() = OutboxRecord(
    bundleId = bundleId,
    state = OutboxState.fromName(state),
    attempts = attempts,
    missingRounds = missingRounds,
    nextAttemptAtMs = nextAttemptAtMs,
    lastError = lastErrorCode?.let { SyncError(it, lastErrorMessage.orEmpty(), lastErrorRetryable) },
    receivedAt = receivedAt,
)

fun OutboxEntity.with(record: OutboxRecord, nowMs: Long) = copy(
    state = record.state.name,
    attempts = record.attempts,
    missingRounds = record.missingRounds,
    nextAttemptAtMs = record.nextAttemptAtMs,
    lastErrorCode = record.lastError?.code,
    lastErrorMessage = record.lastError?.message,
    lastErrorRetryable = record.lastError?.retryable ?: false,
    receivedAt = record.receivedAt,
    updatedAtMs = nowMs,
)

/**
 * Uploads frozen bundles. Every step is idempotent: blobs are content-addressed (PUT by hash),
 * completion reuses the bundle id as Idempotency-Key with the byte-identical stored body, and the
 * server replays the original answer for repeats, so retries can never create duplicates.
 */
class SyncEngine(
    private val db: SamfordDb,
    private val api: MobileApi,
    private val blobs: BlobStore,
    private val session: SessionRepository,
    private val server: ServerRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val json = ApiJson.json
    private val reader = InkFormat.readerJson

    suspend fun runOnce(): SyncRunResult = mutex.withLock {
        // Only one run at a time exists, so anything still "active" was interrupted mid-flight.
        db.outboxDao().all().filter { OutboxState.fromName(it.state).isActive }.forEach {
            db.outboxDao().update(it.with(OutboxStateMachine.transition(it.toRecord(), OutboxEvent.Interrupted), clock.nowMs()))
        }
        val pending = db.outboxDao().all().filter { !OutboxState.fromName(it.state).isTerminal }
        if (pending.any { OutboxStateMachine.isDue(it.toRecord(), clock.nowMs()) } && !server.isOnline) server.refresh()

        var attempted = 0
        var received = 0
        for (item in pending) {
            val record = item.toRecord()
            if (!OutboxStateMachine.isDue(record, clock.nowMs())) continue
            attempted++
            if (process(item).state == OutboxState.RECEIVED.name) received++
        }
        val after = db.outboxDao().all()
        SyncRunResult(
            attempted = attempted,
            received = received,
            retryLater = after.any {
                val s = OutboxState.fromName(it.state)
                s == OutboxState.WAITING_RETRY || s == OutboxState.SAVED_ON_TABLET
            },
            needsDoctor = after.count { OutboxState.fromName(it.state).needsDoctor },
        )
    }

    /** Doctor tapped "Retry now" (or signed in again). */
    suspend fun retryNow(bundleId: String) = mutex.withLock {
        val item = db.outboxDao().get(bundleId) ?: return@withLock
        val s = OutboxState.fromName(item.state)
        if (s == OutboxState.WAITING_RETRY || s.needsDoctor) {
            db.outboxDao().update(item.with(OutboxStateMachine.transition(item.toRecord(), OutboxEvent.RetryNow), clock.nowMs()))
        }
    }

    suspend fun authRestored() = mutex.withLock {
        db.outboxDao().all().filter { OutboxState.fromName(it.state) == OutboxState.AUTH_REQUIRED }.forEach {
            db.outboxDao().update(it.with(OutboxStateMachine.transition(it.toRecord(), OutboxEvent.AuthRestored), clock.nowMs()))
        }
    }

    private suspend fun process(start: OutboxEntity): OutboxEntity {
        var entity = start
        suspend fun fire(event: OutboxEvent) {
            entity = entity.with(OutboxStateMachine.transition(entity.toRecord(), event), clock.nowMs())
            db.outboxDao().update(entity)
        }

        var visit = db.visitDao().visit(entity.visitId) ?: return entity
        // An addendum waits until its original has a server document id.
        val addendumTarget = if (entity.kind == BundleKind.ADDENDUM) addendumTargetId(entity) else null
        if (entity.kind == BundleKind.ADDENDUM && addendumTarget == null) return entity

        fire(OutboxEvent.Begin(needsServerVisit = entity.kind == BundleKind.COMPLETE && visit.serverVisitId == null))
        try {
            if (session.token == null) {
                throw ApiException(ApiError("AUTH_REQUIRED", "Sign in to the hospital server to upload this visit.", retryable = false, httpStatus = 401))
            }
            if (OutboxState.fromName(entity.state) == OutboxState.PREPARING_VISIT) {
                visit = ensureServerVisit(visit)
                fire(OutboxEvent.ServerVisitReady)
            }
            uploadBlobs(entity.bundleId)
            fire(OutboxEvent.BlobsUploaded)
            while (true) {
                val missing: List<String> = when (entity.kind) {
                    BundleKind.COMPLETE -> {
                        val request = reader.decodeFromString(CompleteVisitRequest.serializer(), entity.payloadJson)
                        when (val out = api.completeVisit(visit.serverVisitId!!, entity.bundleId, request)) {
                            is CompleteOutcome.Accepted -> {
                                recordCompletion(visit, request, out.response)
                                entity = entity.copy(responseJson = json.encodeToString(CompleteVisitResponse.serializer(), out.response))
                                fire(OutboxEvent.Accepted(out.response.receivedAt))
                                return entity
                            }
                            is CompleteOutcome.MissingBlobs -> out.hashes
                        }
                    }
                    else -> {
                        val request = reader.decodeFromString(AddendumRequest.serializer(), entity.payloadJson)
                        when (val out = api.addAddendum(addendumTarget!!, request)) {
                            is AddendumOutcome.Accepted -> {
                                entity.docId?.let { docId ->
                                    db.visitDao().document(docId)?.let { d ->
                                        db.visitDao().upsertDocument(d.copy(serverDocumentId = out.response.documentId, version = out.response.version))
                                    }
                                }
                                entity = entity.copy(responseJson = json.encodeToString(com.samford.doctor.data.remote.AddendumResponse.serializer(), out.response))
                                fire(OutboxEvent.Accepted(out.response.createdAt ?: clock.now().toString()))
                                return entity
                            }
                            is AddendumOutcome.MissingBlobs -> out.hashes
                        }
                    }
                }
                db.blobDao().markNotUploaded(missing)
                fire(OutboxEvent.MissingBlobs(missing))
                if (OutboxState.fromName(entity.state) != OutboxState.UPLOADING) return entity
                uploadBlobs(entity.bundleId)
                fire(OutboxEvent.BlobsUploaded)
            }
        } catch (e: ApiException) {
            val err = e.error
            fire(OutboxEvent.Failed(SyncError(err.code, err.message, err.retryable, err.isAuth), clock.nowMs()))
            val retryAfter = err.retryAfterMs
            if (retryAfter != null && OutboxState.fromName(entity.state) == OutboxState.WAITING_RETRY) {
                entity = entity.copy(nextAttemptAtMs = maxOf(entity.nextAttemptAtMs, clock.nowMs() + retryAfter))
                db.outboxDao().update(entity)
            }
            return entity
        } catch (e: IllegalStateException) {
            // Local problem (e.g. a page file missing): keep the bundle, ask a person to look.
            if (OutboxState.fromName(entity.state).isActive) {
                fire(
                    OutboxEvent.Failed(
                        SyncError("LOCAL_PROBLEM", "Some pages of this visit could not be read on this tablet. Please contact support; nothing was deleted.", retryable = false),
                        clock.nowMs(),
                    ),
                )
            }
            return entity
        }
    }

    private suspend fun addendumTargetId(entity: OutboxEntity): String? {
        val doc = entity.docId?.let { db.visitDao().document(it) } ?: return null
        val root = doc.parentDocId?.let { db.visitDao().document(it) } ?: return null
        return root.serverDocumentId
    }

    private suspend fun ensureServerVisit(visit: VisitEntity): VisitEntity {
        visit.serverVisitId?.let { return visit }
        val key = "start-${visit.visitId}"
        val serverId = if (visit.serverQueueEntry && visit.queueEntryId != null) {
            api.startVisit(visit.queueEntryId, key).visitId
        } else {
            // Offline demo walk-in: register the synthetic patient on the server, then start.
            val patient = reader.decodeFromString(Patient.serializer(), visit.patientJson)
            val entry = api.createWalkIn(WalkInRequest(patient.name, patient.age, sexWire(patient.sex)), "walkin-${visit.visitId}")
            api.startVisit(entry.queueEntryId, key).visitId
        }
        val updated = visit.copy(serverVisitId = serverId)
        db.visitDao().upsertVisit(updated)
        return updated
    }

    private suspend fun uploadBlobs(bundleId: String) {
        for (blob in db.outboxDao().blobsForBundle(bundleId)) {
            if (blob.uploadedAtMs != null) continue
            val bytes = blobs.read(blob.sha256) ?: throw IllegalStateException("missing local blob")
            api.putBlob(blob.sha256, blob.contentType, bytes)
            db.blobDao().markUploaded(blob.sha256, clock.nowMs())
        }
    }

    private suspend fun recordCompletion(visit: VisitEntity, request: CompleteVisitRequest, response: CompleteVisitResponse) {
        val labsById = runCatching { db.cacheDao().get("labs") }.getOrNull()
        val labNames = labsById?.let {
            runCatching { json.decodeFromString(com.samford.doctor.data.remote.LabsEnvelope.serializer(), it.json).labs.associate { l -> l.labId to l.name } }.getOrNull()
        }.orEmpty()
        val deliveries = ArrayList<DeliveryEntity>()
        for (ref in response.documents) {
            val doc = db.visitDao().document(ref.clientDocId) ?: continue
            db.visitDao().upsertDocument(doc.copy(serverDocumentId = ref.documentId, version = ref.version))
            val sent = request.documents.firstOrNull { it.clientDocId == ref.clientDocId } ?: continue
            if (sent.routing.pharmacy) deliveries += pending(visit, doc.docId, ref.documentId, ref.version, sent.type, RecipientKind.PHARMACY, null, "Pharmacy")
            sent.routing.labs.forEach { lab -> deliveries += pending(visit, doc.docId, ref.documentId, ref.version, sent.type, RecipientKind.LAB, lab, labNames[lab] ?: lab) }
            if (sent.routing.patient) deliveries += pending(visit, doc.docId, ref.documentId, ref.version, sent.type, RecipientKind.PATIENT, null, "Patient app")
        }
        db.deliveryDao().upsertAll(deliveries)
    }

    private fun pending(visit: VisitEntity, docId: String, serverDocId: String, version: Int, type: String, kind: RecipientKind, labId: String?, name: String) =
        DeliveryEntity(
            key = deliveryKey(serverDocId, kind.name, labId),
            visitId = visit.visitId,
            docId = docId,
            serverDocumentId = serverDocId,
            version = version,
            docType = type,
            recipientKind = kind.name,
            labId = labId,
            name = name,
            state = DeliveryState.PENDING.name,
            updatedAt = null,
        )

    /** Refreshes per-recipient delivery states for recently received visits and the events feed. */
    suspend fun pollStatus() {
        if (session.token == null || settings.current().offlineDemo) return
        if (!server.isOnline) return
        val received = db.outboxDao().all().filter { OutboxState.fromName(it.state) == OutboxState.RECEIVED }
        val weekAgo = clock.nowMs() - 7L * 24 * 3600 * 1000
        for (visitId in received.filter { it.updatedAtMs >= weekAgo }.map { it.visitId }.distinct()) {
            val visit = db.visitDao().visit(visitId) ?: continue
            val serverVisitId = visit.serverVisitId ?: continue
            val delivery = try {
                api.delivery(serverVisitId)
            } catch (e: ApiException) {
                continue
            }
            val rows = delivery.documents.flatMap { d ->
                val local = db.visitDao().documentByServerId(d.documentId)
                d.recipients.map { r ->
                    DeliveryEntity(
                        key = deliveryKey(d.documentId, r.kind, r.labId),
                        visitId = visitId,
                        docId = local?.docId,
                        serverDocumentId = d.documentId,
                        version = r.version ?: d.version,
                        docType = d.type ?: local?.type,
                        recipientKind = r.kind,
                        labId = r.labId,
                        name = r.name.ifBlank { r.kind.lowercase().replaceFirstChar { it.uppercase() } },
                        state = r.state,
                        updatedAt = r.updatedAt,
                    )
                }
            }
            db.deliveryDao().upsertAll(rows)
        }
        try {
            var cursor = settings.current().eventsCursor
            repeat(5) {
                val page = api.events(cursor)
                db.eventDao().insertAll(page.events.map { EventEntity(it.eventId, it.type, it.visitId, it.documentId, it.message, it.at, clock.nowMs()) })
                cursor = page.nextCursor ?: cursor
                settings.setEventsCursor(cursor)
                if (!page.hasMore) return
            }
        } catch (e: ApiException) {
            // Events are hints only; the next poll catches up.
        }
    }

    companion object {
        fun deliveryKey(serverDocId: String, kind: String, labId: String?) = "$serverDocId|$kind|${labId.orEmpty()}"

        fun sexWire(sex: String): String = when {
            sex.startsWith("F", ignoreCase = true) -> "Female"
            sex.startsWith("M", ignoreCase = true) -> "Male"
            else -> "Other"
        }
    }
}
