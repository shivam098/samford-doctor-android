package com.samford.doctor.data.repo

import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.HttpMobileApi
import com.samford.doctor.data.remote.MobileApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ServerStatus {
    data object Unknown : ServerStatus
    data object Checking : ServerStatus
    data class Online(val apiBase: String, val source: Source) : ServerStatus
    data class Unreachable(val message: String, val triedBase: String?) : ServerStatus

    enum class Source { MANUAL, DISCOVERY, LAST_GOOD }
}

/**
 * Finds the hospital server: a manual URL (typed or from a QR link) wins; otherwise the stable
 * discovery document baked into the build; otherwise the last server that worked.
 */
class ServerRepository(
    private val api: MobileApi,
    private val settings: SettingsStore,
    private val discoveryUrl: String,
) {
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()
    private val mutex = Mutex()

    @Volatile
    var apiBase: String? = null
        private set

    val isOnline: Boolean get() = _status.value is ServerStatus.Online

    suspend fun restore() {
        val s = settings.current()
        apiBase = s.manualUrl?.let(HttpMobileApi::normalizeApiBase) ?: s.lastGoodApiBase
    }

    suspend fun refresh(): ServerStatus = mutex.withLock {
        _status.value = ServerStatus.Checking
        val s = settings.current()
        val candidates = ArrayList<Pair<String, ServerStatus.Source>>()
        val manual = s.manualUrl?.let(HttpMobileApi::normalizeApiBase)
        if (manual != null) {
            candidates += manual to ServerStatus.Source.MANUAL
        } else if (discoveryUrl.isNotBlank()) {
            val discovered = try {
                api.discover(discoveryUrl).takeIf { it.status == null || it.status == "online" }?.apiBaseUrl
            } catch (e: ApiException) {
                null
            }
            discovered?.let(HttpMobileApi::normalizeApiBase)?.let { candidates += it to ServerStatus.Source.DISCOVERY }
        }
        s.lastGoodApiBase?.let { candidates += it to ServerStatus.Source.LAST_GOOD }

        for ((base, source) in candidates.distinctBy { it.first }) {
            if (probe(base)) {
                apiBase = base
                settings.setLastGoodApiBase(base)
                val online = ServerStatus.Online(base, source)
                _status.value = online
                return@withLock online
            }
        }
        apiBase = candidates.firstOrNull()?.first
        val message = if (candidates.isEmpty()) {
            "No hospital server found. You can keep working in offline demo mode or enter the server address."
        } else {
            UNREACHABLE
        }
        val unreachable = ServerStatus.Unreachable(message, apiBase)
        _status.value = unreachable
        unreachable
    }

    private suspend fun probe(base: String): Boolean = try {
        api.meta(base)
        true
    } catch (e: ApiException) {
        false
    }

    /**
     * A request to the current server got no API answer (the demo tunnel can move at any time).
     * Drops to Unreachable so the next queue refresh or sync retry re-runs discovery.
     */
    fun markUnreachable(url: String) {
        val current = _status.value as? ServerStatus.Online ?: return
        if (!url.startsWith(current.apiBase)) return
        _status.compareAndSet(current, ServerStatus.Unreachable(UNREACHABLE, current.apiBase))
    }

    /** Sets (or clears, with null) the manual server address, e.g. from a QR link, and re-checks. */
    suspend fun setManualUrl(url: String?): ServerStatus {
        if (url != null && HttpMobileApi.normalizeApiBase(url) == null) {
            return ServerStatus.Unreachable("That doesn't look like a web address. Example: https://samford.example.org", null)
                .also { _status.value = it }
        }
        settings.setManualUrl(url)
        return refresh()
    }

    companion object {
        const val UNREACHABLE = "The hospital server isn't reachable. Work stays on this tablet and uploads when it's back."
    }
}
