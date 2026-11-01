package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面卡片布局模型（第九十五轮 C2）：编码/解码、补缺、移动、尺寸与显隐、宽度分配。
 */
class CardLayoutTest {

    private val natural = mapOf(
        CardLayout.QUICK to 338f,
        CardLayout.MUSIC to 338f,
        CardLayout.CLIMATE to 333f,
        CardLayout.TIRE to 350f,
        CardLayout.CAR to 460f,
        CardLayout.TRIP to 340f,
        CardLayout.STATUS to 380f,
    )

    private fun natural(id: String) = natural[id] ?: 0f

    @Test
    fun 默认布局包含五张卡且编码可往返() {
        val encoded = CardLayout.encode(CardLayout.DEFAULT)
        assertEquals(
            "quick:AUTO:1,music:AUTO:1,climate:AUTO:1,tire:AUTO:1,car:AUTO:1," +
                "trip:AUTO:0,status:AUTO:0",
            encoded,
        )
        assertEquals(CardLayout.DEFAULT, CardLayout.decode(encoded))
    }

    @Test
    fun 非法编码返回空() {
        assertNull(CardLayout.decode(""))
        assertNull(CardLayout.decode(null))
        assertNull(CardLayout.decode("quick:AUTO:1,quick:SMALL:1"))
        assertNull(CardLayout.decode("unknown:AUTO:1"))
        assertNull(CardLayout.decode("quick:HUGE:1"))
        assertNull(CardLayout.decode("quick:AUTO:2"))
        assertNull(CardLayout.decode("quick:AUTO"))
    }

    @Test
    fun 解码缺项时会补齐并保持给到的顺序() {
        val decoded = CardLayout.decode("car:SMALL:1,quick:LARGE:0")!!
        assertEquals(
            listOf(
                CardLayout.CAR, CardLayout.QUICK, CardLayout.MUSIC, CardLayout.CLIMATE,
                CardLayout.TIRE, CardLayout.TRIP, CardLayout.STATUS,
            ),
            decoded.map { it.id },
        )
        assertEquals(CardLayout.Size.SMALL, decoded.first().size)
        assertEquals(false, decoded[1].visible)
    }

    @Test
    fun 移动顺序() {
        val moved = CardLayout.move(CardLayout.DEFAULT, 0, 2)
        assertEquals(
            listOf("music", "climate", "quick", "tire", "car", "trip", "status"),
            moved.map { it.id },
        )
        assertEquals(CardLayout.DEFAULT, CardLayout.move(CardLayout.DEFAULT, 9, 0))
    }

    @Test
    fun 改尺寸只影响指定卡() {
        val layout = CardLayout.withSize(CardLayout.DEFAULT, CardLayout.MUSIC, CardLayout.Size.LARGE)
        assertEquals(CardLayout.Size.LARGE, layout.first { it.id == CardLayout.MUSIC }.size)
        assertEquals(1, layout.count { it.size != CardLayout.Size.AUTO })
    }

    @Test
    fun 隐藏卡但至少保留一张可见() {
        val hidden = CardLayout.withVisible(CardLayout.DEFAULT, CardLayout.TIRE, false)
        assertEquals(false, hidden.first { it.id == CardLayout.TIRE }.visible)
        assertEquals(4, CardLayout.visible(hidden).size)

        val onlyCar = CardLayout.DEFAULT.map { it.copy(visible = it.id == CardLayout.CAR) }
        val still = CardLayout.withVisible(onlyCar, CardLayout.CAR, false)
        assertTrue("最后一张可见卡不能被隐藏", still.any { it.visible })
    }

    @Test
    fun 自适应时车况卡吃掉剩余宽度() {
        val widths = CardLayout.widths(CardLayout.DEFAULT, 1832f, 20f, ::natural)
        assertEquals(listOf("quick", "music", "climate", "tire", "car"), widths.map { it.first })
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 4
        assertEquals(1832f, sum, 0.5f)
        assertTrue("车况卡应该更宽", widths.last().second > 300f)
    }

    @Test
    fun 隐藏后没有空隙且隐藏卡不参与排布() {
        val layout = CardLayout.withVisible(CardLayout.DEFAULT, CardLayout.CLIMATE, false)
        val widths = CardLayout.widths(layout, 1832f, 20f, ::natural)
        assertEquals(listOf("quick", "music", "tire", "car"), widths.map { it.first })
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 3
        assertEquals(1832f, sum, 0.5f)
    }

    @Test
    fun 车况卡改固定档位时剩余宽度均分给自适应卡() {
        val layout = CardLayout.withSize(CardLayout.DEFAULT, CardLayout.CAR, CardLayout.Size.LARGE)
        val widths = CardLayout.widths(layout, 1832f, 20f, ::natural)
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 4
        assertEquals(1832f, sum, 0.5f)
        assertEquals(460f * 1.25f, widths.last().second, 0.5f)
    }

    @Test
    fun 大档位比中档位更宽() {
        val medium = CardLayout.widths(CardLayout.DEFAULT, 1832f, 20f, ::natural)
        val large = CardLayout.widths(
            CardLayout.withSize(CardLayout.DEFAULT, CardLayout.QUICK, CardLayout.Size.LARGE),
            1832f,
            20f,
            ::natural,
        )
        assertTrue(large.first().second > medium.first().second)
    }

    @Test
    fun 卡片库新增卡默认隐藏并可添加() {
        val defaults = CardLayout.DEFAULT
        assertEquals(false, defaults.first { it.id == CardLayout.TRIP }.visible)
        assertEquals(false, defaults.first { it.id == CardLayout.STATUS }.visible)
        val added = CardLayout.withVisible(defaults, CardLayout.TRIP, true)
        assertEquals(6, CardLayout.visible(added).size)
    }

    @Test
    fun 七张卡同时显示时整行不溢出() {
        val all = CardLayout.DEFAULT.map { it.copy(visible = true) }
        val widths = CardLayout.widths(all, 1832f, 20f, ::natural)
        assertEquals(7, widths.size)
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 6
        assertEquals("整行必须刚好铺满且不溢出", 1832f, sum, 1f)
        assertTrue("每张卡都要保留最小可读宽度", widths.all { it.second >= 120f })
    }
}
