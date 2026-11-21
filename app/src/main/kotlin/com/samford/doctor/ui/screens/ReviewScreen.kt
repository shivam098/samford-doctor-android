package com.samford.doctor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.R
import com.samford.doctor.editor.EditorDoc
import com.samford.doctor.model.DocType
import com.samford.doctor.render.RenderMode
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.FollowUp
import com.samford.doctor.ui.Loading
import com.samford.doctor.ui.Screen
import com.samford.doctor.ui.VisitLoad
import com.samford.doctor.ui.theme.AlertRed
import com.samford.doctor.ui.theme.SamfordBlue
import com.samford.doctor.ui.theme.WarnAmber
import kotlinx.coroutines.launch

private data class Recipient(val label: String, val icon: ImageVector, val docs: List<EditorDoc>, val tag: String)

/** Shows exactly what each recipient will receive, then signs and completes the visit. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(vm: AppViewModel, visitId: String) {
    val session = remember(visitId) { vm.visitSession(visitId) }
    val load by session.load.collectAsStateWithLifecycle()
    val ready = load as? VisitLoad.Ready
    if (ready == null) {
        Loading()
        return
    }
    val editor = ready.editor
    val info = ready.info
    val state by editor.state.collectAsStateWithLifecycle()
    val labs by session.labs.collectAsStateWithLifecycle()
    val followUp by session.followUp.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var completing by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val written = state.docs.filter { editor.hasContent(it.docId) }
    val rx = written.firstOrNull { it.type == DocType.PRESCRIPTION }
    val ix = written.firstOrNull { it.type == DocType.INVESTIGATIONS }
    val recipients = buildList {
        add(Recipient(stringResource(R.string.recipient_pharmacy), Icons.Filled.LocalPharmacy, listOfNotNull(rx), "recipient-pharmacy"))
        ix?.labs?.forEach { labId ->
            val name = labs.firstOrNull { it.labId == labId }?.name ?: labId
            add(Recipient(name, Icons.Filled.Science, listOf(ix), "recipient-lab-$labId"))
        }
        add(Recipient(stringResource(R.string.recipient_patient), Icons.Filled.PhoneAndroid, written, "recipient-patient"))
    }
    val current = recipients.getOrElse(selected) { recipients.last() }
    val noSignature = settings?.hasSignature != true

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                title = { Text(stringResource(R.string.review_title, info.patient.name)) },
            )
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.width(360.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.review_who_gets_what), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                recipients.forEachIndexed { i, r ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selected = i }.testTag(r.tag),
                        colors = CardDefaults.cardColors(containerColor = if (i == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(r.icon, null, tint = SamfordBlue)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(r.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (r.docs.isEmpty()) stringResource(R.string.review_nothing_to_send)
                                    else r.docs.map { "${docTitle(it)} (${it.pageIds.size})" }.joinToString(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                val skipped = state.docs.filter { !editor.hasContent(it.docId) }
                if (skipped.isNotEmpty()) {
                    Text(stringResource(R.string.review_not_sent, skipped.map { docTitle(it) }.joinToString()), style = MaterialTheme.typography.bodySmall)
                }
                if (ix != null && ix.labs.isEmpty()) Warning(stringResource(R.string.review_warn_no_lab), WarnAmber)
                if (rx != null && info.patient.hasAllergies) Warning(stringResource(R.string.review_warn_allergy, info.patient.allergyText), AlertRed)
                if (written.isEmpty()) Warning(stringResource(R.string.review_warn_empty), AlertRed)
                if (noSignature) {
                    Warning(stringResource(R.string.review_warn_signature), AlertRed)
                    TextButton(onClick = { vm.navigate(Screen.Signature) }) { Text(stringResource(R.string.signature_title)) }
                }

                Text(stringResource(R.string.review_follow_up), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FollowUp.entries.forEach { f ->
                        FilterChip(selected = followUp == f, onClick = { session.followUp.value = f }, label = { Text(followUpLabel(f)) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.review_lock_note), style = MaterialTheme.typography.bodySmall)
                if (completing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = { confirm = true },
                    enabled = written.isNotEmpty() && !noSignature && !completing,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("complete-visit"),
                ) { Text(stringResource(R.string.action_sign_complete)) }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                Text(stringResource(R.string.review_exactly_what, current.label), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val pages = current.docs.flatMap { d -> d.pageIds.indices.map { d to it } }
                if (pages.isEmpty()) {
                    Text(stringResource(R.string.review_nothing_to_send))
                } else {
                    // A fresh row per recipient, so each list starts at its first document.
                    key(selected) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize().testTag("review-pages")) {
                        items(pages, key = { (d, i) -> "${d.docId}-$i" }) { (d, i) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PageThumbnail(
                                    session.pageSpec(info, d, i, previewSignature = true),
                                    editor.pagesOf(d.docId)[i],
                                    Modifier.fillMaxHeight(0.94f).aspectRatio(1000f / 1414f).border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)),
                                    widthPx = 900,
                                    mode = RenderMode.EXPORT,
                                )
                                Text("${docTitle(d)} · ${stringResource(R.string.page_number, i + 1)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    if (confirm) {
        val doneText = stringResource(R.string.visit_completed_message)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.complete_confirm_title)) },
            text = { Text(stringResource(R.string.complete_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirm = false
                        completing = true
                        scope.launch {
                            val problem = session.complete()
                            completing = false
                            if (problem != null) {
                                vm.say(problem)
                            } else {
                                vm.leaveVisit(visitId)
                                vm.say(doneText)
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm-complete"),
                ) { Text(stringResource(R.string.action_sign_complete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Warning(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

@Composable
private fun docTitle(doc: EditorDoc): String = if (doc.isAddendum) stringResource(R.string.doc_addendum) else docLabel(doc.type)

@Composable
private fun followUpLabel(f: FollowUp): String = when (f) {
    FollowUp.NONE -> stringResource(R.string.follow_none)
    FollowUp.DAYS_3 -> stringResource(R.string.follow_3d)
    FollowUp.WEEK_1 -> stringResource(R.string.follow_1w)
    FollowUp.WEEKS_2 -> stringResource(R.string.follow_2w)
    FollowUp.MONTH_1 -> stringResource(R.string.follow_1m)
}
