package com.samford.doctor.sync

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.samford.doctor.core.Clock
import com.samford.doctor.core.IdGenerator
import com.samford.doctor.core.Ist
import com.samford.doctor.data.BlobStore
import com.samford.doctor.data.InkJournal
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.BundleKind
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.DocumentEntity
import com.samford.doctor.data.db.OutboxBlobEntity
import com.samford.doctor.data.db.OutboxEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.remote.AddendumRequest
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.data.remote.CompleteDocumentDto
import com.samford.doctor.data.remote.CompleteVisitRequest
import com.samford.doctor.data.remote.FollowUpDto
import com.samford.doctor.data.remote.HintsDto
import com.samford.doctor.data.remote.PageRefDto
import com.samford.doctor.data.repo.HeaderFactory
import com.samford.doctor.data.repo.HintBuilder
import com.samford.doctor.ink.AddendumMeta
import com.samford.doctor.ink.InkDocumentMeta
import com.samford.doctor.ink.InkFile
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.ink.SignatureBlock
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.Patient
import com.samford.doctor.model.Routing
import com.samford.doctor.model.Vitals
import com.samford.doctor.render.CompressedPdfPage
import com.samford.doctor.render.PageCompositor
import com.samford.doctor.render.PageRasterizer
import com.samford.doctor.render.PageSpec
import com.samford.doctor.render.PageTemplates
import com.samford.doctor.render.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

/** The immutable outcome of freezing one document version. */
data class FrozenDocument(
    val inkSha256: String,
    val pdfSha256: String,
    val pages: List<PageRefDto>,
    val hints: HintsDto?,
    val blobs: List<String>,
)

/**
 * Completion: freezes what the doctor wrote into an immutable, hashed record on the tablet
 * (samford-ink/1 JSON, canonical page PNGs, a PDF of exactly those pixels) and queues it for
 * upload. Freezing is re-runnable: completedAt and bundleId are persisted first, so a restart
 * mid-freeze produces byte-identical output.
 */
