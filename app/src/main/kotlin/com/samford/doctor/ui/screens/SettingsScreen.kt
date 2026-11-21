package com.samford.doctor.ui.screens

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.AppContainer
import com.samford.doctor.BuildConfig
import com.samford.doctor.MainActivity
import com.samford.doctor.R
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, container: AppContainer) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    var manualUrl by rememberSaveable { mutableStateOf(settings?.manualUrl.orEmpty()) }
    val context = LocalContext.current
    val canLock = (context as? MainActivity)?.canAuthenticate() == true
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerCard(server, manualUrl, { manualUrl = it }, onSave = { vm.setManualServer(manualUrl) }, onRetry = { vm.syncNow() })
            if (settings?.manualUrl != null) {
                OutlinedButton(onClick = { manualUrl = ""; vm.setManualServer(null) }) { Text(stringResource(R.string.server_use_discovery)) }
            }
            Text(stringResource(R.string.server_discovery_url, BuildConfig.DISCOVERY_URL.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall)

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = currentLanguage(context) != "hi", onClick = { setLanguage(context, "en") }, label = { Text("English") })
                        FilterChip(selected = currentLanguage(context) == "hi", onClick = { setLanguage(context, "hi") }, label = { Text("हिन्दी") })
                    }
                    Text(stringResource(R.string.settings_language_note), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitch(stringResource(R.string.settings_finger_writing), stringResource(R.string.settings_finger_writing_note), settings?.fingerWriting == true) {
                        vm.setFingerWriting(it)
                    }
                    SettingSwitch(
                        stringResource(R.string.settings_app_lock),
                        if (canLock) stringResource(R.string.settings_app_lock_note) else stringResource(R.string.settings_app_lock_unavailable),
                        settings?.appLock == true,
                        enabled = canLock,
                    ) { vm.setAppLock(it) }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_account), fontWeight = FontWeight.SemiBold)
                    settings?.doctor?.let { Text("${it.name} · ${it.qualification} · ${stringResource(R.string.reg_no, it.registrationNo)}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.navigate(Screen.Signature) }) { Text(stringResource(R.string.settings_resign)) }
                        OutlinedButton(onClick = { vm.reconnect() }) { Text(stringResource(R.string.action_connect_server)) }
                        OutlinedButton(onClick = { vm.signOut() }) { Text(stringResource(R.string.settings_sign_out)) }
                    }
                    Text(stringResource(R.string.settings_sign_out_note), style = MaterialTheme.typography.bodySmall)
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_about), fontWeight = FontWeight.SemiBold)
                    Text("Samford Doctor ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Text(stringResource(R.string.settings_ink_format, InkFormat.FORMAT, InkFormat.RENDERER))
                    Text(stringResource(R.string.synthetic_banner), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun currentLanguage(context: Context): String =
    context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.language ?: "en"

/** Per-app language (Android 13+); the activity restarts in the chosen language. */
private fun setLanguage(context: Context, tag: String) {
    context.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(tag)
}
