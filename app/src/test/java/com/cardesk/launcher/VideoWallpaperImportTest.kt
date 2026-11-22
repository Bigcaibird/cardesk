package com.cardesk.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import com.cardesk.launcher.core.VideoPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * 第218轮：**视频壁纸导入的选择器降级链**（MainActivity 上的端到端）。
 *
 * ## 复现的是什么
 *
 * 实车诊断包里的真实现场：
 * ```
 * 视频壁纸：收到选视频结果 resultCode=0 uri=null
 * 视频壁纸：进入导入处理 uri=null
 * ```
 * 用户看到的是「**没有可用的视频浏览器**」。
 *
 * 根因：领克车机上 `ACTION_OPEN_DOCUMENT` + 视频通配类型**没有 app 接管**
 * ⇒ 旧代码直接 `startActivityForResult` ⇒ 抛 `ActivityNotFoundException` ⇒ 弹错误提示。
 *
 * ## 这里验三件事
 *
 * 1. **一个选择器都没有** ⇒ 不抛异常，改弹「车机没有视频选择器，请用文件管理器…」；
 * 2. **有选择器** ⇒ 拉起的是**第一级**（精确的视频通配 `OPEN_DOCUMENT`），
 *    而不是一上来就退到「不限类型」那一级；
 * 3. 降级链每级都试过才放弃（不是第一级失败就摆烂）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class VideoWallpaperImportTest {

    private fun activity(): MainActivity {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return a
    }

    /** 给某个 intent 注册一个「有人能接」的 resolve info。 */
    private fun addPicker(activity: MainActivity, intent: Intent) {
        val ri = ResolveInfo()
        ri.activityInfo = ActivityInfo().apply {
            packageName = "com.test.picker"
            name = "com.test.picker.PickVideoActivity"
        }
        shadowOf(activity.packageManager).addResolveInfoForIntent(intent, ri)
    }

    /** 明确声明「这个 intent **没人接**」（清掉默认注册）。 */
    private fun clearPicker(activity: MainActivity, intent: Intent) {
        shadowOf(activity.packageManager).setResolveInfosForIntent(intent, emptyList())
    }

    /**
     * 车机上**一个视频选择器都没有** ⇒
     * 不抛 `ActivityNotFoundException`，改弹有替代路径的明确提示。
     */
    @Test
    fun `没有可用选择器时给明确提示而不是抛异常`() {
        val activity = activity()

        // 前提：三条 action 都明确声明「没人接」
        VideoPicker.strategies().forEach { strategy ->
            clearPicker(activity, VideoPicker.buildIntent(strategy))
        }

        activity.startVideoWallpaperImport()
        shadowOf(Looper.getMainLooper()).idle()

        val toast = ShadowToast.getTextOfLatestToast()
        assertNotNull("应该弹提示而不是静默失败", toast)
        assertTrue(
            "提示文案应说明车机没有选择器并给出替代路径，实际=$toast",
            toast.contains("没有视频选择器") && toast.contains("文件管理器"),
        )
    }

    /**
     * 有选择器 ⇒ 拉起**第一级**（`OPEN_DOCUMENT` + 视频通配）。
     *
     * 这正是车机上「能选视频但界面比手机简陋」的那种文件浏览器该走的路。
     */
    @Test
    fun `有选择器时拉起第一级的视频通配选择器`() {
        val activity = activity()
        val first = VideoPicker.strategies().first()
        addPicker(activity, VideoPicker.buildIntent(first))

        activity.startVideoWallpaperImport()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("应该拉起了选择器", started)
        assertEquals(first.action, started.intent.action)
        assertEquals("video/*", started.intent.type)
        assertEquals(
            // 对应 MainActivity 里的 `REQ_VIDEO_WALLPAPER`（private，只能写字面量）
            0x5102,
            started.requestCode,
        )
    }

    /**
     * 只有 `GET_CONTENT` 有人接（`OPEN_DOCUMENT` 没人接）⇒ 降级到第三级。
     *
     * ## ⚠️ 为什么不能拿「不限类型」那一级来测降级
     *
     * 真机 Android（含 Robolectric）的 intent 匹配是**按 MIME 通配**的：
     * 注册了「不限类型」的 app **也会**接管「视频通配」的查询
     * ⇒ 只要有 app 接 `OPEN_DOCUMENT`，第一级就一定能解析出来。
     *
     * ⇒ 降级链真正起作用的分界是 **action**
     * （`OPEN_DOCUMENT` → `GET_CONTENT`），
     * 也就是「有些车机只注册了 `GET_CONTENT`」的那种情况。
     */
    @Test
    fun `只有GET_CONTENT有人接时降级到第三级`() {
        val activity = activity()
        val strategies = VideoPicker.strategies()
        val third = strategies[2]
        assertEquals(Intent.ACTION_GET_CONTENT, third.action)

        // 前两级明确没人接
        clearPicker(activity, VideoPicker.buildIntent(strategies[0]))
        clearPicker(activity, VideoPicker.buildIntent(strategies[1]))
        // 只有第三级有人接
        addPicker(activity, VideoPicker.buildIntent(third))

        activity.startVideoWallpaperImport()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("应该降级拉起选择器", started)
        assertEquals(Intent.ACTION_GET_CONTENT, started.intent.action)
        assertEquals("video/*", started.intent.type)
    }
}
