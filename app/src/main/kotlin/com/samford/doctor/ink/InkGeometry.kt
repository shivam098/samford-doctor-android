package com.samford.doctor.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Integer bounding box in cU. */
data class BoxCu(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
    fun inflate(by: Int) = BoxCu(left - by, top - by, right + by, bottom + by)
    fun union(o: BoxCu) = BoxCu(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun intersects(o: BoxCu): Boolean = left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom
}

/** x' = a*x + c*y + e ; y' = b*x + d*y + f (Android Matrix order). */
data class Affine(
    val a: Float = 1f, val b: Float = 0f,
    val c: Float = 0f, val d: Float = 1f,
    val e: Float = 0f, val f: Float = 0f,
) {
    fun mapX(x: Float, y: Float) = a * x + c * y + e
    fun mapY(x: Float, y: Float) = b * x + d * y + f
    val uniformScale: Float get() = sqrt(abs(a * d - b * c))

    companion object {
        val IDENTITY = Affine()
        fun translate(dx: Float, dy: Float) = Affine(e = dx, f = dy)
        fun scaleAbout(s: Float, px: Float, py: Float) = Affine(a = s, d = s, e = px - s * px, f = py - s * py)
    }
}

object InkGeometry {

    fun bounds(stroke: InkStroke): BoxCu {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (i in 0 until stroke.pointCount) {
            val x = stroke.x(i); val y = stroke.y(i)
            if (x < l) l = x; if (x > r) r = x
            if (y < t) t = y; if (y > b) b = y
        }
        val half = stroke.w / 2 + 1
        return if (stroke.pointCount == 0) BoxCu(0, 0, 0, 0) else BoxCu(l - half, t - half, r + half, b + half)
    }

    fun bounds(stamp: InkStamp): BoxCu = BoxCu(stamp.x, stamp.y, stamp.x + stamp.w, stamp.y + stamp.h)

    fun bounds(page: InkPage, strokeIds: Set<String>, stampIds: Set<String>): BoxCu? {
        var box: BoxCu? = null
        page.strokes.filter { it.id in strokeIds }.forEach { box = box?.union(bounds(it)) ?: bounds(it) }
        page.stamps.filter { it.id in stampIds }.forEach { box = box?.union(bounds(it)) ?: bounds(it) }
        return box
    }

    /** Distance from (px, py) to segment (x1,y1)-(x2,y2), all in cU. */
    fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    /** True when an eraser disc of [radius] cU at (x, y) touches the drawn stroke. */
    fun hits(stroke: InkStroke, x: Int, y: Int, radius: Int): Boolean {
        val reach = radius + stroke.w / 2f
        if (!bounds(stroke).inflate(radius).contains(x, y)) return false
        val n = stroke.pointCount
        if (n == 1) return hypot((x - stroke.x(0)).toFloat(), (y - stroke.y(0)).toFloat()) <= reach
        for (i in 0 until n - 1) {
            val d = distanceToSegment(
                x.toFloat(), y.toFloat(),
                stroke.x(i).toFloat(), stroke.y(i).toFloat(), stroke.x(i + 1).toFloat(), stroke.y(i + 1).toFloat(),
            )
            if (d <= reach) return true
        }
        return false
    }

    fun hits(stamp: InkStamp, x: Int, y: Int, radius: Int): Boolean = bounds(stamp).inflate(radius).contains(x, y)

    /**
     * Area eraser: removes the parts of [stroke] within [radius] of (x, y), splitting it into the
     * surviving runs. Returns null when the stroke is untouched, or the replacement pieces (possibly
     * empty) otherwise. Segments crossing the disc are cut at sub-point resolution.
     */
    fun eraseArea(stroke: InkStroke, x: Int, y: Int, radius: Int, newId: () -> String): List<InkStroke>? {
        if (!hits(stroke, x, y, radius)) return null
        val dense = densify(stroke, maxStep = max(radius / 2, 40))
        val n = dense.size / InkFormat.STRIDE
        val reach = radius + stroke.w / 2f
        val pieces = ArrayList<InkStroke>()
        var run = ArrayList<Int>()
        fun flush() {
            if (run.size >= 2 * InkFormat.STRIDE) {
                pieces += InkStroke(newId(), stroke.tool, stroke.color, stroke.w, rebaseTime(run.toIntArray()), stroke.origin)
            }
            run = ArrayList()
        }
        for (i in 0 until n) {
            val o = i * InkFormat.STRIDE
            val inside = hypot((dense[o] - x).toFloat(), (dense[o + 1] - y).toFloat()) <= reach
            if (inside) flush() else for (k in 0 until InkFormat.STRIDE) run += dense[o + k]
        }
        flush()
        return pieces
    }

    /** Inserts interpolated points so no segment is longer than [maxStep] cU. */
    fun densify(stroke: InkStroke, maxStep: Int): IntArray {
        val n = stroke.pointCount
        if (n < 2) return stroke.pts.copyOf()
        val out = ArrayList<Int>(stroke.pts.size * 2)
        for (i in 0 until n - 1) {
            val o = i * InkFormat.STRIDE
            val p = i * InkFormat.STRIDE + InkFormat.STRIDE
            val dist = hypot((stroke.pts[p] - stroke.pts[o]).toFloat(), (stroke.pts[p + 1] - stroke.pts[o + 1]).toFloat())
            val steps = max(1, (dist / maxStep).toInt() + 1)
            for (s in 0 until steps) {
                val f = s.toFloat() / steps
                for (k in 0 until InkFormat.STRIDE) {
                    val a = stroke.pts[o + k]; val b = stroke.pts[p + k]
                    out += if (a == InkFormat.NO_VALUE || b == InkFormat.NO_VALUE) a else (a + (b - a) * f).roundToInt()
                }
            }
        }
        val last = (n - 1) * InkFormat.STRIDE
        for (k in 0 until InkFormat.STRIDE) out += stroke.pts[last + k]
        return out.toIntArray()
    }

    private fun rebaseTime(pts: IntArray): IntArray {
        if (pts.isEmpty()) return pts
        val t0 = pts[2]
        for (i in 0 until pts.size / InkFormat.STRIDE) pts[i * InkFormat.STRIDE + 2] -= t0
        return pts
    }

    /** Even-odd point-in-polygon test; [poly] is x0,y0,x1,y1,... in cU. */
    fun pointInPolygon(px: Int, py: Int, poly: IntArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i].toFloat(); val yi = poly[2 * i + 1].toFloat()
            val xj = poly[2 * j].toFloat(); val yj = poly[2 * j + 1].toFloat()
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Lasso: a stroke is selected when at least 60% of its points lie inside; a stamp when its centre does. */
    fun lassoSelect(page: InkPage, poly: IntArray): Pair<Set<String>, Set<String>> {
        val strokes = page.strokes.filter { s ->
            val n = s.pointCount
            if (n == 0) return@filter false
            var inside = 0
            for (i in 0 until n) if (pointInPolygon(s.x(i), s.y(i), poly)) inside++
            inside * 10 >= n * 6
        }.map { it.id }.toSet()
        val stamps = page.stamps.filter { pointInPolygon(it.x + it.w / 2, it.y + it.h / 2, poly) }.map { it.id }.toSet()
        return strokes to stamps
    }

    fun transform(stroke: InkStroke, m: Affine): InkStroke {
        val pts = stroke.pts.copyOf()
        for (i in 0 until stroke.pointCount) {
            val o = i * InkFormat.STRIDE
            val x = pts[o].toFloat(); val y = pts[o + 1].toFloat()
            pts[o] = m.mapX(x, y).roundToInt()
            pts[o + 1] = m.mapY(x, y).roundToInt()
        }
        val w = max(20, (stroke.w * m.uniformScale).roundToInt())
        return stroke.copy(w = w, pts = pts)
    }

    fun transform(stamp: InkStamp, m: Affine): InkStamp {
        val x0 = m.mapX(stamp.x.toFloat(), stamp.y.toFloat())
        val y0 = m.mapY(stamp.x.toFloat(), stamp.y.toFloat())
        val s = m.uniformScale
        return stamp.copy(
            x = x0.roundToInt(), y = y0.roundToInt(),
            w = max(100, (stamp.w * s).roundToInt()), h = max(100, (stamp.h * s).roundToInt()),
            size = max(100, (stamp.size * s).roundToInt()),
        )
    }

    /** Keeps moved items on the page. */
    fun clampToPage(box: BoxCu, dx: Int, dy: Int): Pair<Int, Int> {
        val cdx = dx.coerceIn(-box.left, InkFormat.PAGE_WIDTH_CU - box.right)
        val cdy = dy.coerceIn(-box.top, InkFormat.PAGE_HEIGHT_CU - box.bottom)
        return cdx to cdy
    }
}
