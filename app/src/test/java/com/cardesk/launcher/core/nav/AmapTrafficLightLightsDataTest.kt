package com.cardesk.launcher.core.nav

import com.cardesk.launcher.core.nav.AmapTrafficLight.Lamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `lightsData` 多方向红绿灯（AMap Companion / 社区修改版高德）解析回归。
 *
 * 这是巡航红绿灯的备选数据通道：60073 帧额外带一个 JSON 数组，
 * 一次给出同一路口所有方向，`clearLights` 表示清空。
 */
class AmapTrafficLightLightsDataTest {

    @Test
    fun 解析多方向并按左直右排序() {
        val json = """[
            {"direction":2,"status":1,"countdown":12},
            {"direction":1,"status":0,"countdown":20},
            {"direction":3,"status":1,"countdown":8}
        ]"""
        val lights = AmapTrafficLight.standardArray(json)
        assertEquals(3, lights.size)
        assertEquals(listOf(1, 2, 3), lights.map { it.direction })
        assertEquals(Lamp.RED, lights[0].lamp)
        assertEquals(20, lights[0].countdownSeconds)
        assertEquals(Lamp.GREEN, lights[1].lamp)
        assertEquals(12, lights[1].countdownSeconds)
        assertEquals(Lamp.GREEN, lights[2].lamp)
    }

    @Test
    fun 兼容标准命名字段() {
        val json = """[{"dir":4,"trafficLightStatus":4,"redLightCountDownSeconds":15}]"""
        val lights = AmapTrafficLight.standardArray(json)
        assertEquals(1, lights.size)
        assertEquals(Lamp.GREEN, lights[0].lamp)
        assertEquals(15, lights[0].countdownSeconds)
    }

    @Test
    fun 同方向重复只留第一条_坏条目跳过() {
        val json = """[
            {"direction":1,"status":0,"countdown":20},
            {"direction":1,"status":1,"countdown":5},
            {"direction":2},
            {"direction":9,"status":1,"countdown":3}
        ]"""
        val lights = AmapTrafficLight.standardArray(json)
        assertEquals(2, lights.size)
        assertEquals(1, lights[0].direction)
        assertEquals(Lamp.RED, lights[0].lamp)
        assertNull("未知方向保留一条但 direction=null", lights[1].direction)
    }

    @Test
    fun 空值坏JSON超长都返回空() {
        assertTrue(AmapTrafficLight.standardArray(null).isEmpty())
        assertTrue(AmapTrafficLight.standardArray("").isEmpty())
        assertTrue(AmapTrafficLight.standardArray("broken").isEmpty())
        assertTrue(AmapTrafficLight.standardArray("[]").isEmpty())
        assertTrue(AmapTrafficLight.standardArray("x".repeat(20000)).isEmpty())
    }
}
