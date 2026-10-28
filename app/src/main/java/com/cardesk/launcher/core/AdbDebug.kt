package com.cardesk.launcher.core

/**
 * ADB / 无线调试子系统 —— **纯逻辑层**。
 *
 * 本文件**不含任何 Android API**，可以在 JVM 单测里直接跑。
 * Android 侧的具体实现（`Settings.Global` / `SystemProperties` / `UsbManager` /
 * `Socket` / `SharedPreferences`全部放在 [AdbBackend] 与 [AdbSnapshotStore]
 * 两个接口后面，由 `core/AdbBridge.kt` 提供。
 *
 * ## 能力对照（「领克 05 调试工具 2.0」逆向结论）
 *
 * APK（`local.linkco.wirelessadb`）的机制是四段
 *
 * | 段 |类 | 做什么 |
 * |---|---|---|
 * | 调试开关 | | 写 `development_settings_enabled=1` + `adb_enabled=1`，并**启用开发者选项组件**；写之前先存原值 |
 * | 无线端口 | | 写 `service.adb.tcp.port=5555`（写不进去就退 `persist.adb.tcp.port`），回读校验，再**重启 adbd** |
 * | USB 接口 | | `UsbManager.setCurrentFunctions(ADB 位)` |
 * | 统一出口 | | 「恢复全部设置」：无线端口 / USB 接口 / 调试开关逐段恢复，每段独立兜底 |
 *
 * 我们把上面四段整理成 **一条链路 + 一份原值快照 + 一个恢复出口**：
 *
 * 1. **原值快照**（[AdbSnapshotStore]）：每段动之前把原值存下来；
 *    任一步失败自动回滚并清掉记录；用户随时能「恢复全部设置」退回去。
 * 2. **回读校验**：写完立刻回读，对不上就算失败（宁可回滚也不留半吊子状态）。
 * 3. **端口兜底**：`service.adb.tcp.port` 写不进去且原值为空时，改写 `persist.adb.tcp.port`。
 * 4. **重启 adbd**：优先 `ctl.restart=adbd`；失败且有 `MANAGE_USB` 权限时，
 *    切 USB 功能位（0 ↔ ADB）逼系统重连 adbd。
 *
 * ## 安全口径
 *
 * 无线 ADB 打开期间，**同网段的任何设备都能连上车机**。
 * 所以「恢复全部设置」必须是这个页面上最显眼、最好找的按钮，
 * 而且每一段恢复失败都要如实报告，不能吞。
 */
object AdbDebug {

    // ------------------------------------------------------------------ 系统设置键

    /** `Settings.Global`：开发者选项总开关。 */
    const val SETTING_DEV = "development_settings_enabled"

    /** `Settings.Global`：USB 调试开关。 */
    const val SETTING_ADB = "adb_enabled"

    // ------------------------------------------------------------------ 系统属性键

    /** 系统属性：本次生效的无线端口（`service.` 前缀 = 不持久化）。 */
    const val PROP_SERVICE_PORT = "service.adb.tcp.port"

    /** 系统属性：持久化的无线端口（重启后仍在）。 */
    const val PROP_PERSIST_PORT = "persist.adb.tcp.port"

    /** 系统属性：adbd 服务的运行状态。 */
    const val PROP_ADBD = "init.svc.adbd"

    /** 系统属性：重启 adbd 用的 init 属性。 */
    const val PROP_CTL_RESTART = "ctl.restart"

    /** 无线调试端口（APK 用的就是 5555）。 */
    const val WIRELESS_PORT = "5555"

    // ------------------------------------------------------------------ 组件 / 权限

    /** 原生「开发者选项」页所在包。 */
    const val COMPONENT_PACKAGE = "com.android.settings"

    /** 原生「开发者选项」页的 Activity（部分 ROM 会把这个入口禁用掉）。 */
    const val COMPONENT_ACTIVITY = "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"

    /** `PackageManager` 的组件状态：默认。 */
    const val COMPONENT_STATE_DEFAULT = 0

    /** `PackageManager` 的组件状态：显式启用。 */
    const val COMPONENT_STATE_ENABLED = 1

