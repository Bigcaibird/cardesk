package com.cardesk.launcher.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 壁纸亮度 → 浮层墨色（第222轮）。
 *
 * ## 为什么需要它
 *
 * 日夜模式决定的是「卡片 / 面板 / 胶囊」这一层的配色，
 * 而**直接压在壁纸上的那一层**能不能看清，取决于**壁纸本身有多亮**。
 *
 * 典型翻车场景：**日间模式 + 一张暗色壁纸** ——
 * 日间的字是深色的（`#191C23`），压在暗色照片上对比度只有 1.5~2.0，几乎看不见。
 * 这跟第215/216轮在视频壁纸上遇到的是**同一个病**（当时量到时钟对比度只有 1.95）。
 *
 * ## 这一轮怎么解
 *
 * **不加描边、不加底衬** ——
 * 第215/216轮试过那两条路，效果达标但用户反馈「改得有点丑」，第217轮已**全部回退**。
 * 这一轮换一条路：**按壁纸实际亮度换一套浮层墨色**。
 *
 * - 壁纸暗 → 用「浅字」（夜间那套文字色）；
 * - 壁纸亮 → 用「深字」（日间那套文字色）。
 *
 * ## 第223轮：胶囊底板也跟着走
 *
 * 用户反馈：「状态栏白色的胶囊在黑色的壁纸上有点丑」。
 * 胶囊**有底板**，它压不压得住不是看文字对比度，而是看**底板本身**跟壁纸上合不合。
 * 所以墨色里除了文字/图标，还带上了**胶囊底板 + 描边 + 次级面**：
 *
 * | 壁纸 | 胶囊底板 | 描边 | 胶囊内文字 |
 * |---|---|---|---|
 * | 暗 | 夜间那套 `#171A22` | `#333947` | 浅字 `#EDEFF3` |
 * | 亮 | 日间那套 `#F2F5F9` | `#D5DBE5` | 深字 `#191C23` |
 *
 * 底板与文字**成套**切换 ⇒ 不会出现「深底深字」。
 *
 * ## 为什么默认场景一个像素都不变
 *
 * 选哪一套**由对比度决定**（两套里谁的对比度高用谁），不是拍脑袋的阈值：
 *
 * | 场景 | 壁纸 | 选中的墨色 | 与今天 |
 * |---|---|---|---|
 * | 夜间主题 + 静态壁纸 | 深 | 浅字（夜间那套） | **完全一致** |
 * | 日间主题 + 静态壁纸 | 浅 | 深字（日间那套） | **完全一致** |
 * | 日间主题 + **暗色照片** | 深 | 浅字（夜间那套） | ⬅ 本轮修的就是它 |
 * | 夜间主题 + **亮色照片** | 浅 | 深字（日间那套） | 顺带修好 |
 *
 * 纯 Kotlin（不 import 任何 Android 类）⇒ 可以在 JVM 上直接单测。
 */
object WallpaperContrast {

    /**
     * 一套「压在壁纸上的」墨色。
     *
     * @param text 主文字（时钟 / 温度大字 / 当前句歌词）
     * @param soft 次文字（天气明细 / 城市名 / 衬句歌词）
     * @param faint 三级文字（弱态图标 / 远端歌词）
     * @param accent 强调色（裸着的连接图标等）
     * @param accentLight 强调色的提亮档（热点图标用它）
     * @param surface **胶囊底板**（第223轮）：状态栏那几个圆角底板
     * @param surfaceStrong 胶囊上的次级面（第223轮）：未选中的翻页圆点
     * @param stroke **胶囊描边**（第223轮）
     */
    data class Ink(
        val text: Int,
        val soft: Int,
        val faint: Int,
        val accent: Int,
        val accentLight: Int,
        // —— 第223轮：胶囊（状态栏那几个圆角底板）的面 ——
        // 它们必须跟着墨色**成套**切换：
        // 底板换成深色而文字还留在日间那套深字，就会出现「深底深字」。
        val surface: Int,
        val surfaceStrong: Int,
        val stroke: Int,
    )

