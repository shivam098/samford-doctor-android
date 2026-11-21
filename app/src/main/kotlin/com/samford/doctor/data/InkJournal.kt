package com.samford.doctor.data

import android.util.Log
import com.samford.doctor.core.Clock
import com.samford.doctor.data.db.InkDao
import com.samford.doctor.data.db.PageEntity
import com.samford.doctor.data.db.PageOpEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.PageDelta
import com.samford.doctor.ink.apply
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Crash-safe autosave for handwriting. Every edit is appended as one [PageDelta] row; loading a
 * page replays the deltas over the last snapshot. Compaction folds deltas into the snapshot in a
 * single transaction, so a crash at any point leaves either the old or the new state.
 */
class InkJournal(private val db: SamfordDb, private val dao: InkDao, private val clock: Clock) {
    private val json = InkFormat.canonicalJson

    suspend fun createPage(pageId: String, docId: String, index: Int) {
        dao.insertPage(PageEntity(pageId, docId, index, json.encodeToString(InkPage.serializer(), InkPage(index)), 0, clock.nowMs()))
    }

    suspend fun append(pageId: String, delta: PageDelta) {
        if (delta.isEmpty) return
        dao.insertOp(PageOpEntity(pageId = pageId, deltaJson = json.encodeToString(PageDelta.serializer(), delta), createdAtMs = clock.nowMs()))
    }

    /** Snapshot and later deltas are read in one transaction, so a concurrent compaction can't hide ink. */
    suspend fun load(pageId: String): InkPage? = db.withTransaction {
        val entity = dao.page(pageId) ?: return@withTransaction null
        var page = json.decodeFromString(InkPage.serializer(), entity.snapshotJson)
        for (op in dao.opsAfter(pageId, entity.snapshotSeq)) {
            page = page.apply(json.decodeFromString(PageDelta.serializer(), op.deltaJson))
        }
        page.copy(index = entity.pageIndex)
    }

    /** Pages of a document in order, as (pageId, page). */
    suspend fun pagesForDoc(docId: String): List<Pair<String, InkPage>> =
        dao.pagesForDoc(docId).map { it.pageId to (load(it.pageId) ?: InkPage(it.pageIndex)) }

    suspend fun compact(pageId: String) {
        db.withTransaction {
            val entity = dao.page(pageId) ?: return@withTransaction
            val ops = dao.opsAfter(pageId, entity.snapshotSeq)
            if (ops.isEmpty()) return@withTransaction
            var page = json.decodeFromString(InkPage.serializer(), entity.snapshotJson)
            ops.forEach { page = page.apply(json.decodeFromString(PageDelta.serializer(), it.deltaJson)) }
            val lastSeq = ops.last().seq
            dao.updateSnapshot(pageId, json.encodeToString(InkPage.serializer(), page), lastSeq)
            dao.deleteOpsUpTo(pageId, lastSeq)
        }
    }
}

/**
 * Serialises journal writes on one background coroutine so deltas land in the order they were
 * made, without the pen ever waiting for the disk.
 */
class JournalWriter(scope: CoroutineScope) {
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in tasks) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.e("JournalWriter", "Autosave write failed", e)
                }
            }
        }
    }

    fun submit(task: suspend () -> Unit) {
        tasks.trySend(task)
    }

    /** Suspends until every write submitted before this call has finished. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        tasks.send { done.complete(Unit) }
        done.await()
    }
}
