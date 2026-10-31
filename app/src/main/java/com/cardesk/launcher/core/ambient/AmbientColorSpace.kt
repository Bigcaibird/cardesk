package com.cardesk.launcher.core.ambient

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 氛围灯的**感知色彩空间**工具（纯函数，不依赖 Android，能单测）。
 *
 * ## 为什么不用 HSV + 线性缩放 RGB
 *
 * 旧版路径是「HSV 取色相 → 按亮度线性缩放 RGB」。两个问题：
 *
 * 1. **线性缩放不是感知均匀的**：亮度 V=0.1 时写出去的是 25/255，
 *    物理灯珠看着几乎是黑的 ⇒ 弱音段落等于「灯灭」，明暗变化被压扁。
 * 2. **HSV 的色相插值会经过灰轴**：色相从 50° 走到 70° 时饱和度骤降，
 *    视觉上是「颜色淡了一下又浓起来」，也是一种生硬。
 *
 * OKLab（Ottosson）是感知均匀的：L 的变化对应视觉明度的均匀变化，
 * 所以「明暗变化明显」在物理灯珠上才是线性的。
 *
 * ## CIEDE2000 干什么用
 *
 * 限制「相邻两帧的感知色差」。RGB 的欧氏距离与肉眼感受不成比例
 * （深蓝到浅蓝的 RGB 距离很大，人眼却觉得差不多）。
 * ΔE00 是目前公认最接近人眼差异的色差公式，用它当「单帧最大跳变」的判据。
 *
 * 参考：Ottosson, B. (2020). A perceptual color space for image processing.
 */
object AmbientColorSpace {

    // ------------------------------------------------------------------ sRGB

    /** sRGB 分量（0..1）→ 线性光。 */
    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** 线性光 → sRGB 分量（0..1）。 */
    fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    // ------------------------------------------------------------------ OKLab

    /** 线性 RGB → OKLab（返回 L, a, b）。 */
    fun linearRgbToOklab(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l_ = l.cbrtCompat()
        val m_ = m.cbrtCompat()
        val s_ = s.cbrtCompat()
        return Triple(
            0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_,
        )
    }

    /** OKLab → 线性 RGB（**不夹范围**，越界留给上层做色域收敛）。 */
    fun oklabToLinearRgb(l: Float, a: Float, b: Float): Triple<Float, Float, Float> {
        val l_ = l + 0.3963377774f * a + 0.2158037573f * b
        val m_ = l - 0.1055613458f * a - 0.0638541728f * b
        val s_ = l - 0.0894841775f * a - 1.2914855480f * b
        val ll = l_ * l_ * l_
        val mm = m_ * m_ * m_
        val ss = s_ * s_ * s_
        return Triple(
            4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss,
            -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss,
            -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss,
        )
    }

    // ------------------------------------------------------------------ CIELAB

