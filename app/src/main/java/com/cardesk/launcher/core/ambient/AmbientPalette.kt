package com.cardesk.launcher.core.ambient

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 氛围灯颜色（一组 RGB + 独立亮度）。
 *
 * @param r/g/b 颜色（0..255，满亮度）
 * @param brightness 亮度（0..100，独立于颜色 —— 车机氛围灯一般是一套灯珠 + 一个亮度）
 */
data class AmbientColor(val r: Int, val g: Int, val b: Int, val brightness: Int) {
    /** `#RRGGBB` 形式（调试 / 日志用）。 */
    fun toHex(): String = "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

/**
 * 频谱 → 氛围灯颜色（纯函数，第七十轮）。
 *
 * 三种颜色模式：
 *
 * | 模式 | 色相来源 | 亮度来源 |
 * | --- | --- | --- |
 * | [MODE_BASS] 跟随低频 | 低频段里**最响**的那一段 | 低频能量 |
 * | [MODE_SPECTRUM] 跟随频谱 | 全部频段按能量做**圆周加权平均** | 整体电平 |
 * | [MODE_FIXED] 固定色 | 设定好的固定色相 | 整体电平 |
 *
 * 「跟随低频」是音乐氛围灯最常见的做法：鼓点一响，灯就跟着那个颜色跳。
 * 「跟随频谱」则更像一条彩虹随音乐流动。
 *
 * ## 为什么用圆周平均
 *
 * 色相是**环形**的：359° 和 1° 只差 2°，但算术平均会得到 180°（青绿）——
 * 正好是最离谱的结果。所以这里按 `cos/sin` 做圆周平均。
 */
object AmbientPalette {

    /** 跟随低频。 */
    const val MODE_BASS = 0

    /** 跟随频谱。 */
    const val MODE_SPECTRUM = 1

    /** 固定色。 */
    const val MODE_FIXED = 2

    /** 模式文案。 */
    val MODE_LABELS = arrayOf("跟随低频", "跟随频谱", "固定色")

    /** 固定色预设（色相 → 名称），设置页用。 */
    val FIXED_COLORS = listOf(
        30 to "橙",
        0 to "红",
        330 to "粉",
        280 to "紫",
        210 to "蓝",
        180 to "青",
        120 to "绿",
    )

    /** 固定色预设的文案。 */
    val FIXED_COLOR_LABELS: List<String> = FIXED_COLORS.map { it.second }

    /** 「低频」取前几段（16 段时取前 4 段 ≈ 40Hz~400Hz）。 */
    const val BASS_BANDS = 4

    // ------------------------------------------------------------------ V2 参数

    /**
     * 色相带下限（青，170°）。
     *
     * 限制在 170°~320° 是为了**避开刺眼的亮黄**（人眼在 570nm 最敏感，
     * 亮黄氛围灯既刺眼又影响驾驶），同时保留 150° 的色相行程。
     */
    const val HUE_BAND_LOW = 170f

    /** 色相带上限（品红紫，320°）。 */
    const val HUE_BAND_HIGH = 320f

    /**
     * 默认色相跨度（`HUE_BAND_HIGH - HUE_BAND_LOW` = 150°）。
     *
     * 色相跨度是「五彩」的唯一入口：把它放大到 360 就是整圈彩虹，
     * 但默认必须是 150 —— 默认观感与第八十八轮**逐位一致**。
     */
    const val HUE_BAND_SPAN = HUE_BAND_HIGH - HUE_BAND_LOW

    /** 色相跨度上限（整圈）。设置页滑杆的最大值。 */
    const val HUE_SPAN_MAX = 360f

    /** 色相跨度下限（原窄带，150°）。 */
    const val HUE_SPAN_MIN = HUE_BAND_SPAN

    /**
     * 彩虹预设的质心窗口下沿。
     *
     * 感知层把每段归一化到自己的参考值附近，真实音乐的谱质心其实只在
     * **0.3~0.6** 之间移动 —— 直接乘 360° 也只走 110°，看着仍然「偏蓝」。
     * 所以要把常见的 [0.20, 0.65] 这一段**拉伸**回 [0, 1] 再映射，
     * 色相才真的会走满一圈。这与明度那一路的 `(rel - 0.22) / 0.78` 是同一手法。
     */
    const val RAINBOW_CENTROID_WINDOW_LOW = 0.20f

