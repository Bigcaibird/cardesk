package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.MainActivity
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
 * 设置页**排版**的效果图（第六十轮）。
 *
 * 这一轮按用户要求做了两件事：
 *  1. 设置项之间别那么紧凑（行高 92→104、行间距 37→52）；
 *  2. 去掉标题下那条绝对定位的分割线（它落进第一行，跟行分割线叠成「两根横线」）。
 *
 * 产物：`preview/x01..x05-settings-*.png`（夜间五页）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutRenderTest {

    private val dir = File("../preview").apply { mkdirs() }

    @Test
    fun 渲染设置页五页() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        call(launcher!!, "showSettings")
        shadowOf(Looper.getMainLooper()).idle()
        val overlay = findField<SettingsOverlay>(launcher, "settings")
        assertNotNull("设置页应该打开了", overlay)

        shot(decor, overlay!!, "boot", "x01-settings-boot.png")
        shot(decor, overlay, "dock", "x02-settings-dock.png")
        shot(decor, overlay, "daynight", "x03-settings-daynight.png")
        shot(decor, overlay, "diagnostics", "x04-settings-diagnostics.png")
        // 第六十八轮：「开通免激活」页改成了「开源与声明」页
        shot(decor, overlay, "about", "x05-settings-about.png")
        // 第六十九轮：「在线更新」页改成检测 GitHub Release
        shot(decor, overlay, "update", "x07-settings-update.png")
        // 第六十九轮：局域网传输目录改到公共 Download
        shot(decor, overlay, "lan", "x09-settings-lan.png")
        // 第七十轮：新增「氛围灯」页（频谱预览 + 颜色设置）
        shot(decor, overlay, "ambient", "x10-settings-ambient.png")
        // 第114轮：「首页中央歌词 · 字号缩放」滑杆（当时在「仪表歌词」页里）
        // 第137轮：那一页拆成独立的「桌面歌词」页，页面 key 从 layout 变成 desktoplyric。
        shot(decor, overlay, "desktoplyric", "y29-settings-lyric-scale.png")
        // 第137轮：新增「仪表歌词」页（副屏投射），与桌面歌词彻底分开。
        shot(decor, overlay, "clusterlyric", "y31-settings-cluster-lyric.png")
        // 第137轮：胎压 / 车模 / 数据源从「仪表歌词」页搬到「车辆信息」页。
        shot(decor, overlay, "carinfo", "y32-settings-carinfo.png")
        // 第115轮：滑杆上面那条**歌词预览条**（在页面下方，要滚一点才看得到）
        overlay.showPageKey("desktoplyric")
        shadowOf(Looper.getMainLooper()).idle()
        findField<android.widget.ScrollView>(overlay, "bodyScroll")?.scrollTo(0, 700)
        shadowOf(Looper.getMainLooper()).idle()
        val previewBmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(previewBmp))
        FileOutputStream(File(dir, "y30-settings-lyric-preview.png"))
            .use { previewBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // —— 滚到底：看「桌面歌词」页的末尾
        // （第137轮：原先这一页底部的「胎压 / 车模编号 / 车辆数据源」
        //  已经搬去「车辆信息」页了，见上面的 y32-settings-carinfo.png）
        val scroll = findField<android.widget.ScrollView>(overlay, "bodyScroll")
        scroll?.scrollTo(0, 100000)
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, "x06-settings-about-bottom.png"))
            .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(decor: View, overlay: SettingsOverlay, key: String, name: String) {
        overlay.showPageKey(key)
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 在线更新对话框（第六十九轮重写）的效果图。
     *
     * 对话框**不会自己发请求**（检测手动触发），所以这里渲染是安全的。
     */
    @Test
    fun 渲染在线更新对话框() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        layout(decor)
        val dialog = com.cardesk.launcher.ui.dialogs.UpdateDialog(activity, com.cardesk.launcher.core.Palettes.night)
        (decor as ViewGroup).addView(dialog, ViewGroup.LayoutParams(1920, 1080))
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        FileOutputStream(File(dir, "x08-update-dialog.png"))
            .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun layout(decor: View) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private inline fun <reified T> findField(target: Any, name: String): T? {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as? T
    }

    private fun call(target: Any, method: String, vararg args: Any?): Any? {
        val types = args.map {
            when (it) {
                is Int -> Int::class.java
                is Boolean -> Boolean::class.java
                is String -> String::class.java
                else -> Any::class.java
            }
        }.toTypedArray()
        val m = LauncherView::class.java.getDeclaredMethod(method, *types)
        m.isAccessible = true
        return m.invoke(target, *args)
    }
}
