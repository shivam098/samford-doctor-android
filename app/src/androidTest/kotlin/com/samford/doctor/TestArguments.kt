package com.samford.doctor

import androidx.test.platform.app.InstrumentationRegistry
import com.samford.doctor.ui.write.WetInkMode

/**
 * CI runs the device suite per configuration: samfordWetInk = auto (shipping behaviour),
 * lowlatency (force Jetpack Ink) or standard (force the canvas path).
 */
fun wetInkModeArgument(): WetInkMode = when (InstrumentationRegistry.getArguments().getString("samfordWetInk")?.lowercase()) {
    "lowlatency" -> WetInkMode.LOW_LATENCY
    "standard" -> WetInkMode.STANDARD
    else -> WetInkMode.AUTO
}

/**
 * Waits until the device has (or, with [online] false, no longer has) a network with internet.
 * Airplane mode takes several seconds to actually drop Wi-Fi on the emulator (run 44890765533:
 * 6 s), and a server check in between still succeeds.
 */
fun awaitInternet(online: Boolean, timeoutMs: Long = 120_000) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() < deadline) {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (hasInternet == online) return
        Thread.sleep(250)
    }
    org.junit.Assert.fail(if (online) "the emulator has no network" else "airplane mode did not take the emulator offline")
}
