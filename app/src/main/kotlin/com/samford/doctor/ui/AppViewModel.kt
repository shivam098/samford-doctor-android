package com.samford.doctor.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samford.doctor.AppContainer
import com.samford.doctor.data.AppSettings
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.repo.OpenResult
import com.samford.doctor.data.repo.PersonaList
import com.samford.doctor.data.repo.QueueSync
import com.samford.doctor.data.repo.ServerStatus
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.model.Doctor
import com.samford.doctor.model.QueueEntry
import com.samford.doctor.sync.OutboxState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object SignIn : Screen
    data object Signature : Screen
    data object Queue : Screen
    data class Visit(val visitId: String) : Screen
    data class Review(val visitId: String) : Screen
    data object Sync : Screen
    data class Record(val visitId: String) : Screen
    data class Addendum(val docId: String) : Screen
    data object Settings : Screen
}

/** Overall sync summary for the queue header chip. */
data class SyncSummary(val waiting: Int = 0, val needsDoctor: Int = 0, val received: Int = 0)

class AppViewModel(private val c: AppContainer) : ViewModel() {
    private var pendingScreen: Screen? = null
    val backStack = mutableStateListOf<Screen>()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val server: StateFlow<ServerStatus> = c.server.status
    val queue: StateFlow<List<QueueEntry>> = c.queue.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val queueSync: StateFlow<QueueSync> = c.queue.sync
    val syncSummary: StateFlow<SyncSummary> = c.db.outboxDao().observeAll().map { items ->
        SyncSummary(
            waiting = items.count { !OutboxState.fromName(it.state).isTerminal && !OutboxState.fromName(it.state).needsDoctor },
            needsDoctor = items.count { OutboxState.fromName(it.state).needsDoctor },
            received = items.count { OutboxState.fromName(it.state).isTerminal },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncSummary())

    private val _personas = MutableStateFlow<PersonaList?>(null)
    val personas: StateFlow<PersonaList?> = _personas.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** True once settings are loaded and the first screen is chosen. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        viewModelScope.launch {
            c.restore()
            val s = c.settings.current()
            backStack.clear()
            backStack += when {
                s.doctor == null -> Screen.SignIn
                !s.hasSignature -> Screen.Signature
                else -> Screen.Queue
            }
            _ready.value = true
            pendingScreen?.takeIf { s.doctor != null }?.let { navigate(it) }
            pendingScreen = null
            if (s.doctor == null) loadPersonas()
        }
        // The server can come online after the sign-in screen loaded the offline list (start-up check,
        // "Check again", rediscovery): show the hospital's doctors then instead of "Connected" + offline ones.
        viewModelScope.launch {
            c.server.status.collect { status ->
                val list = _personas.value
                if (status is ServerStatus.Online && list != null && !list.fromServer && current == Screen.SignIn) {
                    _personas.value = c.session.personas()
                }
            }
        }
    }

    val current: Screen? get() = backStack.lastOrNull()

