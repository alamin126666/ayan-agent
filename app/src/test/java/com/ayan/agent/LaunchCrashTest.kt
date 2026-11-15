package com.ayan.agent

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Reproduces the reported crash: the app exits by itself right after
 * opening. Launches MainActivity on the JVM, lets the Loading screen
 * (1.4 s delay + DataStore setup check) run, and fails with the real
 * exception if anything in the launch path throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchCrashTest {

    @Test
    fun appLaunchesAndReachesSetupWithoutCrashing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // Let the main looper run: Loading delay (1.4 s) + navigation to Setup.
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            Thread.sleep(200)
        }
        var alive = false
        scenario.onActivity { activity ->
            alive = !activity.isFinishing && !activity.isDestroyed
            assertNotNull("MainActivity must exist after launch", activity)
        }
        assertFalse("MainActivity finished/destroyed right after launch", !alive)
        scenario.close()
    }
}
