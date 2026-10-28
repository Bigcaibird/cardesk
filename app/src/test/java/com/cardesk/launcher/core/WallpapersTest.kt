package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸目录（第五十七轮）的纯逻辑回归。
 *
 * 这一组是 **JVM 单测**（不碰 Android），钉住三件事：
 * 1. 9 张壁纸都在、名字不重复、默认那张是**风景类**（不是纯色）；
 * 2. 下标越界 / 首尾相接的边界；
 * 3. 所有颜色字面量都是 **8 位不透明 ARGB**（6 位十六进制会让 alpha 变 0）。
 */
class WallpapersTest {

    @Test
    fun 有九张壁纸且名字不重复() {
        assertEquals("预置壁纸应该是 9 张", 9, Wallpapers.COUNT)
        assertEquals(9, Wallpapers.SPECS.size)
        assertEquals(9, Wallpapers.NAMES.size)
        assertEquals("名字不能重复", Wallpapers.COUNT, Wallpapers.NAMES.toSet().size)
        Wallpapers.NAMES.forEach {
            assertTrue("壁纸名不能为空：$it", it.isNotBlank())
        }
    }

    @Test
    fun 默认壁纸是风景类不是纯色() {
        assertEquals("默认壁纸下标", 0, Wallpapers.DEFAULT_INDEX)
        val spec = Wallpapers.spec(Wallpapers.DEFAULT_INDEX)
        // 「紫晖雪山」= 雪峰场景（风景），不是纯色 / 纯渐变。
        assertEquals("紫晖雪山", spec.name)
        assertEquals(Wallpapers.Style.PEAK, spec.style)
        assertTrue("风景类壁纸要有一句描述", spec.desc.isNotBlank())
    }

    @Test
    fun 每张壁纸都是风景类场景() {
        val styles = Wallpapers.SPECS.map { it.style }.toSet()
        assertTrue("场景种类不能少于 6 种，实际 ${styles.size}", styles.size >= 6)
        Wallpapers.SPECS.forEach {
            assertTrue("每张壁纸都要有描述：${it.name}", it.desc.isNotBlank())
        }
    }

    @Test
    fun 下标越界能兜住() {
        assertEquals(0, Wallpapers.normalize(-9))
        assertEquals(8, Wallpapers.normalize(8))
        assertEquals(0, Wallpapers.normalize(9))
        assertEquals(8, Wallpapers.normalize(17))
        assertEquals(8, Wallpapers.normalize(-1))
        // -1 规整到 8（最后一张「云海松林」）
        assertEquals("云海松林", Wallpapers.name(-1))
    }

    @Test
    fun 上一张下一张首尾相接() {
        assertEquals(1, Wallpapers.next(0))
        assertEquals(0, Wallpapers.next(8))
        assertEquals(8, Wallpapers.prev(0))
        assertEquals(8, Wallpapers.prev(9))
    }

    @Test
    fun 颜色都是八位不透明argb() {
        fun checkTone(tone: Wallpapers.Tone, where: String) {
            val colors = mapOf(
                "skyTop" to tone.skyTop,
                "skyMid" to tone.skyMid,
                "skyLow" to tone.skyLow,
                "sun" to tone.sun,
                "sunGlow" to tone.sunGlow,
                "ridgeFar" to tone.ridgeFar,
                "ridgeMid" to tone.ridgeMid,
                "ridgeNear" to tone.ridgeNear,
                "ridgeFront" to tone.ridgeFront,
                "water" to tone.water,
                "haze" to tone.haze,
                "detail" to tone.detail,
            )
            colors.forEach { (name, c) ->
                assertEquals(
                    "$where 的 $name 必须是不透明 ARGB（alpha=0xFF）",
                    0xFF,
                    (c ushr 24) and 0xFF,
                )
            }
        }
        Wallpapers.SPECS.forEach {
            checkTone(it.day, "${it.name}·日")
            checkTone(it.night, "${it.name}·夜")
        }
    }

    @Test
    fun 日夜两套配色不一样() {
        Wallpapers.SPECS.forEach {
            assertNotEquals("${it.name} 的日夜配色不能完全一样", it.day, it.night)
        }
    }

    @Test
    fun 混合颜色的端点与中点() {
        val a = 0xFF102030.toInt()
        val b = 0xFFA0B0C0.toInt()
        assertEquals(a, Wallpapers.mix(a, b, 0f))
        assertEquals(b, Wallpapers.mix(a, b, 1f))
        val mid = Wallpapers.mix(a, b, 0.5f)
        assertEquals(0xFF, (mid ushr 24) and 0xFF)
        // (0x10 + 0xA0) / 2 = 0x58，(0x20 + 0xB0) / 2 = 0x68，(0x30 + 0xC0) / 2 = 0x78
        assertEquals(0x58, (mid ushr 16) and 0xFF)
        assertEquals(0x68, (mid ushr 8) and 0xFF)
        assertEquals(0x78, mid and 0xFF)
    }

    @Test
    fun 背景视图的壁纸名与目录一致() {
        assertEquals(
            "BackgroundView.NAMES 必须和 Wallpapers.NAMES 同源（场景页提示条圆点也用它）",
            Wallpapers.NAMES,
            com.cardesk.launcher.ui.widget.BackgroundView.NAMES.toList(),
        )
    }
}