    /** `PackageManager` 的组件状态：显式禁用。 */
    const val COMPONENT_STATE_DISABLED = 2

    /** 写 `Settings.Global`（APK `WRITE_SECURE_SETTINGS`）。 */
    const val PERM_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    /** 启用 / 禁用原生开发者选项组件（APK `CHANGE_COMPONENT_ENABLED_STATE`）。 */
    const val PERM_CHANGE_COMPONENT = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"

    /** 切换 USB 功能位（APK `MANAGE_USB`）。 */
    const val PERM_MANAGE_USB = "android.permission.MANAGE_USB"

    /** 走完整条链路需要的权限（缺一不可）。 */
    val REQUIRED_PERMISSIONS = listOf(PERM_WRITE_SECURE, PERM_CHANGE_COMPONENT, PERM_MANAGE_USB)

    /**
     * USB 功能位：ADB。
     *
     * APK 直接写了 `4`。我们**不写死**
     * 具体位值随 ROM 变，由 `AdbBridge` 在运行时反射 `UsbManager.FUNCTION_ADB` 取，
     * 取不到才回落到 4（一致）。
     */
    const val USB_FUNCTION_ADB_FALLBACK = 4L

    // ------------------------------------------------------------------ 诊断

    /** 诊断要读的 12 个系统属性（顺序与APK 一致，便于逐行对照）。 */
    val DIAGNOSTIC_PROPERTIES = listOf(
        PROP_ADBD,
        PROP_SERVICE_PORT,
        PROP_PERSIST_PORT,
        "sys.usb.config",
        "sys.usb.state",
        "persist.sys.usb.config",
        "sys.usb.configfs",
        "ro.adb.secure",
        "ro.debuggable",
        "ro.secure",
        "ro.build.type",
        "ro.vendor.sw.version",
    )

    /** SELinux 属性上下文文件（用来解释「为什么属性写不进去」）。 */
    val PROPERTY_CONTEXT_FILES = listOf(
        "/system/etc/selinux/plat_property_contexts",
        "/vendor/etc/selinux/vendor_property_contexts",
        "/vendor/etc/selinux/nonplat_property_contexts",
    )

    // ------------------------------------------------------------------ 时序参数

    /** 探测本机 5555 端口的连接超时（毫秒）。 */
    const val PORT_PROBE_TIMEOUT_MS = 350

    /** 等端口状态翻转的轮询次数。 */
    const val PORT_WAIT_ROUNDS = 16

    /** 等端口状态翻转的轮询间隔（毫秒）。 */
    const val PORT_WAIT_INTERVAL_MS = 350L

    /** 切 USB 功能位逼 adbd 重连时的停顿（毫秒）。 */
    const val USB_TOGGLE_SETTLE_MS = 1500L

    /** USB 恢复后回读确认的轮询次数。 */
    const val USB_CONFIRM_ROUNDS = 12

    /** USB 恢复后回读确认的轮询间隔（毫秒）。 */
    const val USB_CONFIRM_INTERVAL_MS = 150L

    /** 开启 USB 调试后给系统留的生效时间（毫秒）。 */
    const val USB_AFTER_ENABLE_SETTLE_MS = 1600L
}

/** ADB 子系统内部异常：带一句能直接给人看的中文原因。 */
class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---------------------------------------------------------------------------- 数据快照

/**
 * 调试开关段的原值快照。
 *
 * @param dev `development_settings_enabled` 原值（null = 原来没设过）
 * @param adb `adb_enabled` 原值
 * @param component 开发者选项组件状态原值
 */
data class AdbDebugSnapshot(val dev: String?, val adb: String?, val component: Int)

/**
 * 无线端口段的原值快照。
 *
 * @param service `service.adb.tcp.port` 原值
 * @param persist `persist.adb.tcp.port` 原值
 */
data class AdbPortSnapshot(val service: String, val persist: String)

/** USB 接口段的原值快照。 */
data class AdbUsbSnapshot(val functions: Long)

// ---------------------------------------------------------------------------- 后端接口

