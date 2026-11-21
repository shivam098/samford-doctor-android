package com.samford.doctor.data.repo

import com.samford.doctor.core.Ist
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.remote.HintsDto
import com.samford.doctor.data.remote.MedicineHintDto
import com.samford.doctor.data.remote.TestHintDto
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.InkStamp
import com.samford.doctor.ink.PageHeader
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.Patient
import com.samford.doctor.model.Vitals
import java.time.Instant
import kotlin.math.abs

object HeaderFactory {
    fun visitRef(visit: VisitEntity): String = "V-" + visit.visitId.replace("-", "").takeLast(8).uppercase()

    fun build(visit: VisitEntity, patient: Patient, doctor: Doctor, vitals: Vitals?): PageHeader = PageHeader(
        patientName = patient.name,
        ageSex = patient.ageSex,
        uhid = patient.uhid.ifBlank { "—" },
        date = Ist.date(Instant.ofEpochMilli(visit.startedAtMs)),
        token = visit.token.ifBlank { "—" },
        visitRef = visitRef(visit),
        doctorName = doctor.name,
        qualification = doctor.qualification,
        registrationNo = doctor.registrationNo,
        department = doctor.department.ifBlank { "General Medicine" },
        allergies = patient.allergyText,
        hasAllergies = patient.hasAllergies,
        vitals = vitals?.summary() ?: "Not recorded",
        synthetic = patient.isSynthetic,
    )
}

/**
 * Builds the optional structured hints for pharmacy and labs from the stamps the doctor placed.
 * Dosage, food-timing and duration chips placed on the same line as a medicine stamp (within
 * 30 units vertically, to its right) are attached to that medicine. Hints only help software;
 * the rendered page stays the record and pharmacists dispense from it.
 */
object HintBuilder {
    private const val SAME_LINE_CU = 3000

    fun build(pages: List<InkPage>): HintsDto? {
        val medicines = ArrayList<MedicineHintDto>()
        val tests = ArrayList<TestHintDto>()
        val advice = ArrayList<String>()
        for (page in pages) {
            val stamps = page.stamps.filter { it.hint != null }
            val chips = stamps.filter { it.kind == InkStamp.KIND_CHIP }
            for (stamp in stamps) {
                val hint = stamp.hint ?: continue
                when (hint.category) {
                    "medicine" -> {
                        val cy = stamp.y + stamp.h / 2
                        val onLine = chips.filter { abs((it.y + it.h / 2) - cy) <= SAME_LINE_CU && it.x >= stamp.x }
                        val frequency = onLine.firstOrNull { it.hint?.category == "dosage" }?.hint?.label
                        val duration = onLine.firstOrNull { it.hint?.category == "duration" }?.hint?.label
                        val food = onLine.filter { it.hint?.category == "food" }.mapNotNull { it.hint?.label }
                        medicines += MedicineHintDto(
                            name = hint.label,
                            frequency = frequency,
                            durationDays = duration?.let(::durationDays),
                            notes = food.joinToString(", ").ifBlank { null },
                        )
                    }
                    "test" -> tests += TestHintDto(hint.label, hint.code)
                    "advice" -> advice += hint.label
                }
            }
        }
        val hints = HintsDto(
            medicines = medicines.distinct(),
            tests = tests.distinct(),
            notes = advice.distinct().joinToString("; ").ifBlank { null },
        )
        return hints.takeUnless { it.isEmpty }
    }

    fun durationDays(label: String): Int? {
        val n = Regex("(\\d+)").find(label)?.value?.toIntOrNull() ?: return null
        return when {
            label.contains("month") -> n * 30
            label.contains("week") -> n * 7
            else -> n
        }
    }
}
