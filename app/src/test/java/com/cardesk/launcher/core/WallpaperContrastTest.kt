package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第222轮：**壁纸亮度 → 浮层墨色**的核心回归。
 *
 * 这几条是「一眼就知道、但很容易被改回去」的性质：
 * 1. 背景暗 → 浅字；背景亮 → 深字（**由对比度决定**，不是拍阈值）；
 * 2. **默认场景一个像素都不变** —— 夜间主题 + 深壁纸 / 日间主题 + 浅壁纸，
 *    选出来的墨色必须和今天的主题 token **逐字相同**；
 * 3. 强调色要保得住对比度，又尽量保住用户选的色相。
 *
 * 纯 JVM（`WallpaperContrast` 不 import 任何 Android 类）。
 */
class WallpaperContrastTest {

    /** 纯黑 / 纯白 / 中灰。 */
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val mid = 0xFF808080.toInt()

    /** 一张暗色壁纸（接近深蓝夜空）。 */
    private val darkBackdrop = 0xFF10141F.toInt()

    /** 一张浅色壁纸（接近日间的纸白）。 */
    private val lightBackdrop = 0xFFF2F0E8.toInt()

    @Test
    fun 亮度公式与对比度符合标准() {
        assertEquals(255, WallpaperContrast.luma(white))
        assertEquals(0, WallpaperContrast.luma(black))
        assertEquals(128, WallpaperContrast.luma(mid))
        // WCAG：白压黑 = 21:1，同色 = 1:1
        assertEquals(21.0, WallpaperContrast.contrastRatio(white, black), 0.01)
        assertEquals(1.0, WallpaperContrast.contrastRatio(mid, mid), 0.001)
    }

    @Test
    fun 背景未知时跟着主题走() {
        assertEquals(WallpaperContrast.ON_DARK, WallpaperContrast.inkFor(null, themeNight = true))
        assertEquals(WallpaperContrast.ON_LIGHT, WallpaperContrast.inkFor(null, themeNight = false))
    }

    @Test
    fun 暗色壁纸配日间主题时换成浅字() {
        // 这就是本轮要修的那条：日间是深字，压暗色壁纸上几乎看不见。
        val ink = WallpaperContrast.inkFor(darkBackdrop, themeNight = false)
        // ⚠️ 用 equals 不用 assertSame：`inkFor` 会按背景再拉一次对比度（返回新对象）。
        assertEquals(WallpaperContrast.ON_DARK, ink)
        assertEquals(Palettes.night.text, ink.text)
    }

    @Test
    fun 默认场景的墨色与主题token逐字相同() {
        // 夜间主题 + 深色壁纸 → 夜间那套（今天就是这样，一个像素都不变）。
        val night = WallpaperContrast.inkFor(darkBackdrop, themeNight = true)
        assertEquals(Palettes.night.text, night.text)
        assertEquals(Palettes.night.textDim, night.soft)
        assertEquals(Palettes.night.textFaint, night.faint)
        assertEquals(Palettes.night.accent, night.accent)

        // 日间主题 + 浅色壁纸 → 日间那套（今天也是这样）。
        val day = WallpaperContrast.inkFor(lightBackdrop, themeNight = false)
        assertEquals(Palettes.day.text, day.text)
        assertEquals(Palettes.day.textDim, day.soft)
        assertEquals(Palettes.day.textFaint, day.faint)
        assertEquals(Palettes.day.accent, day.accent)
    }

