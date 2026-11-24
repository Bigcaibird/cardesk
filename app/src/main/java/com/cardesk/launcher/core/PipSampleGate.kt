package com.cardesk.launcher.core

/**
 * 画中画画面采样的**alpha 闸门** —— 纯逻辑，不碰 View（第224轮）。
 *
 * ## 为什么要有这个文件
 *
 * 用户报「导入视频壁纸后，画中画的高德跑首页来了」。实车日志里根因之一：
 *
 * ```
 * 画中画画面采样：状态变化 black=true maxLuma=0 avgLuma=0 brightPct=0 streak=1
 * 画中画黑屏自愈：重建承载面 pkg=com.autonavi.amapautp.yh reason=连续黑帧 3 帧
 * ```
 *
 * 而同一时刻**屏幕截图**上高德明明在画地图。
 *
 * 机制：
 *
 * 1. `ActivityView` 内部是 `SurfaceView`，画面画在**独立的 SurfaceFlinger 图层**上；
 * 2. cardesk **窗口缓冲**在那个位置是一个**透明的洞**（alpha = 0、RGB = 0）；
 * 3. `PixelCopy.request(window, rect, …)` 拷的是窗口缓冲 → 拷出来一片透明；
 * 4. 旧的判黑只看 RGB 亮度、**完全忽略 alpha** →
 *    「我们根本看不见应用画面」和「应用画面是黑的」在数据上完全一样 → 被判成黑屏。
 *
 * 视频壁纸会把 cardesk 窗口切成 `PixelFormat.TRANSLUCENT`（见
 * `LauncherView.applyVideoWindowFormat()`），半透明窗口下这个「洞」更容易是真的透明
 * → 采样恒为 0 → 连续 3 帧 → 触发「重建承载面」→ **重启高德**。
 *
 * ## 闸门规则
 *
 * - 不透明像素（alpha ≥ [OPAQUE_ALPHA_MIN]）占比低于 [OPAQUE_RATIO_MIN]
 *   ⇒ 这一帧判为 **[Frame.BLIND]（采样不可用）**：**不算黑**。
 * - 采样可用时：像素判黑成立 → [Frame.BLACK]，否则 → [Frame.CONTENT]。
 *
 * ## 长黑轮：盲帧**不清零**连续黑帧
 *
 * 旧规则是「盲帧既不算黑、也清零连续黑帧（连续黑必须是连续的）」。
 * 真车实测（导航应用画中画，承载面长时间没有画面）：
 *
 * ```
 * 重新看得见承载面 opaquePct=100 blind=14
 * 状态变化 black=true opaquePct=100 maxLuma=238 avgLuma=32 brightPct=1 streak=1
 * ```
 *
 * 即一段时间里**连续 14 帧（约 42 秒）全是 BLIND**，只在末尾夹了 1 帧 BLACK ——
 * 每来一帧盲帧就把黑帧计数清零 ⇒ 连续黑帧永远凑不满阈值 ⇒ 自愈永不触发 ⇒ 永久黑屏。
 *
 * 「看不见」不是「有画面」的反证：透明洞和真黑在用户眼里都是「没有画面」。
 * 所以盲帧改为**保持**连续黑帧计数（见 [nextBlackStreak]），
 * 而「长时间看不见」本身交给 `PipBlackHeal` 的盲帧超时判据兜底 ——
 * 那里才把它当成黑屏证据，并且只在「窗口不透明 / 镜像路线」这种
 * 盲帧确实代表「没有画面」的配置下才认。
 *
 * 只有真正看得见画面（[Frame.CONTENT]）才把连续黑帧清零。
 *
 * ## 为什么抽成纯逻辑
 *
 * 阈值与「盲帧不计黑、不清零」这条规则是**行为契约**，必须能在 JVM 单测里逐条钉死，
 * 而不是埋在 `PipFrameProbe` 的像素循环里靠人眼读。
 */
object PipSampleGate {

