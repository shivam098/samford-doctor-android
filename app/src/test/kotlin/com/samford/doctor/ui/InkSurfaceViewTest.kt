package com.samford.doctor.ui

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.editor.EraserMode
import com.samford.doctor.editor.ToolKind
import com.samford.doctor.editor.ToolState
import com.samford.doctor.ink.Affine
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.input.InputRouter
import com.samford.doctor.model.DocType
import com.samford.doctor.render.PageSpec
import com.samford.doctor.ui.write.InkSurfaceView
import com.samford.doctor.ui.write.SurfaceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Injects real MotionEvents (stylus, S Pen button, eraser tip, finger) into the writing surface. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1000dp-h800dp-land-mdpi")
class InkSurfaceViewTest {
    private class Recorder : SurfaceListener {
        val strokes = mutableListOf<InkStroke>()
        val erases = mutableListOf<Pair<Int, Int>>()
        var eraseEnds = 0
        var undos = 0
        var lasso: IntArray? = null
        override fun onStrokeFinished(stroke: InkStroke) {
            strokes += stroke
        }
        override fun onEraseAt(xCu: Int, yCu: Int, radiusCu: Int, mode: EraserMode) {
            erases += xCu to yCu
        }
        override fun onEraseEnd() {
            eraseEnds++
        }
        override fun onStampTap(xCu: Int, yCu: Int) = Unit
        override fun onLasso(polygonCu: IntArray) {
            lasso = polygonCu
        }
        override fun selectedItems() = emptyList<InkStroke>() to emptyList<InkStamp>()
        override fun onSelectionTransformed(transform: Affine) = Unit
        override fun onSelectionCleared() = Unit
        override fun onUndoGesture() {
            undos++
        }
    }

    private lateinit var view: InkSurfaceView
    private val recorder = Recorder()
    private var t = 1_000L

