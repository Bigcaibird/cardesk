package com.cardesk.launcher.core

/**
 * 360 环影「车机信号联动」的**纯决策逻辑**（无 Android 依赖，可直接 JVM 单测）。
 *
 * ## 为什么要单独抽出这一层
 *
 * 联动这件事真正容易出错的地方不是「怎么调 360 接口」，而是**什么时候该动作**：
 * 挂 R 挡、从 R 切到 D、进 D、进 N/P、雷达图形出现……每条规则都依赖「上一状态」，
 * 而上一状态只有真实车机才会产生。把这些规则写成纯函数后，
 * 全部边界（第一次读到挡位、视角配成 -1、总开关关着、同一状态重复回调）
 * 都能在单测里一次跑完，不需要真车或模拟器。
 *
 * 执行侧见 `vehicle/Camera360Link.kt`：它只负责把车机信号拼成 [Input]、
 * 处理去重/防抖，然后照 [decide] 的结论调用车控。
 *
 * ## 视角编号约定（与车端一致）
 *
 * `-1` = 不切视角；`0` 关闭、`1` 前视、`2` 广角前视、`3` 前视+顶、`4` 顶视、
 * `5` 后视、`6` 广角后视、`7` 后视放大、`8` 后视+顶、`9` 两侧。
 * 所以「视角 = -1」在规则里等价于「这个场景不切视角」，不是切到 0 号视角。
 *
 * ## 为什么 `decide` 要求「只在状态变化时调用」
 *
 * 雷达那一条规则是「**上升沿**」：`false → true` 才开环影，一直为 true 不该反复开。
 * [Input] 里只带当前雷达状态、不带上一次的状态 —— 上升沿由
 * [Deduper] 的「同一 `(总开关, 挡位, 雷达)` 组合只算一次」来保证：
 * 状态没变就不会再进 [decide]，能进来就说明状态刚变过。
 *
 * 挡位动作优先于雷达动作：同一次变化里既进挡又有雷达时，
 * 只按挡位切视角（[Action.OpenAndView]），不再额外发一次「只开环影」，
 * 避免对车机连写两次 360 开关。
 */
object Camera360Policy {

    /**
     * 一次联动决策的全部输入。
     *
     * @param masterEnabled 「360 环影增强」总开关（关着时任何联动都不做）
     * @param gear 当前挡位：`D` / `R` / `N` / `P`；`null` = 读不到
     * @param prevGear 上一次读到的挡位；`null` = 进程内第一次读到（或之前一直读不到）
     * @param radarActive 雷达图形当前是否出现（车端功能号 `0x23010500` 的 value != 0）
     * @param reverseView 挂 R 挡要切的视角（`-1` = 不切）
     * @param rdView R→D 要切的视角（`-1` = 不切）
     * @param dView 进 D 挡要切的视角（`-1` = 不切）
     * @param npClose 进 N/P 挡是否关闭环影
     * @param radarOpen 雷达图形出现时是否开环影
     * @param delayMs 视角下发延迟（100 / 300 / 800 ms，直接透传给车控）
     * @param alreadyOpen 当前 360 是否已经开着（来自车控状态；只用于抑制「雷达出现了，
     *   但环影本来就开着」这种空写，**不影响挡位动作** —— 挡位动作必须保证「确实开着 +
     *   视角对」，不能用可能过期的状态去省那一次写）
     */
    data class Input(
        val masterEnabled: Boolean,
        val gear: Char?,
        val prevGear: Char?,
        val radarActive: Boolean,
        val reverseView: Int,
        val rdView: Int,
        val dView: Int,
        val npClose: Boolean,
        val radarOpen: Boolean,
        val delayMs: Int,
        val alreadyOpen: Boolean = false,
    )

    /** 决策结论。一次决策可能给出 0 条（空列表）或多条动作。 */
    sealed class Action {

        /** 占位动作（正常决策不会返回它，保留给调用方做默认值）。 */
        object None : Action()

