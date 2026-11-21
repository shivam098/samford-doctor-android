package com.samford.doctor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.model.Doctor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

data class AppSettings(
    val deviceId: String?,
    val manualUrl: String?,
    val lastGoodApiBase: String?,
    val token: String?,
    val doctor: Doctor?,
    val offlineDemo: Boolean,
    val signature: List<InkStroke>,
    val fingerWriting: Boolean,
    val appLock: Boolean,
    val eventsCursor: String?,
) {
    val signedIn: Boolean get() = doctor != null
    val hasSignature: Boolean get() = signature.isNotEmpty()
}

/**
 * Small key-value settings. The demo session token is stored here; a production build should
 * wrap it with an Android Keystore key (documented in README "Limitations").
 */
class SettingsStore(context: Context, fileName: String = "samford_settings") {
    /** One instance per file per process (the AppContainer is a singleton). */
    private val samfordDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.applicationContext.preferencesDataStoreFile(fileName) },
    )

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val manualUrl = stringPreferencesKey("manual_url")
        val lastGood = stringPreferencesKey("last_good_api_base")
        val token = stringPreferencesKey("token")
        val doctor = stringPreferencesKey("doctor_json")
        val offlineDemo = booleanPreferencesKey("offline_demo")
        val signature = stringPreferencesKey("signature_json")
        val fingerWriting = booleanPreferencesKey("finger_writing")
        val appLock = booleanPreferencesKey("app_lock")
        val eventsCursor = stringPreferencesKey("events_cursor")
    }

    private val json = InkFormat.readerJson
    private val strokeList = ListSerializer(InkStroke.serializer())

    val settings: Flow<AppSettings> = samfordDataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        deviceId = this[Keys.deviceId],
        manualUrl = this[Keys.manualUrl],
        lastGoodApiBase = this[Keys.lastGood],
        token = this[Keys.token],
        doctor = this[Keys.doctor]?.let { runCatching { json.decodeFromString(Doctor.serializer(), it) }.getOrNull() },
        offlineDemo = this[Keys.offlineDemo] ?: false,
        signature = this[Keys.signature]?.let { runCatching { json.decodeFromString(strokeList, it) }.getOrNull() } ?: emptyList(),
        fingerWriting = this[Keys.fingerWriting] ?: false,
        appLock = this[Keys.appLock] ?: false,
        eventsCursor = this[Keys.eventsCursor],
    )

    suspend fun deviceId(): String {
        current().deviceId?.let { return it }
        val id = UUID.randomUUID().toString()
        samfordDataStore.edit { if (it[Keys.deviceId] == null) it[Keys.deviceId] = id }
        return current().deviceId ?: id
    }

    suspend fun setManualUrl(url: String?) = samfordDataStore.edit {
        if (url.isNullOrBlank()) it.remove(Keys.manualUrl) else it[Keys.manualUrl] = url
    }

    suspend fun setLastGoodApiBase(base: String) = samfordDataStore.edit { it[Keys.lastGood] = base }

    suspend fun setSession(doctor: Doctor, token: String?, offlineDemo: Boolean) = samfordDataStore.edit {
        it[Keys.doctor] = InkFormat.canonicalJson.encodeToString(Doctor.serializer(), doctor)
        if (token == null) it.remove(Keys.token) else it[Keys.token] = token
        it[Keys.offlineDemo] = offlineDemo
    }

    suspend fun setToken(token: String?) = samfordDataStore.edit {
        if (token == null) it.remove(Keys.token) else it[Keys.token] = token
    }

    suspend fun signOut() = samfordDataStore.edit {
        it.remove(Keys.doctor)
        it.remove(Keys.token)
        it.remove(Keys.offlineDemo)
        it.remove(Keys.eventsCursor)
    }

    suspend fun setSignature(strokes: List<InkStroke>) = samfordDataStore.edit {
        it[Keys.signature] = InkFormat.canonicalJson.encodeToString(strokeList, strokes)
    }

    suspend fun setFingerWriting(enabled: Boolean) = samfordDataStore.edit { it[Keys.fingerWriting] = enabled }

    suspend fun setAppLock(enabled: Boolean) = samfordDataStore.edit { it[Keys.appLock] = enabled }

    suspend fun setEventsCursor(cursor: String?) = samfordDataStore.edit {
        if (cursor == null) it.remove(Keys.eventsCursor) else it[Keys.eventsCursor] = cursor
    }
}
