package com.cardesk.launcher.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一百三十五轮：「开机自动切换驾驶模式」的判定与写入（[DriveModeSwitch]）单测。
 *
 * ## 验的是什么
 *
 * 1. **目标合法性**：只有能归一成真实模式的才算数；
 *    厂商的读失败哨兵（253 / 254 / 255）**不是模式号**，不能当目标写下去；
 * 2. **「已经是目标」**：已经在目标模式上就别再写（白跑一次跨进程调用）；
 *    但**读不到**当前模式 ≠ 已经是目标（读失败的机器必须还能切）；
 * 3. **冷却**：一次开机里桌面会被拉起很多次，没有冷却就会对着车机连写几十次；
 * 4. **写入走的是「选择型」通道**（两参优先 + zone 降级），
 *    不是空调那条「三参优先」的路。
 *
 * 厂商侧对照：
 *
 * | 东西 | 位置 | 值 |
 * | --- | --- | --- |
 * | 功能号 | `IDriveMode.DM_FUNC_DRIVE_MODE_SELECT` | `570491136`（`0x22010100`） |
 * | 读 zone | 文档 `16-hud-dim-adas.md` §4.3 | 2 |
 * | 对标 App 写入降级 | `DriveModeController.writeDriveModeAdaptApi` | 两参 → zone 2 → zone 1 |
 */
class DriveModeSwitchTest {

    /** 假通道：记录写入、降级链与读值。 */
    private class FakeGateway : VehicleGateway {
        override val id: String = "adaptapi"
        override val displayName: String = "假通道"
        override var on360State: ((Boolean) -> Unit)? = null

        /** 走 [VehicleGateway.writeSelectFunction] 的调用（功能号 → 值）。 */
        val selectWrites = mutableListOf<Pair<Int, Int>>()

        /** 走 [VehicleGateway.writeFunction]（两参兜底）的调用。 */
        val plainWrites = mutableListOf<Pair<Int, Int>>()

        /** 一参 `getFunctionValue(func)` 的读值（null = 读不到）。 */
        var raw: Int? = null

        /** 带 zone 的读值（null = 读不到）。 */
        var rawByZone: Int? = null

        /** 写是否被固件接受。 */
        var acceptWrites: Boolean = true

        /** 选择型写入是否被接受（false = 模拟「固件不提供两参重载」）。 */
        var acceptSelect: Boolean = true

        override fun connect(onResult: (Boolean) -> Unit) = onResult(true)
        override fun disconnect() = Unit
        override fun isConnected(): Boolean = true
        override fun setHvac(func: Int, zone: Int, value: Int): Boolean = false
        override fun setTemp(zone: Int, celsius: Float): Boolean = false
        override fun readAll(): Map<Int, Int> = emptyMap()
        override fun registerListener(listener: (func: Int, zone: Int, value: Int) -> Unit) = Unit
        override fun unregisterListener(listener: (func: Int, zone: Int, value: Int) -> Unit) = Unit

        override fun writeFunction(func: Int, value: Int): Boolean {
            plainWrites.add(func to value)
            return acceptWrites
        }

        override fun writeSelectFunction(func: Int, value: Int): Boolean {
            selectWrites.add(func to value)
            return acceptSelect
        }

        override fun readFunction(func: Int): Int? = raw

        override fun readValue(func: Int, zone: Int): Int? = rawByZone
    }

    // ------------------------------------------------------------ 功能号

    @Test
    fun `功能号是 570491136 也就是 0x22010100`() {
        assertEquals(570491136, DriveMode.FUNCTION_DRIVE_MODE_SELECT)
        assertEquals(0x22010100, DriveMode.FUNCTION_DRIVE_MODE_SELECT)
    }

    @Test
    fun `对标 App 的 zone 降级顺序是 2 再 1`() {
        // 照抄对标 App DriveModeController.writeDriveModeAdaptApi：
        // trySetDriveModeWithZone(f, 2, mode) → trySetDriveModeWithZone(f, 1, mode)
        assertEquals(listOf(2, 1), DriveModeSwitch.FALLBACK_ZONES.toList())
    }

    // ------------------------------------------------------------ 目标合法性

