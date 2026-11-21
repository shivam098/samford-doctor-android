package com.samford.doctor.data.repo

import com.samford.doctor.core.Clock
import com.samford.doctor.core.IdGenerator
import com.samford.doctor.core.Ist
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.CacheEntity
import com.samford.doctor.data.db.DemoQueueEntity
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.data.remote.LabDto
import com.samford.doctor.data.remote.LabsEnvelope
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.remote.QueueDto
import com.samford.doctor.data.remote.WalkInRequest
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.model.EntrySource
import com.samford.doctor.model.Lab
import com.samford.doctor.model.Patient
import com.samford.doctor.model.QueueEntry
import com.samford.doctor.model.QueueStatus
import com.samford.doctor.model.Vitals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

data class QueueSync(
    val loading: Boolean = false,
    val message: String? = null,
    val fromServer: Boolean = false,
    val updatedAtMs: Long? = null,
    val needsSignIn: Boolean = false,
)

/** Today's OPD queue: the hospital's front-desk check-ins merged with tablet-only demo entries. */
class QueueRepository(
    private val api: MobileApi,
    private val db: SamfordDb,
    private val server: ServerRepository,
    private val session: SessionRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    private val json = ApiJson.json
    private val serverEntries = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val _sync = MutableStateFlow(QueueSync())
    val sync: StateFlow<QueueSync> = _sync.asStateFlow()

    val entries: Flow<List<QueueEntry>> = combine(
        serverEntries,
        db.demoDao().observeAll(),
        db.visitDao().observeVisits(),
    ) { srv, demo, visits -> merge(srv, demo, visits) }

    suspend fun refresh() {
        val s = settings.current()
        if (s.offlineDemo || session.token == null) {
            serverEntries.value = emptyList()
            _sync.value = QueueSync(message = null, fromServer = false, updatedAtMs = clock.nowMs())
            return
        }
        _sync.value = _sync.value.copy(loading = true)
        if (!server.isOnline) server.refresh()
        val date = Ist.isoDate(clock.now())
        val key = "queue:$date"
        val cached = db.cacheDao().get(key)
        try {
            val result = api.queue(date, cached?.etag)
            val dto = if (result.notModified && cached != null) {
                json.decodeFromString(QueueDto.serializer(), cached.json)
            } else {
                val value = result.value ?: QueueDto()
                db.cacheDao().put(CacheEntity(key, json.encodeToString(QueueDto.serializer(), value), result.etag, clock.nowMs()))
                value
            }
            serverEntries.value = dto.entries.map { it.toDomain() }
            _sync.value = QueueSync(loading = false, message = null, fromServer = true, updatedAtMs = clock.nowMs())
        } catch (e: ApiException) {
            cached?.let { c -> serverEntries.value = json.decodeFromString(QueueDto.serializer(), c.json).entries.map { it.toDomain() } }
            _sync.value = QueueSync(
                loading = false,
                message = e.error.message,
                fromServer = cached != null,
                updatedAtMs = cached?.fetchedAtMs,
                needsSignIn = e.error.isAuth,
            )
        }
    }

    /** Demo-only walk-in. Online it registers on the hospital server; offline it stays on the tablet. */
    suspend fun addWalkIn(name: String, age: Int?, sex: String): String? {
        val s = settings.current()
        val cleanName = name.trim().ifBlank { "Walk-in patient" }
        if (!s.offlineDemo && session.token != null && server.isOnline) {
            return try {
                api.createWalkIn(WalkInRequest(cleanName, age, sex), ids.uuid())
                refresh()
                null
            } catch (e: ApiException) {
                e.error.message
            }
        }
        val n = db.demoDao().count() + 1
        val id = "local-walkin-" + ids.shortId("")
        val patient = Patient(
            patientId = "local-patient-" + ids.shortId(""),
            uhid = "DEMO-W%03d".format(n),
            name = if (cleanName.contains("Synthetic", ignoreCase = true)) cleanName else "$cleanName (Synthetic)",
            age = age,
            sex = sex,
            allergies = emptyList(),
            isSynthetic = true,
        )
        db.demoDao().upsert(
            DemoQueueEntity(id, "W$n", InkFormat.canonicalJson.encodeToString(Patient.serializer(), patient), null, false, clock.nowMs()),
        )
        return null
    }

    /** Seeds the synthetic offline queue once. */
    suspend fun ensureDemoQueue() {
        if (db.demoDao().count() > 0) return
        DemoData.seedPatients.forEachIndexed { i, seed ->
            db.demoDao().upsert(
                DemoQueueEntity(
                    seed.queueEntryId, seed.token,
                    InkFormat.canonicalJson.encodeToString(Patient.serializer(), seed.patient),
                    seed.vitals?.let { InkFormat.canonicalJson.encodeToString(Vitals.serializer(), it) },
                    seed.returning, clock.nowMs() + i,
                ),
            )
        }
    }

    suspend fun labs(): List<Lab> {
        val key = "labs"
        if (!settings.current().offlineDemo && session.token != null && server.isOnline) {
            try {
                val labs = api.labs()
                db.cacheDao().put(CacheEntity(key, json.encodeToString(LabsEnvelope.serializer(), LabsEnvelope(labs)), null, clock.nowMs()))
                return labs.map { it.toLab() }
            } catch (e: ApiException) {
                // Fall through to the cached or demo list.
            }
        }
        val cached = db.cacheDao().get(key)?.let { runCatching { json.decodeFromString(LabsEnvelope.serializer(), it.json).labs }.getOrNull() }
        return cached?.map { it.toLab() } ?: DemoData.labs
    }

    private fun LabDto.toLab() = Lab(labId, name, kind)

    private fun merge(srv: List<QueueEntry>, demo: List<DemoQueueEntity>, visits: List<VisitEntity>): List<QueueEntry> {
        val byEntry = visits.filter { it.queueEntryId != null && !it.isSeedHistory }
            .groupBy { it.queueEntryId!! }
            .mapValues { (_, v) -> v.maxBy { it.startedAtMs } }
        val reader = InkFormat.readerJson
        val local = demo.map { d ->
            QueueEntry(
                queueEntryId = d.queueEntryId,
                serverVisitId = null,
                token = d.token,
                status = QueueStatus.WAITING,
                checkedInAtEpochMs = d.createdAtMs,
                returning = d.returning,
                lastVisitAt = null,
                patient = reader.decodeFromString(Patient.serializer(), d.patientJson),
                vitals = d.vitalsJson?.let { reader.decodeFromString(Vitals.serializer(), it) },
                source = EntrySource.LOCAL_DEMO,
            )
        }
        return (srv + local).map { e ->
            val v = byEntry[e.queueEntryId]
            val status = when (v?.status) {
                VisitStatus.DRAFT -> QueueStatus.IN_CONSULTATION
                VisitStatus.COMPLETING, VisitStatus.COMPLETED -> QueueStatus.COMPLETED
                else -> e.status
            }
            e.copy(localVisitId = v?.visitId, status = status)
        }.sortedWith(compareBy<QueueEntry>({ rank(it.status) }, { it.token.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it.token }))
    }

    private fun rank(s: QueueStatus): Int = when (s) {
        QueueStatus.IN_CONSULTATION -> 0
        QueueStatus.CALLED, QueueStatus.WAITING, QueueStatus.ON_HOLD -> 1
        QueueStatus.BOOKED, QueueStatus.MISSED, QueueStatus.UNKNOWN -> 2
        QueueStatus.COMPLETED -> 3
        QueueStatus.CANCELLED -> 4
    }
}
