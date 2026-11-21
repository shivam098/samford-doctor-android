package com.samford.doctor.ui.write

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.render.StrokePainter

data class WetBrush(val colorArgb: Int, val widthU: Float, val highlighter: Boolean)

/**
 * Renders the stroke that is still being written ("wet ink"). The finished stroke is committed to
 * the record immediately; [end]'s callback fires when the dry layer should take over drawing it.
 */
interface WetInkLayer {
    val view: View
    val isLowLatency: Boolean
    fun begin(event: MotionEvent, pointerIndex: Int, brush: WetBrush, viewToPage: Matrix)
    fun move(event: MotionEvent, pointerIndex: Int, prediction: MotionEvent?, partial: InkStroke?)
    fun end(event: MotionEvent, pointerIndex: Int, onHandOff: () -> Unit)
    fun cancel(event: MotionEvent?)

    /**
     * Called when the window is hidden (app switch, screen off) or shown again. Hidden: finished
     * strokes are handed to the dry layer at once and the front-buffered layer is hidden, before the
     * platform tears the window surface down.
     */
    fun setHostVisible(visible: Boolean) = Unit

    companion object {
        fun create(context: Context, viewport: Viewport): WetInkLayer {
            if (!JetpackInkWetLayer.isSupported() || !InkCapability.useLowLatency()) return FallbackWetLayer(context, viewport)
            return try {
                JetpackInkWetLayer(context)
            } catch (t: Throwable) {
                Log.w("WetInkLayer", "Jetpack Ink unavailable; using the fallback renderer", t)
                FallbackWetLayer(context, viewport)
            }
        }
    }
}

/**
 * Jetpack Ink low-latency authoring: front-buffered rendering of the in-flight stroke with motion
 * prediction, so ink appears under the S Pen nib with minimal lag.
 */
class JetpackInkWetLayer(context: Context) : WetInkLayer, InProgressStrokesFinishedListener {
    private val ink = InProgressStrokesView(context)
    private var current: InProgressStrokeId? = null
    private val handOffs = HashMap<InProgressStrokeId, () -> Unit>()

    override val view: View get() = ink
    override val isLowLatency: Boolean = true

    init {
        ink.addFinishedStrokesListener(this)
        ink.eagerInit()
    }

    override fun begin(event: MotionEvent, pointerIndex: Int, brush: WetBrush, viewToPage: Matrix) {
        val family = if (brush.highlighter) StockBrushes.highlighter() else StockBrushes.pressurePen()
        val inkBrush = Brush.createWithColorIntArgb(family, brush.colorArgb, brush.widthU, 0.05f)
        current = ink.startStroke(event, event.getPointerId(pointerIndex), inkBrush, viewToPage, Matrix())
    }

    override fun move(event: MotionEvent, pointerIndex: Int, prediction: MotionEvent?, partial: InkStroke?) {
        if (current == null) return
        ink.addToStroke(event, event.getPointerId(pointerIndex), prediction)
    }

    override fun end(event: MotionEvent, pointerIndex: Int, onHandOff: () -> Unit) {
        val id = current
        if (id == null) {
            onHandOff()
            return
        }
        handOffs[id] = onHandOff
        ink.finishStroke(event, event.getPointerId(pointerIndex))
        current = null
    }

    override fun cancel(event: MotionEvent?) {
        val id = current ?: return
        ink.cancelStroke(id, event)
        current = null
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        // Draw into the dry layer and remove the wet copies in the same frame (no flicker).
        strokes.keys.forEach { handOffs.remove(it)?.invoke() }
        ink.removeFinishedStrokes(strokes.keys)
    }

    override fun setHostVisible(visible: Boolean) {
        if (visible) {
            ink.visibility = View.VISIBLE
            return
        }
        current?.let { ink.cancelStroke(it, null) }
        current = null
        // Every stroke here is already committed to the record; let the dry layer draw them now.
        val pending = handOffs.values.toList()
        handOffs.clear()
        pending.forEach { it() }
        ink.removeFinishedStrokes(ink.getFinishedStrokes().keys)
        ink.visibility = View.INVISIBLE
    }

    companion object {
        /** Jetpack Ink needs its native library, which is absent on the JVM test runtime. */
        fun isSupported(): Boolean = Build.FINGERPRINT != "robolectric"
    }
}

/** Plain-canvas wet ink, used in JVM tests and if Jetpack Ink cannot start on a device. */
@android.annotation.SuppressLint("ViewConstructor")
class FallbackWetLayer(context: Context, private val viewport: Viewport) : View(context), WetInkLayer {
    private var partial: InkStroke? = null

    override val view: View get() = this
    override val isLowLatency: Boolean = false

    override fun begin(event: MotionEvent, pointerIndex: Int, brush: WetBrush, viewToPage: Matrix) {
        partial = null
    }

    override fun move(event: MotionEvent, pointerIndex: Int, prediction: MotionEvent?, partial: InkStroke?) {
        this.partial = partial
        invalidate()
    }

    override fun end(event: MotionEvent, pointerIndex: Int, onHandOff: () -> Unit) {
        partial = null
        onHandOff()
        invalidate()
    }

    override fun cancel(event: MotionEvent?) {
        partial = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = partial ?: return
        canvas.save()
        canvas.concat(viewport.pageToView)
        StrokePainter.draw(canvas, stroke)
        canvas.restore()
    }
}
