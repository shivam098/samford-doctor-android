package com.samford.doctor.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samford.doctor.testing.FakeHospital
import com.samford.doctor.testing.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SignInPersonasTest {
    private fun awaitMain(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun serverDoctorsReplaceTheOfflineListWhenTheServerComesOnlineLater() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hospital = FakeHospital().apply { online = false }
        val c = Fixtures.container(context, hospital)
        runBlocking { c.settings.setManualUrl("http://fake.local") }

        // The sign-in screen's own server check fails (network not up yet), so it shows offline demo doctors.
        val vm = AppViewModel(c)
        awaitMain("first persona list") { vm.personas.value != null }
        assertFalse(vm.personas.value!!.fromServer)

        // Another check (app start-up, "Check again", rediscovery) then reaches the server.
        hospital.online = true
        runBlocking { c.server.refresh() }
        assertTrue(c.server.isOnline)
        awaitMain("server doctors") { vm.personas.value?.fromServer == true }
        assertEquals(listOf("doctor-a", "doctor-b"), vm.personas.value!!.doctors.map { it.personaId })
    }
}
