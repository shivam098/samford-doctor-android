package com.samford.doctor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samford.doctor.core.Hashing
import com.samford.doctor.editor.EraserMode
import com.samford.doctor.ink.Affine
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.ink.SyntheticInk
import com.samford.doctor.model.DocType
import com.samford.doctor.render.PageCompositor
import com.samford.doctor.render.PageRasterizer
import com.samford.doctor.render.PageSpec
import com.samford.doctor.ui.write.InkSurfaceView
import com.samford.doctor.ui.write.InkCapability
import com.samford.doctor.ui.write.SurfaceListener
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** On a real Android runtime: wet-ink path choice, stylus injection and render determinism. */
@RunWith(AndroidJUnit4::class)
class InkDeviceTest {
    private val header = PageHeader("Device Test (Synthetic)", "30 / F", "DEMO-T", "21 Nov 2026", "1", "V-T", "Dr Test", "MBBS", "T-1", "Medicine", "None recorded", false, "—", true)

    @Before
    fun wetInkMode() {
        InkCapability.mode = wetInkModeArgument()
    }

    private class Harness(val scenario: ActivityScenario<ComponentActivity>, val view: InkSurfaceView, val strokes: List<InkStroke>)

    private fun launch(): Harness {
        val strokes = CopyOnWriteArrayList<InkStroke>()
        lateinit var view: InkSurfaceView
        // ComponentActivity is declared in the debug app by compose-ui-test-manifest.
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            view = InkSurfaceView(activity)
            activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            view.listener = object : SurfaceListener {
                override fun onStrokeFinished(stroke: InkStroke) {
                    strokes += stroke
                }
                override fun onEraseAt(xCu: Int, yCu: Int, radiusCu: Int, mode: EraserMode) = Unit
                override fun onEraseEnd() = Unit
                override fun onStampTap(xCu: Int, yCu: Int) = Unit
                override fun onLasso(polygonCu: IntArray) = Unit
                override fun selectedItems() = emptyList<InkStroke>() to emptyList<InkStamp>()
                override fun onSelectionTransformed(transform: Affine) = Unit
                override fun onSelectionCleared() = Unit
                override fun onUndoGesture() = Unit
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return Harness(scenario, view, strokes)
    }

    /** One S Pen stroke (TOOL_TYPE_STYLUS, SOURCE_STYLUS, pressure) dispatched to the surface. */
    private fun penStroke(h: Harness) = h.scenario.onActivity {
        val view = h.view
        view.setContent("p1", PageSpec(DocType.PRESCRIPTION, "Prescription", header, 1, 1, "V-T-RX"), InkPage(0), null)
        val down = SystemClock.uptimeMillis()
        val y = view.height * 0.6f
        fun ev(action: Int, x: Float, t: Long): MotionEvent {
            val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val c = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.6f }
            return MotionEvent.obtain(down, t, action, 1, arrayOf(p), arrayOf(c), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        }
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 200f, down))
        for (i in 1..20) view.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f + i * 15f, down + i * 8))
        view.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 500f, down + 170))
    }

    private fun waitForHandOff(h: Harness): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var pending = true
        while (pending && SystemClock.uptimeMillis() < deadline) {
            h.scenario.onActivity { pending = h.view.pageView.pendingWet.isNotEmpty() }
            Thread.sleep(50)
        }
        return !pending
    }

    /** Removes the surface before the activity goes away so teardown never races the ink layer. */
    private fun close(h: Harness) {
        h.scenario.onActivity { it.setContentView(View(it)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        h.scenario.close()
    }

    @Test
    fun wetInkPathFollowsTheDeviceCapability() {
        val report = InkCapability.report()
        println("InkCapability mode=${InkCapability.mode} report=$report")
        val h = launch()
        try {
            // Low-latency where the GPU supports hardware front-buffering; the standard path otherwise.
            h.scenario.onActivity { assertEquals(report.toString(), InkCapability.useLowLatency(), h.view.isLowLatencyInk) }
        } finally {
            close(h)
        }
    }

    @Test
    fun stylusStrokeIsCommittedAndHandedToTheDryLayer() {
        val h = launch()
        try {
            penStroke(h)
            assertEquals(1, h.strokes.size)
            // The wet stroke is handed to the dry layer once the wet path finishes it.
            assertTrue("wet stroke never handed off", waitForHandOff(h))
        } finally {
            close(h)
        }
    }

    @Test
    fun canonicalRenderIsDeterministicOnDevice() {
        val page = SyntheticInk.page(77, 0, 6)
        val spec = PageSpec(DocType.VISIT_SUMMARY, "Visit Summary", header, 1, 1, "V-T-VS")
        val a = Hashing.sha256Hex(PageRasterizer.encodePng(PageRasterizer.render(spec, page, PageCompositor())))
        val b = Hashing.sha256Hex(PageRasterizer.encodePng(PageRasterizer.render(spec, page, PageCompositor())))
        assertEquals(a, b)
    }
}