/**
 * ADB 子系统需要的系统能力（Android 侧实现见 `core/AdbBridge.kt`）。
 *
 * 抽成接口是为了让整条「开启 / 恢复」链路能在 JVM 单测里用假后端跑通。
 */
interface AdbBackend {

    /** 读 `Settings.Global`（读不到返回 null）。 */
    fun readSetting(key: String): String?

    /** 写 `Settings.Global`（value 为 null 表示删除该键）。 */
    fun writeSetting(key: String, value: String?): Boolean

    /** 读开发者选项组件状态（`PackageManager.getComponentEnabledSetting`）。 */
    fun componentState(): Int

    /** 写开发者选项组件状态。 */
    fun writeComponentState(state: Int)

    /** 读系统属性（读不到返回空串）。 */
    fun readProperty(key: String): String

    /** 写系统属性（写不进去抛异常）。 */
    fun writeProperty(key: String, value: String)

    /** 读当前 USB 功能位。 */
    fun usbFunctions(): Long

    /**
     * ADB 对应的 USB 功能位（运行时从框架取，取不到回落到 4）。
     *
     * APK 直接写死 4；我们不写死 —— 不同 ROM 的位值可能不同。
     */
    fun usbAdbFunction(): Long

    /** 请求切换 USB 功能位。 */
    fun requestUsb(functions: Long)

    /** 本机 5555 端口是否接受连接。 */
    fun portOpen(): Boolean

    /** 是否持有 `MANAGE_USB` 权限（决定能不能用切 USB 的方式重启 adbd）。 */
    fun canManageUsb(): Boolean
}

/**
 * 原值快照的存取（三段各存各的，互不覆盖）。
 *
 * 语义与APK 的三份 SharedPreferences（/
 * 一致：**只有第一次开启时才记原值**，之后反复操作都以那份原值为准。
 */
interface AdbSnapshotStore {

    fun loadDebug(): AdbDebugSnapshot?

    fun saveDebug(snapshot: AdbDebugSnapshot)

    fun clearDebug()

    fun loadPort(): AdbPortSnapshot?

    fun savePort(snapshot: AdbPortSnapshot)

    fun clearPort()

    fun loadUsb(): AdbUsbSnapshot?

    fun saveUsb(snapshot: AdbUsbSnapshot)

    fun clearUsb()
}

// ---------------------------------------------------------------------------- 结果

/**
 * 一次 ADB 操作的结果。
 *
 * @param ok 是否成功
 * @param title 一句话结论（给人看的）
 * @param detail 补充说明：失败时是原因链，成功时是当时的状态摘要
 */
data class AdbOutcome(val ok: Boolean, val title: String, val detail: String = "")

// ---------------------------------------------------------------------------- 前置校验

/**
 * 前置条件检查结果。
 *
 * APK 在动手之前会先查：SDK、uid、固件签名、三条权限、用户策略。
 * 差一样就**先别改**，直接告诉用户缺什么 —— 这条口径必须保留，
 * 否则会出现「点了没反应，还把原值搞乱了」。
 */
data class AdbSupport(val ok: Boolean, val reason: String) {

    companion object {

        /**
         * @param sdkInt 系统 API 级别
         * @param uid 本进程 uid
         * @param platformSigned 是否与系统平台签名一致
         * @param settingsSigned 系统设置是否也是平台签名
         * @param permissions 权限 → 是否已授予
         * @param debuggingRestricted 是否被设备策略禁止调试
         */
        fun check(
            sdkInt: Int,
            uid: Int,
            platformSigned: Boolean,
            settingsSigned: Boolean,
            permissions: Map<String, Boolean>,
            debuggingRestricted: Boolean,
        ): AdbSupport {
            if (sdkInt < 28) {
                return AdbSupport(false, "需要 Android 9（API 28）及以上的系统")
            }
            if (uid != 1000) {
                return AdbSupport(false, "未获得系统共享身份（uid ≠ 1000）")
            }
            if (!platformSigned || !settingsSigned) {
                return AdbSupport(false, "固件签名与本工具核对不一致")
            }
            val missing = AdbDebug.REQUIRED_PERMISSIONS.firstOrNull { permissions[it] != true }
            if (missing != null) {
                return AdbSupport(false, "缺少权限：$missing")
            }
            if (debuggingRestricted) {
                return AdbSupport(false, "设备策略禁止开启调试")
            }
            return AdbSupport(true, "固件与权限符合")
        }
    }
}

