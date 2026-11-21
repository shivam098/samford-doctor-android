package com.samford.doctor.data.repo

import com.samford.doctor.core.Clock
import com.samford.doctor.core.IdGenerator
import com.samford.doctor.data.InkJournal
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.DocumentEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.editor.StampFactory
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.StampCatalog
import com.samford.doctor.ink.SyntheticInk
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.Patient
import com.samford.doctor.model.Vitals
import com.samford.doctor.sync.Freezer

/**
 * Offline demo mode: creates labelled synthetic history (earlier visits with procedurally drawn
 * pages) for the returning demo patients, through the same freeze pipeline as real visits, so the
 * split view and "Repeat last prescription" can be tried without any server.
 */
class DemoSeeder(
    private val db: SamfordDb,
    @Suppress("unused") private val journal: InkJournal,
    private val freezer: Freezer,
    private val settings: SettingsStore,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private val json = InkFormat.canonicalJson

    suspend fun ensureSeedHistory(doctor: Doctor) {
        val signature = settings.current().signature.ifEmpty { SyntheticInk.signature() }
        DemoData.seedPatients.forEachIndexed { n, seed ->
            val days = seed.historyDaysAgo ?: return@forEachIndexed
            val visitId = "seed-${seed.patient.patientId}"
            if (db.visitDao().visit(visitId) != null) return@forEachIndexed
            val started = clock.nowMs() - days * 24L * 3600 * 1000
            val visit = VisitEntity(
                visitId = visitId,
                serverVisitId = null,
                queueEntryId = null,
                serverQueueEntry = false,
                patientId = seed.patient.patientId,
                patientJson = json.encodeToString(Patient.serializer(), seed.patient),
                doctorJson = json.encodeToString(Doctor.serializer(), doctor),
                vitalsJson = seed.vitals?.let { json.encodeToString(Vitals.serializer(), it) },
                token = "S${n + 1}",
                returning = false,
                isDemo = true,
                isSeedHistory = true,
                status = VisitStatus.COMPLETED,
                startedAtMs = started,
                completedAtMs = started + 12 * 60_000,
            )
            val summary = DocumentEntity(ids.uuid(), visitId, DocType.VISIT_SUMMARY.name, DocType.VISIT_SUMMARY.pageTitle, 0, createdAtMs = started)
            val rx = DocumentEntity(ids.uuid(), visitId, DocType.PRESCRIPTION.name, DocType.PRESCRIPTION.pageTitle, 1, createdAtMs = started + 1)
            freezer.freezeSeedVisit(
                visit,
                listOf(
                    summary to listOf(SyntheticInk.page(seed = 100L + n, index = 0, lines = 9)),
                    rx to listOf(prescriptionPage(200L + n)),
                ),
                signature,
            )
        }
    }

    private fun prescriptionPage(seed: Long): InkPage {
        val meds = listOf(StampCatalog.medicines[0], StampCatalog.medicines[1], StampCatalog.medicines[3])
        val chips = listOf(
            listOf(StampCatalog.dosage[0], StampCatalog.food[0], StampCatalog.duration[1]),
            listOf(StampCatalog.dosage[1], StampCatalog.food[1], StampCatalog.duration[2]),
            listOf(StampCatalog.dosage[2], StampCatalog.food[0], StampCatalog.duration[1]),
        )
        val stamps = meds.flatMapIndexed { i, med ->
            val y = 38000 + i * 8000
            val name = StampFactory.create(med, 12000, y, "seed-$seed-m$i")
            var x = name.x + name.w + 2500
            listOf(name) + chips[i].mapIndexed { j, chip ->
                StampFactory.create(chip, x, y, "seed-$seed-c$i-$j").also { x = it.x + it.w + 1500 }
            }
        }
        val scribbles = SyntheticInk.page(seed, 0, lines = 2).strokes.map { s ->
            // Move the scribbled advice below the medicines.
            com.samford.doctor.ink.InkGeometry.transform(s, com.samford.doctor.ink.Affine.translate(0f, 28000f))
        }
        return InkPage(index = 0, strokes = scribbles, stamps = stamps)
    }
}
