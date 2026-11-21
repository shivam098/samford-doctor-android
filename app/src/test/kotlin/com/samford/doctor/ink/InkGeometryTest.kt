package com.samford.doctor.ink

import com.samford.doctor.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {
    private val stroke = Fixtures.stroke("s1", y = 40000)

    @Test
    fun strokeEraserHitsOnlyNearTheLine() {
        assertTrue(InkGeometry.hits(stroke, stroke.x(5), stroke.y(5), 200))
        assertFalse(InkGeometry.hits(stroke, stroke.x(5), stroke.y(5) + 5000, 200))
    }

    @Test
    fun areaEraserSplitsAStrokeIntoTwoPieces() {
        var n = 0
        val pieces = InkGeometry.eraseArea(stroke, stroke.x(15), stroke.y(15), 600) { "p${n++}" }
        assertNotNull(pieces)
        assertEquals(2, pieces!!.size)
        pieces.forEach { piece ->
            for (i in 0 until piece.pointCount) {
                val d = kotlin.math.hypot((piece.x(i) - stroke.x(15)).toDouble(), (piece.y(i) - stroke.y(15)).toDouble())
                assertTrue("point $i at distance $d survived the eraser", d > 600 + stroke.w / 2.0)
            }
            assertEquals(0, piece.t(0))
        }
        assertNull(InkGeometry.eraseArea(stroke, 90000, 130000, 500) { "x" })
    }

    @Test
    fun lassoSelectsStrokesMostlyInsideAndStampsByCentre() {
        val inside = Fixtures.stroke("in", y = 40000, x0 = 20000, points = 10)
        val outside = Fixtures.stroke("out", y = 90000, x0 = 20000, points = 10)
        val stamp = InkStamp("t1", InkStamp.KIND_CHIP, "1-0-1", x = 22000, y = 38000, w = 3000, h = 2000, size = 1800, color = "#1A3C8C")
        val page = InkPage(0, listOf(inside, outside), listOf(stamp))
        val poly = intArrayOf(15000, 30000, 40000, 30000, 40000, 50000, 15000, 50000)
        val (strokes, stamps) = InkGeometry.lassoSelect(page, poly)
        assertEquals(setOf("in"), strokes)
        assertEquals(setOf("t1"), stamps)
    }

    @Test
    fun transformMovesPointsAndScalesWidth() {
        val moved = InkGeometry.transform(stroke, Affine.translate(1000f, -500f))
        assertEquals(stroke.x(0) + 1000, moved.x(0))
        assertEquals(stroke.y(3) - 500, moved.y(3))
        val scaled = InkGeometry.transform(stroke, Affine.scaleAbout(2f, stroke.x(0).toFloat(), stroke.y(0).toFloat()))
        assertEquals(stroke.w * 2, scaled.w)
        assertEquals(stroke.x(0), scaled.x(0))
    }

    @Test
    fun deltaInverseRestoresThePageAndComposes() {
        val page = InkPage(0, listOf(stroke))
        val replacement = InkGeometry.transform(stroke, Affine.translate(500f, 0f))
        val move = PageDelta(removedStrokes = listOf(stroke), addedStrokes = listOf(replacement))
        val after = page.apply(move)
        assertEquals(replacement, after.strokes.single())
        assertEquals(page, after.apply(move.inverse()))

        val add = PageDelta.addStroke(Fixtures.stroke("s2"))
        val removeAdded = PageDelta(removedStrokes = listOf(Fixtures.stroke("s2")))
        val combined = add.then(removeAdded)
        assertTrue(combined.isEmpty)
    }
}
