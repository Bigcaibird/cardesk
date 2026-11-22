package com.cardesk.launcher.core.dock

import android.content.Context
import android.provider.Settings
import com.cardesk.launcher.core.SystemOps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * `policy_control`（`Settings.Global`）里「沉浸哪一条状态栏/Dock」的唯一写入口。
 *
 * 第八十七轮（参考 uni-launcher `overlay/SystemNavigationPolicy.java`）：
 * 全局 Dock 生效时**不能**再对其它应用写 `immersive.navigation` ——
 * 那会把原生导航栏（连同它占的 112px 预留区）一起从窗口布局里摘掉，
 * 全局 Dock 就只能盖在应用内容上。所以：
 *
 * - 需要隐藏状态栏时只写 `immersive.status=*`（可带 `,-包名` 豁免）；
 * - 其它非 `immersive.*` 的策略段原样保留（别把别的功能正在用的策略抹掉）。
 *
 * ## 第193轮：这条规则变成**可执行的不变量**
 *
 * 第八十七轮时「覆盖包装在 /data 上永远不生效」，所以当时把 `navInsets`
 * 定成「只进 snapshot/日志，不改变输出」。
 *
 * 第192/193轮证明那个前提是错的 —— 覆盖包在 API 28 模拟器和这台真车上
 * **真的生效**（系统导航栏实测 112px，CarDesk 的 2024 面板与它同帧）。
 * 预留区既然是活的，`immersive.navigation=*` 就成了**杀死预留带的凶手**：
 *
 * 1. App 全屏 → Dock 盖住所有 App 的底部内容；
 * 2. WMS 给每个 App 都打上沉浸标志 → 全局的 `mLastSystemUiFlags` 永远带沉浸位
 *    → [ImmersiveDetector] 对谁都说「沉浸」→ **Dock 在几乎所有 App 里让位**；
 * 3. Dock 一让位，底下的原车 `CarNavigationBar` 窗口就露了出来。
 *
 * 所以规则落到 [merge] 里强制执行：**预留区在（`navInsets=true`）时，
 * 一律不写 `immersive.navigation=*`** —— 不管调用方传什么。
 * 原车 Dock 在这条路径上本来就被 CarDesk 的 2024 导航栏面板**顶替**了
 * （两者同帧、面板在上），语义上「原车 Dock 已隐藏」本来就成立。
 *
 * 全部 `runCatching`：没有 `WRITE_SECURE_SETTINGS` 时返回 false 由调用方降级。
 */
object SystemNavigationPolicy {

    private const val PREFS = "cardesk"

    /** 进入全局 Dock 前的 `policy_control` 原值（null 表示当时没有策略）。 */
    private const val KEY_ORIGINAL = "sys_nav_policy_original"
    private const val KEY_ORIGINAL_SAVED = "sys_nav_policy_original_saved"

    /** 自己最后一次写进去的值，restore 时用来判断「是否已被别的应用改掉」。 */
    private const val KEY_APPLIED = "sys_nav_policy_applied"
    private const val KEY_APPLIED_SAVED = "sys_nav_policy_applied_saved"

