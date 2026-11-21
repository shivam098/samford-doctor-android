package com.samford.doctor.testing

import android.content.Context
import com.samford.doctor.AppContainer
import com.samford.doctor.SamfordDoctorApplication
import com.samford.doctor.core.Clock
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.repo.DemoData
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.ink.PageDelta
import com.samford.doctor.ink.SyntheticInk
import com.samford.doctor.model.EntrySource
import com.samford.doctor.model.Patient
import com.samford.doctor.model.QueueEntry
import com.samford.doctor.model.QueueStatus
import java.time.Instant
import java.util.UUID

/** Robolectric application: fake hospital, no network, no WorkManager scheduling. */
class TestApplication : SamfordDoctorApplication() {
    val hospital = FakeHospital()

    override fun createContainer(): AppContainer = AppContainer(
        context = this,
        db = SamfordDb.inMemory(this),
        apiOverride = hospital,
        discoveryUrl = "",
        kickSync = {},
        settingsName = "test-" + UUID.randomUUID(),
    )

    override fun scheduleBackgroundWork() = Unit
}

class FixedClock(var instant: Instant = Instant.parse("2026-11-21T04:30:00Z")) : Clock {
    override fun now(): Instant = instant
    fun advanceMs(ms: Long) {
        instant = instant.plusMillis(ms)
    }
}

object Fixtures {
    fun container(context: Context, hospital: FakeHospital = FakeHospital(), clock: Clock = FixedClock()): AppContainer = AppContainer(
        context = context,
        db = SamfordDb.inMemory(context),
        clock = clock,
        apiOverride = hospital,
        discoveryUrl = "",
        kickSync = {},
        settingsName = "test-" + UUID.randomUUID(),
    )

    fun serverEntry(id: String = "visit-a", name: String = "Test Patient A (Synthetic)") = QueueEntry(
        queueEntryId = id,
        serverVisitId = id,
        token = "7",
        status = QueueStatus.WAITING,
        checkedInAtEpochMs = null,
        returning = true,
        lastVisitAt = null,
        patient = Patient("patient-$id", "UH-$id", name, 41, "Female", listOf("Penicillin"), true),
        vitals = null,
        source = EntrySource.SERVER,
    )

    fun demoEntry() = DemoData.seedPatients[2].let {
        QueueEntry(it.queueEntryId, null, it.token, QueueStatus.WAITING, null, false, null, it.patient, it.vitals, EntrySource.LOCAL_DEMO)
    }

    fun stroke(id: String, y: Int = 40000, x0: Int = 12000, points: Int = 30): InkStroke {
        val pts = IntArray(points * InkFormat.STRIDE)
        for (i in 0 until points) {
            val o = i * InkFormat.STRIDE
            pts[o] = x0 + i * 400
            pts[o + 1] = y + ((i % 6) - 3) * 300
            pts[o + 2] = i * 8
            pts[o + 3] = 400 + (i % 5) * 40
            pts[o + 4] = 600
            pts[o + 5] = 3000
        }
        return InkStroke(id, InkStroke.TOOL_PEN, "#1A3C8C", 230, pts)
    }

    fun page(seed: Long = 11L): InkPage = SyntheticInk.page(seed, 0, lines = 4)

    /** Signs in (via settings), opens a visit and writes on the prescription and investigations. */
    suspend fun writeVisit(c: AppContainer, entry: QueueEntry = serverEntry(), labs: List<String> = listOf("central-lab")): String {
        val doctor = com.samford.doctor.model.Doctor("doctor-a", "Dr. Test A", "MBBS, MD", "TEST-001", "General medicine")
        c.settings.setSession(doctor, "token-doctor-a", offlineDemo = false)
        c.settings.setSignature(SyntheticInk.signature())
        c.settings.setManualUrl("http://fake.local")
        c.restore()
        c.server.refresh()
        val opened = c.visits.openVisit(entry)
        val visitId = (opened as com.samford.doctor.data.repo.OpenResult.Opened).visitId
        val docs = c.db.visitDao().documentsForVisit(visitId)
        for (doc in docs) {
            val pageId = c.journal.pagesForDoc(doc.docId).first().first
            when (doc.type) {
                "PRESCRIPTION" -> SyntheticInk.page(5L, 0, 3).strokes.forEach { c.journal.append(pageId, PageDelta.addStroke(it)) }
                "INVESTIGATIONS" -> {
                    c.journal.append(pageId, PageDelta.addStroke(stroke("ix-1")))
                    c.visits.setLabs(doc.docId, labs)
                }
                "VISIT_SUMMARY" -> c.journal.append(pageId, PageDelta.addStroke(stroke("vs-1")))
            }
        }
        return visitId
    }

    suspend fun completeVisit(c: AppContainer, visitId: String) {
        val problem = c.freezer.lockForCompletion(visitId)
        check(problem == null) { problem!! }
        c.freezer.freezeVisit(visitId)
    }
}
