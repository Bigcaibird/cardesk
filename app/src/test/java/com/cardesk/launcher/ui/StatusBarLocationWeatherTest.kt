package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.ui.shell.ShellStatusBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 状态栏「位置 + 天气」（第四轮新增）+ **沉浸式缩放**（第四十三轮）的单测。
 *
 * 覆盖四件事：
 *
 * 1. **数据口径**：[ShellStatusBar.setLocationWeather] 收下城市 / 天气 / 温度，
 *    城市会去掉首尾空格；重复同样的数据不会反复刷（避免日志噪音）。
 * 2. **画得出来**：**右侧图标行**那一段（定位针 + 城市 + 天气图标 + 温度）
 *    真的有像素；**屏幕正中不再有它**（第八十二轮挪走了，正中改放翻页圆点）。
 * 3. **没数据也不崩**：城市为空显示「位置获取中」、温度为空显示 `--°`。
 * 4. **第四十三轮 / 第五十二轮 / 第六十三轮**：顶部渐变遮罩（沉浸式）+
 *    状态栏元素整体缩小（第四十三轮缩一档、第五十二轮按用户要求统一缩到 **2/3**），
 *    第六十三轮再按用户要求**把行内的字与图标放大一档**，
 *    但**状态栏这一行的大小（顶边距 / 行高 / 左右边距）一个字都不动**。
 *
 * ---
 * **为什么统计的是「内容像素」而不是「不透明像素」**
 *
 * 第四十三轮给状态栏加了一条**顶部渐变遮罩**（黑色 → 透明，96px 高）。
 * 这层遮罩铺满整个宽度，所以「这块区域有没有不透明像素」已经测不出
 * 「内容画没画在这里」了 —— 遮罩自己就贡献了一堆半透明像素。
 *
 * 遮罩是**纯黑**（RGB 全 0，只有 alpha 在变），而状态栏里的内容
 * （白字 / 蓝色头像 / 灰色分隔点）RGB 都不为 0。
 * 所以统计 **r+g+b > 0** 的像素 = 「真正画了内容的地方」，与遮罩无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarLocationWeatherTest {

    private fun newBar(): ShellStatusBar {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        return ShellStatusBar(activity)
    }

    private fun render(bar: ShellStatusBar): Bitmap {
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        bar.layout(0, 0, 1920, 1080)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bmp))
        return bmp
    }

    /** 统计一块区域里「不透明」的像素数。 */
    private fun painted(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if ((bmp.getPixel(x, y) ushr 24) != 0) n++
            }
        }
        return n
    }

    /** 统计一块区域里「画了内容」的像素数（排除纯黑渐变遮罩）。 */
    private fun content(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var n = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val p = bmp.getPixel(x, y)
                if (Color.red(p) + Color.green(p) + Color.blue(p) > 0) n++
            }
        }
        return n
    }

    /** 单个像素上「画了内容」吗。 */
    private fun contentAt(bmp: Bitmap, x: Int, y: Int): Boolean {
        val p = bmp.getPixel(x, y)
        return Color.red(p) + Color.green(p) + Color.blue(p) > 0
    }

    @Test
    fun `位置天气数据能落到状态栏`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        assertEquals("北京市", bar.locationText)
        assertEquals("晴", bar.weatherSky)
        assertEquals(14, bar.weatherTemp)
    }

    @Test
    fun `城市首尾空格会被去掉`() {
        val bar = newBar()
        bar.setLocationWeather("  北京市  ", "晴", 14)
        assertEquals("北京市", bar.locationText)
    }

    @Test
    fun `重复同样的数据不会覆盖成新值`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        bar.setLocationWeather("北京市", "晴", 14)
        assertEquals("北京市", bar.locationText)
        assertEquals(14, bar.weatherTemp)
    }

    @Test
    fun `位置天气挪到右侧图标行且正中没有它`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        val bmp = render(bar)
        // 第八十二轮：位置 + 天气**从中置挪到右侧图标行**（和蓝牙/信号/时钟在一起），
        // 屏幕正中改放翻页指示圆点（那个在 PageDotsView 里，不在本 View）。
        //
        // 第三百轮：这一行整体上移到 ROW_TOP=5 / ROW_H=33（中心 y=21.5），
        // 量像素必须跟着这一行的实际范围走，不能写死 y30..90。
        val top = ShellStatusBar.ROW_TOP.toInt()
        val bottom = (ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H).toInt()
        // 右侧：位置 + 天气（裸元素）排在连接图标左边。
        val right = content(bmp, 1200, top, 1800, bottom)
        assertTrue("右侧应该画着位置+天气，实际 $right", right > 0)
        // 正中必须**空出来**：翻页点只占 x870..906，左右两侧都不该有位置/天气。
        val midLeft = content(bmp, 620, top, 840, bottom)
        assertEquals("状态栏正中左侧不该有位置/天气，实际 $midLeft", 0, midLeft)
        val midRight = content(bmp, 1000, top, 1250, bottom)
        assertEquals("状态栏正中右侧不该有位置/天气，实际 $midRight", 0, midRight)
        // 更左边（空白区）同样不能有
        val left = content(bmp, 400, top, 620, bottom)
        assertEquals("位置/天气不该画到左边，实际 $left", 0, left)
    }

    @Test
    fun `没有数据时用占位文案且不崩`() {
        val bar = newBar()
        bar.setLocationWeather("", "", null)
        val bmp = render(bar)
        assertEquals("", bar.locationText)
        val top = ShellStatusBar.ROW_TOP.toInt()
        val bottom = (ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H).toInt()
        // 占位文案（「位置获取中」/ `--°`）也必须落在右侧这一行里。
        assertTrue(content(bmp, 1200, top, 1800, bottom) > 0)
    }

    // ---------------------------------------------------------------
    // 第八十二轮：命中矩形必须与绘制一一对应
    // ---------------------------------------------------------------

    /**
     * 点驾驶模式胶囊要能打开空调面板。
     *
     * **第八十二轮修掉的既有 bug**：以前位置/天气胶囊和驾驶模式胶囊
     * **共用同一个 RectF**，而画顺序是「驾驶模式胶囊 → 位置胶囊 → 天气胶囊」，
     * 所以那块 RectF 最后停在**天气胶囊**上：
     * - 点驾驶模式胶囊 → `contains` 判的是天气胶囊的位置 → **点不开**；
     * - 点天气胶囊 → **误开**空调面板。
     *
     * 现在三个胶囊各有各的矩形（头像 / 驾驶模式 / 位置 / 天气），
     * 命中判定和绘制一一对应。
     */
    @Test
    fun `点驾驶模式胶囊会触发回调`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        var assist = 0
        var avatar = 0
        bar.onAssist = { assist++ }
        bar.onAvatar = { avatar++ }
        val bmp = render(bar)

        // 驾驶模式胶囊在头像右边（x 81 起），这里取一个肯定落在它里面的点
        var hitX = -1
        for (x in 85..260) {
            if (Color.red(bmp.getPixel(x, 38)) + Color.green(bmp.getPixel(x, 38)) +
                Color.blue(bmp.getPixel(x, 38)) > 0
            ) {
                hitX = x
                break
            }
        }
        assertTrue("应该在驾驶模式胶囊上找到有内容的像素", hitX > 0)

        tap(bar, hitX.toFloat(), 38f)
        assertEquals("点驾驶模式胶囊应该触发 onAssist", 1, assist)
        assertEquals("不应该误触发 onAvatar", 0, avatar)
    }

    /**
     * 点右侧的位置/天气胶囊**不能**误开空调面板。
     *
     * 这条在旧代码下会挂：那时命中判定用的矩形停在天气胶囊上，
     * 点天气胶囊正好会误触发 [ShellStatusBar.onAssist]。
     */
    @Test
    fun `点右侧天气胶囊不会误触发驾驶模式回调`() {
        val bar = newBar()
        bar.setLocationWeather("北京市", "晴", 14)
        var assist = 0
        var avatar = 0
        bar.onAssist = { assist++ }
        bar.onAvatar = { avatar++ }
        render(bar)

        // 右侧图标行里的位置/天气胶囊（在连接图标左边、时钟更右边）
        tap(bar, 1500f, 38f)
        assertEquals("点天气胶囊不该开空调面板", 0, assist)
        assertEquals("点天气胶囊不该触发行人", 0, avatar)
    }

    /**
     * 超长城市名要**截断**，否则它会一路顶到状态栏正中的翻页圆点上。
     *
     * 背景：位置 + 天气是**从右往左**排的，城市名越长，位置胶囊的左边界越靠左。
     * 模拟器的默认地名就有 26 个字符（`Redwood Valley-Potter Valley`），
     * 真机上遇到更长的地名就可能压到圆点。
     *
     * 断言依据：翻页圆点的玻璃条占 x 916~1004（`PageDotsView`），
     * 所以「位置胶囊的左边界必须 > 1050」（圆点右边还留 46px 间隙）。
     */
    @Test
    fun `超长城市名会被截断且不挤到正中的圆点`() {
        val bar = newBar()
        bar.setLocationWeather("A".repeat(200), "晴", 14)
        val bmp = render(bar)

        // 从圆点右边开始往右找第一个有内容的像素 —— 那就是位置段的左边界。
        // 第三百轮：行中心是 ROW_TOP + ROW_H/2，不能再按旧的 y=38 扫。
        val cy = (ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H / 2f).toInt()
        var first = -1
        // 城市名最多占 LOC_MAX_W=260，整段右对齐到连接图标左边 ⇒ 左边界不会早于 ~1400。
        for (x in 1005..1600) {
            if (contentAt(bmp, x, cy)) {
                first = x
                break
            }
        }
        assertTrue("应该能找到位置胶囊的左边界", first > 0)
        assertTrue("位置胶囊不应该挤到正中的翻页圆点上，实际从 x=$first 开始", first > 1050)
    }

    /** 模拟一次点击（DOWN + UP）。 */
    private fun tap(bar: ShellStatusBar, x: Float, y: Float) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        bar.dispatchTouchEvent(down)
        down.recycle()
        val up = MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_UP, x, y, 0)
        bar.dispatchTouchEvent(up)
        up.recycle()
    }

    // ---------------------------------------------------------------
    // 第四十三轮：沉浸式 + 状态栏整体缩小一档
    // ---------------------------------------------------------------

    @Test
    fun `顶部不再有渐变遮罩`() {
        val bar = newBar()
        val bmp = render(bar)

        // 第四十九轮「浮屿」：状态栏**不再画顶部渐变遮罩**
        // （第四十三轮那个半透明黑渐变属于「沉浸式」时期的设计，已被本轮推翻）。
        assertEquals("顶部正中必须完全透明", 0, Color.alpha(bmp.getPixel(960, 0)))
        assertEquals("整条宽度都不能有遮罩", 0, Color.alpha(bmp.getPixel(5, 0)))
        // 空白区（驾驶模式胶囊右侧、位置胶囊左侧）也必须没有任何遮罩残留
        assertEquals("遮罩之下更不能有残留", 0, Color.alpha(bmp.getPixel(400, 48)))
    }

    // ---------------------------------------------------------------
    // 第六十三轮：行内元素（字 + 图标）放大一档，**行本身大小不动**
    // ---------------------------------------------------------------

    @Test
    fun `第六十三轮行高不变但字和图标更大`() {
        val bar = newBar()
        val bmp = render(bar)

        // 第三百轮按设计稿重排：边距 29、顶边距 5、行高 33（行底 38）。
        assertEquals(29f, com.cardesk.launcher.ui.shell.ShellStatusBar.EDGE, 0.01f)
        assertEquals(5f, com.cardesk.launcher.ui.shell.ShellStatusBar.ROW_TOP, 0.01f)
        assertEquals(33f, com.cardesk.launcher.ui.shell.ShellStatusBar.ROW_H, 0.01f)

        // 左上设置入口：34×34 玻璃方块，在行内垂直居中（y 4.5~38.5）
        assertEquals(34f, com.cardesk.launcher.ui.shell.ShellStatusBar.AVATAR, 0.01f)
        val avatarRows = (0 until 80).count { contentAt(bmp, 45, it) }
        assertTrue("头像应该占满约 34 行（34×34），实际 $avatarRows", avatarRows in 33..35)
        assertTrue("头像应该画在 x=29~63 / y=4.5~38.5", contentAt(bmp, 45, 21))
        assertFalse("头像右边（间距 12）不该有内容", contentAt(bmp, 66, 21))
        assertFalse("头像不该纵向超过 y=38.5", contentAt(bmp, 45, 41))

        // 驾驶胶囊仍然高 34，从 x=75（29 + 34 + 12）开始
        assertEquals(34f, com.cardesk.launcher.ui.shell.ShellStatusBar.CHIP_H, 0.01f)
        assertTrue("驾驶模式胶囊应该画在 x=75 之后", content(bmp, 76, 6, 300, 38) > 0)

        // 时钟字号 17（第三百轮定稿）：墨迹行数按字号收缩
        assertEquals(17f, com.cardesk.launcher.ui.shell.ShellStatusBar.CLOCK_TEXT_SIZE, 0.01f)
        val clockRows = (0 until 80).count { y -> (1805..1885).any { contentAt(bmp, it, y) } }
        assertTrue("时钟字号 17px 的墨迹行数应该在 11~15 之间，实际 $clockRows", clockRows in 11..15)

        // 连接图标 22 → 18
        assertEquals(18f, com.cardesk.launcher.ui.shell.ShellStatusBar.ICON, 0.01f)
    }
}