    /**
     * 纯函数：把 [current] 里所有 `immersive.*` 段剥掉，再按需要拼回。
     *
     * 语义：
     * - 先按 `:` 切段，丢弃空段和所有 `immersive.*` 段；
     * - [hideStatus] 为真 → 在剩余段后追加 `immersive.status=*`，
     *   [hideStatusPackages] 非空时按 `*,-pkg1,-pkg2` 追加豁免（这些应用**保留**状态栏）；
     * - [hideNavigation] 为真 → 追加 `immersive.navigation=*`；
     * - 两个开关都为假 → 只返回剩余段，空则返回 null（调用方写 `"null"` 复位）；
     * - 非沉浸策略段原样保留（别把别的功能正在用的策略抹掉）。
     *
     * ## 第193轮：[navInsets] 为真时**禁止**写 `immersive.navigation=*`
     *
     * 这一条以前是「navInsets 只进 snapshot/日志，不改变输出」（第八十七轮的假设：
     * 当时以为覆盖包装在 /data 上永远不生效，所以策略不能因为预留区而变）。
     *
     * **第192/193轮证明那个假设是错的**：覆盖包在 API 28 模拟器和这台真车上
     * **真的生效**（导航栏实测 112px，全局 Dock 窗口与它同帧）。
     * 于是预留区是**活的**，而 `immersive.navigation=*` 恰好是**杀死它的东西** ——
     * 它把系统导航栏连同 112px 预留带从**所有** App 的窗口布局里摘掉。
     *
     * 后果（2026-11-17 真车诊断包实测）：① App 全屏、Dock 盖住所有 App 底部；
     * ② WMS 给每个 App 都打上 `HIDE_NAVIGATION`，全局的 `mLastSystemUiFlags`
     * 永远带沉浸位 ⇒ [ImmersiveDetector] 对谁都说「沉浸」⇒
     * **Dock 在几乎所有 App 里让位**（桌面只靠 `homeForeground` 豁免才幸免）；
     * ③ Dock 一让位，底下的原车 `CarNavigationBar` 就露了出来。
     *
     * 所以规则改到**这一层**来强制：预留区在（`navInsets=true`）时，
     * 无论调用方传什么 [hideNavigation]，都**不写** `immersive.navigation=*`。
     * 原车 Dock 在这条路径上本来就被 CarDesk 的 2024 导航栏面板**顶替**了。
     *
     * 返回 null 表示不需要策略。
     */
    fun merge(
        current: String?,
        hideStatus: Boolean,
        hideStatusPackages: Set<String>,
        navInsets: Boolean,
        hideNavigation: Boolean = false,
        immersiveNavigationPackages: Collection<String> = emptyList(),
        preserveForeignImmersiveFull: Boolean = false,
    ): String? {
        val remaining = remainingSegments(current, preserveForeignImmersiveFull)
        val out = ArrayList<String>()
        // 第204轮：按 App 藏原车导航栏。
        //
        // 真车 A/B 实测：`immersive.navigation=<具体包>` 会让
        // `CarNavigationBar.mPolicyVisibility=false`（真的不画了），
        // 而只影响列出来的包 —— 和我们「对沉浸型 App 让位」正好配套。
        val navApps = immersiveNavigationPackages
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
        if (navApps.isNotEmpty()) {
            out.add(NavigationKey + "=" + navApps.joinToString(NavigationSeparator))
        } else if (hideNavigation && !navInsets) {
            // 第193轮：预留区在时禁止写 immersive.navigation=*（见 KDoc）。
            out.add(NavigationKey + "=*")
        }
        if (hideStatus) {
            val exemptions = hideStatusPackages
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { "-$it" }
                .toList()
            val status = (listOf("*") + exemptions).joinToString(ExemptionSeparator)
            out.add("immersive.status=$status")
        }
        if (out.isEmpty()) {
            return remaining.takeIf { it.isNotEmpty() }?.joinToString(SegmentSeparator)
        }
        return (remaining + out).joinToString(SegmentSeparator)
    }

