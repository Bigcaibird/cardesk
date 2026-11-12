package com.cardesk.launcher.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 应用抽屉「禁用 / 启用」（第九十四轮）效果图。
 *
 * 1. `preview/y23-drawer-disabled.png`：禁用应用与可用应用同屏（图标压暗 + 已禁用）；
 * 2. `preview/y23-drawer-disable-menu.png`：禁用应用的应用菜单（启用应用）；
 * 3. `preview/y23-drawer-disable-confirm.png`：可用应用的禁用确认弹窗。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerDisablePreviewTest {

    private val dir = File("../preview").apply { mkdirs() }

    private fun <T> findAll(root: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(root)) @Suppress("UNCHECKED_CAST") out.add(root as T)
        if (root is ViewGroup) for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i), type))
        return out
    }

    private fun text(root: View, value: String): TextView =
        findAll(root, TextView::class.java).first { it.text.toString() == value }

    private fun inject(a: MainActivity, pkg: String, label: String, enabled: Boolean) {
        val cls = "$pkg.MainActivity"
        val component = ComponentName(pkg, cls)
        val shadow = shadowOf(a.packageManager)
        shadow.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = pkg
                name = cls
                exported = true
                this.enabled = enabled
                nonLocalizedLabel = label
            },
        )
        shadow.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    /**
     * 找到指定显示名那个**应用格子**（长按弹菜单的宿主）。
     *
     * 第152轮：菜单入口从「右上角 ⋮ 按钮」改成了「**长按图标**」，
     * 所以这里改成往上找带 [DrawerPage.APP_CELL_MARK] 标记的祖先。
     */
    private fun menuButton(page: View, label: String): View {
        var node: View? = text(page, label)
        while (node != null) {
            if (node.tag === DrawerPage.APP_CELL_MARK) return node
            node = node.parent as? View
        }
        error("找不到「$label」格子的图标列")
    }

    private fun shot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun 渲染禁用状态与启用菜单() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        inject(a, "com.ecarx.music", "爱趣听", enabled = true)
        inject(a, "com.autonavi.amapautp.yh", "高德地图", enabled = true)
        inject(a, "com.tencent.wecar", "微信车载", enabled = false)
        inject(a, "com.example.demo", "示例应用", enabled = true)
        Prefs(a.applicationContext).setAppAlias("com.example.demo", "")

        val page = DrawerPage(a, Palettes.night)
        a.setContentView(page)
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("禁用应用应有已禁用标记", findAll(page, TextView::class.java).firstOrNull { it.text == "已禁用" })
        shot(page, "y23-drawer-disabled.png")

        menuButton(page, "微信车载").performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("禁用应用菜单应含启用应用", findAll(page, TextView::class.java).firstOrNull { it.text == "启用应用" })
        shot(page, "y23-drawer-disable-menu.png")
    }

    @Test
    fun 渲禁用确认弹窗() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        inject(a, "com.example.demo", "示例应用", enabled = true)
        Prefs(a.applicationContext).setAppAlias("com.example.demo", "")

        val page = DrawerPage(a, Palettes.night)
        a.setContentView(page)
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()

        menuButton(page, "示例应用").performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        text(page, "禁用应用").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("应弹出禁用确认", findAll(page, TextView::class.java).firstOrNull { it.text == "禁用" })
        shot(page, "y23-drawer-disable-confirm.png")
    }
}
