package com.samford.doctor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitDao {
    @Upsert
    suspend fun upsertVisit(visit: VisitEntity)

    @Query("SELECT * FROM visits WHERE visitId = :visitId")
    suspend fun visit(visitId: String): VisitEntity?

    @Query("SELECT * FROM visits WHERE visitId = :visitId")
    fun observeVisit(visitId: String): Flow<VisitEntity?>

    @Query("SELECT * FROM visits WHERE queueEntryId = :queueEntryId ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun visitForQueueEntry(queueEntryId: String): VisitEntity?

    @Query("SELECT * FROM visits ORDER BY startedAtMs DESC")
    fun observeVisits(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE status = :status")
    suspend fun visitsWithStatus(status: String): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE patientId = :patientId AND status = 'COMPLETED' ORDER BY startedAtMs DESC")
    suspend fun completedVisitsForPatient(patientId: String): List<VisitEntity>

    @Upsert
    suspend fun upsertDocuments(docs: List<DocumentEntity>)

    @Upsert
    suspend fun upsertDocument(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE docId = :docId")
    suspend fun document(docId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE visitId = :visitId ORDER BY sortOrder, createdAtMs")
    suspend fun documentsForVisit(visitId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE visitId = :visitId ORDER BY sortOrder, createdAtMs")
    fun observeDocumentsForVisit(visitId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE parentDocId = :docId ORDER BY version")
    suspend fun addendaFor(docId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE serverDocumentId = :serverDocumentId LIMIT 1")
    suspend fun documentByServerId(serverDocumentId: String): DocumentEntity?
}

@Dao
interface InkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPage(page: PageEntity)

    @Query("SELECT * FROM pages WHERE pageId = :pageId")
    suspend fun page(pageId: String): PageEntity?

    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY pageIndex")
    suspend fun pagesForDoc(docId: String): List<PageEntity>

    @Insert
    suspend fun insertOp(op: PageOpEntity): Long

    @Query("SELECT * FROM page_ops WHERE pageId = :pageId AND seq > :afterSeq ORDER BY seq")
    suspend fun opsAfter(pageId: String, afterSeq: Long): List<PageOpEntity>

    @Query("UPDATE pages SET snapshotJson = :json, snapshotSeq = :seq WHERE pageId = :pageId")
    suspend fun updateSnapshot(pageId: String, json: String, seq: Long)

    @Query("DELETE FROM page_ops WHERE pageId = :pageId AND seq <= :seq")
    suspend fun deleteOpsUpTo(pageId: String, seq: Long)

    @Query("SELECT COUNT(*) FROM page_ops WHERE pageId = :pageId")
    suspend fun opCount(pageId: String): Int
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: OutboxEntity)

    @Update
    suspend fun update(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE bundleId = :bundleId")
    suspend fun get(bundleId: String): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY createdAtMs")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE visitId = :visitId ORDER BY createdAtMs")
    suspend fun forVisit(visitId: String): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlobRefs(refs: List<OutboxBlobEntity>)

    @Query("SELECT b.* FROM blobs b INNER JOIN outbox_blobs ob ON ob.sha256 = b.sha256 WHERE ob.bundleId = :bundleId ORDER BY b.sha256")
    suspend fun blobsForBundle(bundleId: String): List<BlobEntity>
}

@Dao
interface BlobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blob: BlobEntity)

    @Query("SELECT * FROM blobs WHERE sha256 = :sha256")
    suspend fun get(sha256: String): BlobEntity?

    @Query("UPDATE blobs SET uploadedAtMs = :atMs WHERE sha256 = :sha256")
    suspend fun markUploaded(sha256: String, atMs: Long)

    @Query("UPDATE blobs SET uploadedAtMs = NULL WHERE sha256 IN (:hashes)")
    suspend fun markNotUploaded(hashes: List<String>)
}

@Dao
interface DeliveryDao {
    @Upsert
    suspend fun upsertAll(items: List<DeliveryEntity>)

    @Query("SELECT * FROM delivery WHERE visitId = :visitId ORDER BY docType, recipientKind, name")
    fun observeForVisit(visitId: String): Flow<List<DeliveryEntity>>

    @Query("SELECT * FROM delivery ORDER BY visitId, docType, recipientKind, name")
    fun observeAll(): Flow<List<DeliveryEntity>>
}

@Dao
interface DemoDao {
    @Upsert
    suspend fun upsert(entry: DemoQueueEntity)

    @Query("SELECT * FROM demo_queue ORDER BY createdAtMs")
    suspend fun all(): List<DemoQueueEntity>

    @Query("SELECT * FROM demo_queue ORDER BY createdAtMs")
    fun observeAll(): Flow<List<DemoQueueEntity>>

    @Query("SELECT COUNT(*) FROM demo_queue")
    suspend fun count(): Int
}

@Dao
interface CacheDao {
    @Upsert
    suspend fun put(entry: CacheEntity)

    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntity?
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events ORDER BY receivedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>
}
