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
        val lo = minBrightness.coerceIn(0, 100)
        val hi = maxBrightness.coerceIn(0, 100)
        val bass = bassEnergy(bands)
        val source = when (mode) {
            MODE_SPECTRUM -> level
            MODE_FIXED -> level
            else -> bass
        }
        val brightness = brightnessFor(source, sensitivity, lo, hi)
        val hue = when (mode) {
            MODE_SPECTRUM -> spectrumHue(bands, fallbackHue)
            MODE_FIXED -> normalizeHue(fixedHue)
            else -> bassHue(bands, fallbackHue)
        }
        val rgb = hsvToRgb(hue, 0.85f, 1f)
        return AmbientColor(rgb[0], rgb[1], rgb[2], brightness)
    }

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
        return hueForBand(best, bands.size)
    }

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

    /** 两个颜色的亮度差（调试用：看灯到底跳没跳）。 */
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