    /**
     * 「不透明像素」的 alpha 下限（0..255，128 ≈ 50%）。
     *
     * 半透明窗口里那个「洞」的 alpha = 0；我们自己的玻璃控件通常在 0.3～0.6，
     * 取 128 能把它们和真正的应用画面区分开。
     */
    const val OPAQUE_ALPHA_MIN = 128

    /**
     * 不透明像素**占比**低于这个值就判「采样不可用」（0.02 = 2%）。
     *
     * 与 `PipFrameProbe` 的亮像素阈值同口径：采样图 64×36 = 2304 像素
     * → 约 46 个不透明像素。窗口缓冲里的透明洞整片都是 alpha=0，
     * 远低于这条线；而真承载面（应用画面）几乎全不透明，远高于它。
     */
    const val OPAQUE_RATIO_MIN = 0.02

    /**
     * 一帧的采样判定。
     *
     * @property BLIND 采样不可用（看不见承载面 —— **不是黑**）
     */
    enum class Frame {
        /** 采样可用，画面有内容（非黑）。 */
        CONTENT,

        /** 采样可用，画面是黑的（像素判黑成立）。 */
        BLACK,

        /** 采样不可用：窗口缓冲在该位置是透明的洞 / 整片半透明。 */
        BLIND,
    }

    /** 这个像素算不算「不透明」。 */
    fun isOpaque(alpha: Int): Boolean = alpha >= OPAQUE_ALPHA_MIN

    /**
     * 不透明像素占比（0..1）。
     *
     * @param pixels `ARGB_8888` 像素数组（`Bitmap.getPixels` 的输出）
     */
    fun opaqueRatio(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var opaque = 0
        for (p in pixels) {
            if (isOpaque((p ushr 24) and 0xFF)) opaque++
        }
        return opaque.toDouble() / pixels.size
    }

    /**
     * 把「不透明占比 + 像素判黑」合成一帧判定。
     *
     * **alpha 闸门优先于像素判黑** —— 这一条就是第224轮的核心修复：
     * 看不见时一律 [Frame.BLIND]，绝不落到 [Frame.BLACK]。
     *
     * @param opaqueRatio 不透明像素占比（0..1）
     * @param blackByPixels RGB 亮度判黑是否成立（`PipFrameProbe.isBlackFrame`）
     */
    fun classify(opaqueRatio: Double, blackByPixels: Boolean): Frame = when {
        opaqueRatio < OPAQUE_RATIO_MIN -> Frame.BLIND
        blackByPixels -> Frame.BLACK
        else -> Frame.CONTENT
    }

    /**
     * 连续黑帧的下一步计数。
     *
     * - [Frame.BLACK] → +1；
     * - [Frame.BLIND] → **保持不变**（既不加、也不清零）；
     * - [Frame.CONTENT] → **清零**。
     *
     * ## 为什么盲帧不清零（长黑轮）
     *
     * 真车实测：承载面没有画面时，窗口缓冲在承载面位置是**透明的洞**，
     * 采样恒为 BLIND；而地图加载中的暗帧会零星判成 BLACK。
     * 两者交替时，旧规则（盲帧清零）让连续黑帧恒为 0 → 自愈永不触发。
     *
     * 「看不见」只说明这一帧没有证据，不是「有画面」的证据；
     * 清零只能由真正看得见画面（[Frame.CONTENT]）来做。
     * 「长时间一直看不见」由 `PipBlackHeal` 的盲帧超时判据单独兜底。
     */
    fun nextBlackStreak(previous: Int, frame: Frame): Int = when (frame) {
        Frame.BLACK -> previous + 1
        Frame.BLIND -> previous
        Frame.CONTENT -> 0
    }

    /**
     * 「见过应用画面」的下一步状态（粘性，见 `PipFrameProbe.sawContent`）。
     *
     * 只有 [Frame.CONTENT] 会把标志点亮；[Frame.BLIND] 既不算见过、也不算没见过 ——
     * 保持原值。像素判黑成立的前提仍然是「曾经真的看见过画面」。
     */
    fun nextSawContent(previous: Boolean, frame: Frame): Boolean =
        previous || frame == Frame.CONTENT
}
