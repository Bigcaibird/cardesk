package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部卡片顺序的纯逻辑单测。
 */
class BottomCardOrderTest {

    @Test
    fun keyMatchesReference() {
        assertEquals("scene_bottom_card_order_v1", BottomCardOrder.KEY)
    }

    @Test
    fun defaultOrderMatchesReferenceCardIds() {
        // 快捷 / 媒体 / 空调 / 胎压 / 续航
        assertEquals(
            listOf(
                BottomCardOrder.QUICK,
                BottomCardOrder.MUSIC,
                BottomCardOrder.CLIMATE,
                BottomCardOrder.TIRE,
                BottomCardOrder.CAR,
            ),
            BottomCardOrder.DEFAULT,
        )
    }

    @Test
    fun encode_joinsWithComma() {
        assertEquals("quick,music,climate,tire,car", BottomCardOrder.encode(BottomCardOrder.DEFAULT))
    }

    @Test
    fun decode_roundTrips() {
        val order = listOf(
            BottomCardOrder.MUSIC,
            BottomCardOrder.CAR,
            BottomCardOrder.TIRE,
            BottomCardOrder.CLIMATE,
            BottomCardOrder.QUICK,
        )
        assertEquals(order, BottomCardOrder.decode(BottomCardOrder.encode(order)))
    }

    @Test
    fun decode_rejectsWrongCardCount() {
        // 个数对不上整份丢弃
        assertNull(BottomCardOrder.decode("quick,music"))
        assertNull(BottomCardOrder.decode(""))
        assertNull(BottomCardOrder.decode(null))
    }

    @Test
    fun decode_rejectsDuplicatesAndUnknownIds() {
        assertNull(BottomCardOrder.decode("quick,quick,climate,tire,car"))
        assertNull(BottomCardOrder.decode("quick,music,climate,tire,other"))
    }

    @Test
    fun normalize_fillsMissingAndDropsUnknown() {
        val normalized = BottomCardOrder.normalize(listOf(BottomCardOrder.TIRE, BottomCardOrder.TIRE, "nope"))
        assertEquals(5, normalized.size)
        assertEquals(BottomCardOrder.DEFAULT.size, normalized.toSet().size)
        assertTrue(normalized.contains(BottomCardOrder.TIRE))
        assertTrue(normalized.containsAll(BottomCardOrder.DEFAULT))
    }

    @Test
    fun move_relocatesCard() {
        val moved = BottomCardOrder.move(BottomCardOrder.DEFAULT, 0, 2)
        assertEquals(
            listOf(
                BottomCardOrder.MUSIC,
                BottomCardOrder.CLIMATE,
                BottomCardOrder.QUICK,
                BottomCardOrder.TIRE,
                BottomCardOrder.CAR,
            ),
            moved,
        )
    }

    @Test
    fun move_clampsTargetIndex() {
        val moved = BottomCardOrder.move(BottomCardOrder.DEFAULT, 0, 99)
        assertEquals(BottomCardOrder.QUICK, moved.last())
        val back = BottomCardOrder.move(BottomCardOrder.DEFAULT, 0, -5)
        assertEquals(BottomCardOrder.QUICK, back.first())
    }

    @Test
    fun indexAt_hitsCardUnderPointer() {
        val bounds = listOf(0f to 100f, 120f to 220f, 240f to 340f)
        assertEquals(0, BottomCardOrder.indexAt(10f, bounds))
        assertEquals(1, BottomCardOrder.indexAt(150f, bounds))
        assertEquals(2, BottomCardOrder.indexAt(339f, bounds))
    }

    @Test
    fun indexAt_fallsBackToNearestCenter() {
        // findBottomCardIndexAt（:943-947）：落在卡片外就取中心最近的一张
        // （距离相同时取靠前的那张，与里 `fAbs < f5` 的严格比较一致）
        val bounds = listOf(0f to 100f, 120f to 220f, 240f to 340f)
        assertEquals(0, BottomCardOrder.indexAt(110f, bounds))
        assertEquals(1, BottomCardOrder.indexAt(130f, bounds))
        assertEquals(2, BottomCardOrder.indexAt(350f, bounds))
    }

    @Test
    fun indexAt_emptyBounds() {
        assertEquals(-1, BottomCardOrder.indexAt(10f, emptyList()))
    }
}
