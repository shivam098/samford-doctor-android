package com.samford.doctor.ui.write

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import com.samford.doctor.ink.Affine
import com.samford.doctor.ink.BoxCu
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.render.PageCompositor
import com.samford.doctor.render.PageSpec
import com.samford.doctor.render.RenderMode
import com.samford.doctor.render.StrokePainter
import java.util.concurrent.Executors

/**
 * The "dry" page: template, header and committed ink, rendered by the canonical compositor into
 * a screen-sized bitmap on a background thread. New strokes are painted into that bitmap
 * incrementally, so writing never waits for a full re-render. Pan/zoom shows the cached bitmap
 * transformed and re-renders when the gesture settles.
 */
@SuppressLint("ViewConstructor")
class PageCanvasView(context: Context, private val viewport: Viewport) : View(context) {
    private val deskColor = 0xFFE4E9F0.toInt()
    private val renderer = Executors.newSingleThreadExecutor { r -> Thread(r, "samford-page-render") }
    private val renderCompositor = PageCompositor()
    private val liveCompositor = PageCompositor()
    private val livePaint = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    private var spec: PageSpec? = null
    private var page: InkPage? = null
    private var pageId: String? = null

    private var dry: Bitmap? = null
    private var spare: Bitmap? = null
    private val dryMatrix = Matrix()
    private val dryInverse = Matrix()
    private val drawMatrix = Matrix()
    private var renderGeneration = 0
    private var renderInFlight = false
    private var renderPending = false
    private val drawnIncrementally = HashSet<String>()
    private val drawnSinceRequest = ArrayList<InkStroke>()

    /** Strokes committed to the record but still drawn by the wet-ink layer. */
    val pendingWet = HashSet<String>()

    /** Items hidden from the dry layer while a selection is being dragged. */
    private var hiddenIds: Set<String> = emptySet()
    private var previewItems: Pair<List<InkStroke>, List<InkStamp>>? = null
    var selectionPreview: Affine? = null
        set(value) {
            field = value
            invalidate()
        }
    var selectionBoxCu: BoxCu? = null
        set(value) {
            field = value
            invalidate()
        }
    var lassoPath: Path? = null
    var eraserCursor: PointF? = null
    var eraserRadiusPx: Float = 0f
    var hover: PointF? = null
    var hoverRadiusPx: Float = 0f

    /** Number of completed full renders (for tests). */
    var renderCount = 0
        private set

    private val settleRender = Runnable { requestRender() }

    fun setContent(pageId: String, spec: PageSpec, page: InkPage) {
        val old = this.page
        val sameSurface = pageId == this.pageId && spec == this.spec
        this.spec = spec
        this.page = page
        this.pageId = pageId
        if (sameSurface && old != null && onlyKnownStrokesAppended(old, page)) return
        requestRender()
    }

    fun currentPage(): InkPage? = page

    private fun onlyKnownStrokesAppended(old: InkPage, new: InkPage): Boolean {
        if (old.stamps != new.stamps || new.strokes.size < old.strokes.size) return false
        for (i in old.strokes.indices) if (old.strokes[i] !== new.strokes[i]) return false
        for (i in old.strokes.size until new.strokes.size) {
            val id = new.strokes[i].id
            if (id !in pendingWet && id !in drawnIncrementally) return false
        }
        return true
    }

    /** Called when the wet layer hands a finished stroke over. */
    fun handOff(stroke: InkStroke) {
        pendingWet.remove(stroke.id)
        val current = page ?: return
        if (current.strokes.none { it.id == stroke.id }) return // undone before it dried
        if (stroke.isHighlighter) {
            // Highlighter sits under pen ink in the canonical z-order: re-render for exactness.
            requestRender()
            return
        }
        val d = dry
        if (d == null) {
            requestRender()
            return
        }
        val c = Canvas(d)
        c.concat(dryMatrix)
        StrokePainter.draw(c, stroke, livePaint)
        drawnIncrementally += stroke.id
        if (renderInFlight) drawnSinceRequest += stroke
        invalidate()
    }

    fun beginSelectionDrag(strokes: List<InkStroke>, stamps: List<InkStamp>) {
        hiddenIds = (strokes.map { it.id } + stamps.map { it.id }).toSet()
        previewItems = strokes to stamps
        requestRender()
    }

    fun endSelectionDrag() {
        hiddenIds = emptySet()
        previewItems = null
        selectionPreview = null
        requestRender()
    }

    fun onViewportChanged() {
        invalidate()
        removeCallbacks(settleRender)
        postDelayed(settleRender, 140)
    }

    fun requestRender() {
        if (width == 0 || height == 0) return
        if (renderInFlight) {
            renderPending = true
            return
        }
        startRender()
    }