    /** 彩虹预设的质心窗口跨度（0.20 + 0.45 = 0.65 上沿）。 */
    const val RAINBOW_CENTROID_WINDOW_SPAN = 0.45f

    /**
     * 彩虹预设的彩度上限。
     *
     * 0.22 的彩度在红/绿区会看起来发灰（「五彩但灰扑扑」），
     * 抬到 0.32 才真的「五颜六色」。仍受 [MAX_CHROMA_HARD] 硬保护。
     */
    const val RAINBOW_MAX_CHROMA = 0.32f

    /** 彩度的硬上限（用户滑杆也到不了这里，防止灯珠过饱和溢出）。 */
    const val MAX_CHROMA_HARD = 0.40f

    /** 色相自转的转速上限（度/秒）。60Hz 渲染 + 90°/s 色相限速下跟得上。 */
    const val MAX_HUE_ROTATE_DEG_PER_SEC = 30f

    /** 「跟随低频」算低频质心时取前几段。 */
    const val LOW_CENTROID_BANDS = 8

    /** 明度区间映射的下沿：相对电平低于它 → 底光。 */
    const val LEVEL_WINDOW_LOW = 0.22f

    /** 明度区间映射的跨度：相对电平 [0.22, 1.0] → [0, 1]。 */
    const val LEVEL_WINDOW_SPAN = 0.78f

    /** 对比曲线基准 gamma（灵敏度 = 1 时）。 */
    const val LEVEL_GAMMA_BASE = 1.45f

    /** 灵敏度对 gamma 的影响系数（灵敏度每 +1，gamma 降这么多的一半）。 */
    const val LEVEL_GAMMA_SLOPE = 0.5f

    /** 饱和度（OKLCH chroma）的上限。 */
    const val MAX_CHROMA = 0.22f

    /** 灵敏度下限（曲线指数）。 */
    const val MIN_SENSITIVITY = 0.3f

    /** 灵敏度上限。 */
    const val MAX_SENSITIVITY = 3f

    /**
     * 频谱 → 氛围灯颜色。
     *
     * @param bands 频段能量（0..1）
     * @param level 整体电平（0..1）
     * @param mode 颜色模式（[MODE_BASS] / [MODE_SPECTRUM] / [MODE_FIXED]）
     * @param fixedHue 固定色相（0..360）
     * @param sensitivity 灵敏度（0.3..3，越大越灵敏 → 弱音也亮）
     * @param minBrightness 最低亮度（0..100）
     * @param maxBrightness 最高亮度（0..100）
     * @param fallbackHue 没有声音时保留的色相（避免灯每帧乱跳）
     */
    fun colorFrom(
        bands: FloatArray,
        level: Float,
        mode: Int,
        fixedHue: Float,
        sensitivity: Float,
        minBrightness: Int,
        maxBrightness: Int,
        fallbackHue: Float = 0f,
    ): AmbientColor {
        val hue = targetHue(bands, mode, fixedHue, fallbackHue)
        val brightness = targetLevel(bands, level, mode, sensitivity, minBrightness, maxBrightness)
        val rgb = hsvToRgb(hue, SATURATION, 1f)
        return AmbientColor(rgb[0], rgb[1], rgb[2], brightness)
    }

    /**
     * 目标**色相**（0..360）。
     *
     * 单独拆出来是为了给 [AmbientMotion] 做平滑 ——
     * 平滑要的是「色相」本身，不是已经转成 RGB 的颜色
     * （RGB 再转回色相会有量化误差）。
     *
     * @param fallbackHue 没有声音时保留的色相（**由动感层传入当前色相**，
     *   这样没声音时灯不会乱跳）
     */
    fun targetHue(
        bands: FloatArray,
        mode: Int,
        fixedHue: Float,
        fallbackHue: Float = 0f,
    ): Float = when (mode) {
        MODE_SPECTRUM -> spectrumHue(bands, fallbackHue)
        MODE_FIXED -> normalizeHue(fixedHue)
        else -> bassHue(bands, fallbackHue)
    }

