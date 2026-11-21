package com.samford.doctor.editor

import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.StampCatalog
import com.samford.doctor.ink.StampCategory
import com.samford.doctor.ink.StampTemplate
import com.samford.doctor.render.PageCompositor

/** Creates page stamps with their measured box, clamped inside the page. */
object StampFactory {
    const val STAMP_BLUE = "#1A3C8C"
    const val DIAGRAM_GREY = "#5F6B7A"
    const val LABEL_GREY = "#5F6B7A"

    fun create(template: StampTemplate, xCu: Int, yCu: Int, id: String, color: String = STAMP_BLUE): InkStamp {
        val (w, h, size) = when (template.kind) {
            InkStamp.KIND_DIAGRAM -> {
                val (dw, dh) = PageCompositor.diagramSize(template.asset ?: "")
                Triple(dw, dh, 1200)
            }
            InkStamp.KIND_CHIP -> {
                val (cw, ch) = PageCompositor.measureStamp(InkStamp.KIND_CHIP, template.text, StampCatalog.CHIP_SIZE_CU)
                Triple(cw, ch, StampCatalog.CHIP_SIZE_CU)
            }
            else -> {
                val (tw, th) = PageCompositor.measureStamp(InkStamp.KIND_TEXT, template.text, StampCatalog.TEXT_SIZE_CU)
                Triple(tw, th, StampCatalog.TEXT_SIZE_CU)
            }
        }
        // Text-like stamps sit with their left edge at the pen tip; diagrams are centred on it.
        val x0 = if (template.kind == InkStamp.KIND_DIAGRAM) xCu - w / 2 else xCu
        val y0 = yCu - h / 2
        val x = x0.coerceIn(0, InkFormat.PAGE_WIDTH_CU - w)
        val y = y0.coerceIn(0, InkFormat.PAGE_HEIGHT_CU - h)
        return InkStamp(
            id = id,
            kind = template.kind,
            text = template.text,
            asset = template.asset,
            x = x,
            y = y,
            w = w,
            h = h,
            size = size,
            color = if (template.category == StampCategory.DIAGRAM) DIAGRAM_GREY else color,
            hint = template.hint,
        )
    }

    /** A small italic label, e.g. marking a repeated prescription layer. */
    fun label(text: String, xCu: Int, yCu: Int, id: String, origin: String?): InkStamp {
        val size = 1300
        val (w, h) = PageCompositor.measureStamp(InkStamp.KIND_TEXT, text, size)
        return InkStamp(id = id, kind = InkStamp.KIND_LABEL, text = text, x = xCu, y = yCu, w = w, h = h, size = size, color = LABEL_GREY, origin = origin)
    }
}
