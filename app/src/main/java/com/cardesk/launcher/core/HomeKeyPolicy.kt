package com.cardesk.launcher.core

/**
 * Home 键双击手势。
 *
 * ## 为什么单独做一层
 *
 * 车机的 Home 键被系统在框架层吃掉（`PhoneWindowManager` 直接回桌面 / 双击开
 * 系统最近任务），普通应用拿不到 `KeyEvent`。所以这里只有「一次按压」这个输入，
 * 时序判断必须自己能测：
 *
 * - 第一次按压 → [Action.GO_HOME]（回桌面）；
 * - 在 [DOUBLE_WINDOW_MS] 内再来一次 → [Action.SHOW_RECENTS]（打开本项目的最近任务页），
 *   并把状态清零，这样连按三下是「回桌面 + 最近任务 + 回桌面」，不会变成两次双击。
 * - 超过窗口 → 又是 [Action.GO_HOME]。
 *
 * 时间由调用方传入（`SystemClock.uptimeMillis()`），纯逻辑，JVM 可测。
 *
 * ## 与系统双击的关系
 *
 * 系统自己也有「双击 Home 开最近任务」（`DOUBLE_TAP_HOME_RECENT_SYSTEM_UI`）。
 * 我们的入口有两条：
 * 1. **车机 Input 通道**（`com.ecarx.xui.adaptapi.input.Input`）拦截 Home 键 ——
 *    拦下来系统就看不到按键，因此不会再弹系统最近任务，由我们全权处理；
 * 2. **HOME intent 兜底**（本镜像/部分车机框架把双击行为改掉了，两次按压都会
 *    各发一条 HOME intent）—— 这条路上「回桌面」已经由系统做完了，我们只补
 *    「双击 → 本项目的最近任务」这一个动作。
 */
object HomeKeyPolicy {

    /** 双击窗口：比系统 `ViewConfiguration.getDoubleTapTimeout()`（300ms）宽一点，容错手速。 */
    const val DOUBLE_WINDOW_MS = 350L

    /** 没有「上一次按压」的哨兵值（不能用 0，见 [Detector.lastPressAt]）。 */
    private const val NO_PRESS = Long.MIN_VALUE

    enum class Action {
        /** 不用管（功能关闭 / 同一时刻已有其它入口处理）。 */
        NONE,

        /** 回桌面。 */
        GO_HOME,

        /** 打开本项目的最近任务页。 */
        SHOW_RECENTS,
    }

    /**
     * 事件式手势机：只有「按一下」这一个事件，没有按下/抬起。
     *
     * @param windowMs 双击窗口，默认 [DOUBLE_WINDOW_MS]。
     */
    class Detector(private val windowMs: Long = DOUBLE_WINDOW_MS) {

        /**
         * 没有上一次按压。
         *
         * 不能用 `0L` 当哨兵：`SystemClock.uptimeMillis()` 在开机后最初几毫秒
         * 真的会返回 0，两下都落在 0 附近时会被误判成「还没按过」。
         */
        private var lastPressAt = NO_PRESS

        /** 上一次按压是否还在等第二次（诊断用）。 */
        fun pending(): Boolean = lastPressAt != NO_PRESS

        fun reset() {
            lastPressAt = NO_PRESS
        }

        /**
         * 喂一次按压。
         *
         * @param now 按压时刻（`SystemClock.uptimeMillis()`）。
         * @param enabled 「双击 Home 打开最近任务」总开关；关掉时只回桌面。
         * @param doubleRecents 双击是否映射到最近任务（与 [enabled] 同义，保留参数便于单测灰盒）。
         */
        fun onPress(now: Long, enabled: Boolean = true, doubleRecents: Boolean = true): Action {
            if (!enabled) {
                lastPressAt = NO_PRESS
                return Action.NONE
            }
            val double = doubleRecents && lastPressAt != NO_PRESS && now - lastPressAt <= windowMs
            // 双击成立后清零：连按三下 = 回桌面 → 最近任务 → 回桌面。
            lastPressAt = if (double) NO_PRESS else now
            return if (double) Action.SHOW_RECENTS else Action.GO_HOME
        }
    }
}