    /**
     * 目标**亮度**（0..100）。
     *
     * 亮度来源跟着颜色模式走：
     * - 跟随频谱 / 固定色 → 整体电平；
     * - 跟随低频 → 低频能量（鼓点一响就亮）。
     */
    fun targetLevel(
        bands: FloatArray,
        level: Float,
        mode: Int,
        sensitivity: Float,
        minBrightness: Int,
        maxBrightness: Int,
    ): Int {
        val lo = minBrightness.coerceIn(0, 100)
        val hi = maxBrightness.coerceIn(0, 100)
        val source = when (mode) {
            MODE_SPECTRUM -> level
            MODE_FIXED -> level
            else -> bassEnergy(bands)
        }
        return brightnessFor(source, sensitivity, lo, hi)
    }

    /**
     * 由色相 + 亮度直接产出颜色（亮度**整体缩放** RGB）。
     *
     * 这是动感层算完之后的那一步：
     * 色相是平滑过的、亮度是平滑过的（还叠了起拍包络），
     * 落到车上就是「流动的明暗」。
     */
    fun colorOf(hue: Float, brightness: Int): AmbientColor {
        val rgb = hsvToRgb(hue, SATURATION, 1f)
        val b = brightness.coerceIn(0, 100)
        return scaleByBrightness(AmbientColor(rgb[0], rgb[1], rgb[2], b))
    }

    /** 颜色的饱和度（固定值：车机氛围灯是灯珠，饱和度拉满会显得脏）。 */
    const val SATURATION = 0.85f

    /** 低频能量（前 [BASS_BANDS] 段的最大值）。 */
    fun bassEnergy(bands: FloatArray): Float {
        if (bands.isEmpty()) return 0f
        val n = minOf(BASS_BANDS, bands.size)
        var peak = 0f
        for (i in 0 until n) if (bands[i] > peak) peak = bands[i]
        return peak
    }

    /** 低频模式下的色相：取低频段里最响的那一段对应的色相。 */
    fun bassHue(bands: FloatArray, fallbackHue: Float): Float {
        if (bands.isEmpty()) return normalizeHue(fallbackHue)
        val n = minOf(BASS_BANDS, bands.size)
        var best = 0
        var bestVal = 0f
        for (i in 0 until n) {
            if (bands[i] > bestVal) {
                bestVal = bands[i]
                best = i
            }
        }
        if (bestVal <= 0f) return normalizeHue(fallbackHue)
        // 第八十五轮：**频谱分辨不了低频区时，别装作有信息**。
        //
        // 采集点数太少时（512 点 @ 44.1kHz ⇒ bin 间隔 86Hz），
        // 60~149Hz 那四段会**全部落进同一个 bin** ⇒ 四段功率永远相等 ⇒
        // 「取最响的那一段」永远取到第 0 段 ⇒ **色相恒为 0°（红）**，
        // 低频能量也饱和 ⇒ 亮度钉死 —— 灯是红的、拉满、一动不动。
        //
        // 这种情况下「音高 → 色相」是**假信息**，于是改成用低频**强度**映射色相：
        // 安静 = 深紫，响 = 橙红。宁可给一条粗但**真的**曲线，
        // 也不要给一条卡住不动、看起来像「没声音」的假曲线。
        if (isBassUnresolvable(bands, n)) return intensityHue(bestVal)
        return hueForBand(best, bands.size)
    }

    /**
     * 低频几段是不是**分辨不出来**（全都一样）。
     *
     * 判据：前 [BASS_BANDS] 段的能量**彼此相等**（差值在 [DEGENERATE_EPSILON] 内）
     * 且**不为零**（为零是「没声音」，那种情况走别的分支）。
     *
     * 相等意味着这几段读的是同一批 FFT bin —— 也就是硬件分辨不了这个音区。
     */
    fun isBassUnresolvable(bands: FloatArray, count: Int = BASS_BANDS): Boolean {
        val n = minOf(count, bands.size)
        if (n <= 1) return false
        val first = bands[0]
        if (first <= 0f) return false
        for (i in 1 until n) {
            if (kotlin.math.abs(bands[i] - first) > DEGENERATE_EPSILON) return false
        }
        return true
    }

    /**
     * 低频**强度** → 色相（频谱分辨不了低频区时的兜底）。
     *
     * 安静（0）= 深紫 260°，最响（1）= 橙红 20° —— 线性插值，
     * 中间是蓝、青、绿 —— 跟「安静偏冷、响偏暖」的直觉一致。
     */
    fun intensityHue(level: Float): Float {
        val t = level.coerceIn(0f, 1f)
        return normalizeHue(INTENSITY_HUE_COLD - (INTENSITY_HUE_COLD - INTENSITY_HUE_WARM) * t)
    }