    @Test
    fun 选中的主文字对背景一定有足够对比度() {
        // 背景从纯黑扫到纯白，主文字的对比度都必须够。
        //
        // 理论下限 = 4.28：黑白两套墨色的对比度在「中灰背景」上打平，
        // 那一点上两边都是 4.28（AA 大字阈值 3.0，AA 正文阈值 4.5）。
        // 状态栏时钟（32px）、天气大字（96px）、当前句歌词（56px）都是大字 ⇒ 达标。
        var v = 0
        while (v <= 255) {
            val backdrop = (0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
            val ink = WallpaperContrast.inkFor(backdrop, themeNight = false)
            val ratio = WallpaperContrast.contrastRatio(ink.text, backdrop)
            assertTrue(
                "背景亮度 $v 时对比度只有 $ratio",
                ratio >= 4.4,
            )
            v += 5
        }
    }

    @Test
    fun 胶囊底板跟墨色成套切换() {
        // 第223轮：胶囊**有底板**，暗壁纸上的白胶囊很丑 ⇒ 底板也要成套换。
        val dark = WallpaperContrast.inkFor(darkBackdrop, themeNight = false)
        assertEquals(Palettes.night.surfaceSoft, dark.surface)
        assertEquals(Palettes.night.stroke, dark.stroke)
        assertEquals(Palettes.night.surfaceStrong, dark.surfaceStrong)

        val light = WallpaperContrast.inkFor(lightBackdrop, themeNight = false)
        assertEquals(Palettes.day.surfaceSoft, light.surface)
        assertEquals(Palettes.day.stroke, light.stroke)
        assertEquals(Palettes.day.surfaceStrong, light.surfaceStrong)
    }

    @Test
    fun 胶囊底板与主题token逐字相同() {
        // 默认场景（夜间主题 + 深壁纸 / 日间主题 + 浅壁纸）必须**一个像素都不变**：
        // 胶囊底板 / 描边要和全局 `Skin.CHIP_FILL` / `Skin.CHIP_STROKE` 逐字相同。
        val night = WallpaperContrast.inkFor(darkBackdrop, themeNight = true)
        assertEquals(Palettes.night.surfaceSoft, night.surface)
        assertEquals(Palettes.night.stroke, night.stroke)

        val day = WallpaperContrast.inkFor(lightBackdrop, themeNight = false)
        assertEquals(Palettes.day.surfaceSoft, day.surface)
        assertEquals(Palettes.day.stroke, day.stroke)
    }

    @Test
    fun 胶囊底板上的文字永远够清楚() {
        // 底板和文字成套切换 ⇒ 「深底深字」不可能发生。
        // 这里把背景从纯黑扫到纯白，两套墨色各验一遍：文字压在**自己的底板**上。
        var v = 0
        while (v <= 255) {
            val backdrop = (0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
            for (night in listOf(true, false)) {
                val ink = WallpaperContrast.inkFor(backdrop, themeNight = night)
                val ratio = WallpaperContrast.contrastRatio(ink.text, ink.surface)
                assertTrue(
                    "背景亮度 $v（夜间=$night）时，文字压在自己的胶囊底板上只有 $ratio",
                    ratio >= 4.4,
                )
            }
            v += 5
        }
    }

    @Test
    fun 强调色不够清楚时会被拉清楚但尽量保住色相() {
        // 已经够清楚 → 原样返回（不动用户的色）。
        val ok = 0xFF33C6B4.toInt()
        assertEquals(ok, WallpaperContrast.ensureContrast(ok, black, 3.0))

        // 深色压深色 → 往亮里拉，拉到对比度够为止。
        val dark = 0xFF0A1F1C.toInt()
        assertTrue(
            "这个底色本来就该不够清楚",
            WallpaperContrast.contrastRatio(dark, black) < 3.0,
        )
        val fixed = WallpaperContrast.ensureContrast(dark, black, 3.0)
        assertNotEquals(dark, fixed)
        assertTrue(
            "对比度没拉够：${WallpaperContrast.contrastRatio(fixed, black)}",
            WallpaperContrast.contrastRatio(fixed, black) >= 3.0,
        )
        // 色相尽量保住：还是绿的（绿通道仍然是最高的通道）。
        val g = (fixed shr 8) and 0xFF
        val r = (fixed shr 16) and 0xFF
        val b = fixed and 0xFF
        assertTrue("色相跑掉了：r=$r g=$g b=$b", g > r && g > b)
    }

    @Test
    fun 混合色只动rgb不动alpha() {
        val a = 0x80FF0000.toInt()
        val b = 0xFFFFFFFF.toInt()
        val mixed = WallpaperContrast.mix(a, b, 0.5f)
        assertEquals(0x80, (mixed ushr 24) and 0xFF)
        assertEquals(255, (mixed shr 16) and 0xFF)
    }

    // ------------------------------------------------------------------
    // 第231轮：视频壁纸按「亮度分布」选墨色
    // ------------------------------------------------------------------

    /** 本片实测的那个歌词带：平均 luma 88、最亮 10% 到 122。 */
    private val lyricAverage = 0xFF585858.toInt()   // luma 88
    private val lyricBright = 0xFF7A7A7A.toInt()    // luma 122

    @Test
    fun 分布未知时仍是主题那套墨色() {
        assertSame(
            WallpaperContrast.ON_DARK,
            WallpaperContrast.inkForTone(null, themeNight = true),
        )
        assertSame(
            WallpaperContrast.ON_LIGHT,
            WallpaperContrast.inkForTone(null, themeNight = false),
        )
    }

    @Test
    fun 视频暗底选浅字且按最亮那撮像素把字色拉够() {
        val tone = WallpaperSampler.Tone(
            average = lyricAverage,
            dark = 0xFF303030.toInt(),
            bright = lyricBright,
        )
        val ink = WallpaperContrast.inkForTone(tone, themeNight = false)
        // 方向必须是浅字（不能被日间主题带成深字）—— 这正是用户报的病。
        assertEquals(
            WallpaperContrast.ON_DARK.surface,
            ink.surface,
        )
        // 只按平均值算：`#EDEFF3` 压 88 已经有 5.9:1，判定「不用动」；
        // 加上亮端 122 ⇒ 必须往白里再拉一截。
        assertTrue(
            "浅字要比主题那档更亮：${WallpaperContrast.luma(ink.text)}",
            WallpaperContrast.luma(ink.text) > WallpaperContrast.luma(WallpaperContrast.ON_DARK.text),
        )
        // 拉到极限也到不了 4.5（中灰的物理上限 ≈4.3）—— 但必须明显好于不拉。
        val achieved = WallpaperContrast.contrastRatio(ink.text, lyricBright)
        val naive = WallpaperContrast.contrastRatio(WallpaperContrast.ON_DARK.text, lyricBright)
        assertTrue("拉过之后要更好：$achieved vs $naive", achieved > naive + 0.3)
        assertTrue("不能超过物理上限：$achieved", achieved <= 4.4)
    }

    @Test
    fun 局部高光不会把整块区域判成亮底() {
        // 平均值是暗的（luma 88），但最亮那 10% 有 200（极亮高光）。
        val tone = WallpaperSampler.Tone(
            average = lyricAverage,
            dark = 0xFF303030.toInt(),
            bright = 0xFFC8C8C8.toInt(),
        )
        val ink = WallpaperContrast.inkForTone(tone, themeNight = false)
        // 方向仍按平均值 ⇒ 还是浅字那一套（底板 / 描边成套跟着走），不能翻成深字。
        assertEquals(WallpaperContrast.ON_DARK.surface, ink.surface)
        assertTrue(
            "浅字不能被高光带成深字：${WallpaperContrast.luma(ink.text)}",
            WallpaperContrast.luma(ink.text) >= WallpaperContrast.luma(WallpaperContrast.ON_DARK.text),
        )
    }

    @Test
    fun 视频亮底选深字且按最暗那撮像素拉够() {
        val tone = WallpaperSampler.Tone(
            average = 0xFFC0C0C0.toInt(),
            dark = 0xFF6E6E6E.toInt(),
            bright = 0xFFF0F0F0.toInt(),
        )
        val ink = WallpaperContrast.inkForTone(tone, themeNight = true)
        assertEquals(WallpaperContrast.ON_LIGHT.surface, ink.surface)
        assertTrue(
            "深字要压得比主题那档更暗：${WallpaperContrast.luma(ink.text)}",
            WallpaperContrast.luma(ink.text) <= WallpaperContrast.luma(WallpaperContrast.ON_LIGHT.text),
        )
    }

    @Test
    fun 暗色视频带高光时次级歌词绝不反黑且跨帧稳定() {
        // 实车诊断包实测循环两帧：center=31/bright=163 与 center=36/bright=181
        val frameA = WallpaperSampler.Tone(
            average = 0xFF1F1F1F.toInt(), // luma 31
            dark = 0xFF101010.toInt(),
            bright = 0xFFA3A3A3.toInt(), // luma 163
        )
        val frameB = WallpaperSampler.Tone(
            average = 0xFF242424.toInt(), // luma 36
            dark = 0xFF121212.toInt(),
            bright = 0xFFB5B5B5.toInt(), // luma 181
        )
        val inkA = WallpaperContrast.inkForTone(frameA, themeNight = true, prevUseDarkInk = true)
        val inkB = WallpaperContrast.inkForTone(frameB, themeNight = true, prevUseDarkInk = true)

        // 次级歌词（soft）不能被亮端 163/181 反向混成黑灰，应保持浅色且两帧完全一致
        assertTrue("soft 不应反黑：luma=${WallpaperContrast.luma(inkA.soft)}", WallpaperContrast.luma(inkA.soft) >= 140)
        assertEquals("相邻循环帧的浮层墨色应完全稳定不闪烁", inkA, inkB)
    }

    @Test
    fun 视频壁纸亮度在迟滞带内不反复切换极性() {
        val midTone = WallpaperSampler.Tone(
            average = 0xFF848484.toInt(), // luma 132，处于 [110, 145] 迟滞带内
            dark = 0xFF505050.toInt(),
            bright = 0xFFB0B0B0.toInt(),
        )
        val stayedDark = WallpaperContrast.inkForTone(midTone, themeNight = true, prevUseDarkInk = true)
        val stayedLight = WallpaperContrast.inkForTone(midTone, themeNight = false, prevUseDarkInk = false)
        assertEquals(WallpaperContrast.ON_DARK.surface, stayedDark.surface)
        assertEquals(WallpaperContrast.ON_LIGHT.surface, stayedLight.surface)
    }
}
