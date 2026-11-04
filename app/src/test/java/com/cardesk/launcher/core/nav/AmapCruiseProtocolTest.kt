package com.cardesk.launcher.core.nav

import com.cardesk.launcher.core.nav.AmapTrafficLight.DIR_LEFT
import com.cardesk.launcher.core.nav.AmapTrafficLight.DIR_STRAIGHT
import com.cardesk.launcher.core.nav.AmapTrafficLight.DIR_RIGHT
import com.cardesk.launcher.core.nav.AmapTrafficLight.DIR_UTURN
import com.cardesk.launcher.core.nav.AmapTrafficLight.Lamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 高德 9.5 巡航灯态广播（`NAVITOOL_CRUISE_TRAFFIC_LIGHTS`）协议回归。
 *
 * 口径来自 `巡航红绿灯灯态广播_插件接入说明.md`：
 * direction 0掉头/1左/2直/3右；status 1绿/4红（原生枚举，兼容老包 0红/1绿/2绿）；
 * countdown >0 秒 / 0 通行 / -1 无读秒。
 */
class AmapCruiseProtocolTest {

    @Test
    fun 直行绿灯与左转红灯按原生枚举解析() {
        val json = """{"ts":1762234567890,"lights":[
            {"direction":2,"status":1,"countdown":7,"waitNum":0,"showType":1},
            {"direction":1,"status":4,"countdown":12,"waitNum":0,"showType":1}
        ]}"""
        val lights = AmapTrafficLight.cruiseAll(json)
        assertEquals(2, lights.size)
        assertEquals(DIR_LEFT, lights[0].direction)
        assertEquals(Lamp.RED, lights[0].lamp)
        assertEquals(12, lights[0].countdownSeconds)
        assertEquals(DIR_STRAIGHT, lights[1].direction)
        assertEquals(Lamp.GREEN, lights[1].lamp)
        assertEquals(7, lights[1].countdownSeconds)
    }

    @Test
    fun 掉头方向与右转都能识别() {
        val json = """{"lights":[
            {"direction":0,"status":4,"countdown":30},
            {"direction":3,"status":1,"countdown":5}
        ]}"""
        val lights = AmapTrafficLight.cruiseAll(json)
        assertEquals(listOf(DIR_UTURN, DIR_RIGHT), lights.map { it.direction })
        assertEquals(Lamp.RED, lights[0].lamp)
        assertEquals(Lamp.GREEN, lights[1].lamp)
    }

    @Test
    fun 读秒0与负数都不显示数字() {
        val json = """{"lights":[
            {"direction":2,"status":1,"countdown":0},
            {"direction":1,"status":4,"countdown":-1}
        ]}"""
        val lights = AmapTrafficLight.cruiseAll(json)
        // 排序是 左→直：左转 status=4 红、直行 status=1 绿
        assertEquals(DIR_LEFT, lights[0].direction)
        assertEquals(DIR_STRAIGHT, lights[1].direction)
        assertNull(lights[0].countdownSeconds)
        assertNull(lights[1].countdownSeconds)
        // 读秒 0 / -1 只影响数字，不影响灯色
        assertEquals(Lamp.RED, lights[0].lamp)
        assertEquals(Lamp.GREEN, lights[1].lamp)
    }

    @Test
    fun 单灯接口按偏好方向挑灯并按原生枚举判色() {
        val json = """{"lights":[
            {"direction":1,"status":4,"countdown":12},
            {"direction":2,"status":1,"countdown":7}
        ]}"""
        val red = AmapTrafficLight.cruise(json, DIR_LEFT)
        assertEquals(Lamp.RED, red?.lamp)
        assertEquals(12, red?.countdownSeconds)
        val green = AmapTrafficLight.cruise(json, DIR_STRAIGHT)
        assertEquals(Lamp.GREEN, green?.lamp)
        assertEquals(7, green?.countdownSeconds)
        assertNull("多方向但偏好方向没命中时不猜", AmapTrafficLight.cruise(json, DIR_RIGHT))
    }

    @Test
    fun 兼容老YH包的红绿枚举() {
        val json = """{"lights":[{"direction":2,"status":0,"countdown":9}]}"""
        val lights = AmapTrafficLight.cruiseAll(json)
        assertEquals(Lamp.RED, lights[0].lamp)
    }
}
