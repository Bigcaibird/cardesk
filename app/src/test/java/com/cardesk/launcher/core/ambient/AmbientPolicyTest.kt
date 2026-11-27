package com.cardesk.launcher.core.ambient

import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientPolicyTest {

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.ambientEnabled = false
        prefs.ambientNightOnly = false
        AmbientLightController.onScreenStateChanged(ctx, true)
        AmbientLightController.onDayNightChanged(ctx, false)
    }

    @Test
    fun `screen off suspends ambient light and screen on resumes it`() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).ambientEnabled = true
        AmbientLightController.syncPolicy(ctx)
        assertTrue(AmbientLightController.shouldRun(ctx))

        AmbientLightController.onScreenStateChanged(ctx, false)
        assertFalse("息屏后应关闭氛围灯", AmbientLightController.shouldRun(ctx))

        AmbientLightController.onScreenStateChanged(ctx, true)
        assertTrue("亮屏后应恢复氛围灯", AmbientLightController.shouldRun(ctx))
    }

    @Test
    fun `night only mode disables ambient light in daytime and enables in night`() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.ambientEnabled = true
        prefs.ambientNightOnly = true

        AmbientLightController.onDayNightChanged(ctx, false)
        assertFalse("日间模式下「仅夜间启用」应关闭氛围灯", AmbientLightController.shouldRun(ctx))

        AmbientLightController.onDayNightChanged(ctx, true)
        assertTrue("切换到夜间模式后应自动启用氛围灯", AmbientLightController.shouldRun(ctx))
    }
}
