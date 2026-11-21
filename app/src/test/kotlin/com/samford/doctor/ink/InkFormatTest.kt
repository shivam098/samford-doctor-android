package com.samford.doctor.ink

import com.samford.doctor.core.Hashing
import com.samford.doctor.testing.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InkFormatTest {
    private fun sample() = InkFile(
        document = InkDocumentMeta("doc-1", "PRESCRIPTION", "Prescription", "samford-opd-prescription", 1, "V-ABCDEF12"),
        header = PageHeader("Asha (Synthetic)", "34 / F", "DEMO-0001", "21 Nov 2026", "7", "V-ABCDEF12", "Dr. Demo", "MBBS", "DEMO-1", "Medicine", "Penicillin", true, "BP 120/80", true),
        pages = listOf(InkPage(0, listOf(Fixtures.stroke("s1")), listOf(InkStamp("t1", "chip", "1-0-1", x = 1, y = 2, w = 3, h = 4, size = 1800, color = "#1A3C8C", hint = StampHint("dosage", "1-0-1", "1-0-1"))))),
        signature = SignatureBlock(SyntheticInk.signature(), "2026-11-21T05:00:00Z", "21 Nov 2026, 10:30 IST", "Dr. Demo", "DEMO-1"),
    )

    @Test
    fun canonicalEncodingIsStableAndRoundTrips() {
        val a = InkFormat.encode(sample())
        val b = InkFormat.encode(sample())
        assertArrayEquals(a, b)
        assertEquals(Hashing.sha256Hex(a), Hashing.sha256Hex(b))
        val decoded = InkFormat.decode(a)
        assertEquals(sample(), decoded)
        assertArrayEquals(a, InkFormat.encode(decoded))
    }

    @Test
    fun encodingIsCompactIntegerJsonWithFormatFirst() {
        val text = InkFormat.encode(sample()).toString(Charsets.UTF_8)
        assertTrue(text.startsWith("{\"format\":\"samford-ink/1\",\"renderer\":\"samford-render/1\""))
        assertTrue(!text.contains(' ' + "\n"))
        assertTrue(!Regex("\\d\\.\\d").containsMatchIn(text.substringAfter("\"pts\":[").substringBefore("]")))
    }

    @Test
    fun otherFormatsAreRefused() {
        val placeholder = """{"format":"samford-ink-placeholder/0","document":{"clientDocId":"x","type":"OTHER","title":"","templateId":"","templateVersion":0,"visitRef":""},"header":{"patientName":"","ageSex":"","uhid":"","date":"","token":"","visitRef":"","doctorName":"","qualification":"","registrationNo":"","department":"","allergies":"","hasAllergies":false,"vitals":"","synthetic":true},"pages":[]}"""
        assertThrows(IllegalArgumentException::class.java) { InkFormat.decode(placeholder.toByteArray()) }
    }

    @Test
    fun strokeEqualityComparesPointContent() {
        assertEquals(Fixtures.stroke("a"), Fixtures.stroke("a"))
        assertEquals(Fixtures.stroke("a").hashCode(), Fixtures.stroke("a").hashCode())
    }
}
