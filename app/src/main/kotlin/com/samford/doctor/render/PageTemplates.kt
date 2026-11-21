package com.samford.doctor.render

import com.samford.doctor.ink.AddendumMeta
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.ink.SignatureBlock
import com.samford.doctor.model.DocType

/** Page layout of the hospital letterhead pad, in page units (U). Version is part of the record. */
object PageTemplates {
    const val VERSION = 1
    fun templateId(type: DocType, addendum: Boolean): String =
        if (addendum) "samford-opd-addendum" else "samford-opd-${type.name.lowercase().replace('_', '-')}"

    const val LEFT = 50f
    const val RIGHT = 950f
    const val TITLE_BASELINE = 66f
    const val SUBTITLE_BASELINE = 94f
    const val LETTERHEAD_RULE = 112f
    const val HEADER_TOP = 122f
    const val HEADER_BOTTOM = 248f
    val HEADER_ROWS = floatArrayOf(150f, 179f, 208f, 237f)
    const val WRITING_TOP = 262f
    const val GUIDE_FIRST = 300f
    const val GUIDE_STEP = 40f
    const val GUIDE_LAST = 1290f
    const val MARGIN_X = 100f
    const val FOOTER_RULE = 1326f
    const val FOOTER_LINE1 = 1352f
    const val FOOTER_LINE2 = 1378f

    // Signature area (bottom right of the writing area).
    const val SIG_LEFT = 640f
    const val SIG_TOP = 1170f
    const val SIG_RIGHT = 950f
    const val SIG_BOTTOM = 1304f

    /** Fraction of the page height below which writing on the last page adds a new page. */
    const val AUTO_PAGE_FRACTION = 0.86f

    const val BRAND = 0xFF1A3C8C.toInt()
    const val INK_DARK = 0xFF1B1B1F.toInt()
    const val LABEL_GREY = 0xFF5F6B7A.toInt()
    const val GUIDE_BLUE = 0xFFD6E2F0.toInt()
    const val MARGIN_RED = 0xFFF0C4C4.toInt()
    const val HEADER_FILL = 0xFFF2F6FC.toInt()
    const val ALERT_RED = 0xFFB3261E.toInt()
    const val SECTION_GREY = 0xFFA3ADB9.toInt()
}

data class PageSpec(
    val docType: DocType,
    val title: String,
    val header: PageHeader,
    val pageNumber: Int,
    val pageCount: Int,
    val docRef: String,
    val signature: SignatureBlock? = null,
    val addendum: AddendumMeta? = null,
    val templateVersion: Int = PageTemplates.VERSION,
)

enum class RenderMode {
    /** Exactly what recipients receive. */
    EXPORT,

    /** On the doctor's screen: also shows the signature placeholder on the last page. */
    SCREEN,
}
