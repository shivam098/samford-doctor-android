package com.samford.doctor.ui.write

import android.graphics.Matrix
import com.samford.doctor.ink.InkFormat
import kotlin.math.min

/**
 * Page <-> view transform. Zoom 1 fits the page width; fingers can zoom out to see the whole page
 * and in up to 5x for fine writing.
 */
class Viewport(private val marginPx: Float) {
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    val pageToView = Matrix()
    val viewToPage = Matrix()
    private val point = FloatArray(2)

    /** Pixels per page unit at zoom 1. */
    fun baseScale(): Float = ((viewWidth - 2 * marginPx) / InkFormat.PAGE_WIDTH_U).coerceAtLeast(0.05f)

    val scale: Float get() = baseScale() * zoom

    private fun minZoom(): Float {
        if (viewHeight == 0) return 1f
        val fitPage = (viewHeight - 2 * marginPx) / (InkFormat.PAGE_HEIGHT_U * baseScale())
        return min(1f, fitPage).coerceAtLeast(0.3f)
    }

    fun setViewSize(width: Int, height: Int) {
        val first = viewWidth == 0 || viewHeight == 0
        viewWidth = width
        viewHeight = height
        if (first) reset() else {
            clamp()
            update()
        }
    }

    fun reset() {
        zoom = 1f
        panX = marginPx
        panY = marginPx
        clamp()
        update()
    }

    fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clamp()
        update()
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val newZoom = (zoom * factor).coerceIn(minZoom(), MAX_ZOOM)
        val ratio = newZoom / zoom
        panX = focusX - (focusX - panX) * ratio
        panY = focusY - (focusY - panY) * ratio
        zoom = newZoom
        clamp()
        update()
    }

    /** Scrolls so page y (in units) is near the top of the view. */
    fun scrollToPageY(yU: Float) {
        panY = marginPx - yU * scale
        clamp()
        update()
    }

    private fun clamp() {
        val pw = InkFormat.PAGE_WIDTH_U * scale
        val ph = InkFormat.PAGE_HEIGHT_U * scale
        panX = if (pw + 2 * marginPx <= viewWidth) (viewWidth - pw) / 2f else panX.coerceIn(viewWidth - pw - marginPx, marginPx)
        panY = if (ph + 2 * marginPx <= viewHeight) marginPx else panY.coerceIn(viewHeight - ph - marginPx, marginPx)
    }

    private fun update() {
        pageToView.setScale(scale, scale)
        pageToView.postTranslate(panX, panY)
        pageToView.invert(viewToPage)
    }

    /** Maps a view point to page centi-units into [out] (x, y). */
    fun toCu(x: Float, y: Float, out: IntArray) {
        point[0] = x
        point[1] = y
        viewToPage.mapPoints(point)
        out[0] = Math.round(point[0] * InkFormat.CU_PER_U)
        out[1] = Math.round(point[1] * InkFormat.CU_PER_U)
    }

    fun pxToCu(px: Float): Int = Math.round(px / scale * InkFormat.CU_PER_U)

    companion object {
        const val MAX_ZOOM = 5f
    }
}
