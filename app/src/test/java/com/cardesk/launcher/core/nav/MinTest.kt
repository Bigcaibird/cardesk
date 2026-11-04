package com.cardesk.launcher.core.nav
import org.junit.Test
class MinTest {
    @Test fun min() {
        val json = """{"lights":[{"direction":2,"status":1,"countdown":7}]}"""
        println("len=" + json.length)
        org.junit.Assert.assertEquals(1, AmapTrafficLight.cruiseAll(json).size)
        org.junit.Assert.assertNotNull(AmapTrafficLight.cruise(json))
    }
}
