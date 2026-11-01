package com.cardesk.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * 原厂设置目录（第九十五轮 C4）的行为测试。
 *
 * 只测「目录怎么拼出来」这一层：注入一个假的 Settings Activity 的 intent-filter，
 * 断言语义化 id/文案/可打开性；不依赖真机 `com.ecarx.settings`。
 */
@RunWith(RobolectricTestRunner::class)
class SystemSettingsCatalogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SystemSettingsCatalog.invalidate()
    }

    @Test
    fun 注入的系统设置入口会被扫描到() {
        val pm = context.packageManager
        val shadow = shadowOf(pm)
        val component = ComponentName("com.android.settings", "com.android.settings.Settings")
        // Robolectric 的 addActivityIfNotPresent 默认 exported=false；真机设置页都是导出的，
        // 这里用 addOrUpdateActivity 直接注入一个 exported 的 ActivityInfo。
        shadow.addOrUpdateActivity(
            android.content.pm.ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                exported = true
                enabled = true
            },
        )
        shadow.addIntentFilterForActivity(component, IntentFilter(android.provider.Settings.ACTION_SETTINGS))
        val resolved = pm.queryIntentActivities(Intent(android.provider.Settings.ACTION_SETTINGS), 0)
        assertTrue("Robolectric 应能解析注入的 filter，count=${resolved.size}", resolved.isNotEmpty())
        val entries = SystemSettingsCatalog.warmNow(context)

        val settings = entries.firstOrNull { it.id == "settings" }
        assertNotNull("应扫到系统设置入口", settings)
        assertEquals("系统设置", settings!!.label)
        assertEquals("com.android.settings", settings.intent.component?.packageName)
        assertTrue("扫描后应标记为已预热", SystemSettingsCatalog.isWarmed())
    }

    @Test
    fun 解析不到的入口不会出现在目录里() {
        // 没有任何注入：Robolectric 的包管理器里通常没有 Settings Activity
        val ids = SystemSettingsCatalog.warmNow(context).map { it.id }
        assertFalse("未安装的入口不能凭空出现", ids.contains("drive_mode"))
    }

    @Test
    fun 入口表本身合法() {
        val specs = SystemSettingsCatalog.specs()
        assertTrue(specs.size >= 10)
        assertEquals("id 不能重复", specs.size, specs.map { it.id }.distinct().size)
        assertTrue("每条都要有中文名", specs.all { it.label.isNotBlank() })
        assertTrue(
            "每条至少要有 action 或组件之一",
            specs.all { it.actions.isNotEmpty() || it.component != null },
        )
    }

    @Test
    fun 打开未知入口返回失败而不是崩溃() {
        assertFalse(SystemSettingsCatalog.openById(context, "no_such_entry"))
    }

    @Test
    fun 驾驶模式胶囊优先打开原厂驾驶模式页() {
        val component = ComponentName("com.ecarx.settings", "com.ecarx.settings.drivemode.DriveModeActivity")
        shadowOf(context.packageManager).addOrUpdateActivity(
            android.content.pm.ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                exported = true
                enabled = true
            },
        )
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        assertTrue("组件存在时应直接拉起", SystemSettingsCatalog.openDriveMode(activity))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("应发出 startActivity", started)
        assertEquals(component, started!!.component)
    }

    @Test
    fun 没有原厂驾驶模式页时返回false交给兜底() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        assertFalse("模拟器没有 com.ecarx.settings 时应返回 false", SystemSettingsCatalog.openDriveMode(activity))
    }
}