        /** 打开环影，并按 [view] 切换视角。[reason] 只进日志，便于现场分辨是哪条规则触发。 */
        data class OpenAndView(val view: Int, val delayMs: Int, val reason: String) : Action()

        /** 只打开环影，不切视角（雷达图形出现时的动作）。 */
        object OpenOnly : Action()

        /** 关闭环影（进 N/P 挡时的动作）。 */
        object Close : Action()

        /** 只切视角（不改变开关状态）。 */
        data class View(val view: Int, val delayMs: Int) : Action()

        /**
         * 动作签名：执行侧用它做「1500ms 内同一动作不重复写」的防抖。
         *
         * 只包含「真正会写车」的部分：视角编号 / 开关方向。
         * 延迟与触发原因不影响签名 —— 同一个视角不管延迟多少，短时间重复下发都是多余的。
         */
        val signature: String
            get() = when (this) {
                None -> "none"
                is OpenAndView -> "open+view:$view"
                OpenOnly -> "open"
                Close -> "close"
                is View -> "view:$view"
            }

        /** 日志里的可读动作名（就地拼接，不引入额外工具类）。 */
        val label: String
            get() = when (this) {
                None -> "无动作"
                is OpenAndView -> "开环影并切视角 $view（$reason）"
                OpenOnly -> "开环影（不切视角）"
                Close -> "关环影"
                is View -> "切视角 $view"
            }
    }

    /**
     * 根据一次状态变化给出动作列表。
     *
     * 规则优先级（自上而下，命中即返回）：
     *
     * 1. 总开关关 → 空；
     * 2. 挡位读不到（`null`）→ 空（读不到就什么都不做，宁可不联动也不要误开）；
     * 3. 挡位动作命中 → 只返回挡位动作（雷达动作被抑制，避免同一次变化写两次 360）；
     * 4. 雷达图形出现（状态刚变到 true、环影当前没开）且雷达开关允许 → 只开环影；
     * 5. 其余 → 空。
     *
     * 挡位动作的细分：
     * - 进 R（`prevGear != 'R'`，含第一次读到）：`reverseView >= 0` → 开环影 + 切倒车视角；
     * - R→D：`rdView >= 0` → 开环影 + 切 RD 视角；
     * - 从 P / N / `null` 进 D：`dView >= 0` → 开环影 + 切 D 挡视角；
     * - 进 N 或 P（`prevGear` 与当前挡不同）：`npClose` → 关环影；
     *   一直停在 N/P 时的雷达/重复回调不会反复关（关的是「进入」这个动作）；
     * - 其余挡位转换（如 D→D、N→P 之外）→ 空。
     */
    fun decide(input: Input): List<Action> {
        if (!input.masterEnabled) return emptyList()
        val gear = input.gear ?: return emptyList()
        val gearActions = gearActions(input, gear)
        if (gearActions.isNotEmpty()) return gearActions
        return radarActions(input)
    }

    /** [decide] 的同义入口：执行端按「evaluate（评估一次状态）」命名调用时用这个名字。 */
    fun evaluate(input: Input): List<Action> = decide(input)

    private fun gearActions(input: Input, gear: Char): List<Action> {
        val prev = input.prevGear
        return when (gear) {
            'R' -> if (prev != 'R' && input.reverseView >= 0) {
                listOf(Action.OpenAndView(input.reverseView, input.delayMs, "挂入倒挡"))
            } else {
                emptyList()
            }

            'D' -> when {
                prev == 'R' && input.rdView >= 0 ->
                    listOf(Action.OpenAndView(input.rdView, input.delayMs, "倒挡切前进挡"))

                (prev == 'P' || prev == 'N' || prev == null) && input.dView >= 0 ->
                    listOf(Action.OpenAndView(input.dView, input.delayMs, "进入前进挡"))

                else -> emptyList()
            }

            'N', 'P' -> if (input.npClose && prev != gear) listOf(Action.Close) else emptyList()

            else -> emptyList()
        }
    }

