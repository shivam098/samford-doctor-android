package com.samford.doctor.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWriterTest {
    private fun page(seed: Int): PdfImagePage {
        val w = 30
        val h = 42
        val rgb = ByteArray(w * h * 3) { i -> ((i * 31 + seed) % 256).toByte() }
        return PdfImagePage(w, h, rgb)
    }

    @Test
    fun sameInputGivesByteIdenticalPdf() {
        val a = PdfWriter.write(listOf(page(1), page(2)), "Prescription · DEMO-0001", "V-1-RX")
        val b = PdfWriter.write(listOf(page(1), page(2)), "Prescription · DEMO-0001", "V-1-RX")
        assertArrayEquals(a, b)
        val c = PdfWriter.write(listOf(page(1), page(3)), "Prescription · DEMO-0001", "V-1-RX")
        assertTrue(!a.contentEquals(c))
    }

    @Test
    fun crossReferenceOffsetsPointAtTheirObjects() {
        val pdf = PdfWriter.write(listOf(page(1), page(2)), "पर्चा (Hindi title)", "ref")
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.endsWith("%%EOF\n"))
        val startXref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.substring(startXref).startsWith("xref"))
        val entries = text.substring(startXref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(9, entries.size)
        entries.forEachIndexed { i, line ->
            val offset = line.substring(0, 10).toInt()
            assertTrue("object ${i + 1}", text.substring(offset).startsWith("${i + 1} 0 obj"))
        }
        assertTrue(text.contains("/Count 2"))
        assertTrue(!text.contains("CreationDate"))
    }
}