    /** 强度兜底的「冷端」色相（安静时）。 */
    const val INTENSITY_HUE_COLD = 260f

    /** 强度兜底的「暖端」色相（最响时）。 */
    const val INTENSITY_HUE_WARM = 20f

    /** 「两段能量相等」的判定容差。 */
    const val DEGENERATE_EPSILON = 0.02f

    /** 频谱模式下的色相：按能量做圆周加权平均。 */
    fun spectrumHue(bands: FloatArray, fallbackHue: Float): Float {
        if (bands.isEmpty()) return normalizeHue(fallbackHue)
        var sumW = 0f
        var sumX = 0f
        var sumY = 0f
        for (i in bands.indices) {
            val w = bands[i]
            if (w <= 0f) continue
            val rad = Math.toRadians(hueForBand(i, bands.size).toDouble())
            sumX += w * cos(rad).toFloat()
            sumY += w * sin(rad).toFloat()
            sumW += w
        }
        if (sumW <= 0.0001f) return normalizeHue(fallbackHue)
        val deg = Math.toDegrees(kotlin.math.atan2(sumY.toDouble(), sumX.toDouble())).toFloat()
        return normalizeHue(deg)
    }

    /** 第 i 段对应的色相（0..360，低频偏红、高频偏紫）。 */
    fun hueForBand(index: Int, count: Int): Float {
        if (count <= 0) return 0f
        return normalizeHue(360f * index / count)
    }

    /**
     * 电平 → 亮度。
     *
     * 曲线：`brightness = min + (max − min) × level^(1/sensitivity)`。
     * 灵敏度 1 = 线性；大于 1 时弱音更亮（灯更跟得上）；小于 1 时只有很响才亮。
     */
    fun brightnessFor(level: Float, sensitivity: Float, min: Int, max: Int): Int {
        val lo = min.coerceIn(0, 100)
        val hi = max.coerceIn(0, 100)
        if (hi <= lo) return lo
        val s = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val t = level.coerceIn(0f, 1f).toDouble()
        val curve = if (t <= 0.0) 0.0 else t.pow(1.0 / s.toDouble())
        return (lo + (hi - lo) * curve).roundToInt().coerceIn(lo, hi)
    }

    /**
     * 按亮度把颜色**整体缩放**（频谱的强弱 → 看得见的明暗）。
     *
     * ## 为什么要有这一步
     *
     * 车机那边的亮度只有 HIGH / LOW / OFF 三档，写下去的是**用户拖的绝对亮度**；
     * 而「灵敏度 / 最低亮度 / 最高亮度」三个设置影响的是 [brightnessFor] 的结果。
     * 如果不把亮度落到颜色上，这三个设置就**完全没有效果**（灯既不亮也不暗）。
     *
     * 缩放之后：鼓点一响颜色变亮，安静时压暗 —— 「音乐律动」看得出来，
     * 而亮度档位由用户说了算。
     */
    fun scaleByBrightness(color: AmbientColor): AmbientColor {
        val k = color.brightness.coerceIn(0, 100) / 100f
        return AmbientColor(
            (color.r * k).roundToInt().coerceIn(0, 255),
            (color.g * k).roundToInt().coerceIn(0, 255),
            (color.b * k).roundToInt().coerceIn(0, 255),
            color.brightness,
        )
    }

    /** 色相归一到 0..360。 */
    fun normalizeHue(hue: Float): Float {
        var h = hue % 360f
        if (h < 0f) h += 360f
        return h
    }

