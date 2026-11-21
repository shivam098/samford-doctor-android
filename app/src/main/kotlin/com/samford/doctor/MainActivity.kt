package com.samford.doctor

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.samford.doctor.ui.AppRoot
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.Screen
import com.samford.doctor.ui.theme.SamfordTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SamfordDoctorApplication).container

    private val appViewModel: AppViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
        }
    }

    private val locked = mutableStateOf(false)
    private var backgroundedAt = 0L
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Recents thumbnails must never show patient names or handwriting.
        setRecentsScreenshotEnabled(false)
        handleIntent(intent)
        setContent {
            SamfordTheme {
                AppRoot(
                    vm = appViewModel,
                    container = container,
                    locked = locked.value,
                    onUnlock = ::promptUnlock,
                    onRequestNotifications = ::requestNotificationPermission,
                    onFinish = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.data?.takeIf { it.scheme == "samford-doctor" && it.host == "connect" }?.let { uri ->
            appViewModel.handleDeepLink(uri.getQueryParameter("api"))
        }
        if (intent.getStringExtra(EXTRA_OPEN) == OPEN_SYNC) appViewModel.openWhenReady(Screen.Sync)
    }

    override fun onStart() {
        super.onStart()
        val lockEnabled = appViewModel.settings.value?.appLock == true
        val away = backgroundedAt == 0L || SystemClock.elapsedRealtime() - backgroundedAt > LOCK_AFTER_MS
        if (lockEnabled && away && canAuthenticate()) {
            locked.value = true
            promptUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun canAuthenticate(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun promptUnlock() {
        if (!canAuthenticate()) {
            locked.value = false
            return
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    locked.value = false
                }
            },
        )
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_SYNC = "sync"
        private const val LOCK_AFTER_MS = 60_000L
    }
}
