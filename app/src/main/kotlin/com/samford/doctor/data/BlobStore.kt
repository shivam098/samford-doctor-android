package com.samford.doctor.data

import com.samford.doctor.core.Clock
import com.samford.doctor.core.Hashing
import com.samford.doctor.data.db.BlobDao
import com.samford.doctor.data.db.BlobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Content-addressed, immutable local store: files/blobs/<sha256>. A blob is written to a temp
 * file, fsynced and renamed, so a crash never leaves a half-written blob under its hash.
 */
class BlobStore(private val dir: File, private val dao: BlobDao, private val clock: Clock) {

    init {
        dir.mkdirs()
    }

    fun file(sha256: String): File = File(dir, sha256)

    suspend fun put(bytes: ByteArray, contentType: String, alreadyOnServer: Boolean = false): String = withContext(Dispatchers.IO) {
        val sha = Hashing.sha256Hex(bytes)
        writeIfAbsent(sha, bytes)
        dao.insert(BlobEntity(sha, contentType, bytes.size.toLong(), if (alreadyOnServer) clock.nowMs() else null))
        sha
    }

    /** Stores bytes downloaded for [expectedSha]; rejects content whose hash does not match. */
    suspend fun putDownloaded(expectedSha: String, bytes: ByteArray, contentType: String): Boolean = withContext(Dispatchers.IO) {
        if (Hashing.sha256Hex(bytes) != expectedSha) return@withContext false
        writeIfAbsent(expectedSha, bytes)
        dao.insert(BlobEntity(expectedSha, contentType, bytes.size.toLong(), clock.nowMs()))
        true
    }

    suspend fun read(sha256: String): ByteArray? = withContext(Dispatchers.IO) {
        val f = file(sha256)
        if (f.isFile) f.readBytes() else null
    }

    fun exists(sha256: String): Boolean = file(sha256).isFile

    private fun writeIfAbsent(sha: String, bytes: ByteArray) {
        val target = file(sha)
        if (target.isFile && target.length() == bytes.size.toLong()) return
        val tmp = File(dir, "$sha.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            if (!target.isFile) error("Could not save a page on this tablet")
        }
    }
}
