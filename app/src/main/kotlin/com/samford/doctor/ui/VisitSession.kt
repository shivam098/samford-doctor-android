package com.samford.doctor.ui

import com.samford.doctor.AppContainer
import com.samford.doctor.core.Ist
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.repo.HeaderFactory
import com.samford.doctor.data.repo.HistoryDocument
import com.samford.doctor.data.repo.PatientHistory
import com.samford.doctor.editor.EditorDoc
import com.samford.doctor.editor.EraserMode
import com.samford.doctor.editor.StampFactory
import com.samford.doctor.editor.ToolKind
import com.samford.doctor.editor.ToolState
import com.samford.doctor.editor.VisitEditor
import com.samford.doctor.ink.AddendumMeta
import com.samford.doctor.ink.Affine
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.ink.StampTemplate
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.Lab
import com.samford.doctor.model.Patient
import com.samford.doctor.model.Vitals
import com.samford.doctor.render.PageSpec
import com.samford.doctor.sync.Freezer
import com.samford.doctor.ui.write.SurfaceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

data class VisitInfo(
    val visit: VisitEntity,
    val patient: Patient,
    val doctor: Doctor,
    val vitals: Vitals?,
    val header: PageHeader,
    val parentTitle: String? = null,
    val parentVersion: Int? = null,
)

sealed interface VisitLoad {
    data object Loading : VisitLoad
    data class Ready(val editor: VisitEditor, val info: VisitInfo) : VisitLoad
    data class Failed(val message: String) : VisitLoad
}

enum class FollowUp(val days: Long?) { NONE(null), DAYS_3(3), WEEK_1(7), WEEKS_2(14), MONTH_1(30) }

/**
 * Everything one open visit (or one addendum draft) needs across the writing and review screens.
 * Lives in the activity-scoped AppViewModel and is closed when the visit is completed or left.
 */
