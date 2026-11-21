package com.samford.doctor.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.R
import com.samford.doctor.SamfordDoctorApplication
import com.samford.doctor.data.remote.LabResultDto
import com.samford.doctor.model.DocType
import com.samford.doctor.ui.VisitSession
import com.samford.doctor.ui.theme.AlertRed
import com.samford.doctor.ui.theme.SamfordBlue

/** Split view: the returning patient's earlier handwritten pages and lab results. */
@Composable
fun HistoryPane(session: VisitSession, modifier: Modifier) {
    val history by session.history.collectAsStateWithLifecycle()
    var enlarged by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp).testTag("history-pane")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { session.showHistory.value = false }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
        }
        val h = history
        if (h == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        h.message?.let { Text(it, color = AlertRed, style = MaterialTheme.typography.bodySmall) }
        if (h.visits.isEmpty() && h.labResults.isEmpty()) {
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (h.labResults.isNotEmpty()) {
                item { LabResultsCard(h.labResults) }
            }
            items(h.visits, key = { it.key }) { visit ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${visit.date} · ${visit.doctorName}", fontWeight = FontWeight.SemiBold)
                        if (visit.fromTablet) Text(stringResource(R.string.history_from_tablet), style = MaterialTheme.typography.bodySmall)
                        visit.documents.forEach { doc ->
                            Text(
                                docLabel(doc.type) + if (doc.versionCount > 1) " · v${doc.version} (${stringResource(R.string.history_has_addendum)})" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = SamfordBlue,
                            )
                            doc.pageShas.forEach { sha ->
                                HistoryPage(sha, Modifier.fillMaxWidth().aspectRatio(1000f / 1414f).clickable { enlarged = sha })
                            }
                            if (doc.type == DocType.PRESCRIPTION && doc.inkSha256 != null) {
                                OutlinedButton(onClick = { session.repeatPrescription(doc, visit.date) }, modifier = Modifier.testTag("repeat-rx")) {
                                    Icon(Icons.Filled.Repeat, null)
                                    Text(stringResource(R.string.history_repeat_rx))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    enlarged?.let { sha -> EnlargedPage(sha) { enlarged = null } }
}

@Composable
private fun LabResultsCard(results: List<LabResultDto>) {
    Card {
        Column(Modifier.padding(10.dp)) {
            Text(stringResource(R.string.history_lab_results), fontWeight = FontWeight.SemiBold)
            results.forEach { r ->
                val abnormal = r.flag == "ABNORMAL" || r.flag == "CRITICAL"
                Row(Modifier.fillMaxWidth()) {
                    Text(r.testName, modifier = Modifier.weight(1f))
                    Text(
                        listOfNotNull(r.value, r.unit).joinToString(" "),
                        color = if (abnormal) AlertRed else Color.Unspecified,
                        fontWeight = if (abnormal) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                r.refRange?.let { Text(stringResource(R.string.history_ref_range, it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun HistoryPage(sha: String, modifier: Modifier, widthPx: Int = 700) {
    val context = LocalContext.current
    val records = (context.applicationContext as SamfordDoctorApplication).container.records
    val image by produceState<ImageBitmap?>(null, sha) {
        value = records.pageBitmap(sha, widthPx)?.asImageBitmap()
    }
    Box(modifier.background(Color.White).border(1.dp, Color.LightGray), contentAlignment = Alignment.Center) {
        val img = image
        if (img == null) CircularProgressIndicator() else Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun EnlargedPage(sha: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset += pan
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000))) {
            HistoryPage(
                sha,
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .transformable(transform),
                widthPx = 1500,
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = Color.White) }
        }
    }
}
