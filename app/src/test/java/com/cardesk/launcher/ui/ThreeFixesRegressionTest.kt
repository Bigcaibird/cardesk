package com.cardesk.launcher.ui

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.cluster.ClusterLyricsService
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.SceneQuickApps
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.pages.ScenePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 本轮三项需求回归单测：
 * 1. 应用抽屉已禁用应用排在应用列表尾部（可用应用在前、禁用应用在后，组内保序）；
 * 2. 桌面「快捷应用」卡片槽位模型（更换/排序/清空/重置）及全尺寸绘制不溢出（160px..700px）；
 * 3. 仪表 Overlay 歌词开关关闭时同步更新状态、拆窗停服，onStartCommand 不再返回 START_STICKY。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class ThreeFixesRegressionTest {

    @Test
    fun `DrawerPage places disabled apps at the tail of the list`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val pm = shadowOf(ctx.packageManager)

        fun registerApp(pkg: String, label: String, enabled: Boolean) {
            val appInfo = ApplicationInfo().apply {
                packageName = pkg
                name = label
                this.enabled = enabled
            }
            val actInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.MainActivity"
                applicationInfo = appInfo
                this.enabled = enabled
            }
            val pkgInfo = PackageInfo().apply {
                packageName = pkg
                applicationInfo = appInfo
                activities = arrayOf(actInfo)
            }
            pm.installPackage(pkgInfo)
            val resolve = ResolveInfo().apply {
                activityInfo = actInfo
                nonLocalizedLabel = label
            }
            pm.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                resolve,
            )
            ctx.packageManager.setApplicationEnabledSetting(
                pkg,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.DONT_KILL_APP,
            )
        }

        // 故意让禁用应用的名称在字母序上排最前面（"AAA..."）
        registerApp("com.test.aaa.disabled", "AAA 禁用应用", enabled = false)
        registerApp("com.test.mmm.enabled", "MMM 可用应用", enabled = true)
        registerApp("com.test.bbb.disabled", "BBB 禁用应用", enabled = false)
        registerApp("com.test.zzz.enabled", "ZZZ 可用应用", enabled = true)

        Skin.apply(Palettes.night)
        val drawer = DrawerPage(ctx, Palettes.night)
        drawer.reload()
        shadowOf(Looper.getMainLooper()).idle()

        @Suppress("UNCHECKED_CAST")
        val pkgs = DrawerPage::class.java.getDeclaredField("currentPackages")
            .apply { isAccessible = true }
            .get(drawer) as List<String>

        val testPkgs = pkgs.filter { it.startsWith("com.test.") }
        assertEquals(
            "可用应用应排在前面，已禁用应用应统一排在列表尾部",
            listOf(
                "com.test.mmm.enabled",
                "com.test.zzz.enabled",
                "com.test.aaa.disabled",
                "com.test.bbb.disabled",
            ),
            testPkgs,
        )
    }

    @Test
    fun `SceneQuickApps supports slot replacement deduplication swap and reset`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        val store = SceneQuickApps.PrefsStore(prefs)
        store.resetDefault()
        assertFalse(store.isConfigured())

        var slots = SceneQuickApps.empty()
        slots = SceneQuickApps.withSlot(slots, 0, "com.app.a")
        slots = SceneQuickApps.withSlot(slots, 2, "com.app.b")
        assertEquals(listOf("com.app.a", "", "com.app.b", ""), slots)

        // 同包名放到槽位 3 时自动清空旧槽位 0，保留其余槽位位置
        slots = SceneQuickApps.withSlot(slots, 3, "com.app.a")
        assertEquals(listOf("", "", "com.app.b", "com.app.a"), slots)

        // 交换槽位 2 和 3
        slots = SceneQuickApps.swap(slots, 2, 3)
        assertEquals(listOf("", "", "com.app.a", "com.app.b"), slots)

        store.save(slots)
        assertTrue(store.isConfigured())
        assertEquals(slots, store.load())

        store.resetDefault()
        assertFalse(store.isConfigured())
    }

    @Test
    fun `ScenePage drawQuick never overflows across all widths from 160px to 700px`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        Skin.apply(Palettes.night)
        val page = ScenePage(ctx)
        page.setQuickApps(
            listOf(
                ScenePage.QuickApp("com.a", "高德地图车机版", null),
                ScenePage.QuickApp("com.b", "QQ音乐车机版", null),
                ScenePage.QuickApp("", "", null),
                ScenePage.QuickApp("com.d", "电话", null),
            ),
        )
        val drawQuick = ScenePage::class.java.getDeclaredMethod("drawQuick", Canvas::class.java, RectF::class.java)
            .apply { isAccessible = true }
        val hitsField = ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }

        val widths = listOf(160f, 190f, 215f, 225f, 260f, 277f, 338f, 422f, 560f, 700f)
        val bmp = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        try {
            for (w in widths) {
                @Suppress("UNCHECKED_CAST")
                val hits = hitsField.get(page) as MutableMap<String, RectF>
                hits.clear()
                val rect = RectF(20f, 20f, 20f + w, 20f + 150f)
                drawQuick.invoke(page, canvas, rect)

                assertEquals("宽度 $w 下卡片右上角不应再显示编辑入口", null, hits["quickConfig"])

                val slotRects = (0 until 4).map { idx ->
                    val r = hits["quick$idx"]
                    assertNotNull("宽度 $w 下槽位 $idx 应有命中区", r)
                    assertTrue("宽度 $w 下槽位 $idx 不得超出卡片右/左边界：$r vs $rect", r!!.left >= rect.left && r.right <= rect.right)
                    assertTrue("宽度 $w 下槽位 $idx 不得超出卡片上/下边界：$r vs $rect", r.top >= rect.top + 32f && r.bottom <= rect.bottom)
                    r
                }
                // 任意两个槽位互不重叠
                for (i in slotRects.indices) {
                    for (j in i + 1 until slotRects.size) {
                        assertFalse(
                            "宽度 $w 下槽位 $i 与 $j 不应重叠：${slotRects[i]} vs ${slotRects[j]}",
                            RectF.intersects(slotRects[i], slotRects[j]),
                        )
                    }
                }
                // 极窄宽度 (<220px) 自动切 2×2 网格，确保上下两行分布
                if (w < 220f) {
                    assertTrue("宽度 $w 应启用 2×2 布局", slotRects[2].top > slotRects[0].bottom)
                } else {
                    assertEquals("宽度 $w 应为单行等高布局", slotRects[0].top, slotRects[3].top, 0.5f)
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `ClusterLyricsService switch off synchronously stops service and returns START_NOT_STICKY`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.clusterOverlayEnabled = true

        val controller = Robolectric.buildService(ClusterLyricsService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(ctx, ClusterLyricsService::class.java), 0, 1))

        // 模拟用户在设置页关闭「仪表 Overlay 歌词」开关
        prefs.clusterOverlayEnabled = false
        ClusterLyricsService.apply(ctx)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("关闭后状态报告应立刻变为「已关闭」", "已关闭", ClusterLyricsService.lastReport)
        assertTrue("关闭后服务应立刻调用 stopSelf", shadowOf(service).isStoppedBySelf)

        // 即使有迟到的 onStartCommand，关闭态下也必须返回 START_NOT_STICKY
        val ret = service.onStartCommand(Intent(ctx, ClusterLyricsService::class.java), 0, 2)
        assertEquals("关闭态 onStartCommand 不得返回 START_STICKY", Service.START_NOT_STICKY, ret)
    }

    @Test
    fun `SettingsOverlay lyric page toggles cluster overlay switch cleanly on and off`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val prefs = Prefs(ctx)
        prefs.clusterOverlayEnabled = false
        Skin.apply(Palettes.night)

        val overlay = SettingsOverlay(ctx, prefs, Palettes.night)
        overlay.showPageKey("lyric")
        shadowOf(Looper.getMainLooper()).idle()

        fun findAllTextViews(root: View): List<TextView> {
            val out = ArrayList<TextView>()
            if (root is TextView) out.add(root)
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) out.addAll(findAllTextViews(root.getChildAt(i)))
            }
            return out
        }

        // 进入歌词页时「当前投射状态」不再为空
        val statusTv = findAllTextViews(overlay).firstOrNull { it.text.toString().startsWith("状态：") }
        assertNotNull("进入歌词页应立即展示当前投射状态", statusTv)

        // 找到「仪表 Overlay 歌词」所在行并点击开启 -> 再点击关闭
        val labelTv = findAllTextViews(overlay).first { it.text.toString() == "仪表 Overlay 歌词" }
        val rowHolder = labelTv.parent.parent as View
        rowHolder.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("点击开启后 clusterOverlayEnabled 应为 true", prefs.clusterOverlayEnabled)

        rowHolder.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("再次点击关闭后 clusterOverlayEnabled 应为 false", prefs.clusterOverlayEnabled)
        assertTrue(
            "关闭后状态文本应立刻刷新为已关闭（实际：${statusTv!!.text}）",
            statusTv.text.toString().contains("已关闭"),
        )
    }
}
