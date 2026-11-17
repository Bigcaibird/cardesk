package com.cardesk.launcher.ui.anim

import kotlin.math.roundToInt

/**
 * 全局动效的**数值规格**（纯 Kotlin，可 JVM 单测）。
 *
 * 这里只放数字，不碰 Android —— `ui/anim/Motion.kt` 负责把它们喂给
 * `ObjectAnimator` / `ViewPropertyAnimator`。
 *
 * ## 为什么要有这一层
 *
 * 之前 App 里的动效是「哪里需要就哪里写」：
 * `pressFeedback()` 散落在 10 多个文件里，各写各的 60ms / 0.96f；
 * 对话框进场是「啪」一下弹出来的；开关的滑块是瞬移的；
 * 数值（温度、风量）改完是硬切。
 *
 * 结果是**手感不统一**：按下去的反馈有的有、有的没有；
 * 同类控件的动画时长对不上；新增控件很容易就漏掉动画。
 *
 * 把数字收进一个纯 Kotlin 的 object 之后：
 *
 * - 所有控件引用同一套时长和曲线 —— 手感统一；
 * - 纯 Kotlin ⇒ 能进 JVM 单测（`MotionSpecTest`），
 *   数值被改坏时构建期就能发现，不用上车机才知道；
 * - 新增控件只要调 [Motion] 的入口，不用再抄一遍魔法数字。
 *
 * ## 设计口径（车机 HMI）
 *
 * 车机屏是**驾驶员瞥一眼**就要读懂信息的场合，所以：
 *
 * - **按压反馈必须快**（[PRESS_DOWN_DURATION] = 60ms）。
 *   手指抬起来之前就要看到东西动了，否则会被当成「没点到」。
 * - **回弹稍慢**（[PRESS_UP_DURATION] = 90ms），
 *   留出一点「弹回去」的余韵，手感才不生硬。
 * - **状态切换要给二次确认**（[STATE_BUMP]）——
 *   空调开关、车门这类「点错了有后果」的操作，
 *   除了颜色变化还要有形变，确认「这一下真的生效了」。
 * - **不要花哨**：只用缩放 / 位移 / 透明度三种最廉价的属性，
 *   不做布局动画、不做模糊、不做路径动画 —— 车机是车机，
 *   卡帧比不好看严重得多。
 */
object MotionSpec {

    // ---------------------------------------------------------------
    // 按压反馈
    // ---------------------------------------------------------------

    /**
     * 按下时的缩放**动画时长**（毫秒）。
     *
     * 60ms：手指还没抬起来就已经看到缩放了。
     * 这是延续 App 里原有的按压手感（`ui/ChipButton.kt` 的 `pressFeedback()`）。
     */
    const val PRESS_DOWN_DURATION = 60L

    /**
     * 松手回弹的**动画时长**（毫秒）。
     *
     * 比按下慢一点（90 > 60）：回弹需要一点点「弹」的尾巴，
     * 等长的话会显得像被弹回，缺少「按下去陷进去」的层次。
     */
    const val PRESS_UP_DURATION = 90L

    /**
     * 按下时的缩放**比例**（1 = 不缩）。
     *
     * 0.96 是延续 App 原有的数值。
     * 再小（比如 0.9）会让整块卡片看起来要塌下去，
     * 车机上的大控件会显得廉价。
     */
    const val PRESS_SCALE = 0.96f

    /**
     * 图标类小按钮的缩放比例。
     *
     * 小按钮按 0.96 几乎看不出来，所以给更大的幅度（0.9）。
     */
    const val PRESS_SCALE_ICON = 0.9f

    /**
     * 整行可点（列表行、卡片）的缩放比例。
     *
     * 行很大，整行缩 4% 会很晃；0.985 只是「轻轻一沉」，
     * 配合行内元素（图标/文字）的缩放一起用更自然。
     */
    const val PRESS_SCALE_ROW = 0.985f

    // ---------------------------------------------------------------
    // 状态切换（开关 / 选中）
    // ---------------------------------------------------------------

    /**
     * 状态切换时「鼓一下」的**时长**（毫秒）。
     *
     * 180ms：够被眼睛捕捉到，又不至于让人等。
     */
    const val STATE_BUMP_DURATION = 180L

    /**
     * 状态切换时的**放大比例**（>1 = 鼓一下）。
     *
     * 1.06：只是「弹」一点点，配合底色变化一起给信号。
     */
    const val STATE_BUMP = 1.06f

    /**
     * 开关滑块（knob）滑动的**时长**（毫秒）。
     */
    const val KNOB_DURATION = 200L

    // ---------------------------------------------------------------
    // 数值变化
    // ---------------------------------------------------------------

    /**
     * 数值变化（温度 ±、风量 ±）的**动画时长**（毫秒）。
     */
    const val VALUE_DURATION = 200L

    /**
     * 数值变化时控件的**位移幅度**（设计坐标 px）。
     *
     * 8px：一个小小的「顶一下」，让眼睛注意到「这里变了」。
     */
    const val VALUE_NUDGE_PX = 8f

    // ---------------------------------------------------------------
    // 列表入场
    // ---------------------------------------------------------------

    /**
     * 列表项入场的**动画时长**（毫秒）。
     */
    const val LIST_REVEAL_DURATION = 240L

