package com.samford.doctor.data.remote

import com.samford.doctor.model.Routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire models for the Samford mobile API v1. The contract is owned by shivam098/samford-web-app
 * (docs/mobile-api-v1.yaml, M1 at commit 27e0ef3a). Unknown fields are ignored so the server can
 * add fields without breaking installed tablets.
 */
object ApiJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
        // Scalars may arrive as numbers/booleans where the tablet keeps text (e.g. queue "token": 1).
        isLenient = true
    }

    /**
     * Request encoder: the server validates bodies with additionalProperties=false and
     * non-nullable optional fields, so nulls are omitted. The required-but-nullable ink keys
     * (inkSha256, inkFormat) are always non-null in tablet requests.
     */
    val requestJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

@Serializable
data class DiscoveryDoc(
    val schema: String? = null,
    val status: String? = null,
    /** Full API base including /api/mobile/v1; null while the deployment is offline. */
    val apiBaseUrl: String? = null,
    val webUrl: String? = null,
    val updatedAt: String? = null,
    val minDoctorAppVersion: Int? = null,
    val minPatientAppVersion: Int? = null,
    val synthetic: Boolean? = null,
    val message: String? = null,
)

@Serializable
data class PersonaDto(
    val personaId: String,
    val doctorId: String? = null,
    val name: String,
    val qualification: String = "",
    val registrationNo: String = "",
    val department: String = "",
    val departmentId: String? = null,
)

@Serializable
data class PersonasEnvelope(val personas: List<PersonaDto> = emptyList())

@Serializable
data class SessionRequest(val personaId: String, val deviceId: String, val deviceName: String? = null)

@Serializable
data class SessionDto(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresAt: String? = null,
    val deviceId: String? = null,
    val doctor: PersonaDto,
)

@Serializable
data class LabDto(val labId: String, val name: String, val kind: String = "OTHER")

@Serializable
data class LabsEnvelope(val labs: List<LabDto> = emptyList())

@Serializable
data class AllergyDto(val substance: String, val reaction: String? = null, val certainty: String? = null) {
    fun display(): String = if (reaction.isNullOrBlank()) substance else "$substance ($reaction)"
}

@Serializable
data class PatientDto(
    val patientId: String,
    val uhid: String = "",
    val name: String,
    val age: Int? = null,
    val sex: String = "",
    val mobileMasked: String? = null,
    val allergies: List<AllergyDto> = emptyList(),
)

@Serializable
data class VitalsDto(
    val bp: String? = null,
    val pulse: Int? = null,
    val tempC: Double? = null,
    val spo2: Int? = null,
    val weightKg: Double? = null,
    val recordedAt: String? = null,
)

@Serializable
data class QueueEntryDto(
    /** Always equal to [visitId] on the Samford server. */
    val queueEntryId: String,
    val visitId: String? = null,
    val token: String? = null,
    val status: String = "WAITING",
    val date: String? = null,
    val time: String? = null,
    val mode: String? = null,
    val priority: String? = null,
    val checkedInAt: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val returning: Boolean = false,
    val lastVisitAt: String? = null,
    val priorDocumentCount: Int = 0,
    val patient: PatientDto,
    val vitals: VitalsDto? = null,
    val demoWalkIn: Boolean = false,
)

@Serializable
data class QueueDto(
    val date: String? = null,
    val doctorId: String? = null,
    val entries: List<QueueEntryDto> = emptyList(),
    val serverTime: String? = null,
)

@Serializable
data class StartVisitRequest(val identityConfirmed: Boolean = true)

@Serializable
data class StartVisitDto(
    val queueEntryId: String? = null,
    val visitId: String,
    val status: String? = null,
    val startedAt: String? = null,
)

@Serializable
data class WalkInRequest(val name: String, val age: Int?, val sex: String, val mobile: String? = null)

@Serializable
data class PageRefDto(
    val index: Int,
    val pngSha256: String,
    val widthPx: Int = 1500,
    val heightPx: Int = 2121,
    /** Short-lived signed URL (server records only; never sent by the tablet). */
    val url: String? = null,
)

/** Closed hint schema: helpers for pharmacy/lab software; the handwriting stays authoritative. */
@Serializable
data class MedicineHintDto(
    val name: String,
    val strength: String? = null,
    val dose: String? = null,
    val frequency: String? = null,
    val durationDays: Int? = null,
    val notes: String? = null,
)

@Serializable
data class TestHintDto(val name: String, val code: String? = null)

@Serializable
data class HintsDto(
    val medicines: List<MedicineHintDto> = emptyList(),
    val tests: List<TestHintDto> = emptyList(),
    val diagnosis: String? = null,
    val notes: String? = null,
) {
    val isEmpty: Boolean get() = medicines.isEmpty() && tests.isEmpty() && diagnosis == null && notes == null
}

