package com.cardesk.launcher.core

/**
 * 设置应用闸口（纯逻辑）。
 *
 * ## 为什么需要它
 *
 * 设置页里**每改一个开关**都会走
 * [com.cardesk.launcher.ui.LauncherView.onSettingsApplied]：那一步会把
 * 「系统级策略」「整屏壁纸 / 玻璃底图」「画中画承载面」这些**和本次修改无关**的东西
 * 整套重跑一遍。在车机上每一套都有可见代价：
 *
 * - **重复写 `policy_control`**：写入路径是「先复位成 null，再写真值」，
 *   系统栏与窗口 insets 会被重新计算一次 —— 屏幕上就是一下闪。
 *   真车诊断日志里两次写入的字符串**完全相同**，也就是这一次写是纯冗余的；
 * - **重复重算整屏壁纸 / 玻璃底图**：主线程要画 192×108 代理图 + 480×270 玻璃底图，
 *   车机 SoC 上几十毫秒起，掉帧看得出来；
 * - **重复刷新画中画**：会把已经挂着的承载面再设一遍可见性 / 再问一次渲染路线。
 *
 * ## 为什么做成纯函数
 *
 * 判据是「值真的变了没有」，与 Android 无关，可以脱离设备直接单测；
 * 而且判据只有一个方向 —— **只有确认没变才跳过**，写错最坏也只是多跑一次，
 * 不会让设置不生效。
 *
 * 覆盖：[SettingsApplyGateTest]。
 */
internal object SettingsApplyGate {

    /** [shouldWriteOverscan] 的「还没写过」哨兵值（overscan 的合法值是 -200~200）。 */
    const val UNSET_OVERSCAN: Int = Int.MIN_VALUE

    /**
     * `policy_control` 的「空策略」有几种等价写法：`null` / 空串 / 字面量 `"null"`。
     *
     * 系统读回来的是字符串，`Settings.Global` 里没写过时可能是 `null`，
     * 我们复位时写的是 `"null"`，别的应用也可能写空串 —— 判定「变了没有」之前先归一。
     */
    private fun normalizePolicy(value: String?): String? {
        val text = value?.trim().orEmpty()
        return if (text.isEmpty() || text == "null") null else text
    }

    /**
     * 该不该写 `policy_control`：目标值与系统当前值（归一后）相同就不写。
     *
     * 注意判据用的是**系统里的实际值**，不是我们自己缓存的「上次写了什么」——
     * 策略可能被别的应用改掉，缓存会漏掉这种情况；读一次 `Settings.Global` 很便宜，
     * 而且本来写完也要回读校验。
     */
    fun shouldWritePolicy(current: String?, target: String?): Boolean =
        normalizePolicy(current) != normalizePolicy(target)

    /**
     * 该不该再写一次 overscan：目标值没变就不写。
     *
     * 写 overscan 要反射 `Display.setOverscan`，多数车机上这个方法不对外开放
     * （真车日志每次都是「反射 setOverscan 不可用」），重复调用只剩异常构造与日志开销。
     */
    fun shouldWriteOverscan(lastApplied: Int, next: Int): Boolean =
        lastApplied == UNSET_OVERSCAN || lastApplied != next

    /**
     * 影响画中画承载面的输入。
     *
     * - [navReady]：导航页建好了没有（没建好时 [refreshPip] 直接返回，什么也没应用）；
     * - [hostingStarted]：承载闸口开没开（同上，没开就白跑）；
     * - [dualPip] / [pipApp] / [pip2App] / [fullscreen]：主副画与全屏状态；
     * - [translucentWindow]：窗口当前是不是半透明（决定承载面走 LAYER 还是 MIRROR 路线）。
     *
     * 前两项刻意放进来：刷新被提前返回时，输入与「真的应用过一次」时不同，
     * 下一次状态变化仍然会刷新，不会卡在「永远跳过」。
     */
    data class PipInputs(
        val navReady: Boolean,
        val hostingStarted: Boolean,
        val dualPip: Boolean,
        val pipApp: String,
        val pip2App: String,
        val fullscreen: Boolean,
        val translucentWindow: Boolean,
    )

    /** 该不该刷新画中画：与上一次真正刷新时的输入完全相同就不刷。 */
    fun shouldRefreshPip(last: PipInputs?, next: PipInputs): Boolean = last != next
}
