package com.cardesk.launcher.core.ambient

import kotlin.math.exp
import kotlin.math.sin

/**
 * 氛围灯的**动感层（V2）**：把 10Hz 抖动的频谱目标值，变成平滑流动的灯光。
 *
 * ## 与旧版（[AmbientMotionLegacy]）的三个差别
 *
 * | | 旧版 | V2 |
 * |---|---|---|
 * | 色相 | 一阶指数平滑 + **限速** | **二阶**（限速 + **限加速**） |
 * | 起拍 | 固定阈值 `bass > 0.08 && bass > slow × 1.15` | **自适应阈值** + 迟滞 + **最短间隔** |
 * | 空闲 | 冻在最后一个颜色 | **缓慢漂移 + 呼吸底色** |
 *
 * ## 为什么色相要「二阶」
 *
 * 一阶只能约束**速度**，约束不了**加速度**：
 * 大跨度变化时灯会「甩」过去，甩到一半突然改向 —— 这本身就是一种生硬。
 * 二阶多带一个角速度状态（`hueVel`），把角加速度也限住，
 * 灯就是「平滑地转过去」，而不是「转过去再顿一下」。
 *
 * ## 这一层是纯状态机
 *
 * 不碰 Android（没有 Context / 没有 Handler），
 * 只吃「上一帧状态 + 这一帧目标 + 时间差」，出一帧新状态 ——
 * 所以能直接写 JVM 单测。
 *
 * 帧率无关：平滑系数是 `1 − e^(−dt/τ)`，
 * 掉帧或变频率时观感一致（不会因为帧变慢就变得更快）。
 */
