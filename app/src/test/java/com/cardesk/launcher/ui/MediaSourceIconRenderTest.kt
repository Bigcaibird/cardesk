package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.media.MediaSourceIconKind
import com.cardesk.launcher.core.media.MediaSources
import com.cardesk.launcher.core.media.MediaSourceIconKind as IconKind
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.widget.MediaSourceIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 媒体源**图标**（第 125 轮）的像素级断言。
 *
 * ## 为什么测「形状」而不是「有没有东西」
 *
 * 来源行原来是一个品牌色小圆点。如果只断言「图标区域有墨」，
 * 那根圆点也能过 —— 断言就没管住任何东西。
 *
 * 所以这里断言的是**每个符号自己的特征部位**：
 *
 * - **音符**：右边有符干（竖着），左边没有；下方有符头。
 * - **蓝牙**：右上有折角，左边整片是空的（蓝牙 rune 的特征）。
 * - **USB**：顶部是方块（实心），左下角是圆点。
 *
 * 这三条合起来才认得出「画的是哪个符号」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaSourceIconRenderTest {

    /** 品牌色判墨的容差（曼哈顿距离，和像素差一个量级）。 */
    private val BRAND_TOLERANCE = 120

    // --------------------------------------------------------------- 纯逻辑

    @Test
    fun `三种自绘符号都有各自的特征部位`() {
        // 音符：符干在右边，符头在下方。
        val note = draw(IconKind.NOTE)
        assertTrue("音符的符干应该有墨", painted(note, 105, 95))
        assertTrue("音符的符头应该有墨", painted(note, 100, 109))
        assertTrue("音符左上方应该没有墨", !painted(note, 100, 95))

        // 蓝牙：右上有折角，左边整片是空的。
        val bt = draw(IconKind.BLUETOOTH)
        assertTrue("蓝牙右上折角应该有墨", painted(bt, 112, 92))
        assertTrue("蓝牙左边应该有竖线", painted(bt, 100, 100))
        assertTrue("蓝牙左边整片应该没墨", !painted(bt, 88, 100))

        // USB：顶部方块（实心）+ 左下角圆点。
        val usb = draw(IconKind.USB)
        assertTrue("USB 顶部方块应该有墨", painted(usb, 100, 86))
        assertTrue("USB 左下圆点应该有墨", painted(usb, 88, 111))
        assertTrue("USB 支线应该有墨", painted(usb, 93, 102))
    }

    @Test
    fun `三个符号画出来不一样`() {
        val note = draw(IconKind.NOTE)
        val bt = draw(IconKind.BLUETOOTH)
        val usb = draw(IconKind.USB)
        val a = inkCount(note)
        val b = inkCount(bt)
        val c = inkCount(usb)
        assertTrue("三个符号的墨迹量不该完全一样：note=$a bt=$b usb=$c", a != b || b != c)
    }

    // --------------------------------------------------------------- 音乐卡片

    @Test
    fun `音乐卡片来源行画出了图标`() {
        val page = ScenePage(RuntimeEnvironment.getApplication())
        page.mediaSource = "通用音乐"
        page.mediaSourceId = "generic"
        page.mediaSourceIconKind = IconKind.NOTE
        val bmp = render(page)
        val anchor = page.sourceAnchorRect
        assertNotNull("应该有来源行的命中区", anchor)
        val box = anchor!!

        // 图标框：命中区左缘就是图标左缘（两者共用 x+14k），
        // 垂直方向以命中区顶部往下 2~22 为准（k≈1，误差留足）。
        val brand = MediaSources.brandColor("通用音乐")
        var brandInIcon = 0
        for (y in (box.top + 2).toInt()..(box.top + 22).toInt()) {
            for (x in box.left.toInt()..(box.left + 18).toInt()) {
                if (isBrandInk(bmp, x, y, brand)) brandInIcon++
            }
        }
        assertTrue(
            "来源行的图标区域应该有图标（实际只有 $brandInIcon 个品牌色像素）",
            brandInIcon >= 10,
        )

        // 关键区分点：**音符的符干**在图标框的右上（圆心右 0.28r、往上 0.88r）。
        //
        // 为什么非要钉这一处：来源行原来那个品牌色**小圆点**（半径 4、圆心在
        // 命中区左缘 +8）也落在这个「图标区域」里 —— 只断言「区域里有墨」的话，
        // 圆点也能过，断言就没管住任何东西。
        //
        // 符干在**圆点上方**（y ≈ 命中区顶部 +6，圆点只到 +8），
        // 所以这一条能把「图标」和「圆点」分开。
        assertTrue(
            "音符符干的位置应该有墨（圆点的位置画不出这里）",
            isBrandInk(bmp, (box.left + 10).toInt(), (box.top + 6).toInt(), brand),
        )
    }

    @Test
    fun `真实App图标会画在来源行上`() {
        val page = ScenePage(RuntimeEnvironment.getApplication())
        page.mediaSource = "QQ音乐"
        page.mediaSourceId = "qq"
        page.mediaSourceIconKind = IconKind.APP
        // 塞一张 64×64 的假图标（纯品红），看它有没有被画到来源行上。
        val icon = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(icon).drawColor(0xFFFF00FF.toInt())
        page.mediaSourceIcon = icon
        val bmp = render(page)
        val anchor = page.sourceAnchorRect!!
        var magenta = 0
        for (y in (anchor.top).toInt()..(anchor.top + 26).toInt()) {
            for (x in anchor.left.toInt()..(anchor.left + 20).toInt()) {
                val p = bmp.getPixel(x, y)
                if ((p shr 16) and 0xFF > 200 && (p shr 8) and 0xFF < 80 && (p and 0xFF) > 200) magenta++
            }
        }
        assertTrue("真实 App 图标应该被画在来源行上（实际只有 $magenta 个品红像素）", magenta >= 20)
    }

    // --------------------------------------------------------------- 设置页

    @Test
    fun `设置页可以选默认媒体源`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val prefs = Prefs(activity)
        prefs.defaultMusicSource = "generic"
        val overlay = com.cardesk.launcher.ui.SettingsOverlay(activity, prefs, com.cardesk.launcher.core.Palettes.night)

        // 切到「系统诊断」页（媒体来源那一节在那页）。
        val navRowsField = com.cardesk.launcher.ui.SettingsOverlay::class.java
            .getDeclaredField("navRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val navRows = navRowsField.get(overlay) as Map<String, TextView>
        val row = navRows["diagnostics"]
        assertNotNull("左栏应该有「系统诊断」", row)
        row!!.performClick()

        // 找到「QQ音乐」那个可点的 chip（下面的只读列表里的同名行不可点）。
        val chip = findClickable(overlay, "QQ音乐")
        assertNotNull("设置页应该有可选的媒体源 chip", chip)
        chip!!.performClick()

        assertEquals("选了 QQ音乐，默认源就该是 qq", "qq", prefs.defaultMusicSource)
    }

    // --------------------------------------------------------------- 默认源生效

    @Test
    fun `通用音乐作为默认源时不锁包`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val launcher = findLauncher(activity.window.decorView)
        assertNotNull("应该有 LauncherView", launcher)
        assertNull(
            "通用音乐不锁任何包",
            launcher!!.applyDefaultMediaSource("generic"),
        )
    }

    @Test
    fun `默认源没装时降级成不锁包`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val launcher = findLauncher(activity.window.decorView)
        assertNotNull("应该有 LauncherView", launcher)
        // 模拟器 / Robolectric 里没装 QQ音乐 —— 必须降级，不能崩、也不能锁一个不存在的包。
        assertNull(
            "没装的来源不该锁包",
            launcher!!.applyDefaultMediaSource("qq"),
        )
    }

    // --------------------------------------------------------------- 工具

    /** 把某个自绘符号画到一张透明位图上（中心 100,100，半径 20）。 */
    private fun draw(kind: IconKind): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        MediaSourceIcons.drawFallback(Canvas(bmp), kind, 100f, 100f, 20f, 0xFF33C6B4.toInt())
        return bmp
    }

    /** 渲染 ScenePage 到 1920×1080。 */
    private fun render(page: ScenePage): Bitmap {
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(bmp))
        return bmp
    }

    /**
     * 这个像素是**图标墨**吗（足够接近来源品牌色）。
     *
     * 为什么不用「alpha > 0」：音乐卡的**卡片底色本身就不透明**，
     * 用 alpha 判据的话整个卡片都是「画过的」，断言恒真 —— 圆点也能过。
     * 必须按**颜色**判，才分得出「品牌色的图标」和「深色的卡片底」。
     */
    private fun isBrandInk(bmp: Bitmap, x: Int, y: Int, brand: Int): Boolean {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
        val p = bmp.getPixel(x, y)
        if ((p ushr 24) == 0) return false
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val br = (brand shr 16) and 0xFF
        val bg = (brand shr 8) and 0xFF
        val bb = brand and 0xFF
        return abs(r - br) + abs(g - bg) + abs(b - bb) <= BRAND_TOLERANCE
    }

    /** 这个像素被画过吗（alpha > 0）。只在**透明画布**上用。 */
    private fun painted(bmp: Bitmap, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
        return (bmp.getPixel(x, y) ushr 24) != 0
    }

    private fun inkCount(bmp: Bitmap): Int {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (painted(bmp, x, y)) n++
        return n
    }

    /** 在窗口里找出 LauncherView。 */
    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 递归找出文案为 [text] 且**可点**的 TextView（只读列表里的同名行不可点）。 */
    private fun findClickable(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text && root.isClickable) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findClickable(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
