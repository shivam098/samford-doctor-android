package com.samford.doctor

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.samford.doctor.sync.OutboxState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The doctor journey on a real (emulated) tablet. Offline by default; when the instrumentation
 * argument samfordApiBase is given (e.g. http://10.0.2.2:4320/api/mobile/v1) the visit is also
 * uploaded to the isolated mock backend through WorkManager and must reach RECEIVED.
 * Screenshots: files/screens/ inside the app's external data dir (pulled by CI).
 */
@RunWith(AndroidJUnit4::class)
class DeviceFlowTest {
    // The app asks for the notification permission after sign-in; the system dialog would pause the activity.
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SamfordDoctorApplication
    private val apiBase: String? = InstrumentationRegistry.getArguments().getString("samfordApiBase")

    private fun shot(name: String) {
        val dir = File(app.getExternalFilesDir(null), "screens").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @After
    fun restoreNetwork() {
        if (apiBase == null) shell("cmd connectivity airplane-mode disable")
    }

    @org.junit.Before
    fun wetInkMode() {
        // Set before the writing surface is created; the activity only shows sign-in at this point.
        com.samford.doctor.ui.write.InkCapability.mode = wetInkModeArgument()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun write(lines: Int) {
        compose.onNodeWithTag("ink-surface").performTouchInput {
            repeat(lines) { line ->
                val y = height * (0.5f + line * 0.06f)
                swipe(Offset(width * 0.15f, y), Offset(width * 0.55f, y + height * 0.02f), durationMillis = 300)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun doctorJourney() {
        if (apiBase != null) {
            runBlocking { app.container.server.setManualUrl(apiBase) }
            compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Check again")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Check again").performClick()
        } else {
            // A real offline tablet: no discovery, no last-good server.
            shell("cmd connectivity airplane-mode enable")
            Thread.sleep(3_000)
            compose.waitUntil(30_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Check again")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Check again").performClick()
        }
        val persona = if (apiBase != null) "persona-doctor-a" else "persona-demo-dr-asha"
        // A server check can take a few seconds per address (discovery, then the last good one).
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasTestTag(persona)).fetchSemanticsNodes().isNotEmpty()
        }
        shot("01-sign-in")
        compose.onNodeWithTag(persona).performClick()
        waitForTag("signature-pad")
        compose.onNodeWithTag("signature-pad").performTouchInput {
            swipe(Offset(width * 0.1f, height * 0.6f), Offset(width * 0.5f, height * 0.3f), 300)
            swipe(Offset(width * 0.5f, height * 0.3f), Offset(width * 0.9f, height * 0.6f), 300)
        }
        compose.onNodeWithText("Save signature").performClick()

        val entryTag = if (apiBase != null) "queue-visit-a" else "queue-local-demo-1"
        waitForTag(entryTag)
        shot("02-queue")
        compose.onNodeWithTag(entryTag).performClick()
        waitForTag("confirm-start")
        compose.onNodeWithTag("confirm-start").performClick()
        waitForTag("ink-surface")
        compose.onNodeWithTag("tool-finger").performScrollTo().performClick()
        write(4)
        compose.onNodeWithTag("doc-PRESCRIPTION").performClick()
        write(2)
        // The doctor lifts the pen and switches app at once, then comes back to the same page.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForTag("ink-surface")
        compose.onNodeWithTag("tool-stamps").performScrollTo().performClick()
        waitForTag("stamp-1-0-1")
        // A relaunch mid-visit (font size, keyboard cover, locale) must keep the doctor exactly where they were.
        compose.activityRule.scenario.recreate()
        waitForTag("ink-surface")
        waitForTag("stamp-1-0-1")
        compose.onNodeWithTag("stamp-1-0-1").performClick()
        compose.onNodeWithTag("ink-surface").performTouchInput { click(Offset(width * 0.6f, height * 0.6f)) }
        shot("03-writing")

        compose.onNodeWithTag("primary-action").performClick()
        waitForTag("complete-visit")
        waitForTag("page-image-ready")
        shot("04-review")
        compose.onNodeWithTag("complete-visit").performClick()
        waitForTag("confirm-complete")
        compose.onNodeWithTag("confirm-complete").performClick()
        waitForTag("queue-list")

        val c = app.container
        compose.waitUntil(60_000) { runBlocking { c.db.outboxDao().all().isNotEmpty() } }
        shot("05-after-complete")
        if (apiBase != null) {
            compose.waitUntil(120_000) {
                runBlocking { c.db.outboxDao().all().all { it.state == OutboxState.RECEIVED.name } }
            }
            compose.onNodeWithTag("sync-chip").performClick()
            waitForTag("sync-list")
            shot("06-sync-received")
            assertTrue(runBlocking { c.db.outboxDao().all().single().state } == OutboxState.RECEIVED.name)
        } else {
            val bundle = runBlocking { c.db.outboxDao().all().single() }
            assertTrue(bundle.state != OutboxState.RECEIVED.name)
            assertEquals(1, runBlocking { c.db.outboxDao().all().size })
        }
    }
}
