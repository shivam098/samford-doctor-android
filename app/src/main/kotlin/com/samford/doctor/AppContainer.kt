package com.samford.doctor

import android.content.Context
import com.samford.doctor.core.Clock
import com.samford.doctor.core.IdGenerator
import com.samford.doctor.core.RandomIds
import com.samford.doctor.core.WallClock
import com.samford.doctor.data.BlobStore
import com.samford.doctor.data.InkJournal
import com.samford.doctor.data.JournalWriter
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.db.SamfordDb
import com.samford.doctor.data.remote.HttpMobileApi
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.repo.DemoSeeder
import com.samford.doctor.data.repo.QueueRepository
import com.samford.doctor.data.repo.RecordRepository
import com.samford.doctor.data.repo.ServerRepository
import com.samford.doctor.data.repo.SessionRepository
import com.samford.doctor.data.repo.VisitRepository
import com.samford.doctor.sync.Freezer
import com.samford.doctor.sync.SyncEngine
import com.samford.doctor.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency graph (no reflection-based DI in the hot path). */
class AppContainer(
    context: Context,
    val db: SamfordDb = SamfordDb.open(context),
    val clock: Clock = WallClock,
    val ids: IdGenerator = RandomIds,
    apiOverride: MobileApi? = null,
    discoveryUrl: String = BuildConfig.DISCOVERY_URL,
    private val kickSync: (Context) -> Unit = SyncScheduler::kick,
    settingsName: String = "samford_settings",
) {
    private val appContext = context.applicationContext

    /** Survives screens: freezes and uploads continue while the doctor calls the next patient. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var deviceId: String? = null

    val settings = SettingsStore(appContext, settingsName)
    val blobs = BlobStore(File(appContext.filesDir, "blobs"), db.blobDao(), clock)
    val journal = InkJournal(db, db.inkDao(), clock)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val journalWriter = JournalWriter(CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))

    private lateinit var serverRef: ServerRepository
    private lateinit var sessionRef: SessionRepository

    val api: MobileApi = apiOverride ?: HttpMobileApi(
        baseProvider = { serverRef.apiBase },
        tokenProvider = { sessionRef.token },
        deviceIdProvider = { deviceId },
        appVersionCode = BuildConfig.VERSION_CODE,
    )

    val server = ServerRepository(api, settings, discoveryUrl).also { serverRef = it }
    val session = SessionRepository(api, settings, server, ids).also { sessionRef = it }
    val queue = QueueRepository(api, db, server, session, settings, clock, ids)
    val visits = VisitRepository(db, journal, api, server, session, settings, ids, clock)
    val freezer = Freezer(db, journal, blobs, settings, ids, clock) { kickSync(appContext) }
    val syncEngine = SyncEngine(db, api, blobs, session, server, settings, clock)
    val records = RecordRepository(api, db, blobs, server, session, settings, clock)
    val demoSeeder = DemoSeeder(db, journal, freezer, settings, ids, clock)

    fun kickSync() = kickSync(appContext)

    suspend fun restore() {
        deviceId = settings.deviceId()
        server.restore()
        session.restore()
    }
}
