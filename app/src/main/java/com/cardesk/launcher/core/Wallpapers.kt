package com.cardesk.launcher.core

/**
 * 壁纸目录（第五十七轮）。
 *
 * ## 为什么重新做壁纸
 *
 * 第四十九轮「浮屿」把壁纸从**拟物山峦 / 湖面 / 城市剪影**改成了
 * 「纯色底 + 一层极淡的柔和渐变」（[com.cardesk.launcher.ui.widget.BackgroundView] 的），
 * 结果默认壁纸就是一块**纯色**，桌面看上去像没上壁纸。
 *
 * 用户明确要求：**默认壁纸必须是风景 / 拟物类的**。
 * 所以这一轮把 9 张壁纸全部改回**程序化绘制的风景**：
 * 雪峰、雪湖、蓝调湖岸、日照金山、星空湖、极光、沙丘、城市夜色、云海松林。
 *
 * ## 实现口径
 *
 * 共 9 张
 * `紫晖雪山 / 粉霞雪湖 / 蓝调湖畔 / 日照金山 / 星空湖畔 / 极光 / 沙丘 / 城市夜色 / 液态渐变`。
 * 我们**不复制图片资源**，全部改成同名、同顺序的**程序化 Canvas 绘制**，
 * 用户切换壁纸的肌肉记忆不变。
 * 第 9 张是「液态渐变」（纯渐变），这里换成同样是风景的「**云海松林**」——
 * 因为用户要求「默认壁纸不要用纯色的，用风景、拟物类的」，纯渐变不该占一个坑位。
 *
 * 落盘键 `design_wallpaper_index`（见 [Prefs.wallpaper]）。
 *
 * ## 日夜两套色调
 *
 * 每张壁纸都有 `day` / `night` 两套 [WallpaperTone]：
 * - 日间用**亮**的那套（天空高、山体分得开），桌面上的深色字才读得清；
 * - 夜间用**暗**的那套（天顶压暗、地平线保留一点暖光），白色的字才读得清。
 *
 * 整套数据是**纯 Kotlin**（不 import 任何 Android 类），可以直接在 JVM 上单测。
 */
object Wallpapers {

    /** 场景类型（决定程序化绘制走哪条路径）。 */
    enum class Style {
        /** 雪峰（无水面）：紫晖 / 日照金山。 */
        PEAK,

        /** 雪湖（有水面 + 倒影）：粉霞 / 星空湖。 */
        LAKE,

        /** 蓝调湖岸（有水面 + 倒影，色调偏冷）。 */
        LAKE_BLUE,

        /** 极光（夜空 + 极光带 + 前景原野）。 */
        AURORA,

        /** 沙丘（暖色沙脊曲线）。 */
        DUNE,

        /** 城市夜色（天际线 + 窗灯 + 水面倒影）。 */
        CITY,

        /** 云海松林（云海 + 前景松树）。 */
        CLOUD_FOREST,
    }

    /**
     * 一套壁纸配色。
     *
     * 全部是 **8 位 ARGB**（Kotlin 里写 6 位十六进制 alpha 会变 0）。
     *
     * @param skyTop 天顶色
     * @param skyMid 天空中段色
     * @param skyLow 地平线附近的颜色
     * @param sun 日 / 月本体色
     * @param sunGlow 日 / 光的晕色
     * @param ridgeFar 最远一层山体
     * @param ridgeMid 中层山体
     * @param ridgeNear 近层山体
     * @param ridgeFront 前景地面 / 最近一层
     * @param water 水面色
     * @param haze 地平线雾霭色
     * @param detail 高光细节（雪顶 / 星点 / 窗灯 / 极光）
     */
    data class Tone(
        val skyTop: Int,
        val skyMid: Int,
        val skyLow: Int,
        val sun: Int,
        val sunGlow: Int,
        val ridgeFar: Int,
        val ridgeMid: Int,
        val ridgeNear: Int,
        val ridgeFront: Int,
        val water: Int,
        val haze: Int,
        val detail: Int,
    )

