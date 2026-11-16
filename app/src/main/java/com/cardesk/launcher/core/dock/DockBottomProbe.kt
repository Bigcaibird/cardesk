package com.cardesk.launcher.core.dock

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 全局 Dock 的**底部占位**探测（第 159 轮）。
 *
 * ## 为什么要有这个对象
 *
 * 之前「别的 App 会不会被全局 Dock 盖住底部」这件事**说不清**：
 * - 代码里 [NavInsets.applied] 只看「覆盖包在系统里是不是 enabled」，
 *   而覆盖包 enabled **不等于** framework-res 的导航栏尺寸真的变了
 *   （第八十七轮的模拟器实测就是：overlay 显示 `[x]`，导航栏恒为 56px）；
 * - 诊断页里也**没有任何**关于 Dock 占位 / 预留区的输出，
 *   现场只能靠猜。
 *
 * 这个对象把「能测的」全部测出来，且**只读、不改任何运行时行为**：
 *
 * | 测什么 | 怎么测 | 回答什么问题 |
 * |---|---|---|
 * | 系统导航栏真实高度 | 读 `Resources.getSystem()` 里的 `navigation_bar_height` | 预留区（RRO）到底通没通 |
 * | 全局 Dock 窗口占哪一段 | 纯几何（与 [GlobalDockService] 同一公式） | Dock 压住了屏幕的哪一段 |
 * | 某个应用窗口是否铺满到底 | 纯几何（`overlapPx` / `reachesScreenBottom`） | 那个 App 会不会被压住 |
 *
 * ## 纯几何与 Android 依赖的分工
 *
 * 几何部分全部是纯函数（不碰 Android），可以 JVM 单测；
 * 需要 Android 的部分（读资源、读屏幕尺寸）用 `runCatching` 包着，
 * 读不到就返回 null，绝不因为诊断把桌面搞崩。
 */
object DockBottomProbe {

    /**
     * 全局 Dock 设计层总高（设计坐标）= Dock 本体 104。
     *
     * 第183轮：用户要求 Dock **紧贴屏幕底部**，原来 Dock 下面留的 8px 底边距
     * （104 + 8 = 112）被去掉，所以这条常量从 112 改成 104。
     */
    const val DESIGN_BAR_HEIGHT = 104

    /** 设计画布宽度（与 `Design.W` 一致）。 */
    const val DESIGN_WIDTH = 1920

    /**
     * 期望的系统导航栏预留高度（px，1920 宽的车机 1dp = 1px）。
     *
     * 第183轮起**直接派生自 [DESIGN_BAR_HEIGHT]**：预留区必须正好等于 Dock 占的高度，
     * 否则沉浸型 App 的底部内容会被 Dock 压住 / 或上方凭空多出一条空带。
     */
    const val EXPECTED_NAV_BAR_PX = DESIGN_BAR_HEIGHT

    private const val PREFS = "cardesk"

    /** 探测快照的键（服务写入，诊断页读取）。 */
    private const val KEY_SNAPSHOT = "dock_bottom_probe"

    /** `dumpsys window` 摘要最多留多少行（整份输出太长，诊断包会爆）。 */
    private const val MAX_DUMPSYS_LINES = 160

    /**
     * 用来读「系统导航栏高度」的资源名，按可能性排序。
     *
     * AOSP 是 `navigation_bar_height`；个别 ROM 会用别的名字，
     * 所以多试几个，读到哪个算哪个。
     */
    private val NAV_BAR_DIMENS = listOf(
        "navigation_bar_height",
        "navigation_bar_frame_height",
        "navigation_bar_height_landscape",
    )

    // ------------------------------------------------------------------ 纯几何

    /**
     * 全局 Dock 窗口高度（px）。
     *
     * 与 [GlobalDockService.applyGeometry] **同一套公式**：
     * 高度 = `round(屏宽 * 112 / 1920)` —— 按屏宽等比缩放，
     * 保证任何分辨率下 Dock 都「贴着底、和桌面里一样大」。
     */
    fun dockBarHeightPx(screenWidth: Int): Int =
        (screenWidth * DESIGN_BAR_HEIGHT.toFloat() / DESIGN_WIDTH.toFloat())
            .roundToInt()
            .coerceAtLeast(1)

