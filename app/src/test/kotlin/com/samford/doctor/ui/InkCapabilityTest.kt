package com.samford.doctor.ui

import com.samford.doctor.ui.write.InkCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkCapabilityTest {
    @Test
    fun softwareGlRenderersAreRecognised() {
        // Emulator renderers seen in CI (GL translator and ANGLE on SwiftShader Vulkan).
        assertTrue(InkCapability.isSoftwareRenderer("Android Emulator OpenGL ES Translator (Google SwiftShader)"))
        assertTrue(InkCapability.isSoftwareRenderer("ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)"))
        assertTrue(InkCapability.isSoftwareRenderer("llvmpipe (LLVM 15.0.7, 256 bits)"))
        // Galaxy Tab GPUs.
        assertFalse(InkCapability.isSoftwareRenderer("Adreno (TM) 740"))
        assertFalse(InkCapability.isSoftwareRenderer("Mali-G710"))
        assertFalse(InkCapability.isSoftwareRenderer(null))
    }

    @Test
    fun lowLatencyNeedsFrontBufferAndAHardwareGpu() {
        assertTrue(InkCapability.Report(frontBufferSupported = true, glRenderer = "Adreno (TM) 740").lowLatency)
        assertFalse(InkCapability.Report(frontBufferSupported = false, glRenderer = "Adreno (TM) 740").lowLatency)
        assertFalse(InkCapability.Report(frontBufferSupported = true, glRenderer = "Android Emulator OpenGL ES Translator (Google SwiftShader)").lowLatency)
        assertFalse(InkCapability.Report(frontBufferSupported = true, glRenderer = null).lowLatency)
    }
}
