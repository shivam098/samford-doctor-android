package com.samford.doctor.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.remote.ApiError
import com.samford.doctor.data.remote.CompleteVisitRequest
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.model.DeliveryState
import com.samford.doctor.testing.FakeHospital
import com.samford.doctor.testing.FixedClock
import com.samford.doctor.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hospital = FakeHospital()
    private val clock = FixedClock()
    private val c = Fixtures.container(context, hospital, clock)

    private suspend fun completedVisit(): String {
        val visitId = Fixtures.writeVisit(c)
        Fixtures.completeVisit(c, visitId)
        c.server.refresh()
        return visitId
    }

    private suspend fun bundle(visitId: String) = c.db.outboxDao().forVisit(visitId).single()

    @Test
    fun completionFreezesUploadsOnceAndRoutesDocuments() = runBlocking {
        val visitId = completedVisit()
        val visit = c.db.visitDao().visit(visitId)!!
        assertEquals(VisitStatus.COMPLETED, visit.status)
        assertEquals(OutboxState.SAVED_ON_TABLET.name, bundle(visitId).state)

        val result = c.syncEngine.runOnce()
        assertEquals(1, result.received)
        val b = bundle(visitId)
        assertEquals(OutboxState.RECEIVED.name, b.state)
        assertEquals(1, hospital.distinctCompletions)

        val request = hospital.completionBodies.values.single()
        val rx = request.documents.single { it.type == "PRESCRIPTION" }
        assertTrue(rx.routing.pharmacy)
        assertTrue(rx.routing.labs.isEmpty())
        val ix = request.documents.single { it.type == "INVESTIGATIONS" }
        assertEquals(listOf("central-lab"), ix.routing.labs)
        assertFalse(ix.routing.pharmacy)
        assertEquals(setOf("VISIT_SUMMARY", "PRESCRIPTION", "INVESTIGATIONS"), request.documents.map { it.type }.toSet())
        request.documents.forEach { d ->
            assertEquals("samford-ink/1", d.inkFormat)
            d.pages.forEach { p -> assertTrue(p.pngSha256 in hospital.blobs); assertEquals(1500, p.widthPx); assertEquals(2121, p.heightPx) }
            assertTrue(d.pdfSha256 in hospital.blobs)
        }
        // Advice/Referral had nothing written: not sent.
        val advice = c.db.visitDao().documentsForVisit(visitId).single { it.type == "ADVICE_REFERRAL" }
        assertEquals(DocStatus.EMPTY, advice.status)

        // A second run sends nothing more.
        val before = hospital.calls.size
        c.syncEngine.runOnce()
        assertEquals(before, hospital.calls.size)

        // Delivery status flows back per recipient.
        c.syncEngine.pollStatus()
        val deliveries = c.db.deliveryDao().observeAllOnce()
        assertTrue(deliveries.any { it.recipientKind == "PHARMACY" && it.state == DeliveryState.DISPENSED.name })
    }

    @Test
    fun offlineCompletionQueuesDurablyAndRetriesWithTheSameKeyAndBody() = runBlocking {
        val visitId = completedVisit()
        hospital.online = false
        c.syncEngine.runOnce()
        var b = bundle(visitId)
        assertEquals(OutboxState.WAITING_RETRY.name, b.state)
        assertEquals(1, b.attempts)
        assertTrue(b.lastErrorMessage!!.contains("saved on this tablet"))

        // Not due yet: nothing happens.
        hospital.online = true
        c.syncEngine.runOnce()
        assertEquals(OutboxState.WAITING_RETRY.name, bundle(visitId).state)

        clock.advanceMs(31_000)
        c.syncEngine.runOnce()
        b = bundle(visitId)
        assertEquals(OutboxState.RECEIVED.name, b.state)
        assertEquals(1, hospital.distinctCompletions)
        val stored = ApiJson.json.decodeFromString(CompleteVisitRequest.serializer(), b.payloadJson)
        assertEquals(stored, hospital.completionBodies[b.bundleId])
    }

    @Test
    fun lostReplyIsRetriedIdempotentlyWithoutDuplicates() = runBlocking {
        val visitId = completedVisit()
        hospital.loseNextReply = true
        c.syncEngine.runOnce()
        assertEquals(OutboxState.WAITING_RETRY.name, bundle(visitId).state)
        assertEquals(1, hospital.distinctCompletions) // the server already has it

        c.syncEngine.retryNow(bundle(visitId).bundleId)
        c.syncEngine.runOnce()
        assertEquals(OutboxState.RECEIVED.name, bundle(visitId).state)
        assertEquals(1, hospital.distinctCompletions)
        assertEquals(2, hospital.completeCalls)
        val keys = hospital.calls.filter { it.startsWith("complete:") }.map { it.substringAfterLast(":") }.toSet()
        assertEquals(setOf(bundle(visitId).bundleId), keys)
    }

    @Test
    fun missingBlobsAreUploadedAgainAndTheSameRequestIsReplayed() = runBlocking {
        val visitId = completedVisit()
        val firstPage = ApiJson.json.decodeFromString(CompleteVisitRequest.serializer(), bundle(visitId).payloadJson).documents.first().pages.first().pngSha256
        hospital.forceMissingOnce = listOf(firstPage)
        c.syncEngine.runOnce()
        assertEquals(OutboxState.RECEIVED.name, bundle(visitId).state)
        assertEquals(2, hospital.calls.count { it == "put:$firstPage" })
    }

    @Test
    fun expiredSessionWaitsForSignInAndNeverDropsTheBundle() = runBlocking {
        val visitId = completedVisit()
        hospital.authValid = false
        c.syncEngine.runOnce()
        assertEquals(OutboxState.AUTH_REQUIRED.name, bundle(visitId).state)
        c.syncEngine.runOnce()
        assertEquals(OutboxState.AUTH_REQUIRED.name, bundle(visitId).state)

        assertEquals(null, c.session.reconnect())
        c.syncEngine.authRestored()
        c.syncEngine.runOnce()
        assertEquals(OutboxState.RECEIVED.name, bundle(visitId).state)
    }

    @Test
    fun rejectedBundleNeedsAttentionWithAPlainMessage() = runBlocking {
        val visitId = completedVisit()
        hospital.failNext = ApiError("ROUTING_INVALID", "Labs can only receive investigations.", retryable = false, httpStatus = 422)
        c.syncEngine.runOnce()
        val b = bundle(visitId)
        assertEquals(OutboxState.NEEDS_ATTENTION.name, b.state)
        assertEquals("Labs can only receive investigations.", b.lastErrorMessage)
        assertNotNull(c.db.visitDao().visit(visitId)) // the record is still on the tablet
    }

    @Test
    fun offlineDemoVisitIsRegisteredAndStartedBeforeCompletion() = runBlocking {
        val visitId = Fixtures.writeVisit(c, Fixtures.demoEntry(), labs = emptyList())
        Fixtures.completeVisit(c, visitId)
        c.server.refresh()
        c.syncEngine.runOnce()
        assertEquals(OutboxState.RECEIVED.name, bundle(visitId).state)
        val walkIns = hospital.calls.filter { it.startsWith("walkin:") }
        assertEquals(listOf("walkin:walkin-$visitId"), walkIns)
        assertTrue(hospital.calls.any { it.startsWith("start:") && it.endsWith(":start-$visitId") })
        assertNotNull(c.db.visitDao().visit(visitId)!!.serverVisitId)
    }

    @Test
    fun addendumCreatesVersionTwoAndKeepsTheOriginalPages() = runBlocking {
        val visitId = completedVisit()
        val rx = c.db.visitDao().documentsForVisit(visitId).single { it.type == "PRESCRIPTION" }
        val addendumId = c.visits.createAddendumDraft(rx.docId)!!
        val pageId = c.journal.pagesForDoc(addendumId).first().first
        c.journal.append(pageId, com.samford.doctor.ink.PageDelta.addStroke(Fixtures.stroke("add-1")))
        assertEquals(null, c.freezer.freezeAddendum(addendumId, "Dose correction"))

        // The addendum waits until the original has a server id, then goes out.
        c.syncEngine.runOnce()
        c.syncEngine.runOnce()
        val bundles = c.db.outboxDao().forVisit(visitId)
        assertTrue(bundles.all { it.state == OutboxState.RECEIVED.name })
        val request = hospital.addendumBodies.values.single()
        assertEquals(1, request.baseVersion)
        assertEquals("Dose correction", request.reason)

        val original = c.db.visitDao().document(rx.docId)!!
        val addendum = c.db.visitDao().document(addendumId)!!
        assertEquals(1, original.version)
        assertEquals(2, addendum.version)
        assertEquals(original.serverDocumentId, addendum.serverDocumentId)
        val originalPages = ApiJson.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.samford.doctor.data.remote.PageRefDto.serializer()), original.pageRefsJson!!)
        // Version 2 = every original page unchanged (same hashes) + the addendum page.
        assertEquals(originalPages.map { it.pngSha256 }, request.pages.take(originalPages.size).map { it.pngSha256 })
        assertEquals(originalPages.size + 1, request.pages.size)
        assertTrue(original.pdfSha256 != addendum.pdfSha256)
    }

    @Test
    fun freezingIsRepeatableAfterACrashMidFreeze() = runBlocking {
        val visitId = Fixtures.writeVisit(c)
        assertEquals(null, c.freezer.lockForCompletion(visitId))
        // Crash before the background freeze ran: on restart it resumes and queues exactly once.
        c.freezer.resumeInterrupted()
        c.freezer.resumeInterrupted()
        assertEquals(1, c.db.outboxDao().forVisit(visitId).size)
        assertEquals(VisitStatus.COMPLETED, c.db.visitDao().visit(visitId)!!.status)
    }
}

private suspend fun com.samford.doctor.data.db.DeliveryDao.observeAllOnce() = observeAll().first()