    /** 全局 Dock 窗口的顶边 y（屏幕坐标，0 在顶部）。 */
    fun dockTopY(screenHeight: Int, screenWidth: Int): Int =
        screenHeight - dockBarHeightPx(screenWidth)

    /**
     * 一个「底边在 [windowBottom]」的应用窗口，被全局 Dock 压住多少 px。
     *
     * @return 重叠高度；没有重叠时是 0
     */
    fun overlapPx(windowBottom: Int, dockTop: Int): Int =
        (windowBottom - dockTop).coerceAtLeast(0)

    /**
     * 该应用窗口是不是**铺满到了屏幕底**（= 它没有避开系统底部预留区）。
     *
     * 导航页 / 音乐 App 这类**沉浸型**（自己 `hideNavigationBars`）会这样；
     * 而遵守系统导航栏 inset 的 App（QQ 音乐车机版就是）底边会停在预留区上沿。
     */
    fun reachesScreenBottom(windowBottom: Int, screenHeight: Int): Boolean =
        windowBottom >= screenHeight - 1

    // ------------------------------------------------------------------ 实测

    /**
     * 系统导航栏的**真实高度**（px）—— 预留区通没通的真值。
     *
     * ## 为什么不能只看覆盖包状态
     *
     * 覆盖包「enabled」只说明 `OverlayManager` 认它，
     * 不代表 framework-res 的 `navigation_bar_height` 真的变了。
     * 所以这里读的是**资源实测值**：被覆盖了就变，没被覆盖就是原值。
     *
     * 读不到返回 null（例如 ROM 根本没有这个 dimen）。
     */
    fun systemNavBarHeightPx(context: Context): Int? = runCatching {
        val sys = Resources.getSystem()
        for (name in NAV_BAR_DIMENS) {
            val id = sys.getIdentifier(name, "dimen", "android")
            if (id > 0) {
                val px = sys.getDimensionPixelSize(id)
                if (px > 0) return@runCatching px
            }
        }
        // 兜底：用本进程的 resources 再试一遍（部分 ROM 只在应用资源表里带覆盖）
        val app = context.resources
        for (name in NAV_BAR_DIMENS) {
            val id = app.getIdentifier(name, "dimen", "android")
            if (id > 0) {
                val px = app.getDimensionPixelSize(id)
                if (px > 0) return@runCatching px
            }
        }
        null
    }.getOrNull()

