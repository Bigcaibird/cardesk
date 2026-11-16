package com.cardesk.launcher.core.dock

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 第182轮：**任意 App 是否自己隐藏了导航栏**的运行时探测。
 *
 * ## 为什么需要它
 *
 * 用户要求「所有调用 `hideNavigationBars` 的 App 都让 Dock 让位」，
 * 而 CarDesk 是第三方进程，**拿不到别的 App 的窗口标志**（`WindowManager` 只给
 * 自己进程的 `WindowInsets`，`AccessibilityService` 也不暴露 sysui flags）。
 *
 * 唯一可行的路径：读系统的窗口策略 dump —— `dumpsys window policy` 里的
 * `mLastSystemUiFlags` 就是**当前焦点窗口**最后一次设置的 SystemUI 标志位，
 * 平台签名 / system uid 的包能执行 `dumpsys`（和 `settings` 一样走 shell）。
 *
 * 判定规则（和 `View.SYSTEM_UI_FLAG_*` 常量一致）：
 * - `HIDE_NAVIGATION = 0x2`
 * - `IMMERSIVE = 0x800` / `IMMERSIVE_STICKY = 0x1000`
 *
 * 三者任一出现 → 该 App 正在隐藏导航栏 → 全局 Dock 让位。
 *
 * ## 为什么要缓存
 *
 * 一次 `dumpsys` 要新起进程 + binder dump（几十毫秒到一两百毫秒），
 * 绝不能 1s 一次。所以：
 * - 只在**前台包名变化**时立刻探一次；
 * - 同一个包停留超过 [RECHECK_MS] 才再探（视频类 App 会在播放中切全屏）；
 * - 探测失败（没有 shell 权限 / 输出格式变了）返回 null，调用方回退到
 *   [com.cardesk.launcher.core.OemDockPolicy] 的已知名单，**绝不因为探测失败而乱让位**。
 */
object ImmersiveDetector {

    /** 同一个包多久重探一次。 */
    const val RECHECK_MS = 2_500L

    /** 最近一次探测结果（包名 + 时间戳 + 是否沉浸）。 */
    private var cachedPkg: String? = null
    private var cachedAt = 0L
    private var cachedHidden: Boolean? = null

    /**
     * 前台 App 是否正在隐藏导航栏。
     *
     * @return true=隐藏（应让位）/ false=没隐藏 / null=探测不可用（调用方回退名单）
     */
    @Synchronized
    fun navBarHidden(context: Context, pkg: String?, nowMs: Long = System.currentTimeMillis()): Boolean? {
        if (pkg.isNullOrBlank()) return null
        if (pkg == cachedPkg && nowMs - cachedAt < RECHECK_MS) return cachedHidden
        val flags = readSystemUiFlags()
        cachedPkg = pkg
        cachedAt = nowMs
        cachedHidden = flags?.let { looksImmersive(it) }
        return cachedHidden
    }

    /** 清缓存（服务重建 / 调试注入时用）。 */
    fun clear() {
        cachedPkg = null
        cachedAt = 0L
        cachedHidden = null
    }

    /** 从 `mLastSystemUiFlags=0x...` 判断是否隐藏了导航栏（纯逻辑，可单测）。 */
    fun looksImmersive(systemUiFlags: Int): Boolean {
        val hideNavigation = 0x2
        val immersive = 0x800
        val immersiveSticky = 0x1000
        return (systemUiFlags and (hideNavigation or immersive or immersiveSticky)) != 0
    }

    /** 从 dumpsys 文本里抠 `mLastSystemUiFlags=0x...`。 */
    fun parseSystemUiFlags(dump: String?): Int? {
        if (dump.isNullOrBlank()) return null
        val m = Regex("mLastSystemUiFlags=(0x[0-9a-fA-F]+)").find(dump) ?: return null
        return m.groupValues[1].removePrefix("0x").toIntOrNull(16)
    }

    private fun readSystemUiFlags(): Int? = runCatching {
        val proc = ProcessBuilder("sh", "-c", "dumpsys window policy")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        parseSystemUiFlags(out)
    }.onFailure {
        Logx.w(LogTags.DOCK, "读取系统 UISystemFlags 失败（回退名单判断）", it)
    }.getOrNull()
}
