package com.samford.doctor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.R
import com.samford.doctor.core.Ist
import com.samford.doctor.data.repo.ServerStatus
import com.samford.doctor.model.EntrySource
import com.samford.doctor.model.QueueEntry
import com.samford.doctor.model.QueueStatus
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.Screen
import com.samford.doctor.ui.StatusPill
import com.samford.doctor.ui.SyncSummary
import com.samford.doctor.ui.SyntheticBanner
import com.samford.doctor.ui.theme.AlertRed
import com.samford.doctor.ui.theme.SamfordBlue
import com.samford.doctor.ui.theme.SuccessGreen
import com.samford.doctor.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: AppViewModel, onRequestNotifications: () -> Unit) {
    val entries by vm.queue.collectAsStateWithLifecycle()
    val sync by vm.queueSync.collectAsStateWithLifecycle()
    val summary by vm.syncSummary.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<QueueEntry?>(null) }
    var walkIn by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRequestNotifications()
        while (true) {
            vm.refreshQueue()
            delay(30_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.queue_title, Ist.date(Instant.now())), fontWeight = FontWeight.SemiBold)
                        settings?.doctor?.let { Text("${it.name} · ${it.department}", style = MaterialTheme.typography.bodyMedium) }
                    }
                },
                actions = {
                    SyncChip(summary) { vm.navigate(Screen.Sync) }
                    IconButton(onClick = { vm.refreshQueue() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings_title)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { walkIn = true },
                icon = { Icon(Icons.Filled.PersonAdd, null) },
                text = { Text(stringResource(R.string.queue_add_walkin)) },
                modifier = Modifier.testTag("add-walkin"),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyntheticBanner(Modifier.fillMaxWidth())
            if (busy || sync.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ConnectionLine(settings?.offlineDemo == true, server, sync.message, sync.needsSignIn) { vm.reconnect() }
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.queue_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 380.dp),
                    modifier = Modifier.fillMaxSize().testTag("queue-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(entries, key = { it.queueEntryId }) { entry ->
                        QueueCard(entry, onOpen = {
                            if (entry.localVisitId != null) vm.openEntry(entry) else confirm = entry
                        })
                    }
                }
            }
        }
    }

    confirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.confirm_identity_title)) },
            text = {
                Column {
                    Text(entry.patient.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${entry.patient.ageSex} · UHID ${entry.patient.uhid} · ${stringResource(R.string.token_label, entry.token)}")
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.confirm_identity_body))
                }
            },
            confirmButton = {
                Button(onClick = { vm.openEntry(entry); confirm = null }, modifier = Modifier.testTag("confirm-start")) {
                    Text(stringResource(R.string.confirm_identity_ok))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (walkIn) WalkInDialog(onDismiss = { walkIn = false }) { name, age, sex ->
        vm.addWalkIn(name, age, sex)
        walkIn = false
    }
}

@Composable
private fun ConnectionLine(offlineDemo: Boolean, server: ServerStatus, message: String?, needsSignIn: Boolean, onReconnect: () -> Unit) {
    val text: String
    val color = when {
        offlineDemo -> {
            text = stringResource(R.string.mode_offline_demo)
            WarnAmber
        }
        message != null -> {
            text = message
            AlertRed
        }
        server is ServerStatus.Online -> {
            text = stringResource(R.string.mode_connected)
            SuccessGreen
        }
        server is ServerStatus.Unreachable -> {
            text = server.message
            AlertRed
        }
        else -> {
            text = stringResource(R.string.server_checking)
            SamfordBlue
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (offlineDemo || needsSignIn) {
            TextButton(onClick = onReconnect) { Text(stringResource(R.string.action_connect_server)) }
        }
    }
}

@Composable
private fun SyncChip(summary: SyncSummary, onClick: () -> Unit) {
    val (label, icon, color) = when {
        summary.needsDoctor > 0 -> Triple(stringResource(R.string.sync_needs_attention, summary.needsDoctor), Icons.Filled.Warning, AlertRed)
        summary.waiting > 0 -> Triple(stringResource(R.string.sync_waiting, summary.waiting), Icons.Filled.CloudUpload, WarnAmber)
        else -> Triple(stringResource(R.string.sync_all_done), Icons.Filled.CloudDone, SuccessGreen)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, color = color) },
        leadingIcon = { Icon(icon, null, tint = color) },
        modifier = Modifier.padding(end = 8.dp).testTag("sync-chip"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueCard(entry: QueueEntry, onOpen: () -> Unit) {
    val canOpen = entry.localVisitId != null || entry.source == EntrySource.LOCAL_DEMO || entry.status.canStart
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = canOpen, onClick = onOpen).testTag("queue-${entry.queueEntryId}"),
        colors = CardDefaults.cardColors(containerColor = if (entry.status == QueueStatus.IN_CONSULTATION) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Text(stringResource(R.string.token_short), style = MaterialTheme.typography.labelSmall)
                Text(entry.token, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = SamfordBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.patient.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${entry.patient.ageSex} · UHID ${entry.patient.uhid}", style = MaterialTheme.typography.bodyMedium)
                if (entry.patient.hasAllergies) {
                    Text(stringResource(R.string.allergies_label, entry.patient.allergyText), color = AlertRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                entry.vitals?.let { Text(it.summary(), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(statusLabel(entry.status), statusColor(entry.status))
                    if (entry.returning) StatusPill(stringResource(R.string.returning_patient), SamfordBlue)
                    if (entry.source == EntrySource.LOCAL_DEMO) StatusPill(stringResource(R.string.local_demo), WarnAmber)
                }
            }
            val label = when {
                entry.status == QueueStatus.COMPLETED && entry.localVisitId != null -> stringResource(R.string.action_view_record)
                entry.localVisitId != null -> stringResource(R.string.action_resume)
                else -> stringResource(R.string.action_start_visit)
            }
            if (entry.status == QueueStatus.COMPLETED && entry.localVisitId == null) return@Row
            if (entry.localVisitId != null) OutlinedButton(onClick = onOpen) { Text(label) } else Button(onClick = onOpen, enabled = canOpen) { Text(label) }
        }
    }
}

@Composable
fun statusLabel(status: QueueStatus): String = when (status) {
    QueueStatus.BOOKED -> stringResource(R.string.status_booked)
    QueueStatus.WAITING -> stringResource(R.string.status_waiting)
    QueueStatus.CALLED -> stringResource(R.string.status_called)
    QueueStatus.IN_CONSULTATION -> stringResource(R.string.status_in_consultation)
    QueueStatus.ON_HOLD -> stringResource(R.string.status_on_hold)
    QueueStatus.COMPLETED -> stringResource(R.string.status_completed)
    QueueStatus.MISSED -> stringResource(R.string.status_missed)
    QueueStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    QueueStatus.UNKNOWN -> stringResource(R.string.status_unknown)
}

private fun statusColor(status: QueueStatus) = when (status) {
    QueueStatus.IN_CONSULTATION -> SamfordBlue
    QueueStatus.COMPLETED -> SuccessGreen
    QueueStatus.CANCELLED, QueueStatus.MISSED -> AlertRed
    else -> WarnAmber
}

@Composable
private fun WalkInDialog(onDismiss: () -> Unit, onAdd: (String, Int?, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf("Female") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.walkin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.walkin_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.walkin_name)) }, singleLine = true, modifier = Modifier.testTag("walkin-name"))
                OutlinedTextField(
                    age, { age = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.walkin_age)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Female", "Male", "Other").forEach { option ->
                        FilterChip(selected = sex == option, onClick = { sex = option }, label = { Text(sexLabel(option)) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(name, age.toIntOrNull(), sex) }, modifier = Modifier.testTag("walkin-add")) { Text(stringResource(R.string.walkin_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun sexLabel(option: String) = when (option) {
    "Female" -> stringResource(R.string.sex_female)
    "Male" -> stringResource(R.string.sex_male)
    else -> stringResource(R.string.sex_other)
}