    /**
     * 列表项之间**错峰**的间隔（毫秒）。
     */
    const val LIST_STAGGER_STEP = 16L

    /**
     * 最多只给**前几个**项做错峰。
     *
     * 再多的话最后一个要等 16 × 30 = 480ms 才出现，
     * 用户会以为「列表卡住了」。
     * 超过这个数的项一起入场。
     */
    const val LIST_STAGGER_MAX_ITEMS = 12

    /**
     * 列表项入场时的**上移距离**（设计坐标 px）。
     *
     * 12px：微微往上冒一点点，配上淡入就足够「依次出现」。
     */
    const val LIST_REVEAL_OFFSET_PX = 12f

    // ---------------------------------------------------------------
    // 对话框 / 浮层进出
    // ---------------------------------------------------------------

    /**
     * 对话框进场时长（毫秒）。
     *
     * 与浮层打开（`OverlaySpec.OPEN_DURATION` = 200ms）保持一致。
     */
    const val SHEET_ENTER_DURATION = 200L

    /**
     * 对话框退场时长（毫秒）。
     *
     * 比进场慢（240 > 200）：和浮层一样，「关」要给一个明确的收尾。
     */
    const val SHEET_EXIT_DURATION = 240L

    /**
     * 对话框进场时的**上滑距离**（设计坐标 px）。
     *
     * 40：比浮层的 56 略短 —— 对话框是居中的卡片，
     * 滑太远会像「从屏幕外面飞进来」。
     */
    const val SHEET_SLIDE_PX = 40f

    /**
     * 遮罩淡入淡出的时长（毫秒）。
     */
    const val SHEET_SCRIM_DURATION = 180L

    /**
     * 对话框进场的**缩放**起始比例。
     *
     * 0.96：轻微的纵深，配合上滑，卡片像是「浮起来」的。
     */
    const val SHEET_ENTER_SCALE = 0.96f

    // ---------------------------------------------------------------
    // 插值器控制点
    // ---------------------------------------------------------------

    /**
     * 标准减速曲线：`PathInterpolator(0.4f, 0f, 0.2f, 1f)`。
     *
     * Material 的标准 easing：起步快、尾巴长，
     * 是「系统默认应该长这样」的那条曲线。
     */
    const val STANDARD_X1 = 0.4f
    const val STANDARD_Y1 = 0f
    const val STANDARD_X2 = 0.2f
    const val STANDARD_Y2 = 1f

    /**
     * 强减速曲线：`PathInterpolator(0.2f, 0.8f, 0.2f, 1f)`。
     *
     * 与 App 里已有的浮层动画（`OverlaySpec`）同一条，
     * 用来做「进场 / 退场」这种大动作。
     */
    const val EMPHASIS_X1 = 0.2f
    const val EMPHASIS_Y1 = 0.8f
    const val EMPHASIS_X2 = 0.2f
    const val EMPHASIS_Y2 = 1f

    /**
     * 回弹的**过冲比例**（`OvershootInterpolator` 的 tension）。
     *
     * 1.1：松手时略微冲过 1 再回来。
     * 太大（比如 2.0）会像弹簧玩具，车机上不合适。
     */
    const val REBOUND_TENSION = 1.1f

    // ---------------------------------------------------------------
    // 派生量（纯函数，可单测）
    // ---------------------------------------------------------------

    /**
     * 列表第 [index] 项入场的**延迟**（毫秒）。
     *
     * 超过 [LIST_STAGGER_MAX_ITEMS] 之后不再递增 ——
     * 后面所有项一起入场，避免「列表尾巴等太久」。
     */
    fun staggerDelayMs(index: Int): Long {
        val step = index.coerceAtLeast(0).coerceAtMost(LIST_STAGGER_MAX_ITEMS - 1)
        return step * LIST_STAGGER_STEP
    }

    /**
     * 按压缩放比例。
     *
     * @param scale 传 null 时用标准的 [PRESS_SCALE]
     */
    fun pressScale(scale: Float?): Float = scale ?: PRESS_SCALE

    /**
     * 两个颜色（0xAARRGGBB）之间按 [t] 线性插值。
     *
     * 纯 Kotlin ⇒ 能进 JVM 单测（`MotionSpecTest`）。
     *
     * 状态切换时底色要「渐变过去」而不是硬切，就得能算出中间色。
     *
     * @param t 0 = 全是 [from]，1 = 全是 [to]
     */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (from ushr 24 and 0xFF).toFloat()
        val r = (from ushr 16 and 0xFF).toFloat()
        val g = (from ushr 8 and 0xFF).toFloat()
        val b = (from and 0xFF).toFloat()
        val ta = (to ushr 24 and 0xFF).toFloat()
        val tr = (to ushr 16 and 0xFF).toFloat()
        val tg = (to ushr 8 and 0xFF).toFloat()
        val tb = (to and 0xFF).toFloat()
        // 四舍五入而不是截断：截断的话 127.5 会变成 127，
        // 白↔黑的中点就不是 #808080 了。
        fun mix(x: Float, y: Float) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        return (mix(a, ta) shl 24) or (mix(r, tr) shl 16) or (mix(g, tg) shl 8) or mix(b, tb)
    }
}
