package com.cardesk.launcher.ui.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 全局动效的**执行层**。
 *
 * 数值全部来自 [MotionSpec]（纯 Kotlin，可 JVM 单测），这里只负责把它们
 * 喂给 `ViewPropertyAnimator` / `ObjectAnimator`。
 *
 * ## 提供哪几类动效
 *
 * | 场景 | 入口 | 效果 |
 * | --- | --- | --- |
 * | 按下去 | [press] | 缩小一点，松手弹回 |
 * | 状态变了 | [bump] | 鼓一下 + 底色变化（配合调用方） |
 * | 数值变了 | [nudge] / [animateValue] | 顶一下 / 平滑过渡到新值 |
 * | 列表出来了 | [revealStagger] | 逐项淡入上移 |
 * | 浮层打开了 | [enterSheet] / [exitSheet] | 上滑 + 淡入 + 轻微缩放 |
 *
 * ## 为什么要单独一层
 *
 * 之前按压反馈是 `ui/ChipButton.kt` 里一个孤零零的 `pressFeedback()`，
 * 十几个文件各调各的。现在统一收口：
 *
 * - **手感一致**：所有控件用同一套时长和曲线；
 * - **不容易漏**：新增控件只要调这里的入口；
 * - **可测**：数字都在 [MotionSpec] 里，能进 JVM 单测。
 *
 * ## 一个坑：`setOnTouchListener` 会覆盖旧的监听器
 *
 * [press] 会给视图装 `OnTouchListener`，而 `View.setOnTouchListener`
 * 是**覆盖式**的 —— 视图本来就有监听器（比如拖拽把手）的话会被顶掉。
 *
 * 所以：
 *
 * - 单纯要按压反馈 → 用 [press]；
 * - 视图**已经有**自己的触摸处理 → 用 [attachPress]，
 *   它把原监听器当参数接进来，两个一起跑。
 */
object Motion {

    // ---------------------------------------------------------------
    // 插值器
    // ---------------------------------------------------------------

    /**
     * 标准减速：`PathInterpolator(0.4f, 0f, 0.2f, 1f)`。
     *
     * 大部分过渡都用这条：起步快、尾巴长，是「不惊动用户」的那条曲线。
     */
    val STANDARD: PathInterpolator = PathInterpolator(
        MotionSpec.STANDARD_X1,
        MotionSpec.STANDARD_Y1,
        MotionSpec.STANDARD_X2,
        MotionSpec.STANDARD_Y2,
    )

    /**
     * 强减速：`PathInterpolator(0.2f, 0.8f, 0.2f, 1f)`。
     *
     * 与浮层动画（`OverlaySpec`）同一条，用在「进场 / 退场」这种大动作上。
     */
    val EMPHASIS: PathInterpolator = PathInterpolator(
        MotionSpec.EMPHASIS_X1,
        MotionSpec.EMPHASIS_Y1,
        MotionSpec.EMPHASIS_X2,
        MotionSpec.EMPHASIS_Y2,
    )

    /**
     * 回弹用的过冲插值器。
     *
     * 松手时略微冲过 1 再回来，按钮才「活」。
     */
    val REBOUND: OvershootInterpolator = OvershootInterpolator(MotionSpec.REBOUND_TENSION)

    // ---------------------------------------------------------------
    // 按压反馈
    // ---------------------------------------------------------------

    /**
     * 已经装过按压反馈的视图。
     *
     * 用 [WeakHashMap] 而不是 `view.tag` ——
     * App 里 `tag` 已经被占用（比如抽屉单元格的 `APP_CELL_MARK`），
     * 再往里塞东西会跟业务逻辑打架。
     */
    private val pressed = WeakHashMap<View, Boolean>()

    /**
     * 给视图装按压反馈：按下缩小、松手弹回。
     *
     * 监听器返回 `false`，**不吞事件**，所以 `setOnClickListener` 照常触发。
     *
     * @param scale 按下时的缩放比例（null = [MotionSpec.PRESS_SCALE]）
     */
    @JvmStatic
    fun press(view: View, scale: Float? = null) {
        if (pressed.containsKey(view)) return
        pressed[view] = true
        view.setOnTouchListener { v, event -> pressTouch(v, event, scale); false }
        resetOnAttach(view)
    }

