package com.cardesk.launcher.core.media

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「桌面启动后唤起默认媒体源 App 并让它播放」的**执行层**（第149轮）。
 *
 * ## 为什么要单独测执行层
 *
 * `MediaAppLauncherTest` 测的是纯判定（该不该做），
 * 这里测的是**真的做不做** —— 最要命的是「用户明明关了开关，
 * 开机还是弹出音乐 App」。
 *
 * 之所以敢把开关判断放在 `launchAndPlay` **里面**，
 * 就是因为每条调用路径都过这道门；这条测试就是那道门的守门员。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class MediaAppLauncherLaunchTest {

    /** QQ音乐车机版（第147轮真机验证过的那个包）。 */
    private val qqPkg = "com.tencent.qqmusiccar"

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    /**
     * 在 Robolectric 的包管理器里「装」一个 App，并给它一个可启动的 Activity。
     *
     * ## 两个坑
     *
     * ① `applicationInfo` 必须自己填 —— Robolectric 的 `getApplicationInfo`
     *    在它为 null 时会抛 `NameNotFoundException`，
     *    而生产代码里的「装没装」判定正是走这个 API。
     * ② 光 `addActivityIfNotPresent` 不够 ——
     *    `getLaunchIntentForPackage` 内部是 `queryIntentActivities`，
     *    得再补一个 `ACTION_MAIN + CATEGORY_LAUNCHER` 的 filter 才解析得出来。
     *    （和 `DesignParityRenderTest` 里装假包的做法一样。）
     */
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
    fun `开关关着时不启动任何 Activity`() {
        val activity = activity()
        install(activity, qqPkg)

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = false,
            sourceId = "qq",
        )

        assertNull("开关关着就不能启动 App", pkg)
        assertTrue(
            "开关关着时一个 Activity 都不该被启动（实际启动 ${startedIntents(activity).size} 个）",
            startedIntents(activity).isEmpty(),
        )
    }

    @Test
    fun `开关开着且装了对应 App 时才启动`() {
        val activity = activity()
        install(activity, qqPkg)

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = true,
            sourceId = "qq",
        )

        assertEquals("应该拉起 QQ音乐车机版", qqPkg, pkg)
        val started = startedIntents(activity)
        assertEquals("应该启动 1 个 Activity", 1, started.size)
        assertEquals(
            "启动的应该是目标 App",
            qqPkg,
            started.first().component?.packageName,
        )
    }

    @Test
    fun `没装对应 App 时不启动`() {
        val activity = activity()
        // 什么都不装

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = true,
            sourceId = "qq",
        )

        assertNull("没装就不该有包名", pkg)
        assertTrue(
            "没装对应 App 时不该启动任何 Activity",
            startedIntents(activity).isEmpty(),
        )
    }

    @Test
    fun `通用音乐不启动`() {
        val activity = activity()

        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = true,
            sourceId = MediaSources.GENERIC_ID,
        )

        assertNull("通用音乐没有对应 App 可拉", pkg)
        assertTrue(
            "通用音乐不该启动任何 Activity",
            startedIntents(activity).isEmpty(),
        )
    }

    @Test
    fun `开关判断放在函数内部_调用方忘了判也拦得住`() {
        val activity = activity()
        install(activity, qqPkg)

        // 这一条专门盯「开关判断只写在调用方」这种写法：
        // 调用方一旦漏判，开关就形同虚设。
        val pkg = MediaAppLauncher.launchAndPlay(
            context = activity,
            autoLaunchEnabled = false,
            sourceId = "qq",
        )
        assertNull("内部必须自己判一次开关", pkg)
        assertTrue(startedIntents(activity).isEmpty())
    }
}