class VisitSession(
    val visitId: String,
    val addendumDocId: String?,
    private val c: AppContainer,
    parent: CoroutineScope,
    private val say: (String) -> Unit,
) {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Main.immediate)

    private val _load = MutableStateFlow<VisitLoad>(VisitLoad.Loading)
    val load: StateFlow<VisitLoad> = _load.asStateFlow()
    val tool = MutableStateFlow(ToolState())
    private val _history = MutableStateFlow<PatientHistory?>(null)
    val history: StateFlow<PatientHistory?> = _history.asStateFlow()
    private val _labs = MutableStateFlow<List<Lab>>(emptyList())
    val labs: StateFlow<List<Lab>> = _labs.asStateFlow()
    val showHistory = MutableStateFlow(false)
    val followUp = MutableStateFlow(FollowUp.NONE)
    val addendumReason = MutableStateFlow("")
    val zoomReset = MutableStateFlow(0)
    val signature: List<InkStroke> get() = signatureCache
    private var signatureCache: List<InkStroke> = emptyList()

    val isAddendum: Boolean get() = addendumDocId != null

    private val editor: VisitEditor? get() = (_load.value as? VisitLoad.Ready)?.editor

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val loaded = c.visits.load(visitId) ?: run {
            _load.value = VisitLoad.Failed("This visit is no longer on the tablet.")
            return
        }
        val docs = if (addendumDocId == null) {
            loaded.documents.filter { !it.entity.isAddendum }
        } else {
            loaded.documents.filter { it.entity.docId == addendumDocId }
        }
        if (docs.isEmpty()) {
            _load.value = VisitLoad.Failed("Nothing to edit for this visit.")
            return
        }
        val readOnly = if (addendumDocId == null) loaded.visit.status != VisitStatus.DRAFT else docs.first().entity.status != DocStatus.DRAFT
        val editorDocs = docs.map {
            EditorDoc(
                docId = it.entity.docId,
                type = DocType.fromWire(it.entity.type),
                title = it.entity.title,
                pageIds = it.pages.map { p -> p.first },
                labs = c.visits.labsOf(it.entity),
                isAddendum = it.entity.isAddendum,
            )
        }
        val editor = VisitEditor(editorDocs, docs.flatMap { it.pages }.toMap(), c.journalWriter, c.journal, c.ids, readOnly)
        val settings = c.settings.current()
        signatureCache = settings.signature
        tool.value = tool.value.copy(fingerWriting = settings.fingerWriting)
        var parentTitle: String? = null
        var parentVersion: Int? = null
        if (addendumDocId != null) {
            val root = docs.first().entity.parentDocId?.let { c.db.visitDao().document(it) }
            parentTitle = root?.title
            parentVersion = root?.let { c.visits.latestVersionOf(it.docId)?.version }
            addendumReason.value = docs.first().entity.addendumReason.orEmpty()
        }
        val info = VisitInfo(
            visit = loaded.visit,
            patient = loaded.patient,
            doctor = loaded.doctor,
            vitals = loaded.vitals,
            header = HeaderFactory.build(loaded.visit, loaded.patient, loaded.doctor, loaded.vitals),
            parentTitle = parentTitle,
            parentVersion = parentVersion,
        )
        loaded.visit.followUpDate?.let { date ->
            val days = runCatching { LocalDate.parse(date).toEpochDay() - LocalDate.now(Ist.zone).toEpochDay() }.getOrNull()
            followUp.value = FollowUp.entries.firstOrNull { it.days == days } ?: FollowUp.NONE
        }
        showHistory.value = addendumDocId == null && loaded.visit.returning
        _load.value = VisitLoad.Ready(editor, info)
        scope.launch { _labs.value = c.queue.labs() }
        if (addendumDocId == null) scope.launch { refreshHistory() }
    }

    suspend fun refreshHistory() {
        val info = (_load.value as? VisitLoad.Ready)?.info ?: return
        _history.value = c.records.history(info.patient.patientId, visitId, info.visit.serverVisitId)
    }

    fun pageSpec(info: VisitInfo, doc: EditorDoc, pageIndex: Int, previewSignature: Boolean = false): PageSpec {
        val addendum = if (doc.isAddendum) {
            AddendumMeta("pending", info.parentVersion ?: 1, null, addendumReason.value.ifBlank { "…" }, info.parentTitle ?: doc.type.pageTitle)
        } else {
            null
        }
        val signature = if (previewSignature && signatureCache.isNotEmpty()) {
            c.freezer.signatureBlock(signatureCache, info.doctor, Instant.now()).copy(signedAtDisplay = "on completion (preview)")
        } else {
            null
        }
        return PageSpec(
            docType = doc.type,
            title = if (doc.isAddendum) "Addendum" else doc.title,
            header = info.header,
            pageNumber = pageIndex + 1,
            pageCount = doc.pageIds.size,
            docRef = Freezer.docRef(info.visit, doc.type, if (doc.isAddendum) (info.parentVersion ?: 1) + 1 else 1),
            signature = signature,
            addendum = addendum,
        )
    }

    fun setTool(transform: (ToolState) -> ToolState) {
        tool.value = transform(tool.value)
    }

    fun armStamp(template: StampTemplate) {
        tool.value = tool.value.copy(kind = ToolKind.STAMP, stamp = template)
    }

    fun toggleLab(docId: String, labId: String) {
        val e = editor ?: return
        val doc = e.doc(docId) ?: return
        val labs = if (labId in doc.labs) doc.labs - labId else doc.labs + labId
        e.setLabs(docId, labs)
        scope.launch { c.visits.setLabs(docId, labs) }
    }

    /** Brings an earlier prescription in as a clearly labelled, editable layer. */
    fun repeatPrescription(doc: HistoryDocument, visitDate: String) {
        val e = editor ?: return
        val sha = doc.inkSha256 ?: return say("This earlier prescription has no editable ink; use the page view instead.")
        scope.launch {
            val ink = c.records.inkFile(sha, doc.inkFormat)
                ?: return@launch say("This earlier prescription can't be repeated (its handwriting isn't in samford-ink/1).")
            val rx = e.state.value.docs.firstOrNull { it.type == DocType.PRESCRIPTION } ?: return@launch
            e.selectDocument(rx.docId)
            e.selectPage(0)
            val source = ink.pages.firstOrNull() ?: return@launch
            val origin = "repeat:${doc.key}@v${doc.version}"
            val strokes = source.strokes.map { it.copy(id = c.ids.shortId("s"), origin = origin) }
            val stamps = source.stamps.filter { it.kind != InkStamp.KIND_LABEL }.map { it.copy(id = c.ids.shortId("t"), origin = origin) }
            val label = StampFactory.label("↻ Repeated from prescription of $visitDate — review and edit before signing", 11000, 26500, c.ids.shortId("t"), origin)
            e.importLayer(strokes, stamps + label)
            say("Previous prescription added as an editable layer. Erase or change anything before signing.")
        }
    }

    val surfaceListener = object : SurfaceListener {
        override fun onStrokeFinished(stroke: InkStroke) {
            editor?.commitStroke(stroke)
        }

        override fun onEraseAt(xCu: Int, yCu: Int, radiusCu: Int, mode: EraserMode) {
            editor?.eraseAt(xCu, yCu, radiusCu, mode)
        }

        override fun onEraseEnd() {
            editor?.endErase()
        }

        override fun onStampTap(xCu: Int, yCu: Int) {
            val template = tool.value.stamp ?: return
            editor?.placeStamp(template, xCu, yCu)
            tool.value = tool.value.copy(kind = ToolKind.PEN, stamp = null)
        }

        override fun onLasso(polygonCu: IntArray) {
            editor?.lasso(polygonCu)
        }

        override fun selectedItems(): Pair<List<InkStroke>, List<InkStamp>> = editor?.selectedItems() ?: (emptyList<InkStroke>() to emptyList())

        override fun onSelectionTransformed(transform: Affine) {
            editor?.transformSelection(transform)
        }

        override fun onSelectionCleared() {
            editor?.clearSelection()
        }

        override fun onUndoGesture() {
            editor?.undo()
        }
    }

    /** Locks the visit and starts freezing in the background. Null on success. */
    suspend fun complete(): String? {
        c.journalWriter.flush()
        val days = followUp.value.days
        c.visits.setFollowUp(visitId, days?.let { LocalDate.now(Ist.zone).plusDays(it).toString() }, null)
        val problem = c.freezer.lockForCompletion(visitId)
        if (problem != null) return problem
        c.appScope.launch { c.freezer.freezeVisit(visitId) }
        return null
    }

    suspend fun completeAddendum(): String? {
        val docId = addendumDocId ?: return "Not an addendum."
        c.journalWriter.flush()
        val reason = addendumReason.value.trim()
        c.visits.setAddendumReason(docId, reason)
        return c.freezer.freezeAddendum(docId, reason)
    }

    fun close() {
        editor?.let { e ->
            e.state.value.docs.flatMap { it.pageIds }.forEach { pageId -> c.journalWriter.submit { c.journal.compact(pageId) } }
        }
        scope.cancel()
    }
}
