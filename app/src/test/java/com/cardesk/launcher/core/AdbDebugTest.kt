package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADB / 无线调试子系统的**纯逻辑**单测（第五十五轮）。
 *
 * 这一层没有 Android 依赖，所以能直接在 JVM 上跑完整条链路：
 * 「开调试开关 → 切无线端口 → 切 USB 功能 → 恢复全部设置」，
 * 外加每一步失败时的**回滚**行为。
 *
 * 重点覆盖三类：
 * 1. **正常路径**：三段都按预期生效；
 * 2. **失败回滚**：写不进去 / 回读对不上时，原值必须被还原、快照必须被清掉；
 * 3. **安全出口**：「恢复全部设置」能把三段都退回去，某段失败不影响其它段。
 */
class AdbDebugTest {

    // ------------------------------------------------------------- 前置校验

    @Test
    fun supportRejectsOldSdk() {
        val result = AdbSupport.check(27, 1000, true, true, allGranted(), false)
        assertFalse(result.ok)
        assertTrue(result.reason.contains("Android 9"))
    }

    @Test
    fun supportRejectsNonSystemUid() {
        val result = AdbSupport.check(28, 10123, true, true, allGranted(), false)
        assertFalse(result.ok)
        assertTrue(result.reason.contains("系统共享身份"))
    }

    @Test
    fun supportRejectsBadSignature() {
        val result = AdbSupport.check(28, 1000, true, false, allGranted(), false)
        assertFalse(result.ok)
        assertTrue(result.reason.contains("签名"))
    }

    @Test
    fun supportReportsFirstMissingPermission() {
        val permissions = allGranted().toMutableMap()
        permissions[AdbDebug.PERM_MANAGE_USB] = false
        val result = AdbSupport.check(28, 1000, true, true, permissions, false)
        assertFalse(result.ok)
        assertTrue(result.reason.contains(AdbDebug.PERM_MANAGE_USB))
    }

    @Test
    fun supportRejectsDevicePolicyRestriction() {
        val result = AdbSupport.check(28, 1000, true, true, allGranted(), true)
        assertFalse(result.ok)
        assertTrue(result.reason.contains("设备策略"))
    }

    @Test
    fun supportPassesWhenEverythingMatches() {
        val result = AdbSupport.check(28, 1000, true, true, allGranted(), false)
        assertTrue(result.ok)
        assertEquals("固件与权限符合", result.reason)
    }

    // ------------------------------------------------------------- 调试开关

    @Test
    fun enableDebugWritesThreeThingsAndVerifies() {
        val backend = FakeBackend()
        val store = FakeStore()
        val outcome = controller(backend, store).enableDebug()

        assertTrue(outcome.ok)
        assertEquals("1", backend.settings[AdbDebug.SETTING_DEV])
        assertEquals("1", backend.settings[AdbDebug.SETTING_ADB])
        assertEquals(AdbDebug.COMPONENT_STATE_ENABLED, backend.componentState)
        assertNotNull("应当留下原值快照", store.debug)
    }

    @Test
    fun enableDebugRollsBackWhenSettingWriteFails() {
        val backend = FakeBackend()
        backend.failSettings += AdbDebug.SETTING_ADB
        val store = FakeStore()

        val outcome = controller(backend, store).enableDebug()

        assertFalse(outcome.ok)
        // 写 adb_enabled 失败 → 回滚 dev → 快照清掉
        assertNull(backend.settings[AdbDebug.SETTING_DEV])
        assertNull(backend.settings[AdbDebug.SETTING_ADB])
        assertEquals(AdbDebug.COMPONENT_STATE_DEFAULT, backend.componentState)
        assertNull("失败的快照不能留下来", store.debug)
    }

    @Test
    fun enableDebugRollsBackWhenReadBackMismatches() {
        val backend = FakeBackend()
        // 写进去但回读还是旧值（模拟系统没收下）
        backend.ignoreReadBack += AdbDebug.SETTING_ADB
        val store = FakeStore()

        val outcome = controller(backend, store).enableDebug()

        assertFalse(outcome.ok)
        assertTrue(outcome.detail.contains("回读不一致"))
        assertNull(store.debug)
    }

