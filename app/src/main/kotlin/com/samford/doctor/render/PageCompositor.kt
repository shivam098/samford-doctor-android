package com.samford.doctor.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.samford.doctor.ink.Diagrams
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.SignatureBlock
import com.samford.doctor.model.DocType
import java.util.Locale

/**
 * Draws a complete page (letterhead, automatic patient header, ruled guides, handwriting,
 * stamps, signature, footer) onto a canvas that maps 1 page unit to device pixels.
 * Output depends only on (spec, page, mode) and the template version, never on device locale.
 *
 * Z-order (part of samford-render/1): highlighter strokes, diagram stamps, pen strokes,
 * chip/text/label stamps, signature.
 */
class PageCompositor {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textLocale = Locale.ENGLISH
    }
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint()

    fun drawPage(canvas: Canvas, spec: PageSpec, page: InkPage, mode: RenderMode) {
        drawBackground(canvas, spec, mode)
        drawContent(canvas, page)
        val lastPage = spec.pageNumber == spec.pageCount
        if (lastPage && spec.signature != null) drawSignature(canvas, spec.signature)
        else if (lastPage && mode == RenderMode.SCREEN) drawSignaturePlaceholder(canvas)
    }

    fun drawBackground(canvas: Canvas, spec: PageSpec, mode: RenderMode) {
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(0f, 0f, InkFormat.PAGE_WIDTH_U.toFloat(), InkFormat.PAGE_HEIGHT_U.toFloat(), fill)
        if (spec.header.synthetic) drawWatermark(canvas)
        drawSampleBanner(canvas)
        drawLetterhead(canvas, spec)
        drawHeader(canvas, spec)
        drawGuides(canvas, spec)
        drawFooter(canvas, spec)
    }

    fun drawContent(canvas: Canvas, page: InkPage) {
        page.strokes.filter { it.isHighlighter }.forEach { StrokePainter.draw(canvas, it, strokePaint) }
        page.stamps.filter { it.kind == InkStamp.KIND_DIAGRAM }.forEach { drawStamp(canvas, it) }
        page.strokes.filter { !it.isHighlighter }.forEach { StrokePainter.draw(canvas, it, strokePaint) }
        page.stamps.filter { it.kind != InkStamp.KIND_DIAGRAM }.forEach { drawStamp(canvas, it) }
    }

    /** Every canonical page of this demo system carries this banner (hashes freeze it into the record). */
    private fun drawSampleBanner(canvas: Canvas) {
        fill.color = PageTemplates.ALERT_RED
        canvas.drawRoundRect(RectF(PageTemplates.LEFT, 6f, PageTemplates.RIGHT, 30f), 4f, 4f, fill)
        text.typeface = bold
        text.textSize = 14f
        text.color = 0xFFFFFFFF.toInt()
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(SAMPLE_BANNER, 500f, 23f, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawWatermark(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-30f, 500f, 760f)
        text.typeface = bold
        text.textSize = 62f
        text.color = 0x12B3261E
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("SYNTHETIC DEMO RECORD", 500f, 780f, text)
        canvas.restore()
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawLetterhead(canvas: Canvas, spec: PageSpec) {
        // Hospital mark: brand circle with a white cross.
        fill.color = PageTemplates.BRAND
        canvas.drawCircle(80f, 70f, 30f, fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(74f, 52f, 86f, 88f, fill)
        canvas.drawRect(62f, 64f, 98f, 76f, fill)

        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 30f
        text.color = PageTemplates.BRAND
        canvas.drawText("Samford Hospital, Ranchi", 124f, PageTemplates.TITLE_BASELINE, text)
        text.typeface = regular
        text.textSize = 16f
        text.color = PageTemplates.LABEL_GREY
        canvas.drawText(fit("Department of ${spec.header.department} · Out-patient department", 440f, 16f), 124f, PageTemplates.SUBTITLE_BASELINE, text)

        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold
        text.textSize = 28f
        text.color = if (spec.addendum != null) PageTemplates.ALERT_RED else PageTemplates.BRAND
        canvas.drawText(spec.title, PageTemplates.RIGHT, PageTemplates.TITLE_BASELINE, text)
        text.typeface = regular
        text.textSize = 17f
        text.color = PageTemplates.LABEL_GREY
        val hindi = if (spec.addendum != null) "परिशिष्ट" else spec.docType.pageTitleHi
        canvas.drawText(hindi, PageTemplates.RIGHT, PageTemplates.SUBTITLE_BASELINE, text)
        text.textAlign = Paint.Align.LEFT

        line.color = PageTemplates.BRAND
        line.strokeWidth = 2.5f
        line.pathEffect = null
        canvas.drawLine(PageTemplates.LEFT, PageTemplates.LETTERHEAD_RULE, PageTemplates.RIGHT, PageTemplates.LETTERHEAD_RULE, line)
    }

    private fun drawHeader(canvas: Canvas, spec: PageSpec) {
        val h = spec.header
        fill.color = PageTemplates.HEADER_FILL
        canvas.drawRoundRect(RectF(PageTemplates.LEFT, PageTemplates.HEADER_TOP, PageTemplates.RIGHT, PageTemplates.HEADER_BOTTOM), 8f, 8f, fill)

        val rows = PageTemplates.HEADER_ROWS
        field(canvas, "Patient", h.patientName, 62f, rows[0], 440f, valueBold = true)
        field(canvas, "Age/Sex", h.ageSex, 520f, rows[0], 160f)
        field(canvas, "UHID", h.uhid, 700f, rows[0], 245f, valueBold = true)

        field(canvas, "Date", h.date, 62f, rows[1], 230f)
        field(canvas, "Token", h.token, 300f, rows[1], 210f, valueBold = true)
        field(canvas, "Visit", h.visitRef, 520f, rows[1], 170f)
        field(canvas, "Dept", h.department, 700f, rows[1], 245f)

        val doctor = listOf(h.doctorName, h.qualification).filter { it.isNotBlank() }.joinToString(", ")
        field(canvas, "Doctor", doctor, 62f, rows[2], 630f)
        field(canvas, "Reg. No.", h.registrationNo, 700f, rows[2], 245f, valueBold = true)

        field(
            canvas, "Allergies", h.allergies, 62f, rows[3], 400f,
            valueBold = h.hasAllergies, valueColor = if (h.hasAllergies) PageTemplates.ALERT_RED else PageTemplates.INK_DARK,
        )
        field(canvas, "Vitals", h.vitals, 480f, rows[3], 465f)

        if (h.synthetic) {
            text.textAlign = Paint.Align.RIGHT
            text.typeface = bold
            text.textSize = 11f
            text.color = PageTemplates.ALERT_RED
            canvas.drawText("SYNTHETIC — NOT A REAL PATIENT", PageTemplates.RIGHT - 8f, PageTemplates.HEADER_TOP + 13f, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun field(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        baseline: Float,
        width: Float,
        valueBold: Boolean = false,
        valueColor: Int = PageTemplates.INK_DARK,
    ) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = regular
        text.textSize = 13f
        text.color = PageTemplates.LABEL_GREY
        val labelText = "$label: "
        canvas.drawText(labelText, x, baseline, text)
        val labelWidth = text.measureText(labelText)
        text.typeface = if (valueBold) bold else regular
        text.textSize = 17f
        text.color = valueColor
        canvas.drawText(fit(value.ifBlank { "—" }, width - labelWidth, 17f), x + labelWidth, baseline, text)
    }

    /** Trims [value] with an ellipsis so it fits [maxWidth] at the current typeface. */
    private fun fit(value: String, maxWidth: Float, size: Float): String {
        text.textSize = size
        if (text.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && text.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    private fun drawGuides(canvas: Canvas, spec: PageSpec) {
        line.pathEffect = null
        line.color = PageTemplates.GUIDE_BLUE
        line.strokeWidth = 1f
        var y = PageTemplates.GUIDE_FIRST
        while (y <= PageTemplates.GUIDE_LAST) {
            canvas.drawLine(PageTemplates.LEFT, y, PageTemplates.RIGHT, y, line)
            y += PageTemplates.GUIDE_STEP
        }
        line.color = PageTemplates.MARGIN_RED
        line.strokeWidth = 1.2f
        canvas.drawLine(PageTemplates.MARGIN_X, PageTemplates.WRITING_TOP, PageTemplates.MARGIN_X, PageTemplates.GUIDE_LAST + 10f, line)

        if (spec.pageNumber != 1) return
        val addendum = spec.addendum
        if (addendum != null) {
            text.typeface = bold
            text.textSize = 15f
            text.color = PageTemplates.ALERT_RED
            val banner = "Addendum to ${addendum.parentTitle} v${addendum.previousVersion} · Reason: ${addendum.reason}"
            canvas.drawText(fit(banner, 880f, 15f), PageTemplates.LEFT + 8f, 286f, text)
            return
        }
        text.typeface = bold
        text.color = PageTemplates.SECTION_GREY
        when (spec.docType) {
            DocType.PRESCRIPTION -> {
                text.textSize = 46f
                text.color = PageTemplates.BRAND
                canvas.drawText("℞", 56f, 318f, text)
            }
            DocType.VISIT_SUMMARY -> sectionLabels(canvas, listOf("C/O" to 292f, "O/E" to 532f, "Dx" to 812f, "Plan" to 1012f))
            DocType.INVESTIGATIONS -> sectionLabels(canvas, listOf("Tests advised" to 292f, "Clinical notes for lab" to 892f))
            DocType.ADVICE_REFERRAL -> sectionLabels(canvas, listOf("Advice" to 292f, "Referral" to 892f))
            DocType.OTHER -> Unit
        }
    }

    private fun sectionLabels(canvas: Canvas, labels: List<Pair<String, Float>>) {
        text.typeface = bold
        text.textSize = 13f
        text.color = PageTemplates.SECTION_GREY
        labels.forEach { (label, y) -> canvas.drawText(label, PageTemplates.LEFT + 4f, y, text) }
    }

    private fun drawFooter(canvas: Canvas, spec: PageSpec) {
        line.pathEffect = null
        line.color = PageTemplates.GUIDE_BLUE
        line.strokeWidth = 1.5f
        canvas.drawLine(PageTemplates.LEFT, PageTemplates.FOOTER_RULE, PageTemplates.RIGHT, PageTemplates.FOOTER_RULE, line)
        text.typeface = regular
        text.textSize = 13f
        text.color = PageTemplates.LABEL_GREY
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(fit("Samford Hospital, Ranchi · ${spec.title} · ${spec.docRef}", 700f, 13f), PageTemplates.LEFT, PageTemplates.FOOTER_LINE1, text)
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold
        canvas.drawText("Page ${spec.pageNumber} of ${spec.pageCount}", PageTemplates.RIGHT, PageTemplates.FOOTER_LINE1, text)
        text.typeface = regular
        text.textSize = 11f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(
            "Handwritten on Samford Doctor · ${InkFormat.FORMAT} · template ${spec.templateVersion} · the hospital record is authoritative",
            PageTemplates.LEFT, PageTemplates.FOOTER_LINE2, text,
        )
    }

    fun drawStamp(canvas: Canvas, stamp: InkStamp) {
        val x = stamp.x / 100f; val y = stamp.y / 100f
        val w = stamp.w / 100f; val h = stamp.h / 100f
        val size = stamp.size / 100f
        val color = StrokePainter.parseColor(stamp.color)
        when (stamp.kind) {
            InkStamp.KIND_CHIP -> {
                fill.color = 0xFFEAF1FB.toInt()
                canvas.drawRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, fill)
                line.pathEffect = null
                line.color = color
                line.strokeWidth = 1.4f
                canvas.drawRoundRect(RectF(x, y, x + w, y + h), h / 2f, h / 2f, line)
                text.typeface = bold
                text.textSize = size
                text.color = color
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(stamp.text, x + w / 2f, y + h / 2f + size * 0.35f, text)
                text.textAlign = Paint.Align.LEFT
            }
            InkStamp.KIND_TEXT -> {
                text.typeface = bold
                text.textSize = size
                text.color = color
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(stamp.text, x, y + h * 0.78f, text)
            }
            InkStamp.KIND_LABEL -> {
                text.typeface = Typeface.create(regular, Typeface.ITALIC)
                text.textSize = size
                text.color = color
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(stamp.text, x, y + h * 0.78f, text)
                text.typeface = regular
            }
            InkStamp.KIND_DIAGRAM -> {
                line.pathEffect = null
                line.color = color
                line.strokeWidth = 1.6f
                line.strokeJoin = Paint.Join.ROUND
                line.strokeCap = Paint.Cap.ROUND
                val asset = stamp.asset ?: return
                for (poly in Diagrams.polylines(asset)) {
                    val path = Path()
                    for (i in 0 until poly.size / 2) {
                        val px = x + poly[2 * i] / 1000f * w
                        val py = y + poly[2 * i + 1] / 1000f * h
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    canvas.drawPath(path, line)
                }
                line.strokeCap = Paint.Cap.BUTT
                if (stamp.text.isNotBlank()) {
                    text.typeface = regular
                    text.textSize = 12f
                    text.color = PageTemplates.LABEL_GREY
                    canvas.drawText(stamp.text, x, y + h + 14f, text)
                }
            }
        }
    }

    fun drawSignature(canvas: Canvas, sig: SignatureBlock) {
        val boxW = PageTemplates.SIG_RIGHT - PageTemplates.SIG_LEFT
        val scale = boxW / (InkFormat.SIGNATURE_W_CU / 100f)
        canvas.save()
        canvas.translate(PageTemplates.SIG_LEFT, PageTemplates.SIG_TOP)
        canvas.scale(scale, scale)
        sig.strokes.forEach { StrokePainter.draw(canvas, it, strokePaint) }
        canvas.restore()
        val sigHeight = InkFormat.SIGNATURE_H_CU / 100f * scale
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 12f
        text.color = PageTemplates.INK_DARK
        val textTop = PageTemplates.SIG_TOP + sigHeight + 12f
        canvas.drawText(fit("${sig.doctorName} · Reg. No. ${sig.registrationNo}", boxW, 12f), PageTemplates.SIG_LEFT, textTop, text)
        text.typeface = regular
        text.textSize = 11f
        text.color = PageTemplates.LABEL_GREY
        canvas.drawText("Signed ${sig.signedAtDisplay}", PageTemplates.SIG_LEFT, textTop + 14f, text)
    }

    private fun drawSignaturePlaceholder(canvas: Canvas) {
        line.color = PageTemplates.SECTION_GREY
        line.strokeWidth = 1.2f
        line.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        canvas.drawRoundRect(
            RectF(PageTemplates.SIG_LEFT, PageTemplates.SIG_TOP, PageTemplates.SIG_RIGHT, PageTemplates.SIG_BOTTOM), 6f, 6f, line,
        )
        line.pathEffect = null
        text.typeface = regular
        text.textSize = 13f
        text.color = PageTemplates.SECTION_GREY
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "Your signature is applied on completion",
            (PageTemplates.SIG_LEFT + PageTemplates.SIG_RIGHT) / 2f, (PageTemplates.SIG_TOP + PageTemplates.SIG_BOTTOM) / 2f, text,
        )
        text.textAlign = Paint.Align.LEFT
    }

    companion object {
        const val SAMPLE_BANNER = "SYNTHETIC SAMPLE - not for clinical use"

        /** Measures a stamp so its stored box matches what [drawStamp] paints. */
        fun measureStamp(kind: String, textValue: String, sizeCu: Int): Pair<Int, Int> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = sizeCu / 100f
                textLocale = Locale.ENGLISH
            }
            val textW = paint.measureText(textValue)
            val size = sizeCu / 100f
            return when (kind) {
                InkStamp.KIND_CHIP -> ((textW + size * 1.4f) * 100).toInt() to (size * 1.7f * 100).toInt()
                else -> ((textW + 2f) * 100).toInt() to (size * 1.3f * 100).toInt()
            }
        }

        fun diagramSize(asset: String): Pair<Int, Int> {
            val w = com.samford.doctor.ink.StampCatalog.DIAGRAM_WIDTH_CU
            return w to (w * Diagrams.aspect(asset)).toInt()
        }
    }
}