    /** HSV → RGB（`v` 取 1，饱和度可调）。返回 `[r, g, b]`。 */
    fun hsvToRgb(hue: Float, saturation: Float, value: Float): IntArray {
        val h = normalizeHue(hue) / 60f
        val c = value * saturation
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val m = value - c
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return intArrayOf(
            ((r1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255).roundToInt().coerceIn(0, 255),
        )
    }

    /**
     * 频谱**质心**（0..1）：低音 → 0，高音 → 1。
     *
     * ## 为什么用质心而不是「最响的那一段」
     *
     * 旧版的 [bassHue] 是**离散挑选**：从低频段里挑出能量最高的那一段，
     * 用它的段号当色相。段与段之间差 `360 / 段数`（24 段就是 15°），
     * 鼓点一变最响的段就换 ⇒ **色相在档位之间跳变**。
     *
     * 质心是**连续量**：频谱重心连续地移，色相就连续地移，没有档位。
     *
     * ## 频段序号本身就是对数频率轴
     *
     * 频段是**对数**分段的（60Hz ~ 14kHz 等比），所以段号的归一化
     * 直接就是对数频率的归一化 —— 不需要知道采样率就能算质心。
     *
     * ## 用的是频段的**中心**位置，不是序号
     *
     * 第 i 段覆盖的是 `[底·r^(i/N), 底·r^((i+1)/N))`，它的中心频率对应
     * 对数轴上的 `(i + 0.5) / N`。用中心而不是序号本身，
     * 质心才不会整体偏高半个段（24 段就是 2% 的色相偏移），
     * 两端也对称（首段中心 0.021、末段中心 0.979）。
     */
    fun spectralCentroid(bands: FloatArray): Float {
        if (bands.size <= 1) return 0.5f
        var num = 0f
        var den = 0f
        val n = bands.size.toFloat()
        for (i in bands.indices) {
            val w = bands[i]
            if (w <= 0f) continue
            num += ((i + 0.5f) / n) * w
            den += w
        }
        if (den <= 0.0001f) return 0.5f
        return (num / den).coerceIn(0f, 1f)
    }

    /**
     * 频谱质心 → 色相（**全谱**）。
     *
     * 色相带限制在 **[HUE_BAND_LOW, HUE_BAND_HIGH]**（青 → 蓝 → 紫 → 品红）：
     *
     * - 避开 570nm 附近（人眼最敏感的亮黄）：亮黄既刺眼又影响驾驶；
     * - 但仍保留 150° 的行程，足够「颜色变化明显」。
     *
     * 低音 → 品红紫 320°，高音 → 青 170°。
     */
    fun centroidHue(
        bands: FloatArray,
        fallbackHue: Float,
        spanDeg: Float = HUE_BAND_SPAN,
        windowLow: Float = 0f,
        windowSpan: Float = 1f,
    ): Float {
        if (bands.isEmpty()) return normalizeHue(fallbackHue)
        val u = expandCentroid(spectralCentroid(bands), windowLow, windowSpan)
        return normalizeHue(HUE_BAND_HIGH - spanDeg.coerceIn(HUE_SPAN_MIN, HUE_SPAN_MAX) * u)
    }

    /**
     * 谱质心 → 展开后的 0..1。
     *
     * `windowLow = 0, windowSpan = 1` 是**恒等映射**（默认行为不变）；
     * `0.20 / 0.45` 把真实音乐常见的 [0.20, 0.65] 拉伸回整段。
     *
     * 感知层把每段都归一化到自己的参考值附近，所以质心本身**动态范围很小**
     * —— 不展开的话，即使把色相跨度放到 360°，色相也只走 100° 出头。
     */
    fun expandCentroid(centroid: Float, windowLow: Float, windowSpan: Float): Float {
        val span = windowSpan.coerceIn(0.05f, 1f)
        return ((centroid - windowLow) / span).coerceIn(0f, 1f)
    }

    /**
     * 色相自转的相位（**纯函数**，逐帧累加）。
     *
     * ## 为什么要有它
     *
     * 谱质心在某些音乐里几乎不动（全频段均衡 → 质心恒 0.5），
     * 光靠映射会长时间停在同一个颜色。自转让色相以固定角速度慢慢转，
     * 保证「五彩」在不同段落都看得见 —— 而且它是**连续**的，
     * 经过运动层的限速/限加速之后仍然是「流过去」而不是「跳过去」。
     *
     * @param prevPhaseDeg 上一帧的相位（0..360）
     * @param rotateDegPerSec 转速（0..[MAX_HUE_ROTATE_DEG_PER_SEC]），0 = 不转
     * @param dtMs 距上一帧的毫秒数
     */
    fun rotationPhase(prevPhaseDeg: Float, rotateDegPerSec: Float, dtMs: Long): Float =
        normalizeHue(
            prevPhaseDeg +
                rotateDegPerSec.coerceIn(0f, MAX_HUE_ROTATE_DEG_PER_SEC) *
                dtMs.coerceIn(0L, 1000L) / 1000f,
        )

    /**
     * 低频质心 → 色相（只看前 [LOW_CENTROID_BANDS] 段）。
     *
     * 「跟随低频」模式用这个：低频内部的音高结构变化也会带出色相变化，
     * 但整体仍留在低频那一侧（偏品红紫），和「跟随频谱」区分得开。
     */
    fun lowCentroidHue(
        bands: FloatArray,
        fallbackHue: Float,
        spanDeg: Float = HUE_BAND_SPAN,
        windowLow: Float = 0f,
        windowSpan: Float = 1f,
    ): Float {
        if (bands.isEmpty()) return normalizeHue(fallbackHue)
        val n = minOf(LOW_CENTROID_BANDS, bands.size)
        if (n <= 1) return normalizeHue(fallbackHue)
        var num = 0f
        var den = 0f
        val total = n.toFloat()
        for (i in 0 until n) {
            val w = bands[i]
            if (w <= 0f) continue
            num += ((i + 0.5f) / total) * w
            den += w
        }
        if (den <= 0.0001f) return normalizeHue(fallbackHue)
        val u = expandCentroid((num / den).coerceIn(0f, 1f), windowLow, windowSpan)
        return normalizeHue(HUE_BAND_HIGH - spanDeg.coerceIn(HUE_SPAN_MIN, HUE_SPAN_MAX) * u)
    }

    /**
     * 相对电平 → 目标明度（0..1）。
     *
     * ## 这是「明暗变化明显」的关键
     *
     * 旧版 [brightnessFor] 是 `min + (max − min) × level^(1/sens)`：
     * 低频能量中位值（约 0.5）映射出来就已经接近上限，
     * **动态范围只剩 10%** —— 于是灯看着一直是满的，明暗根本看不出来。
     *
     * 新版先做**区间映射**再过一条对比曲线：
     * - 相对电平 < [LEVEL_WINDOW_LOW]（0.22）→ 0（安静段落）；
     * - 相对电平 ≈ 0.55（典型段落）→ **约 0.5**；
     * - 相对电平 > 1.0（鼓点/高潮）→ 1（再叠起拍脉冲）。
     *
     * 典型值必须落在 0.5 附近 —— 贴顶 = 明暗被压扁。
     *
     * @param relative 感知层给出的相对电平（0..1）
     * @param sensitivity 感知灵敏度（0.3..3，越大弱音也越亮）
     * @param floorPercent 基础亮度（%），呼吸底光；**不能是 0**（灯不能真灭）
     */
    fun levelFromRelative(relative: Float, sensitivity: Float, floorPercent: Float): Float {
        val u = ((relative - LEVEL_WINDOW_LOW) / LEVEL_WINDOW_SPAN).coerceIn(0f, 1f)
        // 灵敏度越大 → gamma 越小 → 曲线越平 → 弱音也亮
        val gamma = LEVEL_GAMMA_BASE + LEVEL_GAMMA_SLOPE * (1f - sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY))
        val v = Math.pow(u.toDouble(), gamma.toDouble()).toFloat()
        val floor = (floorPercent / 100f).coerceIn(0f, 1f)
        return (floor + (1f - floor) * v).coerceIn(0f, 1f)
    }