    fun navigate(screen: Screen) {
        backStack += screen
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun replaceAll(screen: Screen) {
        backStack.clear()
        backStack += screen
    }

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    fun loadPersonas() = viewModelScope.launch {
        _busy.value = true
        c.server.refresh()
        _personas.value = c.session.personas()
        _busy.value = false
    }

    fun setManualServer(url: String?) = viewModelScope.launch {
        _busy.value = true
        val status = c.server.setManualUrl(url?.takeIf { it.isNotBlank() })
        if (status is ServerStatus.Unreachable) say(status.message)
        _personas.value = c.session.personas()
        _busy.value = false
        if (status is ServerStatus.Online) afterServerAvailable()
    }

    fun signIn(doctor: Doctor, offlineDemo: Boolean) = viewModelScope.launch {
        _busy.value = true
        val problem = if (offlineDemo || !c.server.isOnline) {
            c.session.signInOfflineDemo(doctor)
            null
        } else {
            c.session.signIn(doctor)
        }
        _busy.value = false
        if (problem != null) {
            say(problem)
            return@launch
        }
        if (offlineDemo || !c.server.isOnline) {
            c.queue.ensureDemoQueue()
            c.appScope.launch { c.demoSeeder.ensureSeedHistory(c.settings.current().doctor ?: doctor) }
        }
        replaceAll(if (c.settings.current().hasSignature) Screen.Queue else Screen.Signature)
        refreshQueue()
    }

    fun saveSignature(strokes: List<InkStroke>) = viewModelScope.launch {
        c.settings.setSignature(strokes)
        if (backStack.size > 1) back() else replaceAll(Screen.Queue)
    }

    fun refreshQueue() = viewModelScope.launch { c.queue.refresh() }

    fun addWalkIn(name: String, age: Int?, sex: String) = viewModelScope.launch {
        val problem = c.queue.addWalkIn(name, age, sex)
        if (problem != null) say(problem)
    }

    fun openEntry(entry: QueueEntry) = viewModelScope.launch {
        entry.localVisitId?.let { id ->
            val visit = c.db.visitDao().visit(id)
            if (visit != null && visit.status != VisitStatus.DRAFT) {
                navigate(Screen.Record(id))
                return@launch
            }
        }
        _busy.value = true
        val result = c.visits.openVisit(entry)
        _busy.value = false
        when (result) {
            is OpenResult.Opened -> navigate(Screen.Visit(result.visitId))
            is OpenResult.Refused -> say(result.message)
        }
    }

    /** Called when the hospital server becomes reachable while signed in for offline demo. */
    private suspend fun afterServerAvailable() {
        val s = c.settings.current()
        if (s.doctor != null && s.token == null) {
            val problem = c.session.reconnect()
            if (problem == null) {
                c.syncEngine.authRestored()
                c.kickSync()
                say("Connected to the hospital server. Waiting visits will upload now.")
            }
        }
    }

    fun reconnect() = viewModelScope.launch {
        _busy.value = true
        val problem = c.session.reconnect()
        _busy.value = false
        if (problem != null) say(problem) else {
            c.syncEngine.authRestored()
            c.kickSync()
            refreshQueue()
            say("Signed in to the hospital server. Uploads resumed.")
        }
    }

    fun retry(bundleId: String) = viewModelScope.launch {
        c.syncEngine.retryNow(bundleId)
        c.kickSync()
    }

    fun syncNow() = viewModelScope.launch {
        c.server.refresh()
        c.kickSync()
        c.syncEngine.pollStatus()
    }

    fun setFingerWriting(enabled: Boolean) = viewModelScope.launch { c.settings.setFingerWriting(enabled) }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { c.settings.setAppLock(enabled) }

    fun signOut() = viewModelScope.launch {
        c.session.signOut()
        replaceAll(Screen.SignIn)
        loadPersonas()
    }

    val pendingCount: StateFlow<Int> = combine(syncSummary, queue) { s, _ -> s.waiting + s.needsDoctor }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun handleDeepLink(api: String?) {
        if (api.isNullOrBlank()) return
        setManualServer(api)
        say("Server address received. Checking connection…")
    }

    private val sessions = HashMap<String, VisitSession>()

    fun visitSession(visitId: String, addendumDocId: String? = null): VisitSession =
        sessions.getOrPut(addendumDocId ?: visitId) { VisitSession(visitId, addendumDocId, c, viewModelScope, ::say) }

    fun closeSession(key: String) {
        sessions.remove(key)?.close()
    }

    /** Leaves a visit: drafts stay autosaved and can be resumed from the queue. */
    fun leaveVisit(visitId: String) {
        closeSession(visitId)
        if (current is Screen.Visit || current is Screen.Review) {
            while (backStack.size > 1 && (current is Screen.Visit || current is Screen.Review)) back()
        }
    }

    fun startAddendum(parentDocId: String) = viewModelScope.launch {
        val docId = c.visits.createAddendumDraft(parentDocId)
        if (docId == null) say("An addendum can only be added to a completed document.") else navigate(Screen.Addendum(docId))
    }

    fun openWhenReady(screen: Screen) {
        if (_ready.value) navigate(screen) else pendingScreen = screen
    }

    override fun onCleared() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
