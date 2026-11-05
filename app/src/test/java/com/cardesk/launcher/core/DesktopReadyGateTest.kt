package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画承载闸门（[DesktopReadyGate]）的判定。
 *
 * 第116轮：第112轮把画中画承载挂到「桌面完全就绪」之后，
 * 那条链**静默断过**（启动页在脱离窗口后 `post`，Runnable 永不执行），
 * 用户看到「导航页画中画不显示应用」，而且没有任何报错。
 *
 * 所以这里把「承载该开始了」这件事的判定单独拆出来钉死：
 * 两条路径（正常 / 兜底）谁先到都只开始一次，
 * 桌面首帧之前绝不开始（第112轮「开机不唤起高德」的前提不能破）。
 */
class DesktopReadyGateTest {

    @Test
    fun 桌面首帧之前绝不该开始承载() {
        val gate = DesktopReadyGate()
        // 连首帧都还没画出来 → 任何时刻都不该开始
        assertFalse(gate.shouldStart(0L))
        assertFalse(gate.shouldStart(999_999L))
        assertFalse(gate.consume(999_999L))
    }

    @Test
    fun 桌面就绪信号一到就立刻开始承载() {
        val gate = DesktopReadyGate()
        gate.markLauncherReady(1000L)
        gate.markDesktopReady()
        assertTrue(gate.shouldStart(1000L))
        assertTrue(gate.consume(1000L))
        assertTrue(gate.isStarted())
        assertTrue(gate.isDesktopReady())
    }

    @Test
    fun 就绪信号没来时首帧之后满窗口兜底启动() {
        val gate = DesktopReadyGate(watchdogMs = 3000L)
        gate.markLauncherReady(1000L)
        // 还没等满窗口 → 不启动
        assertFalse(gate.shouldStart(3999L))
        assertFalse(gate.consume(3999L))
        // 等满窗口 → 兜底启动
        assertTrue(gate.shouldStart(4000L))
        assertTrue(gate.consume(4000L))
        assertTrue(gate.isStarted())
        // 兜底启动后仍然算「就绪链断了」——用来区分是哪条路径兜的底
        assertFalse(gate.isDesktopReady())
    }

    @Test
    fun 承载只开始一次_正常路径与兜底路径只会生效一个() {
        val gate = DesktopReadyGate(watchdogMs = 3000L)
        gate.markLauncherReady(0L)
        // 正常路径先到
        gate.markDesktopReady()
        assertTrue(gate.consume(10L))
        // 兜底路径后到 → 不再重复
        assertFalse(gate.consume(5000L))
        assertTrue(gate.isStarted())
    }

    @Test
    fun 承载只开始一次_兜底路径先到时正常路径也不再重复() {
        val gate = DesktopReadyGate(watchdogMs = 3000L)
        gate.markLauncherReady(0L)
        // 兜底先到（就绪链断了）
        assertTrue(gate.consume(3000L))
        // 后来 desktopReady 才姗姗来迟 → 不再重复承载
        gate.markDesktopReady()
        assertFalse(gate.consume(3100L))
    }

    @Test
    fun 桌面首帧只记第一次_重建不刷新计时起点() {
        val gate = DesktopReadyGate(watchdogMs = 3000L)
        gate.markLauncherReady(1000L)
        // 换肤重建会再次报首帧 → 不能把计时起点往后推，
        // 否则兜底窗口会被无限推迟。
        gate.markLauncherReady(2000L)
        assertEquals(1000L, gate.launcherReadyAt())
        // 计时起点没变 → 3000ms 处就该兜底启动
        assertTrue(gate.shouldStart(4000L))
    }

    @Test
    fun 默认兜底窗口是三秒() {
        // 3 秒 = 桌面首帧之后，桌面自己的重活（天气/车控/导航引导）差不多收尾，
        // 既不跟首帧抢 CPU（第112轮的目的），也不让用户盯着占位图等太久。
        assertEquals(3000L, DesktopReadyGate.DEFAULT_WATCHDOG_MS)
    }
}
