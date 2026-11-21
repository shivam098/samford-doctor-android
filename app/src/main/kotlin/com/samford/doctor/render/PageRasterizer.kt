package com.samford.doctor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import java.io.ByteArrayOutputStream

/** Renders canonical page images (1500 x 2121 px) and encodes them losslessly. */
object PageRasterizer {

    fun render(
        spec: PageSpec,
        page: InkPage,
        compositor: PageCompositor = PageCompositor(),
        mode: RenderMode = RenderMode.EXPORT,
        widthPx: Int = InkFormat.RENDER_WIDTH_PX,
    ): Bitmap {
        val scale = widthPx / InkFormat.PAGE_WIDTH_U.toFloat()
        val heightPx = Math.round(InkFormat.PAGE_HEIGHT_U * scale)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        compositor.drawPage(canvas, spec, page, mode)
        return bitmap
    }

    fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not encode page image" }
        return out.toByteArray()
    }

    fun toPdfPage(bitmap: Bitmap): PdfImagePage {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        var o = 0
        for (p in pixels) {
            rgb[o++] = (p shr 16 and 0xFF).toByte()
            rgb[o++] = (p shr 8 and 0xFF).toByte()
            rgb[o++] = (p and 0xFF).toByte()
        }
        return PdfImagePage(w, h, rgb)
    }
}
