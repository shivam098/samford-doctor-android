package com.samford.doctor.editor

import com.samford.doctor.core.IdGenerator
import com.samford.doctor.data.InkJournal
import com.samford.doctor.data.JournalWriter
import com.samford.doctor.ink.Affine
import com.samford.doctor.ink.BoxCu
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkGeometry
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.ink.PageDelta
import com.samford.doctor.ink.StampTemplate
import com.samford.doctor.ink.apply
import com.samford.doctor.model.DocType
import com.samford.doctor.render.PageTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ToolKind { PEN, HIGHLIGHTER, ERASER, LASSO, STAMP }

enum class EraserMode { STROKE, AREA }

enum class InkColor(val hex: String) { BLUE("#1A3C8C"), BLACK("#1B1B1F"), RED("#C62828") }

enum class PenWidth(val cu: Int) { FINE(160), MEDIUM(230), BOLD(330) }

data class ToolState(
    val kind: ToolKind = ToolKind.PEN,
    val color: InkColor = InkColor.BLUE,
    val width: PenWidth = PenWidth.MEDIUM,
    val eraserMode: EraserMode = EraserMode.STROKE,
    val stamp: StampTemplate? = null,
    val fingerWriting: Boolean = false,
) {
    companion object {
        const val HIGHLIGHT_HEX = "#FFC107"
        const val HIGHLIGHT_WIDTH_CU = 1400
    }
}

data class EditorDoc(
    val docId: String,
    val type: DocType,
    val title: String,
    val pageIds: List<String>,
    val labs: List<String>,
    val isAddendum: Boolean = false,
)

data class Selection(val pageId: String, val strokeIds: Set<String>, val stampIds: Set<String>, val box: BoxCu)

sealed interface EditorNotice {
    data class PageAdded(val docId: String, val pageNumber: Int) : EditorNotice
}

data class EditorState(
    val docs: List<EditorDoc>,
    val currentDocId: String,
    val pageIndex: Int,
    val revision: Long = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selection: Selection? = null,
    val notice: EditorNotice? = null,
) {
    val currentDoc: EditorDoc get() = docs.first { it.docId == currentDocId }
}

/**
 * In-memory editing state for one visit (or one addendum). Every change is applied immediately
 * for the UI and appended to the autosave journal in order; undo/redo are inverse deltas, kept per
 * document. Must be used from the main thread.
 */
