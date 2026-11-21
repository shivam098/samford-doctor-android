package com.samford.doctor.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.samford.doctor.core.Clock
import com.samford.doctor.core.Ist
import com.samford.doctor.data.BlobStore
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.CacheEntity
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.DocumentEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.data.remote.LabResultDto
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.remote.PageRefDto
import com.samford.doctor.data.remote.RecordDto
import com.samford.doctor.ink.InkFile
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.model.DocType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HistoryDocument(
    val key: String,
    val type: DocType,
    val title: String,
    val version: Int,
    val versionCount: Int,
    val reason: String?,
    val pageShas: List<String>,
    val inkSha256: String?,
    val inkFormat: String?,
    val signedAt: String?,
)

data class HistoryVisit(
    val key: String,
    val sortKey: String,
    val date: String,
    val doctorName: String,
    val department: String?,
    val documents: List<HistoryDocument>,
    val fromTablet: Boolean,
)

data class PatientHistory(
    val visits: List<HistoryVisit>,
    val labResults: List<LabResultDto>,
    val message: String?,
)

/**
 * A returning patient's earlier handwritten pages and lab results. The hospital record is read
 * only while the doctor has an active visit with the patient (the server audits it); pages are
 * fetched by hash and cached immutably.
 */
class RecordRepository(
    private val api: MobileApi,
    private val db: SamfordDb,
    private val blobs: BlobStore,
    private val server: ServerRepository,
    private val session: SessionRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    private val json = ApiJson.json
    private val pageRefs = ListSerializer(PageRefDto.serializer())

    suspend fun history(patientId: String, currentVisitId: String?, serverVisitId: String?): PatientHistory {
        val local = localHistory(patientId, currentVisitId)
        var remote = emptyList<HistoryVisit>()
        var labs = DemoData.labResults(patientId)
        var message: String? = null
        if (!settings.current().offlineDemo && session.token != null && server.isOnline) {
            val key = "record:$patientId"
            val cached = db.cacheDao().get(key)
            val dto: RecordDto? = try {
                val res = api.patientRecord(patientId, serverVisitId, cached?.etag)
                if (res.notModified && cached != null) {
                    json.decodeFromString(RecordDto.serializer(), cached.json)
                } else {
                    res.value?.also { db.cacheDao().put(CacheEntity(key, json.encodeToString(RecordDto.serializer(), it), res.etag, clock.nowMs())) }
                }
            } catch (e: ApiException) {
                message = e.error.message
                cached?.let { runCatching { json.decodeFromString(RecordDto.serializer(), it.json) }.getOrNull() }
            }
            if (dto != null) {
                labs = dto.labResults
                remote = dto.visits.filter { it.visitId != serverVisitId }.map { v ->
                    HistoryVisit(
                        key = "srv:${v.visitId}",
                        sortKey = v.completedAt ?: v.date,
                        date = displayDate(v.date),
                        doctorName = v.doctorName,
                        department = v.department,
                        documents = v.documents.mapNotNull { d ->
                            val latest = d.versions.maxByOrNull { it.version } ?: return@mapNotNull null
                            HistoryDocument(
                                key = "srv:${d.documentId}",
                                type = DocType.fromWire(d.type),
                                title = d.title.ifBlank { DocType.fromWire(d.type).pageTitle },
                                version = latest.version,
                                versionCount = d.versions.size,
                                reason = latest.reason,
                                pageShas = latest.pages.sortedBy { it.index }.map { it.pngSha256 },
                                inkSha256 = latest.inkSha256,
                                inkFormat = latest.inkFormat,
                                signedAt = latest.signedAt,
                            )
                        },
                        fromTablet = false,
                    )
                }
            }
        }
        val remoteIds = remote.map { it.key.removePrefix("srv:") }.toSet()
        val tabletOnly = local.filter { it.second == null || it.second !in remoteIds }.map { it.first }
        return PatientHistory((remote + tabletOnly).sortedByDescending { it.sortKey }, labs, message)
    }

    private suspend fun localHistory(patientId: String, excludeVisitId: String?): List<Pair<HistoryVisit, String?>> =
        db.visitDao().completedVisitsForPatient(patientId).filter { it.visitId != excludeVisitId }.map { visit ->
            val docs = db.visitDao().documentsForVisit(visit.visitId)
            val originals = docs.filter { !it.isAddendum && it.status == DocStatus.FROZEN }
            val documents = originals.map { root ->
                val chain = listOf(root) + docs.filter { it.parentDocId == root.docId && it.status == DocStatus.FROZEN }
                val latest = chain.maxBy { it.version }
                toHistoryDocument(root, latest, chain.size)
            }
            val doctor = runCatching { InkFormat.readerJson.decodeFromString(com.samford.doctor.model.Doctor.serializer(), visit.doctorJson) }.getOrNull()
            HistoryVisit(
                key = "tab:${visit.visitId}",
                sortKey = Instant.ofEpochMilli(visit.startedAtMs).toString(),
                date = Ist.date(Instant.ofEpochMilli(visit.startedAtMs)),
                doctorName = doctor?.name.orEmpty(),
                department = doctor?.department,
                documents = documents,
                fromTablet = true,
            ) to visit.serverVisitId
        }

    private fun toHistoryDocument(root: DocumentEntity, latest: DocumentEntity, count: Int) = HistoryDocument(
        key = "tab:${root.docId}",
        type = DocType.fromWire(root.type),
        title = root.title,
        version = latest.version,
        versionCount = count,
        reason = latest.addendumReason,
        pageShas = latest.pageRefsJson?.let { runCatching { json.decodeFromString(pageRefs, it) }.getOrNull() }
            ?.sortedBy { it.index }?.map { it.pngSha256 }.orEmpty(),
        inkSha256 = latest.inkSha256,
        inkFormat = InkFormat.FORMAT,
        signedAt = latest.signedAt,
    )

    /** Loads a page image by hash (local cache first, then the hospital), downsampled for display. */
    suspend fun pageBitmap(sha256: String, targetWidthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = blobBytes(sha256, "image/png") ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The vector record behind a document, when it is samford-ink/1 (placeholders are skipped). */
    suspend fun inkFile(sha256: String, format: String?): InkFile? {
        if (format != null && format != InkFormat.FORMAT) return null
        val bytes = blobBytes(sha256, InkFormat.CONTENT_TYPE) ?: return null
        return runCatching { InkFormat.decode(bytes) }.getOrNull()
    }

    private suspend fun blobBytes(sha256: String, contentType: String): ByteArray? {
        blobs.read(sha256)?.let { return it }
        if (session.token == null || !server.isOnline) return null
        return try {
            val bytes = api.getBlob(sha256)
            if (blobs.putDownloaded(sha256, bytes, contentType)) bytes else null
        } catch (e: ApiException) {
            null
        }
    }

    private fun displayDate(isoDate: String): String = runCatching {
        LocalDate.parse(isoDate.take(10)).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
    }.getOrDefault(isoDate)
}