    /**
     * 深底墨色 = **夜间那套**文字色。
     *
     * 取值直接引用 [Palettes.night]，保证跟夜间主题**逐字一致** ——
     * 壁纸暗时浮层跟夜间主题长得一样，用户不会觉得「白天突然变了个样」。
     */
    val ON_DARK = Ink(
        text = Palettes.night.text,
        soft = Palettes.night.textDim,
        faint = Palettes.night.textFaint,
        accent = Palettes.night.accent,
        accentLight = Palettes.night.accentLight,
        // 第223轮：胶囊底板 = 夜间那套（`surfaceSoft` 就是 chip / 内嵌块的面），
        // 描边 = 夜间那套 `stroke`。跟 [Skin.CHIP_FILL] / [Skin.CHIP_STROKE] 逐字相同。
        surface = Palettes.night.surfaceSoft,
        surfaceStrong = Palettes.night.surfaceStrong,
        stroke = Palettes.night.stroke,
    )

    /** 浅底墨色 = **日间那套**文字色（取值直接引用 [Palettes.day]）。 */
    val ON_LIGHT = Ink(
        text = Palettes.day.text,
        soft = Palettes.day.textDim,
        faint = Palettes.day.textFaint,
        accent = Palettes.day.accent,
        accentLight = Palettes.day.accentLight,
        // 第223轮：胶囊底板 = 日间那套（浅底 `#F2F5F9`），描边 = `#D5DBE5`。
        surface = Palettes.day.surfaceSoft,
        surfaceStrong = Palettes.day.surfaceStrong,
        stroke = Palettes.day.stroke,
    )

    /** 强调色在壁纸上的最低对比度（图标属于 UI 组件，WCAG 阈值 3.0）。 */
    const val ACCENT_MIN_CONTRAST = 3.0

    /** 主文字的最低对比度（WCAG 正文阈值 4.5）。 */
    const val TEXT_MIN_CONTRAST = 4.5

    /** 次文字的最低对比度（WCAG 大字 / UI 阈值 3.0）。 */
    const val SOFT_MIN_CONTRAST = 3.0

