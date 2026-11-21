package com.samford.doctor.ink

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * samford-ink/1: the vector record of what the doctor wrote. See docs/ink-format.md.
 *
 * Coordinates are integers in centi-units (cU). A page is 1000 x 1414 units (A4 ratio), so it is
 * 100000 x 141400 cU. Integers keep the JSON byte-stable across devices and JVMs.
 */
object InkFormat {
    const val FORMAT = "samford-ink/1"
    const val RENDERER = "samford-render/1"
    const val CONTENT_TYPE = "application/vnd.samford.ink+json"

    const val PAGE_WIDTH_U = 1000
    const val PAGE_HEIGHT_U = 1414
    const val CU_PER_U = 100
    const val PAGE_WIDTH_CU = PAGE_WIDTH_U * CU_PER_U
    const val PAGE_HEIGHT_CU = PAGE_HEIGHT_U * CU_PER_U

    /** Canonical render: 1.5 px per unit -> 1500 x 2121 px PNG (about 180 dpi on A4). */
    const val RENDER_SCALE = 1.5f
    const val RENDER_WIDTH_PX = 1500
    const val RENDER_HEIGHT_PX = 2121

    /** Point stride inside [InkStroke.pts]: x, y, t, pressure, tilt, orientation. */
    const val STRIDE = 6
    const val NO_VALUE = -1

    /** Signature box geometry in cU (600 x 200 units). */
    const val SIGNATURE_W_CU = 60000
    const val SIGNATURE_H_CU = 20000

    /** Canonical encoder: declaration-order keys, no whitespace, nulls omitted, defaults kept. */
    val canonicalJson: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    /** Tolerant reader for files written by this or later minor revisions. */
    val readerJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(file: InkFile): ByteArray = canonicalJson.encodeToString(InkFile.serializer(), file).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): InkFile {
        val file = readerJson.decodeFromString(InkFile.serializer(), bytes.toString(Charsets.UTF_8))
        require(file.format == FORMAT) { "Unsupported ink format" }
        return file
    }
}

@Serializable
data class InkFile(
    val format: String = InkFormat.FORMAT,
    val renderer: String = InkFormat.RENDERER,
    val document: InkDocumentMeta,
    val page: PageGeometry = PageGeometry(),
    val header: PageHeader,
    val pages: List<InkPage>,
    val signature: SignatureBlock? = null,
    val addendum: AddendumMeta? = null,
)

@Serializable
data class InkDocumentMeta(
    val clientDocId: String,
    val type: String,
    val title: String,
    val templateId: String,
    val templateVersion: Int,
    val visitRef: String,
)

@Serializable
data class PageGeometry(
    val widthU: Int = InkFormat.PAGE_WIDTH_U,
    val heightU: Int = InkFormat.PAGE_HEIGHT_U,
    val unitsPerU: Int = InkFormat.CU_PER_U,
    val renderWidthPx: Int = InkFormat.RENDER_WIDTH_PX,
    val renderHeightPx: Int = InkFormat.RENDER_HEIGHT_PX,
)

/** Everything printed in the automatic page header. The doctor never writes these by hand. */
@Serializable
data class PageHeader(
    val patientName: String,
    val ageSex: String,
    val uhid: String,
    val date: String,
    val token: String,
    val visitRef: String,
    val doctorName: String,
    val qualification: String,
    val registrationNo: String,
    val department: String,
    val allergies: String,
    val hasAllergies: Boolean,
    val vitals: String,
    val synthetic: Boolean,
)

@Serializable
data class InkPage(
    val index: Int,
    val strokes: List<InkStroke> = emptyList(),
    val stamps: List<InkStamp> = emptyList(),
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && stamps.isEmpty()
}

/**
 * One pen or highlighter stroke. [pts] holds [InkFormat.STRIDE] integers per point:
 * x cU, y cU, t ms since stroke start, pressure 0..1000 (or -1), tilt mrad (or -1), orientation mrad (or -1).
 */
@Serializable
class InkStroke(
    val id: String,
    val tool: String,
    val color: String,
    val w: Int,
    val pts: IntArray,
    val origin: String? = null,
) {
    val pointCount: Int get() = pts.size / InkFormat.STRIDE

    fun x(i: Int): Int = pts[i * InkFormat.STRIDE]
    fun y(i: Int): Int = pts[i * InkFormat.STRIDE + 1]
    fun t(i: Int): Int = pts[i * InkFormat.STRIDE + 2]
    fun pressure(i: Int): Int = pts[i * InkFormat.STRIDE + 3]

    val isHighlighter: Boolean get() = tool == TOOL_HIGHLIGHTER

    fun copy(
        id: String = this.id,
        tool: String = this.tool,
        color: String = this.color,
        w: Int = this.w,
        pts: IntArray = this.pts,
        origin: String? = this.origin,
    ) = InkStroke(id, tool, color, w, pts, origin)

    override fun equals(other: Any?): Boolean =
        other is InkStroke && id == other.id && tool == other.tool && color == other.color &&
            w == other.w && origin == other.origin && pts.contentEquals(other.pts)

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + tool.hashCode()
        h = 31 * h + color.hashCode()
        h = 31 * h + w
        h = 31 * h + (origin?.hashCode() ?: 0)
        h = 31 * h + pts.contentHashCode()
        return h
    }

    override fun toString(): String = "InkStroke($id, $tool, $color, w=$w, points=$pointCount)"

    companion object {
        const val TOOL_PEN = "pen"
        const val TOOL_HIGHLIGHTER = "highlighter"
    }
}

/**
 * Tap-placed vector items: dosage/advice chips, favourite medicine or test names, diagrams and
 * labels. [w] and [h] are measured once at placement and stored so hit-testing and rendering
 * agree without re-measuring fonts. Structured [hint]s help pharmacy and labs but never replace
 * the handwriting.
 */
@Serializable
data class InkStamp(
    val id: String,
    val kind: String,
    val text: String = "",
    val asset: String? = null,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val size: Int,
    val color: String,
    val hint: StampHint? = null,
    val origin: String? = null,
) {
    companion object {
        const val KIND_CHIP = "chip"
        const val KIND_TEXT = "text"
        const val KIND_DIAGRAM = "diagram"
        const val KIND_LABEL = "label"
    }
}

@Serializable
data class StampHint(val category: String, val code: String, val label: String)

/** Signature captured once at setup; applied to the last page of each document at completion. */
@Serializable
data class SignatureBlock(
    val strokes: List<InkStroke>,
    val signedAt: String,
    val signedAtDisplay: String,
    val doctorName: String,
    val registrationNo: String,
)

@Serializable
data class AddendumMeta(
    val previousDocumentId: String,
    val previousVersion: Int,
    val previousInkSha256: String?,
    val reason: String,
    val parentTitle: String,
)
