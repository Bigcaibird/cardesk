package com.cardesk.launcher.core

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 原车最近任务压制（第三百一十九轮）。
 *
 * 真车实测：Home 拦截回调返回 `true` 也拦不住框架自己的 double-tap-home，
 * 车机的最近任务会紧跟着我们的压上来。这里把「什么时候该压制、什么时候必须
 * 恢复」钉成可测的规则 —— 尤其是「包不存在时不能崩」「上次压过必须能恢复」，
 * 后者关系到用户会不会永久丢掉车机自己的最近任务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OemRecentsSuppressorTest {

    private lateinit var context: android.content.Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = Prefs(context)
        prefs.setFlag(HomeKeyRuntime.KEY_ENABLED, true)
        prefs.setFlag(HomeKeyRuntime.KEY_DOUBLE_RECENTS, true)
        prefs.setFlag(OemRecentsSuppressor.KEY_APPLIED, false)
        HomeKeyRuntime.interceptorActive = true
    }

    @After
    fun tearDown() {
        HomeKeyRuntime.interceptorActive = false
        prefs.setFlag(OemRecentsSuppressor.KEY_APPLIED, false)
    }

    private fun installTarget() {
        val pkg = OemRecentsSuppressor.TARGET_PACKAGE
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    name = pkg
                }
                versionName = "1.0"
            },
        )
    }

    @Test
    fun `目标包不存在时只记状态，不写压制标记`() {
        OemRecentsSuppressor.suppress(context, prefs)
        assertFalse(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, true))
        assertTrue(OemRecentsSuppressor.statusText.contains("未安装"))
    }

    @Test
    fun `目标包存在时压制并记住状态`() {
        installTarget()
        OemRecentsSuppressor.suppress(context, prefs)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            context.packageManager.getApplicationEnabledSetting(OemRecentsSuppressor.TARGET_PACKAGE),
        )
        assertTrue(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, false))
        assertTrue(OemRecentsSuppressor.statusText.startsWith("已压制"))
    }

    @Test
    fun `恢复后组件回到默认状态并清掉标记`() {
        installTarget()
        OemRecentsSuppressor.suppress(context, prefs)
        OemRecentsSuppressor.release(context, prefs)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getApplicationEnabledSetting(OemRecentsSuppressor.TARGET_PACKAGE),
        )
        assertFalse(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, true))
        assertEquals("未压制", OemRecentsSuppressor.statusText)
    }

    @Test
    fun `启动兜底会把上次没恢复的压制恢复掉`() {
        installTarget()
        OemRecentsSuppressor.suppress(context, prefs)
        assertTrue(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, false))
        // 模拟「上次进程被杀、这次冷启动」：先按上次状态恢复。
        OemRecentsSuppressor.restoreIfNeeded(context, prefs)
        assertFalse(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, true))
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getApplicationEnabledSetting(OemRecentsSuppressor.TARGET_PACKAGE),
        )
    }

    @Test
    fun `接管没生效时 sync 不压制`() {
        installTarget()
        HomeKeyRuntime.interceptorActive = false
        OemRecentsSuppressor.sync(context, prefs)
        assertFalse(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, true))
    }

    @Test
    fun `关掉双击映射后 sync 会把车机最近任务放回去`() {
        installTarget()
        OemRecentsSuppressor.sync(context, prefs)
        assertTrue(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, false))
        prefs.setFlag(HomeKeyRuntime.KEY_DOUBLE_RECENTS, false)
        OemRecentsSuppressor.sync(context, prefs)
        assertFalse(prefs.flag(OemRecentsSuppressor.KEY_APPLIED, true))
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getApplicationEnabledSetting(OemRecentsSuppressor.TARGET_PACKAGE),
        )
    }
}