    /**
     * 感知亮度（Rec.601 加权），0..255。
     *
     * 口径与 `ui/pip/PipFrameProbe` 一致（全项目统一，别再写第二份公式）。
     */
    fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** WCAG 相对亮度（0..1）。 */
    fun relativeLuminance(color: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 对比度（1.0 ~ 21.0）。 */
    fun contrastRatio(fg: Int, bg: Int): Double {
        val a = relativeLuminance(fg)
        val b = relativeLuminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * 按背景色选墨色。
     *
     * **由对比度决定**，不是阈值：两套墨色里谁在背景上更清楚就用谁。
     * 所以背景亮度在临界点附近时不会「突然跳变」——
     * 背景一点点变亮，对比度就一点点换边，视觉上是平滑的。
     *
     * @param backdrop 背景的**平均颜色**；null = 还不知道（跟着主题走）
     * @param themeNight 当前主题是不是夜间（背景未知时的兜底）
     */
    fun inkFor(backdrop: Int?, themeNight: Boolean): Ink {
        if (backdrop == null) return if (themeNight) ON_DARK else ON_LIGHT
        val dark = contrastRatio(ON_DARK.text, backdrop)
        val light = contrastRatio(ON_LIGHT.text, backdrop)
        val ink = if (dark >= light) ON_DARK else ON_LIGHT
        // 中间调背景上两套墨色的对比度会打平（≈3.9，达不到正文 4.5）⇒ 再拉够一次。
        return ink.copy(
            text = ensureContrast(ink.text, backdrop, TEXT_MIN_CONTRAST),
            soft = ensureContrast(ink.soft, backdrop, SOFT_MIN_CONTRAST),
        )
    }

    /**
     * 按**亮度分布**选墨色（第231轮；视频壁纸用）。
     *
     * ## 为什么视频要单独一套
     *
     * 视频画面一直在动，而且带**局部高光**（湖畔静思右上那块暖色岩壁能到 luma 190）。
     * 只按区域平均值选墨色，会出现「平均值说够清楚、肉眼却发糊」——
     * 实测本片歌词带平均值 88（浅字 5.9:1，判定通过），
     * 但最亮那 10% 像素有 122，浅字压上去只有 3.3:1。
     *
     * ## 这一版怎么算
     *
     * 1. **方向由平均值定**（[WallpaperSampler.Tone.average]）——
     *    局部一团高光不该把整块区域判成「亮底」；
     * 2. 方向定下后，**按最坏情况把字色拉够**：
     *    浅字对着**亮端 10%**、深字对着**暗端 10%** 去做 [ensureContrast]。
     *
     * 拉不动也没关系（中灰背景上纯白也只有 4.3:1）：二分会在「最远的那一端」收敛，
     * 拿到的是**该方向上可能的最高对比度**，不会反过来把字色混成灰。
     *
     * @param tone 区域的亮度分布；null = 还不知道（跟着主题走）
     * @param themeNight 当前主题是不是夜间（分布未知时的兜底）
     */
    /** 视频壁纸极性迟滞上界：当前深底浅字时，平均亮度需升至 ≥145 才切浅底深字。 */
    const val VIDEO_SWITCH_TO_LIGHT_LUMA = 145

    /** 视频壁纸极性迟滞下界：当前浅底深字时，平均亮度需降至 ≤110 才切深底浅字。 */
    const val VIDEO_SWITCH_TO_DARK_LUMA = 110

    fun inkForTone(
        tone: WallpaperSampler.Tone?,
        themeNight: Boolean,
        prevUseDarkInk: Boolean? = null,
    ): Ink {
        if (tone == null) return if (themeNight) ON_DARK else ON_LIGHT
        val average = tone.average
        // 方向只看平均值（避免被局部高光带偏），并支持视频壁纸极性迟滞，防止临界帧反复跳变。
        val avgLuma = luma(average)
        val useDarkInk = when (prevUseDarkInk) {
            true -> avgLuma < VIDEO_SWITCH_TO_LIGHT_LUMA
            false -> avgLuma <= VIDEO_SWITCH_TO_DARK_LUMA
            null -> {
                val darkOnAverage = contrastRatio(ON_DARK.text, average)
                val lightOnAverage = contrastRatio(ON_LIGHT.text, average)
                darkOnAverage >= lightOnAverage
            }
        }
        val base = if (useDarkInk) ON_DARK else ON_LIGHT
        val toward = if (useDarkInk) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        // 主文字按最坏高光/暗端拉够对比度，但锁定提亮/压暗方向，绝不反向反转；
        // 次级文字（如三行歌词相邻行）按区域平均色校验，避免局部小高光把浅字压黑或随帧抖动。
        val worst = if (useDarkInk) tone.bright else tone.dark
        return base.copy(
            text = ensureContrastToward(base.text, worst, TEXT_MIN_CONTRAST, toward),
            soft = ensureContrastToward(base.soft, average, SOFT_MIN_CONTRAST, toward),
        )
    }

    /**
     * 保证颜色压在背景上够清楚：不够就朝「更亮 / 更暗」的方向**最少地**挪一点。
     *
     * 只用在**强调色**上（用户自定义色要保留色相，不能直接换成另一套），
     * 文字三档走 [inkFor] 的整套切换（切换出来的颜色是干净的，不是灰扑扑的混合色）。
     *
     * @param target 目标对比度
     * @return 对比度已经够的原色；不够则返回混合后的颜色
     */
    fun ensureContrast(color: Int, backdrop: Int, target: Double): Int {
        if (contrastRatio(color, backdrop) >= target) return color
        // 往「离背景更远」的方向走：
        // 字比背景亮 → 继续提亮；字比背景暗 → 继续压暗。
        // （不能只看背景明暗：白字压中灰底时，往黑里混只会更看不清。）
        val toward = if (relativeLuminance(color) >= relativeLuminance(backdrop)) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF000000.toInt()
        }
        return ensureContrastToward(color, backdrop, target, toward)
    }

    /** 按指定目标极性（纯白或纯黑）单向拉够对比度，避免深底浅字被局部高光反向混成黑色。 */
    fun ensureContrastToward(color: Int, backdrop: Int, target: Double, toward: Int): Int {
        if (contrastRatio(color, backdrop) >= target) return color
        // 二分：找**最小**的混合比（少动一点，色相尽量保住）。
        var lo = 0f
        var hi = 1f
        repeat(14) {
            val mid = (lo + hi) / 2f
            if (contrastRatio(mix(color, toward, mid), backdrop) >= target) hi = mid else lo = mid
        }
        return mix(color, toward, hi)
    }

    /**
     * 两个 ARGB 颜色按比例混合（只混 RGB，alpha 取 [a] 的）。
     *
     * @param t 0 = 全取 [a]，1 = 全取 [b]
     */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val inv = 1f - k
        val aa = (a ushr 24) and 0xFF
        val rr = ((a shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * k
        val gg = ((a shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * k
        val bb = (a and 0xFF) * inv + (b and 0xFF) * k
        return (aa shl 24) or
            (rr.toInt() and 0xFF shl 16) or
            (gg.toInt() and 0xFF shl 8) or
            (bb.toInt() and 0xFF)
    }
}
