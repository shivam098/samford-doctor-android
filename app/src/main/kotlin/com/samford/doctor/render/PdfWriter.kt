package com.samford.doctor.render

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Deflater

/** One page image in packed 8-bit RGB, top row first. */
class PdfImagePage(val widthPx: Int, val heightPx: Int, val rgb: ByteArray) {
    init {
        require(rgb.size == widthPx * heightPx * 3) { "RGB buffer does not match page size" }
    }
}

class CompressedPdfPage(val widthPx: Int, val heightPx: Int, val deflated: ByteArray)

/**
 * Minimal deterministic PDF 1.4 writer. Each page is an A4 MediaBox showing exactly the canonical
 * page pixels (Flate-compressed RGB), so the PDF and the PNG pages are the same handwriting by
 * construction. No timestamps are written; the document ID is a hash of the content, so the same
 * input always yields byte-identical output on the same build.
 */
object PdfWriter {
    private const val A4_W = "595.28"
    private const val A4_H = "841.89"

    /** Compresses one page right away so callers never hold many raw RGB buffers at once. */
    fun compress(page: PdfImagePage): CompressedPdfPage = CompressedPdfPage(page.widthPx, page.heightPx, deflate(page.rgb))

    fun write(pages: List<PdfImagePage>, title: String, subject: String): ByteArray =
        writeCompressed(pages.map(::compress), title, subject)

    fun writeCompressed(pages: List<CompressedPdfPage>, title: String, subject: String): ByteArray {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

        ascii("%PDF-1.4\n")
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        val n = pages.size
        val total = 3 + 3 * n
        val offsets = IntArray(total + 1)
        fun begin(num: Int) {
            offsets[num] = out.size()
            ascii("$num 0 obj\n")
        }

        begin(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2)
        ascii("<< /Type /Pages /Kids [" + (0 until n).joinToString(" ") { "${4 + 3 * it} 0 R" } + "] /Count $n >>\nendobj\n")
        begin(3)
        ascii("<< /Title ${pdfText(title)} /Subject ${pdfText(subject)} /Producer (Samford Doctor samford-render/1) >>\nendobj\n")

        pages.forEachIndexed { i, page ->
            val pageNum = 4 + 3 * i
            val imageNum = 5 + 3 * i
            val contentNum = 6 + 3 * i
            begin(pageNum)
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $A4_W $A4_H] " +
                    "/Resources << /XObject << /Im0 $imageNum 0 R >> /ProcSet [/PDF /ImageC] >> /Contents $contentNum 0 R >>\nendobj\n",
            )
            val compressed = page.deflated
            begin(imageNum)
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.widthPx} /Height ${page.heightPx} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${compressed.size} >>\nstream\n",
            )
            out.write(compressed)
            ascii("\nendstream\nendobj\n")
            val content = "q\n$A4_W 0 0 $A4_H 0 0 cm\n/Im0 Do\nQ\n".toByteArray(Charsets.ISO_8859_1)
            begin(contentNum)
            ascii("<< /Length ${content.size} >>\nstream\n")
            out.write(content)
            ascii("\nendstream\nendobj\n")
        }

        val xref = out.size()
        ascii("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (num in 1..total) ascii(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[num]))
        val id = MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
            .joinToString("") { "%02X".format(Locale.ROOT, it) }.substring(0, 32)
        ascii("trailer\n<< /Size ${total + 1} /Root 1 0 R /Info 3 0 R /ID [<$id> <$id>] >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** PDF text string as UTF-16BE hex with BOM, safe for Hindi and punctuation. */
    private fun pdfText(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_16BE)
        return "<FEFF" + bytes.joinToString("") { "%02X".format(Locale.ROOT, it) } + ">"
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 8 + 64)
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