    /** 读当前 `policy_control` → [merge] → 交给 `SystemOps.applyPolicyControl` 写回。 */
    fun apply(
        context: Context,
        hideStatus: Boolean,
        hideStatusPackages: Set<String>,
        navInsets: Boolean,
        hideNavigation: Boolean = false,
        immersiveNavigationPackages: Collection<String> = emptyList(),
        preserveForeignImmersiveFull: Boolean = false,
    ): Boolean {
        val prefs = prefs(context)
        val current = readPolicy(context)
        // 只记第一次进入前的原值：重复 apply（改隐藏名单）不能把上一次写进去的值当成原值，
        // 否则退出全局 Dock 时会「恢复」成自己写的策略而不是原车策略。
        if (!prefs.getBoolean(KEY_ORIGINAL_SAVED, false)) {
            prefs.edit()
                .putString(KEY_ORIGINAL, current)
                .putBoolean(KEY_ORIGINAL_SAVED, true)
                .apply()
            // 排障场景：退出后策略没还原 —— 先确认进入前到底是什么值
            Logx.d(LogTags.SYS, "全局 Dock：记录 policy_control 原值", "original" to current)
        }
        val value = merge(
            current,
            hideStatus,
            hideStatusPackages,
            navInsets,
            hideNavigation,
            immersiveNavigationPackages,
            preserveForeignImmersiveFull,
        )
        val ok = SystemOps.applyPolicyControl(context, value)
        if (ok) {
            prefs.edit()
                .putString(KEY_APPLIED, value)
                .putBoolean(KEY_APPLIED_SAVED, true)
                .apply()
            Logx.i(
                LogTags.SYS,
                "全局 Dock：policy_control 已应用",
                "value" to value,
                "hideStatus" to hideStatus,
                "hideStatusPackages" to hideStatusPackages.joinToString(","),
                "hideNavigation" to hideNavigation,
                "navInsets" to navInsets,
            )
        } else {
            // 排障场景：隐藏状态栏没反应 —— 多半是这台车机没有 WRITE_SECURE_SETTINGS
            Logx.w(
                LogTags.SYS,
                "全局 Dock：policy_control 应用失败",
                "value" to value,
                "hideStatus" to hideStatus,
                "hideNavigation" to hideNavigation,
                "navInsets" to navInsets,
            )
        }
        return ok
    }

    /**
     * 第204轮：把我们算出来的策略「覆盖进去」，但保留别人写的段。
     *
     * 场景：全局 Dock 关闭 / 没有预留区这两条路径以前直接 `OemDockPolicy.buildPolicyValue`
     * 算出整条策略、`SystemOps.applyPolicyControl` 一把写死 —— 真车上用户还有
     * hywebview 之类在用 `immersive.full`（按 App 隐藏状态栏+导航栏），
     * 这一把写就会把它清掉（第204轮实测：重启一次整条策略变成 null）。
     *
     * 现在的口径：
     * - `immersive.navigation` / `immersive.status` 是**我们**的段，用 [owned] 里的替换；
     * - 其它 `immersive.*`（重点是别人的 `immersive.full`）原样保留；
     * - 非沉浸段（别的功能写的 `foo=bar`）也保留。
     */
    fun overwriteOwned(context: Context, owned: String?): Boolean {
        val merged = mergePreserving(readPolicy(context), owned)
        val ok = SystemOps.applyPolicyControl(context, merged)
        Logx.i(
            LogTags.SYS,
            "全局 Dock：policy_control 合并写入",
            "owned" to owned,
            "merged" to merged,
            "ok" to ok,
        )
        return ok
    }

