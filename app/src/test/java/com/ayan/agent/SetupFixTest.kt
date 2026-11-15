package com.ayan.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.ayan.agent.data.InstallResult
import com.ayan.agent.data.RuntimeInstaller
import com.ayan.agent.data.Runtimes
import com.ayan.agent.data.SettingsStore
import com.ayan.agent.ui.screens.SetupViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Setup fix (2026-11-15): on a normal phone there is no Termux, so `pkg`
 * does not exist and every runtime install failed with a generic error.
 * The installer must report NO_PKG_MANAGER, and the user must be able to
 * skip setup — the Chat agent works without local runtimes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupFixTest {

    @Test
    fun installerReportsNoPkgManagerWhenPkgIsMissing() = runBlocking {
        val installer = RuntimeInstaller()
        if (!installer.hasPkgManager()) {
            val result = installer.install(Runtimes.all[0], onOutput = {}, onProgress = {})
            assertEquals(InstallResult.NO_PKG_MANAGER, result)
        }
    }

    @Test
    fun skipSetupMarksSetupDone() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SetupViewModel(app)
        vm.skipSetup()
        val done = runBlocking {
            // Give the ViewModel's coroutine a moment to write the flag.
            kotlinx.coroutines.delay(500)
            SettingsStore(app).setupDone.first()
        }
        assertTrue("skipSetup must mark setup done", done)
    }
}
