package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车身状态（车门 / 引擎盖 / 后备箱）的**纯口径**单测。
 *
 * 对齐用户给的图（=）
 * `
 * publishBodyState
 *   ├─ 源1 car_signal 反射 getter（0=关、1=开）
 *   ├─ 源2 adaptapi BCM_DOOR_POS（zones {1,4,16,64}）
 *   └─ 源3 CarPropertyManager propertyId 373295872
 *        ↓ allClosed
 *      onDoorState(valid, allClosed)
 * `
 *
 * 这里只测**不碰 Android 的纯函数**：归一化与聚合。
 */
class BodyStateTest {

    @Test
    fun `interpretDoorSignal 只有 0 和 1 有效`() {
        //
        assertEquals("0 = 关", true, BodyState.interpretDoorSignal(0))
        assertEquals("1 = 开", false, BodyState.interpretDoorSignal(1))
        assertNull("其它值读不出来", BodyState.interpretDoorSignal(2))
        assertNull("负数读不出来", BodyState.interpretDoorSignal(-1))
        assertNull("百分比值在这里读不出来", BodyState.interpretDoorSignal(50))
    }

    @Test
    fun `aggregateAllClosed 全关才是关`() {
        // 全部关着
        assertEquals(true, BodyState.aggregateAllClosed(listOf(true, true, true, true)))
        // 有一扇开着 → 不是全关
        assertEquals(false, BodyState.aggregateAllClosed(listOf(true, false, true, true)))
        // 读不到的项跳过（：解释不出来的值不参与聚合）
        assertEquals(true, BodyState.aggregateAllClosed(listOf(true, null, true, null)))
        assertEquals(false, BodyState.aggregateAllClosed(listOf(null, false, null, null)))
        // 一项都读不出来 → null
        assertNull(BodyState.aggregateAllClosed(listOf(null, null, null, null)))
        assertNull(BodyState.aggregateAllClosed(emptyList()))
    }

    @Test
    fun `六项聚合把引擎盖和后备箱也算进去`() {
        //  car_signal 聚合覆盖 4 门 + 盖 + 箱
        val closed = listOf(true, true, true, true, true, true)
        val hoodOpen = listOf(true, true, true, true, false, true)
        assertEquals(6, BodyState.ITEM_COUNT)
        assertEquals(true, BodyState.aggregateAllClosed(closed))
        assertEquals("引擎盖开着就不算全关", false, BodyState.aggregateAllClosed(hoodOpen))
    }

    @Test
    fun `Result 的相等比较认逐门内容`() {
        val a = BodyState.Result(
            valid = true,
            allClosed = false,
            doors = listOf(true, false, true, true),
            hood = true,
            trunk = null,
            source = BodyState.SOURCE_CAR_SIGNAL,
        )
        val b = BodyState.Result(
            valid = true,
            allClosed = false,
            doors = listOf(true, false, true, true),
            hood = true,
            trunk = null,
            source = BodyState.SOURCE_CAR_SIGNAL,
        )
        val c = BodyState.Result(
            valid = true,
            allClosed = false,
            doors = listOf(true, true, true, true),
            hood = true,
            trunk = null,
            source = BodyState.SOURCE_CAR_SIGNAL,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse("逐门内容不同就不相等", a == c)
    }

    @Test
    fun `读不到时 valid 为 false`() {
        val r = BodyState.Result(
            valid = false,
            allClosed = false,
            doors = null,
            hood = null,
            trunk = null,
            source = "无",
        )
        assertFalse(r.valid)
        assertNull(r.doors)
    }

    @Test
    fun `下标常量与项顺序一致`() {
        assertEquals(4, BodyState.DOOR_COUNT)
        assertEquals(4, BodyState.HOOD_INDEX)
        assertEquals(5, BodyState.TRUNK_INDEX)
        // 三条来源的名字要与图上一致，便于对着日志排查
        assertEquals("car_signal", BodyState.SOURCE_CAR_SIGNAL)
        assertEquals("BCM_DOOR_POS", BodyState.SOURCE_ADAPTAPI)
        assertEquals("DOOR_POS", BodyState.SOURCE_PROPERTY)
    }

    // ------------------------------------------------------------ 逐门独立（第四十五轮）

    @Test
    fun `mergeItems 逐项取第一个能解释的来源`() {
        // 源1 只给出左前门；源2 给出左前 + 右前；源3 补上右后
        val merged = BodyState.mergeItems(
            listOf(true, null, null, null),
            listOf(true, false, null, null),
            listOf(null, null, true, true),
        )
        assertEquals(listOf(true, false, true, true), merged)
    }

    @Test
    fun `mergeItems 后面来源不覆盖前面已经读到的`() {
        // 源1 说左前门关着，源3 说开着 —— 取前面的（优先级更高）
        val merged = BodyState.mergeItems(
            listOf(true, null, null, null),
            null,
            listOf(false, null, null, null),
        )
        assertEquals(listOf(true, null, null, null), merged)
    }

    @Test
    fun `mergeItems 全都读不到时返回 null`() {
        assertNull(BodyState.mergeItems(null, null, null))
        assertNull(BodyState.mergeItems(listOf(null, null, null, null)))
        assertNull(BodyState.mergeItems(emptyList()))
    }

    @Test
    fun `mergeItems 长度取各来源里最长的那个`() {
        val merged = BodyState.mergeItems(
            listOf(true),
            listOf(true, false, true, true),
        )
        assertEquals(4, merged!!.size)
        assertEquals(true, merged[0])
        assertEquals(false, merged[1])
    }

    @Test
    fun `MC 事件属性是第四档来源`() {
        assertEquals("MC_DOOR", BodyState.SOURCE_MC)
    }
}
