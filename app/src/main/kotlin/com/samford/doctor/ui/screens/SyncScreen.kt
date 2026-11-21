package com.samford.doctor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.AppContainer
import com.samford.doctor.R
import com.samford.doctor.core.Ist
import com.samford.doctor.data.db.BundleKind
import com.samford.doctor.data.db.DeliveryEntity
import com.samford.doctor.data.db.DocStatus
import com.samford.doctor.data.db.DocumentEntity
import com.samford.doctor.data.db.OutboxEntity
import com.samford.doctor.data.db.VisitEntity
import com.samford.doctor.data.db.VisitStatus
import com.samford.doctor.data.remote.ApiJson
import com.samford.doctor.data.remote.PageRefDto
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.model.DeliveryState
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Patient
import com.samford.doctor.sync.OutboxState
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.Loading
import com.samford.doctor.ui.StatusPill
import com.samford.doctor.ui.VisitLoad
import com.samford.doctor.ui.Screen
import com.samford.doctor.ui.theme.AlertRed
import com.samford.doctor.ui.theme.SamfordBlue
import com.samford.doctor.ui.theme.SuccessGreen
import com.samford.doctor.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

private data class SyncItem(val visit: VisitEntity, val patientName: String, val bundles: List<OutboxEntity>, val deliveries: List<DeliveryEntity>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(vm: AppViewModel, container: AppContainer) {
    val items by remember {
        combine(container.db.visitDao().observeVisits(), container.db.outboxDao().observeAll(), container.db.deliveryDao().observeAll()) { visits, bundles, deliveries ->
            val byVisit = bundles.groupBy { it.visitId }
            visits.filter { it.visitId in byVisit }.map { v ->
                val name = runCatching { InkFormat.readerJson.decodeFromString(Patient.serializer(), v.patientJson).name }.getOrDefault("—")
                SyncItem(v, name, byVisit[v.visitId].orEmpty().sortedBy { it.createdAtMs }, deliveries.filter { it.visitId == v.visitId })
            }.sortedByDescending { it.bundles.maxOf { b -> b.createdAtMs } }
        }
    }.collectAsStateWithLifecycle(emptyList())
    val summary by vm.syncSummary.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            vm.syncNow()
            delay(20_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                title = { Text(stringResource(R.string.sync_title)) },
                actions = {
                    TextButton(onClick = { vm.syncNow() }) {
                        Icon(Icons.Filled.Sync, null)
                        Text(stringResource(R.string.action_sync_now))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.sync_summary, summary.received, summary.waiting, summary.needsDoctor),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (items.isEmpty()) {
                Text(stringResource(R.string.sync_empty))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("sync-list")) {
                items(items, key = { it.visit.visitId }) { item -> SyncCard(item, vm) }
            }
        }
    }
}

@Composable
private fun SyncCard(item: SyncItem, vm: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${item.patientName} · ${stringResource(R.string.token_label, item.visit.token)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                item.visit.completedAtMs?.let { Text(Ist.dateTime(Instant.ofEpochMilli(it)), style = MaterialTheme.typography.bodySmall) }
            }
            item.bundles.forEach { b -> BundleRow(b, vm) }
            DeliveryRows(item.deliveries)
            TextButton(onClick = { vm.navigate(Screen.Record(item.visit.visitId)) }) { Text(stringResource(R.string.action_view_record)) }
        }
    }
}

