package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import com.cardesk.launcher.core.Palettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 空调温度表盘（`TempDialView`）的拖拽行为。
 *
 * ## 覆盖的那个坑：**拖表盘完全没有效果**
 *
 * `temperature` 的 setter 里有一句「拖动中不回读覆盖，避免手势被打断」——
 * 本意是挡掉**车机回读**（车端把实际温度下发回来，那个回声会把用户
 * 正在拖的那个值覆盖掉，手感就断了）。
 *
 * 但用户**自己拖出来的值**走的也是同一个 setter，于是被一起挡掉了：
 *
 * ```
 * ACTION_MOVE → temperature = temperatureFor(x, y)
 *                       ↓
 *              setter: if (dragging) return   ← 自己的值也被当成了「车机回声」
 *                       ↓
 *              表盘纹丝不动；抬手时 onTemp(temperature) 报的还是拖之前的旧值
 *                       ↓
 *              宿主算增量 = 目标 − 当前 ≈ 0 → 拖了等于没拖
 * ```
 *
 * 症状是：**按住圆环拖，表盘不动，松手车机也不动**。
 * 只有 ± 两个方块键能用（它们走 `onDelta`，不经过这个 setter）。
 *
 * ## 这轮怎么修的
 *
 * 把「谁在写这个值」拆成两条路：
 *
 * | 谁在写 | 走哪条路 | 拖动中 |
 * |---|---|---|
 * | 车机回读 | `temperature` setter | 挡掉（保住原意） |
 * | 用户拖出来的 | `applyDragTemperature` | **必须放行** |
 *
 * 另外把抬手时的顺序倒过来：**先** `dragging = false`，**再**回调 `onTemp` ——
 * 手指已经抬起来了，这时宿主在回调里回写的温度必须被接受。
 * 之前顺序反了，回写被当成「拖动中的回声」丢掉了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TempDialViewDragTest {

    /** 强调色（换成纯红，断言时好认）。 */
    private val accent = 0xFFFF3B30.toInt()

    /** 圆环轨道色（深灰，和强调色区分得开）。 */
    private val track = 0xFF101216.toInt()

    private val dialWidth = 340
    private val dialHeight = 424

    /** 圆环圆心（和 `TempDialView` 的版式常量一致）。 */
    private val cx = dialWidth / 2f
    private val cy = TempDialView.RING_CY
    private val r = TempDialView.RING_R

    /**
     * 造一个**画过一次**的表盘。
     *
     * 必须先画一次：`−/+` 两个方块键是**画在这个视图里**的
     * （不是子 View），按键热区用的坐标是在 `onDraw` 里算出来的 ——
     * 没画过的话那两个坐标全是 0，热区判断就是错的。
     */
    private fun makeDial(): TempDialView {
        val v = TempDialView(RuntimeEnvironment.getApplication())
        v.palette = Palettes.night.copy(accent = accent, surfaceStrong = track)
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dialWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dialHeight, android.view.View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, dialWidth, dialHeight)
        v.draw(Canvas(Bitmap.createBitmap(dialWidth, dialHeight, Bitmap.Config.ARGB_8888)))
        return v
    }

    /** 送一个触摸事件进去。 */
    private fun touch(v: TempDialView, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        v.onTouchEvent(e)
        e.recycle()
    }

    /**
     * 圆环上被强调色覆盖的比例（0..1）。
     *
     * 强调弧从**正上方**顺时针铺，铺的角度 = `360 × 温度 / 30` ——
     * 所以这个比例直接反映「画出来的那个温度」是多少。
     *
     * 采样点在半径 RING_R 上（描边正中），逐度取一圈；
     * 判定用「红味」而不是精确比色，因为圆头端点有抗锯齿的过渡像素。
     */
    private fun accentArcRatio(v: TempDialView): Double {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        var hit = 0
        var total = 0
        for (deg in 0 until 360) {
            // 0° = 正上方，顺时针 —— 和画弧的方向一致
            val rad = Math.toRadians((deg - 90).toDouble())
            val x = (cx + r * cos(rad)).roundToInt()
            val y = (cy + r * sin(rad)).roundToInt()
            total++
            val p = bmp.getPixel(x, y)
            if (Color.red(p) > 180 && Color.green(p) < 120) hit++
        }
        return hit.toDouble() / total
    }

    // ------------------------------------------------------------------ 拖动

    /**
     * 拖动时温度要**跟着手指走**。
     *
     * 拖到圆环正右方 → 90° → 映射到 16~32℃ 的第 (90+135)/270 处 → 29.33 → 吸附 29.5。
     */
    @Test
    fun 拖动时温度跟着手指走() {
        val v = makeDial()

        // 按在圆环正上方（不是 ± 键那一带）→ 进入拖动
        touch(v, MotionEvent.ACTION_DOWN, cx, cy - r)
        // 拖到圆环正右方
        touch(v, MotionEvent.ACTION_MOVE, cx + r, cy)

        assertEquals(
            "拖到圆环正右方（90°）应该是 29.5℃，实际 ${v.temperature}",
            29.5f,
            v.temperature,
            0.001f,
        )
    }

    /**
     * 拖动时**画出来的**也要跟着走 —— 表盘不能纹丝不动。
     *
     * 这一条是「用户看得见」的那一半：值变了但圆环不动，等于没改。
     * 断言画出来的强调弧比例 = 温度 / 30（设计板口径：30℃ 满格）。
     *
     * 基准写的是**绝对值 29.5℃**（不是 `v.temperature`）——
     * 拿 `v.temperature` 当基准是**自证**：值没变时画出来自然也没变，
     * 断言照样通过，等于没测。
     */
    @Test
    fun 拖动时画出来的圆环弧长跟着变() {
        val v = makeDial()

        touch(v, MotionEvent.ACTION_DOWN, cx, cy - r)
        touch(v, MotionEvent.ACTION_MOVE, cx + r, cy)

        val expected = 29.5f / TempDialView.ARC_TEMP_SPAN
        val actual = accentArcRatio(v)
        assertTrue(
            "圆环弧长没跟着拖动走：期望约 $expected，实际 $actual",
            kotlin.math.abs(actual - expected) < 0.05,
        )
    }

    /**
     * 抬手时上报的必须是**拖出来的那个绝对温度**。
     *
     * 宿主靠它换算增量（`目标 − 当前`）再下发 —— 上报旧值就等于增量 0，
     * 拖了等于没拖。
     */
    @Test
    fun 抬手上报的是拖出来的绝对温度() {
        val v = makeDial()
        var reported: Float? = null
        v.onTemp = { reported = it }

        touch(v, MotionEvent.ACTION_DOWN, cx, cy - r)
        touch(v, MotionEvent.ACTION_MOVE, cx + r, cy)
        touch(v, MotionEvent.ACTION_UP, cx + r, cy)

        assertEquals(
            "抬手上报的温度不是拖出来的那个（宿主会算出 0 增量，拖动就白拖了）",
            29.5f,
            reported ?: -1f,
            0.001f,
        )
    }

    /**
     * 拖动中要**挡住车机回读** —— 这条原意不能丢。
     *
     * 车端把实际温度下发回来的回声会把用户正在拖的值覆盖掉，手感就断了。
     */
    @Test
    fun 拖动中忽略车机回读() {
        val v = makeDial()

        touch(v, MotionEvent.ACTION_DOWN, cx, cy - r)
        touch(v, MotionEvent.ACTION_MOVE, cx + r, cy)

        // 车机在这时候回读了一个别的温度
        v.temperature = 18.5f

        assertEquals(
            "拖动中的车机回读把用户拖出来的值覆盖了（手势会被打断）",
            29.5f,
            v.temperature,
            0.001f,
        )
    }

    /**
     * 抬手之后就要**重新接受**车机回写。
     *
     * 手指已经抬起来了，这时候宿主回写的温度（可能被车端夹了一下）
     * 必须能写进来 —— 之前 `dragging = false` 排在回调**后面**，
     * 回调里宿主的回写被当成「拖动中的回声」丢掉了。
     *
     * 所以这里把回写放在 [onTemp] 回调**里面**：
     * 宿主就是「收到拖动结果 → 下发 → 回写」这么连着做的。
     */
    @Test
    fun 抬手后接受车机回写() {
        val v = makeDial()

        // 宿主在收到拖动结果的回调里立刻回写（车端把温度夹了一下）
        v.onTemp = { v.temperature = 18.5f }

        touch(v, MotionEvent.ACTION_DOWN, cx, cy - r)
        touch(v, MotionEvent.ACTION_MOVE, cx + r, cy)
        touch(v, MotionEvent.ACTION_UP, cx + r, cy)

        assertEquals(
            "抬手后宿主在回调里的回写被丢掉了（dragging 复位排在了回调后面）",
            18.5f,
            v.temperature,
            0.001f,
        )
    }

    /**
     * 拖到很低的位置（圆环正左方）→ 18.5℃。
     *
     * 顺带验证另一个方向也没被写反（角度 → 温度的映射是单调的）。
     */
    @Test
    fun 拖到圆环正左方是低温度() {
        val v = makeDial()

        touch(v, MotionEvent.ACTION_DOWN, cx, cy - r)
        touch(v, MotionEvent.ACTION_MOVE, cx - r, cy)

        assertEquals(
            "拖到圆环正左方（−90°）应该是 18.5℃，实际 ${v.temperature}",
            18.5f,
            v.temperature,
            0.001f,
        )
    }

    // ------------------------------------------------------------------ ± 键

    /**
     * ± 键**不受影响**：按下就步进，走 `onDelta`，不报 `onTemp`。
     *
     * 这次改的是「拖动」这条路径，± 键那条不能被带坏。
     */
    @Test
    fun 按下正负键仍然只走步进回调() {
        val v = makeDial()
        var delta: Float? = null
        var tempReported = false
        v.onDelta = { delta = it }
        v.onTemp = { tempReported = true }

        // 减号键在圆环下方偏左（onDraw 里算出来的位置）
        val minusX = cx - (TempDialView.BUTTON_SIZE + TempDialView.BUTTON_GAP) / 2f
        val buttonY = TempDialView.BUTTON_TOP + TempDialView.BUTTON_SIZE / 2f

        touch(v, MotionEvent.ACTION_DOWN, minusX, buttonY)
        touch(v, MotionEvent.ACTION_UP, minusX, buttonY)

        assertEquals("按下减号键应该步进 −0.5℃", -0.5f, delta ?: 0f, 0.001f)
        assertTrue("± 键不该触发 onTemp（那是拖动结束的回调）", !tempReported)
    }

    /**
     * ± 键步进之后，表盘上显示的温度要跟着变。
     *
     * 宿主是拿到步进回调后才去改车机温度、再回读下来的；
     * 这条走的是 `temperature` setter（**不是**拖动路径），必须被接受。
     */
    @Test
    fun 步进回读会被接受并显示出来() {
        val v = makeDial()

        // 模拟宿主收到步进后回读车机温度
        v.temperature = 24.0f
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("步进后的回读被丢掉了", 24.0f, v.temperature, 0.001f)
        val expected = 24.0f / TempDialView.ARC_TEMP_SPAN
        val actual = accentArcRatio(v)
        assertTrue(
            "回读后圆环弧长没跟上：期望约 $expected，实际 $actual",
            kotlin.math.abs(actual - expected) < 0.05,
        )
    }
}
