package com.cardesk.launcher.core

/**
 * 第218轮：**按键增强的手势识别**（纯逻辑，不碰 Android ⇒ 可 JVM 单测）。
 *
 * ## 为什么要有这个
 *
 * 第215轮之前，「按键增强」只做了**配置界面**：
 * 语音键的单击 / 双击 / 长按、媒体键的长按都能在设置里选动作，
 * 但**没有任何一行代码在按键事件发生时去读这些配置** ⇒ 配了等于没配。
 *
 * 本类补上识别这一半（另一半在 `ui/KeyActionExecutor`）。
 *
 * ## 手势怎么判
 *
 * | 手势 | 判定 |
 * |---|---|
 * | **长按** | 按住超过 [LONG_THRESHOLD_MS] 还没抬手 ⇒ `onLongTimeout` 触发 |
 * | **双击** | 两次「按下—抬起」间隔在 [DOUBLE_WINDOW_MS] 内 ⇒ 第二次抬起时判定 |
 * | **单击** | 抬起后等满双击窗口仍没有第二次按下 ⇒ `onSingleTimeout` 触发 |
 *
 * ## ⚠️ 为什么单击要**等窗口**才发
 *
 * 用户可以同时配「单击 = A」和「双击 = B」。
 * 若抬起就立刻发单击，双击时就会**先触发 A 再触发 B**（A 被误触发）。
 *
 * ⇒ 单击必须**延迟**到双击窗口结束才发。
 * 代价：单击有 [DOUBLE_WINDOW_MS] 的延迟（默认 380ms，人手指可感知但不难受）。
 *
 * ## 线程
 *
 * 纯状态机，**不持有时间源** —— 时间由调用方传进来（`SystemClock.uptimeMillis()`）。
 * ⇒ 单测可以随便喂时间，不用 sleep。
 */
object KeyGesture {

    /** 识别出的手势。 */
    enum class Gesture {
        /** 不是本类关心的按键。 */
        NONE,

        /** 单击（要等双击窗口结束才发）。 */
        SINGLE,

        /** 双击。 */
        DOUBLE,

        /** 长按。 */
        LONG,
    }

    /**
     * 双击判定窗口（毫秒）。两次抬起间隔小于它 ⇒ 算双击。
     *
     * 第三百一十五轮：`280ms` → `380ms`，与方控广播那套手势机
     * （`core/OemKeyGesture.DOUBLE_WINDOW_MS`）对齐 ——
     * 同一个用户、同一颗语音键，两条通路的双击窗口不该不一样，
     * 否则「按键在桌面里好点、在导航里不好点」。实车手感以 380ms 为准。
     */
    const val DOUBLE_WINDOW_MS = 380L

    /** 长按阈值（毫秒）。按住超过它 ⇒ 算长按。 */
    const val LONG_THRESHOLD_MS = 500L

    /**
     * 一个按键的手势状态机（**一个实例管一个键**）。
     *
     * 媒体键与语音键各用一个实例 —— 它们的判定规则不同
     * （媒体键只认长按，语音键认单击/双击/长按）。
     */
    class Detector(
        private val doubleWindowMs: Long = DOUBLE_WINDOW_MS,
        private val longThresholdMs: Long = LONG_THRESHOLD_MS,
    ) {
        /** 本次按下的时刻（0 = 没按下）。 */
        private var downAt = 0L

        /** 本次按下的 keyCode（0 = 没按下）。 */
        private var downKey = 0

        /** 长按是否已经触发过（一次按下只触发一次）。 */
        private var longFired = false

        /**
         * 上一次**点击**抬起的时刻（0 = 从没抬过手）。
         *
         * ⚠️ 长按的抬手**不更新**它 —— 长按不算「一次点击」，
         * 不该参与双击判定（否则「长按之后马上再点一下」会被判成双击）。
         */
        private var lastUpAt = 0L

        /** 上一次抬起后是否还挂着一个「待发的单击」。 */
        private var singlePending = false

        /**
         * `ACTION_DOWN`。
         *
         * @return 恒为 [Gesture.NONE]（按下本身不产生手势）。
         */
        fun onDown(keyCode: Int, now: Long): Gesture {
            downAt = now
            downKey = keyCode
            longFired = false
            return Gesture.NONE
        }

        /**
         * `ACTION_UP`。
         *
         * @return [Gesture.DOUBLE]（窗口内的第二次抬起）或 [Gesture.NONE]。
         *   单击**不在这里发** —— 要等 [onSingleTimeout]。
         */
        fun onUp(keyCode: Int, now: Long): Gesture {
            val wasDown = downKey == keyCode
            downKey = 0
            downAt = 0L
            if (!wasDown) return Gesture.NONE

            // ⚠️ 本次按下已经判成长按 ⇒ 抬手**不再挂单击**。
            // 否则一次长按会先触发长按动作、抬手 280ms 后又补发一次单击动作
            // （用户会看到「切了两次」）。这是第218轮端到端测试抓出来的真实缺陷。
            if (longFired) return Gesture.NONE

            val isDouble = lastUpAt > 0L && (now - lastUpAt) <= doubleWindowMs
            lastUpAt = now
            if (isDouble) {
                // 双击成立 ⇒ 挂起的单击作废
                singlePending = false
                return Gesture.DOUBLE
            }
            // 还没到双击窗口 ⇒ 挂一个待发的单击，等 [onSingleTimeout] 定夺。
            singlePending = true
            return Gesture.NONE
        }

        /**
         * 双击窗口结束（调用方用定时器在窗口到期后调）。
         *
         * @return [Gesture.SINGLE]（窗口内没有第二次按下）或 [Gesture.NONE]。
         */
        fun onSingleTimeout(): Gesture {
            if (!singlePending) return Gesture.NONE
            singlePending = false
            return Gesture.SINGLE
        }

        /**
         * 长按到期（调用方用定时器在 [longThresholdMs] 后调）。
         *
         * @return [Gesture.LONG] 或 [Gesture.NONE]。
         */
        fun onLongTimeout(now: Long): Gesture {
            if (downKey == 0 || longFired) return Gesture.NONE
            if (now - downAt < longThresholdMs) return Gesture.NONE
            longFired = true
            // 长按成立 ⇒ 挂起的单击作废（这一次是长按，不是单击）
            singlePending = false
            return Gesture.LONG
        }

        /** 是否正按住（用于按键事件的重复分发判断）。 */
        val isDown: Boolean get() = downKey != 0

        /** 距离本次按下过去了多少毫秒（没按下时返回 0）。 */
        fun heldMs(now: Long): Long = if (downKey == 0) 0L else now - downAt
    }
}