    @Test
    fun `本车支持的三个档位都是合法目标`() {
        assertTrue(DriveModeSwitch.isValidTarget(DriveMode.SELECTION_ECO))
        assertTrue(DriveModeSwitch.isValidTarget(DriveMode.SELECTION_COMFORT))
        assertTrue(DriveModeSwitch.isValidTarget(DriveMode.SELECTION_SPORT))
    }

    @Test
    fun `HEV 的标准档位也是合法目标`() {
        // 570491153：厂商给了两个名字（COMFORT_HEV / NORMAL），是真实档位。
        // 面板只列 3 档不代表持久化里不能留着它 —— 不该因为「不在面板列表里」就拒绝执行。
        assertTrue(DriveModeSwitch.isValidTarget(DriveMode.SELECTION_COMFORT_HEV))
    }

    @Test
    fun `读失败哨兵和未知值都不是合法目标`() {
        // 253 / 254 / 255 是厂商的「读不到」哨兵，**不是模式号**
        assertFalse(DriveModeSwitch.isValidTarget(DriveMode.READ_ERROR))
        assertFalse(DriveModeSwitch.isValidTarget(DriveMode.READ_NONE))
        assertFalse(DriveModeSwitch.isValidTarget(DriveMode.READ_UNKNOWN))
        assertFalse(DriveModeSwitch.isValidTarget(DriveMode.SELECTION_NONE))
        assertFalse(DriveModeSwitch.isValidTarget(0))
        assertFalse(DriveModeSwitch.isValidTarget(-1))
        assertFalse(DriveModeSwitch.isValidTarget(12345))
    }

    // ------------------------------------------------------------ 「已经是目标」

    @Test
    fun `当前就是目标时不再写`() {
        assertTrue(DriveModeSwitch.alreadyAt(DriveMode.SELECTION_SPORT, DriveMode.SELECTION_SPORT))
        assertTrue(DriveModeSwitch.alreadyAt(DriveMode.SELECTION_COMFORT, DriveMode.SELECTION_COMFORT))
    }

    @Test
    fun `当前不是目标时要写`() {
        assertFalse(DriveModeSwitch.alreadyAt(DriveMode.SELECTION_COMFORT, DriveMode.SELECTION_SPORT))
        assertFalse(DriveModeSwitch.alreadyAt(DriveMode.SELECTION_NONE, DriveMode.SELECTION_SPORT))
    }

    @Test
    fun `读不到当前模式不等于已经是目标`() {
        // 关键区分：读失败（null / 哨兵）时**必须**还能切，
        // 否则「这台车恰好读不到模式」的机器就永远切不了。
        assertFalse(DriveModeSwitch.alreadyAt(null, DriveMode.SELECTION_SPORT))
        assertFalse(DriveModeSwitch.alreadyAt(DriveMode.READ_ERROR, DriveMode.SELECTION_SPORT))
        assertFalse(DriveModeSwitch.alreadyAt(DriveMode.READ_NONE, DriveMode.SELECTION_SPORT))
        assertFalse(DriveModeSwitch.alreadyAt(DriveMode.READ_UNKNOWN, DriveMode.SELECTION_SPORT))
    }

    // ------------------------------------------------------------ 冷却

    @Test
    fun `从没执行过时冷却算已过`() {
        assertTrue(DriveModeSwitch.cooldownElapsed(0L, 1_000_000L))
    }

    @Test
    fun `冷却窗口没过时不许再写`() {
        val now = 10_000_000L
        assertFalse(DriveModeSwitch.cooldownElapsed(now - 1_000L, now))
        assertFalse(DriveModeSwitch.cooldownElapsed(now - DriveModeSwitch.COOLDOWN_MS + 1, now))
        assertTrue(DriveModeSwitch.cooldownElapsed(now - DriveModeSwitch.COOLDOWN_MS, now))
        assertTrue(DriveModeSwitch.cooldownElapsed(now - DriveModeSwitch.COOLDOWN_MS - 1, now))
    }

    // ------------------------------------------------------------ 总判定

    @Test
    fun `开关关着不执行`() {
        assertFalse(
            DriveModeSwitch.shouldRun(
                enabled = false,
                target = DriveMode.SELECTION_SPORT,
                current = DriveMode.SELECTION_COMFORT,
                lastAt = 0L,
                now = 1_000_000L,
            )
        )
    }

    @Test
    fun `目标非法不执行`() {
        assertFalse(
            DriveModeSwitch.shouldRun(
                enabled = true,
                target = DriveMode.READ_ERROR,
                current = DriveMode.SELECTION_COMFORT,
                lastAt = 0L,
                now = 1_000_000L,
            )
        )
    }

