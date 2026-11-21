package com.samford.doctor.render

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.core.Hashing
import com.samford.doctor.ink.InkFile
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.ink.SignatureBlock
import com.samford.doctor.ink.StampCatalog
import com.samford.doctor.ink.SyntheticInk
import com.samford.doctor.editor.StampFactory
import com.samford.doctor.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderDeterminismTest {
    private val header = PageHeader(
        "Meera Kumari (Synthetic)", "34 / F", "DEMO-0001", "21 Nov 2026", "D1", "V-1234ABCD",
        "Dr. Asha Verma (Demo)", "MBBS, MD", "DEMO-JMC-1187", "General Medicine", "Penicillin (rash)", true,
        "BP 118/76 · Pulse 82", true,
    )

    private fun page(): InkPage {
        val base = SyntheticInk.page(42, 0, lines = 5)
        val stamps = listOf(
            StampFactory.create(StampCatalog.medicines[0], 12000, 60000, "t1"),
            StampFactory.create(StampCatalog.dosage[0], 50000, 60000, "t2"),
            StampFactory.create(StampCatalog.diagrams[0], 70000, 95000, "t3"),
        )
        val highlight = SyntheticInk.page(7, 0, 1).strokes.first().copy(id = "hl", tool = "highlighter", color = "#FFC107", w = 1400)
        return base.copy(strokes = base.strokes + highlight, stamps = stamps)
    }

    private fun spec(sig: SignatureBlock?) = PageSpec(DocType.PRESCRIPTION, "Prescription", header, 1, 1, "V-1234ABCD-RX", signature = sig)

    private val signature = SignatureBlock(SyntheticInk.signature(), "2026-11-21T05:00:00Z", "21 Nov 2026, 10:30 IST", "Dr. Asha Verma (Demo)", "DEMO-JMC-1187")

    @Test
    fun sameInkGivesTheSamePngAndPdfHashes() {
        val ink = InkFile(
            document = com.samford.doctor.ink.InkDocumentMeta("d1", "PRESCRIPTION", "Prescription", "samford-opd-prescription", 1, "V-1234ABCD"),
            header = header,
            pages = listOf(page()),
            signature = signature,
        )
        val bytes = InkFormat.encode(ink)
        fun renderOnce(): Pair<String, String> {
            val stored = InkFormat.decode(bytes)
            val bmp = PageRasterizer.render(spec(stored.signature).copy(header = stored.header), stored.pages[0], PageCompositor())
            val png = PageRasterizer.encodePng(bmp)
            val pdf = PdfWriter.write(listOf(PageRasterizer.toPdfPage(bmp)), "Prescription", "V-1234ABCD-RX")
            return Hashing.sha256Hex(png) to Hashing.sha256Hex(pdf)
        }
        val first = renderOnce()
        val second = renderOnce()
        assertEquals(first, second)
    }

    @Test
    fun canonicalPageHasSizeBannerSignatureAndInk() {
        val bmp = PageRasterizer.render(spec(signature), page(), PageCompositor())
        assertEquals(InkFormat.RENDER_WIDTH_PX, bmp.width)
        assertEquals(InkFormat.RENDER_HEIGHT_PX, bmp.height)
        // The red "SYNTHETIC SAMPLE" banner sits at the very top of every page.
        val banner = bmp.getPixel(100, 12)
        assertTrue("banner should be red, was ${Integer.toHexString(banner)}", Color.red(banner) > 150 && Color.green(banner) < 90)
        val withoutInk = PageRasterizer.render(spec(signature), InkPage(0), PageCompositor())
        assertNotEquals(
            Hashing.sha256Hex(PageRasterizer.encodePng(bmp)),
            Hashing.sha256Hex(PageRasterizer.encodePng(withoutInk)),
        )
        val unsigned = PageRasterizer.render(spec(null), page(), PageCompositor())
        assertNotEquals(Hashing.sha256Hex(PageRasterizer.encodePng(bmp)), Hashing.sha256Hex(PageRasterizer.encodePng(unsigned)))
    }

    @Test
    fun stampMeasurementMatchesStoredBox() {
        val chip = StampFactory.create(StampCatalog.dosage[0], 50000, 60000, "c")
        assertEquals(InkStamp.KIND_CHIP, chip.kind)
        assertTrue(chip.w > 0 && chip.h > 0)
    }
}