    /** 一张壁纸的完整定义。 */
    data class WallpaperSpec(
        val index: Int,
        val name: String,
        val desc: String,
        val style: Style,
        /** 夜间配色（设计稿本体）。 */
        val night: Tone,
    ) {
        /**
         * 日间配色 = 夜间的**淡彩版**（[dayTone]）。
         *
         * 为什么不在白天用「浓彩」：
         * 日间主题的文字是**深色**的（`--text` / `--textDim` / `--textFaint`），
         * 浮在壁纸上的次级歌词、气温这些就要求壁纸**足够浅**才读得清；
         * 夜间主题的文字是浅色，要求壁纸**足够深**。
         *
         * 所以一套配色、两个方向：白天淡、夜晚浓 —— 桌面上的字始终有对比度。
         */
        val day: Tone = Wallpapers.dayTone(night)
    }

    /** 默认壁纸下标（**紫晖雪山**，风景类，不是纯色）。 */
    const val DEFAULT_INDEX = 0

    /** 日间「淡彩版」的纸白（带一点点暖，不是纯白）。 */
    private const val DAY_PAPER = 0xFFFFFDF8.toInt()

    /**
     * 从夜间配色派生日间**淡彩**配色。
     *
     * 角色不同、淡化幅度也不同：
     * - 天空淡得最多（白天才有对比度）；
     * - 越靠前的山体淡得越少（保留景深层次，别糊成一片）；
     * - 高光细节（雪顶 / 星点）**不淡化**（否则雪顶没了）。
     */
    fun dayTone(night: Tone): Tone = Tone(
        skyTop = mix(night.skyTop, DAY_PAPER, 0.80f),
        skyMid = mix(night.skyMid, DAY_PAPER, 0.72f),
        skyLow = mix(night.skyLow, DAY_PAPER, 0.58f),
        sun = mix(night.sun, DAY_PAPER, 0.30f),
        sunGlow = mix(night.sunGlow, DAY_PAPER, 0.45f),
        ridgeFar = mix(night.ridgeFar, DAY_PAPER, 0.70f),
        ridgeMid = mix(night.ridgeMid, DAY_PAPER, 0.58f),
        ridgeNear = mix(night.ridgeNear, DAY_PAPER, 0.45f),
        ridgeFront = mix(night.ridgeFront, DAY_PAPER, 0.32f),
        water = mix(night.water, DAY_PAPER, 0.58f),
        haze = mix(night.haze, DAY_PAPER, 0.50f),
        detail = night.detail,
    )

    // ------------------------------------------------------------------ 9 张壁纸