    @Test
    fun restoreDebugWithoutSnapshotChangesNothing() {
        val backend = FakeBackend()
        val store = FakeStore()
        val outcome = controller(backend, store).restoreDebug()

        assertTrue(outcome.ok)
        assertTrue(outcome.title.contains("未修改"))
    }

    @Test
    fun restoreDebugPutsBackOriginalValues() {
        val backend = FakeBackend()
        backend.settings[AdbDebug.SETTING_DEV] = "0"
        backend.settings[AdbDebug.SETTING_ADB] = "0"
        backend.componentState = AdbDebug.COMPONENT_STATE_DISABLED
        val store = FakeStore()
        val controller = controller(backend, store)

        controller.enableDebug()
        assertEquals("1", backend.settings[AdbDebug.SETTING_ADB])

        val outcome = controller.restoreDebug()

        assertTrue(outcome.ok)
        assertEquals("0", backend.settings[AdbDebug.SETTING_DEV])
        assertEquals("0", backend.settings[AdbDebug.SETTING_ADB])
        assertEquals(AdbDebug.COMPONENT_STATE_DISABLED, backend.componentState)
        assertNull(store.debug)
    }

    @Test
    fun enableDebugKeepsTheVeryFirstSnapshot() {
        val backend = FakeBackend()
        // 第一次开启之前系统里的原值
        backend.settings[AdbDebug.SETTING_ADB] = "0"
        val store = FakeStore()
        val controller = controller(backend, store)

        controller.enableDebug()
        // 用户在系统里手动改了开关，再开一次：原值快照**不能**被覆盖
        backend.settings[AdbDebug.SETTING_ADB] = "1"
        controller.enableDebug()

        assertEquals("0", store.debug?.adb)
    }

    // ------------------------------------------------------------- 无线端口

    @Test
    fun enableWirelessWritesServicePortAndRestartsAdbd() {
        val backend = FakeBackend()
        val store = FakeStore()

        val outcome = controller(backend, store).enableWireless()

        assertTrue(outcome.ok)
        assertEquals(AdbDebug.WIRELESS_PORT, backend.properties[AdbDebug.PROP_SERVICE_PORT])
        assertTrue("应当重启 adbd", backend.restarted)
        assertNotNull(store.port)
    }

    @Test
    fun enableWirelessFallsBackToPersistPort() {
        val backend = FakeBackend()
        backend.failProperties += AdbDebug.PROP_SERVICE_PORT
        val store = FakeStore()

        val outcome = controller(backend, store).enableWireless()

        assertTrue(outcome.ok)
        assertEquals(AdbDebug.WIRELESS_PORT, backend.properties[AdbDebug.PROP_PERSIST_PORT])
    }

    @Test
    fun enableWirelessDoesNotFallBackWhenServiceHadAValue() {
        val backend = FakeBackend()
        backend.properties[AdbDebug.PROP_SERVICE_PORT] = "6666"
        backend.failProperties += AdbDebug.PROP_SERVICE_PORT
        val store = FakeStore()

        val outcome = controller(backend, store).enableWireless()

        assertFalse(outcome.ok)
        assertNull("原值不是空就不许改写 persist", backend.properties[AdbDebug.PROP_PERSIST_PORT])
    }

    @Test
    fun enableWirelessRollsBackWhenReadBackMismatches() {
        val backend = FakeBackend()
        backend.ignoreReadBack += AdbDebug.PROP_SERVICE_PORT
        val store = FakeStore()

        val outcome = controller(backend, store).enableWireless()

        assertFalse(outcome.ok)
        assertTrue(outcome.detail.contains("回读不一致"))
        assertNull("失败的快照不能留下来", store.port)
    }

    @Test
    fun restoreWirelessPutsBackOriginalPort() {
        val backend = FakeBackend()
        backend.properties[AdbDebug.PROP_SERVICE_PORT] = "6666"
        val store = FakeStore()
        val controller = controller(backend, store)

        controller.enableWireless()
        assertEquals(AdbDebug.WIRELESS_PORT, backend.properties[AdbDebug.PROP_SERVICE_PORT])

        val outcome = controller.restoreWireless()

        assertTrue(outcome.ok)
        assertEquals("6666", backend.properties[AdbDebug.PROP_SERVICE_PORT])
        assertNull(store.port)
    }