    private fun radarActions(input: Input): List<Action> =
        if (input.radarActive && input.radarOpen && !input.alreadyOpen) {
            listOf(Action.OpenOnly)
        } else {
            emptyList()
        }

    /**
     * 去重用的状态键。
     *
     * 只包含「总开关 + 挡位 + 雷达」三项 —— 这正是 [decide] 的规则依赖的全部车端状态。
     * **不包含 `prevGear`**：同一个当前挡位重复回调不该再动作，`prevGear` 只用来判断
     * 「是刚进这条挡位，还是一直停在这条挡位」。
     */
    fun stateKey(input: Input): String = buildString {
        append(if (input.masterEnabled) '1' else '0')
        append('|')
        append(input.gear ?: '?')
        append('|')
        append(if (input.radarActive) '1' else '0')
    }

    /**
     * 状态去重器：同一个状态组合只放行一次。
     *
     * 执行侧每次收到车机信号都会组一份 [Input]，但信号回调可能重复
     * （例如挡位信号和轮询同时报告同一个值），重复决策会让 [decide] 的
     * 「雷达上升沿」被误当成新上升沿，从而反复写 360。
     * 把「来没来过」这件事收在这里，[decide] 就能保持纯函数。
     */
    class Deduper {

        private var last: String? = null

        /** 状态有变化才返回 true；同一组合的第二次及以后返回 false。 */
        fun accept(input: Input): Boolean {
            val key = stateKey(input)
            if (key == last) return false
            last = key
            return true
        }

        /** 清掉记录（重连通道 / 设置变化后强制重新决策用）。 */
        fun reset() {
            last = null
        }

        /** 最近一次放行的状态键（排障日志用）。 */
        fun lastKey(): String? = last
    }

    // ------------------------------------------------------------ 挡位值映射
    //
    // 不同来源的挡位编码口径完全不一样，全部收在这里，GearSource 只负责取原始值。

    /**
     * `car_signal` 通道的挡位信号（`29693`）映射。
     *
     * 实车口径：`1` = D、`2` = R、`3` = N、`0` 或 `4` = P；
     * 其它值返回 `null`（当作读不到，由上层决定是否继续降级）。
     */
    fun gearFromSignal(raw: Int?): Char? = when (raw) {
        1 -> 'D'
        2 -> 'R'
        3 -> 'N'
        0, 4 -> 'P'
        else -> null
    }

    /**
     * AOSP `CURRENT_GEAR` 属性映射（兜底通道）。
     *
     * AOSP 的 `VehicleGear` 是**位**口径：`1` = NEUTRAL、`2` = REVERSE、
     * `4` = PARK、`8` = DRIVE，`0x10` 起是具体前进挡位。
     * 所以先按已知位判断，再兜底「带挡位编号」的情形（一律按前进挡处理）。
     * 读到的原始值请务必落日志 —— 不同 HAL 的实际编码可能与本表有差异，
     * 真车校准就靠那条日志。
     */
    fun gearFromAospProperty(raw: Int?): Char? {
        val v = raw ?: return null
        if (v == 0) return null
        return when {
            v and 0x0008 != 0 -> 'D'
            v and 0x0004 != 0 -> 'P'
            v and 0x0002 != 0 -> 'R'
            v and 0x0001 != 0 -> 'N'
            // 0x10..0x200 = GEAR_1..GEAR_10，只可能是前进挡
            v and 0x03F0 != 0 -> 'D'
            else -> null
        }
    }

    /**
     * 模拟车辆 `gear` 文本映射（`"P"` / `"D"` / `"R"` / `"N"`）。
     *
     * 只取首字母并忽略大小写与空白；其它文本返回 `null`。
     * 这是模拟器上验证联动的入口（见 `vehicle/GearSource.simulate` 与
     * `vehicle/Camera360Link.simulateGear`），真车不会走这条。
     */
    fun gearFromMockText(text: String?): Char? {
        val c = text?.trim()?.firstOrNull()?.uppercaseChar() ?: return null
        return when (c) {
            'D', 'R', 'N', 'P' -> c
            else -> null
        }
    }
}
