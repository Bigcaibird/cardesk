package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarTopGeometry] 单测（纯逻辑，不依赖 Android）。
 *
 *
 *
 * `
 * moveTo(60,8)
 * cubicTo(86,8, 102,18, 104,42) // 车头
 * lineTo(108,112) // 上侧腰
 * lineTo(106,202) // 下侧腰
 * cubicTo(104,224, 88,232, 60,232) // 车尾
 * `
 *
 * 第四轮把车模从「样条站点」改成这套**分段构造**，
 * 这里锁住关键坐标与控制点，别让以后改参数时把车形改跑偏。
 */
class CarTopGeometryTest {

    private fun approx(expected: Float, actual: Float, tol: Float = 0.6f) {
        assertTrue(
            "期望 $expected ± $tol，实际 $actual",
            kotlin.math.abs(expected - actual) <= tol,
        )
    }

    @Test
    fun `轿车关键点符合预期`() {
        val g = CarTopGeometryFactory.of("03")
        assertEquals(60f, g.nose.x, 0.01f)
        approx(8f, g.nose.y)
        assertEquals(104f, g.shoulder.x, 0.01f)
        approx(42f, g.shoulder.y)
        assertEquals(108f, g.midTop.x, 0.01f)
        approx(112f, g.midTop.y)
        assertEquals(106f, g.midBottom.x, 0.01f)
        approx(202f, g.midBottom.y)
        assertEquals(60f, g.tail.x, 0.01f)
        approx(232f, g.tail.y)
    }

    @Test
    fun `轿车车头贝塞尔控制点符合预期`() {
        val c = CarTopGeometryFactory.of("03").noseCubic
        approx(86f, c.c1.x)
        approx(8f, c.c1.y)
        approx(102f, c.c2.x)
        approx(18f, c.c2.y)
    }

    @Test
    fun `轿车尾巴贝塞尔控制点符合预期`() {
        val c = CarTopGeometryFactory.of("03").tailCubic
        approx(104f, c.c1.x)
        approx(224f, c.c1.y)
        approx(88f, c.c2.x)
        approx(232f, c.c2.y)
    }

    @Test
    fun `所有车型的车模都落在 120x240 设计空间内`() {
        for (id in CarModels.MODEL_IDS) {
            val g = CarTopGeometryFactory.of(id)
            assertTrue("$id 鼻尖应在空间内", g.nose.y >= 0f)
            assertTrue("$id 尾端应在空间内", g.tail.y <= CarModels.DESIGN_H)
            assertTrue("$id 车身半宽不应超出", g.maxHalf <= 60f)
            // 轮子探出车身 6px，最外沿也不能越过设计空间
            assertTrue("$id 轮子最外沿应在空间内", g.maxHalf + 6f <= CarModels.DESIGN_W / 2f)
        }
    }

    @Test
    fun `SUV 比轿车更短更宽`() {
        val suv = CarTopGeometryFactory.of("01")
        val sedan = CarTopGeometryFactory.of("03")
        assertTrue("SUV 应比轿车短", suv.length < sedan.length)
        assertTrue("SUV 应比轿车宽", suv.maxHalf >= sedan.maxHalf)
        assertTrue("SUV 车头应更方", suv.noseReach > sedan.noseReach)
    }

    @Test
    fun `车身半宽从车头到车尾先增后减`() {
        val g = CarTopGeometryFactory.of("03")
        val halfNose = g.halfAt(g.nose.y + 14f)
        val halfMid = g.halfAt(g.midTop.y)
        val halfTail = g.halfAt(g.tail.y - 14f)
        assertTrue("车头处应比腰线窄", halfNose < halfMid)
        assertTrue("车尾处应比腰线窄", halfTail < halfMid)
    }

