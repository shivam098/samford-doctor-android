package com.samford.doctor.model

import kotlinx.serialization.Serializable

/** Document kinds a doctor can write in one OPD visit. Wire names match the mobile API. */
enum class DocType(val pageTitle: String, val pageTitleHi: String, val shortCode: String) {
    VISIT_SUMMARY("Visit Summary", "परामर्श सारांश", "VS"),
    PRESCRIPTION("Prescription", "पर्चा", "RX"),
    INVESTIGATIONS("Investigations", "जाँच", "IX"),
    ADVICE_REFERRAL("Advice / Referral", "सलाह / रेफ़रल", "AR"),
    OTHER("Notes", "टिप्पणी", "OT");

    companion object {
        /** Default set opened for every new visit, in tab order. */
        val visitDefaults = listOf(VISIT_SUMMARY, PRESCRIPTION, INVESTIGATIONS, ADVICE_REFERRAL)

        fun fromWire(value: String): DocType = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

@Serializable
data class Doctor(
    val personaId: String,
    val name: String,
    val qualification: String,
    val registrationNo: String,
    val department: String,
    val isDemo: Boolean = false,
)

@Serializable
data class Patient(
    val patientId: String,
    val uhid: String,
    val name: String,
    val age: Int? = null,
    val sex: String = "",
    val allergies: List<String> = emptyList(),
    val isSynthetic: Boolean = true,
) {
    val ageSex: String
        get() = listOfNotNull(age?.let { "$it" }, sex.takeIf { it.isNotBlank() }?.take(1)?.uppercase())
            .joinToString(" / ").ifBlank { "—" }

    val allergyText: String
        get() = allergies.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "None recorded" }

    val hasAllergies: Boolean get() = allergies.any { it.isNotBlank() }
}

@Serializable
data class Vitals(
    val bp: String? = null,
    val pulse: Int? = null,
    val tempC: Double? = null,
    val spo2: Int? = null,
    val weightKg: Double? = null,
) {
    /** Locale-independent text used on canonical pages. */
    fun summary(): String = buildList {
        bp?.let { add("BP $it") }
        pulse?.let { add("Pulse $it") }
        spo2?.let { add("SpO2 $it%") }
        tempC?.let { add("Temp ${"%.1f".format(java.util.Locale.ROOT, it)} °C") }
        weightKg?.let { add("Wt ${"%.1f".format(java.util.Locale.ROOT, it)} kg") }
    }.joinToString(" · ").ifBlank { "Not recorded" }
}

enum class QueueStatus {
    BOOKED, WAITING, CALLED, IN_CONSULTATION, ON_HOLD, COMPLETED, MISSED, CANCELLED, UNKNOWN;

    /** Statuses from which the server allows starting a consultation. */
    val canStart: Boolean get() = this == WAITING || this == CALLED || this == ON_HOLD || this == MISSED || this == IN_CONSULTATION

    companion object {
        fun fromWire(value: String?): QueueStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class EntrySource { SERVER, LOCAL_DEMO }

data class QueueEntry(
    val queueEntryId: String,
    val serverVisitId: String?,
    val token: String,
    val status: QueueStatus,
    val checkedInAtEpochMs: Long?,
    val returning: Boolean,
    val lastVisitAt: String?,
    val patient: Patient,
    val vitals: Vitals?,
    val source: EntrySource,
    /** Local visit id when this tablet already opened a visit for the entry. */
    val localVisitId: String? = null,
)

@Serializable
data class Lab(val labId: String, val name: String, val kind: String = "OTHER")

@Serializable
data class Routing(
    val pharmacy: Boolean,
    val labs: List<String>,
    val patient: Boolean,
)

/** Delivery states reported by the hospital for each recipient. PENDING is local-only. */
enum class RecipientKind { PHARMACY, LAB, PATIENT }

enum class DeliveryState {
    PENDING, AVAILABLE, VIEWED, SAMPLE_COLLECTED, RESULT_READY, PARTIALLY_DISPENSED, DISPENSED, NOT_DISPENSED, SUPERSEDED, UNKNOWN;

    companion object {
        fun fromWire(value: String?): DeliveryState = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
