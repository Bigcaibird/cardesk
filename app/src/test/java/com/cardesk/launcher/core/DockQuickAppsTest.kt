package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dock **快捷应用**槽位配置的纯逻辑单测（第五十四轮）。
 *
 * 配置是「槽位 → 包名」的映射，用户随手就能填出各种边角情况：
 * 同一个应用填两格、填超过槽位数、填空、填带空格的值。
 * 这些都必须在**存取层**就被规整掉，画的时候只管按下标取。
 */
class DockQuickAppsTest {

    @Test
    fun parseEmptyGivesNoSlots() {
        assertTrue(DockQuickApps.parse("").isEmpty())
    }

    @Test
    fun parseKeepsSlotOrder() {
        val slots = DockQuickApps.parse("com.b,com.a,com.c")
        assertEquals(listOf("com.b", "com.a", "com.c"), slots)
    }

    @Test
    fun normalizeTrimsBlanks() {
        assertEquals(
            listOf("com.a", "com.b"),
            DockQuickApps.normalize(listOf("  com.a  ", "", "   ", "com.b")),
        )
    }

    @Test
    fun normalizeDropsDuplicates() {
        assertEquals(
            listOf("com.a", "com.b"),
            DockQuickApps.normalize(listOf("com.a", "com.b", "com.a")),
        )
    }

    @Test
    fun normalizeTruncatesToSlotCount() {
        val many = (0 until 9).map { "com.app$it" }
        assertEquals(DockQuickApps.SLOTS, DockQuickApps.normalize(many).size)
        assertEquals("com.app0", DockQuickApps.normalize(many).first())
    }

    @Test
    fun padFillsEmptySlots() {
        val padded = DockQuickApps.pad(listOf("com.a"))
        assertEquals(DockQuickApps.SLOTS, padded.size)
        assertEquals("com.a", padded[0])
        assertEquals("", padded[1])
        assertEquals("", padded[2])
    }

    @Test
    fun withSlotReplacesOneSlot() {
        val next = DockQuickApps.withSlot(listOf("com.a", "com.b"), 1, "com.c")
        assertEquals(listOf("com.a", "com.c"), next)
    }

    @Test
    fun withSlotCanClear() {
        val next = DockQuickApps.withSlot(listOf("com.a", "com.b"), 0, "")
        assertEquals(listOf("com.b"), next)
    }

    @Test
    fun withSlotOutOfRangeIsIgnored() {
        val cur = listOf("com.a")
        assertEquals(DockQuickApps.normalize(cur), DockQuickApps.withSlot(cur, 9, "com.x"))
    }

    @Test
    fun csvRoundTrip() {
        val slots = listOf("com.a", "com.b")
        assertEquals(slots, DockQuickApps.parse(DockQuickApps.toCsv(slots)))
        assertEquals("", DockQuickApps.toCsv(emptyList()))
    }

    /** 存了「同一个应用两格」时，读出来必须已经去重（不然画出来会重复）。 */
    @Test
    fun storeNormalizesOnSave() {
        val fake = FakeStore()
        fake.save(listOf("com.a", "com.a", "com.b"))
        assertEquals(listOf("com.a", "com.b"), fake.load())
    }

    /** 存 / 读往返。 */
    @Test
    fun storeRoundTrip() {
        val fake = FakeStore()
        fake.save(listOf("com.x", "com.y", "com.z"))
        assertEquals(listOf("com.x", "com.y", "com.z"), fake.load())
    }

    private class FakeStore : DockQuickApps.Store {
        private var csv = ""
        override fun load(): List<String> = DockQuickApps.parse(csv)
        override fun save(pkgs: List<String>) {
            csv = DockQuickApps.toCsv(pkgs)
        }
    }
}
