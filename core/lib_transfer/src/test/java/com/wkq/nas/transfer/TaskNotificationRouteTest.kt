package com.wkq.nas.transfer

import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import android.content.Intent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskNotificationRouteTest {
    @After fun resetRoute() { TaskNotificationRoute.intentFactory = null }
    @Test fun notificationHasExplicitImmutableApplicationRouteWithoutBusinessData() {
        val app = RuntimeEnvironment.getApplication()
        TaskNotificationRoute.intentFactory = { context -> Intent().setClassName(context.packageName, "test.host.TaskActivity") }
        val pending = requireNotNull(TaskNotificationRoute.pendingIntent(app))
        val intent = Shadows.shadowOf(pending).savedIntent
        assertEquals(app.packageName, intent.component!!.packageName)
        assertEquals("test.host.TaskActivity", intent.component!!.className)
        assertTrue(intent.getBooleanExtra(TaskNotificationRoute.EXTRA_TASKS, false))
        assertEquals(setOf(TaskNotificationRoute.EXTRA_TASKS), intent.extras!!.keySet())
        assertTrue(Shadows.shadowOf(pending).isImmutable)
    }
    @Test fun headlessHostCanRunTransfersWithoutNotificationClickRoute() {
        val app = RuntimeEnvironment.getApplication()
        assertNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
        assertNull(TaskNotificationRoute.pendingIntent(app))
    }
}