    @Test
    fun restoreWirelessWithoutSnapshotChangesNothing() {
        val outcome = controller(FakeBackend(), FakeStore()).restoreWireless()
        assertTrue(outcome.ok)
        assertTrue(outcome.title.contains("未修改"))
    }

    // ------------------------------------------------------------- USB 接口

    @Test
    fun enableUsbSavesOriginalFunctionsAndRequestsAdb() {
        val backend = FakeBackend()
        backend.usbFunctions = 1L
        val store = FakeStore()

        val outcome = controller(backend, store).enableUsb()

        assertTrue(outcome.ok)
        assertEquals(4L, backend.usbFunctions)
        assertEquals(1L, store.usb?.functions)
    }

    @Test
    fun restoreUsbPutsBackOriginalFunctions() {
        val backend = FakeBackend()
        backend.usbFunctions = 1L
        val store = FakeStore()
        val controller = controller(backend, store)

        controller.enableUsb()
        val outcome = controller.restoreUsb()

        assertTrue(outcome.ok)
        assertEquals(1L, backend.usbFunctions)
        assertNull(store.usb)
    }

    @Test
    fun restoreUsbKeepsRecordWhenReadBackNeverMatches() {
        val backend = FakeBackend()
        backend.usbFunctions = 1L
        val store = FakeStore()
        val controller = controller(backend, store)

        controller.enableUsb()
        // 恢复阶段回读一直对不上原值
        backend.ignoreUsbReadBack = true
        val outcome = controller.restoreUsb()

        assertFalse(outcome.ok)
        assertNotNull("回读不到原值时必须保留恢复记录", store.usb)
    }

    // ------------------------------------------------------------- adbd 重启

    @Test
    fun restartAdbdUsesCtlRestartFirst() {
        val backend = FakeBackend()
        controller(backend, FakeStore()).restartAdbd()

        assertEquals("adbd", backend.properties[AdbDebug.PROP_CTL_RESTART])
        assertFalse("不该动 USB 功能位", backend.usbTouched)
    }

    @Test
    fun restartAdbdFallsBackToUsbToggleWhenAllowed() {
        val backend = FakeBackend()
        backend.failProperties += AdbDebug.PROP_CTL_RESTART
        backend.canManageUsb = true
        backend.usbFunctions = 4L

        controller(backend, FakeStore()).restartAdbd()

        assertTrue("应当切过 USB 功能位", backend.usbTouched)
        assertEquals(4L, backend.usbFunctions)
    }

    @Test
    fun restartAdbdFailsWithoutManageUsbPermission() {
        val backend = FakeBackend()
        backend.failProperties += AdbDebug.PROP_CTL_RESTART
        backend.canManageUsb = false

        var failed = false
        try {
            controller(backend, FakeStore()).restartAdbd()
        } catch (t: Throwable) {
            failed = true
        }
        assertTrue("没有 MANAGE_USB 就不该偷偷切 USB", failed)
        assertFalse(backend.usbTouched)
    }

    // ------------------------------------------------------------- 恢复全部

    @Test
    fun restoreAllRestoresThreeSegments() {
        val backend = FakeBackend()
        backend.settings[AdbDebug.SETTING_ADB] = "0"
        backend.properties[AdbDebug.PROP_SERVICE_PORT] = "6666"
        backend.usbFunctions = 1L
        val store = FakeStore()
        val controller = controller(backend, store)

        controller.enableDebug()
        controller.enableWireless()
        controller.enableUsb()

        val outcome = controller.restoreAll()

        assertTrue(outcome.ok)
        assertEquals("0", backend.settings[AdbDebug.SETTING_ADB])
        assertEquals("6666", backend.properties[AdbDebug.PROP_SERVICE_PORT])
        assertEquals(1L, backend.usbFunctions)
        assertNull(store.debug)
        assertNull(store.port)
        assertNull(store.usb)
    }

