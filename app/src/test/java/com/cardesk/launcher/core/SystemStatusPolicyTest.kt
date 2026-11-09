package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏连接图标显示规则（[SystemStatusPolicy]）单测（第138轮，纯逻辑）。
 *
 * 口径来自参考工程 UNI 的状态栏右端图标槽：
 *
 * 1. 蜂窝：`移动网络已知` **且** `当前没连 WiFi` 才显示；
 * 2. 热点：开启才显示，**与 WiFi 互斥**；
 * 3. WiFi：已连接才显示，等级分档；
 * 4. 蓝牙：`已知` **且** `已开启` 才显示，已连接用强调色。
 *
 * 与 UNI 的一处刻意不同：WiFi 等级**读不到时不当成满格**（保持「读不到就不猜」）。
 */
class SystemStatusPolicyTest {

    private fun slotOf(s: SystemStatusPolicy.Snapshot, kind: SystemStatusPolicy.Kind) =
        SystemStatusPolicy.slots(s).firstOrNull { it.kind == kind }

    private fun kinds(s: SystemStatusPolicy.Snapshot) = SystemStatusPolicy.slots(s).map { it.kind }

    // ------------------------------------------------------------------ 蜂窝

    @Test
    fun `什么都没读到时一个图标都不画`() {
        val slots = SystemStatusPolicy.slots(SystemStatusPolicy.Snapshot())
        assertTrue("读不到就不该画图标", slots.isEmpty())
    }

    @Test
    fun `移动网络已知且没连 WiFi 时显示蜂窝`() {
        val s = SystemStatusPolicy.Snapshot(
            mobileKnown = true,
            mobileEnabled = true,
            mobileLevel = 3,
        )
        val cell = slotOf(s, SystemStatusPolicy.Kind.CELLULAR)
        assertTrue("蜂窝该显示", cell != null)
        assertEquals(3, cell!!.level)
    }

    @Test
    fun `连了 WiFi 就不显示蜂窝（两条链路不能同时亮）`() {
        val s = SystemStatusPolicy.Snapshot(
            wifiKnown = true,
            wifiConnected = true,
            wifiLevel = 4,
            mobileKnown = true,
            mobileEnabled = true,
            mobileLevel = 4,
        )
        assertEquals(null, slotOf(s, SystemStatusPolicy.Kind.CELLULAR))
    }

    @Test
    fun `移动数据关掉时蜂窝是零格`() {
        val s = SystemStatusPolicy.Snapshot(
            mobileKnown = true,
            mobileEnabled = false,
            mobileLevel = 4,
        )
        assertEquals(0, slotOf(s, SystemStatusPolicy.Kind.CELLULAR)!!.level)
    }

    @Test
    fun `蜂窝等级读不到时不猜（保持未知而不是满格）`() {
        val s = SystemStatusPolicy.Snapshot(
            mobileKnown = true,
            mobileEnabled = true,
            mobileLevel = SystemStatusPolicy.LEVEL_UNKNOWN,
        )
        assertEquals(
            SystemStatusPolicy.LEVEL_UNKNOWN,
            slotOf(s, SystemStatusPolicy.Kind.CELLULAR)!!.level,
        )
    }

    @Test
    fun `蜂窝等级超过四格按四格算`() {
        val s = SystemStatusPolicy.Snapshot(mobileKnown = true, mobileLevel = 9)
        assertEquals(4, slotOf(s, SystemStatusPolicy.Kind.CELLULAR)!!.level)
    }

    // ------------------------------------------------------------------ 热点 / WiFi

    @Test
    fun `热点开启时显示热点`() {
        val s = SystemStatusPolicy.Snapshot(hotspotOn = true)
        val hot = slotOf(s, SystemStatusPolicy.Kind.HOTSPOT)
        assertTrue("热点该显示", hot != null)
        assertTrue("热点是强调色", hot!!.emphasized)
    }

    @Test
    fun `热点开启时即使连着 WiFi 也不画 WiFi（互斥）`() {
        val s = SystemStatusPolicy.Snapshot(
            hotspotOn = true,
            wifiKnown = true,
            wifiConnected = true,
            wifiLevel = 4,
        )
        assertEquals(null, slotOf(s, SystemStatusPolicy.Kind.WIFI))
        assertTrue(slotOf(s, SystemStatusPolicy.Kind.HOTSPOT) != null)
    }

    @Test
    fun `热点关掉且连着 WiFi 时显示 WiFi 并带等级`() {
        val s = SystemStatusPolicy.Snapshot(
            hotspotOn = false,
            wifiKnown = true,
            wifiConnected = true,
            wifiLevel = 2,
        )
        assertEquals(2, slotOf(s, SystemStatusPolicy.Kind.WIFI)!!.level)
    }

    @Test
    fun `没连 WiFi 就不显示 WiFi`() {
        val s = SystemStatusPolicy.Snapshot(wifiKnown = true, wifiConnected = false)
        assertEquals(null, slotOf(s, SystemStatusPolicy.Kind.WIFI))
    }

