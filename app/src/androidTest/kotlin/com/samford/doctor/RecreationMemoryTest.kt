package com.samford.doctor

import android.Manifest
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference

/**
 * Memory across activity recreation on the writing screen (a multi-page visit with ink open):
 * 20 rotations, 20 recreations (any configuration change the app doesn't handle itself, e.g.
 * locale or font size) and 3 airplane-mode cycles. After each step a GC is forced and
 * `dumpsys meminfo` is recorded (files/memory/recreation.csv, and logcat tag RecreationMemory).
 * Fails if an earlier MainActivity instance is still reachable, or if the heap keeps growing.
 */
@RunWith(AndroidJUnit4::class)
class RecreationMemoryTest {
    @get:Rule(order = -1)
    val stall = StallDumpRule()

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SamfordDoctorApplication
    private val activities = ArrayList<WeakReference<MainActivity>>()
    private val rows = ArrayList<Map<String, Long>>()
    private val csv by lazy { File(app.getExternalFilesDir(null), "memory").apply { mkdirs() }.resolve("recreation.csv") }

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 60_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun write(lines: Int) {
        compose.onNodeWithTag("ink-surface").performTouchInput {
            repeat(lines) { line ->
                val y = height * (0.5f + line * 0.06f)
                swipe(Offset(width * 0.15f, y), Offset(width * 0.55f, y + height * 0.02f), durationMillis = 250)
            }
        }
        compose.waitForIdle()
    }

    private fun trackCurrentActivity() {
        compose.activityRule.scenario.onActivity { current ->
            if (activities.none { it.get() === current }) activities += WeakReference(current)
        }
    }

    private fun gc() {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            SystemClock.sleep(200)
        }
    }

    private fun number(text: String, label: String): Long =
        Regex("""\b$label:\s+(\d+)""").find(text)?.groupValues?.get(1)?.toLong() ?: -1

    /** Forces a GC, then records the app's meminfo counters for [step]. */
    private fun measure(step: String) {
        compose.waitForIdle()
        trackCurrentActivity()
        gc()
        val info = shell("dumpsys meminfo ${app.packageName}")
        val row = linkedMapOf(
            "activities" to number(info, "Activities"),
            "views" to number(info, "Views"),
            "viewRootImpl" to number(info, "ViewRootImpl"),
            "javaHeapKb" to number(info, "Java Heap"),
            "nativeHeapKb" to number(info, "Native Heap"),
            "graphicsKb" to number(info, "Graphics"),
            "totalPssKb" to number(info, "TOTAL PSS"),
            "liveMainActivities" to activities.count { it.get() != null }.toLong(),
        )
        rows += row
        val line = "$step," + row.values.joinToString(",")
        if (!csv.exists()) csv.writeText("step," + row.keys.joinToString(",") + "\n")
        csv.appendText(line + "\n")
        Log.i("RecreationMemory", line)
    }

    private fun setUpVisitWithInk() {
        shell("cmd connectivity airplane-mode enable")
        SystemClock.sleep(3_000)
        // Signed in by an earlier test in this run, or not yet: handle both.
        compose.waitUntil(60_000) { exists("queue-list") || compose.onAllNodes(hasText("Check again")).fetchSemanticsNodes().isNotEmpty() }
        if (!exists("queue-list")) {
            compose.onNodeWithText("Check again").performClick()
            waitForTag("persona-demo-dr-asha")
            compose.onNodeWithTag("persona-demo-dr-asha").performClick()
            compose.waitUntil(60_000) { exists("signature-pad") || exists("queue-list") }
            if (exists("signature-pad")) {
                compose.onNodeWithTag("signature-pad").performTouchInput {
                    swipe(Offset(width * 0.1f, height * 0.6f), Offset(width * 0.5f, height * 0.3f), 300)
                    swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.9f, height * 0.6f), 300)
                }
                compose.onNodeWithText("Save signature").performClick()
            }
        }
        waitForTag("queue-list")
        waitForTag("queue-local-demo-2")
        compose.onNodeWithTag("queue-local-demo-2").performClick()
        compose.waitUntil(30_000) { exists("confirm-start") || exists("ink-surface") }
        if (exists("confirm-start")) compose.onNodeWithTag("confirm-start").performClick()
        waitForTag("ink-surface")
        compose.onNodeWithTag("tool-finger").performScrollTo().performClick()
        write(3)
        compose.onNodeWithTag("add-page").performClick()
        waitForTag("page-thumb-1")
        write(3)
        compose.onNodeWithTag("doc-PRESCRIPTION").performClick()
        write(2)
    }

    private fun assertStillWriting() {
        waitForTag("ink-surface")
        waitForTag("doc-PRESCRIPTION")
    }

    @After
    fun restore() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        shell("cmd connectivity airplane-mode disable")
        rows.lastOrNull()?.let { Log.i("RecreationMemory", "final $it") }
    }

    @Test
    fun recreationKeepsOneActivityAndAFlatHeap() {
        setUpVisitWithInk()
        measure("baseline")

        // Rotation: MainActivity handles orientation/screen size itself, so this should not recreate it.
        val beforeRotation = activities.size
        repeat(20) { i ->
            instrumentation.uiAutomation.setRotation(if (i % 2 == 0) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
            SystemClock.sleep(700)
            assertStillWriting()
            measure("rotate-${i + 1}")
        }
        Log.i("RecreationMemory", "activity instances created by 20 rotations: ${activities.size - beforeRotation}")
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)

        // Recreation (locale, font size, density, mcc/mnc ... ): a new activity each time.
        repeat(20) { i ->
            compose.activityRule.scenario.recreate()
            assertStillWriting()
            measure("recreate-${i + 1}")
        }

        // Airplane mode on/off: on the emulator the radio change recreates the activity.
        repeat(3) { i ->
            shell("cmd connectivity airplane-mode disable")
            SystemClock.sleep(3_000)
            assertStillWriting()
            measure("airplane-off-${i + 1}")
            shell("cmd connectivity airplane-mode enable")
            SystemClock.sleep(3_000)
            assertStillWriting()
            measure("airplane-on-${i + 1}")
        }

        // Every earlier MainActivity must be collectable.
        var leaked = Int.MAX_VALUE
        repeat(10) {
            gc()
            trackCurrentActivity()
            var current: MainActivity? = null
            compose.activityRule.scenario.onActivity { current = it }
            leaked = activities.count { ref -> ref.get().let { it != null && it !== current } }
            if (leaked == 0) return@repeat
            SystemClock.sleep(500)
        }
        measure("final")
        Log.i("RecreationMemory", "MainActivity instances created: ${activities.size}, still reachable besides the current one: $leaked")
        assertEquals("earlier MainActivity instances still reachable after GC (created ${activities.size})", 0, leaked)

        // Heap: compared with the first recreation (rows[21], caches warm), not growing per recreation.
        val warm = rows[21]
        val last = rows.last()
        val javaGrowth = last.getValue("javaHeapKb") - warm.getValue("javaHeapKb")
        val nativeGrowth = last.getValue("nativeHeapKb") - warm.getValue("nativeHeapKb")
        Log.i("RecreationMemory", "growth since first recreation: java ${javaGrowth} KB, native ${nativeGrowth} KB")
        assertTrue("Java heap grew by $javaGrowth KB over 25 recreations", javaGrowth < 16 * 1024)
        assertTrue("native heap grew by $nativeGrowth KB over 25 recreations", nativeGrowth < 64 * 1024)
    }
}
