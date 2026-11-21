package com.samford.doctor.data.repo

import com.samford.doctor.data.remote.AllergyDto
import com.samford.doctor.data.remote.LabResultDto
import com.samford.doctor.data.remote.PatientDto
import com.samford.doctor.data.remote.QueueEntryDto
import com.samford.doctor.data.remote.VitalsDto
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.EntrySource
import com.samford.doctor.model.Lab
import com.samford.doctor.model.Patient
import com.samford.doctor.model.QueueEntry
import com.samford.doctor.model.QueueStatus
import com.samford.doctor.model.Vitals
import java.time.Instant

/**
 * Synthetic people for offline demo mode. Every name carries "(Synthetic)" and every UHID starts
 * with DEMO- so a screenshot can never be mistaken for a real record.
 */
object DemoData {
    val personas: List<Doctor> = listOf(
        Doctor("demo-dr-asha", "Dr. Asha Verma (Demo)", "MBBS, MD (Medicine)", "DEMO-JMC-1187", "General Medicine", isDemo = true),
        Doctor("demo-dr-rohan", "Dr. Rohan Mehta (Demo)", "MBBS, MS (Orthopaedics)", "DEMO-JMC-0932", "Orthopaedics", isDemo = true),
        Doctor("demo-dr-farah", "Dr. Farah Siddiqui (Demo)", "MBBS, DCH", "DEMO-JMC-0456", "Paediatrics", isDemo = true),
    )

    /** Same ids as the mock backend's seed so routing survives a later upload. */
    val labs: List<Lab> = listOf(
        Lab("central-lab", "Central Lab (Pathology)", "PATHOLOGY"),
        Lab("radiology", "Radiology (Imaging)", "IMAGING"),
    )

    data class SeedPatient(
        val queueEntryId: String,
        val token: String,
        val patient: Patient,
        val vitals: Vitals?,
        val returning: Boolean,
        val historyDaysAgo: Int?,
    )

    val seedPatients: List<SeedPatient> = listOf(
        SeedPatient(
            "local-demo-1", "D1",
            Patient("demo-patient-1", "DEMO-0001", "Meera Kumari (Synthetic)", 34, "Female", listOf("Penicillin (rash)"), isSynthetic = true),
            Vitals("118/76", 82, 37.2, 98, 58.0), returning = true, historyDaysAgo = 14,
        ),
        SeedPatient(
            "local-demo-2", "D2",
            Patient("demo-patient-2", "DEMO-0002", "Arjun Oraon (Synthetic)", 52, "Male", emptyList(), isSynthetic = true),
            Vitals("138/88", 76, 36.8, 97, 74.5), returning = true, historyDaysAgo = 30,
        ),
        SeedPatient(
            "local-demo-3", "D3",
            Patient("demo-patient-3", "DEMO-0003", "Sana Parveen (Synthetic)", 7, "Female", listOf("Sulfa drugs"), isSynthetic = true),
            Vitals(null, 104, 38.4, 97, 21.0), returning = false, historyDaysAgo = null,
        ),
        SeedPatient(
            "local-demo-4", "D4",
            Patient("demo-patient-4", "DEMO-0004", "Ravi Munda (Synthetic)", 28, "Male", emptyList(), isSynthetic = true),
            Vitals("122/80", 72, 36.9, 99, 66.0), returning = false, historyDaysAgo = null,
        ),
        SeedPatient(
            "local-demo-5", "D5",
            Patient("demo-patient-5", "DEMO-0005", "Kavita Devi (Synthetic)", 61, "Female", listOf("Aspirin (wheeze)"), isSynthetic = true),
            Vitals("146/92", 88, 37.0, 96, 63.0), returning = false, historyDaysAgo = null,
        ),
    )

    fun labResults(patientId: String): List<LabResultDto> = when (patientId) {
        "demo-patient-2" -> listOf(
            LabResultDto("demo-r1", testName = "HbA1c", value = "7.8", unit = "%", refRange = "4.0-5.6", flag = "ABNORMAL", reportedAt = "2026-10-22T09:30:00+05:30"),
            LabResultDto("demo-r2", testName = "Fasting glucose", value = "142", unit = "mg/dL", refRange = "70-100", flag = "ABNORMAL", reportedAt = "2026-10-22T09:30:00+05:30"),
            LabResultDto("demo-r3", testName = "Serum creatinine", value = "0.9", unit = "mg/dL", refRange = "0.7-1.3", flag = "NORMAL", reportedAt = "2026-10-22T09:30:00+05:30"),
        )
        "demo-patient-1" -> listOf(
            LabResultDto("demo-r4", testName = "Haemoglobin", value = "10.9", unit = "g/dL", refRange = "12.0-15.5", flag = "ABNORMAL", reportedAt = "2026-11-07T11:00:00+05:30"),
        )
        else -> emptyList()
    }
}

/** Wire -> domain. All Samford demo data is synthetic, so server patients are flagged too. */
fun PatientDto.toPatient(): Patient = Patient(
    patientId = patientId,
    uhid = uhid,
    name = name,
    age = age,
    sex = sex,
    allergies = allergies.map(AllergyDto::display),
    isSynthetic = true,
)

fun VitalsDto.toVitals(): Vitals = Vitals(bp, pulse, tempC, spo2, weightKg)

fun QueueEntryDto.toDomain(): QueueEntry = QueueEntry(
    queueEntryId = queueEntryId,
    serverVisitId = visitId ?: queueEntryId,
    token = token ?: time ?: "—",
    status = QueueStatus.fromWire(status),
    checkedInAtEpochMs = checkedInAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    returning = returning,
    lastVisitAt = lastVisitAt,
    patient = patient.toPatient(),
    vitals = vitals?.toVitals(),
    source = EntrySource.SERVER,
)
