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
        CardLayout.RANGE to 311f,
        CardLayout.TRIP1 to 388f,
        CardLayout.TRIP2 to 380f,
        CardLayout.STATUS to 380f,
    )

    private fun natural(id: String) = natural[id] ?: 0f

    @Test
    fun 默认布局包含五张卡且编码可往返() {
        val encoded = CardLayout.encode(CardLayout.DEFAULT)
        assertEquals(
            "car:MEDIUM:1,quick:AUTO:0,music:AUTO:1,climate:AUTO:1,tire:AUTO:1," +
                "trip1:MEDIUM:1,trip2:AUTO:0,status:AUTO:0",
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
                CardLayout.RANGE, CardLayout.QUICK, CardLayout.MUSIC, CardLayout.CLIMATE,
                CardLayout.TIRE, CardLayout.TRIP1, CardLayout.TRIP2, CardLayout.STATUS,
            ),
            decoded.map { it.id },
        )
        assertEquals(CardLayout.Size.SMALL, decoded.first().size)
        assertEquals(false, decoded[1].visible)
    }

    @Test
    fun 旧行程卡迁移到行程1但默认隐藏() {
        // 需求：行程卡只在卡片库里，升级不自动上桌。
        val decoded = CardLayout.decode("trip:AUTO:1,quick:AUTO:0")!!
        assertEquals(CardLayout.TRIP1, decoded.first().id)
        assertEquals(false, decoded.first().visible)
        assertEquals(CardLayout.TRIP1, CardLayout.migrate("trip"))
        assertEquals(CardLayout.TRIP2, CardLayout.migrate(CardLayout.TRIP2))
        // 第九十六轮：行程1 默认显示（挪到原来快捷应用卡的位置），行程2 仍在卡片库
        assertEquals(true, CardLayout.DEFAULT.first { it.id == CardLayout.TRIP1 }.visible)
        assertEquals(false, CardLayout.DEFAULT.first { it.id == CardLayout.TRIP2 }.visible)
    }

    @Test
    fun 移动顺序() {
        val moved = CardLayout.move(CardLayout.DEFAULT, 0, 2)
        assertEquals(
            listOf("quick", "music", "car", "climate", "tire", "trip1", "trip2", "status"),
            moved.map { it.id },
        )
        assertEquals(CardLayout.DEFAULT, CardLayout.move(CardLayout.DEFAULT, 9, 0))
    }

    @Test
    fun 改尺寸只影响指定卡() {
        val layout = CardLayout.withSize(CardLayout.DEFAULT, CardLayout.MUSIC, CardLayout.Size.LARGE)
        assertEquals(CardLayout.Size.LARGE, layout.first { it.id == CardLayout.MUSIC }.size)
        // 默认布局里行程1/续航已经是固定 MEDIUM，再加音乐这一张 = 3 张非自适应
        assertEquals(3, layout.count { it.size != CardLayout.Size.AUTO })
    }

    @Test
    fun 隐藏卡但至少保留一张可见() {
        val hidden = CardLayout.withVisible(CardLayout.DEFAULT, CardLayout.TIRE, false)
        assertEquals(false, hidden.first { it.id == CardLayout.TIRE }.visible)
        assertEquals(4, CardLayout.visible(hidden).size)

        val onlyCar = CardLayout.DEFAULT.map { it.copy(visible = it.id == CardLayout.RANGE) }
        val still = CardLayout.withVisible(onlyCar, CardLayout.RANGE, false)
        assertTrue("最后一张可见卡不能被隐藏", still.any { it.visible })
    }

    @Test
    fun 默认布局里行程宽续航窄() {
        val widths = CardLayout.widths(CardLayout.DEFAULT, 1832f, 20f, ::natural)
        assertEquals(listOf("car", "music", "climate", "tire", "trip1"), widths.map { it.first })
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 4
        assertEquals(1832f, sum, 0.5f)
        // 续航 311 在最左、行程 388 在收尾（与右侧车况卡同宽）
        assertEquals(311f, widths.first().second, 0.5f)
        assertEquals(388f, widths.last().second, 0.5f)
        assertTrue("行程卡应该比续航卡宽", widths.last().second > widths.first().second)
    }

    @Test
    fun 隐藏后没有空隙且隐藏卡不参与排布() {
        val layout = CardLayout.withVisible(CardLayout.DEFAULT, CardLayout.CLIMATE, false)
        val widths = CardLayout.widths(layout, 1832f, 20f, ::natural)
        assertEquals(listOf("car", "music", "tire", "trip1"), widths.map { it.first })
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 3
        assertEquals(1832f, sum, 0.5f)
    }

    @Test
    fun 续航卡改固定档位时剩余宽度均分给自适应卡() {
        val layout = CardLayout.withSize(CardLayout.DEFAULT, CardLayout.RANGE, CardLayout.Size.LARGE)
        val widths = CardLayout.widths(layout, 1832f, 20f, ::natural)
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 4
        assertEquals(1832f, sum, 0.5f)
        assertEquals(311f * 1.25f, widths.first().second, 0.5f)
    }

    @Test
    fun 大档位比中档位更宽() {
        val medium = CardLayout.widths(CardLayout.DEFAULT, 1832f, 20f, ::natural)
        val large = CardLayout.widths(
            CardLayout.withSize(CardLayout.DEFAULT, CardLayout.TRIP1, CardLayout.Size.LARGE),
            1832f,
            20f,
            ::natural,
        )
        assertTrue(large.last().second > medium.last().second)
    }

    @Test
    fun 卡片库新增卡默认隐藏并可添加() {
        val defaults = CardLayout.DEFAULT
        assertEquals(true, defaults.first { it.id == CardLayout.TRIP1 }.visible)
        assertEquals(false, defaults.first { it.id == CardLayout.QUICK }.visible)
        assertEquals(false, defaults.first { it.id == CardLayout.TRIP2 }.visible)
        assertEquals(false, defaults.first { it.id == CardLayout.STATUS }.visible)
        val added = CardLayout.withVisible(defaults, CardLayout.TRIP2, true)
        assertEquals(6, CardLayout.visible(added).size)
    }

    @Test
    fun 八张卡同时显示时整行不溢出() {
        val all = CardLayout.DEFAULT.map { it.copy(visible = true) }
        val widths = CardLayout.widths(all, 1832f, 20f, ::natural)
        assertEquals(8, widths.size)
        val sum = widths.sumOf { it.second.toDouble() }.toFloat() + 20f * 7
        assertEquals("整行必须刚好铺满且不溢出", 1832f, sum, 1f)
        assertTrue("每张卡都要保留最小可读宽度", widths.all { it.second >= 120f })
    }

    @Test
    fun 续航与行程1位置互换() {
        val swapped = CardLayout.migrateSwapCarTrip(CardLayout.DEFAULT)
        assertEquals(CardLayout.TRIP1, swapped.first().id)
        assertEquals(CardLayout.RANGE, swapped[5].id)
        // 尺寸/显隐跟着卡走
        assertEquals(CardLayout.Size.MEDIUM, swapped.first().size)
    }

    fun 快捷应用收进卡片库并把行程1挪到最左() {
        // 升级前的默认布局：快捷应用可见在最左，行程1 隐藏在卡片库。
        val old = listOf(
            CardLayout.QUICK, CardLayout.MUSIC, CardLayout.CLIMATE,
            CardLayout.TIRE, CardLayout.RANGE, CardLayout.TRIP1, CardLayout.TRIP2, CardLayout.STATUS,
        ).map { id -> CardLayout.Slot(id, CardLayout.Size.AUTO, visible = id != CardLayout.TRIP1 && id != CardLayout.TRIP2 && id != CardLayout.STATUS) }
        val migrated = CardLayout.migrateQuickToTrip(old)
        assertEquals(CardLayout.TRIP1, migrated.first().id)
        assertEquals(true, migrated.first().visible)
        assertEquals(false, migrated.first { it.id == CardLayout.QUICK }.visible)
        // 其它卡的尺寸/显隐原样保留
        assertEquals(true, migrated.first { it.id == CardLayout.MUSIC }.visible)
        assertEquals(true, migrated.first { it.id == CardLayout.RANGE }.visible)
    }

    @Test
    fun 行程与续航尺寸迁移成固定档() {
        val old = CardLayout.DEFAULT.map { it.copy(size = CardLayout.Size.AUTO) }
        val migrated = CardLayout.migrateSizes(old)
        assertEquals(CardLayout.Size.MEDIUM, migrated.first { it.id == CardLayout.TRIP1 }.size)
        assertEquals(CardLayout.Size.MEDIUM, migrated.first { it.id == CardLayout.RANGE }.size)
        // 其它卡尺寸不动
        assertEquals(CardLayout.Size.AUTO, migrated.first { it.id == CardLayout.MUSIC }.size)
    }

    // ---------------------------------------------------------------- 第111轮

    /**
     * 第111轮：设置页「桌面卡片 → 车况」的开关控制的是**续航卡**，不是车况卡。
     *
     * 根因：两张卡被同一个名字混起来了——
     * - 行内卡 id = `car`（[CardLayout.RANGE]）画的是「续航」（里程 + 油量条）；
     * - 真正的车况卡是右侧固定那张（[CardLayout.CAR_STATUS]），以前**没有开关**。
     */
    @Test
    fun 标题映射_行内car是续航_车况卡另有其名() {
        assertEquals("续航", CardLayout.title(CardLayout.RANGE))
        assertEquals("车况", CardLayout.title(CardLayout.CAR_STATUS))
    }

    @Test
    fun 持久化id不变_行内卡仍叫car() {
        // 改了 id 老设备整份布局读不出来（decode 认不出就回退默认布局），
        // 所以第111轮只改 Kotlin 常量名和显示名，**线上的 id 字符串不动**。
        assertEquals("car", CardLayout.RANGE)
        assertEquals("carstatus", CardLayout.CAR_STATUS)
        // 默认布局的编码串与第111轮之前逐字节相同
        assertEquals(
            "car:MEDIUM:1,quick:AUTO:0,music:AUTO:1,climate:AUTO:1,tire:AUTO:1," +
                "trip1:MEDIUM:1,trip2:AUTO:0,status:AUTO:0",
            CardLayout.encode(CardLayout.DEFAULT),
        )
    }

    @Test
    fun 车况卡不进底部卡片行_不参与分宽() {
        // 车况卡是右侧固定卡（388×250，位置固定），混进 ALL 会被 widths() 当行内卡分宽度。
        assertTrue("车况卡不能进 ALL", CardLayout.CAR_STATUS !in CardLayout.ALL)
        assertTrue("车况卡不能被解码进行内布局", CardLayout.decode("carstatus:AUTO:1") == null)
        assertTrue(
            "行内布局里不该出现车况卡",
            CardLayout.normalize(CardLayout.DEFAULT).none { it.id == CardLayout.CAR_STATUS },
        )
    }

    @Test
    fun 车况卡显隐不影响底部卡片行的排布() {
        // 车况卡的显隐走独立开关（Prefs.carStatusCardVisible），
        // 不进 CardLayout ⇒ 行内宽度分配与它无关。
        val withRange = CardLayout.widths(CardLayout.DEFAULT, 1832f, 20f, ::natural)
        assertEquals(listOf("car", "music", "climate", "tire", "trip1"), withRange.map { it.first })
    }
}