    private val header = PageHeader("P (Synthetic)", "30 / F", "DEMO", "21 Nov 2026", "1", "V-1", "Dr", "MBBS", "R", "Med", "None recorded", false, "—", true)

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = InkSurfaceView(activity)
        activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1000, 800)
        view.listener = recorder
        view.setContent("p1", PageSpec(DocType.PRESCRIPTION, "Prescription", header, 1, 1, "V-1-RX"), InkPage(0), null)
    }

    private fun event(
        action: Int,
        points: List<Pair<Float, Float>>,
        tool: Int,
        buttons: Int = 0,
        pressure: Float = 0.5f,
        actionIndex: Int = 0,
    ): MotionEvent {
        t += 8
        val props = points.indices.map { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }.toTypedArray()
        val coords = points.map { (x, y) -> MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; touchMajor = 20f } }.toTypedArray()
        val fullAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(1_000, t, fullAction, points.size, props, coords, 0, buttons, 1f, 1f, 0, 0, source, 0)
    }

    private fun drag(tool: Int, buttons: Int = 0, from: Pair<Float, Float> = 200f to 300f, steps: Int = 6, dx: Float = 40f, dy: Float = 0f) {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(from), tool, buttons))
        for (i in 1..steps) view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, listOf((from.first + dx * i) to (from.second + dy * i)), tool, buttons))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, listOf((from.first + dx * steps) to (from.second + dy * steps)), tool, buttons))
    }

    @Test
    fun stylusWritesAStrokeInPageCoordinatesWithPressure() {
        drag(MotionEvent.TOOL_TYPE_STYLUS)
        val stroke = recorder.strokes.single()
        assertEquals(InkStroke.TOOL_PEN, stroke.tool)
        assertTrue(stroke.pointCount >= 6)
        assertEquals(500, stroke.pressure(0))
        // 1000 px view, 16 px margins: (200 - 16) / 0.968 px per unit = ~190 units.
        assertTrue(stroke.x(0) in 18_500..19_500)
        assertTrue(stroke.x(stroke.pointCount - 1) > stroke.x(0))
        assertTrue((0 until stroke.pointCount).all { stroke.y(it) in 0..InkFormat.PAGE_HEIGHT_CU })
    }

    @Test
    fun highlighterProducesAHighlighterStroke() {
        view.tool = ToolState(kind = ToolKind.HIGHLIGHTER)
        drag(MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(InkStroke.TOOL_HIGHLIGHTER, recorder.strokes.single().tool)
    }

    @Test
    fun sPenSideButtonErasesInsteadOfWriting() {
        drag(MotionEvent.TOOL_TYPE_STYLUS, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertTrue(recorder.strokes.isEmpty())
        assertTrue(recorder.erases.size >= 6)
        assertEquals(1, recorder.eraseEnds)
    }

    @Test
    fun eraserTipErases() {
        drag(MotionEvent.TOOL_TYPE_ERASER)
        assertTrue(recorder.strokes.isEmpty())
        assertTrue(recorder.erases.isNotEmpty())
    }

    @Test
    fun fingerScrollsButNeverWritesByDefault() {
        val before = FloatArray(9).also { view.viewport.pageToView.getValues(it) }
        drag(MotionEvent.TOOL_TYPE_FINGER, from = 400f to 600f, dx = 0f, dy = -40f)
        val after = FloatArray(9).also { view.viewport.pageToView.getValues(it) }
        assertTrue(recorder.strokes.isEmpty())
        assertTrue("page should have scrolled", after[5] < before[5])
    }

    @Test
    fun fingerWritesWhenFingerWritingIsOn() {
        view.tool = ToolState(fingerWriting = true)
        drag(MotionEvent.TOOL_TYPE_FINGER)
        val stroke = recorder.strokes.single()
        assertEquals(InkFormat.NO_VALUE, stroke.pressure(0))
    }

    @Test
    fun palmTouchWhilePenHoversIsIgnored() {
        view.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_ENTER, listOf(300f to 300f), MotionEvent.TOOL_TYPE_STYLUS))
        view.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, listOf(305f to 300f), MotionEvent.TOOL_TYPE_STYLUS))
        val before = FloatArray(9).also { view.viewport.pageToView.getValues(it) }
        view.tool = ToolState(fingerWriting = true)
        drag(MotionEvent.TOOL_TYPE_FINGER, from = 500f to 600f, dx = 0f, dy = -30f)
        val after = FloatArray(9).also { view.viewport.pageToView.getValues(it) }
        assertTrue(recorder.strokes.isEmpty())
        assertTrue(before.contentEquals(after))
    }

    @Test
    fun twoFingerTapIsUndo() {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, listOf(300f to 300f), MotionEvent.TOOL_TYPE_FINGER))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN, listOf(300f to 300f, 400f to 300f), MotionEvent.TOOL_TYPE_FINGER, actionIndex = 1))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_POINTER_UP, listOf(300f to 300f, 400f to 300f), MotionEvent.TOOL_TYPE_FINGER, actionIndex = 1))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, listOf(300f to 300f), MotionEvent.TOOL_TYPE_FINGER))
        assertEquals(1, recorder.undos)
        assertTrue(recorder.strokes.isEmpty())
    }

    @Test
    fun lassoReturnsAPolygonInPageUnits() {
        view.tool = ToolState(kind = ToolKind.LASSO)
        drag(MotionEvent.TOOL_TYPE_STYLUS, dx = 30f, dy = 20f)
        assertTrue((recorder.lasso?.size ?: 0) >= 6)
        assertFalse(recorder.strokes.isNotEmpty())
    }

    @Test
    fun inkCannotStartOnTheAutoPrintedHeader() {
        // y = 100 px is inside the letterhead/patient header band.
        drag(MotionEvent.TOOL_TYPE_STYLUS, from = 200f to 100f)
        assertTrue(recorder.strokes.isEmpty())
        drag(MotionEvent.TOOL_TYPE_STYLUS, from = 200f to 400f)
        assertEquals(1, recorder.strokes.size)
    }

    @Test
    fun sideButtonConstantMatchesTheRouter() {
        assertTrue(InputRouter.isEraserButton(MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertEquals(MotionEvent.BUTTON_STYLUS_PRIMARY, InputRouter.BUTTON_STYLUS_PRIMARY)
        assertEquals(MotionEvent.BUTTON_SECONDARY, InputRouter.BUTTON_SECONDARY)
    }
}