class VisitEditor(
    docs: List<EditorDoc>,
    initialPages: Map<String, InkPage>,
    private val writer: JournalWriter,
    private val journal: InkJournal,
    private val ids: IdGenerator,
    val readOnly: Boolean = false,
) {
    private data class PageEdit(val pageId: String, val delta: PageDelta)

    private val pages = HashMap(initialPages)
    private val undo = HashMap<String, ArrayDeque<PageEdit>>()
    private val redo = HashMap<String, ArrayDeque<PageEdit>>()
    private var eraseGesture: PageEdit? = null
    private val _state = MutableStateFlow(EditorState(docs, docs.first().docId, 0))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val currentDoc: EditorDoc get() = _state.value.currentDoc
    val currentPageId: String
        get() {
            val doc = currentDoc
            return doc.pageIds[_state.value.pageIndex.coerceIn(0, doc.pageIds.lastIndex)]
        }

    fun page(pageId: String): InkPage = pages[pageId] ?: InkPage(0)
    fun currentPage(): InkPage = page(currentPageId)
    fun pagesOf(docId: String): List<InkPage> = doc(docId)?.pageIds?.map(::page).orEmpty()
    fun hasContent(docId: String): Boolean = pagesOf(docId).any { !it.isEmpty }
    fun doc(docId: String): EditorDoc? = _state.value.docs.firstOrNull { it.docId == docId }

    private fun docOf(pageId: String): String = _state.value.docs.first { pageId in it.pageIds }.docId
    private fun stack(map: HashMap<String, ArrayDeque<PageEdit>>, docId: String) = map.getOrPut(docId) { ArrayDeque() }

    private fun bump(selection: Selection? = _state.value.selection, notice: EditorNotice? = _state.value.notice) {
        _state.update {
            it.copy(
                revision = it.revision + 1,
                canUndo = stack(undo, it.currentDocId).isNotEmpty(),
                canRedo = stack(redo, it.currentDocId).isNotEmpty(),
                selection = selection,
                notice = notice,
            )
        }
    }

    fun selectDocument(docId: String) {
        if (doc(docId) == null) return
        _state.update { it.copy(currentDocId = docId, pageIndex = 0, selection = null) }
        bump(selection = null)
    }

    fun selectPage(index: Int) {
        val count = currentDoc.pageIds.size
        _state.update { it.copy(pageIndex = index.coerceIn(0, count - 1), selection = null) }
        bump(selection = null)
    }

    /** Adds a blank page at the end of [docId]; returns its 1-based number. */
    fun addPage(docId: String = _state.value.currentDocId, select: Boolean = true): Int {
        val doc = doc(docId) ?: return 0
        val pageId = ids.uuid()
        val index = doc.pageIds.size
        pages[pageId] = InkPage(index)
        writer.submit { journal.createPage(pageId, docId, index) }
        _state.update { s ->
            s.copy(
                docs = s.docs.map { if (it.docId == docId) it.copy(pageIds = it.pageIds + pageId) else it },
                pageIndex = if (select && s.currentDocId == docId) index else s.pageIndex,
                selection = null,
            )
        }
        bump(selection = null)
        return index + 1
    }

    private fun commit(pageId: String, delta: PageDelta, recordUndo: Boolean = true) {
        if (readOnly || delta.isEmpty) return
        pages[pageId] = page(pageId).apply(delta)
        writer.submit { journal.append(pageId, delta) }
        if (recordUndo) pushUndo(PageEdit(pageId, delta))
    }

    private fun pushUndo(edit: PageEdit) {
        val docId = docOf(edit.pageId)
        val u = stack(undo, docId)
        u.addLast(edit)
        while (u.size > MAX_UNDO) u.removeFirst()
        stack(redo, docId).clear()
    }

    fun commitStroke(stroke: InkStroke) {
        if (readOnly) return
        val pageId = currentPageId
        commit(pageId, PageDelta.addStroke(stroke))
        maybeAutoAddPage(stroke)
    }

    private fun maybeAutoAddPage(stroke: InkStroke) {
        val doc = currentDoc
        if (_state.value.pageIndex != doc.pageIds.lastIndex || stroke.pointCount == 0) {
            bump()
            return
        }
        var maxY = 0
        for (i in 0 until stroke.pointCount) maxY = maxOf(maxY, stroke.y(i))
        if (maxY > InkFormat.PAGE_HEIGHT_CU * PageTemplates.AUTO_PAGE_FRACTION) {
            val number = addPage(doc.docId, select = false)
            bump(notice = EditorNotice.PageAdded(doc.docId, number))
        } else {
            bump()
        }
    }

    /** One eraser sample. A whole eraser gesture becomes a single undo step at [endErase]. */
    fun eraseAt(xCu: Int, yCu: Int, radiusCu: Int, mode: EraserMode): Boolean {
        if (readOnly) return false
        val pageId = currentPageId
        val p = page(pageId)
        val stamps = p.stamps.filter { InkGeometry.hits(it, xCu, yCu, radiusCu) }
        val delta = when (mode) {
            EraserMode.STROKE -> PageDelta(removedStrokes = p.strokes.filter { InkGeometry.hits(it, xCu, yCu, radiusCu) }, removedStamps = stamps)
            EraserMode.AREA -> {
                val removed = ArrayList<InkStroke>()
                val added = ArrayList<InkStroke>()
                for (s in p.strokes) {
                    val pieces = InkGeometry.eraseArea(s, xCu, yCu, radiusCu) { ids.shortId("s") } ?: continue
                    removed += s
                    added += pieces
                }
                PageDelta(removedStrokes = removed, addedStrokes = added, removedStamps = stamps)
            }
        }
        if (delta.isEmpty) return false
        commit(pageId, delta, recordUndo = false)
        val g = eraseGesture
        eraseGesture = if (g != null && g.pageId == pageId) PageEdit(pageId, g.delta.then(delta)) else {
            g?.let(::pushUndo)
            PageEdit(pageId, delta)
        }
        bump()
        return true
    }

    fun endErase() {
        eraseGesture?.let(::pushUndo)
        eraseGesture = null
        bump()
    }

    fun placeStamp(template: StampTemplate, xCu: Int, yCu: Int) {
        if (readOnly) return
        commit(currentPageId, PageDelta.addStamp(StampFactory.create(template, xCu, yCu, ids.shortId("t"))))
        bump()
    }

    fun lasso(polygonCu: IntArray) {
        val pageId = currentPageId
        val (strokes, stamps) = InkGeometry.lassoSelect(page(pageId), polygonCu)
        val box = InkGeometry.bounds(page(pageId), strokes, stamps)
        bump(selection = if (box == null) null else Selection(pageId, strokes, stamps, box))
    }

    fun clearSelection() = bump(selection = null)

    fun transformSelection(m: Affine) {
        val sel = _state.value.selection ?: return
        val p = page(sel.pageId)
        val beforeStrokes = p.strokes.filter { it.id in sel.strokeIds }
        val beforeStamps = p.stamps.filter { it.id in sel.stampIds }
        val delta = PageDelta(
            removedStrokes = beforeStrokes,
            addedStrokes = beforeStrokes.map { InkGeometry.transform(it, m) },
            removedStamps = beforeStamps,
            addedStamps = beforeStamps.map { InkGeometry.transform(it, m) },
        )
        commit(sel.pageId, delta)
        val box = InkGeometry.bounds(page(sel.pageId), sel.strokeIds, sel.stampIds)
        bump(selection = box?.let { sel.copy(box = it) })
    }

    fun deleteSelection() {
        val sel = _state.value.selection ?: return
        val p = page(sel.pageId)
        commit(sel.pageId, PageDelta(removedStrokes = p.strokes.filter { it.id in sel.strokeIds }, removedStamps = p.stamps.filter { it.id in sel.stampIds }))
        bump(selection = null)
    }

    fun selectedItems(): Pair<List<InkStroke>, List<InkStamp>> {
        val sel = _state.value.selection ?: return emptyList<InkStroke>() to emptyList()
        val p = page(sel.pageId)
        return p.strokes.filter { it.id in sel.strokeIds } to p.stamps.filter { it.id in sel.stampIds }
    }

    fun undo() {
        val docId = _state.value.currentDocId
        val edit = stack(undo, docId).removeLastOrNull() ?: return
        commit(edit.pageId, edit.delta.inverse(), recordUndo = false)
        stack(redo, docId).addLast(edit)
        showPage(edit.pageId)
    }

    fun redo() {
        val docId = _state.value.currentDocId
        val edit = stack(redo, docId).removeLastOrNull() ?: return
        commit(edit.pageId, edit.delta, recordUndo = false)
        stack(undo, docId).addLast(edit)
        showPage(edit.pageId)
    }

    private fun showPage(pageId: String) {
        val index = currentDoc.pageIds.indexOf(pageId)
        if (index >= 0) _state.update { it.copy(pageIndex = index) }
        bump(selection = null)
    }

    /** Adds a whole layer (e.g. a repeated prescription) as one undoable step. */
    fun importLayer(strokes: List<InkStroke>, stamps: List<InkStamp>) {
        commit(currentPageId, PageDelta(addedStrokes = strokes, addedStamps = stamps))
        bump()
    }

    fun setLabs(docId: String, labs: List<String>) {
        _state.update { s -> s.copy(docs = s.docs.map { if (it.docId == docId) it.copy(labs = labs) else it }) }
        bump()
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    companion object {
        const val MAX_UNDO = 200
    }
}