// ---------------------------------------------------------------------------- 当前状态

/**
 * ADB 子系统的当前状态快照（纯数据，便于单测断言与 UI 直读）。
 */
data class AdbState(
    val uid: Int,
    val platformSigned: Boolean,
    val settingsSigned: Boolean,
    val devSetting: String?,
    val adbSetting: String?,
    val componentState: Int,
    val componentEnabled: Boolean,
    val adbdService: String,
    val servicePort: String,
    val persistPort: String,
    val usbFunctions: Long,
    val portOpen: Boolean,
    /** 本机 IPv4（给「用电脑连车机」用；读不到就是「<读不到>」）。 */
    val localIp: String,
    val hasDebugSnapshot: Boolean,
    val hasUsbSnapshot: Boolean,
    val hasPortSnapshot: Boolean,
) {

    /** 当前生效的无线端口：`service` 为空时取 `persist`。 */
    val wirelessPort: String get() = servicePort.ifEmpty { persistPort }

    /** 三段恢复记录的有无。 */
    val restoreSummary: String
        get() = "调试 ${yn(hasDebugSnapshot)} / USB ${yn(hasUsbSnapshot)} / 无线 ${yn(hasPortSnapshot)}"

    private fun yn(v: Boolean) = if (v) "有" else "无"
}

// ---------------------------------------------------------------------------- 诊断

/**
 * 诊断文本的拼装（纯函数）。
 */
object AdbDiagnostics {

    /** `Settings.Global` 里 "1" / "0" / 其它 的显示文案。 */
    fun settingLabel(value: String?): String = when (value) {
        "1" -> "开启"
        "0" -> "关闭"
        null -> "未设置"
        "" -> "未设置"
        else -> value
    }

    /** 组件状态的显示文案。 */
    fun componentLabel(state: Int): String = when (state) {
        AdbDebug.COMPONENT_STATE_ENABLED -> "启用"
        AdbDebug.COMPONENT_STATE_DISABLED -> "禁用"
        else -> "默认"
    }

    /** USB 功能位转文字（`names` 由 Android 侧反射 `UsbManager.FUNCTION_*` 填）。 */
    fun usbFunctionsText(functions: Long, names: Map<Long, String> = emptyMap()): String {
        if (functions == 0L) return "0（无）"
        val hits = names.filterKeys { it != 0L && (functions and it) != 0L }
        val label = hits.entries.joinToString(" + ") { it.value }
        return if (label.isEmpty()) functions.toString() else "$functions（$label）"
    }

    /** 异常原因链（外层 → 内层，逐层换行）。 */
    fun describe(error: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = error
        while (current != null) {
            if (sb.isNotEmpty()) sb.append('\n').append("原因：")
            sb.append(current.javaClass.simpleName).append(": ").append(current.message.orEmpty())
            current.suppressed.forEach { sb.append('\n').append("补充：").append(describe(it)) }
            current = current.cause
        }
        return sb.toString()
    }

    /** 系统属性清单（`key = value`，空值显示 `<空>`）。 */
    fun formatProperties(values: List<Pair<String, String>>): String =
        values.joinToString("\n") { (key, value) ->
            "$key = ${if (value.isEmpty()) "<空>" else value}"
        }

    /**
     * 从崩溃日志里挑出 **系统设置 App（`com.android.settings`）** 那一次 Java 异常。
     *
     * APK 的原意：打开开发者选项时车机上的系统设置会崩
     * 但 logcat 里堆着一大堆别的崩溃，得按 pid 把那一次捞出来。
     *
     * 纯函数（可单测）：输入 logcat 文本，输出那一次崩溃的原文。
     */
    fun filterSettingsCrash(log: String): String {
        val lines = log.split("\n")
        var pid: String? = null
        for (line in lines) {
            if (line.contains("Process: com.android.settings,")) {
                val match = CRASH_PID.find(line)
                if (match != null) pid = match.groupValues[1]
            }
        }
        if (pid == null) {
            return "最近的崩溃日志中没有找到 com.android.settings 的 Java 异常。"
        }
        val sb = StringBuilder()
        for (line in lines) {
            val match = CRASH_PID.find(line)
            if (match != null && match.groupValues[1] == pid) {
                sb.append(line).append('\n')
                if (sb.length >= CRASH_LIMIT) break
            }
        }
        return sb.toString()
    }

