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
 * ## 第193轮：全局沉浸策略会让这个探测**完全失效**
 *
 * `mLastSystemUiFlags` 是**全局**的一个值，不是「焦点窗口」的私有值：
 * 只要 `policy_control` 里有**对所有包生效**的沉浸策略
 * （`immersive.navigation=*` / `immersive.full=*`），
 * WMS 就会给**每一个** App 打上 `HIDE_NAVIGATION`/`IMMERSIVE`，
 * 这个值于是**永远**带那些位 —— 它反映的是**策略**，不是某个 App 的行为。
 *
 * 后果（2026-11-17 真车诊断包实测）：Dock 在**几乎所有** App 里让位，
 * 因为探测对谁都说「沉浸」。
 *
 * 所以这里加一道闸：`policy_control` 里有通配符沉浸策略时，
 * **不信任**这个探测 —— 返回 null（= 探测不可用），
 * 由调用方回退到已知名单，**宁可不让位，也不让所有 App 都让位**
 * （这正是本类注释里「绝不因为探测失败而乱让位」的由来）。
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
        // 第193轮：全局沉浸策略在场时，这个全局标志位反映的是策略而不是 App —— 不信任它。
        val policy = readPolicyControl(context)
        if (hasGlobalImmersivePolicy(policy)) {
            Logx.w(
                LogTags.DOCK,
                "沉浸探测失效：policy_control 有全局沉浸策略，mLastSystemUiFlags 不代表单个 App",
                "policy" to policy,
            )
            cachedPkg = pkg
            cachedAt = nowMs
            cachedHidden = null
            return null
        }
        if (pkg == cachedPkg && nowMs - cachedAt < RECHECK_MS) return cachedHidden
        val flags = readSystemUiFlags()
        cachedPkg = pkg
        cachedAt = nowMs
        cachedHidden = flags?.let { looksImmersive(it) }
        return cachedHidden
    }

    /**
     * `policy_control` 里有没有**对所有包生效**的沉浸策略（纯函数，可单测）。
     *
     * 判据：某一段是 `immersive.navigation=…` 或 `immersive.full=…`，
     * 且它的值里带**通配符 `*`**。
     *
     * - `immersive.navigation=*` → 全局沉浸，探测失效；
     * - `immersive.navigation=*,-com.foo` → 除豁免包外都是全局沉浸，探测同样失效；
     * - `immersive.navigation=com.ecarx.cardesk` → 只针对一个包，
     *   别的 App 前台时这个标志位仍然有意义 → **不**算全局。
     */
    fun hasGlobalImmersivePolicy(policy: String?): Boolean {
        if (policy.isNullOrBlank()) return false
        return policy.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { segment ->
                val eq = segment.indexOf('=')
                if (eq <= 0) return@any false
                val key = segment.substring(0, eq).trim()
                if (key != "immersive.navigation" && key != "immersive.full") return@any false
                segment.substring(eq + 1)
                    .split(',')
                    .map { it.trim() }
                    .any { it == "*" }
            }
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

    /** 读当前 `policy_control`（读不到返回 null）。 */
    private fun readPolicyControl(context: Context): String? = runCatching {
        android.provider.Settings.Global.getString(
            context.contentResolver,
            com.cardesk.launcher.core.SystemOps.POLICY_CONTROL,
        )
    }.getOrNull()
}
