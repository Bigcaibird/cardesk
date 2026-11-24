package com.cardesk.launcher.core

/**
 * 第三百一十五轮：**方控广播的手势机**（纯逻辑，不 import Android ⇒ 可 JVM 单测）。
 *
 * ## 与 [KeyGesture] 的区别
 *
 * [KeyGesture] 吃的是**按下/抬起**两条事件（KeyEvent 通道，我们自己判长按）；
 * 而方控广播是**语义事件**：系统只给「单击」和「长按」（长按是系统判定成立后才发），
 * 抬手那条事件根本不存在 ⇒ 需要另一套状态机：
 *
 * - 收到 `CLICK`：先挂起 [DOUBLE_WINDOW_MS]，窗口内再同键 `CLICK` ⇒ [Gesture.DOUBLE]；
 * - 窗口到期（[Detector.expire]）⇒ [Gesture.SINGLE]；
 * - 收到 `LONG` ⇒ 直接 [Gesture.LONG]（并作废挂起的单击，否则长按后还会补一个单击）。
 *
 * ## 为什么双击窗口是 380ms
 *
 * 车机同一颗语音键，用户从"点一下"切到"连点两下"的间隔实测多在 180~320ms；
 * 窗口太短双击会漏，太长单击（唤起语音）会显得迟钝。
 * 380ms 是车机原厂按键逻辑用的值，与其对齐可以让手感一致。
 */
object OemKeyGesture {

    /** 双击合成窗口（毫秒）。 */
    const val DOUBLE_WINDOW_MS = 380L

    /** 手势。 */
    enum class Gesture { NONE, SINGLE, DOUBLE, LONG }

    /** 一次手势判定结果。 */
    data class Result(val keyCode: Int, val gesture: Gesture) {
        val isNone: Boolean get() = gesture == Gesture.NONE

        companion object {
            val NONE = Result(0, Gesture.NONE)
        }
    }

    /**
     * 事件式手势机：一次只挂一个"待定单击"。
     *
     * 同一时刻只有一个语音键在手 ⇒ 单槽够用；若真的来了另一个键的单击，
     * 新键覆盖旧挂起（旧的单击丢失，但那种输入本身不成立）。
     */
    class Detector(private val doubleWindowMs: Long = DOUBLE_WINDOW_MS) {

        private var pendingKey = 0
        private var pendingAt = 0L

        /** 收到一次「单击」事件。 */
        fun onClick(keyCode: Int, now: Long): Result {
            if (keyCode <= 0) return Result.NONE
            val sameKey = pendingKey == keyCode
            val inWindow = now - pendingAt <= doubleWindowMs
            if (sameKey && inWindow) {
                clear()
                return Result(keyCode, Gesture.DOUBLE)
            }
            pendingKey = keyCode
            pendingAt = now
            return Result(keyCode, Gesture.NONE)
        }

        /** 收到一次「长按」事件：作废挂起的单击。 */
        fun onLong(keyCode: Int): Result {
            clear()
            return Result(keyCode, Gesture.LONG)
        }

        /**
         * 窗口到期结算。
         *
         * @return 还没到期 ⇒ [Gesture.NONE]（调用方应稍后再来）；
         *         到期且有挂起 ⇒ [Gesture.SINGLE]。
         */
        fun expire(now: Long): Result {
            val key = pendingKey
            if (key == 0) return Result.NONE
            if (now - pendingAt < doubleWindowMs) return Result(key, Gesture.NONE)
            clear()
            return Result(key, Gesture.SINGLE)
        }

        /** 当前挂起等待双击的键码（0 = 没有）。 */
        fun pendingKey(): Int = pendingKey

        /** 挂起的单击是否已经到期（诊断/单测用）。 */
        fun isExpired(now: Long): Boolean = pendingKey != 0 && now - pendingAt >= doubleWindowMs

        /** 清空挂起（停止服务 / 配置变更时调用）。 */
        fun reset() = clear()

        private fun clear() {
            pendingKey = 0
            pendingAt = 0L
        }
    }
}