    /**
     * 高频瞬态（0..1）：镲片、齿音那一层。
     *
     * 用来驱动**饱和度** —— 与色相（秒级）、明度（10~100ms）都不同步，
     * 三者叠在一起才是「流动」而不是「一起抖」。
     */
    fun transientOf(bands: FloatArray): Float {
        if (bands.size < 4) return 0f
        val from = bands.size * 2 / 3
        var sum = 0f
        var n = 0
        for (i in from until bands.size) {
            sum += bands[i]
            n++
        }
        if (n == 0) return 0f
        return (sum / n).coerceIn(0f, 1f)
    }

    /**
     * 目标**彩度**（OKLCH 的 C，0..[MAX_CHROMA]）。
     *
     * ## 为什么彩度也要单独一路
     *
     * 色相（秒级）、明度（10~100ms）已经是两路不同步的信号。
     * 如果彩度也跟着明度走，三者就一起抖 —— 观感是「整体在跳」，
     * 而不是「颜色在流动」。
     *
     * 彩度跟**高频瞬态**（镲片、齿音）走：
     * 镲片一响颜色就浓一下，这一路与低频鼓点是错开的，
     * 于是灯看起来有「细节」而不是只有「节拍」。
     *
     * @param bands 感知层归一后的频段（0..1）
     * @param saturation 饱和度上限（0..1，用户可调）
     * @param maxChroma 彩度上限（默认 [MAX_CHROMA]；彩虹预设用 [RAINBOW_MAX_CHROMA]，
     *   否则红/绿区会因为彩度只有 0.22 而看起来发灰）
     */
    fun chromaTarget(bands: FloatArray, saturation: Float, maxChroma: Float = MAX_CHROMA): Float {
        if (bands.isEmpty()) return 0f
        var energy = 0f
        for (i in bands.indices) energy += bands[i]
        // 归一基准：每段都到「典型值」时算 1.0
        val energyNorm = (energy / (bands.size * TYPICAL_BAND_VALUE)).coerceIn(0f, 1f)
        val transient = (transientOf(bands) / TYPICAL_BAND_VALUE).coerceIn(0f, 1f)
        val sat = saturation.coerceIn(0f, 1f)
        val c = (CHROMA_BASE + CHROMA_SAT_GAIN * sat) * (0.55f + 0.45f * energyNorm) +
            CHROMA_TRANSIENT_GAIN * transient
        return c.coerceIn(0f, maxChroma.coerceIn(0f, MAX_CHROMA_HARD))
    }

