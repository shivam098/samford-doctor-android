package com.samford.doctor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.AppContainer
import com.samford.doctor.R
import com.samford.doctor.ui.screens.AddendumScreen
import com.samford.doctor.ui.screens.QueueScreen
import com.samford.doctor.ui.screens.RecordScreen
import com.samford.doctor.ui.screens.ReviewScreen
import com.samford.doctor.ui.screens.SettingsScreen
import com.samford.doctor.ui.screens.SignInScreen
import com.samford.doctor.ui.screens.SignatureScreen
import com.samford.doctor.ui.screens.SyncScreen
import com.samford.doctor.ui.screens.VisitScreen

@Composable
fun AppRoot(
    vm: AppViewModel,
    container: AppContainer,
    locked: Boolean,
    onUnlock: () -> Unit,
    onRequestNotifications: () -> Unit,
    onFinish: () -> Unit,
) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler {
        val screen = vm.current
        if (screen is Screen.Visit) vm.leaveVisit(screen.visitId) else if (!vm.back()) onFinish()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!ready) {
            Loading()
        } else {
            when (val screen = vm.backStack.lastOrNull()) {
                Screen.SignIn -> SignInScreen(vm)
                Screen.Signature -> SignatureScreen(vm)
                Screen.Queue -> QueueScreen(vm, onRequestNotifications)
                is Screen.Visit -> key(screen.visitId) { VisitScreen(vm, screen.visitId) }
                is Screen.Review -> key(screen.visitId) { ReviewScreen(vm, screen.visitId) }
                Screen.Sync -> SyncScreen(vm, container)
                is Screen.Record -> key(screen.visitId) { RecordScreen(vm, container, screen.visitId) }
                is Screen.Addendum -> key(screen.docId) { AddendumScreen(vm, container, screen.docId) }
                Screen.Settings -> SettingsScreen(vm, container)
                null -> Loading()
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        if (locked) LockOverlay(onUnlock)
    }
}

@Composable
fun Loading(text: String? = null) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        if (text != null) {
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LockOverlay(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.lock_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}

/** Small rounded status label. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun SyntheticBanner(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0xFFFFF3E0))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.synthetic_banner), color = Color(0xFF8A4B00), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
    }
}
