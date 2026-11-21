package com.samford.doctor.core

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object Hashing {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(64)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun isSha256Hex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

interface IdGenerator {
    fun uuid(): String
    fun shortId(prefix: String): String
}

object RandomIds : IdGenerator {
    override fun uuid(): String = UUID.randomUUID().toString()
    override fun shortId(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "").take(12)
}

interface Clock {
    fun now(): Instant
    fun nowMs(): Long = now().toEpochMilli()
}

object WallClock : Clock {
    override fun now(): Instant = Instant.now()
}

/** All user-visible and printed times are IST, formatted without device-locale influence. */
object Ist {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val date = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    private val dateTime = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'IST'", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

    fun date(instant: Instant): String = date.format(instant.atZone(zone))
    fun dateTime(instant: Instant): String = dateTime.format(instant.atZone(zone))
    fun time(instant: Instant): String = time.format(instant.atZone(zone))
    fun isoDate(instant: Instant): String = isoDate.format(instant.atZone(zone))
    fun isoOffset(instant: Instant): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone))
}
