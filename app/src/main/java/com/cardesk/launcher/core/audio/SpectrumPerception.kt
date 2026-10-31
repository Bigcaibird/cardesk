package com.cardesk.launcher.core.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 频谱的**感知层**（V2）：把「绝对 dB」换算成「相对这一段自身平均水平的强弱」。
 *
 * ## 为什么要有这一层
 *
 * [SpectrumBands.calibrate] 给的是**绝对** dB 标定：`(db + 54) / 54`。
 * 绝对标定的意思是「这一首歌的鼓点有多响」取决于**录音电平**：
 *
 * - 母带压得响的歌，所有频段都在 0.7 以上 ⇒ 灯一直很亮，看不出起伏；
 * - 录得轻的歌，所有频段都在 0.2 以下 ⇒ 灯一直很暗。
 *
 * 这正是旧版被评价为「明暗变化不明显」的根因之一：
 * **绝对电平不构成对比**。
 *
 * ## 这一层做两件事
 *
 * ### 1. 每段一个慢速参考电平（对称 EMA，τ = 2000 ms）
 *
 * ```kotlin
 * bandRef[i] += (rawDb[i] - bandRef[i]) * (1 - exp(-dtMs / 2000f))
 * relative[i] = ((rawDb[i] - bandRef[i] + 12f) / 24f).coerceIn(0f, 1f)
 * ```
 *
 * 每段**自校准**：该段的平均水平落在 0.5，高于平均 12 dB 记 1，
 * 低于平均 12 dB 记 0。安静段落照样有起伏，换歌也不用重新标定。
 *
 * ### 2. 静音门（整体峰值跟随，慢回落 15 dB/s）
 *
 * 音乐停掉时所有频段一起掉到地板，门跟着关到 0 ⇒
 * 灯慢慢暗到底光，而不是**冻在最后一个颜色**。
 *
 * ## 为什么不用 min/max 双统计 AGC
 *
 * 以前试过「min/max 双追踪器」：信号持续偏高时，**下限追踪器会跟着信号跑**
 * （回升比下降快），归一化后整段恒为 0；而且初值不对时要十几秒才收敛。
 * 对称 EMA 没有这个问题：它永远以「当前平均值」为中心。
 *
 * ## 与演示台（`docs/ambient-spectrum/demo.html`）逐字对齐
 *
 * 演示台上量化过的新版指标（ΔE00/帧 0.5~1.3、亮度变化率 30~90%/s）
 * 就是这套公式跑出来的 —— 落地到车上必须是同一套，否则车上的观感
 * 与演示台对不上，验收就没了参照物。
 *
 * ## 踩坑记录：dt 与 τ 必须同单位
 *
 * [AmbientMotion] 用**毫秒**，这里的 EMA 也用毫秒。
 * 若误传「秒」，慢速追踪器每步只走 0.005%，
 * 表现是「频谱永远是 0」。
 */
