package com.samford.doctor.data.repo

import android.os.Build
import com.samford.doctor.core.IdGenerator
import com.samford.doctor.data.SettingsStore
import com.samford.doctor.data.remote.ApiException
import com.samford.doctor.data.remote.MobileApi
import com.samford.doctor.data.remote.PersonaDto
import com.samford.doctor.data.remote.SessionRequest
import com.samford.doctor.model.Doctor

data class PersonaList(val doctors: List<Doctor>, val fromServer: Boolean, val message: String?)

/** Public demo sign-in: pick a persona; the server issues a device-bound bearer token. */
class SessionRepository(
    private val api: MobileApi,
    private val settings: SettingsStore,
    private val server: ServerRepository,
    private val ids: IdGenerator,
) {
    @Volatile
    var token: String? = null
        private set

    suspend fun restore() {
        token = settings.current().token
    }

    suspend fun personas(): PersonaList {
        if (server.isOnline) {
            try {
                val list = api.personas().map { it.toDoctor() }
                if (list.isNotEmpty()) return PersonaList(list, fromServer = true, message = null)
            } catch (e: ApiException) {
                return PersonaList(DemoData.personas, fromServer = false, message = e.error.message)
            }
        }
        return PersonaList(DemoData.personas, fromServer = false, message = null)
    }

    /** Server sign-in. Returns null on success or a plain-language problem. */
    suspend fun signIn(persona: Doctor): String? = try {
        val session = api.signIn(SessionRequest(persona.personaId, settings.deviceId(), deviceName()), ids.uuid())
        token = session.token
        settings.setSession(session.doctor.toDoctor(), session.token, offlineDemo = false)
        null
    } catch (e: ApiException) {
        e.error.message
    }

    /** Offline demo: no server needed. Completed visits stay queued until a server is reachable. */
    suspend fun signInOfflineDemo(persona: Doctor) {
        token = null
        settings.setSession(persona.copy(isDemo = true), null, offlineDemo = true)
    }

    /** Gets a fresh token for the signed-in doctor (used when uploads report AUTH_REQUIRED). */
    suspend fun reconnect(): String? {
        val doctor = settings.current().doctor ?: return "Please sign in first."
        val refreshed = server.refresh()
        if (refreshed !is ServerStatus.Online) return (refreshed as? ServerStatus.Unreachable)?.message ?: "The hospital server isn't reachable."
        val serverPersonas = try {
            api.personas()
        } catch (e: ApiException) {
            return e.error.message
        }
        val match = serverPersonas.firstOrNull { it.personaId == doctor.personaId }
            ?: serverPersonas.firstOrNull { it.registrationNo == doctor.registrationNo }
            ?: return "This demo doctor isn't on the hospital server. Sign out and pick a doctor from the server list."
        return try {
            val session = api.signIn(SessionRequest(match.personaId, settings.deviceId(), deviceName()), ids.uuid())
            token = session.token
            // Keep the persona the pages were signed with; only the token changes.
            settings.setSession(doctor, session.token, offlineDemo = false)
            null
        } catch (e: ApiException) {
            e.error.message
        }
    }

    suspend fun signOut() {
        token = null
        settings.signOut()
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        fun PersonaDto.toDoctor() = Doctor(personaId, name, qualification, registrationNo, department, isDemo = false)
    }
}