@Composable
private fun BundleRow(b: OutboxEntity, vm: AppViewModel) {
    val state = OutboxState.fromName(b.state)
    Column {
        Text(if (b.kind == BundleKind.ADDENDUM) stringResource(R.string.bundle_addendum) else stringResource(R.string.bundle_visit), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Step(stringResource(R.string.step_saved), done = true)
            Step(stringResource(R.string.step_uploading), done = state == OutboxState.RECEIVED, active = state.isActive)
            Step(stringResource(R.string.step_received), done = state == OutboxState.RECEIVED, failed = state.needsDoctor)
        }
        val (label, color) = when (state) {
            OutboxState.SAVED_ON_TABLET -> stringResource(R.string.state_saved) to WarnAmber
            OutboxState.PREPARING_VISIT, OutboxState.UPLOADING, OutboxState.SUBMITTING -> stringResource(R.string.state_uploading) to SamfordBlue
            OutboxState.RECEIVED -> stringResource(R.string.state_received, b.receivedAt?.let { runCatching { Ist.dateTime(Instant.parse(it)) }.getOrDefault(it) } ?: "") to SuccessGreen
            OutboxState.WAITING_RETRY -> stringResource(R.string.state_waiting_retry) to WarnAmber
            OutboxState.AUTH_REQUIRED -> stringResource(R.string.state_auth) to AlertRed
            OutboxState.NEEDS_ATTENTION -> stringResource(R.string.state_attention) to AlertRed
        }
        StatusPill(label, color, Modifier.testTag("bundle-state-${state.name}"))
        b.lastErrorMessage?.takeIf { state == OutboxState.NEEDS_ATTENTION || state == OutboxState.WAITING_RETRY }?.let {
            Text(it, color = color, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state == OutboxState.WAITING_RETRY || state == OutboxState.NEEDS_ATTENTION) {
                OutlinedButton(onClick = { vm.retry(b.bundleId) }) { Text(stringResource(R.string.action_retry_now)) }
            }
            if (state == OutboxState.AUTH_REQUIRED) {
                Button(onClick = { vm.reconnect() }) { Text(stringResource(R.string.action_sign_in_again)) }
            }
        }
    }
}

@Composable
private fun Step(label: String, done: Boolean, active: Boolean = false, failed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when {
            failed -> Icons.Filled.Error to AlertRed
            done -> Icons.Filled.CheckCircle to SuccessGreen
            active -> Icons.Filled.Sync to SamfordBlue
            else -> Icons.Filled.RadioButtonUnchecked to Color.Gray
        }
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeliveryRows(deliveries: List<DeliveryEntity>) {
    if (deliveries.isEmpty()) return
    deliveries.groupBy { it.docType }.forEach { (type, rows) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(docLabel(DocType.fromWire(type ?: "OTHER")) + ":", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(130.dp))
            rows.forEach { d ->
                val state = DeliveryState.fromWire(d.state)
                StatusPill("${d.name}: ${deliveryLabel(state)}", deliveryColor(state))
            }
        }
    }
}

@Composable
private fun deliveryLabel(state: DeliveryState): String = when (state) {
    DeliveryState.PENDING -> stringResource(R.string.delivery_pending)
    DeliveryState.AVAILABLE -> stringResource(R.string.delivery_available)
    DeliveryState.VIEWED -> stringResource(R.string.delivery_viewed)
    DeliveryState.SAMPLE_COLLECTED -> stringResource(R.string.delivery_sample)
    DeliveryState.RESULT_READY -> stringResource(R.string.delivery_result)
    DeliveryState.PARTIALLY_DISPENSED -> stringResource(R.string.delivery_partial)
    DeliveryState.DISPENSED -> stringResource(R.string.delivery_dispensed)
    DeliveryState.NOT_DISPENSED -> stringResource(R.string.delivery_not_dispensed)
    DeliveryState.SUPERSEDED -> stringResource(R.string.delivery_superseded)
    DeliveryState.UNKNOWN -> stringResource(R.string.status_unknown)
}

private fun deliveryColor(state: DeliveryState) = when (state) {
    DeliveryState.PENDING, DeliveryState.UNKNOWN -> Color.Gray
    DeliveryState.NOT_DISPENSED -> AlertRed
    DeliveryState.DISPENSED, DeliveryState.RESULT_READY -> SuccessGreen
    else -> SamfordBlue
}