@Serializable
data class AuthorDto(val doctorId: String? = null, val name: String = "")

@Serializable
data class DocumentVersionDto(
    val version: Int,
    val createdAt: String? = null,
    val signedAt: String? = null,
    val reason: String? = null,
    val authorName: String? = null,
    val title: String? = null,
    val pages: List<PageRefDto> = emptyList(),
    val pdfSha256: String? = null,
    val inkSha256: String? = null,
    val inkFormat: String? = null,
    val recordHash: String? = null,
)

@Serializable
data class RecipientDto(
    val kind: String,
    val labId: String? = null,
    val name: String = "",
    val state: String = "AVAILABLE",
    val version: Int? = null,
    val updatedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class ClinicalDocumentDto(
    val documentId: String,
    val visitId: String? = null,
    val patientId: String? = null,
    val type: String,
    val title: String = "",
    val author: AuthorDto? = null,
    val latestVersion: Int = 1,
    val routing: Routing? = null,
    val versions: List<DocumentVersionDto> = emptyList(),
    val recipients: List<RecipientDto> = emptyList(),
)

@Serializable
data class RecordVisitDto(
    val visitId: String,
    val date: String = "",
    val doctorId: String? = null,
    val doctorName: String = "",
    val department: String? = null,
    val status: String? = null,
    val completedAt: String? = null,
    val documents: List<ClinicalDocumentDto> = emptyList(),
)

@Serializable
data class LabResultDto(
    val resultId: String,
    val documentId: String? = null,
    val labId: String? = null,
    val testName: String,
    val value: String = "",
    val unit: String? = null,
    val refRange: String? = null,
    val flag: String? = null,
    val reportedAt: String? = null,
    val summary: String? = null,
)

@Serializable
data class RecordDto(
    val patient: PatientDto,
    val allergies: List<AllergyDto> = emptyList(),
    val visits: List<RecordVisitDto> = emptyList(),
    val labResults: List<LabResultDto> = emptyList(),
    val generatedAt: String? = null,
)

@Serializable
data class CompleteDocumentDto(
    val clientDocId: String,
    val type: String,
    val title: String,
    val pages: List<PageRefDto>,
    val pdfSha256: String,
    val inkSha256: String,
    val inkFormat: String,
    val routing: Routing,
    val hints: HintsDto? = null,
    val signedAt: String,
)

@Serializable
data class FollowUpDto(val date: String, val note: String? = null, val requestBooking: Boolean = true)

@Serializable
data class CompleteVisitRequest(
    val bundleId: String,
    val completedAt: String,
    val documents: List<CompleteDocumentDto>,
    val followUp: FollowUpDto? = null,
)

@Serializable
data class CompletedDocumentRefDto(
    val clientDocId: String,
    val documentId: String,
    val version: Int = 1,
    val type: String? = null,
    val title: String? = null,
    val recordHash: String? = null,
)

@Serializable
data class CompleteVisitResponse(
    val bundleId: String? = null,
    val visitId: String? = null,
    val visitStatus: String? = null,
    val receivedAt: String,
    val recordHash: String? = null,
    val documents: List<CompletedDocumentRefDto> = emptyList(),
    val followUpId: String? = null,
)

@Serializable
data class AddendumRequest(
    val clientAddendumId: String,
    val baseVersion: Int,
    val reason: String,
    val pages: List<PageRefDto>,
    val pdfSha256: String,
    val inkSha256: String,
    val inkFormat: String,
    val signedAt: String,
    val hints: HintsDto? = null,
)

@Serializable
data class AddendumResponse(
    val documentId: String,
    val version: Int,
    val previousVersion: Int? = null,
    val recordHash: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DeliveryDocumentDto(
    val documentId: String,
    val version: Int = 1,
    val type: String? = null,
    val title: String? = null,
    val recipients: List<RecipientDto> = emptyList(),
)

@Serializable
data class DeliveryDto(val visitId: String? = null, val documents: List<DeliveryDocumentDto> = emptyList())

@Serializable
data class EventDto(
    val eventId: String,
    val type: String,
    val at: String? = null,
    val message: String = "",
    val visitId: String? = null,
    val documentId: String? = null,
    val patientId: String? = null,
    val data: JsonObject? = null,
)

@Serializable
data class EventsDto(
    val events: List<EventDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val serverTime: String? = null,
)

@Serializable
data class MissingBlobDto(
    val sha256: String,
    val mediaType: String? = null,
    val role: String? = null,
    val clientDocId: String? = null,
    val pageIndex: Int? = null,
)

@Serializable
data class ErrorDetails(val missing: List<MissingBlobDto> = emptyList(), val bundleId: String? = null)

@Serializable
data class ErrorBody(
    val code: String = "UNKNOWN",
    val message: String = "",
    val retryable: Boolean = false,
    val details: ErrorDetails? = null,
    val requestId: String? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody = ErrorBody())