    /** 线性 RGB → CIELAB（D65，8bit 分量口径：0..255）。 */
    fun rgbToLab(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val lr = srgbToLinear(r / 255f)
        val lg = srgbToLinear(g / 255f)
        val lb = srgbToLinear(b / 255f)
        var x = (lr * 0.4124564f + lg * 0.3575761f + lb * 0.1804375f) / 0.95047f
        var y = lr * 0.2126729f + lg * 0.7151522f + lb * 0.0721750f
        var z = (lr * 0.0193339f + lg * 0.1191920f + lb * 0.9503041f) / 1.08883f
        val e = 216f / 24389f
        val k = 24389f / 27f
        val fx = labF(x, e, k)
        val fy = labF(y, e, k)
        val fz = labF(z, e, k)
        return Triple(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    private fun labF(t: Float, e: Float, k: Float): Float =
        if (t > e) t.cbrtCompat() else (k * t + 16f) / 116f

    // ------------------------------------------------------------------ CIEDE2000

    /**
     * CIEDE2000 色差（ΔE00）。
     *
     * 取值：0 = 完全同色；~1 = 刚好可辨；2~3 = 明显不同；>5 = 差很多。
     *
     * 实现按 Sharma-Wu-Dalal (2005) 的标准公式。
     */
    fun deltaE00(
        lab1: Triple<Float, Float, Float>,
        lab2: Triple<Float, Float, Float>,
    ): Float {
        val l1 = lab1.first; val a1 = lab1.second; val b1 = lab1.third
        val l2 = lab2.first; val a2 = lab2.second; val b2 = lab2.third

        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) / 2f
        val cBar7 = cBar.pow(7f)
        val g = 0.5f * (1f - sqrt(cBar7 / (cBar7 + 6103515625f))) // 25^7

        val a1p = (1f + g) * a1
        val a2p = (1f + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)

        var h1p = if (c1p == 0f) 0f else hueAngle(b1, a1p)
        var h2p = if (c2p == 0f) 0f else hueAngle(b2, a2p)

        val dLp = l2 - l1
        val dCp = c2p - c1p

        var dhp = 0f
        if (c1p * c2p != 0f) {
            dhp = h2p - h1p
            if (dhp > 180f) dhp -= 360f else if (dhp < -180f) dhp += 360f
        }
        val dHp = 2f * sqrt(c1p * c2p) * sin(Math.toRadians((dhp / 2f).toDouble()).toFloat())

        val lBarP = (l1 + l2) / 2f
        val cBarP = (c1p + c2p) / 2f

        var hBarP = h1p + h2p
        if (c1p * c2p != 0f) {
            if (abs(h1p - h2p) > 180f) {
                hBarP += if (h1p + h2p < 360f) 360f else -360f
            }
            hBarP /= 2f
        }

        val t = 1f -
            0.17f * cos(Math.toRadians((hBarP - 30f).toDouble()).toFloat()) +
            0.24f * cos(Math.toRadians((2f * hBarP).toDouble()).toFloat()) +
            0.32f * cos(Math.toRadians((3f * hBarP + 6f).toDouble()).toFloat()) -
            0.20f * cos(Math.toRadians((4f * hBarP - 63f).toDouble()).toFloat())

        val dTheta = 30f * exp(-((hBarP - 275f) / 25f).pow(2f))
        val cBarP7 = cBarP.pow(7f)
        val rc = 2f * sqrt(cBarP7 / (cBarP7 + 6103515625f))
        val rt = -rc * sin(Math.toRadians((2f * dTheta).toDouble()).toFloat())

        val sl = 1f + (0.015f * (lBarP - 50f).pow(2f)) / sqrt(20f + (lBarP - 50f).pow(2f))
        val sc = 1f + 0.045f * cBarP
        val sh = 1f + 0.015f * cBarP * t

        val tl = dLp / sl
        val tc = dCp / sc
        val th = dHp / sh
        return sqrt(tl * tl + tc * tc + th * th + rt * tc * th)
    }

    private fun hueAngle(b: Float, a: Float): Float {
        var h = Math.toDegrees(Math.atan2(b.toDouble(), a.toDouble())).toFloat()
        if (h < 0f) h += 360f
        return h
    }

    // ------------------------------------------------------------------ OKLCH → RGB

    /**
     * OKLCH → sRGB（0..255）。
     *
     * ## 色域收敛：为什么不是「裁 RGB」
     *
     * 直接把越界的线性 RGB 夹到 0..1 会**偏色**（比如高饱和的蓝会被夹成
     * 一个说不上来的暗色）。正确做法是**压低彩度 C** 再找落在色域内的值 ——
     * 色相与明度保持不变，只牺牲一点鲜艳度。
     *
     * @param l 感知明度 0..1
     * @param c 彩度（0..0.4 之类的量级，OKLab 的 C 范围约 0..0.4）
     * @param h 色相角 0..360
     */
    fun oklchToRgb(l: Float, c: Float, h: Float): Triple<Int, Int, Int> {
        val rad = Math.toRadians(h.toDouble())
        val cosH = cos(rad).toFloat()
        val sinH = sin(rad).toFloat()

        fun linear(chroma: Float): Triple<Float, Float, Float> =
            oklabToLinearRgb(l, chroma * cosH, chroma * sinH)

        fun inGamut(v: Triple<Float, Float, Float>): Boolean =
            v.first >= -EPS && v.first <= 1f + EPS &&
                v.second >= -EPS && v.second <= 1f + EPS &&
                v.third >= -EPS && v.third <= 1f + EPS

        var v = linear(c)
        if (!inGamut(v)) {
            // 二分压彩度：16 次足够（彩度精度 0.4/65536，远低于 8 bit 精度）
            var lo = 0f
            var hi = c
            var best: Triple<Float, Float, Float>? = null
            repeat(16) {
                val mid = (lo + hi) / 2f
                val t = linear(mid)
                if (inGamut(t)) {
                    best = t
                    lo = mid
                } else {
                    hi = mid
                }
            }
            v = best ?: linear(0f)
        }
        return Triple(
            to8bit(v.first),
            to8bit(v.second),
            to8bit(v.third),
        )
    }

    private const val EPS = 0.001f

    // ------------------------------------------------------------------ ΔE00 限速

    /**
     * ΔE00 限速：把候选色拉回到与「上一帧色」相差 ≤ [maxDeltaE]。
     *
     * ## 怎么做
     *
     * 1. 两个颜色都转到 CIELAB；
     * 2. 算 ΔE00；
     * 3. 超过上限就按比例往回拉：
     *    `k = 1 − (maxDeltaE / ΔE) × 0.5`，
     *    结果 = `候选色 × (1 − k) + 上一帧色 × k`，
     *    在 **OKLab** 里插值（感知均匀，不会因为插值而偏色）再转回 8 bit。
     *
     * ## 为什么要乘 0.5
     *
     * 线性拉回会刚好落在上限上（ΔE 恰好 = maxDeltaE），
     * 但那是**数学上限** —— 实车上每帧都顶在上限上看着仍然偏跳。
     * 乘 0.5 是留一半余量：实际每帧的色差大约是上限的一半，
     * 观感上就是「连续流动」。
     *
     * ## 为什么在 OKLab 里插值而不是 RGB
     *
     * RGB 直插会在两端之间**穿过灰轴**（尤其两端色相差别大时），
     * 中间那一帧会「淡一下」—— 这恰恰是要消除的生硬。
     *
     * @return 限速后的颜色；**不需要限速时原样返回**
     */
    fun limitByDeltaE(
        prevR: Int, prevG: Int, prevB: Int,
        curR: Int, curG: Int, curB: Int,
        maxDeltaE: Float,
    ): Triple<Int, Int, Int> {
        val cur = Triple(curR, curG, curB)
        if (maxDeltaE <= 0f) return cur
        val prevLab = rgbToLab(prevR, prevG, prevB)
        val curLab = rgbToLab(curR, curG, curB)
        val de = deltaE00(prevLab, curLab)
        if (de <= maxDeltaE) return cur
        val k = (1f - (maxDeltaE / de) * 0.5f).coerceIn(0f, 1f)
        // 结果 = `候选色 × (1 − k) + 上一帧色 × k`（k 越大越靠近上一帧）。
        // 参数顺序写反就成了「几乎不拉回」—— 这是最容易写错的一行。
        return lerpRgb(curR, curG, curB, prevR, prevG, prevB, k)
    }

    // ------------------------------------------------------------------ 插值

    /**
     * 两个 8 bit sRGB 之间按 **OKLab** 插值。
     *
     * ## 为什么不在 RGB 里直接插
     *
     * RGB 直插会**穿过灰轴**：两端颜色差别大时，中间那一帧会「淡一下」，
     * 肉眼看到的是颜色忽然褪色再恢复 —— 这恰恰是要消除的生硬。
     *
     * OKLab 是感知均匀的：在它里面插值，感知路径就是一条直线，
     * 中间帧既不褪色也不偏色。
     *
     * @param t 0 = 取 a，1 = 取 b
     */
    fun lerpRgb(
        ar: Int, ag: Int, ab: Int,
        br: Int, bg: Int, bb: Int,
        t: Float,
    ): Triple<Int, Int, Int> {
        val k = t.coerceIn(0f, 1f)
        val a = linearRgbToOklab(
            srgbToLinear(ar / 255f),
            srgbToLinear(ag / 255f),
            srgbToLinear(ab / 255f),
        )
        val b = linearRgbToOklab(
            srgbToLinear(br / 255f),
            srgbToLinear(bg / 255f),
            srgbToLinear(bb / 255f),
        )
        val l = a.first + (b.first - a.first) * k
        val aa = a.second + (b.second - a.second) * k
        val bb2 = a.third + (b.third - a.third) * k
        val rgb = oklabToLinearRgb(l, aa, bb2)
        return Triple(to8bit(rgb.first), to8bit(rgb.second), to8bit(rgb.third))
    }

    private fun to8bit(linear: Float): Int {
        val s = linearToSrgb(linear.coerceIn(0f, 1f))
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    // ------------------------------------------------------------------ 抖动

    /**
     * 8 bit 量化的**抖动**（dither）。
     *
     * ## 为什么需要
     *
     * 物理灯珠是 8 bit 的。暗部 RGB 从 30 跳到 31 就是肉眼可见的一跳，
     * 在缓慢变化的场景里会形成「色带」/「台阶」—— 这正是「生硬」的一种。
     *
     * 加一个**三角分布**噪声（范围约 ±1 LSB）把台阶打散：
     * 单个像素的噪声不可见，但整条渐变会变成平滑过渡。
     *
     * ## 为什么用**四舍五入**而不是截断
     *
     * 三角分布噪声在 (−1, 1) 上对称：
     * - 截断（`toInt`）⇒ 输出只能是 `v` 或 `v−1`，且**平均偏低 0.5 LSB**
     *   （整体偏暗，而且只有 2 个取值，打散效果一般）；
     * - 四舍五入 ⇒ 输出是 `v−1` / `v` / `v+1`，概率 1:2:1，**无偏**，3 个取值。
     *
     * 无偏这一点在暗部尤其重要：整体偏 0.5 个色阶在黑色附近是能看出来的。
     *
     * @param rand 随机源（0..1）；传 null 用 [Math.random]
     */
    fun dither(value: Float, rand: (() -> Float)? = null): Int {
        val r = rand ?: { Math.random().toFloat() }
        val n = r() + r() - 1f
        return Math.round(value + n).coerceIn(0, 255)
    }

    // ------------------------------------------------------------------ 工具

    /** 立方根（负数也能用，Java 的 cbrt 在低版本 API 上才有）。 */
    private fun Float.cbrtCompat(): Float {
        if (this == 0f) return 0f
        val s = if (this < 0f) -1f else 1f
        return s * kotlin.math.abs(this).pow(1f / 3f)
    }
}