    /**
     * 感知明度 → OKLCH 的 L。
     *
     * 运动层算出来的 `level` 是「相对能量」，
     * 直接当 L 用的话暗部会贴到 0（灯珠看着全黑），
     * 所以留一点底：`L = level × 0.92 + 0.04`。
     */
    fun perceptualLightness(level: Float): Float =
        (level.coerceIn(0f, 1f) * LIGHTNESS_GAIN + LIGHTNESS_BIAS).coerceIn(0f, 1f)

    /** 「典型」的频段值（每段 0.5）—— 归一化的基准。 */
    const val TYPICAL_BAND_VALUE = 0.5f

    /** 彩度基值（不含饱和度调节）。 */
    const val CHROMA_BASE = 0.10f

    /** 饱和度设置对彩度的增益（饱和度 1.0 时额外加这么多）。 */
    const val CHROMA_SAT_GAIN = 0.16f

    /** 高频瞬态对彩度的增益。 */
    const val CHROMA_TRANSIENT_GAIN = 0.05f

    /** 运动层明度 → 感知明度 L 的增益（留 8% 的底给暗部）。 */
    const val LIGHTNESS_GAIN = 0.92f

    /** 感知明度 L 的偏置（保证暗部不是纯黑）。 */
    const val LIGHTNESS_BIAS = 0.04f

    /**
     * OKLCH → 颜色（含 8bit 抖动）。
     *
     * 与旧版 [colorOf] 的区别：
     * - 旧版是 HSV + **线性缩放 RGB**（暗部几乎不可见）；
     * - 新版用 **OKLCH**，L 就是感知明度 ⇒ 物理灯珠上的明暗变化是线性的。
     *
     * @param lightness 感知明度 0..1
     * @param chroma 彩度（OKLab 的 C，0..0.4 量级）
     */
    fun colorOfOklch(
        hue: Float,
        lightness: Float,
        chroma: Float,
        rand: (() -> Float)? = null,
    ): AmbientColor {
        val l = lightness.coerceIn(0f, 1f)
        val (r, g, b) = AmbientColorSpace.oklchToRgb(l, chroma.coerceIn(0f, 0.4f), normalizeHue(hue))
        return AmbientColor(
            AmbientColorSpace.dither(r.toFloat(), rand),
            AmbientColorSpace.dither(g.toFloat(), rand),
            AmbientColorSpace.dither(b.toFloat(), rand),
            (l * 100f).roundToInt().coerceIn(0, 100),
        )
    }

    /** 颜色的亮度差（调试用：看灯到底跳没跳）。 */
    fun brightnessDelta(a: AmbientColor, b: AmbientColor): Int = (a.brightness - b.brightness).let {
        if (it < 0) -it else it
    }

    /** 颜色距离（0..441）。 */
    fun colorDistance(a: AmbientColor, b: AmbientColor): Int {
        val dr = a.r - b.r
        val dg = a.g - b.g
        val db = a.b - b.b
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }
}
