package com.samford.doctor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object VisitStatus {
    const val DRAFT = "DRAFT"
    /** Completion requested; freezing is in progress or will resume after a restart. */
    const val COMPLETING = "COMPLETING"
    const val COMPLETED = "COMPLETED"
}

object DocStatus {
    const val DRAFT = "DRAFT"
    const val FROZEN = "FROZEN"
    /** Nothing was written in this document when the visit was completed; it is not sent. */
    const val EMPTY = "EMPTY"
}

object BundleKind {
    const val COMPLETE = "COMPLETE"
    const val ADDENDUM = "ADDENDUM"
}

@Entity(tableName = "visits", indices = [Index("patientId"), Index("status"), Index("queueEntryId")])
data class VisitEntity(
    @PrimaryKey val visitId: String,
    val serverVisitId: String?,
    val queueEntryId: String?,
    /** True when [queueEntryId] belongs to the hospital server's queue (not a local demo entry). */
    val serverQueueEntry: Boolean,
    val patientId: String,
    val patientJson: String,
    val doctorJson: String,
    val vitalsJson: String?,
    val token: String,
    val returning: Boolean,
    val isDemo: Boolean,
    /** Synthetic history created for offline demos; never uploaded. */
    val isSeedHistory: Boolean = false,
    val status: String,
    val startedAtMs: Long,
    val completedAtMs: Long? = null,
    val followUpDate: String? = null,
    val followUpNote: String? = null,
    val bundleId: String? = null,
)

@Entity(tableName = "documents", indices = [Index("visitId"), Index("parentDocId"), Index("serverDocumentId")])
data class DocumentEntity(
    @PrimaryKey val docId: String,
    val visitId: String,
    val type: String,
    val title: String,
    val sortOrder: Int,
    val labsJson: String = "[]",
    val isAddendum: Boolean = false,
    val parentDocId: String? = null,
    val addendumReason: String? = null,
    val status: String = DocStatus.DRAFT,
    val version: Int = 1,
    val serverDocumentId: String? = null,
    val inkSha256: String? = null,
    val pdfSha256: String? = null,
    /** JSON list of PageRefDto for the frozen version (includes earlier pages for addenda). */
    val pageRefsJson: String? = null,
    val signedAt: String? = null,
    val createdAtMs: Long,
)

/** Autosave: a page is its last compacted snapshot plus the deltas appended after it. */
@Entity(tableName = "pages", indices = [Index("docId")])
data class PageEntity(
    @PrimaryKey val pageId: String,
    val docId: String,
    val pageIndex: Int,
    val snapshotJson: String,
    val snapshotSeq: Long,
    val createdAtMs: Long,
)

@Entity(tableName = "page_ops", indices = [Index("pageId")])
data class PageOpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val pageId: String,
    val deltaJson: String,
    val createdAtMs: Long,
)

@Entity(tableName = "outbox", indices = [Index("visitId"), Index("state")])
data class OutboxEntity(
    @PrimaryKey val bundleId: String,
    val visitId: String,
    val kind: String,
    /** For addenda: the local addendum document id. */
    val docId: String? = null,
    val payloadJson: String,
    val state: String,
    val attempts: Int = 0,
    val missingRounds: Int = 0,
    val nextAttemptAtMs: Long = 0,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastErrorRetryable: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val receivedAt: String? = null,
    val responseJson: String? = null,
)

@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey val sha256: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedAtMs: Long? = null,
)

@Entity(tableName = "outbox_blobs", primaryKeys = ["bundleId", "sha256"], indices = [Index("sha256")])
data class OutboxBlobEntity(val bundleId: String, val sha256: String)

@Entity(tableName = "delivery", indices = [Index("visitId")])
data class DeliveryEntity(
    @PrimaryKey val key: String,
    val visitId: String,
    val docId: String?,
    val serverDocumentId: String,
    val version: Int,
    val docType: String?,
    val recipientKind: String,
    val labId: String?,
    val name: String,
    val state: String,
    val updatedAt: String?,
)

/** Walk-ins and synthetic patients created on the tablet (offline demo mode or demo button). */
@Entity(tableName = "demo_queue")
data class DemoQueueEntity(
    @PrimaryKey val queueEntryId: String,
    val token: String,
    val patientJson: String,
    val vitalsJson: String?,
    val returning: Boolean,
    val createdAtMs: Long,
)

@Entity(tableName = "cache")
data class CacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val etag: String?,
    val fetchedAtMs: Long,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val type: String,
    val visitId: String?,
    val documentId: String?,
    val message: String,
    val at: String?,
    val receivedAtMs: Long,
)