    @Test
    fun `WiFi 等级读不到时保持未知（不当满格）`() {
        val s = SystemStatusPolicy.Snapshot(
            wifiKnown = true,
            wifiConnected = true,
            wifiLevel = SystemStatusPolicy.LEVEL_UNKNOWN,
        )
        assertEquals(
            SystemStatusPolicy.LEVEL_UNKNOWN,
            slotOf(s, SystemStatusPolicy.Kind.WIFI)!!.level,
        )
    }

    // ------------------------------------------------------------------ 蓝牙

    @Test
    fun `蓝牙已知且已开启才显示`() {
        val s = SystemStatusPolicy.Snapshot(bluetoothKnown = true, bluetoothEnabled = true)
        assertTrue(slotOf(s, SystemStatusPolicy.Kind.BLUETOOTH) != null)
    }

    @Test
    fun `蓝牙已知但没开就不显示`() {
        val s = SystemStatusPolicy.Snapshot(bluetoothKnown = true, bluetoothEnabled = false)
        assertEquals(null, slotOf(s, SystemStatusPolicy.Kind.BLUETOOTH))
    }

    @Test
    fun `蓝牙状态读不到就不显示`() {
        val s = SystemStatusPolicy.Snapshot(bluetoothKnown = false, bluetoothEnabled = true)
        assertEquals(null, slotOf(s, SystemStatusPolicy.Kind.BLUETOOTH))
    }

    @Test
    fun `蓝牙已连接用强调色 只开启不用`() {
        val connected = SystemStatusPolicy.Snapshot(
            bluetoothKnown = true, bluetoothEnabled = true, bluetoothConnected = true,
        )
        val idle = SystemStatusPolicy.Snapshot(
            bluetoothKnown = true, bluetoothEnabled = true, bluetoothConnected = false,
        )
        assertTrue(slotOf(connected, SystemStatusPolicy.Kind.BLUETOOTH)!!.emphasized)
        assertFalse(slotOf(idle, SystemStatusPolicy.Kind.BLUETOOTH)!!.emphasized)
    }

    // ------------------------------------------------------------------ 顺序 / 辅助

    @Test
    fun `图标顺序是从右往左`() {
        // 没连 WiFi：蜂窝 → 蓝牙
        val cellular = SystemStatusPolicy.Snapshot(
            mobileKnown = true, mobileLevel = 2,
            bluetoothKnown = true, bluetoothEnabled = true,
        )
        assertEquals(
            listOf(
                SystemStatusPolicy.Kind.CELLULAR,
                SystemStatusPolicy.Kind.BLUETOOTH,
            ),
            kinds(cellular),
        )
        // 连了 WiFi（蜂窝被互斥掉）：WiFi → 蓝牙
        val wifi = SystemStatusPolicy.Snapshot(
            wifiKnown = true, wifiConnected = true, wifiLevel = 3,
            bluetoothKnown = true, bluetoothEnabled = true,
        )
        assertEquals(
            listOf(
                SystemStatusPolicy.Kind.WIFI,
                SystemStatusPolicy.Kind.BLUETOOTH,
            ),
            kinds(wifi),
        )
    }

    @Test
    fun `蜂窝与 WiFi 永远不同时出现`() {
        // 这条是「连了 WiFi 就不画蜂窝」的直接后果，单独钉死：
        // 有人以后把蜂窝条件改回去时，这条会先红。
        val s = SystemStatusPolicy.Snapshot(
            mobileKnown = true, mobileLevel = 4,
            wifiKnown = true, wifiConnected = true, wifiLevel = 4,
        )
        val k = kinds(s)
        assertFalse(k.contains(SystemStatusPolicy.Kind.CELLULAR))
        assertTrue(k.contains(SystemStatusPolicy.Kind.WIFI))
    }

    @Test
    fun `cellularLevel 与 slots 里的蜂窝等级一致`() {
        val s = SystemStatusPolicy.Snapshot(mobileKnown = true, mobileLevel = 2)
        assertEquals(2, SystemStatusPolicy.cellularLevel(s))
        assertEquals(
            SystemStatusPolicy.LEVEL_UNKNOWN,
            SystemStatusPolicy.cellularLevel(SystemStatusPolicy.Snapshot()),
        )
    }

    @Test
    fun `wifiLevel 在热点开启时不算（互斥）`() {
        val s = SystemStatusPolicy.Snapshot(
            hotspotOn = true, wifiKnown = true, wifiConnected = true, wifiLevel = 4,
        )
        assertEquals(SystemStatusPolicy.LEVEL_UNKNOWN, SystemStatusPolicy.wifiLevel(s))
    }

    @Test
    fun `快照指纹能区分不同状态`() {
        val a = SystemStatusPolicy.Snapshot(wifiConnected = true, wifiLevel = 3)
        val b = SystemStatusPolicy.Snapshot(wifiConnected = true, wifiLevel = 3)
        val c = SystemStatusPolicy.Snapshot(wifiConnected = true, wifiLevel = 2)
        assertEquals(a.signature(), b.signature())
        assertFalse(a.signature() == c.signature())
    }
}
