package com.cardesk.launcher.ui.pip

import com.cardesk.launcher.core.HostTaskDisplay
import com.cardesk.launcher.core.PipRenderPlan

/**
 * 画中画**黑屏自愈**的判据与分级决策 —— 纯逻辑（不碰 View，可 JVM 单测）。
 *
 * ## 为什么要有这个文件（第130轮）
 *
 * 第129轮取证时撞见「画中画黑屏」，查下来是**一整条链子断了**：
 *
 * 1. **黑屏从来没被桌面发现过** —— 判黑的探针压根没在工作
 *    （见 [PipFrameProbe] 的注释：以前取不到 surface 时是**静默跳过**的，
 *    连一行日志都不留）；
 * 2. 于是「黑屏自愈」永远不会触发；
 * 3. 就算触发了，**只自愈一次**（`blackHealDone`），
 *    而且动作只有「重发一次启动」这一档 —— 承载应用的 task
 *    真挂在**旧 VirtualDisplay** 上时，重发启动救不回来 → **永久黑屏**。
 *
 * 这个文件把「判黑」和「该做什么」两件事从 View 代码里抽出来，
 * 变成可以单测的纯函数。
 *
 * ## 判据：三条
 *
 * | 判据 | 含义 | 可信度 |
 * |---|---|---|
 * | [taskOffDisplay] | 承载应用的 Activity **不在我们的 VirtualDisplay 上** | **权威**（直接对应黑屏的成因） |
 * | [sawContent] + 连续黑帧 | 像素判黑（**只有证明采样能看到应用画面之后**才认） | 辅助 |
 * | 连续盲帧超时 | 长时间「看不见承载面」（只在盲帧确实代表「没有画面」的配置下认） | 辅助（长黑轮新增） |
 *
 * ### 为什么像素判黑不能单独用（第130轮实测）
 *
 * 原来判黑靠 `PixelCopy` 采承载面的画面。实测（模拟器 + 高德）：
 *
 * ```
 * 画中画画面采样：状态变化 black=true maxLuma=0 avgLuma=0 brightPct=0
 * ```
 *
 * 而同一时刻**屏幕截图**上高德明明在渲染地图。
 *
 * 原因：`ActivityView` 内部是 `SurfaceView`，它的内容画在**独立的图层**上，
 * 而窗口自己的缓冲在那个位置是**透明的洞** ——
 * `PixelCopy.request(window, …)` 拷到的是**窗口缓冲**，拷不到那个图层。
 * 于是「应用画面是黑的」和「我们根本看不见应用画面」在数据上**完全一样**。
 *
 * 拿它当判据 = **把正常的高德判成黑屏**（实测确实误判并重启了高德）。
 *
 * 所以像素判黑必须**先证明采样能看到应用画面**（[sawContent]）才生效；
 * 而真正管用的判据是 [taskOffDisplay]。
 *
 * ## 长黑轮：三条判据在真车上**全都失效**（长时间黑屏不自愈的根因）
 *
 * 真车诊断包（导航应用画中画，切到其它页较久后回来仍黑）里的采样证据：
 *
 * ```
 * 重新看得见承载面 opaquePct=100 blind=14
 * 状态变化 black=true opaquePct=100 maxLuma=238 avgLuma=32 brightPct=1 streak=1
 * 状态变化 black=false … streak=0
 * 看不见承载面（半透明窗口/透明洞），不判黑 opaquePct=0
 * ```
 *
 * 1. 承载面没有画面时，采样的第一表现是 **BLIND**（窗口缓冲在该位置是透明的洞），
 *    不是连续黑帧：42 秒里连续 14 帧全盲，只在末尾夹了 **1 帧**黑
 *    （亮像素占比 1%，压在地图暗帧的 2% 阈值边上）。
 * 2. 旧规则「盲帧清零连续黑帧」⇒ 连续黑帧永远到不了阈值 ⇒ 自愈永不触发。
 * 3. 权威判据 [taskOffDisplay] 在车机上恒为「判不准」
 *    （调试构建拿不到别人 task 的 displayId ⇒ [HostTaskDisplay] 返回 UNKNOWN）；
 *    输出面体检只看「VirtualDisplay 绑的 surface 还在不在」，不看「有没有画面」。
 *
 * 修复分两半：
 *
 * - 盲帧不再清零连续黑帧（见 `PipSampleGate.nextBlackStreak`）；
 * - 本文件新增**盲帧超时判据**：连续够多帧看不见承载面 ⇒ 按「没有画面」自愈。
 *   它只在盲帧确实代表「没有画面」的配置下启用（见 [blindCanMeanLost]）——
 *   半透明窗口（视频壁纸）下盲帧是正常的（第224轮踩过），永不启用。
 *
 * ## 分级自愈
 *
 * | 阶段 | 动作 | 为什么 |
 * |---|---|---|
 * | 0 | [Action.RELAUNCH] 重发启动 | 轻量：多数黑屏是「承载应用的 Activity 被回收」，拉起来就好 |
 * | 1 | [Action.REBUILD] **重建承载面** | 重量：task 挂在**旧 VirtualDisplay** 上时，重发启动只会把旧 task 拉回来（旧 display！）→ 还是黑。必须拆掉旧承载面、连同旧 VirtualDisplay 一起释放，再干净地重建 |
 * | 2 | 放弃 | 再重建也是白搭（例如承载应用自己就是黑的）。**绝不能无限重启** —— 反复重启比一直黑更烦人 |
 *
 * 每级之间有**递增冷却**（见 [cooldownFor]）：黑屏是用户可见的故障，
 * 第一次要快（8s），第二次可以慢些（20s），第三次直接放弃。
 */