    /**
     * 给**已经有自己触摸处理**的视图装按压反馈。
     *
     * [delegate] 就是原来的 `OnTouchListener`，
     * 按压反馈和它一起跑，两个都不耽误。
     *
     * @param delegate 原来的触摸处理，返回 true 表示消费了事件
     */
    @JvmStatic
    fun attachPress(
        view: View,
        delegate: (View, MotionEvent) -> Boolean,
        scale: Float? = null,
    ) {
        if (pressed.containsKey(view)) return
        pressed[view] = true
        view.setOnTouchListener { v, event ->
            pressTouch(v, event, scale)
            delegate(v, event)
        }
        resetOnAttach(view)
    }

    /**
     * 按下 / 松手那一刻的具体动作。
     */
    private fun pressTouch(v: View, event: MotionEvent, scale: Float?) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!v.isEnabled) return
                val s = MotionSpec.pressScale(scale)
                v.animate().cancel()
                v.animate()
                    .scaleX(s).scaleY(s)
                    .setDuration(MotionSpec.PRESS_DOWN_DURATION)
                    .setInterpolator(STANDARD)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(MotionSpec.PRESS_UP_DURATION)
                    .setInterpolator(REBOUND)
                    .start()
            }
        }
    }

    /**
     * 视图重新挂上去时把缩放复位。
     *
     * 列表里按着按钮的同时这一行被重建的话，新视图会带着 0.96 的
     * 缩放出现 —— 看起来就是「莫名其妙缩小了一点」。
     */
    private fun resetOnAttach(view: View) {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.scaleX = 1f
                v.scaleY = 1f
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    // ---------------------------------------------------------------
    // 状态切换
    // ---------------------------------------------------------------

    /**
     * 状态切换时「鼓一下」。
     *
     * 空调开关、车门这类操作，光靠底色变化在余光里是抓不住的 ——
     * 补一个轻微的放大，确认「这一下真的生效了」。
     *
     * 底色 / 文字颜色的变化由调用方自己做（那是业务状态），
     * 这里只管形变，两边不打架。
     */
    @JvmStatic
    fun bump(view: View, scale: Float = MotionSpec.STATE_BUMP) {
        val half = MotionSpec.STATE_BUMP_DURATION / 2
        twoStage(
            view = view,
            duration = half,
            out = { view.animate().scaleX(scale).scaleY(scale) },
            back = {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(half)
                    .setInterpolator(REBOUND)
                    .start()
            },
        )
    }

    /**
     * 数值变化时「顶一下」。
     *
     * 用在温度 ±、风量 ± 这种「数字跳了」的场合 ——
     * 位移比缩放更容易被余光捕捉到「这里变了」。
     */
    @JvmStatic
    fun nudge(view: View, dy: Float = -MotionSpec.VALUE_NUDGE_PX) {
        view.translationY = 0f
        twoStage(
            view = view,
            duration = MotionSpec.VALUE_DURATION / 2,
            out = { view.animate().translationY(dy) },
            back = {
                view.animate()
                    .translationY(0f)
                    .setDuration(MotionSpec.VALUE_DURATION / 2)
                    .setInterpolator(STANDARD)
                    .start()
            },
        )
    }

    /**
     * 两段式动画：先「出去」，再「回来」。
     *
     * ## 为什么不能直接写 `withEndAction`
     *
     * `view.animate()` 每次拿到的都是**同一个** `ViewPropertyAnimator` 实例，
     * 而 `withEndAction` 设的回调会**一直留在那个实例上**。
     *
     * 于是第二段动画结束时会**再次触发**第一段留下的回调 ——
     * 回调又起第三段，第三段结束又触发……**无限循环**。
     *
     * 后果不只是「动画停不下来」：
     * 每一帧都在跑 → Robolectric 的虚拟时钟被**无限推进** →
     * 应用里所有定时器（状态栏时钟、天气刷新…）跟着疯狂触发 →
     * 每个 tick 都写一条日志 → 日志把单测撑爆。
     *
     * 所以两段之间、以及每次起新动画之前，都要把旧回调**清掉**。
     *
     * @param out 第一段：设置「出去」的目标属性（作用在同一个 animator 上）
     * @param back 第二段：设置「回来」的目标属性并启动（**不要**再挂回调）
     */
    private fun twoStage(
        view: View,
        duration: Long,
        out: () -> Unit,
        back: () -> Unit,
    ) {
        view.animate().cancel()
        // 清掉上一次可能留下的回调 —— 别的动画结束时不该触发它。
        view.animate().withEndAction(null)
        out()
        view.animate()
            .setDuration(duration)
            .setInterpolator(STANDARD)
            .withEndAction {
                // 第二段**不带回调**：否则又绕回无限循环。
                view.animate().withEndAction(null)
                back()
            }
            .start()
    }

    /**
     * 让一个**自绘**控件的数值平滑过渡到新值。
     *
     * 自绘控件（温度盘、风量条、座椅档位）的值是画出来的，
     * 直接赋值 + `invalidate()` 是硬切 —— 数字「啪」地跳过去，
     * 看不出是渐变过去的。
     *
     * 这里用 [ValueAnimator] 在两值之间插值，每帧回调一次，
     * 由调用方决定怎么画。
     *
     * @param from 起始值
     * @param to 目标值
     * @param onFrame 每帧的中间值（**放在最后**，方便用尾随 lambda 写）
     * @param onEnd 动画结束回调（用于提交最终值）
     */
    @JvmStatic
    fun animateValue(
        from: Float,
        to: Float,
        duration: Long = MotionSpec.VALUE_DURATION,
        onEnd: () -> Unit = {},
        onFrame: (Float) -> Unit,
    ) {
        if (from == to) {
            onFrame(to)
            onEnd()
            return
        }
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = STANDARD
            addUpdateListener { onFrame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFrame(to)
                    onEnd()
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------
    // 通用进度动画
    // ---------------------------------------------------------------

    /**
     * 正在跑的进度动画（按视图记）。
     *
     * 同一个视图上再起一个进度动画时，先把上一个取消 ——
     * 两个动画抢同一个属性会互相打架，画面会抖。
     */
    private val progresses = WeakHashMap<View, ValueAnimator>()

    /**
     * 跑一段 0→1 的进度动画，每帧回调一次。
     *
     * 状态渐变、进度条推进这类「要自己插值」的场合用它。
     *
     * @param duration 时长（毫秒），默认用状态切换的时长
     * @param onFrame 每帧的进度（0..1）—— **放在最后**，方便用尾随 lambda 写
     * @param onEnd 动画结束回调
     */
    @JvmStatic
    fun animateProgress(
        view: View,
        duration: Long = MotionSpec.STATE_BUMP_DURATION,
        onEnd: () -> Unit = {},
        onFrame: (Float) -> Unit,
    ) {
        progresses.remove(view)?.cancel()
        // 监听器里**不能**直接捕获 `view`。
        //
        // [progresses] 是 WeakHashMap：值（ValueAnimator）会经由监听器
        // **强引用**回键（view），于是这一项永远不会被回收 ——
        // WeakHashMap 清理弱引用的前提是「值不再引用键」，这条不满足就等于普通 HashMap。
        // 列表里翻几屏就能攒出不少死视图，白白占着内存。
        //
        // 所以监听器只持有一个弱引用。
        val ref = WeakReference(view)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = STANDARD
            addUpdateListener { onFrame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    ref.get()?.let { progresses.remove(it) }
                    // 被取消（同一个视图上又起了新动画）就不再补最后一帧 ——
                    // 补了会让画面先跳到终点再从头开始，抖一下。
                    if (cancelled) return
                    onFrame(1f)
                    onEnd()
                }
            })
        }
        progresses[view] = anim
        anim.start()
    }

    // ---------------------------------------------------------------
    // 列表入场
    // ---------------------------------------------------------------

    /**
     * 列表项错峰入场：淡入 + 微微上移。
     *
     * @param index 这一项在列表里的序号（0 起）
     */
    @JvmStatic
    fun revealStagger(view: View, index: Int) {
        val delay = MotionSpec.staggerDelayMs(index)
        view.animate().cancel()
        // 清掉可能残留的 end action（见 [twoStage] 的说明）。
        view.animate().withEndAction(null)
        view.alpha = 0f
        view.translationY = MotionSpec.LIST_REVEAL_OFFSET_PX
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(MotionSpec.LIST_REVEAL_DURATION)
            .setInterpolator(STANDARD)
            .start()
    }

    // ---------------------------------------------------------------
    // 浮层 / 对话框进出
    // ---------------------------------------------------------------

    /**
     * 正在跑的退场动画（按视图记）。
     *
     * 退场用的是 [ObjectAnimator]，不是 `ViewPropertyAnimator` ——
     * 所以 `view.animate().cancel()` 掐不掉它。
     * 紧接着又打开同一个视图时，两个动画会抢同一批属性（位移 / 透明度），
     * 画面会抖。这里记一下，[enterSheet] 负责掐掉。
     */
    private val exits = WeakHashMap<View, AnimatorSet>()

    /**
     * 对话框进场：上滑 + 淡入 + 轻微放大，遮罩淡入。
     */
    @JvmStatic
    fun enterSheet(sheet: View?, scrim: View?) {
        sheet?.let {
            // 上一轮退场动画可能还在跑 —— 先掐掉。
            // 退场用的是 ObjectAnimator（不是 ViewPropertyAnimator），
            // `animate().cancel()` 掐不掉它，两个动画会抢同一批属性，
            // 画面会抖。
            exits.remove(it)?.cancel()
            it.animate().cancel()
            // 清掉可能残留的 end action（见 [twoStage] 的说明）——
            // 不然进场的动画结束时会触发上一轮留下的回调。
            it.animate().withEndAction(null)
            it.alpha = 0f
            it.translationY = MotionSpec.SHEET_SLIDE_PX
            it.scaleX = MotionSpec.SHEET_ENTER_SCALE
            it.scaleY = MotionSpec.SHEET_ENTER_SCALE
            it.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionSpec.SHEET_ENTER_DURATION)
                .setInterpolator(EMPHASIS)
                .start()
        }
        scrim?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.animate()
                .alpha(1f)
                .setDuration(MotionSpec.SHEET_SCRIM_DURATION)
                .start()
        }
    }

    /**
     * 对话框退场：下滑 + 淡出，遮罩淡出。
     *
     * 动画结束后回调 [onEnd]（一般是把这层从窗口里摘掉）。
     *
     * 退场比进场慢一点（240 vs 200），并且**在动画结束后才摘层** ——
     * 直接 `removeView()` 的话动画根本看不见。
     *
     * @param resetOnEnd 动画结束后要不要把面板复位（alpha / 位移 / 缩放回初始值）。
     *   面板**马上要被摘掉**时传 false —— 复位会让它先「闪回可见」一下，
     *   再被摘掉，看起来像闪了一下。
     */
    @JvmStatic
    fun exitSheet(
        sheet: View?,
        scrim: View?,
        resetOnEnd: Boolean = true,
        onEnd: () -> Unit,
    ) {
        if (sheet == null) {
            onEnd()
            return
        }
        sheet.animate().cancel()
        scrim?.animate()?.cancel()
        val slide = ObjectAnimator.ofFloat(sheet, View.TRANSLATION_Y, sheet.translationY, MotionSpec.SHEET_SLIDE_PX).apply {
            duration = MotionSpec.SHEET_EXIT_DURATION
            interpolator = EMPHASIS
        }
        val fade = ObjectAnimator.ofFloat(sheet, View.ALPHA, sheet.alpha, 0f).apply {
            duration = MotionSpec.SHEET_SCRIM_DURATION
            interpolator = EMPHASIS
        }
        scrim?.animate()?.alpha(0f)?.setDuration(MotionSpec.SHEET_SCRIM_DURATION)?.start()
        // 同 [animateProgress]：监听器不能强引用 `sheet`，
        // 否则 WeakHashMap 的值会一直吊着键，这一项永远回收不掉。
        val ref = WeakReference(sheet)
        val set = AnimatorSet().apply {
            playTogether(slide, fade)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    ref.get()?.let { exits.remove(it) }
                    // 被取消（比如紧接着又打开）就不摘层、不复位。
                    if (cancelled) return
                    if (resetOnEnd) {
                        // 复位：下次打开是「从 40 处滑上来」，而不是从残留位置滑。
                        sheet.translationY = 0f
                        sheet.alpha = 1f
                        sheet.scaleX = 1f
                        sheet.scaleY = 1f
                    }
                    onEnd()
                }
            })
        }
        // 记下这一组退场动画：紧接着又打开时（[enterSheet]）要能掐掉它。
        exits.remove(sheet)?.cancel()
        exits[sheet] = set
        set.start()
    }
}