    /** 屏幕真实尺寸（px，含系统栏），与 [GlobalDockService.realSize] 同口径。 */
    fun screenSize(context: Context): Pair<Int, Int>? = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        dm.widthPixels to dm.heightPixels
    }.getOrNull()

    /**
     * 预留区到底通没通 —— 用实测导航栏高度与期望值比。
     *
     * @return null = 读不到（判不了）；true/false = 结论
     */
    fun reservedAreaEffective(context: Context): Boolean? {
        val actual = systemNavBarHeightPx(context) ?: return null
        // 期望是 112px（= Dock 104 + 底边距 8）。
        // 容差给 4px：不同 ROM 的密度换算可能差一两像素。
        return actual >= EXPECTED_NAV_BAR_PX - 4
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 写探测快照（由全局 Dock 服务在 tick 里调用）。
     *
     * 用 SharedPreferences 而不是内存：诊断页在**桌面进程**，
     * 全局 Dock 在**服务进程**，两者不共享内存。
     */
    fun writeSnapshot(context: Context, values: Map<String, Any?>) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SNAPSHOT, encode(values))
                .apply()
        }
    }

    /** 读探测快照（诊断页用）。 */
    fun snapshot(context: Context): Map<String, String> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return@runCatching emptyMap()
        decode(raw)
    }.getOrDefault(emptyMap())

    /** 把探测值编码成一行行 `key=value`（SharedPreferences 只能存字符串）。 */
    private fun encode(values: Map<String, Any?>): String =
        values.entries.joinToString("\n") { (k, v) -> "$k=${v ?: "-"}" }

    /** 解码 [encode] 的结果。 */
    private fun decode(raw: String): Map<String, String> =
        raw.lineSequence()
            .filter { it.contains('=') }
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .toMap()

    // ------------------------------------------------------------------ 诊断文本

    /**
     * 诊断页的一段文字：把「预留区通没通」「Dock 占哪一段」讲清楚。
     *
     * 结论口径：
     * - 导航栏实测高度 ≥ 112 → 预留区**已生效**，遵守 inset 的 App（QQ 音乐）不会被盖；
     * - 导航栏实测高度 < 112 → 预留区**没生效**，所有贴底的应用都有被盖风险；
     * - 读不到 → 判不了，让维护者看 `dumpsys window`。
     */
    fun diagnosticsText(context: Context): String {
        val sb = StringBuilder()
        val (w, h) = screenSize(context) ?: (0 to 0)
        val navBar = systemNavBarHeightPx(context)
        val dockHeight = if (w > 0) dockBarHeightPx(w) else 0
        val dockTop = if (h > 0) dockTopY(h, w) else 0

        sb.append("系统导航栏实测高度: ${navBar ?: "读不到"}px（期望 ≥$EXPECTED_NAV_BAR_PX 才算预留区生效）\n")
        sb.append(
            "预留区结论: " + when {
                navBar == null -> "读不到（看下面的 dumpsys window）"
                navBar >= EXPECTED_NAV_BAR_PX - 4 -> "已生效 → 遵守系统 inset 的 App 不会被盖"
                else -> "未生效 → 贴底的应用都有被盖风险"
            } + "\n",
        )
        sb.append("屏幕: ${w}x${h}  全局 Dock 窗口: 顶边 y=$dockTop  高 ${dockHeight}px（占底部 ${dockHeight}px）\n")
        sb.append("全局 Dock 窗口类型: 2024=导航栏面板(预留区已生效) / 2038=普通悬浮窗(盖在应用上)\n")

        val snap = snapshot(context)
        if (snap.isNotEmpty()) {
            sb.append("--- 全局 Dock 服务运行时采样 ---\n")
            snap.forEach { (k, v) -> sb.append("$k=$v\n") }
        } else {
            sb.append("（全局 Dock 服务还没写入采样，可能没开或服务没起）\n")
        }
        sb.append("\n--- dumpsys window 摘要（窗口真实 frame）---\n")
        sb.append(dumpsysWindowsSummary(context))
        return sb.toString()
    }

    /**
     * `dumpsys window windows` 的**精简摘要**。
     *
     * ## 为什么放诊断里
     *
     * 「某个 App 是不是铺满到了屏幕底」是**窗口属性**，
     * 只有 `dumpsys window` 的 frame 能作证。
     * 有了它，排查时能一眼看到：
     * - 前台 App 的 `frame=[..][1920,1080]` → 它铺到底了 → 会被全局 Dock 盖；
     * - 前台 App 的 `frame=[..][1920,968]` → 它避开了底部预留区 → 不会被盖。
     *
     * 只在诊断（用户主动触发）时跑一次，不是常驻开销。
     */
    fun dumpsysWindowsSummary(context: Context): String = runCatching {
        val proc = ProcessBuilder("dumpsys", "window", "windows")
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val keep = text.lineSequence().filter { line ->
            line.contains("Window{") ||
                line.contains("mCurrentFocus") ||
                line.contains("mFocusedApp") ||
                line.contains("Frames:") ||
                line.contains("mFrame=")
        }
        val body = keep.take(MAX_DUMPSYS_LINES).joinToString("\n")
        if (body.isBlank()) "(dumpsys window 没取到窗口帧)" else body
    }.getOrDefault("(dumpsys window 执行失败)")
}
