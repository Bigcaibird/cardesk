package com.cardesk.launcher.ui.pip

import android.content.Context
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import kotlin.math.max
import kotlin.math.min

/**
 * 画中画分屏比例：默认值、持久化键名、纯换算逻辑。
 *
 * 出处
 * - 默认值： —— 双画 `0.5f`、单画 `0.71030045f`；
 * - 持久化键名；
 * - 读取 + clamp；
 * - 写入。
 *
 * **比例语义**：主画（左侧地图卡）在导航行里的权重，
 *
 * `setHorizontalWeight(mapCardContainer, primaryPipRatio())` /
 * `setHorizontalWeight(combinedCardContainer, 1.0f - primaryPipRatio())`。
 *
 * 本文件里的常量与换算都是**纯逻辑**（不依赖 Android View），单测见
 * `app/src/test/java/com/cardesk/launcher/ui/pip/PipRatioTest.kt`。
 */
object PipRatio {

    /** 单画默认比例。 */
    const val DEFAULT_NORMAL = 0.71030045f

    /** 双画默认比例。 */
    const val DEFAULT_DUAL = 0.5f

    /** 单画比例持久化键。 */
    const val KEY_NORMAL = "desk_primary_ratio_normal_v1"

    /** 双画比例持久化键。 */
    const val KEY_DUAL = "desk_primary_ratio_dual_v1"

    /** 左右互换开关（`KEY_SIDES_SWAPPED`）。 */
    const val KEY_SIDES_SWAPPED = "desk_sides_swapped"

    /** 副画应用包名（`KEY_PIP2_APP`）。 */
    const val KEY_PIP2_APP = "pip2_app"

    /** 主画应用包名（`KEY_PIP_APP`）。 */
    const val KEY_PIP_APP = "pip_app"

    /** SharedPreferences 文件名（与 同一个文件）。 */
    const val PREFS_NAME = "cardesk"

    /** 双画 / 单画对应的默认比例。 */
    fun defaultRatio(dual: Boolean): Float = if (dual) DEFAULT_DUAL else DEFAULT_NORMAL

    /** 双画 / 单画对应的持久化键。 */
    fun key(dual: Boolean): String = if (dual) KEY_DUAL else KEY_NORMAL

    /**
     * 把比例夹到 0..1；非有限值（NaN / ±Inf）回落到默认值。
     *
     */
    fun clamp(value: Float, dual: Boolean): Float {
        if (value.isNaN() || value.isInfinite()) return defaultRatio(dual)
        return max(0f, min(1f, value))
    }

    /**
     * 拖动换算
     * 新比例 = 起始比例 + 手指横向位移 / 可拖动总宽度；左右互换时位移取反；结果夹到 0..1。
     *
     * @param startRatio 手势起始比例（DOWN 时取一次）
     * @param dxDesign 手指横向位移（**设计 px**，调用方已除以累计缩放）
     * @param totalWidth 可拖动总宽度（设计 px）
     * @param swapped 左右是否互换
     * @param dual 是否双画（决定非法值回落用哪个默认值）
     */
    fun dragRatio(
        startRatio: Float,
        dxDesign: Float,
        totalWidth: Float,
        swapped: Boolean,
        dual: Boolean,
    ): Float {
        if (totalWidth <= 0f) return clamp(startRatio, dual)
        val dx = if (swapped) -dxDesign else dxDesign
        return clamp(dx / totalWidth + startRatio, dual)
    }
}

/**
 * 比例持久化（与 的）。
 *
 * 我们不引入 MMKV，也没有新依赖：用 `SharedPreferences("cardesk")` 存。
 * **刻意不复用** `core/Prefs.kt`（该文件由主线程并行修改），这里自己开一份同名的偏好文件。
 */
class PipRatioStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PipRatio.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取当前模式下的比例；读不到 / 非法值 → 默认值。
     */
    fun read(dual: Boolean): Float {
        val def = PipRatio.defaultRatio(dual)
        val stored = runCatching { sp.getFloat(PipRatio.key(dual), def) }.getOrDefault(def)
        val value = PipRatio.clamp(stored, dual)
        // 排障场景：「分屏比例每次重启都变回默认」——读到的原始值与夹紧后的值都要看。
        Logx.d(
            LogTags.NAV_UI,
            "画中画比例读取",
            "dual" to dual,
            "key" to PipRatio.key(dual),
            "raw" to stored,
            "value" to value,
            "default" to def,
        )
        return value
    }

    /** 写入当前模式下的比例。 */
    fun write(dual: Boolean, ratio: Float) {
        val value = PipRatio.clamp(ratio, dual)
        runCatching { sp.edit().putFloat(PipRatio.key(dual), value).apply() }
            .onFailure { Logx.w(LogTags.NAV_UI, "画中画比例写入失败", it, "dual" to dual, "ratio" to value) }
        // 排障场景：「比例没落盘」——落盘值落盘日志。
        Logx.i(
            LogTags.NAV_UI,
            "画中画比例写入",
            "dual" to dual,
            "key" to PipRatio.key(dual),
            "value" to value,
        )
    }

    /**
     * 左右是否互换。
     * 键名一致（`desk_sides_swapped`），写过的值也能读到。
     */
    fun readSidesSwapped(): Boolean =
        runCatching { sp.getBoolean(PipRatio.KEY_SIDES_SWAPPED, false) }.getOrDefault(false)

    /**
     * 写入「左右互换」。
     *
     * 写完 MMKV 还要 ——
     * 我们的 SharedPreferences 是 （异步落盘），系统会在应用退出前刷盘，
     * 语义等价。
     */
    fun writeSidesSwapped(value: Boolean) {
        runCatching { sp.edit().putBoolean(PipRatio.KEY_SIDES_SWAPPED, value).apply() }
            .onFailure { Logx.w(LogTags.NAV_UI, "画中画左右互换写入失败", it, "swapped" to value) }
        Logx.i(
            LogTags.NAV_UI,
            "画中画左右互换写入",
            "swapped" to value,
            "key" to PipRatio.KEY_SIDES_SWAPPED,
        )
    }

    /**
     * 副画应用包名。
     *
     * 本应用没有「第二画应用」设置项 → 读不到 `pip2_app` 时退回主画同一个包名 `pip_app`，
     * 再读不到才用调用方给的兜底值。
     */
    fun readSecondaryPackage(fallback: String): String {
        val secondary = runCatching { sp.getString(PipRatio.KEY_PIP2_APP, "") }.getOrNull().orEmpty()
        if (secondary.isNotBlank()) return secondary
        val primary = runCatching { sp.getString(PipRatio.KEY_PIP_APP, "") }.getOrNull().orEmpty()
        return primary.ifBlank { fallback }
    }
}