    @Test
    fun `已经是目标不执行`() {
        assertFalse(
            DriveModeSwitch.shouldRun(
                enabled = true,
                target = DriveMode.SELECTION_SPORT,
                current = DriveMode.SELECTION_SPORT,
                lastAt = 0L,
                now = 1_000_000L,
            )
        )
    }

    @Test
    fun `冷却中不执行`() {
        val now = 10_000_000L
        assertFalse(
            DriveModeSwitch.shouldRun(
                enabled = true,
                target = DriveMode.SELECTION_SPORT,
                current = DriveMode.SELECTION_COMFORT,
                lastAt = now - 1_000L,
                now = now,
            )
        )
    }

    @Test
    fun `条件都满足时执行`() {
        assertTrue(
            DriveModeSwitch.shouldRun(
                enabled = true,
                target = DriveMode.SELECTION_SPORT,
                current = DriveMode.SELECTION_COMFORT,
                lastAt = 0L,
                now = 1_000_000L,
            )
        )
    }

    @Test
    fun `读不到当前模式时照样执行`() {
        assertTrue(
            DriveModeSwitch.shouldRun(
                enabled = true,
                target = DriveMode.SELECTION_SPORT,
                current = null,
                lastAt = 0L,
                now = 1_000_000L,
            )
        )
    }

    // ------------------------------------------------------------ 不执行的原因（排障用）

    @Test
    fun `skipReason 把拦下来的原因说清楚`() {
        val now = 10_000_000L
        assertEquals("开关关闭", DriveModeSwitch.skipReason(false, DriveMode.SELECTION_SPORT, null, 0L, now))
        assertTrue(
            DriveModeSwitch.skipReason(true, 12345, null, 0L, now).startsWith("目标模式非法")
        )
        assertEquals(
            "已经是目标模式",
            DriveModeSwitch.skipReason(true, DriveMode.SELECTION_SPORT, DriveMode.SELECTION_SPORT, 0L, now),
        )
        assertTrue(
            DriveModeSwitch.skipReason(true, DriveMode.SELECTION_SPORT, null, now - 1_000L, now).startsWith("冷却中")
        )
        assertEquals("", DriveModeSwitch.skipReason(true, DriveMode.SELECTION_SPORT, null, 0L, now))
    }

    // ------------------------------------------------------------ 写

    @Test
    fun `写驾驶模式走的是选择型通道`() {
        val gw = FakeGateway()
        assertTrue(DriveModeSwitch.write(gw, DriveMode.SELECTION_SPORT))
        assertEquals(listOf(DriveMode.FUNCTION_DRIVE_MODE_SELECT to DriveMode.SELECTION_SPORT), gw.selectWrites)
        // 不能退回空调那条「三参优先」的路
        assertTrue(gw.plainWrites.isEmpty())
    }

    @Test
    fun `固件拒收时写入返回 false`() {
        val gw = FakeGateway()
        gw.acceptSelect = false
        assertFalse(DriveModeSwitch.write(gw, DriveMode.SELECTION_SPORT))
    }

    @Test
    fun `目标非法时根本不写`() {
        val gw = FakeGateway()
        assertFalse(DriveModeSwitch.write(gw, DriveMode.READ_ERROR))
        assertTrue(gw.selectWrites.isEmpty())
    }

    // ------------------------------------------------------------ 读

    @Test
    fun `读当前模式优先用一参`() {
        val gw = FakeGateway()
        gw.raw = DriveMode.SELECTION_COMFORT
        gw.rawByZone = DriveMode.SELECTION_ECO
        assertEquals(DriveMode.SELECTION_COMFORT, DriveModeSwitch.readCurrent(gw))
    }

    @Test
    fun `一参读不到时退到带 zone 的读`() {
        val gw = FakeGateway()
        gw.raw = null
        gw.rawByZone = DriveMode.SELECTION_ECO
        assertEquals(DriveMode.SELECTION_ECO, DriveModeSwitch.readCurrent(gw))
    }

    @Test
    fun `读不到返回 null`() {
        val gw = FakeGateway()
        gw.raw = null
        gw.rawByZone = null
        assertNull(DriveModeSwitch.readCurrent(gw))
    }
}