object PipBlackHeal {

    /** 自愈阶段：还没自愈过。 */
    const val STAGE_NONE = 0

    /** 自愈阶段：已经重发过一次启动。 */
    const val STAGE_RELAUNCH = 1

    /** 自愈阶段：已经重建过一次承载面。 */
    const val STAGE_REBUILD = 2

    /** 第一级自愈前的冷却（毫秒）。黑屏是用户可见的故障，第一次要快。 */
    const val COOLDOWN_FIRST_MS = 8_000L

    /** 第二级自愈前的冷却（毫秒）。 */
    const val COOLDOWN_SECOND_MS = 20_000L

    /** 自愈动作。 */
    enum class Action {
        /** 不动作（还没到判据，或者已经放弃）。 */
        NONE,

        /** 重发一次启动（轻量）。 */
        RELAUNCH,

        /** 重建承载面（重量：释放旧 VirtualDisplay 再干净重建）。 */
        REBUILD,
    }

    /**
     * 决策结果。
     *
     * @param action 该做什么
     * @param reason 为什么（进日志，便于事后对账）
     */
    data class Decision(val action: Action, val reason: String) {
        companion object {
            /** 不动作。 */
            val NONE = Decision(Action.NONE, "未达判据")
        }
    }

    /**
     * 连续全黑几帧才判黑。
     *
     * 看门狗 3s 一轮 → 3 帧 ≈ **9 秒**。避开地图加载中的短暂黑帧。
     */
    const val BLACK_STREAK_LIMIT = PipFrameProbe.BLACK_STREAK_LIMIT

    /**
     * 连续几帧「看不见承载面」才按「没有画面」自愈（长黑轮）。
     *
     * 看门狗 3s 一轮 → 10 帧 ≈ **30 秒**。比 [BLACK_STREAK_LIMIT] 宽松得多：
     * 盲帧是弱证据（透明洞在正常换帧时也会短暂出现），
     * 而且只在 [blindCanMeanLost] 成立的配置下才启用。
     */
    const val BLIND_STREAK_LIMIT = PipFrameProbe.BLIND_STREAK_LIMIT

