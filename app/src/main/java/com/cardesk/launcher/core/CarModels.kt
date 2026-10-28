package com.cardesk.launcher.core

/**
 * 车型车模（自绘俯视车模，按车型给不同形状）。
 *
 * ## 胎压卡里的车模
 *
 * - 胎压卡中间是一张**俯视车模**：`140.0px × 188.0px` 的外层容器里
 *   放一张 `96.0px × 188.0px` 的车模，车模居中，`tag="up"` 表示车头朝上。
 * - 车模的**设计空间是 120 × 240**：整体按 `width / 120.0f, height / 240.0f` 缩放。
 *
 *   | 元素 | 坐标 |
 *   | --- | --- |
 *   | 车身 | 鼻尖 `(60,8)` → 尾端 `(60,232)`，最大半宽 48（`x=12..108`） |
 *   | 前轮 | `x=6..22` / `x=98..114`，`y=46..82`（**探出车身 6px**） |
 *   | 后轮 | `x=6..22` / `x=98..114`，`y=162..198` |
 *   | 前风挡 | 上沿宽（`x=28..92`）下沿窄（`x=34..86`），`y=60..100` |
 *   | 车顶 | 圆角矩形 `x=36..84`，`y=108..170` |
 *   | 后风挡 | 上沿窄（`x=34..86`）下沿宽（`x=28..92`），`y=178..210` |
 *
 * - 车型持久化键 `tpms_car_model`，默认 `03`。
 *
 * ## 我们的做法：自己画，按车型给不同形状
 *
 * 本应用**不联网下载整车图**（车机离线、远端仓库不可控），沿用**自绘俯视车模**，
 * 轮廓按分段构造（贝塞尔车头 + 直线侧腰 + 贝塞尔车尾 + 探出车身的四个轮子），
 * 并按车型给两套参数。
 *
 * **第四轮起**：车模的坐标全部挪到 [CarTopGeometry] / [CarTopGeometryFactory]，
 * 本文件只保留「车型编号 ↔ 车身类型」的映射（[bodyOf]），
 * 避免同一个车形有两套参数来源。
 *
 * | 车型 | 车身类型 | 说明 |
 * | --- | --- | --- |
 * | `01` | SUV | 领克 01，紧凑型 SUV |
 * | `02` | SUV | 领克 02，轿跑 SUV |
 * | `02HB` | SUV | 领克 02 猎装版 |
 * | `03` | 轿车 | 领克 03，轿车（内置车型） |
 * | `03+` | 轿车 | 领克 03+ |
 * | `05` | SUV | 领克 05，中型 SUV |
 *
 * 画在胎压卡里，四个胎压值（FL / RL / FR / RR）压在四个轮子的位置上
 * （对照 4 个轮子矩形 + 车身轮廓）。
 */
object CarModels {

    /** 车型持久化键。 */
    const val KEY = "tpms_car_model"

    const val MODEL_01 = "01"
    const val MODEL_02 = "02"
    const val MODEL_02HB = "02HB"
    const val MODEL_03 = "03"
    const val MODEL_03PLUS = "03+"
    const val MODEL_05 = "05"

    /** 全部车型（顺序）。 */
    val MODEL_IDS: List<String> = listOf(MODEL_01, MODEL_02, MODEL_02HB, MODEL_03, MODEL_03PLUS, MODEL_05)

    /** 车模设计空间宽（`canvas.scale(width / 120f,...)`）。 */
    const val DESIGN_W = 120f

    /** 车模设计空间高。 */
    const val DESIGN_H = 240f

    /** 车模视图宽（`android:layout_width="96.0px"`）。 */
    const val VIEW_W = 96f

    /** 车模视图高（`android:layout_height="188.0px"`）。 */
    const val VIEW_H = 188f

    /** 车身类型。 */
    enum class Body {
        /** SUV / 轿跑 SUV / 猎装：方正、敦实。 */
        SUV,

        /** 轿车：细长、流线。 */
        SEDAN,
    }

    /**
     * 车型 → 车身类型。
     *
     * `01` / `02` / `02HB` / `05` 是 SUV 家族，`03` / `03+` 是轿车家族。
     */
    fun bodyOf(modelId: String): Body = when (normalize(modelId)) {
        MODEL_01, MODEL_02, MODEL_02HB, MODEL_05 -> Body.SUV
        else -> Body.SEDAN
    }

    /**
     * 归一化车型编号。
     *
     * - 空 → `03`；
     * - 不区分大小写匹配 [MODEL_IDS]（`02hb` → `02HB`）；
     * - `03plus` → `03+`；
     * - 其余一律 → `03`（兜底也是 03）。
     */
    fun normalize(raw: String?): String {
        if (raw == null) return MODEL_03
        val t = raw.trim()
        MODEL_IDS.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        return when {
            t.equals("03plus", ignoreCase = true) -> MODEL_03PLUS
            t.equals("02hb", ignoreCase = true) -> MODEL_02HB
            else -> MODEL_03
        }
    }

    /**
     * 车型对应的远程车模文件名。
     *
     * 我们不下载，这里只保留口径（便于对照/ 将来接真正的车型资源）。
     */
    fun remoteFileName(modelId: String): String? = when (normalize(modelId)) {
        MODEL_01 -> "vc_car_model_01.webp"
        MODEL_02 -> "vc_car_model_02.webp"
        MODEL_02HB -> "vc_car_model_02hb.webp"
        MODEL_03PLUS -> "vc_car_model_03plus.webp"
        MODEL_05 -> "vc_car_model_05.webp"
        else -> null
    }

    /** —— 只有 `03` 是内置资源。 */
    fun isBundled(modelId: String): Boolean = normalize(modelId) == MODEL_03
}
