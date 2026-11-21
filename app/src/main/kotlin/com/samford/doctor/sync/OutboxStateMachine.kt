package com.samford.doctor.sync

/**
 * Lifecycle of one frozen bundle (a completed visit or an addendum) on its way to the hospital.
 * See docs/sync-state-machine.md. The transition function is pure so it can be tested exhaustively.
 */
enum class OutboxState {
    /** Frozen, hashed and stored durably on the tablet; waiting for the worker. */
    SAVED_ON_TABLET,

    /** Demo walk-in or offline visit: creating/starting the visit on the server first. */
    PREPARING_VISIT,

    /** Uploading content-addressed blobs (pages, PDF, ink). */
    UPLOADING,

    /** Sending the completion/addendum request with the bundle's Idempotency-Key. */
    SUBMITTING,

    /** The hospital acknowledged the bundle. Terminal. */
    RECEIVED,

    /** A temporary problem (offline, timeout, server busy); retried automatically. */
    WAITING_RETRY,

    /** The doctor needs to sign in to the hospital server again. */
    AUTH_REQUIRED,

    /** The server rejected the bundle; needs a person. The record stays safe on the tablet. */
    NEEDS_ATTENTION;

    val isActive: Boolean get() = this == PREPARING_VISIT || this == UPLOADING || this == SUBMITTING
    val isTerminal: Boolean get() = this == RECEIVED
    val needsDoctor: Boolean get() = this == AUTH_REQUIRED || this == NEEDS_ATTENTION

    companion object {
        fun fromName(value: String): OutboxState = entries.firstOrNull { it.name == value } ?: SAVED_ON_TABLET
    }
}

data class SyncError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val authProblem: Boolean = false,
)

data class OutboxRecord(
    val bundleId: String,
    val state: OutboxState,
    val attempts: Int = 0,
    val missingRounds: Int = 0,
    val nextAttemptAtMs: Long = 0,
    val lastError: SyncError? = null,
    val receivedAt: String? = null,
)

sealed interface OutboxEvent {
    data class Begin(val needsServerVisit: Boolean) : OutboxEvent
    data object ServerVisitReady : OutboxEvent
    data object BlobsUploaded : OutboxEvent
    data class Accepted(val receivedAt: String) : OutboxEvent
    data class MissingBlobs(val hashes: List<String>) : OutboxEvent
    data class Failed(val error: SyncError, val nowMs: Long) : OutboxEvent
    data object RetryNow : OutboxEvent
    data object AuthRestored : OutboxEvent

    /** The process stopped mid-flight; the next run starts the bundle again (safe: idempotent). */
    data object Interrupted : OutboxEvent
}

class IllegalOutboxTransition(state: OutboxState, event: OutboxEvent) :
    IllegalStateException("Outbox cannot handle ${event::class.simpleName} in $state")

object OutboxStateMachine {
    const val MAX_MISSING_BLOB_ROUNDS = 3
    private const val BASE_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 30 * 60_000L

    /** 30 s, 1 min, 2 min ... capped at 30 min. */
    fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 10)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun isDue(record: OutboxRecord, nowMs: Long): Boolean =
        record.state == OutboxState.SAVED_ON_TABLET ||
            (record.state == OutboxState.WAITING_RETRY && nowMs >= record.nextAttemptAtMs)

    fun transition(record: OutboxRecord, event: OutboxEvent): OutboxRecord {
        val s = record.state
        fun illegal(): Nothing = throw IllegalOutboxTransition(s, event)
        return when (event) {
            is OutboxEvent.Begin -> {
                if (s != OutboxState.SAVED_ON_TABLET && s != OutboxState.WAITING_RETRY) illegal()
                record.copy(
                    state = if (event.needsServerVisit) OutboxState.PREPARING_VISIT else OutboxState.UPLOADING,
                    lastError = null,
                )
            }
            OutboxEvent.ServerVisitReady -> {
                if (s != OutboxState.PREPARING_VISIT) illegal()
                record.copy(state = OutboxState.UPLOADING)
            }
            OutboxEvent.BlobsUploaded -> {
                if (s != OutboxState.UPLOADING) illegal()
                record.copy(state = OutboxState.SUBMITTING)
            }
            is OutboxEvent.Accepted -> {
                if (s != OutboxState.SUBMITTING) illegal()
                record.copy(state = OutboxState.RECEIVED, receivedAt = event.receivedAt, lastError = null, nextAttemptAtMs = 0)
            }
            is OutboxEvent.MissingBlobs -> {
                if (s != OutboxState.SUBMITTING) illegal()
                val rounds = record.missingRounds + 1
                if (rounds > MAX_MISSING_BLOB_ROUNDS) {
                    record.copy(
                        state = OutboxState.NEEDS_ATTENTION,
                        missingRounds = rounds,
                        lastError = SyncError(
                            "MISSING_BLOBS",
                            "The hospital could not store some page images. The record is safe on this tablet.",
                            retryable = false,
                        ),
                    )
                } else {
                    record.copy(state = OutboxState.UPLOADING, missingRounds = rounds)
                }
            }
            is OutboxEvent.Failed -> {
                if (!s.isActive) illegal()
                val attempts = record.attempts + 1
                when {
                    event.error.authProblem -> record.copy(state = OutboxState.AUTH_REQUIRED, attempts = attempts, lastError = event.error)
                    event.error.retryable -> record.copy(
                        state = OutboxState.WAITING_RETRY,
                        attempts = attempts,
                        nextAttemptAtMs = event.nowMs + backoffMs(attempts),
                        lastError = event.error,
                    )
                    else -> record.copy(state = OutboxState.NEEDS_ATTENTION, attempts = attempts, lastError = event.error)
                }
            }
            OutboxEvent.RetryNow -> {
                if (s != OutboxState.WAITING_RETRY && !s.needsDoctor) illegal()
                record.copy(state = OutboxState.SAVED_ON_TABLET, nextAttemptAtMs = 0, missingRounds = 0)
            }
            OutboxEvent.AuthRestored -> {
                if (s != OutboxState.AUTH_REQUIRED) illegal()
                record.copy(state = OutboxState.SAVED_ON_TABLET, nextAttemptAtMs = 0)
            }
            OutboxEvent.Interrupted -> {
                if (!s.isActive) illegal()
                record.copy(state = OutboxState.SAVED_ON_TABLET)
            }
        }
    }
}