class Freezer(
    private val db: SamfordDb,
    private val journal: InkJournal,
    private val blobs: BlobStore,
    private val settings: SettingsStore,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val onQueued: () -> Unit,
) {
    private val reader = InkFormat.readerJson
    private val mutex = Mutex()
    private val compositor = PageCompositor()
    private val stringList = ListSerializer(String.serializer())
    private val pageRefList = ListSerializer(PageRefDto.serializer())

    /**
     * Step 1 (fast, on the doctor's tap): locks the visit for editing and persists the completion
     * time and bundle id. Returns a plain-language problem, or null when the visit is locked.
     */
    suspend fun lockForCompletion(visitId: String): String? {
        val visit = db.visitDao().visit(visitId) ?: return "This visit no longer exists on the tablet."
        if (visit.status != VisitStatus.DRAFT) return null
        if (settings.current().signature.isEmpty()) return "Please set up your signature first (Settings → Signature)."
        val docs = db.visitDao().documentsForVisit(visitId).filter { !it.isAddendum }
        val hasContent = docs.any { doc -> journal.pagesForDoc(doc.docId).any { !it.second.isEmpty } }
        if (!hasContent) return "Nothing has been written for this visit yet."
        db.visitDao().upsertVisit(
            visit.copy(status = VisitStatus.COMPLETING, completedAtMs = clock.nowMs(), bundleId = visit.bundleId ?: ids.uuid()),
        )
        return null
    }

    /** Step 2 (background): renders, hashes, stores and queues. Safe to call again after a crash. */
    suspend fun freezeVisit(visitId: String): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            val visit = db.visitDao().visit(visitId) ?: return@withContext
            if (visit.status != VisitStatus.COMPLETING) return@withContext
            val completedAt = Instant.ofEpochMilli(visit.completedAtMs ?: clock.nowMs())
            val bundleId = visit.bundleId ?: ids.uuid()
            val patient = reader.decodeFromString(Patient.serializer(), visit.patientJson)
            val doctor = reader.decodeFromString(Doctor.serializer(), visit.doctorJson)
            val vitals = visit.vitalsJson?.let { reader.decodeFromString(Vitals.serializer(), it) }
            val header = HeaderFactory.build(visit, patient, doctor, vitals)
            val signature = signatureBlock(settings.current().signature, doctor, completedAt)

            val requests = ArrayList<CompleteDocumentDto>()
            val updated = ArrayList<DocumentEntity>()
            val blobRefs = LinkedHashSet<String>()
            for (doc in db.visitDao().documentsForVisit(visitId).filter { !it.isAddendum && it.status == DocStatus.DRAFT }) {
                val pages = trimTrailingEmpty(journal.pagesForDoc(doc.docId).map { it.second })
                if (pages.isEmpty()) {
                    updated += doc.copy(status = DocStatus.EMPTY)
                    continue
                }
                val type = DocType.fromWire(doc.type)
                val frozen = freezeDocument(doc.docId, type, doc.title, docRef(visit, type, 1), pages, header, signature, null, emptyList())
                blobRefs += frozen.blobs
                val labs = runCatching { reader.decodeFromString(stringList, doc.labsJson) }.getOrDefault(emptyList())
                requests += CompleteDocumentDto(
                    clientDocId = doc.docId,
                    type = type.name,
                    title = doc.title,
                    pages = frozen.pages,
                    pdfSha256 = frozen.pdfSha256,
                    inkSha256 = frozen.inkSha256,
                    inkFormat = InkFormat.FORMAT,
                    routing = routingFor(type, labs),
                    hints = frozen.hints,
                    signedAt = completedAt.toString(),
                )
                updated += doc.copy(
                    status = DocStatus.FROZEN,
                    version = 1,
                    inkSha256 = frozen.inkSha256,
                    pdfSha256 = frozen.pdfSha256,
                    pageRefsJson = ApiJson.json.encodeToString(pageRefList, frozen.pages),
                    signedAt = completedAt.toString(),
                )
            }
            val request = CompleteVisitRequest(
                bundleId = bundleId,
                completedAt = completedAt.toString(),
                documents = requests,
                followUp = visit.followUpDate?.let { FollowUpDto(it, visit.followUpNote) },
            )
            val now = clock.nowMs()
            db.withTransaction {
                db.visitDao().upsertDocuments(updated)
                if (!visit.isSeedHistory) {
                    db.outboxDao().insert(
                        OutboxEntity(
                            bundleId = bundleId,
                            visitId = visitId,
                            kind = BundleKind.COMPLETE,
                            payloadJson = ApiJson.requestJson.encodeToString(CompleteVisitRequest.serializer(), request),
                            state = OutboxState.SAVED_ON_TABLET.name,
                            createdAtMs = now,
                            updatedAtMs = now,
                        ),
                    )
                    db.outboxDao().insertBlobRefs(blobRefs.map { OutboxBlobEntity(bundleId, it) })
                }
                db.visitDao().upsertVisit(visit.copy(status = VisitStatus.COMPLETED, bundleId = bundleId))
            }
        }
        onQueued()
    }

    /** Re-runs freezes that a crash or reboot interrupted. */
    suspend fun resumeInterrupted() {
        db.visitDao().visitsWithStatus(VisitStatus.COMPLETING).forEach { freezeVisit(it.visitId) }
    }

    /**
     * Freezes an addendum draft as the next immutable version of its document. The new version
     * contains every earlier page unchanged (same hashes) followed by the addendum pages.
     */
    suspend fun freezeAddendum(addendumDocId: String, reason: String): String? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val doc = db.visitDao().document(addendumDocId) ?: return@withContext "This addendum no longer exists."
            if (doc.status != DocStatus.DRAFT || !doc.isAddendum) return@withContext null
            if (reason.isBlank()) return@withContext "Please give a reason for the addendum."
            val root = doc.parentDocId?.let { db.visitDao().document(it) } ?: return@withContext "The original document is missing."
            val latest = (listOf(root) + db.visitDao().addendaFor(root.docId))
                .filter { it.status == DocStatus.FROZEN }.maxByOrNull { it.version }
                ?: return@withContext "The original document hasn't been completed yet."
            val pages = trimTrailingEmpty(journal.pagesForDoc(doc.docId).map { it.second })
            if (pages.isEmpty()) return@withContext "Write the correction on the addendum page first."
            if (settings.current().signature.isEmpty()) return@withContext "Please set up your signature first."
            val visit = db.visitDao().visit(doc.visitId) ?: return@withContext "The visit is missing."
            val patient = reader.decodeFromString(Patient.serializer(), visit.patientJson)
            val doctor = reader.decodeFromString(Doctor.serializer(), visit.doctorJson)
            val vitals = visit.vitalsJson?.let { reader.decodeFromString(Vitals.serializer(), it) }
            val signedAt = clock.now()
            val header = HeaderFactory.build(visit, patient, doctor, vitals)
            val signature = signatureBlock(settings.current().signature, doctor, signedAt)
            val type = DocType.fromWire(root.type)
            val previous = latest.pageRefsJson?.let { reader.decodeFromString(pageRefList, it) }.orEmpty()
            val meta = AddendumMeta(
                previousDocumentId = root.serverDocumentId ?: "local:${root.docId}",
                previousVersion = latest.version,
                previousInkSha256 = latest.inkSha256,
                reason = reason.trim(),
                parentTitle = root.title,
            )
            val version = latest.version + 1
            val frozen = freezeDocument(doc.docId, type, "Addendum", docRef(visit, type, version), pages, header, signature, meta, previous)
            val bundleId = ids.uuid()
            val request = AddendumRequest(
                clientAddendumId = bundleId,
                baseVersion = latest.version,
                reason = reason.trim(),
                pages = frozen.pages,
                pdfSha256 = frozen.pdfSha256,
                inkSha256 = frozen.inkSha256,
                inkFormat = InkFormat.FORMAT,
                signedAt = signedAt.toString(),
                hints = frozen.hints,
            )
            val now = clock.nowMs()
            db.withTransaction {
                db.visitDao().upsertDocument(
                    doc.copy(
                        status = DocStatus.FROZEN,
                        version = version,
                        addendumReason = reason.trim(),
                        inkSha256 = frozen.inkSha256,
                        pdfSha256 = frozen.pdfSha256,
                        pageRefsJson = ApiJson.json.encodeToString(pageRefList, frozen.pages),
                        signedAt = signedAt.toString(),
                    ),
                )
                if (!visit.isSeedHistory) {
                    db.outboxDao().insert(
                        OutboxEntity(
                            bundleId = bundleId,
                            visitId = visit.visitId,
                            kind = BundleKind.ADDENDUM,
                            docId = doc.docId,
                            payloadJson = ApiJson.requestJson.encodeToString(AddendumRequest.serializer(), request),
                            state = OutboxState.SAVED_ON_TABLET.name,
                            createdAtMs = now,
                            updatedAtMs = now,
                        ),
                    )
                    db.outboxDao().insertBlobRefs(frozen.blobs.map { OutboxBlobEntity(bundleId, it) })
                }
            }
            null
        }.also { if (it == null) onQueued() }
    }

    /** Freezes a synthetic earlier visit for offline demos (no upload). */
    suspend fun freezeSeedVisit(visit: VisitEntity, documents: List<Pair<DocumentEntity, List<InkPage>>>, signatureStrokes: List<InkStroke>) {
        val patient = reader.decodeFromString(Patient.serializer(), visit.patientJson)
        val doctor = reader.decodeFromString(Doctor.serializer(), visit.doctorJson)
        val vitals = visit.vitalsJson?.let { reader.decodeFromString(Vitals.serializer(), it) }
        val completedAt = Instant.ofEpochMilli(visit.completedAtMs ?: visit.startedAtMs)
        val header = HeaderFactory.build(visit, patient, doctor, vitals)
        val signature = signatureBlock(signatureStrokes, doctor, completedAt)
        val frozenDocs = withContext(Dispatchers.Default) {
            documents.map { (doc, pages) ->
                val type = DocType.fromWire(doc.type)
                val f = freezeDocument(doc.docId, type, doc.title, docRef(visit, type, 1), pages, header, signature, null, emptyList())
                doc.copy(
                    status = DocStatus.FROZEN, version = 1, inkSha256 = f.inkSha256, pdfSha256 = f.pdfSha256,
                    pageRefsJson = ApiJson.json.encodeToString(pageRefList, f.pages), signedAt = completedAt.toString(),
                )
            }
        }
        db.withTransaction {
            db.visitDao().upsertVisit(visit)
            db.visitDao().upsertDocuments(frozenDocs)
        }
    }

    fun signatureBlock(strokes: List<InkStroke>, doctor: Doctor, at: Instant) = SignatureBlock(
        strokes = strokes,
        signedAt = at.toString(),
        signedAtDisplay = Ist.dateTime(at),
        doctorName = doctor.name,
        registrationNo = doctor.registrationNo,
    )

    /** Renders and stores one document version; [previous] pages are carried over unchanged. */
    suspend fun freezeDocument(
        clientDocId: String,
        type: DocType,
        title: String,
        docRef: String,
        pages: List<InkPage>,
        header: PageHeader,
        signature: SignatureBlock,
        addendum: AddendumMeta?,
        previous: List<PageRefDto>,
    ): FrozenDocument {
        val reindexed = pages.mapIndexed { i, p -> p.copy(index = i) }
        val ink = InkFile(
            document = InkDocumentMeta(clientDocId, type.name, title, PageTemplates.templateId(type, addendum != null), PageTemplates.VERSION, header.visitRef),
            header = header,
            pages = reindexed,
            signature = signature,
            addendum = addendum,
        )
        val inkBytes = InkFormat.encode(ink)
        val inkSha = blobs.put(inkBytes, InkFormat.CONTENT_TYPE)
        // Render from the stored bytes so the images are, by construction, a rendering of the record.
        val stored = InkFormat.decode(inkBytes)
        val pdfPages = ArrayList<CompressedPdfPage>()
        val refs = ArrayList<PageRefDto>(previous)
        val blobList = ArrayList<String>()
        blobList += inkSha
        for (ref in previous) {
            val png = blobs.read(ref.pngSha256) ?: error("An earlier page is missing on this tablet")
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("An earlier page could not be read")
            pdfPages += PdfWriter.compress(PageRasterizer.toPdfPage(bmp))
            bmp.recycle()
            blobList += ref.pngSha256
        }
        stored.pages.forEachIndexed { i, page ->
            val spec = PageSpec(
                docType = type,
                title = title,
                header = stored.header,
                pageNumber = i + 1,
                pageCount = stored.pages.size,
                docRef = docRef,
                signature = stored.signature,
                addendum = stored.addendum,
            )
            val bmp = PageRasterizer.render(spec, page, compositor)
            val png = PageRasterizer.encodePng(bmp)
            val sha = blobs.put(png, "image/png")
            refs += PageRefDto(previous.size + i, sha, bmp.width, bmp.height)
            pdfPages += PdfWriter.compress(PageRasterizer.toPdfPage(bmp))
            bmp.recycle()
            blobList += sha
        }
        val pdf = PdfWriter.writeCompressed(pdfPages, "$title · ${header.uhid}", docRef)
        val pdfSha = blobs.put(pdf, "application/pdf")
        blobList += pdfSha
        return FrozenDocument(inkSha, pdfSha, refs, HintBuilder.build(reindexed), blobList.distinct())
    }

    companion object {
        fun trimTrailingEmpty(pages: List<InkPage>): List<InkPage> {
            val last = pages.indexOfLast { !it.isEmpty }
            return if (last < 0) emptyList() else pages.subList(0, last + 1)
        }

        /** Contract: pharmacy only for prescriptions, labs only for investigations, patient always. */
        fun routingFor(type: DocType, labs: List<String>) = Routing(
            pharmacy = type == DocType.PRESCRIPTION,
            labs = if (type == DocType.INVESTIGATIONS) labs.distinct() else emptyList(),
            patient = true,
        )

        fun docRef(visit: VisitEntity, type: DocType, version: Int): String =
            "${HeaderFactory.visitRef(visit)}-${type.shortCode}" + if (version > 1) "-v$version" else ""
    }
}