class AmbientMotion(
    /** 色相平滑的时间常数（毫秒）。越大越「黏」，越小越跟手。 */
    private val hueTauMs: Float = 260f,
    /** 色相角速度上限（度/秒）。防止大跨度变化时灯「甩」过去。 */
    private val hueMaxDegPerSec: Float = 60f,
    /** 色相角加速度上限（度/秒²）。二阶的核心，消除「一顿一顿」。 */
    private val hueMaxAccelDegPerSec2: Float = 240f,
    /**
     * 色相跳变步长（度）。0 = 连续平滑；>0 = 每个目标直接吸到最近的档位。
     *
     * 「跳跃」模式用它：色相不是滑过去的，是**跳**过去的。
     * 只加快平滑（tau / 限速 / 限加速）做不到真正的跳变 ——
     * 平滑器本来就是把跳变抹成滑动的东西。
     */
    private val hueJumpDeg: Float = 0f,
    /**
     * 跳变模式的**强制换色间隔**（毫秒）。0 = 只跟起拍（检测到鼓点才换）。
     *
     * 为什么需要它：起拍检测是内容驱动的，音乐没鼓点、或者鼓点被自适应阈值
     * 当成「一直很响」时就不换色 —— 灯又定住了。给一个上限间隔，保证
     * 「隔这么久至少换一次」，观感才是**一直在闪**，而不是偶尔跳一下。
     */
    private val jumpHoldMs: Long = 0L,
    /** 明度**上升**的时间常数（毫秒）—— 鼓点来了要马上跟上。 */
    private val attackMs: Float = 45f,
    /** 明度**下降**的时间常数（毫秒）—— 音乐停了要慢慢暗下去。 */
    private val decayMs: Float = 260f,
    /** 低频包络的时间常数（毫秒）—— 用来判断「是不是起拍了」。 */
    private val bassTauMs: Float = 160f,
    /** 起拍包络的衰减时间常数（毫秒）—— 一次起拍闪多久。 */
    private val pulseTauMs: Float = 170f,
    /** 起拍时明度额外加多少（0..1 的比例）。 */
    private val pulseGain: Float = 0.35f,
    /** 起拍判定的绝对下限（低于这个不算起拍，避免底噪一直闪）。 */
    private val beatFloor: Float = 0.12f,
    /** 起拍判定的自适应倍率：低频要比慢包络高这个比例才算「比刚才更响」。 */
    private val beatRatio: Float = 1.25f,
    /** 起拍最短间隔（毫秒）—— 防 120BPM 的八分音符被当成两次起拍。 */
    private val beatMinGapMs: Long = 240L,
    /** 空闲呼吸幅度（0..0.4）。 */
    private val breathAmount: Float = 0.10f,
) {

    /**
     * 动感层的状态（**跨帧保留**）。
     *
     * @param hue 当前色相（0..360）
     * @param hueVel 当前角速度（度/秒）—— 二阶状态
     * @param level 平滑后的明度（0..1）
     * @param chroma 平滑后的彩度（OKLCH 的 C）
     * @param pulse 起拍包络（0..1，1 = 刚起拍）
     * @param bassSlow 低频慢包络（0..1，起拍判定用）
     * @param brightness 最终明度（0..1，= 明度 × 起拍增益）
     * @param lastBeatAt 上一次起拍的时刻（毫秒，单调时钟）
     * @param initialized 是否已经出过第一帧（第一帧直接落到位，不从红色扫过去）
     */
    data class State(
        val hue: Float = 0f,
        val hueVel: Float = 0f,
        /**
         * 跳变模式下累计的「起拍换色」偏移（度，0..360）。
         *
         * 为什么不能只把 hue 加一档：色相每帧都要从目标重算（`snapHue(target)`），
         * 加在 hue 上下一帧就被覆盖回去了。偏移要单独记住，
         * 这样「鼓点跳色」才留得住。连续模式恒为 0。
         */
        val beatHueOffsetDeg: Float = 0f,
        val level: Float = 0f,
        val chroma: Float = 0f,
        val pulse: Float = 0f,
        val bassSlow: Float = 0f,
        val brightness: Float = 0f,
        val lastBeatAt: Long = 0L,
        /** 跳变模式下上一次**换色**的时刻（毫秒）—— 强制换色间隔用它计时。 */
        val lastHopAt: Long = 0L,
        val initialized: Boolean = false,
    )

    /**
     * 走一帧。
     *
     * @param prev 上一帧的状态
     * @param targetHue 目标色相（0..360）
     * @param targetLevel 目标明度（0..1）
     * @param targetChroma 目标彩度（OKLCH 的 C）
     * @param bass 低频能量（0..1）
     * @param dtMs 距离上一帧的毫秒数
     * @param nowMs 当前时刻（毫秒，单调时钟；用于起拍最短间隔与呼吸相位）
     * @param silent 是否处于「没有声音」状态（走空闲呼吸）
     */
    fun step(
        prev: State,
        targetHue: Float,
        targetLevel: Float,
        targetChroma: Float,
        bass: Float,
        dtMs: Long,
        nowMs: Long,
        silent: Boolean = false,
    ): State {
        // 时间差夹一下：切后台再回来可能隔了几分钟，
        // 那时不该「一步补上」几秒的变化，直接当成一帧处理。
        val dt = dtMs.coerceIn(1L, MAX_DT_MS).toFloat()
        val dtS = dt / 1000f
        val tgt = targetLevel.coerceIn(0f, 1f)
        val bassNow = bass.coerceIn(0f, 1f)

        // —— 起拍：自适应阈值 + 最短间隔 ——
        val bassSlow = prev.bassSlow +
            (bassNow - prev.bassSlow) * (1f - exp(-dt / bassTauMs))
        val threshold = maxOf(beatFloor, bassSlow * beatRatio)
        val gapOk = nowMs - prev.lastBeatAt > beatMinGapMs
        val beat = bassNow > threshold && gapOk
        val pulse = if (beat) 1f else prev.pulse * exp(-dt / pulseTauMs)
        val lastBeatAt = if (beat) nowMs else prev.lastBeatAt

        // —— 色相：二阶（限速 + 限加速）——
        // 起拍把色相往暖端推一点点：鼓点看得见，但不刺眼。
        //
        // 空闲时色相不再追最后一个目标，只做缓慢漂移（见下面的 idle 分支）：
        // 跟踪器会把色相拉回去，漂移就自己抵消掉了。
        val hueTarget = if (silent) prev.hue
        else normalizeHue(targetHue + pulse * BEAT_HUE_PUSH_DEG)
        var hue = prev.hue
        var hueVel = prev.hueVel
        var beatHueOffsetDeg = if (hueJumpDeg > 0f) prev.beatHueOffsetDeg else 0f
        var lastHopAt = prev.lastHopAt
        if (hueJumpDeg > 0f) {
            // 「跳跃」：不做跟踪，直接把色相吸到最近的档位 —— 颜色是「跳」过去的。
            // 平滑器只会把跳跃抹成滑动，那不是跳跃。
            //
            // 再加一条：**每个起拍再前进一档**。只靠质心慢慢挪，几十秒才换一次色；
            // 鼓点换色才是跳跃，所以拍子一到就走一格（拍间隔由 beatMinGapMs 管）。
            // 偏移单独记（见 State.beatHueOffsetDeg）：色相每帧都从目标重算，
            // 把「加一档」写进 hue 下一帧就会被覆盖，鼓点跳色留不住。
            //
            // 再加一条：**换色间隔封顶**。起拍检测是内容驱动的，没鼓点就不换色，
            // 灯又定住了 —— 隔 [jumpHoldMs] 至少换一次，才是「一直在闪」。
            val hop = beat || (jumpHoldMs > 0L && nowMs - prev.lastHopAt >= jumpHoldMs)
            if (hop) {
                beatHueOffsetDeg = normalizeHue(beatHueOffsetDeg + hueJumpDeg)
                lastHopAt = nowMs
            }
            hue = normalizeHue(snapHue(hueTarget, hueJumpDeg) + beatHueOffsetDeg)
            hueVel = 0f
        } else if (!prev.initialized) {
            // 第一帧直接落到目标色相（别从 0° 扫过去，开灯时颜色是「亮」出来的）
            hue = hueTarget
            hueVel = 0f
        } else {
            val delta = shortestDelta(prev.hue, hueTarget)
            // 一阶跟踪器的目标速度 = 角度差 / 时间常数（`delta / tauSeconds`）。
            //
            // `1 - exp(-dt / tau)` 是**这一帧收敛掉的比例**（无量纲）；
            // 除以帧长才变成「每秒收敛多少」的速度。
            // 少了这一步，这个数是**距离**却被当成**速度**用：
            // 色相以应有速度的 1/64 在爬，整条灯就一直是同一个底色。
            val share = 1f - exp(-dt / hueTauMs)
            val vDesired = clamp(
                delta * share / dtS,
                -hueMaxDegPerSec,
                hueMaxDegPerSec,
            )
            // 角加速度限幅 —— 这一步才是「一顿一顿」的解药
            val dv = clamp(
                vDesired - prev.hueVel,
                -hueMaxAccelDegPerSec2 * dtS,
                hueMaxAccelDegPerSec2 * dtS,
            )
            hueVel = prev.hueVel + dv
            hue = normalizeHue(prev.hue + hueVel * dtS)
        }

        // —— 明度：attack / decay 非对称 + 起拍脉冲 ——
        val base = if (silent) idleLevel(nowMs) else tgt
        val withPulse = clamp(base * (1f + pulse * pulseGain), 0f, 1f)
        val tau = if (withPulse > prev.level) attackMs else decayMs
        val level = prev.level + (withPulse - prev.level) * (1f - exp(-dt / tau))

        // —— 彩度：慢速平滑（颜色的「浓淡」不该跳）——
        // 这里夹的是**硬上限**：五彩模式会把彩度上限抬到 0.32，艺术层已经按 prefs
        // 夹过一次，运动层只兜住「谁传进来都不许超过 0.40」。
        val chromaTarget = targetChroma.coerceIn(0f, AmbientPalette.MAX_CHROMA_HARD)
        val chroma = prev.chroma +
            (chromaTarget - prev.chroma) * (1f - exp(-dt / CHROMA_TAU_MS))

        // —— 空闲：色相缓慢漂移，别把灯冻住 ——
        if (silent) {
            hue = normalizeHue(hue + SILENT_HUE_DRIFT_DEG_PER_SEC * dtS)
            hueVel = 0f
        }

        val brightness = level.coerceIn(0f, 1f)
        return State(
            hue = hue,
            hueVel = hueVel,
            beatHueOffsetDeg = beatHueOffsetDeg,
            level = level.coerceIn(0f, 1f),
            chroma = chroma.coerceIn(0f, AmbientPalette.MAX_CHROMA_HARD),
            pulse = pulse.coerceIn(0f, 1f),
            bassSlow = bassSlow.coerceIn(0f, 1f),
            brightness = brightness,
            lastBeatAt = lastBeatAt,
            lastHopAt = lastHopAt,
            initialized = true,
        )
    }

    /** 空闲时的呼吸明度（0..1）。 */
    private fun idleLevel(nowMs: Long): Float {
        if (breathAmount <= 0f) return 0f
        val phase = (nowMs / 1000.0) * (2.0 * Math.PI / SILENT_BREATH_PERIOD_SEC)
        return breathAmount * (0.5f + 0.5f * sin(phase).toFloat())
    }

    companion object {
        /** 单帧时间差上限（毫秒）—— 切后台再回来时别「一步补上」几秒的变化。 */
        const val MAX_DT_MS = 1000L

        /** 起拍把色相往暖端推多少度。 */
        const val BEAT_HUE_PUSH_DEG = 14f

        /** 空闲时色相的漂移速度（度/秒）。 */
        const val SILENT_HUE_DRIFT_DEG_PER_SEC = 2.2f

        /** 空闲呼吸的周期（秒）。 */
        const val SILENT_BREATH_PERIOD_SEC = 6.0

        /** 彩度平滑的时间常数（毫秒）。 */
        const val CHROMA_TAU_MS = 220f

        /**
         * 两个色相之间的**最短**角度差（结果在 `(-180, 180]`）。
         *
         * 色相是环形的：`359° → 1°` 只差 **2°**，绕远路会变成 358°（穿过整个色环）。
         * 所以插值必须走短边。
         */
        fun shortestDelta(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d <= -180f) d += 360f
            return d
        }

        /** 色相归一到 0..360。 */
        fun normalizeHue(hue: Float): Float {
            var h = hue % 360f
            if (h < 0f) h += 360f
            return h
        }

        /**
         * 把色相吸到最近的 `stepDeg` 档位（结果 0..360）。
         *
         * 6 档（60°）= 红/黄/绿/青/蓝/品红，驾驶时能一眼看出「颜色换了」；
         * 步长越小档位越多、跳变越细。步长夹到 `1..180`，
         * 180 相当于只有两个极性（暖/冷），240 以上没有意义（同一对档位重复）。
         */
        fun snapHue(hue: Float, stepDeg: Float): Float {
            val step = stepDeg.coerceIn(1f, 180f)
            return normalizeHue(Math.round(hue / step) * step)
        }

        private fun clamp(v: Float, lo: Float, hi: Float): Float =
            if (v < lo) lo else if (v > hi) hi else v
    }
}