/** A completed visit's frozen documents and their versions; corrections only by addendum. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(vm: AppViewModel, container: AppContainer, visitId: String) {
    val visit by container.db.visitDao().observeVisit(visitId).collectAsStateWithLifecycle(null)
    val docs by container.db.visitDao().observeDocumentsForVisit(visitId).collectAsStateWithLifecycle(emptyList())
    val v = visit
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                title = {
                    val name = v?.let { runCatching { InkFormat.readerJson.decodeFromString(Patient.serializer(), it.patientJson).name }.getOrNull() }.orEmpty()
                    Text(stringResource(R.string.record_title, name))
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (v == null) {
                Loading()
                return@Column
            }
            if (v.status == VisitStatus.COMPLETING) {
                Text(stringResource(R.string.record_freezing))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (v.status == VisitStatus.DRAFT) {
                Button(onClick = { vm.navigate(Screen.Visit(visitId)) }) { Text(stringResource(R.string.action_resume)) }
            }
            val originals = docs.filter { !it.isAddendum && it.status == DocStatus.FROZEN }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(originals, key = { it.docId }) { root ->
                    val chain = listOf(root) + docs.filter { it.parentDocId == root.docId }.sortedBy { it.version }
                    DocumentVersions(root, chain, onAddendum = { vm.startAddendum(root.docId) })
                }
            }
        }
    }
}

@Composable
private fun DocumentVersions(root: DocumentEntity, chain: List<DocumentEntity>, onAddendum: () -> Unit) {
    val pageRefs = ListSerializer(PageRefDto.serializer())
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(docLabel(DocType.fromWire(root.type)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val hasDraft = chain.any { it.status == DocStatus.DRAFT }
                OutlinedButton(onClick = onAddendum, modifier = Modifier.testTag("addendum-${root.type}")) {
                    Text(if (hasDraft) stringResource(R.string.addendum_continue) else stringResource(R.string.addendum_add))
                }
            }
            chain.filter { it.status == DocStatus.FROZEN }.forEach { version ->
                val signed = version.signedAt?.let { runCatching { Ist.dateTime(Instant.parse(it)) }.getOrDefault(it) }.orEmpty()
                Text(
                    if (version.isAddendum) stringResource(R.string.version_addendum, version.version, signed, version.addendumReason.orEmpty())
                    else stringResource(R.string.version_original, version.version, signed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val refs = version.pageRefsJson?.let { runCatching { ApiJson.json.decodeFromString(pageRefs, it) }.getOrNull() }.orEmpty()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(refs.sortedBy { it.index }, key = { "${version.docId}-${it.index}" }) { ref ->
                        HistoryPage(ref.pngSha256, Modifier.height(320.dp).aspectRatio(1000f / 1414f), widthPx = 500)
                    }
                }
            }
        }
    }
}

/** Writing an addendum: a new signed version with a reason; earlier pages stay unchanged. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddendumScreen(vm: AppViewModel, container: AppContainer, docId: String) {
    val visitId by produceState<String?>(null, docId) { value = container.db.visitDao().document(docId)?.visitId }
    val vid = visitId ?: return Loading()
    val session = remember(docId) { vm.visitSession(vid, addendumDocId = docId) }
    val load by session.load.collectAsStateWithLifecycle()
    val reason by session.addendumReason.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val doneText = stringResource(R.string.addendum_done)
    when (val l = load) {
        VisitLoad.Loading -> Loading()
        is VisitLoad.Failed -> Loading(l.message)
        is VisitLoad.Ready -> WritingScreen(
            session = session,
            editor = l.editor,
            info = l.info,
            onBack = { vm.closeSession(docId); vm.back() },
            primaryLabel = stringResource(R.string.addendum_sign),
            onPrimary = { confirm = true },
            extraHeader = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.addendum_explain, l.info.parentTitle ?: "", l.info.parentVersion ?: 1), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { session.addendumReason.value = it.take(200) },
                            label = { Text(stringResource(R.string.addendum_reason)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("addendum-reason"),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(R.string.reason_dose, R.string.reason_added, R.string.reason_legibility, R.string.reason_test).forEach { id ->
                            val text = stringResource(id)
                            FilterChip(selected = reason == text, onClick = { session.addendumReason.value = text }, label = { Text(text) })
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.addendum_sign)) },
            text = { Text(stringResource(R.string.addendum_confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        val problem = session.completeAddendum()
                        busy = false
                        if (problem != null) vm.say(problem) else {
                            vm.closeSession(docId)
                            vm.back()
                            vm.say(doneText)
                        }
                    }
                }, modifier = Modifier.testTag("confirm-addendum")) { Text(stringResource(R.string.addendum_sign)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
