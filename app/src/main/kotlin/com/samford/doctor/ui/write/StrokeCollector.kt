package com.samford.doctor.ui.write

import android.view.MotionEvent
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkStroke
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Turns MotionEvents (including every batched historical sample) into samford-ink/1 points in
 * page centi-units: x, y, t (ms since pen down), pressure 0..1000, tilt mrad, orientation mrad
 * shifted by +pi so it is never negative. Fingers record no pressure/tilt/orientation (-1).
 */
class StrokeCollector {
    private var pts = IntArray(InkFormat.STRIDE * 256)
    private var size = 0
    private var downTime = 0L
    private var finger = false
    private val cu = IntArray(2)

    val pointCount: Int get() = size / InkFormat.STRIDE

    fun start(event: MotionEvent, pointerIndex: Int, viewport: Viewport, finger: Boolean) {
        size = 0
        downTime = event.eventTime
        this.finger = finger
        add(event, pointerIndex, viewport)
    }

    fun add(event: MotionEvent, pointerIndex: Int, viewport: Viewport) {
        for (h in 0 until event.historySize) {
            addPoint(
                event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h), event.getHistoricalEventTime(h),
                event.getHistoricalPressure(pointerIndex, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                event.getHistoricalOrientation(pointerIndex, h),
                viewport,
            )
        }
        addPoint(
            event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime,
            event.getPressure(pointerIndex), event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex), event.getOrientation(pointerIndex),
            viewport,
        )
    }

    /** Adds a point given in view pixels (used by tests and replay). */
    fun addPoint(x: Float, y: Float, timeMs: Long, pressure: Float, tilt: Float, orientation: Float, viewport: Viewport) {
        viewport.toCu(x, y, cu)
        if (size > 0 && pts[size - InkFormat.STRIDE] == cu[0] && pts[size - InkFormat.STRIDE + 1] == cu[1]) return
        if (size + InkFormat.STRIDE > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[size] = cu[0]
        pts[size + 1] = cu[1]
        pts[size + 2] = (timeMs - downTime).coerceAtLeast(0).toInt()
        pts[size + 3] = if (finger) InkFormat.NO_VALUE else (pressure.coerceIn(0f, 1f) * 1000).roundToInt()
        pts[size + 4] = if (finger) InkFormat.NO_VALUE else (tilt.coerceIn(0f, (PI / 2).toFloat()) * 1000).roundToInt()
        pts[size + 5] = if (finger) InkFormat.NO_VALUE else ((orientation + PI.toFloat()).coerceIn(0f, (2 * PI).toFloat()) * 1000).roundToInt()
        size += InkFormat.STRIDE
    }

    fun snapshot(id: String, tool: String, color: String, widthCu: Int): InkStroke? =
        if (size == 0) null else InkStroke(id, tool, color, widthCu, pts.copyOf(size))

    fun clear() {
        size = 0
    }
}
