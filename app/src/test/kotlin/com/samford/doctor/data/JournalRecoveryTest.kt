package com.samford.doctor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.ink.PageDelta
import com.samford.doctor.testing.FixedClock
import com.samford.doctor.testing.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Autosave: every edit survives the app process dying (simulated by closing and reopening the database file). */
@RunWith(AndroidJUnit4::class)
class JournalRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun openDb(name: String) = Room.databaseBuilder(context, SamfordDb::class.java, name).allowMainThreadQueries().build()

    @Test
    fun strokesErasesAndCompactionSurviveARestart() = runBlocking {
        val name = "journal-${System.nanoTime()}.db"
        val db1 = openDb(name)
        val journal1 = InkJournal(db1, db1.inkDao(), FixedClock())
        journal1.createPage("p1", "d1", 0)
        val a = Fixtures.stroke("a", y = 40000)
        val b = Fixtures.stroke("b", y = 50000)
        val c = Fixtures.stroke("c", y = 60000)
        journal1.append("p1", PageDelta.addStroke(a))
        journal1.append("p1", PageDelta.addStroke(b))
        journal1.append("p1", PageDelta(removedStrokes = listOf(a)))
        val expected = journal1.load("p1")!!
        db1.close() // the process is killed here

        val db2 = openDb(name)
        val journal2 = InkJournal(db2, db2.inkDao(), FixedClock())
        val recovered = journal2.load("p1")!!
        assertEquals(expected, recovered)
        assertEquals(listOf("b"), recovered.strokes.map { it.id })

        journal2.compact("p1")
        assertEquals(0, db2.inkDao().opCount("p1"))
        journal2.append("p1", PageDelta.addStroke(c))
        db2.close()

        val db3 = openDb(name)
        assertEquals(listOf("b", "c"), InkJournal(db3, db3.inkDao(), FixedClock()).load("p1")!!.strokes.map { it.id })
        db3.close()
    }

    @Test
    fun writerKeepsOrderAndFlushWaitsForPendingWrites() = runBlocking {
        val db = SamfordDb.inMemory(context)
        val journal = InkJournal(db, db.inkDao(), FixedClock())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val writer = JournalWriter(scope)
        writer.submit { journal.createPage("p", "d", 0) }
        (1..50).forEach { i -> writer.submit { journal.append("p", PageDelta.addStroke(Fixtures.stroke("s$i"))) } }
        writer.submit { journal.append("p", PageDelta(removedStrokes = listOf(Fixtures.stroke("s1")))) }
        writer.flush()
        val page = journal.load("p")!!
        assertEquals(49, page.strokes.size)
        assertEquals("s2", page.strokes.first().id)
        scope.cancel()
        db.close()
    }
}
