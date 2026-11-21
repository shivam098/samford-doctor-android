package com.samford.doctor.ui.write

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.input.motionprediction.MotionEventPredictor
import com.samford.doctor.core.RandomIds
import com.samford.doctor.editor.EraserMode
import com.samford.doctor.editor.Selection
import com.samford.doctor.editor.ToolKind
import com.samford.doctor.editor.ToolState
import com.samford.doctor.ink.Affine
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.input.InputRouter
import com.samford.doctor.input.InputSample
import com.samford.doctor.input.PointerTool
import com.samford.doctor.input.Route
import com.samford.doctor.render.PageSpec
import com.samford.doctor.render.PageTemplates
import com.samford.doctor.render.StrokePainter
import kotlin.math.hypot
import kotlin.math.max

interface SurfaceListener {
    fun onStrokeFinished(stroke: InkStroke)
    fun onEraseAt(xCu: Int, yCu: Int, radiusCu: Int, mode: EraserMode)
    fun onEraseEnd()
    fun onStampTap(xCu: Int, yCu: Int)
    fun onLasso(polygonCu: IntArray)
    fun selectedItems(): Pair<List<InkStroke>, List<InkStamp>>
    fun onSelectionTransformed(transform: Affine)
    fun onSelectionCleared()
    fun onUndoGesture()
}

