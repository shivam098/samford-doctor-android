package com.samford.doctor.data.repo

import com.samford.doctor.core.Clock
import com.samford.doctor.core.IdGenerator
import com.samford.doctor.data.InkJournal
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.DocumentEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.remote.PlainErrors
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.EntrySource
import com.samford.doctor.model.Patient
import com.samford.doctor.model.QueueEntry
import com.samford.doctor.model.QueueStatus
import com.samford.doctor.model.Vitals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

data class LoadedDocument(val entity: DocumentEntity, val pages: List<Pair<String, InkPage>>)

data class LoadedVisit(
    val visit: VisitEntity,
    val patient: Patient,
    val doctor: Doctor,
    val vitals: Vitals?,
    val documents: List<LoadedDocument>,
)

sealed interface OpenResult {
    data class Opened(val visitId: String) : OpenResult
    data class Refused(val message: String) : OpenResult
}

class VisitRepository(
    private val db: SamfordDb,
    private val journal: InkJournal,
    private val api: MobileApi,
    private val server: ServerRepository,
    private val session: SessionRepository,
    private val settings: SettingsStore,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private val json = InkFormat.canonicalJson
    private val reader = InkFormat.readerJson
    private val stringList = ListSerializer(String.serializer())

    /** Opens (or resumes) the visit for a queue entry. The doctor has confirmed identity. */
    suspend fun openVisit(entry: QueueEntry): OpenResult {
        db.visitDao().visitForQueueEntry(entry.queueEntryId)?.let { existing ->
            if (!existing.isSeedHistory) return OpenResult.Opened(existing.visitId)
        }
        val doctor = settings.current().doctor ?: return OpenResult.Refused("Please sign in first.")
        if (entry.source == EntrySource.SERVER && entry.status == QueueStatus.BOOKED) {
            return OpenResult.Refused("This patient hasn't checked in at the front desk yet.")
        }
        val visitId = ids.uuid()
        var serverVisitId: String? = null
        if (entry.source == EntrySource.SERVER && session.token != null) {
            try {
                serverVisitId = api.startVisit(entry.queueEntryId, "start-$visitId").visitId
            } catch (e: ApiException) {
                val offline = e.error.code == PlainErrors.NETWORK || e.error.code == PlainErrors.NO_SERVER || e.error.retryable
                // Offline: keep writing; the upload starts the visit first (idempotent).
                if (!offline) return OpenResult.Refused(e.error.message)
            }
        }
        val now = clock.nowMs()
        db.visitDao().upsertVisit(
            VisitEntity(
                visitId = visitId,
                serverVisitId = serverVisitId,
                queueEntryId = entry.queueEntryId,
                serverQueueEntry = entry.source == EntrySource.SERVER,
                patientId = entry.patient.patientId,
                patientJson = json.encodeToString(Patient.serializer(), entry.patient),
                doctorJson = json.encodeToString(Doctor.serializer(), doctor),
                vitalsJson = entry.vitals?.let { json.encodeToString(Vitals.serializer(), it) },
                token = entry.token,
                returning = entry.returning,
                isDemo = entry.source == EntrySource.LOCAL_DEMO,
                status = VisitStatus.DRAFT,
                startedAtMs = now,
            ),
        )
        createDocuments(visitId, DocType.visitDefaults, now)
        return OpenResult.Opened(visitId)
    }

    suspend fun createDocuments(visitId: String, types: List<DocType>, now: Long) {
        val docs = types.mapIndexed { i, type ->
            DocumentEntity(docId = ids.uuid(), visitId = visitId, type = type.name, title = type.pageTitle, sortOrder = i, createdAtMs = now + i)
        }
        db.visitDao().upsertDocuments(docs)
        docs.forEach { journal.createPage(ids.uuid(), it.docId, 0) }
    }

    suspend fun load(visitId: String): LoadedVisit? {
        val visit = db.visitDao().visit(visitId) ?: return null
        val docs = db.visitDao().documentsForVisit(visitId).map { LoadedDocument(it, journal.pagesForDoc(it.docId)) }
        return LoadedVisit(
            visit = visit,
            patient = reader.decodeFromString(Patient.serializer(), visit.patientJson),
            doctor = reader.decodeFromString(Doctor.serializer(), visit.doctorJson),
            vitals = visit.vitalsJson?.let { reader.decodeFromString(Vitals.serializer(), it) },
            documents = docs,
        )
    }

    suspend fun addPage(docId: String, pageId: String, index: Int) = journal.createPage(pageId, docId, index)

    suspend fun setLabs(docId: String, labs: List<String>) {
        val doc = db.visitDao().document(docId) ?: return
        if (doc.status != DocStatus.DRAFT) return
        db.visitDao().upsertDocument(doc.copy(labsJson = json.encodeToString(stringList, labs)))
    }

    fun labsOf(doc: DocumentEntity): List<String> = runCatching { reader.decodeFromString(stringList, doc.labsJson) }.getOrDefault(emptyList())

    suspend fun setFollowUp(visitId: String, date: String?, note: String?) {
        val visit = db.visitDao().visit(visitId) ?: return
        if (visit.status != VisitStatus.DRAFT) return
        db.visitDao().upsertVisit(visit.copy(followUpDate = date, followUpNote = note))
    }

    /** Starts an addendum draft for a frozen document; returns the new draft's document id. */
    suspend fun createAddendumDraft(parentDocId: String): String? {
        val parent = db.visitDao().document(parentDocId) ?: return null
        if (parent.status != DocStatus.FROZEN) return null
        val existingDraft = db.visitDao().addendaFor(rootOf(parent).docId).firstOrNull { it.status == DocStatus.DRAFT }
        if (existingDraft != null) return existingDraft.docId
        val docId = ids.uuid()
        db.visitDao().upsertDocument(
            DocumentEntity(
                docId = docId,
                visitId = parent.visitId,
                type = parent.type,
                title = "Addendum",
                sortOrder = 100 + parent.sortOrder,
                labsJson = parent.labsJson,
                isAddendum = true,
                parentDocId = rootOf(parent).docId,
                createdAtMs = clock.nowMs(),
            ),
        )
        journal.createPage(ids.uuid(), docId, 0)
        return docId
    }

    /** Latest frozen version of the document chain that [doc] belongs to. */
    suspend fun latestVersionOf(rootDocId: String): DocumentEntity? {
        val root = db.visitDao().document(rootDocId) ?: return null
        return (listOf(root) + db.visitDao().addendaFor(rootDocId))
            .filter { it.status == DocStatus.FROZEN }
            .maxByOrNull { it.version }
    }

    private suspend fun rootOf(doc: DocumentEntity): DocumentEntity =
        doc.parentDocId?.let { db.visitDao().document(it) } ?: doc

    suspend fun setAddendumReason(docId: String, reason: String) {
        val doc = db.visitDao().document(docId) ?: return
        if (doc.status != DocStatus.DRAFT) return
        db.visitDao().upsertDocument(doc.copy(addendumReason = reason))
    }
}