    private fun startRender() {
        val s = spec ?: return
        val p = page ?: return
        renderInFlight = true
        renderPending = false
        val generation = ++renderGeneration
        val w = width
        val h = height
        val matrix = Matrix(viewport.pageToView)
        val exclude = pendingWet + hiddenIds
        val filtered = if (exclude.isEmpty()) p else p.copy(
            strokes = p.strokes.filter { it.id !in exclude },
            stamps = p.stamps.filter { it.id !in exclude },
        )
        val reuse = spare
        spare = null
        drawnSinceRequest.clear()
        renderer.execute {
            val bmp = reuse?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(deskColor)
            c.save()
            c.concat(matrix)
            renderCompositor.drawPage(c, s, filtered, RenderMode.SCREEN)
            c.restore()
            post { onRendered(generation, bmp, matrix, filtered) }
        }
    }

    private fun onRendered(generation: Int, bitmap: Bitmap, matrix: Matrix, rendered: InkPage) {
        renderInFlight = false
        if (generation == renderGeneration) {
            spare = dry
            dry = bitmap
            dryMatrix.set(matrix)
            dryMatrix.invert(dryInverse)
            drawnIncrementally.clear()
            rendered.strokes.forEach { drawnIncrementally += it.id }
            val c = Canvas(bitmap)
            c.concat(dryMatrix)
            val currentIds = page?.strokes?.map { it.id }?.toSet().orEmpty()
            for (s in drawnSinceRequest) {
                if (s.id in currentIds && s.id !in drawnIncrementally) {
                    StrokePainter.draw(c, s, livePaint)
                    drawnIncrementally += s.id
                }
            }
            drawnSinceRequest.clear()
            renderCount++
            invalidate()
        } else {
            spare = bitmap
        }
        if (renderPending) startRender()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        spare = null
        requestRender()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(settleRender)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(deskColor)
        val d = dry
        if (d != null) {
            drawMatrix.set(dryInverse)
            drawMatrix.postConcat(viewport.pageToView)
            canvas.drawBitmap(d, drawMatrix, bitmapPaint)
        }
        drawSelection(canvas)
        drawOverlays(canvas)
    }

    private fun drawSelection(canvas: Canvas) {
        val preview = selectionPreview ?: Affine.IDENTITY
        previewItems?.let { (strokes, stamps) ->
            canvas.save()
            canvas.concat(viewport.pageToView)
            canvas.concat(preview.toPageUnitMatrix())
            strokes.forEach { StrokePainter.draw(canvas, it, livePaint) }
            stamps.forEach { liveCompositor.drawStamp(canvas, it) }
            canvas.restore()
        }
        val box = selectionBoxCu ?: return
        val pts = floatArrayOf(
            preview.mapX(box.left.toFloat(), box.top.toFloat()) / InkFormat.CU_PER_U, preview.mapY(box.left.toFloat(), box.top.toFloat()) / InkFormat.CU_PER_U,
            preview.mapX(box.right.toFloat(), box.bottom.toFloat()) / InkFormat.CU_PER_U, preview.mapY(box.right.toFloat(), box.bottom.toFloat()) / InkFormat.CU_PER_U,
        )
        viewport.pageToView.mapPoints(pts)
        val rect = RectF(pts[0], pts[1], pts[2], pts[3])
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.strokeWidth = 2f * density
        overlayPaint.color = 0xFF1A73E8.toInt()
        overlayPaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
        canvas.drawRect(rect, overlayPaint)
        overlayPaint.pathEffect = null
        overlayPaint.style = Paint.Style.FILL
        canvas.drawCircle(rect.right, rect.bottom, HANDLE_DP * density / 2f, overlayPaint)
    }

    fun selectionRectInView(): RectF? {
        val box = selectionBoxCu ?: return null
        val pts = floatArrayOf(
            box.left / InkFormat.CU_PER_U.toFloat(), box.top / InkFormat.CU_PER_U.toFloat(),
            box.right / InkFormat.CU_PER_U.toFloat(), box.bottom / InkFormat.CU_PER_U.toFloat(),
        )
        viewport.pageToView.mapPoints(pts)
        return RectF(pts[0], pts[1], pts[2], pts[3])
    }

    private fun drawOverlays(canvas: Canvas) {
        lassoPath?.let {
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 2f * density
            overlayPaint.color = 0xFF1A73E8.toInt()
            overlayPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
            canvas.drawPath(it, overlayPaint)
            overlayPaint.pathEffect = null
        }
        eraserCursor?.let {
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 1.5f * density
            overlayPaint.color = 0xFF5F6B7A.toInt()
            canvas.drawCircle(it.x, it.y, eraserRadiusPx, overlayPaint)
        }
        hover?.let {
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 1f * density
            overlayPaint.color = 0x991A3C8C.toInt()
            canvas.drawCircle(it.x, it.y, hoverRadiusPx.coerceAtLeast(3f * density), overlayPaint)
        }
    }

    companion object {
        const val HANDLE_DP = 22f
    }
}

/** The cU-space affine expressed in page units (translation scaled from cU to U). */
fun Affine.toPageUnitMatrix(): Matrix = Matrix().apply {
    setValues(floatArrayOf(a, c, e / InkFormat.CU_PER_U, b, d, f / InkFormat.CU_PER_U, 0f, 0f, 1f))
}
