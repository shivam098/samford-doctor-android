package com.samford.doctor.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkStroke
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Canonical stroke renderer (samford-render/1). It is fully specified in docs/ink-format.md so a
 * stored ink file can be re-rendered without depending on any ink library's internals:
 *  1. width_i = w * f(pressure_i), f = clamp(0.55 + 0.9 * p, 0.55, 1.45); f = 1 without pressure
 *     and for the highlighter; widths are then smoothed with an EMA (alpha 0.35).
 *  2. points closer than 0.05 U to the previous kept point are dropped (the last point is kept).
 *  3. the centre line is subdivided with uniform Catmull-Rom splines (1..6 steps per segment).
 *  4. pen: consecutive sub-segments with the same width bucket (0.05 U) are stroked as one path
 *     with round caps/joins; highlighter: one path, constant width, alpha 0x50.
 * The canvas must already map 1 page unit (U) to device pixels.
 */
object StrokePainter {
    private const val MIN_FACTOR = 0.55f
    private const val PRESSURE_GAIN = 0.9f
    private const val MAX_FACTOR = 1.45f
    private const val EMA_ALPHA = 0.35f
    private const val DEDUPE_U = 0.05f
    private const val BUCKET_U = 0.05f
    private const val MIN_WIDTH_U = 0.3f
    const val HIGHLIGHTER_ALPHA = 0x50

    fun parseColor(hex: String): Int {
        val clean = hex.removePrefix("#")
        val rgb = clean.toLongOrNull(16)?.toInt() ?: 0x1B1B1F
        return if (clean.length == 8) rgb else (0xFF shl 24) or (rgb and 0xFFFFFF)
    }

    private fun widthFactor(pressure: Int): Float =
        if (pressure < 0) 1f else (MIN_FACTOR + PRESSURE_GAIN * (pressure / 1000f)).coerceIn(MIN_FACTOR, MAX_FACTOR)

    fun draw(canvas: Canvas, stroke: InkStroke, paint: Paint = Paint()) {
        val n = stroke.pointCount
        if (n == 0) return
        val highlighter = stroke.isHighlighter
        val baseW = stroke.w / InkFormat.CU_PER_U.toFloat()
        val color = parseColor(stroke.color)

        // 1-2: widths with EMA smoothing, then de-duplicate.
        val kx = FloatArray(n); val ky = FloatArray(n); val kw = FloatArray(n)
        var m = 0
        var prevW = 0f
        for (i in 0 until n) {
            val x = stroke.x(i) / InkFormat.CU_PER_U.toFloat()
            val y = stroke.y(i) / InkFormat.CU_PER_U.toFloat()
            val raw = if (highlighter) baseW else baseW * widthFactor(stroke.pressure(i))
            val w = if (i == 0) raw else prevW + EMA_ALPHA * (raw - prevW)
            prevW = w
            val isLast = i == n - 1
            if (m > 0 && !isLast && hypot(x - kx[m - 1], y - ky[m - 1]) < DEDUPE_U) continue
            if (m > 0 && isLast && hypot(x - kx[m - 1], y - ky[m - 1]) < DEDUPE_U) {
                kx[m - 1] = x; ky[m - 1] = y; kw[m - 1] = w
                continue
            }
            kx[m] = x; ky[m] = y; kw[m] = w
            m++
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.isDither = false
        paint.color = color
        if (highlighter) paint.alpha = HIGHLIGHTER_ALPHA

        if (m == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(kx[0], ky[0], max(kw[0], MIN_WIDTH_U) / 2f, paint)
            return
        }

        // 3: Catmull-Rom subdivision.
        val sx = ArrayList<Float>(m * 3); val sy = ArrayList<Float>(m * 3); val sw = ArrayList<Float>(m * 3)
        for (i in 0 until m - 1) {
            val i0 = max(i - 1, 0); val i3 = min(i + 2, m - 1)
            val d = hypot(kx[i + 1] - kx[i], ky[i + 1] - ky[i])
            val steps = ceil(d / 1.5f).toInt().coerceIn(1, 6)
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                sx += catmull(kx[i0], kx[i], kx[i + 1], kx[i3], t)
                sy += catmull(ky[i0], ky[i], ky[i + 1], ky[i3], t)
                sw += kw[i] + (kw[i + 1] - kw[i]) * t
            }
        }
        sx += kx[m - 1]; sy += ky[m - 1]; sw += kw[m - 1]

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        if (highlighter) {
            val path = Path()
            path.moveTo(sx[0], sy[0])
            for (j in 1 until sx.size) path.lineTo(sx[j], sy[j])
            paint.strokeWidth = max(baseW, MIN_WIDTH_U)
            canvas.drawPath(path, paint)
            return
        }

        // 4: group sub-segments by width bucket.
        var path = Path()
        path.moveTo(sx[0], sy[0])
        var bucket = bucketOf((sw[0] + sw[1]) / 2f)
        var hasSegments = false
        for (j in 1 until sx.size) {
            val b = bucketOf((sw[j - 1] + sw[j]) / 2f)
            if (b != bucket && hasSegments) {
                paint.strokeWidth = max(bucket * BUCKET_U, MIN_WIDTH_U)
                canvas.drawPath(path, paint)
                path = Path()
                path.moveTo(sx[j - 1], sy[j - 1])
                hasSegments = false
            }
            bucket = b
            path.lineTo(sx[j], sy[j])
            hasSegments = true
        }
        if (hasSegments) {
            paint.strokeWidth = max(bucket * BUCKET_U, MIN_WIDTH_U)
            canvas.drawPath(path, paint)
        }
    }

    private fun bucketOf(width: Float): Int = (width / BUCKET_U).roundToInt()

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }
}
