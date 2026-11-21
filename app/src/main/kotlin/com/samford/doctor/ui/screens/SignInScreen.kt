package com.samford.doctor.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.R
import com.samford.doctor.data.repo.ServerStatus
import com.samford.doctor.ink.InkFormat
import com.samford.doctor.ink.InkStroke
import com.samford.doctor.model.Doctor
import com.samford.doctor.render.StrokePainter
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.StatusPill
import com.samford.doctor.ui.SyntheticBanner
import com.samford.doctor.ui.theme.AlertRed
import com.samford.doctor.ui.theme.SamfordBlue
import com.samford.doctor.ui.theme.SuccessGreen
import kotlin.math.roundToInt

@Composable
fun SignInScreen(vm: AppViewModel) {
    val personas by vm.personas.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var manualUrl by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SyntheticBanner(Modifier.fillMaxWidth())
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = SamfordBlue, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.signin_tagline), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                ServerCard(server, manualUrl, { manualUrl = it }, onSave = { vm.setManualServer(manualUrl) }, onRetry = { vm.loadPersonas() })
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.signin_demo_note), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.signin_choose_doctor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                personas?.message?.let { Text(it, color = AlertRed) }
                Spacer(Modifier.height(12.dp))
                val list = personas
                if (list == null) {
                    Text(stringResource(R.string.loading))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list.doctors, key = { it.personaId }) { doctor ->
                            PersonaCard(doctor, online = list.fromServer, onClick = { vm.signIn(doctor, offlineDemo = !list.fromServer) })
                        }
                        if (list.fromServer) {
                            item {
                                TextButton(onClick = { vm.signIn(list.doctors.first().copy(isDemo = true), offlineDemo = true) }) {
                                    Text(stringResource(R.string.signin_use_offline))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaCard(doctor: Doctor, online: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("persona-${doctor.personaId}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(doctor.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${doctor.qualification} · ${doctor.department}", style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.reg_no, doctor.registrationNo), style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(
                if (online) stringResource(R.string.signin_server_persona) else stringResource(R.string.signin_offline_persona),
                if (online) SuccessGreen else SamfordBlue,
            )
        }
    }
}

@Composable
fun ServerCard(status: ServerStatus, manualUrl: String, onUrl: (String) -> Unit, onSave: () -> Unit, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when (status) {
                is ServerStatus.Online -> StatusPill(stringResource(R.string.server_online, status.apiBase), SuccessGreen)
                is ServerStatus.Unreachable -> Text(status.message, color = AlertRed)
                ServerStatus.Checking -> Text(stringResource(R.string.server_checking))
                ServerStatus.Unknown -> Text(stringResource(R.string.server_unknown))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = manualUrl,
                onValueChange = onUrl,
                label = { Text(stringResource(R.string.server_manual_label)) },
                placeholder = { Text("http://192.168.1.20:4320") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.server_qr_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = manualUrl.isNotBlank()) { Text(stringResource(R.string.server_connect)) }
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.server_retry)) }
            }
        }
    }
}

/** One-time signature capture; applied to every completed document with time and Reg. No. */
@Composable
fun SignatureScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var pad by remember { mutableStateOf<SignaturePadView?>(null) }
    var hasInk by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.signature_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.signature_hint), style = MaterialTheme.typography.bodyLarge)
        settings?.doctor?.let { Text("${it.name} · ${stringResource(R.string.reg_no, it.registrationNo)}") }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .aspectRatio(3f)
                .border(2.dp, SamfordBlue, RoundedCornerShape(12.dp))
                .background(Color.White, RoundedCornerShape(12.dp)),
        ) {
            AndroidView(
                factory = { ctx -> SignaturePadView(ctx).also { v -> pad = v; v.onChanged = { hasInk = v.strokes.isNotEmpty() } } },
                modifier = Modifier.fillMaxSize().testTag("signature-pad"),
            )
            HorizontalDivider(Modifier.align(Alignment.BottomCenter).padding(horizontal = 40.dp, vertical = 36.dp), color = Color(0xFFB0BEC5))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { pad?.clear(); hasInk = false }) { Text(stringResource(R.string.signature_clear)) }
            Button(onClick = { pad?.let { vm.saveSignature(it.strokes.toList()) } }, enabled = hasInk) { Text(stringResource(R.string.signature_save)) }
        }
    }
}

/**
 * Signature pad: pen or finger, stored as samford-ink/1 strokes in the 600 x 200 unit signature
 * box, drawn with the canonical painter so it looks identical on every page.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class SignaturePadView(context: Context) : View(context) {
    val strokes = ArrayList<InkStroke>()
    var onChanged: () -> Unit = {}
    private val current = ArrayList<Int>()
    private var wet: InkStroke? = null
    private var downTime = 0L
    private val paint = Paint()

    private fun scale(): Float = width / (InkFormat.SIGNATURE_W_CU / 100f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = scale()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current.clear()
                downTime = event.eventTime
                add(event, s)
            }
            MotionEvent.ACTION_MOVE -> add(event, s)
            MotionEvent.ACTION_UP -> {
                add(event, s)
                if (current.size >= InkFormat.STRIDE) {
                    strokes += InkStroke("sig-${strokes.size + 1}", InkStroke.TOOL_PEN, "#1B1B1F", 260, current.toIntArray())
                }
                current.clear()
                onChanged()
            }
            MotionEvent.ACTION_CANCEL -> current.clear()
        }
        wet = if (current.size >= InkFormat.STRIDE) InkStroke("wet", InkStroke.TOOL_PEN, "#1B1B1F", 260, current.toIntArray()) else null
        invalidate()
        return true
    }

    private fun add(e: MotionEvent, s: Float) {
        val stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        for (h in 0..e.historySize) {
            val x = if (h < e.historySize) e.getHistoricalX(h) else e.x
            val y = if (h < e.historySize) e.getHistoricalY(h) else e.y
            val t = if (h < e.historySize) e.getHistoricalEventTime(h) else e.eventTime
            val p = if (h < e.historySize) e.getHistoricalPressure(h) else e.pressure
            current += (x / s * 100).roundToInt().coerceIn(0, InkFormat.SIGNATURE_W_CU)
            current += (y / s * 100).roundToInt().coerceIn(0, InkFormat.SIGNATURE_H_CU)
            current += (t - downTime).toInt()
            current += if (stylus) (p.coerceIn(0f, 1f) * 1000).roundToInt() else InkFormat.NO_VALUE
            current += InkFormat.NO_VALUE
            current += InkFormat.NO_VALUE
        }
    }

    fun clear() {
        strokes.clear()
        current.clear()
        wet = null
        invalidate()
        onChanged()
    }

    override fun onDraw(canvas: Canvas) {
        val s = scale()
        canvas.save()
        canvas.scale(s, s)
        strokes.forEach { StrokePainter.draw(canvas, it, paint) }
        wet?.let { StrokePainter.draw(canvas, it, paint) }
        canvas.restore()
    }
}
