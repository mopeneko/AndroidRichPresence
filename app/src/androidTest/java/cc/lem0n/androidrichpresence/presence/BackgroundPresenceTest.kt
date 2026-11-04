package cc.lem0n.androidrichpresence.presence

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.test.platform.app.InstrumentationRegistry
import cc.lem0n.androidrichpresence.youtube.YouTubeNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackgroundPresenceTest {
    @Test fun savedLinkPublishesWithoutLaunchingActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backgroundPresence") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        val launches = AtomicInteger()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { launches.incrementAndGet() }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            instrumentation.runOnMainSync {
                PresenceRuntime.get(context)
                val component = ComponentName(context, YouTubeNotificationListener::class.java)
                if (Build.VERSION.SDK_INT >= 34) NotificationListenerService.requestUnbind(component)
                NotificationListenerService.requestRebind(component)
            }
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val screen = PresenceStore.screen.value
                val expected = screen.playback.presence()
                if (screen.connected && screen.auth == Auth.Ready && expected != null &&
                    screen.delivery == Delivery.Accepted(expected)) break
                SystemClock.sleep(250)
            }
            val screen = PresenceStore.screen.value
            assertEquals("No Activity may launch during background restore", 0, launches.get())
            assertTrue("Notification listener must reconnect", screen.connected)
            assertEquals("Saved Discord link must restore without UI", Auth.Ready, screen.auth)
            val expected = screen.playback.presence()
            assertTrue("Prepare a playing or paused source with sharing enabled", expected != null)
            assertEquals("Current media must be accepted by Discord", Delivery.Accepted(expected!!), screen.delivery)
            val observeSeconds = InstrumentationRegistry.getArguments()
                .getString("backgroundObserveSeconds", "0").toLong()
            require(observeSeconds in 0..600)
            SystemClock.sleep(observeSeconds * 1000)
            val restored = PresenceStore.screen.value
            assertEquals("No Activity may launch during observation", 0, launches.get())
            assertEquals("Background connection must recover", Auth.Ready, restored.auth)
            assertEquals("Current media must be accepted after observation",
                Delivery.Accepted(restored.playback.presence()!!), restored.delivery)
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}
