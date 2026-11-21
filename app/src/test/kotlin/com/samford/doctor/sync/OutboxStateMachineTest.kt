package com.samford.doctor.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxStateMachineTest {
    private val sm = OutboxStateMachine
    private val saved = OutboxRecord("b1", OutboxState.SAVED_ON_TABLET)
    private val network = SyncError("NETWORK", "offline", retryable = true)

    @Test
    fun happyPathReachesReceived() {
        var r = sm.transition(saved, OutboxEvent.Begin(needsServerVisit = true))
        assertEquals(OutboxState.PREPARING_VISIT, r.state)
        r = sm.transition(r, OutboxEvent.ServerVisitReady)
        r = sm.transition(r, OutboxEvent.BlobsUploaded)
        assertEquals(OutboxState.SUBMITTING, r.state)
        r = sm.transition(r, OutboxEvent.Accepted("2026-11-21T05:00:00Z"))
        assertEquals(OutboxState.RECEIVED, r.state)
        assertEquals("2026-11-21T05:00:00Z", r.receivedAt)
        assertNull(r.lastError)
    }

    @Test
    fun receivedIsTerminalSoABundleCanNeverBeSentTwice() {
        val received = saved.copy(state = OutboxState.RECEIVED)
        assertThrows(IllegalOutboxTransition::class.java) { sm.transition(received, OutboxEvent.Begin(false)) }
        assertThrows(IllegalOutboxTransition::class.java) { sm.transition(received, OutboxEvent.RetryNow) }
        assertTrue(sm.isDue(saved, 0))
        assertTrue(!sm.isDue(received, Long.MAX_VALUE))
    }

    @Test
    fun retryableFailureBacksOffExponentiallyAndCaps() {
        var r = sm.transition(saved, OutboxEvent.Begin(false))
        r = sm.transition(r, OutboxEvent.Failed(network, nowMs = 1_000))
        assertEquals(OutboxState.WAITING_RETRY, r.state)
        assertEquals(1, r.attempts)
        assertEquals(1_000 + 30_000, r.nextAttemptAtMs)
        assertTrue(!sm.isDue(r, 30_999))
        assertTrue(sm.isDue(r, 31_000))
        r = sm.transition(sm.transition(r, OutboxEvent.Begin(false)), OutboxEvent.Failed(network, nowMs = 100_000))
        assertEquals(2, r.attempts)
        assertEquals(100_000 + 60_000, r.nextAttemptAtMs)
        assertEquals(30 * 60_000L, sm.backoffMs(20))
    }

    @Test
    fun authProblemsWaitForTheDoctorAndResumeAfterSignIn() {
        var r = sm.transition(saved, OutboxEvent.Begin(false))
        r = sm.transition(r, OutboxEvent.Failed(SyncError("TOKEN_EXPIRED", "sign in", false, authProblem = true), 0))
        assertEquals(OutboxState.AUTH_REQUIRED, r.state)
        assertTrue(r.state.needsDoctor)
        r = sm.transition(r, OutboxEvent.AuthRestored)
        assertEquals(OutboxState.SAVED_ON_TABLET, r.state)
    }

    @Test
    fun nonRetryableRejectionNeedsAttentionButCanBeRetriedManually() {
        var r = sm.transition(saved, OutboxEvent.Begin(false))
        r = sm.transition(sm.transition(r, OutboxEvent.BlobsUploaded), OutboxEvent.Failed(SyncError("ROUTING_INVALID", "bad", false), 0))
        assertEquals(OutboxState.NEEDS_ATTENTION, r.state)
        r = sm.transition(r, OutboxEvent.RetryNow)
        assertEquals(OutboxState.SAVED_ON_TABLET, r.state)
    }

    @Test
    fun missingBlobsGoBackToUploadingThenGiveUpAfterThreeRounds() {
        var r = sm.transition(sm.transition(saved, OutboxEvent.Begin(false)), OutboxEvent.BlobsUploaded)
        repeat(OutboxStateMachine.MAX_MISSING_BLOB_ROUNDS) {
            r = sm.transition(r, OutboxEvent.MissingBlobs(listOf("abc")))
            assertEquals(OutboxState.UPLOADING, r.state)
            r = sm.transition(r, OutboxEvent.BlobsUploaded)
        }
        r = sm.transition(r, OutboxEvent.MissingBlobs(listOf("abc")))
        assertEquals(OutboxState.NEEDS_ATTENTION, r.state)
    }

    @Test
    fun interruptedActiveStatesRestartSafely() {
        val uploading = sm.transition(saved, OutboxEvent.Begin(false))
        assertEquals(OutboxState.SAVED_ON_TABLET, sm.transition(uploading, OutboxEvent.Interrupted).state)
        assertThrows(IllegalOutboxTransition::class.java) { sm.transition(saved, OutboxEvent.Interrupted) }
    }

    @Test
    fun outOfOrderEventsAreRejected() {
        assertThrows(IllegalOutboxTransition::class.java) { sm.transition(saved, OutboxEvent.BlobsUploaded) }
        assertThrows(IllegalOutboxTransition::class.java) { sm.transition(saved, OutboxEvent.Accepted("x")) }
        assertThrows(IllegalOutboxTransition::class.java) { sm.transition(saved, OutboxEvent.Failed(network, 0)) }
    }
}