    @Test
    fun `轮子四个且前后分离`() {
        for (id in CarModels.MODEL_IDS) {
            val g = CarTopGeometryFactory.of(id)
            assertEquals("$id 应该有四个轮子", 4, g.wheels.size)
            val front = g.wheels[0]
            val rear = g.wheels[2]
            assertTrue("$id 前轮应在上半部", front.bottom < rear.top)
            assertTrue("$id 后轮应在车身长度内", rear.bottom <= g.tailY)
            assertTrue("$id 前轮应在前风挡之前", front.top < g.windshield.topLeft.y)
        }
    }

    @Test
    fun `三段玻璃首尾相接且不重叠`() {
        for (id in CarModels.MODEL_IDS) {
            val g = CarTopGeometryFactory.of(id)
            assertTrue("$id 前风挡下沿应不晚于车顶前沿", g.windshield.bottomLeft.y <= g.roof.top)
            assertTrue("$id 车顶后沿应不晚于后风挡上沿", g.roof.bottom <= g.rearGlass.topLeft.y)
            assertTrue("$id 前风挡上沿应比下沿宽", g.windshield.topRight.x > g.windshield.bottomRight.x)
            assertTrue("$id 后风挡下沿应比上沿宽", g.rearGlass.bottomRight.x > g.rearGlass.topRight.x)
        }
    }

    @Test
    fun `玻璃不会超出车身轮廓`() {
        for (id in CarModels.MODEL_IDS) {
            val g = CarTopGeometryFactory.of(id)
            val wsHalf = g.windshield.topRight.x - CarTopGeometryFactory.CENTER_X
            assertTrue("$id 前风挡应在车身内", wsHalf <= g.halfAt(g.windshield.topLeft.y))
            val rgHalf = g.rearGlass.bottomRight.x - CarTopGeometryFactory.CENTER_X
            assertTrue("$id 后风挡应在车身内", rgHalf <= g.halfAt(g.rearGlass.bottomRight.y))
            val roofHalf = g.roof.right - CarTopGeometryFactory.CENTER_X
            assertTrue("$id 车顶应在车身内", roofHalf <= g.halfAt(g.roof.top))
        }
    }

    @Test
    fun `灯组贴在车身轮廓内侧`() {
        for (id in CarModels.MODEL_IDS) {
            val g = CarTopGeometryFactory.of(id)
            assertEquals("$id 应该有两只前灯", 2, g.headLights.size)
            assertEquals("$id 应该有两只尾灯", 2, g.tailLights.size)
            for (lamp in g.headLights + g.tailLights) {
                val y = (lamp.top + lamp.bottom) / 2f
                val outer = kotlin.math.max(
                    CarTopGeometryFactory.CENTER_X - lamp.left,
                    lamp.right - CarTopGeometryFactory.CENTER_X,
                )
                assertTrue(
                    "$id 灯组($outer) 不应超出车身($y)",
                    outer <= g.halfAt(y) + 0.01f,
                )
            }
        }
    }

    @Test
    fun `轮廓采样折线的 y 单调递增`() {
        val g = CarTopGeometryFactory.of("03")
        val profile = g.profile()
        for (i in 1 until profile.size) {
            assertTrue(
                "第 $i 个站点 y 应不小于前一个",
                profile[i].y >= profile[i - 1].y - 0.001f,
            )
        }
    }

    @Test
    fun `贝塞尔端点与设定值一致`() {
        val g = CarTopGeometryFactory.of("05")
        val nose = g.noseCubic
        assertEquals(g.nose.x, nose.p0.x, 0.01f)
        assertEquals(g.nose.y, nose.p0.y, 0.01f)
        assertEquals(g.shoulder.x, nose.p1.x, 0.01f)
        assertEquals(g.shoulder.y, nose.p1.y, 0.01f)
        val tail = g.tailCubic
        assertEquals(g.midBottom.x, tail.p0.x, 0.01f)
        assertEquals(g.tail.x, tail.p1.x, 0.01f)
        assertEquals(g.tail.y, tail.p1.y, 0.01f)
    }
}
