package com.cardesk.launcher.core

/**
 * 换肤重建（切日夜 / 换主题）时，**旧的画中画承载面该怎么处理**（第139轮）。
 *
 * ## 背景：这一条老路走过两次弯路
 *
 * - **第十二轮**：换肤重建前**不释放**旧承载面 →
 *   旧 `ActivityView` + `VirtualDisplay` 泄漏，而且新承载面黑屏
 *   （高德的 task 还挂在旧 VirtualDisplay 上）。
 *   当时的修法是「重建前先 `release` 旧承载面」。
 * - **第139轮**（用户报的现象）：**切日夜时高德从画中画变成全屏**。
 *
 * 根因就在第十二轮那一步：`release` 会**销毁旧的 VirtualDisplay**，
 * 而**挂在它上面的承载应用 task 会被系统搬到主显示器**
 * （Android 9 的 `ActivityStackManager` 就是这么处理的；
 * AOSP 的 VirtualDisplay Launcher 示例也是靠 `moveTaskToDisplay`
 * 把 task 从默认显示器搬回虚拟显示器）。
 *
 * → 高德的 task 被搬到 display 0 → **高德全屏抢屏**，画中画只剩一块黑。
 *
 * ## 这一轮的修法：搬，而不是拆
 *
 * 换肤重建时**不拆**旧承载面，而是把它**摘下来交给新的承载面接着用**：
 *
 * - VirtualDisplay 全程不死 → 高德的 task 一直在我们的 display 上
 *   → 既不会被搬到主屏抢屏，也不会被重启（导航不中断）；
 * - 换肤只影响**我们自己的**外观，承载应用根本不该被牵动。
 *
 * ## 为什么不直接写成代码里的 if
 *
 * 这条决策有三条分支（主画 / 副画 / 搬完之后要不要重新拉起），
 * 每一支都对应一种「画中画坏掉」的用户可见故障。
 * 抽成纯逻辑是为了能在 JVM 单测里**逐条钉死**，
 * 而不是散在 `build()` 里靠人眼读。
 */
object PipRebuildPolicy {

    /** 旧承载面的处置方式。 */
    enum class Action {
        /**
         * **搬家**：把承载内容（`ActivityView`）摘出来，交给新的承载面接管。
         *
         * VirtualDisplay 不重建 → 承载应用的 task 不动。
         */
        TRANSFER,

        /**
         * **释放**：把承载面连同 VirtualDisplay 一起拆掉。
         *
         * 只在「这块承载面本来就不该再存在」时用
         * （没有承载内容 / 副画已经关掉了）。
         */
        RELEASE,
    }

    /**
     * 主画承载面怎么处置。
     *
     * 有承载内容就**搬家** —— 没有内容的话摘了也白摘，直接放掉。
     *
     * @param hasContent 旧承载面上是否真的有承载内容
     */
    fun mainAction(hasContent: Boolean): Action =
        if (hasContent) Action.TRANSFER else Action.RELEASE

    /**
     * 副画承载面怎么处置。
     *
     * 副画比主画多一条：**双画开着才有副画**。
     * 双画已经关掉时，副画的 VirtualDisplay 本来就该拆掉
     * （留着就是泄漏），所以走 [Action.RELEASE]。
     *
     * @param hasContent 旧副画上是否真的有承载内容
     * @param dualPip 双画是否开着
     * @param secondaryApp 副画选的应用（空 = 没选）
     */
    fun secondaryAction(hasContent: Boolean, dualPip: Boolean, secondaryApp: String): Action =
        if (hasContent && dualPip && secondaryApp.isNotBlank()) Action.TRANSFER else Action.RELEASE

    /**
     * 承载面的 surface 重建时，要不要**重新拉起承载应用**。
     *
     * - [Action.TRANSFER]（搬家）：**不要**。
     *   搬家过程中 surface 必然销毁重建一次，但承载应用的 task 一直好好的
     *   挂在**同一个 VirtualDisplay** 上 —— 重新拉起只会让它**从头再跑一遍**
     *   （高德退回首页、正在导航的路线断掉）。
     * - [Action.RELEASE]（释放）：**要**。
     *   承载面整个换掉了，新承载面必须自己把应用拉起来。
     *
     * @param action 这次采用的是哪种处置
     */
    fun shouldRelaunchOnRecreate(action: Action): Boolean = action != Action.TRANSFER
}
