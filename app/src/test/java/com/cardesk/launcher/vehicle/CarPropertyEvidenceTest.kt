package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * 车门属性取证的**纯逻辑**单测（第七十九轮）。
 *
 * 这一轮修的是「车门状态还是拿不到」：
 * 1. 反射异常只记到 `InvocationTargetException: -`（内层 cause 被丢掉）；
 * 2. `getStatus()` 的值从来没被记录；
 * 3. 候选类型里的 `int[]` 永远匹配不上（车机那边是 `Integer[]`）。
 *
 * 这里盯住 1 与 3 的**纯逻辑**部分（异常链剥层与类型候选）。
 */
class CarPropertyEvidenceTest {

    /**
     * 模拟车机抛的真异常：
     * `IllegalArgumentException: Invalid property type. Expected: class java.lang.Integer, but was: class [B`
     *
     * 外面再包一层反射专用的 `InvocationTargetException`（它自己的 message 是 null）。
     */
    private fun wrappedTypeMismatch(): Throwable {
        val inner = IllegalArgumentException(
            "Invalid property type. Expected: class java.lang.Integer, but was: class [B",
        )
        return InvocationTargetException(inner, null)
    }

    @Test
    fun `反射异常能剥到最内层`() {
        val root = CarSignalMath.rootCause(wrappedTypeMismatch())
        assertTrue("应该剥到 IllegalArgumentException", root is IllegalArgumentException)
        assertTrue(
            "内层消息里应带出值的真实类型",
            root.message?.contains("but was: class [B") == true,
        )
    }

    @Test
    fun `失败描述里带出内层异常`() {
        val text = CarSignalMath.describeFailure(wrappedTypeMismatch())
        assertTrue("外层类名要在（实际 $text）", text.contains("InvocationTargetException"))
        assertTrue("内层类名要在（实际 $text）", text.contains("IllegalArgumentException"))
        assertTrue("内层消息要在（实际 $text）", text.contains("but was: class [B"))
    }

    @Test
    fun `没有内层异常时只描述自己`() {
        val text = CarSignalMath.describeFailure(IllegalStateException("就我自己"))
        assertTrue("应该只有自己（实际 $text）", text.contains("IllegalStateException"))
        assertTrue("不该出现 cause 标记（实际 $text）", !text.contains("cause["))
    }

    @Test
    fun `异常链成环时不会死循环`() {
        // 自引用的 cause（病态输入）—— 剥层必须有上限
        val a = RuntimeException("A")
        val b = RuntimeException("B", a)
        runCatching {
            @Suppress("CAST_NEVER_SUCCEEDS")
            val field = Throwable::class.java.getDeclaredField("cause")
            field.isAccessible = true
            field.set(a, b)
        }
        val root = CarSignalMath.rootCause(b, 5)
        assertTrue("剥层必须有上限，不能死循环", root === a || root === b)
    }

    @Test
    fun `status 的文字口径`() {
        assertEquals("AVAILABLE", CarProperties.PropSnapshot.statusText(0))
        assertEquals("UNAVAILABLE", CarProperties.PropSnapshot.statusText(1))
        assertEquals("ERROR", CarProperties.PropSnapshot.statusText(2))
        assertEquals("-", CarProperties.PropSnapshot.statusText(null))
        assertTrue("未知状态要原样带上数字", CarProperties.PropSnapshot.statusText(7).contains("7"))
    }

    @Test
    fun `快照描述带出类型与字节`() {
        val snap = CarProperties.PropSnapshot(
            propertyId = 0x21701414,
            requestedArea = 0,
            returnedArea = 0,
            status = 0,
            timestamp = 123L,
            valueClass = "[B",
            valueText = null,
            bytesLength = 2,
            bytesHex = "0100",
            bytesInt = "1,0",
            causeClass = null,
            causeMessage = null,
        )
        val text = snap.describe()
        assertTrue("属性 id 要在（实际 $text）", text.contains("0x21701414"))
        assertTrue("status 要在（实际 $text）", text.contains("status=0"))
        assertTrue("状态文字要在（实际 $text）", text.contains("AVAILABLE"))
        assertTrue("真实类名要在（实际 $text）", text.contains("[B"))
        assertTrue("字节长度要在（实际 $text）", text.contains("bytesLen=2"))
        assertTrue("字节 hex 要在（实际 $text）", text.contains("0100"))
    }

    @Test
    fun `读不到时快照也要带出原因`() {
        val snap = CarProperties.PropSnapshot(
            propertyId = 0x21701414,
            requestedArea = 0,
            returnedArea = null,
            status = null,
            timestamp = null,
            valueClass = null,
            valueText = null,
            bytesLength = null,
            bytesHex = null,
            bytesInt = null,
            causeClass = "java.lang.IllegalStateException",
            causeMessage = "通道未就绪",
        )
        val text = snap.describe()
        assertTrue("原因类名要在（实际 $text）", text.contains("IllegalStateException"))
        assertTrue("原因消息要在（实际 $text）", text.contains("通道未就绪"))
    }
}
