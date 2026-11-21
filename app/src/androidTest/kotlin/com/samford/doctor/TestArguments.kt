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