/**
 * The writing surface: routes stylus, eraser and finger input (see [InputRouter]), feeds the
 * low-latency wet-ink layer with motion prediction, and hands finished strokes to the editor.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class InkSurfaceView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    val viewport = Viewport(marginPx = 16f * density)
    val pageView = PageCanvasView(context, viewport)
    private val wet: WetInkLayer = WetInkLayer.create(context, viewport)
    private val router = InputRouter(palmTouchMajorPx = 100f * density)
    private val predictor: MotionEventPredictor? = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    var listener: SurfaceListener? = null
    var tool: ToolState = ToolState()
        set(value) {
            field = value
            pageView.hoverRadiusPx = brushRadiusPx()
        }

    /** When false the surface only navigates (completed, read-only pages). */
    var editable: Boolean = true

    val isLowLatencyInk: Boolean get() = wet.isLowLatency

    private enum class Mode { NONE, INK, ERASE, LASSO, STAMP_TAP, NAVIGATE, IGNORE, DRAG_MOVE, DRAG_SCALE }

    private var mode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeTool = PointerTool.UNKNOWN
    private val collector = StrokeCollector()
    private var inkTool = InkStroke.TOOL_PEN
    private var inkColor = ""
    private var inkWidth = 0
    private var eraseMode = EraserMode.STROKE
    private val lassoCu = ArrayList<Int>()
    private val lassoPath = Path()
    private var downX = 0f
    private var downY = 0f
    private var maxPointers = 0
    private var twoFingerDownTime = 0L
    private var navMoved = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val cu = IntArray(2)
    private var dragStart = PointF()
    private var dragAffine = Affine.IDENTITY

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                viewport.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                pageView.onViewportChanged()
                navMoved = true
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    init {
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        contentDescription = "Handwriting page"
    }

    fun setContent(pageId: String, spec: PageSpec, page: InkPage, selection: Selection?) {
        pageView.setContent(pageId, spec, page)
        pageView.selectionBoxCu = selection?.box
    }

    fun resetZoom() {
        viewport.reset()
        pageView.onViewportChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.setViewSize(w, h)
    }

    // Dispatched before the window surface is destroyed on app switch or screen off.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        wet.setHostVisible(visibility == VISIBLE)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    private fun pointerTool(e: MotionEvent, index: Int): PointerTool = when (e.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerTool.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerTool.ERASER_TIP
        MotionEvent.TOOL_TYPE_FINGER -> PointerTool.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> PointerTool.MOUSE
        else -> PointerTool.UNKNOWN
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onLastUp(e)
            MotionEvent.ACTION_CANCEL -> cancelGesture(e)
        }
        return true
    }

    private fun onFirstDown(e: MotionEvent) {
        val index = e.actionIndex
        val pt = pointerTool(e, index)
        activePointerId = e.getPointerId(index)
        activeTool = pt
        downX = e.getX(index)
        downY = e.getY(index)
        maxPointers = 1
        navMoved = false
        val route = router.route(InputSample(pt, e.buttonState, e.getTouchMajor(index), e.pointerCount, e.eventTime), tool.kind, tool.fingerWriting)
        val writes = route == Route.INK || route == Route.LASSO || route == Route.STAMP_TAP || route == Route.ERASE
        if (!editable && writes) {
            mode = if (pt == PointerTool.FINGER) Mode.NAVIGATE else Mode.IGNORE
            if (mode == Mode.NAVIGATE) beginNavigate(e)
            return
        }
        if (writes && pageView.selectionBoxCu != null) {
            val hit = selectionHit(downX, downY)
            if (hit != null) {
                beginDrag(hit)
                return
            }
            pageView.selectionBoxCu = null
            listener?.onSelectionCleared()
        }
        if ((route == Route.INK || route == Route.STAMP_TAP) && !inWritingArea(downX, downY)) {
            // The patient header and footer are printed automatically; ink can't start on them.
            mode = if (pt == PointerTool.FINGER) Mode.NAVIGATE.also { beginNavigate(e) } else Mode.IGNORE
            return
        }
        mode = when (route) {
            Route.INK -> Mode.INK.also { beginInk(e, index) }
            Route.ERASE -> Mode.ERASE.also { beginErase(e, index, sideButton = InputRouter.isEraserButton(e.buttonState) || pt == PointerTool.ERASER_TIP) }
            Route.LASSO -> Mode.LASSO.also { beginLasso(e, index) }
            Route.STAMP_TAP -> Mode.STAMP_TAP
            Route.NAVIGATE -> Mode.NAVIGATE.also { beginNavigate(e) }
            Route.IGNORE -> Mode.IGNORE
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        maxPointers = max(maxPointers, e.pointerCount)
        when (mode) {
            Mode.INK -> if (activeTool == PointerTool.FINGER) {
                // A second finger turns finger-writing into a pinch/pan.
                cancelInk(e)
                mode = Mode.NAVIGATE
                beginNavigate(e)
            }
            Mode.NAVIGATE -> {
                if (e.pointerCount == 2) twoFingerDownTime = e.eventTime
                anchorFocus(e)
            }
            else -> Unit
        }
    }

    private fun onMove(e: MotionEvent) {
        val index = e.findPointerIndex(activePointerId)
        when (mode) {
            Mode.INK -> if (index >= 0) {
                predictor?.record(e)
                collector.add(e, index, viewport)
                val partial = if (wet.isLowLatency) null else collector.snapshot("wet", inkTool, inkColor, inkWidth)
                wet.move(e, index, predictor?.predict(), partial)
            }
            Mode.ERASE -> if (index >= 0) eraseAlong(e, index)
            Mode.LASSO -> if (index >= 0) addLassoPoints(e, index)
            Mode.NAVIGATE -> navigate(e)
            Mode.DRAG_MOVE, Mode.DRAG_SCALE -> if (index >= 0) updateDrag(e.getX(index), e.getY(index))
            else -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        val upId = e.getPointerId(e.actionIndex)
        when (mode) {
            Mode.INK -> if (upId == activePointerId) finishInk(e, e.actionIndex)
            Mode.NAVIGATE -> anchorFocus(e, excludeIndex = e.actionIndex)
            else -> Unit
        }
    }

    private fun onLastUp(e: MotionEvent) {
        val index = e.actionIndex
        when (mode) {
            Mode.INK -> finishInk(e, index)
            Mode.ERASE -> endErase()
            Mode.LASSO -> endLasso()
            Mode.STAMP_TAP -> if (hypot(e.getX(index) - downX, e.getY(index) - downY) <= touchSlop * 2) {
                viewport.toCu(e.getX(index), e.getY(index), cu)
                listener?.onStampTap(cu[0], cu[1])
            }
            Mode.NAVIGATE -> {
                if (maxPointers == 2 && !navMoved && e.eventTime - twoFingerDownTime < TWO_FINGER_TAP_MS) listener?.onUndoGesture()
                pageView.onViewportChanged()
            }
            Mode.DRAG_MOVE, Mode.DRAG_SCALE -> endDrag()
            else -> Unit
        }
        mode = Mode.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun cancelGesture(e: MotionEvent) {
        when (mode) {
            Mode.INK -> cancelInk(e)
            Mode.ERASE -> endErase()
            Mode.LASSO -> {
                pageView.lassoPath = null
                pageView.invalidate()
            }
            Mode.DRAG_MOVE, Mode.DRAG_SCALE -> {
                pageView.endSelectionDrag()
            }
            else -> Unit
        }
        mode = Mode.NONE
    }

    private fun inWritingArea(x: Float, y: Float): Boolean {
        viewport.toCu(x, y, cu)
        val yU = cu[1] / InkFormat.CU_PER_U.toFloat()
        return cu[0] in 0..InkFormat.PAGE_WIDTH_CU && yU >= PageTemplates.WRITING_TOP - 4f && yU <= PageTemplates.FOOTER_RULE
    }

    // --- ink ---------------------------------------------------------------------------------

    private fun beginInk(e: MotionEvent, index: Int) {
        requestUnbufferedDispatch(e)
        val highlighter = tool.kind == ToolKind.HIGHLIGHTER
        inkTool = if (highlighter) InkStroke.TOOL_HIGHLIGHTER else InkStroke.TOOL_PEN
        inkColor = if (highlighter) ToolState.HIGHLIGHT_HEX else tool.color.hex
        inkWidth = if (highlighter) ToolState.HIGHLIGHT_WIDTH_CU else tool.width.cu
        collector.start(e, index, viewport, finger = activeTool == PointerTool.FINGER)
        predictor?.record(e)
        var argb = StrokePainter.parseColor(inkColor)
        if (highlighter) argb = (StrokePainter.HIGHLIGHTER_ALPHA shl 24) or (argb and 0xFFFFFF)
        wet.begin(e, index, WetBrush(argb, inkWidth / 100f, highlighter), viewport.viewToPage)
    }

    private fun finishInk(e: MotionEvent, index: Int) {
        if (e.flags and MotionEvent.FLAG_CANCELED != 0) {
            // The system flagged this touch as accidental (e.g. a palm): drop it.
            cancelInk(e)
            mode = Mode.IGNORE
            return
        }
        collector.add(e, index, viewport)
        val stroke = collector.snapshot(RandomIds.shortId("s"), inkTool, inkColor, inkWidth)
        collector.clear()
        if (stroke == null) {
            wet.cancel(e)
            return
        }
        pageView.pendingWet += stroke.id
        listener?.onStrokeFinished(stroke)
        wet.end(e, index) { pageView.handOff(stroke) }
        mode = Mode.NONE
    }

    private fun cancelInk(e: MotionEvent?) {
        collector.clear()
        wet.cancel(e)
    }

    // --- eraser ------------------------------------------------------------------------------

    private fun beginErase(e: MotionEvent, index: Int, sideButton: Boolean) {
        eraseMode = if (sideButton) EraserMode.STROKE else tool.eraserMode
        pageView.eraserRadiusPx = eraserRadiusPx()
        eraseAlong(e, index)
    }

    private fun eraserRadiusPx(): Float = (if (eraseMode == EraserMode.AREA) 14f else 10f) * density

    private fun eraseAlong(e: MotionEvent, index: Int) {
        val r = viewport.pxToCu(eraserRadiusPx())
        for (h in 0 until e.historySize) {
            viewport.toCu(e.getHistoricalX(index, h), e.getHistoricalY(index, h), cu)
            listener?.onEraseAt(cu[0], cu[1], r, eraseMode)
        }
        viewport.toCu(e.getX(index), e.getY(index), cu)
        listener?.onEraseAt(cu[0], cu[1], r, eraseMode)
        pageView.eraserCursor = PointF(e.getX(index), e.getY(index))
        pageView.invalidate()
    }

    private fun endErase() {
        listener?.onEraseEnd()
        pageView.eraserCursor = null
        pageView.invalidate()
    }

    // --- lasso and selection -----------------------------------------------------------------

    private fun beginLasso(e: MotionEvent, index: Int) {
        lassoCu.clear()
        lassoPath.reset()
        lassoPath.moveTo(e.getX(index), e.getY(index))
        addLassoPoint(e.getX(index), e.getY(index))
        pageView.lassoPath = lassoPath
    }

    private fun addLassoPoints(e: MotionEvent, index: Int) {
        for (h in 0 until e.historySize) {
            addLassoPoint(e.getHistoricalX(index, h), e.getHistoricalY(index, h))
            lassoPath.lineTo(e.getHistoricalX(index, h), e.getHistoricalY(index, h))
        }
        addLassoPoint(e.getX(index), e.getY(index))
        lassoPath.lineTo(e.getX(index), e.getY(index))
        pageView.invalidate()
    }

    private fun addLassoPoint(x: Float, y: Float) {
        viewport.toCu(x, y, cu)
        lassoCu += cu[0]
        lassoCu += cu[1]
    }

    private fun endLasso() {
        pageView.lassoPath = null
        pageView.invalidate()
        if (lassoCu.size >= 6) listener?.onLasso(lassoCu.toIntArray())
    }

    private fun selectionHit(x: Float, y: Float): Mode? {
        val rect = pageView.selectionRectInView() ?: return null
        val handle = PageCanvasView.HANDLE_DP * density
        if (hypot(x - rect.right, y - rect.bottom) <= handle) return Mode.DRAG_SCALE
        val pad = 8f * density
        return if (x >= rect.left - pad && x <= rect.right + pad && y >= rect.top - pad && y <= rect.bottom + pad) Mode.DRAG_MOVE else null
    }

    private fun beginDrag(hit: Mode) {
        mode = hit
        dragStart = PointF(downX, downY)
        dragAffine = Affine.IDENTITY
        val (strokes, stamps) = listener?.selectedItems() ?: return
        pageView.beginSelectionDrag(strokes, stamps)
    }

    private fun updateDrag(x: Float, y: Float) {
        val box = pageView.selectionBoxCu ?: return
        viewport.toCu(dragStart.x, dragStart.y, cu)
        val sx = cu[0]
        val sy = cu[1]
        viewport.toCu(x, y, cu)
        dragAffine = if (mode == Mode.DRAG_MOVE) {
            val (dx, dy) = com.samford.doctor.ink.InkGeometry.clampToPage(box, cu[0] - sx, cu[1] - sy)
            Affine.translate(dx.toFloat(), dy.toFloat())
        } else {
            val start = hypot((sx - box.left).toFloat(), (sy - box.top).toFloat()).coerceAtLeast(1f)
            val now = hypot((cu[0] - box.left).toFloat(), (cu[1] - box.top).toFloat())
            Affine.scaleAbout((now / start).coerceIn(0.3f, 4f), box.left.toFloat(), box.top.toFloat())
        }
        pageView.selectionPreview = dragAffine
    }

    private fun endDrag() {
        val transform = dragAffine
        pageView.endSelectionDrag()
        if (transform != Affine.IDENTITY) listener?.onSelectionTransformed(transform)
    }

    // --- navigation --------------------------------------------------------------------------

    private fun beginNavigate(e: MotionEvent) {
        anchorFocus(e)
        scaleDetector.onTouchEvent(e)
    }

    private fun anchorFocus(e: MotionEvent, excludeIndex: Int = -1) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex) continue
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        if (n > 0) {
            lastFocusX = sx / n
            lastFocusY = sy / n
        }
    }

    private fun navigate(e: MotionEvent) {
        scaleDetector.onTouchEvent(e)
        var sx = 0f
        var sy = 0f
        for (i in 0 until e.pointerCount) {
            sx += e.getX(i)
            sy += e.getY(i)
        }
        val fx = sx / e.pointerCount
        val fy = sy / e.pointerCount
        val dx = fx - lastFocusX
        val dy = fy - lastFocusY
        if (hypot(fx - downX, fy - downY) > touchSlop) navMoved = true
        viewport.panBy(dx, dy)
        lastFocusX = fx
        lastFocusY = fy
        pageView.onViewportChanged()
    }

    // --- hover -------------------------------------------------------------------------------

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val pt = pointerTool(event, 0)
        if (pt != PointerTool.STYLUS && pt != PointerTool.ERASER_TIP) return super.onHoverEvent(event)
        router.noteStylus(event.eventTime)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val erasing = pt == PointerTool.ERASER_TIP || InputRouter.isEraserButton(event.buttonState) || tool.kind == ToolKind.ERASER
                pageView.hover = PointF(event.x, event.y)
                pageView.hoverRadiusPx = if (erasing) 10f * density else brushRadiusPx()
            }
            MotionEvent.ACTION_HOVER_EXIT -> pageView.hover = null
        }
        pageView.invalidate()
        return true
    }

    private fun brushRadiusPx(): Float {
        val widthCu = if (tool.kind == ToolKind.HIGHLIGHTER) ToolState.HIGHLIGHT_WIDTH_CU else tool.width.cu
        return widthCu / 100f * viewport.scale / 2f
    }

    companion object {
        const val TWO_FINGER_TAP_MS = 300L
    }
}
