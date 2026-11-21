package com.samford.doctor.data

import com.samford.doctor.data.repo.HintBuilder
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.StampCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HintBuilderTest {
    private fun stamp(key: String, x: Int, y: Int): InkStamp {
        val t = StampCatalog.byKey(key)!!
        return InkStamp("id-$key-$y", t.kind, t.text, x = x, y = y, w = 4000, h = 2400, size = 1800, color = "#1A3C8C", hint = t.hint)
    }

    @Test
    fun chipsOnTheSameLineAttachToTheMedicine() {
        val page = InkPage(
            0,
            stamps = listOf(
                stamp("paracetamol-650", 12000, 38000), stamp("1-0-1", 40000, 38200), stamp("after-food", 50000, 38100), stamp("5d", 62000, 37900),
                stamp("cetirizine-10", 12000, 46000), stamp("0-0-1", 40000, 46000),
                stamp("cbc", 12000, 90000), stamp("review-1w", 12000, 100000),
            ),
        )
        val hints = HintBuilder.build(listOf(page))!!
        assertEquals(2, hints.medicines.size)
        val para = hints.medicines.first()
        assertEquals("Tab Paracetamol 650 mg", para.name)
        assertEquals("1-0-1", para.frequency)
        assertEquals(5, para.durationDays)
        assertEquals("After food", para.notes)
        assertEquals("0-0-1", hints.medicines[1].frequency)
        assertEquals("CBC", hints.tests.single().name)
        assertEquals("Review after 1 week", hints.notes)
    }

    @Test
    fun noStampsMeansNoHints() {
        assertNull(HintBuilder.build(listOf(InkPage(0))))
        assertEquals(30, HintBuilder.durationDays("× 1 month"))
    }
}