    /**
     * 「看不见承载面」能不能当成「没有画面」（= 黑屏证据）？
     *
     * ## 什么时候能认
     *
     * 盲帧（窗口缓冲在承载面位置是透明的洞）既可能来自「承载面没有画面」，
     * 也可能来自「窗口本身就是半透明，我们永远采不到那一层」。
     * 只有前一种才有诊断价值 —— 真车实测（[PipRenderPlan] 两条路线）：
     *
     * | 路线 | 窗口 | 有画面时采样 | 盲帧的含义 |
     * |---|---|---|---|
     * | [PipRenderPlan.Mode.LAYER] | 不透明 | 不透明像素 ≈ 100% | **承载面没有画面** ⇒ 认 |
     * | [PipRenderPlan.Mode.LAYER] | 半透明 | 恒为 0%（第224轮的误判来源） | 说明不了 ⇒ **不认** |
     * | [PipRenderPlan.Mode.MIRROR] | 任意 | 不透明像素 ≈ 100%（画进窗口缓冲） | **承载面没有画面** ⇒ 认 |
     *
     * `null`（降级成静态卡片 / 还没建好）⇒ 判不准，**不认**（安全方向）。
     *
     * @param activeMode 当前承载内容实际走的路线（null = 没有承载面）
     * @param translucentWindow 桌面窗口当前是不是半透明
     * @return true = 盲帧可以当作「没有画面」的证据
     */
    fun blindCanMeanLost(activeMode: PipRenderPlan.Mode?, translucentWindow: Boolean): Boolean =
        when (activeMode) {
            PipRenderPlan.Mode.LAYER -> !translucentWindow
            PipRenderPlan.Mode.MIRROR -> true
            null -> false
        }

    /**
     * 每一级自愈之间的冷却。
     *
     * @param stage 已经自愈到第几级（[STAGE_NONE] = 还没自愈过）
     * @return 毫秒；[Long.MAX_VALUE] = 不该再自愈了
     */
    fun cooldownFor(stage: Int): Long = when (stage) {
        STAGE_NONE -> COOLDOWN_FIRST_MS
        STAGE_RELAUNCH -> COOLDOWN_SECOND_MS
        else -> Long.MAX_VALUE
    }

    /**
     * 承载应用的 Activity **不在**我们的 VirtualDisplay 上吗？（**纯函数**，可单测）
     *
     * ## 这就是黑屏的成因本身
     *
     * `ActivityView` 的画面 = 它那个 VirtualDisplay 上的内容。
     * 应用的 task 挂到别的 display 上（用户全屏拉走 / 换肤重建后旧 display
     * 没释放干净）时，我们的 VirtualDisplay 上就**什么都没有** → 一块黑。
     *
     * 判法跟 AOSP 官方 `VirtualDisplay Launcher` 示例一样：
     * `task.displayId == virtualDisplay.getDisplayId()`。
     *
     * ## 为什么要「判不准就当没黑」
     *
     * 拿不到我们的 displayId（反射失败）、或者一个 task 的 displayId 读不出来时，
     * **宁可漏判也不能误判** —— 误判的代价是**把用户正在用的应用重启**。
     *
     * @param ourDisplayId 我们这个承载面的 displayId（null = 取不到）
     * @param taskDisplayIds 承载应用那些 task 各自的 displayId（空 = 没找到 task）
     * @return true = **不在**我们的 display 上（= 黑屏）；false = 在 / 判断不了
     */
    fun isTaskOffDisplay(ourDisplayId: Int?, taskDisplayIds: List<Int>): Boolean =
        // 第224轮：判据下沉到 [HostTaskDisplay]（`ReadyStarter` 也要用同一份判断），
        // 这里改为**委托**，保证整条链路上只有一份实现、不会分叉。
        //
        // 语义完全一致：ourDisplayId == null → UNKNOWN → false；
        // 没有 task → NOT_FOUND → false；其余看是否有匹配。
        HostTaskDisplay.isOffScreenForBlackHeal(
            HostTaskDisplay.stateOf(ourDisplayId, taskDisplayIds),
        )