    /** 崩溃日志一次最多取多少字符。 */
    const val CRASH_LIMIT = 18000

    private val CRASH_PID = Regex("^[A-Z]/AndroidRuntime\\(\\s*(\\d+)\\)")
}

// ---------------------------------------------------------------------------- 控制器

/**
 * ADB 子系统的控制器：**开启 / 恢复** 的全部状态机都在这里。
 *
 * 设计要点：
 * - **先存原值再动手**；任一步失败 → 回滚 + 清记录，不留半吊子状态；
 * - **写完必须回读**，对不上就是失败；
 * - 每一段（调试开关 / 无线端口 / USB 接口）互相独立，
 *   「恢复全部设置」逐段恢复、逐段报告，某段失败不影响其它段。
 */
class AdbController(
    private val backend: AdbBackend,
    private val store: AdbSnapshotStore,
    /** 注入的休眠函数（单测里换掉，别真睡）。 */
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    // ------------------------------------------------------------- 调试开关

    /**
     * 开启调试开关：`development_settings_enabled=1` + `adb_enabled=1` +
     * 启用原生开发者选项组件，三项都回读确认。
     */
    fun enableDebug(): AdbOutcome {
        val snapshot = try {
            ensureDebugSnapshot()
        } catch (t: Throwable) {
            return AdbOutcome(false, "调试开关开启失败", AdbDiagnostics.describe(t))
        }
        return try {
            writeSettingChecked(AdbDebug.SETTING_DEV, "1")
            writeSettingChecked(AdbDebug.SETTING_ADB, "1")
            backend.writeComponentState(AdbDebug.COMPONENT_STATE_ENABLED)
            requireSame("1", backend.readSetting(AdbDebug.SETTING_DEV), AdbDebug.SETTING_DEV)
            requireSame("1", backend.readSetting(AdbDebug.SETTING_ADB), AdbDebug.SETTING_ADB)
            requireSame(
                AdbDebug.COMPONENT_STATE_ENABLED,
                backend.componentState(),
                "开发者页面组件状态",
            )
            AdbOutcome(
                true,
                "调试开关已开启并回读确认",
                "开发者选项与 USB 调试均为开启；开发者页面入口已显式启用。",
            )
        } catch (t: Throwable) {
            val rollback = runCatching { applyDebugSnapshot(snapshot) }.exceptionOrNull()
            store.clearDebug()
            if (rollback != null) t.addSuppressed(rollback)
            AdbOutcome(false, "调试开关开启失败", AdbDiagnostics.describe(t))
        }
    }

    /** 恢复调试开关到原值（没有记录就说「未修改」）。 */
    fun restoreDebug(): AdbOutcome {
        val snapshot = store.loadDebug()
            ?: return AdbOutcome(true, "没有调试开关恢复记录，未修改")
        return try {
            applyDebugSnapshot(snapshot)
            store.clearDebug()
            AdbOutcome(true, "调试开关原值已恢复并回读确认")
        } catch (t: Throwable) {
            AdbOutcome(false, "调试开关恢复失败", AdbDiagnostics.describe(t))
        }
    }

    // ------------------------------------------------------------- 无线端口

    /**
     * 开启无线 ADB：把端口切到 5555 并重启 adbd。
     *
     * 兜底：`service.adb.tcp.port` 写不进去、且它原值本来就是空时，
     * 改写 `persist.adb.tcp.port`（重启后仍在，但本次不生效的那条路走不通）。
     */
    fun enableWireless(): AdbOutcome {
        val snapshot = try {
            ensurePortSnapshot()
        } catch (t: Throwable) {
            return AdbOutcome(false, "无线端口开启失败", AdbDiagnostics.describe(t))
        }
        return try {
            switchPortToWireless(snapshot)
            AdbOutcome(
                true,
                "无线端口已切到 ${AdbDebug.WIRELESS_PORT}",
                "adbd 已重启；本机 5555 " + if (backend.portOpen()) "接受连接" else "暂未接受连接。",
            )
        } catch (t: Throwable) {
            val rollback = runCatching { applyPortSnapshot(snapshot, restart = false) }.exceptionOrNull()
            store.clearPort()
            if (rollback != null) t.addSuppressed(rollback)
            AdbOutcome(false, "无线端口开启失败", AdbDiagnostics.describe(t))
        }
    }

    /** 恢复无线端口原值（并重启 adbd）。 */
    fun restoreWireless(): AdbOutcome {
        val snapshot = store.loadPort()
            ?: return AdbOutcome(true, "没有无线端口恢复记录，未修改")
        return try {
            applyPortSnapshot(snapshot, restart = true)
            store.clearPort()
            AdbOutcome(
                true,
                "原端口属性已恢复",
                "本机 5555 " + if (backend.portOpen()) "仍在接受连接" else "已不再接受连接",
            )
        } catch (t: Throwable) {
            AdbOutcome(false, "无线端口恢复失败", AdbDiagnostics.describe(t))
        }
    }

    // ------------------------------------------------------------- USB 接口

    /** 请求 USB 功能切到 ADB 位（电脑是否识别仍需另行验证）。 */
    fun enableUsb(): AdbOutcome {
        return try {
            if (store.loadUsb() == null) {
                store.saveUsb(AdbUsbSnapshot(backend.usbFunctions()))
            }
            backend.requestUsb(backend.usbAdbFunction())
            AdbOutcome(true, "USB 调试接口已请求激活", "电脑是否识别仍需验证。")
        } catch (t: Throwable) {
            AdbOutcome(false, "USB 接口请求失败", AdbDiagnostics.describe(t))
        }
    }

    /** 恢复 USB 功能到原值并回读确认。 */
    fun restoreUsb(): AdbOutcome {
        val snapshot = store.loadUsb()
            ?: return AdbOutcome(true, "没有 USB 接口恢复记录，未修改")
        return try {
            backend.requestUsb(snapshot.functions)
            var confirmed = false
            for (i in 0 until AdbDebug.USB_CONFIRM_ROUNDS) {
                sleeper(AdbDebug.USB_CONFIRM_INTERVAL_MS)
                if (backend.usbFunctions() == snapshot.functions) {
                    confirmed = true
                    break
                }
            }
            if (!confirmed) {
                throw AdbException("USB 恢复请求已发送，尚未回读到原值；恢复记录保留")
            }
            store.clearUsb()
            AdbOutcome(true, "原接口功能已恢复并回读确认")
        } catch (t: Throwable) {
            AdbOutcome(false, "USB 接口恢复失败", AdbDiagnostics.describe(t))
        }
    }

    // ------------------------------------------------------------- 统一出口

    /**
     * 完整链路之一：开调试开关 + 请求 USB 接口。
     *
     * 调试开关是前提，USB 接口请求失败时**要如实说明「开关已开、接口没成」**，
     * 不能笼统报一个「失败」让人以为什么都没改。
     */
    fun enableUsbSession(): AdbOutcome {
        val debug = enableDebug()
        if (!debug.ok) return debug
        val usb = enableUsb()
        if (!usb.ok) {
            return AdbOutcome(false, "调试开关已开启；USB 接口请求未完成", usb.detail)
        }
        sleeper(AdbDebug.USB_AFTER_ENABLE_SETTLE_MS)
        return AdbOutcome(
            true,
            "调试开关已开启并回读确认；USB 接口已请求激活。",
            "电脑是否识别仍需验证。",
        )
    }

    /**
     * 完整链路之二：开调试开关 + 切无线端口（5555）+ 等端口起来。
     */
    fun enableWirelessSession(): AdbOutcome {
        val debug = enableDebug()
        if (!debug.ok) return debug
        val port = enableWireless()
        if (!port.ok) return port
        val open = waitForPort(true)
        return AdbOutcome(
            true,
            "无线 ADB 已开启",
            "调试开关已开启；端口已切到 ${AdbDebug.WIRELESS_PORT}；本机 5555 " +
                if (open) "接受连接" else "暂未接受连接，可稍后再看状态。",
        )
    }

    /**
     * 完整链路之三：恢复无线端口，并等端口状态回到「原值对应的样子」。
     *
     * 原值本来就是 5555 → 恢复后仍应接受连接；否则应关闭。
     */
    fun restoreWirelessSession(): AdbOutcome {
        val snapshot = store.loadPort()
            ?: return AdbOutcome(true, "没有无线端口恢复记录，未修改")
        val original = snapshot.service.ifEmpty { snapshot.persist }
        val outcome = restoreWireless()
        if (!outcome.ok) return outcome
        waitForPort(original == AdbDebug.WIRELESS_PORT)
        return AdbOutcome(
            true,
            "原端口属性已恢复",
            "本机 5555 " + if (backend.portOpen()) "仍接受连接" else "未接受连接",
        )
    }

    /**
     * 等端口状态翻转（轮询 [AdbDebug.PORT_WAIT_ROUNDS] 次）。
     *
     * @return true = 在等待窗口内达到了目标状态
     */
    fun waitForPort(expected: Boolean): Boolean {
        for (i in 0 until AdbDebug.PORT_WAIT_ROUNDS) {
            if (backend.portOpen() == expected) return true
            sleeper(AdbDebug.PORT_WAIT_INTERVAL_MS)
        }
        return false
    }

    /**
     * 「恢复全部设置」：无线端口 → USB 接口 → 调试开关，逐段恢复、逐段报告。
     *
     * 某段失败不影响其它段继续恢复 —— 全部要退，退不干净也要说清楚哪段没退掉。
     */
    fun restoreAll(): AdbOutcome {
        val wireless = restoreWirelessSession()
        val usb = restoreUsb()
        val debug = restoreDebug()
        val lines = listOf(
            "无线端口：${wireless.title}" + detailOf(wireless),
            "USB 接口：${usb.title}" + detailOf(usb),
            "调试开关：${debug.title}" + detailOf(debug),
        )
        val ok = wireless.ok && usb.ok && debug.ok
        return AdbOutcome(
            ok,
            if (ok) "全部设置已恢复" else "部分恢复未完成",
            lines.joinToString("\n"),
        )
    }

    // ------------------------------------------------------------- adbd 重启

    /**
     * 重启 adbd。
     *
     * 首选 `ctl.restart=adbd`；这条路走不通但有 `MANAGE_USB` 权限时，
     * 切 USB 功能位（0 ↔ ADB）逼系统重连 adbd。
     */
    fun restartAdbd() {
        try {
            backend.writeProperty(AdbDebug.PROP_CTL_RESTART, "adbd")
            return
        } catch (t: Throwable) {
            if (!backend.canManageUsb()) throw t
        }
        val current = backend.usbFunctions()
        val adbFunction = backend.usbAdbFunction()
        val off = if (current == adbFunction) 0L else adbFunction
        try {
            backend.requestUsb(off)
            sleeper(AdbDebug.USB_TOGGLE_SETTLE_MS)
        } finally {
            backend.requestUsb(current)
        }
    }

    // ------------------------------------------------------------- 内部

    private fun ensureDebugSnapshot(): AdbDebugSnapshot =
        store.loadDebug() ?: AdbDebugSnapshot(
            backend.readSetting(AdbDebug.SETTING_DEV),
            backend.readSetting(AdbDebug.SETTING_ADB),
            backend.componentState(),
        ).also { store.saveDebug(it) }

    private fun ensurePortSnapshot(): AdbPortSnapshot =
        store.loadPort() ?: AdbPortSnapshot(
            backend.readProperty(AdbDebug.PROP_SERVICE_PORT),
            backend.readProperty(AdbDebug.PROP_PERSIST_PORT),
        ).also { store.savePort(it) }

    private fun switchPortToWireless(snapshot: AdbPortSnapshot) {
        try {
            backend.writeProperty(AdbDebug.PROP_SERVICE_PORT, AdbDebug.WIRELESS_PORT)
        } catch (t: Throwable) {
            // 兜底：`service.` 属性写不进去（常见于属性上下文不给写权限），
            // 且它本来就是空的 —— 这时改写持久化的那条属性。
            val current = backend.readProperty(AdbDebug.PROP_SERVICE_PORT)
            if (current == snapshot.service && snapshot.service.isEmpty()) {
                try {
                    backend.writeProperty(AdbDebug.PROP_PERSIST_PORT, AdbDebug.WIRELESS_PORT)
                } catch (persistError: Throwable) {
                    persistError.addSuppressed(t)
                    throw persistError
                }
            } else {
                throw t
            }
        }
        val readback = backend.readProperty(AdbDebug.PROP_SERVICE_PORT)
            .ifEmpty { backend.readProperty(AdbDebug.PROP_PERSIST_PORT) }
        requireSame(AdbDebug.WIRELESS_PORT, readback, "无线端口")
        restartAdbd()
    }

    private fun applyDebugSnapshot(snapshot: AdbDebugSnapshot) {
        var failure: Throwable? = null
        try {
            writeSettingChecked(AdbDebug.SETTING_ADB, snapshot.adb)
        } catch (t: Throwable) {
            failure = t
        }
        try {
            writeSettingChecked(AdbDebug.SETTING_DEV, snapshot.dev)
        } catch (t: Throwable) {
            failure = failure?.also { it.addSuppressed(t) } ?: t
        }
        try {
            backend.writeComponentState(snapshot.component)
        } catch (t: Throwable) {
            failure = failure?.also { it.addSuppressed(t) } ?: t
        }
        failure?.let { throw it }
        requireSame(snapshot.adb, backend.readSetting(AdbDebug.SETTING_ADB), AdbDebug.SETTING_ADB)
        requireSame(snapshot.dev, backend.readSetting(AdbDebug.SETTING_DEV), AdbDebug.SETTING_DEV)
        requireSame(snapshot.component, backend.componentState(), "开发者页面组件状态")
    }

    private fun applyPortSnapshot(snapshot: AdbPortSnapshot, restart: Boolean) {
        var failure: Throwable? = null
        var changed = false
        try {
            if (backend.readProperty(AdbDebug.PROP_SERVICE_PORT) != snapshot.service) {
                backend.writeProperty(AdbDebug.PROP_SERVICE_PORT, snapshot.service)
                changed = true
            }
        } catch (t: Throwable) {
            failure = t
        }
        try {
            if (backend.readProperty(AdbDebug.PROP_PERSIST_PORT) != snapshot.persist) {
                backend.writeProperty(AdbDebug.PROP_PERSIST_PORT, snapshot.persist)
                changed = true
            }
        } catch (t: Throwable) {
            failure = failure?.also { it.addSuppressed(t) } ?: t
        }
        failure?.let { throw it }
        requireSame(
            snapshot.service,
            backend.readProperty(AdbDebug.PROP_SERVICE_PORT),
            AdbDebug.PROP_SERVICE_PORT,
        )
        requireSame(
            snapshot.persist,
            backend.readProperty(AdbDebug.PROP_PERSIST_PORT),
            AdbDebug.PROP_PERSIST_PORT,
        )
        if (changed || restart) restartAdbd()
    }

    private fun writeSettingChecked(key: String, value: String?) {
        if (!backend.writeSetting(key, value)) {
            throw AdbException("无法写入 $key")
        }
    }

    private fun requireSame(expected: Any?, actual: Any?, what: String) {
        if (expected != actual) {
            val shown = actual?.toString()?.ifEmpty { "<空>" } ?: "<空>"
            throw AdbException("$what 回读不一致（期望 $expected，实际 $shown）")
        }
    }

    private fun detailOf(outcome: AdbOutcome): String =
        if (outcome.detail.isEmpty()) "" else " — ${outcome.detail}"
}