    @Test
    fun restoreAllReportsEachSegmentAndKeepsGoingOnFailure() {
        val backend = FakeBackend()
        backend.usbFunctions = 1L
        val store = FakeStore()
        val controller = controller(backend, store)
        controller.enableDebug()
        controller.enableUsb()
        // 让 USB 恢复回读一直对不上
        backend.ignoreUsbReadBack = true

        val outcome = controller.restoreAll()

        assertFalse(outcome.ok)
        assertTrue(outcome.detail.contains("无线端口"))
        assertTrue(outcome.detail.contains("USB 接口"))
        assertTrue(outcome.detail.contains("调试开关"))
        // 调试开关那一段不受 USB 失败影响，照样要退回去
        assertNull(store.debug)
    }

    // ------------------------------------------------------------- 会话级操作

    @Test
    fun enableWirelessSessionOpensDebugFirst() {
        val backend = FakeBackend()
        val store = FakeStore()

        val outcome = controller(backend, store).enableWirelessSession()

        assertTrue(outcome.ok)
        assertEquals("1", backend.settings[AdbDebug.SETTING_ADB])
        assertEquals(AdbDebug.WIRELESS_PORT, backend.properties[AdbDebug.PROP_SERVICE_PORT])
    }

    @Test
    fun enableUsbSessionStopsWhenDebugFails() {
        val backend = FakeBackend()
        backend.failSettings += AdbDebug.SETTING_ADB
        val store = FakeStore()

        val outcome = controller(backend, store).enableUsbSession()

        assertFalse(outcome.ok)
        assertFalse("调试开关没开成就不该去动 USB", backend.usbTouched)
    }

    // ------------------------------------------------------------- 诊断

    @Test
    fun settingLabelCoversAllCases() {
        assertEquals("开启", AdbDiagnostics.settingLabel("1"))
        assertEquals("关闭", AdbDiagnostics.settingLabel("0"))
        assertEquals("未设置", AdbDiagnostics.settingLabel(null))
        assertEquals("未设置", AdbDiagnostics.settingLabel(""))
    }

    @Test
    fun componentLabelCoversAllCases() {
        assertEquals("启用", AdbDiagnostics.componentLabel(AdbDebug.COMPONENT_STATE_ENABLED))
        assertEquals("禁用", AdbDiagnostics.componentLabel(AdbDebug.COMPONENT_STATE_DISABLED))
        assertEquals("默认", AdbDiagnostics.componentLabel(AdbDebug.COMPONENT_STATE_DEFAULT))
    }

    @Test
    fun usbFunctionsTextNamesKnownBits() {
        assertEquals("0（无）", AdbDiagnostics.usbFunctionsText(0L))
        assertEquals("4（ADB）", AdbDiagnostics.usbFunctionsText(4L, mapOf(4L to "ADB")))
        assertEquals("5（MTP + ADB）", AdbDiagnostics.usbFunctionsText(5L, mapOf(1L to "MTP", 4L to "ADB")))
        assertEquals("3", AdbDiagnostics.usbFunctionsText(3L))
    }

    @Test
    fun describeChainsCauses() {
        val root = IllegalStateException("外层")
        root.initCause(IllegalArgumentException("内层"))
        val text = AdbDiagnostics.describe(root)
        assertTrue(text.contains("外层"))
        assertTrue(text.contains("内层"))
    }

    @Test
    fun formatPropertiesShowsEmptyAsPlaceholder() {
        val text = AdbDiagnostics.formatProperties(listOf("a" to "1", "b" to ""))
        assertTrue(text.contains("a = 1"))
        assertTrue(text.contains("b = <空>"))
    }

    @Test
    fun filterSettingsCrashFindsTheSettingsProcessCrash() {
        val log = """
            E/AndroidRuntime( 1234): FATAL EXCEPTION: main
            E/AndroidRuntime( 1234): Process: com.android.settings, PID: 1234
            E/AndroidRuntime( 1234): java.lang.SecurityException: boom
            E/AndroidRuntime( 5678): Process: com.other.app, PID: 5678
            E/AndroidRuntime( 5678): java.lang.RuntimeException: other
        """.trimIndent()
        val text = AdbDiagnostics.filterSettingsCrash(log)
        assertTrue(text.contains("SecurityException: boom"))
        assertFalse("别的进程那次不该混进来", text.contains("other"))
    }

