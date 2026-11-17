package com.cardesk.launcher.core.media

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「后台起播」的执行层（第一九四轮）。
 *
 * ## 这条测试锁住的是「**后台起播时一个 Activity 都不该被启动**」
 *
 * 这是用户提的那个需求本身：
 * 「不把音乐 App 唤醒到前台，在后台直接播放」。
 *
 * 所以断言不能是「最终有没有播上」（那要真机），
 * 而是「**有没有把音乐 App 拉到前台**」——
 * 这正是后台通道与前台降级路径的分界线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class BackgroundPlaybackLaunchTest {

    private val neteasePkg = "com.netease.cloudmusic.iot"
    private val qqPkg = "com.tencent.qqmusiccar"

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    /** 在 Robolectric 里「装」一个 App 并给它可启动的 Activity。 */
    private fun install(activity: Activity, pkg: String) {
        val pm = shadowOf(activity.packageManager)
        pm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    packageName = pkg
                    name = pkg
                }
                versionName = "1.0"
            },
        )
        val component = android.content.ComponentName(pkg, "$pkg.MainActivity")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            },
        )
    }

    private fun startedIntents(activity: Activity): List<Intent> =
        shadowOf(activity).nextStartedActivity?.let { listOf(it) } ?: emptyList()

    @Test
    fun `后台通道可用时不启动任何 Activity`() {
        val activity = activity()
        install(activity, neteasePkg)

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = true,
            sourceId = "netease",
        )

        assertEquals("应该走网易云", neteasePkg, pkg)
        assertTrue(
            "后台起播时**一个 Activity 都不该被启动**（实际启动 ${startedIntents(activity).size} 个）",
            startedIntents(activity).isEmpty(),
        )
    }

    @Test
    fun `没有后台通道的播放器退回拉前台`() {
        val activity = activity()
        install(activity, qqPkg)

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = true,
            sourceId = "qq",
        )

        assertEquals("应该走 QQ音乐", qqPkg, pkg)
        val started = startedIntents(activity)
        assertEquals("QQ 音乐没有后台通道，必须退回拉前台", 1, started.size)
        assertEquals(
            "拉起的应该是目标 App",
            qqPkg,
            started.first().component?.packageName,
        )
    }

    @Test
    fun `开关关着时后台和前台都不动作`() {
        val activity = activity()
        install(activity, neteasePkg)

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = false,
            sourceId = "netease",
        )

        assertEquals("开关关着就不该有包名", null, pkg)
        assertTrue(
            "开关关着时一个 Activity 都不该被启动",
            startedIntents(activity).isEmpty(),
        )
    }

    @Test
    fun `通用音乐既不后台也不前台`() {
        val activity = activity()

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = true,
            sourceId = MediaSources.GENERIC_ID,
        )

        assertEquals(null, pkg)
        assertTrue(startedIntents(activity).isEmpty())
    }
}
