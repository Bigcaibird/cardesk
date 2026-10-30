package com.cardesk.launcher.core.ambient

import kotlin.math.exp

/**
 * 氛围灯的**动感层**：把 10Hz 抖动的频谱目标值，变成平滑流动的灯光。
 *
 * ## 为什么要有这一层
 *
 * 以前是「频谱 → 颜色」**一步到位**：
 *
 * ```
 * 频谱(10Hz) ──► 选最响的低频段 ──► 色相 ──► 乘亮度 ──► 写车机
 * ```
 *
 * 三个地方天然是「跳」的：
 *
 * 1. **色相是离散挑出来的** —— [AmbientPalette.bassHue] 取「最响的那一段」，
 *    段与段之间差 `360 / 段数`（16 段就是 22.5°），
 *    鼓点一变最响的段就换 ⇒ 色相在档位之间**跳变**。
 * 2. **亮度直接乘进 RGB** —— 频谱的强弱原样落成明暗，
 *    于是灯跟着频谱一起抖（10Hz 的台阶）。
 * 3. **车机那边亮度只有三档**（HIGH / LOW / OFF），
 *    所以连续变化只能靠我们自己写 RGB，写一次是一级台阶。
 *
 * 结果就是「灯在跳」，不是「灯在流动」。
 *
 * ## 这一层做三件事
 *
 * | | 做法 | 效果 |
 * |---|---|---|
 * | **颜色** | 指数平滑 + **最短路径**插值 + **限速** | 颜色沿着色环**流动**，不跳段；359°→1° 走 2° 而不是绕 358° |
 * | **亮度** | **快起慢落**（attack / decay 两条时间常数） | 鼓点一响立刻亮，音乐停了慢慢暗下去 —— 而不是跟着频谱抖 |
 * | **律动** | 低频**起拍检测** + 衰减包络 | 每个鼓点叠一下「心跳」，灯有呼吸感 |
 *
 * ## 为什么是纯状态机
 *
 * 这一层**不碰 Android**（没有 Context / 没有 Handler），
 * 只吃「上一帧状态 + 这一帧目标 + 时间差」，出一帧新状态 ——
 * 所以能直接写 JVM 单测（见 `AmbientMotionTest`）。
 *
 * 帧率无关：平滑系数是 `1 − e^(−dt/τ)`，
 * 掉帧或变频率时观感一致（不会因为帧变慢就变得更快）。
 */
class AmbientMotion(
    /** 色相平滑的时间常数（毫秒）。越大越「黏」，越小越跟手。 */
    private val hueTauMs: Float = 320f,
    /** 色相移动速度上限（度/秒）。防止大跨度变化时灯「甩」过去。 */
    private val hueMaxDegPerSec: Float = 90f,
    /** 亮度**上升**的时间常数（毫秒）—— 鼓点来了要马上跟上。 */
    private val attackTauMs: Float = 50f,
    /** 亮度**下降**的时间常数（毫秒）—— 音乐停了要慢慢暗下去。 */
    private val decayTauMs: Float = 320f,
    /** 低频包络的时间常数（毫秒）—— 用来判断「是不是起拍了」。 */
    private val bassTauMs: Float = 160f,
    /** 起拍包络的衰减时间常数（毫秒）—— 一次起拍闪多久。 */
    private val pulseTauMs: Float = 170f,
    /** 起拍时亮度额外加多少（0..1 的比例）。 */
    private val pulseGain: Float = 0.45f,
    /** 起拍判定：低频能量低于这个值就不算起拍（避免底噪一直闪）。 */
    private val beatFloor: Float = 0.08f,
    /** 起拍判定：低频能量要比包络高这个比例才算「比刚才更响」。 */
    private val beatRatio: Float = 1.15f,
) {

    /**
     * 动感层的状态（**跨帧保留**）。
     *
     * @param hue 当前色相（0..360）
     * @param level 平滑后的响度（0..1）
     * @param pulse 起拍包络（0..1，1 = 刚起拍）
     * @param bassSlow 低频慢包络（0..1，起拍判定用）
     * @param brightness 最终亮度（0..1，= 响度 × 起拍增益）
     * @param initialized 是否已经出过第一帧（第一帧直接落到位，不从红色扫过去）
     */
    data class State(
        val hue: Float = 0f,
        val level: Float = 0f,
        val pulse: Float = 0f,
        val bassSlow: Float = 0f,
        val brightness: Float = 0f,
        val initialized: Boolean = false,
    )

    /**
     * 走一帧。
     *
     * @param prev 上一帧的状态
     * @param targetHue 目标色相（0..360）
     * @param targetLevel 目标亮度（0..100）
     * @param bass 低频能量（0..1）
     * @param dtMs 距离上一帧的毫秒数
     */
    fun step(
        prev: State,
        targetHue: Float,
        targetLevel: Int,
        bass: Float,
        dtMs: Long,
    ): State {
        // 时间差夹一下：切后台再回来可能隔了几分钟，
        // 那时不该「一步补上」几秒的变化，直接当成一帧处理。
        val dt = dtMs.coerceIn(1L, MAX_DT_MS).toFloat()
        val tgt = (targetLevel.coerceIn(0, 100)) / 100f
        val bassNow = bass.coerceIn(0f, 1f)

        // —— 亮度：快起慢落 ——
        val tau = if (tgt > prev.level) attackTauMs else decayTauMs
        val level = prev.level + (tgt - prev.level) * (1f - exp(-dt / tau))

        // —— 色相：指数平滑 → 最短路径 → 限速 ——
        val hue = if (!prev.initialized) {
            // 第一帧直接落到目标色相（别从 0° 扫过去，开灯时颜色是「亮」出来的）
            normalizeHue(targetHue)
        } else {
            val delta = shortestDelta(prev.hue, targetHue)
            val eased = delta * (1f - exp(-dt / hueTauMs))
            val maxStep = hueMaxDegPerSec * dt / 1000f
            normalizeHue(prev.hue + eased.coerceIn(-maxStep, maxStep))
        }

        // —— 起拍：低频比慢包络突然高出一截 ——
        val bassSlow = prev.bassSlow + (bassNow - prev.bassSlow) * (1f - exp(-dt / bassTauMs))
        val beat = bassNow > beatFloor && bassNow > bassSlow * beatRatio
        val pulse = if (beat) 1f else prev.pulse * exp(-dt / pulseTauMs)

        // —— 最终亮度：响度 × 起拍增益 ——
        val brightness = (level * (1f + pulse * pulseGain)).coerceIn(0f, 1f)

        return State(
            hue = hue,
            level = level.coerceIn(0f, 1f),
            pulse = pulse.coerceIn(0f, 1f),
            bassSlow = bassSlow.coerceIn(0f, 1f),
            brightness = brightness,
            initialized = true,
        )
    }

    companion object {
        /** 单帧时间差上限（毫秒）—— 切后台再回来时别「一步补上」几秒的变化。 */
        const val MAX_DT_MS = 1000L

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

        /** 色相归一到 0..360（与 [AmbientPalette.normalizeHue] 同义，这里自成一个纯函数层）。 */
        fun normalizeHue(hue: Float): Float {
            var h = hue % 360f
            if (h < 0f) h += 360f
            return h
        }
    }
}
