package com.samford.doctor.ink

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally generated "writing" for demos, tests and backend seed data. It is deliberately
 * abstract (looping cursive-like scribbles laid out on the ruled lines) so that no real
 * handwriting or clinical content is ever shipped.
 */
object SyntheticInk {

    fun scribbleLine(random: Random, idPrefix: String, startX: Int, baselineY: Int, widthCu: Int, color: String): List<InkStroke> {
        val strokes = ArrayList<InkStroke>()
        var x = startX
        var word = 0
        while (x < startX + widthCu - 4000) {
            val letters = 3 + random.nextInt(6)
            val pts = ArrayList<Int>()
            var t = 0
            val stepX = 600 + random.nextInt(250)
            for (l in 0 until letters * 10) {
                val phase = l / 10.0 * 2 * PI
                val height = 1300 + random.nextInt(500)
                val px = x + (l * stepX / 10.0).roundToInt() + (cos(phase) * 260).roundToInt()
                val py = baselineY - ((sin(phase) + 1) / 2 * height).roundToInt()
                val pressure = 420 + (sin(phase * 0.5) * 120).roundToInt() + random.nextInt(40)
                pts += listOf(px, py, t, pressure, 700, 1200)
                t += 8
            }
            strokes += InkStroke("$idPrefix-w$word", InkStroke.TOOL_PEN, color, 230, pts.toIntArray())
            x += letters * stepX + 1500 + random.nextInt(1200)
            word++
        }
        return strokes
    }

    /** A page of [lines] scribbled lines starting below the header area. */
    fun page(seed: Long, index: Int, lines: Int, color: String = "#1A3C8C"): InkPage {
        val random = Random(seed)
        val strokes = (0 until lines).flatMap { line ->
            scribbleLine(random, "syn$seed-p$index-l$line", 11000, 33500 + line * 4000, 78000 - random.nextInt(20000), color)
        }
        return InkPage(index = index, strokes = strokes)
    }

    /** A synthetic signature-like flourish in signature-box coordinates. */
    fun signature(seed: Long = 7L): List<InkStroke> {
        val random = Random(seed)
        val pts = ArrayList<Int>()
        var t = 0
        for (i in 0..120) {
            val f = i / 120.0
            val x = (4000 + f * 50000).roundToInt()
            val y = (11000 - sin(f * 9 * PI) * 5000 * (1 - f * 0.6) + random.nextInt(200)).roundToInt()
            pts += listOf(x, y, t, 500 + (sin(f * PI) * 200).roundToInt(), InkFormat.NO_VALUE, InkFormat.NO_VALUE)
            t += 6
        }
        val underline = intArrayOf(6000, 16000, 0, 450, -1, -1, 30000, 16500, 120, 520, -1, -1, 52000, 15500, 240, 480, -1, -1)
        return listOf(
            InkStroke("sig-1", InkStroke.TOOL_PEN, "#1B1B1F", 260, pts.toIntArray()),
            InkStroke("sig-2", InkStroke.TOOL_PEN, "#1B1B1F", 200, underline),
        )
    }
}