    /**
     * 这一轮该做什么动作。
     *
     * ## 判据（任一成立即判「黑屏」）
     *
     * 1. **[taskOffDisplay] = true** —— 承载应用的 Activity 不在我们的
     *    VirtualDisplay 上（它跑到别的显示器去了）。
     *    这就是黑屏的**成因本身**，不需要任何像素证据。
     * 2. **[sawContent] = true 且连续黑帧够多** —— 像素判黑。
     *    必须先见过应用画面（[sawContent]）才认这条 ——
     *    否则「我们采不到应用画面」（实测常态）会被当成「应用画面是黑的」，
     *    把正常承载的应用判成黑屏。
     * 3. **连续盲帧够多**（[blindMeansLost] 成立时）—— 长时间「看不见承载面」。
     *    承载面没有画面时，窗口缓冲在承载面位置是**透明的洞**，
     *    采样的第一表现是 BLIND 而不是黑帧（长黑轮真车证据）⇒ 必须有这条兜底。
     *
     * ## 然后
     *
     * 4. 冷却过了才动手（别连着重启）。
     * 5. 已重建过还黑 → **放弃**（绝不能无限重启承载应用）。
     *
     * @param healStage 已经自愈到第几级（[STAGE_NONE] = 还没自愈过）
     * @param sawContent 探针见过**应用画面**（像素判黑的第一道闸）
     * @param taskOffDisplay 承载应用的 Activity 不在我们的 VirtualDisplay 上
     * @param consecutiveBlack 连续全黑帧数
     * @param sinceLastHealMs 距上次自愈过了多久（`Long.MAX_VALUE` = 从没自愈过）
     * @param consecutiveBlind 连续「看不见承载面」的帧数
     * @param blindStreakLimit 连续盲帧到多少帧才算「没有画面」
     * @param blindMeansLost 当前配置下盲帧是否真的代表「没有画面」
     *   （见 [blindCanMeanLost]；半透明窗口下必须传 false）
     */
    fun decide(
        healStage: Int,
        sawContent: Boolean,
        taskOffDisplay: Boolean,
        consecutiveBlack: Int,
        sinceLastHealMs: Long,
        blackStreakLimit: Int = BLACK_STREAK_LIMIT,
        consecutiveBlind: Int = 0,
        blindStreakLimit: Int = BLIND_STREAK_LIMIT,
        blindMeansLost: Boolean = false,
    ): Decision {
        // —— 已经重建过还黑 → 放弃（绝不能无限重启承载应用）——
        if (healStage >= STAGE_REBUILD) {
            return Decision(Action.NONE, "已重建过承载面仍黑，放弃（不再反复重启）")
        }
        // —— 判据 1：承载应用的 Activity 不在我们的 VirtualDisplay 上（权威）——
        val blackByTask = taskOffDisplay
        // —— 判据 2：像素判黑（必须先证明采样能看到应用画面）——
        val blackByPixels = sawContent && consecutiveBlack >= blackStreakLimit
        // —— 判据 3：长时间「看不见承载面」（长黑轮新增）——
        //
        // 两道闸：
        // 1. [blindMeansLost]：当前配置下盲帧确实代表「没有画面」
        //    （见 [blindCanMeanLost]；半透明窗口下永不成立）；
        // 2. 已经证明过「采样能看到画面」（[sawContent]）—— 否则可能只是应用
        //    还没画出第一帧（冷启动），30s 内不碰它，避免把慢启动重启掉。
        //    例外：已经重发过一次启动还看不见（stage ≥ [STAGE_RELAUNCH]）时，
        //    不再等画面证据 —— 这次要升级到「重建承载面」才有救。
        val hasBlindEvidence = blindMeansLost && (sawContent || healStage >= STAGE_RELAUNCH)
        val blackByBlind = hasBlindEvidence && consecutiveBlind >= blindStreakLimit
        if (!blackByTask && !blackByPixels && !blackByBlind) {
            return Decision.NONE
        }
        // —— 冷却 ——
        val cooldown = cooldownFor(healStage)
        if (sinceLastHealMs < cooldown) {
            return Decision(Action.NONE, "冷却中（${sinceLastHealMs}ms < ${cooldown}ms）")
        }
        val why = when {
            blackByTask -> "承载应用的 Activity 不在我们的 VirtualDisplay 上"
            blackByPixels -> "连续黑帧 $consecutiveBlack 帧"
            else -> "连续 $consecutiveBlind 帧看不见承载面（窗口缓冲在承载面位置是透明洞，长时间没有画面）"
        }
        return Decision(
            if (healStage >= STAGE_RELAUNCH) Action.REBUILD else Action.RELAUNCH,
            why,
        )
    }
}