    private val SPEC_LIST = listOf(
        WallpaperSpec(
            0, "紫晖雪山", "晨光初照的雪脊", Style.PEAK,
            night = Tone(
                skyTop = 0xFF1A1730.toInt(), skyMid = 0xFF3A2E52.toInt(), skyLow = 0xFF6E4E5E.toInt(),
                sun = 0xFFFFD9A8.toInt(), sunGlow = 0xFFB06A50.toInt(),
                ridgeFar = 0xFF3E3454.toInt(), ridgeMid = 0xFF2A2340.toInt(),
                ridgeNear = 0xFF1C182C.toInt(), ridgeFront = 0xFF12101C.toInt(),
                water = 0xFF241E38.toInt(), haze = 0xFF6E4E5E.toInt(), detail = 0xFFCFC2E8.toInt(),
            ),
        ),
        WallpaperSpec(
            1, "粉霞雪湖", "粉霞落在静湖上", Style.LAKE,
            night = Tone(
                skyTop = 0xFF1E1526.toInt(), skyMid = 0xFF4A2E44.toInt(), skyLow = 0xFF7A4A50.toInt(),
                sun = 0xFFFFDCC0.toInt(), sunGlow = 0xFFB07064.toInt(),
                ridgeFar = 0xFF54455E.toInt(), ridgeMid = 0xFF3A2E44.toInt(),
                ridgeNear = 0xFF241E30.toInt(), ridgeFront = 0xFF16121E.toInt(),
                water = 0xFF34283E.toInt(), haze = 0xFF7A4A50.toInt(), detail = 0xFFE8D8E4.toInt(),
            ),
        ),
        WallpaperSpec(
            2, "蓝调湖畔", "蓝调时刻的水岸", Style.LAKE_BLUE,
            night = Tone(
                skyTop = 0xFF0C1430.toInt(), skyMid = 0xFF1E3050.toInt(), skyLow = 0xFF3A5674.toInt(),
                sun = 0xFFE8EEF8.toInt(), sunGlow = 0xFF7E96B8.toInt(),
                ridgeFar = 0xFF3A4A66.toInt(), ridgeMid = 0xFF26344A.toInt(),
                ridgeNear = 0xFF18202E.toInt(), ridgeFront = 0xFF0C121C.toInt(),
                water = 0xFF1A2C42.toInt(), haze = 0xFF3A5674.toInt(), detail = 0xFFD8E4F4.toInt(),
            ),
        ),
        WallpaperSpec(
            3, "日照金山", "阳光打亮山脊", Style.PEAK,
            night = Tone(
                skyTop = 0xFF142038.toInt(), skyMid = 0xFF2C4266.toInt(), skyLow = 0xFF6E5238.toInt(),
                sun = 0xFFFFE6B0.toInt(), sunGlow = 0xFFC07A3E.toInt(),
                ridgeFar = 0xFF5E5248.toInt(), ridgeMid = 0xFF3E342E.toInt(),
                ridgeNear = 0xFF2A221E.toInt(), ridgeFront = 0xFF181412.toInt(),
                water = 0xFF2E3840.toInt(), haze = 0xFF6E5238.toInt(), detail = 0xFFE8CE96.toInt(),
            ),
        ),
        WallpaperSpec(
            4, "星空湖畔", "银河下的湖面", Style.LAKE,
            night = Tone(
                skyTop = 0xFF04060F.toInt(), skyMid = 0xFF0C1428.toInt(), skyLow = 0xFF16283E.toInt(),
                sun = 0xFFE8F0FF.toInt(), sunGlow = 0xFF6E84A8.toInt(),
                ridgeFar = 0xFF1E2C42.toInt(), ridgeMid = 0xFF141E30.toInt(),
                ridgeNear = 0xFF0C1220.toInt(), ridgeFront = 0xFF06090F.toInt(),
                water = 0xFF0E1A2C.toInt(), haze = 0xFF22384E.toInt(), detail = 0xFFFFFFFF.toInt(),
            ),
        ),
        WallpaperSpec(
            5, "极光", "绿光掠过原野", Style.AURORA,
            night = Tone(
                skyTop = 0xFF03060E.toInt(), skyMid = 0xFF08122A.toInt(), skyLow = 0xFF10203A.toInt(),
                sun = 0xFFC8DCF0.toInt(), sunGlow = 0xFF50688E.toInt(),
                ridgeFar = 0xFF13203A.toInt(), ridgeMid = 0xFF0C1628.toInt(),
                ridgeNear = 0xFF070C18.toInt(), ridgeFront = 0xFF03060C.toInt(),
                water = 0xFF0A1424.toInt(), haze = 0xFF1C3050.toInt(), detail = 0xFF5CE8B8.toInt(),
            ),
        ),
        WallpaperSpec(
            6, "沙丘", "暖沙起伏的曲线", Style.DUNE,
            night = Tone(
                skyTop = 0xFF182440.toInt(), skyMid = 0xFF2C4166.toInt(), skyLow = 0xFF6A5638.toInt(),
                sun = 0xFFFFEDBE.toInt(), sunGlow = 0xFFB57C44.toInt(),
                ridgeFar = 0xFF6E5A42.toInt(), ridgeMid = 0xFF503E2E.toInt(),
                ridgeNear = 0xFF38291F.toInt(), ridgeFront = 0xFF201812.toInt(),
                water = 0xFF36444E.toInt(), haze = 0xFF6A5638.toInt(), detail = 0xFFE8CE9C.toInt(),
            ),
        ),
        WallpaperSpec(
            7, "城市夜色", "灯火与倒影", Style.CITY,
            night = Tone(
                skyTop = 0xFF04060E.toInt(), skyMid = 0xFF0C1424.toInt(), skyLow = 0xFF18263E.toInt(),
                sun = 0xFFC8D4E8.toInt(), sunGlow = 0xFF62708C.toInt(),
                ridgeFar = 0xFF131C30.toInt(), ridgeMid = 0xFF0C1220.toInt(),
                ridgeNear = 0xFF080C16.toInt(), ridgeFront = 0xFF04070C.toInt(),
                water = 0xFF0A1220.toInt(), haze = 0xFF1E2C44.toInt(), detail = 0xFFFFD9A0.toInt(),
            ),
        ),
        WallpaperSpec(
            8, "云海松林", "云海之下的松林", Style.CLOUD_FOREST,
            night = Tone(
                skyTop = 0xFF101A30.toInt(), skyMid = 0xFF24344E.toInt(), skyLow = 0xFF4A4550.toInt(),
                sun = 0xFFFFEBC8.toInt(), sunGlow = 0xFFB08A60.toInt(),
                ridgeFar = 0xFF6A7686.toInt(), ridgeMid = 0xFF4A5560.toInt(),
                ridgeNear = 0xFF2A3630.toInt(), ridgeFront = 0xFF16201C.toInt(),
                water = 0xFF6A7686.toInt(), haze = 0xFF4A4550.toInt(), detail = 0xFFDCE8F0.toInt(),
            ),
        ),
    )