    /** [overwriteOwned] 的纯函数部分（可单测）。 */
    fun mergePreserving(current: String?, owned: String?): String? {
        val keep = current
            .orEmpty()
            .split(SegmentSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { seg ->
                when {
                    seg.startsWith(NavigationKey) -> false
                    seg.startsWith(StatusKey) -> false
                    else -> true
                }
            }
        val ours = owned
            .orEmpty()
            .split(SegmentSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !keep.contains(it) }
        val out = (keep + ours).joinToString(SegmentSeparator)
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * 把进入全局 Dock 之前的原值写回。
     *
     * 如果 `policy_control` 已经**不是**我们最后写进去的值（别的功能/应用改过），
     * 就不写回：覆盖别人的策略比残留一条自己的策略更糟，这里返回 false 让调用方
     * 知道「没恢复」，由上层决定是否要提示用户手工检查。
     */
    fun restore(context: Context): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ORIGINAL_SAVED, false)) {
            // 没进过全局 Dock：没有要恢复的东西，等价于恢复成功
            Logx.d(LogTags.SYS, "全局 Dock：没有记录过 policy_control 原值，跳过恢复")
            return true
        }
        val original = prefs.getString(KEY_ORIGINAL, null)
        val applied = if (prefs.getBoolean(KEY_APPLIED_SAVED, false)) {
            prefs.getString(KEY_APPLIED, null)
        } else {
            null
        }
        val current = readPolicy(context)
        if (!samePolicy(current, applied)) {
            // 排障场景：退出全局 Dock 后状态栏仍然隐藏 —— 先看策略是不是被别的应用改过
            Logx.w(
                LogTags.SYS,
                "全局 Dock：policy_control 已被其它应用修改，跳过写回",
                "current" to current,
                "appliedByCardesk" to applied,
                "original" to original,
            )
            return false
        }
        // 第204轮：恢复也要保住别人后加的段（真车上的 hywebview 会写 immersive.full）——
        // 原值可能是几天前记的 null，直接写回去等于把用户中途加的配置清空。
        val target = mergePreserving(current, original)
        val ok = runCatching { SystemOps.applyPolicyControl(context, target) }.getOrDefault(false)
        if (ok) {
            prefs.edit()
                .remove(KEY_ORIGINAL)
                .remove(KEY_ORIGINAL_SAVED)
                .remove(KEY_APPLIED)
                .remove(KEY_APPLIED_SAVED)
                .apply()
            Logx.i(
                LogTags.SYS,
                "全局 Dock：policy_control 已恢复",
                "original" to original,
                "written" to target,
            )
        } else {
            // 排障场景：恢复失败 —— 原值没丢，下次退出/重启还能再试
            Logx.w(LogTags.SYS, "全局 Dock：policy_control 恢复失败", "original" to original)
        }
        return ok
    }

    /** 诊断用：设置页/诊断包展示策略状态。 */
    fun snapshot(context: Context): Map<String, Any?> {
        val prefs = prefs(context)
        val originalSaved = prefs.getBoolean(KEY_ORIGINAL_SAVED, false)
        val appliedSaved = prefs.getBoolean(KEY_APPLIED_SAVED, false)
        val original = if (originalSaved) prefs.getString(KEY_ORIGINAL, null) else null
        val applied = if (appliedSaved) prefs.getString(KEY_APPLIED, null) else null
        val current = readPolicy(context)
        return linkedMapOf(
            "current" to current,
            "original" to original,
            "appliedByCardesk" to applied,
            "originalSaved" to originalSaved,
            "matchesApplied" to samePolicy(current, applied),
        )
    }

    // ---------------------------------------------------------------- 内部实现

    private const val SegmentSeparator = ":"
    private const val ExemptionSeparator = ","

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readPolicy(context: Context): String? = runCatching {
        Settings.Global.getString(context.contentResolver, SystemOps.POLICY_CONTROL)
    }.getOrNull()

    /** 剥掉空段与所有 `immersive.*` 段，保留其余策略（顺序不变）。 */
    private fun remainingSegments(
        current: String?,
        preserveForeignImmersiveFull: Boolean = false,
    ): List<String> = current
        .orEmpty()
        .split(SegmentSeparator)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { seg ->
            // immersive.navigation / immersive.status 归我们管，一律先剥掉再重写。
            // 第204轮起，别人写的 immersive.full 段在 preserveForeignImmersiveFull
            // 时原样保留：真车上有 hywebview 之类的应用靠它按 App 隐藏状态栏+导航栏，
            // 全剥掉会把用户已有的配置清空。
            when {
                seg.startsWith(NavigationKey) -> false
                seg.startsWith(StatusKey) -> false
                seg.startsWith(ImmersivePrefix) -> preserveForeignImmersiveFull
                else -> true
            }
        }

    /**
     * 比较两个 `policy_control` 值是否等价：null / 空串 / 字面量 `"null"` 都表示「无策略」。
     * `SystemOps.applyPolicyControl` 写 null 时会落成字符串 `"null"`，不能直接字符串比较。
     */
    private fun samePolicy(a: String?, b: String?): Boolean = normalize(a) == normalize(b)

    private fun normalize(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return trimmed.takeIf { it.isNotEmpty() && it != "null" }
    }

    private const val ImmersivePrefix = "immersive."

    /** 第204轮：按 App 藏原车导航栏用的键。 */
    private const val NavigationKey = "immersive.navigation"

    private const val StatusKey = "immersive.status"

    private const val NavigationSeparator = ","
}