    @Test
    fun filterSettingsCrashSaysSoWhenNothingFound() {
        val text = AdbDiagnostics.filterSettingsCrash("nothing here")
        assertTrue(text.contains("没有找到"))
    }

    @Test
    fun stateWirelessPortPrefersServiceOverPersist() {
        val state = AdbState(
            uid = 1000,
            platformSigned = true,
            settingsSigned = true,
            devSetting = "1",
            adbSetting = "1",
            componentState = 1,
            componentEnabled = true,
            adbdService = "running",
            servicePort = "5555",
            persistPort = "5555",
            usbFunctions = 4L,
            portOpen = true,
            localIp = "192.168.1.112",
            hasDebugSnapshot = true,
            hasUsbSnapshot = false,
            hasPortSnapshot = true,
        )
        assertEquals("5555", state.wirelessPort)
        assertEquals("调试 有 / USB 无 / 无线 有", state.restoreSummary)
    }

    // ------------------------------------------------------------- 假实现

    /** 测试里不真的睡：把休眠换成空实现，轮询逻辑照跑。 */
    private fun controller(backend: FakeBackend, store: FakeStore) = AdbController(backend, store) { }

    private fun allGranted(): Map<String, Boolean> =
        AdbDebug.REQUIRED_PERMISSIONS.associateWith { true }

    private class FakeBackend : AdbBackend {

        val settings = HashMap<String, String?>()
        val properties = HashMap<String, String>()
        val failSettings = HashSet<String>()
        val failProperties = HashSet<String>()
        val ignoreReadBack = HashSet<String>()

        var componentState = AdbDebug.COMPONENT_STATE_DEFAULT
        var usbFunctions = 0L
        var usbAdbFunction = 4L
        var portOpen = false
        var canManageUsb = false
        var ignoreUsbReadBack = false
        var usbTouched = false
        var restarted = false

        override fun readSetting(key: String): String? =
            if (key in ignoreReadBack) "0" else settings[key]

        override fun writeSetting(key: String, value: String?): Boolean {
            if (key in failSettings) return false
            settings[key] = value
            return true
        }

        override fun componentState(): Int = componentState

        override fun writeComponentState(state: Int) {
            componentState = state
        }

        override fun readProperty(key: String): String =
            if (key in ignoreReadBack) "" else properties[key].orEmpty()

        override fun writeProperty(key: String, value: String) {
            if (key in failProperties) throw AdbException("写属性失败：$key")
            properties[key] = value
            if (key == AdbDebug.PROP_CTL_RESTART) restarted = true
        }

        override fun usbFunctions(): Long =
            if (ignoreUsbReadBack) 0L else usbFunctions

        override fun usbAdbFunction(): Long = usbAdbFunction

        override fun requestUsb(functions: Long) {
            usbTouched = true
            usbFunctions = functions
        }

        override fun portOpen(): Boolean = portOpen

        override fun canManageUsb(): Boolean = canManageUsb
    }

    private class FakeStore : AdbSnapshotStore {

        var debug: AdbDebugSnapshot? = null
        var port: AdbPortSnapshot? = null
        var usb: AdbUsbSnapshot? = null

        override fun loadDebug(): AdbDebugSnapshot? = debug

        override fun saveDebug(snapshot: AdbDebugSnapshot) {
            debug = snapshot
        }

        override fun clearDebug() {
            debug = null
        }

        override fun loadPort(): AdbPortSnapshot? = port

        override fun savePort(snapshot: AdbPortSnapshot) {
            port = snapshot
        }

        override fun clearPort() {
            port = null
        }

        override fun loadUsb(): AdbUsbSnapshot? = usb

        override fun saveUsb(snapshot: AdbUsbSnapshot) {
            usb = snapshot
        }

        override fun clearUsb() {
            usb = null
        }
    }
}