class SpectrumPerception(
    /** 频段数（要与 [SpectrumBands.DEFAULT_BAND_COUNT] 一致）。 */
    bandCount: Int = SpectrumBands.DEFAULT_BAND_COUNT,
    /** 每段参考电平的时间常数（毫秒）。越大越「黏」。 */
    private val refTauMs: Float = DEFAULT_REF_TAU_MS,
    /** 整体峰值回落速度（dB/秒）。决定音乐停后多久暗到底光。 */
    private val peakFallDbPerSec: Float = PEAK_FALL_DB_PER_SEC,
) {

    /** 每段的慢速参考电平（dB）。 */
    private val bandRefDb = FloatArray(bandCount)

    /**
     * 感知权重（低频补偿、高频衰减）。
     *
     * 人耳对低频更敏感，但**低频的能量本来就大** ——
     * 不加权的话，整体电平会被低频独占，高频的变化根本看不出来。
     */
    private val weights = FloatArray(bandCount) { i ->
        when {
            i < 4 -> 3.0f
            i < 12 -> 1.6f
            else -> 0.7f
        }
    }

    /** 整体峰值跟随（dB）。 */
    private var peakDb = SILENT_DB

    /** 参考电平是否已经初始化过（第一帧直接拿原始值当参考）。 */
    private var refInitialized = false

    /** 上一帧算出来的**相对电平**（0..1）—— 艺术层做明度映射用。 */
    var relativeLevel: Float = 0f
        private set

    /** 上一帧的静音门（0..1）。 */
    var gate: Float = 0f
        private set

    /** 上一帧的整体原始电平（dB）—— 诊断用。 */
    var overallDb: Float = SILENT_DB
        private set

    /**
     * 处理一帧频谱。
     *
     * @param input **绝对 dB 标定**后的频段（0..1，即 `(db + 54) / 54` 的口径）
     * @param dtMs 距离上一帧的毫秒数
     * @return 归一后的相对频段（0..1）
     */
    fun process(input: FloatArray, dtMs: Long): FloatArray {
        val n = minOf(input.size, bandRefDb.size)
        val out = FloatArray(n)
        if (n == 0) {
            relativeLevel = 0f
            gate = 0f
            overallDb = SILENT_DB
            return out
        }
        // 时间差夹一下：切后台再回来可能隔了几分钟，
        // 那时不该「一步补上」几秒的参考电平变化。
        val dt = dtMs.coerceIn(1L, MAX_DT_MS).toFloat()
        val dtS = dt / 1000f
        val alpha = 1f - exp(-dt / refTauMs)

        // —— ① 还原成 dB，并算整体原始电平（感知加权）——
        //
        // `calibrate()` 是 `(db - DB_FLOOR) / DB_RANGE`，所以反解是
        // `db = v * DB_RANGE + DB_FLOOR`。
        var ps = 0f
        var wsum = 0f
        for (i in 0 until n) {
            val db = input[i] * SpectrumBands.DB_RANGE + SpectrumBands.DB_FLOOR
            val w = weights[i]
            // 功率域相加（dB 域直接平均是错的）
            ps += 10f.pow(db / 10f) * w
            wsum += w
        }
        overallDb = if (ps > 0f && wsum > 0f) 10f * log10(ps / wsum) else SILENT_DB

        // —— ② 整体峰值跟随 → 静音门 ——
        peakDb = maxOf(overallDb, peakDb - peakFallDbPerSec * dtS)
        val g = ((peakDb + GATE_SILENT_DB) / GATE_RANGE_DB).coerceIn(0f, 1f)
        gate = g

        // —— ③ 每段自适应参考 + 相对值 ——
        var sum = 0f
        for (i in 0 until n) {
            val db = input[i] * SpectrumBands.DB_RANGE + SpectrumBands.DB_FLOOR
            if (!refInitialized) {
                bandRefDb[i] = db
            } else {
                bandRefDb[i] += (db - bandRefDb[i]) * alpha
            }
            // 高于平均 12 dB 记 1，低于平均 12 dB 记 0
            val x = ((db - bandRefDb[i] + REL_HALF_SPAN_DB) / REL_SPAN_DB).coerceIn(0f, 1f)
            val v = x * g
            out[i] = v
            sum += v * v * weights[i]
        }
        refInitialized = true

        // —— ④ 整体相对电平 = 感知加权 RMS ——
        //
        // 用 RMS 而不是均值：RMS 对「有几段同时很响」更敏感，
        // 于是鼓点（低频整段抬起来）能把整体电平顶上去。
        relativeLevel = if (wsum > 0f) sqrt(sum / wsum).coerceIn(0f, 1f) else 0f
        return out
    }

    /** 重置（换歌 / 重新开始采集时调用，避免用上一首歌的参考电平）。 */
    fun reset() {
        bandRefDb.fill(0f)
        peakDb = SILENT_DB
        refInitialized = false
        relativeLevel = 0f
        gate = 0f
        overallDb = SILENT_DB
    }

    companion object {
        /** 每段参考电平的时间常数（毫秒）。 */
        const val DEFAULT_REF_TAU_MS = 2000f

        /** 整体峰值回落速度（dB/秒）。 */
        const val PEAK_FALL_DB_PER_SEC = 15f

        /**
         * 相对值的量程（dB）。
         *
         * 高于参考 12 dB 记 1、低于参考 12 dB 记 0 ⇒ 总共 24 dB 的窗口。
         */
        const val REL_SPAN_DB = 24f

        /** 相对值量程的一半（±12 dB）。 */
        const val REL_HALF_SPAN_DB = 12f

        /**
         * 静音门的关闭电平（dB）。
         *
         * ## 为什么不直接用演示台上的 −70 dB
         *
         * 演示台拿的是 Web Audio 的**原始** dB（可以到 −140）。
         * 而车机这条链上，[SpectrumBands.calibrate] 会把结果**夹到 0..1**，
         * 也就是 dB 被夹在 **−54 ~ 0** 之间 —— 低于 −54 dB 的信息已经丢了。
         *
         * 所以这里的门限必须落在 −54 dB 之内：
         * −54 dB 是「彻底没声音」，−48 dB 以上算「有声音，门全开」。
         * 中间 6 dB 是渐变区，避免门开关时灯「啪」一下。
         */
        const val GATE_SILENT_DB = 54f

        /** 静音门的渐变区宽度（dB）。 */
        const val GATE_RANGE_DB = 6f

        /** 「完全没声音」的电平（dB）。 */
        const val SILENT_DB = -60f

        /** 单帧时间差上限（毫秒）。 */
        const val MAX_DT_MS = 1000L
    }
}
