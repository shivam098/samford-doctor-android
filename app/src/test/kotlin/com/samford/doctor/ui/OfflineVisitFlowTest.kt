package com.samford.doctor.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.samford.doctor.MainActivity
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.sync.OutboxState
import com.samford.doctor.testing.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The whole doctor journey offline, on the real app UI: demo sign-in -> signature -> queue ->
 * confirm patient -> write on several documents (stamps, labs) -> review recipients -> sign and
 * complete -> record frozen and queued. Screenshots go to app/build/outputs/roborazzi/ for review.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class OfflineVisitFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<TestApplication>()
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun write(lines: Int, startY: Float = 0.48f) {
        compose.onNodeWithTag("ink-surface").performTouchInput {
            repeat(lines) { line ->
                val y = height * (startY + line * 0.06f)
                swipe(Offset(width * 0.15f, y), Offset(width * 0.55f, y + height * 0.02f), durationMillis = 300)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun offlineDemoVisitFromSignInToSignedRecord() {
        waitForTag("persona-demo-dr-asha")
        shot("01-sign-in")
        compose.onNodeWithTag("persona-demo-dr-asha").performClick()

        waitForTag("signature-pad")
        compose.onNodeWithTag("signature-pad").performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.6f), Offset(width * 0.45f, height * 0.3f), 300)
            swipe(Offset(width * 0.45f, height * 0.3f), Offset(width * 0.85f, height * 0.65f), 300)
        }
        shot("02-signature")
        compose.onNodeWithText("Save signature").performClick()

        waitForTag("queue-local-demo-1")
        shot("03-queue")
        compose.onNodeWithTag("queue-local-demo-1").performClick()
        waitForTag("confirm-start")
        shot("04-confirm-patient")
        compose.onNodeWithTag("confirm-start").performClick()

        waitForTag("ink-surface")
        // JVM tests drive the canvas with touch, so turn on finger writing (stylus routing is covered by InkSurfaceViewTest).
        compose.onNodeWithTag("tool-finger").performScrollTo().performClick()
        write(lines = 5)
        shot("05-visit-summary-written")

        compose.onNodeWithTag("doc-PRESCRIPTION").performClick()
        write(lines = 2)
        compose.onNodeWithTag("tool-stamps").performScrollTo().performClick()
        waitForTag("stamp-paracetamol-650")
        shot("06-stamp-palette")
        compose.onNodeWithTag("stamp-paracetamol-650").performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput { click(Offset(width * 0.2f, height * 0.6f)) }
        compose.onNodeWithTag("tool-stamps").performScrollTo().performClick()
        waitForTag("stamp-1-0-1")
        // Relaunch mid-visit (font size, keyboard cover, locale): same visit, same document, sheet still open.
        compose.activityRule.scenario.recreate()
        waitForTag("ink-surface")
        waitForTag("stamp-1-0-1")
        compose.onNodeWithTag("stamp-1-0-1").performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput { click(Offset(width * 0.55f, height * 0.6f)) }
        compose.waitForIdle()
        shot("07-prescription-with-stamps-and-history")

        compose.onNodeWithTag("doc-INVESTIGATIONS").performClick()
        waitForTag("lab-central-lab")
        compose.onNodeWithTag("lab-central-lab").performClick()
        write(lines = 2)
        shot("08-investigations-routed")

        compose.onNodeWithTag("primary-action").performClick()
        waitForTag("complete-visit")
        waitForTag("page-image-ready")
        shot("09-review-pharmacy")
        compose.onNodeWithTag("recipient-patient").performClick()
        compose.waitForIdle()
        waitForTag("page-image-ready")
        shot("10-review-patient")
        compose.onNodeWithTag("complete-visit").performClick()
        waitForTag("confirm-complete")
        compose.onNodeWithTag("confirm-complete").performClick()

        waitForTag("queue-list")
        val container = app.container
        compose.waitUntil(30_000) {
            runBlocking { container.db.outboxDao().all().isNotEmpty() }
        }
        shot("11-queue-after-completion")

        runBlocking {
            val visit = container.db.visitDao().visitForQueueEntry("local-demo-1")!!
            assertEquals(VisitStatus.COMPLETED, visit.status)
            val docs = container.db.visitDao().documentsForVisit(visit.visitId)
            val frozen = docs.filter { it.status == DocStatus.FROZEN }.map { it.type }.toSet()
            assertEquals(setOf("VISIT_SUMMARY", "PRESCRIPTION", "INVESTIGATIONS"), frozen)
            val rx = docs.single { it.type == "PRESCRIPTION" }
            assertTrue(container.blobs.exists(rx.pdfSha256!!))
            assertTrue(rx.pageRefsJson!!.contains("pngSha256"))
            val bundle = container.db.outboxDao().all().single()
            assertEquals(OutboxState.SAVED_ON_TABLET.name, bundle.state)
            // Offline demo has no hospital session: the upload waits for sign-in, nothing is lost.
            container.syncEngine.runOnce()
            assertEquals(OutboxState.AUTH_REQUIRED.name, container.db.outboxDao().all().single().state)
        }

        compose.onNodeWithTag("sync-chip").performClick()
        waitForTag("sync-list")
        shot("12-sync-status")
    }
}