    /** 全部 9 张壁纸（顺序 =顺序）。 */
    val SPECS: List<WallpaperSpec> = SPEC_LIST

    /** 壁纸总数。 */
    val COUNT: Int = SPEC_LIST.size

    /** 壁纸名（顺序同 [SPECS]）。 */
    val NAMES: List<String> = SPEC_LIST.map { it.name }

    /** 把任意下标规整进 `0 until COUNT`（负数、越界都兜得住）。 */
    fun normalize(index: Int): Int = ((index % COUNT) + COUNT) % COUNT

    /** 取壁纸定义（越界自动兜到合法值）。 */
    fun spec(index: Int): WallpaperSpec = SPEC_LIST[normalize(index)]

    /** 取壁纸名（越界自动兜到合法值）。 */
    fun name(index: Int): String = spec(index).name

    /** 取壁纸配色（[night] = true 取夜间那套）。 */
    fun tone(index: Int, night: Boolean): Tone = spec(index).let { if (night) it.night else it.day }

    /** 下一张（末尾回到开头）。 */
    fun next(index: Int): Int = normalize(index + 1)

    /** 上一张（开头回到末尾）。 */
    fun prev(index: Int): Int = normalize(index - 1)

    /**
     * 两个 ARGB 颜色按比例混合（纯 Kotlin，不依赖 `android.graphics.Color`）。
     *
     * @param t 0 = 全取 [a]，1 = 全取 [b]
     */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        val aa = ((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * k
        val rr = ((a ushr 16) and 0xFF) * inv + ((b ushr 16) and 0xFF) * k
        val gg = ((a ushr 8) and 0xFF) * inv + ((b ushr 8) and 0xFF) * k
        val bb = (a and 0xFF) * inv + (b and 0xFF) * k
        return ((aa.toInt() and 0xFF) shl 24) or
            ((rr.toInt() and 0xFF) shl 16) or
            ((gg.toInt() and 0xFF) shl 8) or
            (bb.toInt() and 0xFF)
    }
}
