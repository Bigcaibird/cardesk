package com.cardesk.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * ADB 子系统的 **Android 侧实现**。
 *
 * 纯逻辑在 [AdbDebug] / [AdbController]；这里只负责把「系统能力」接上去：
 * `Settings.Global` / `PackageManager` / `SystemProperties` / `UsbManager` / `Socket`。
 *
 * ## 与APK 的对应关系
 *
 * |类 | 我们 | 说明 |
 * |---|---|---|
 * | | [AndroidAdbBackend] | 系统属性读写、adbd 重启、端口探测、SELinux 上下文 |
 * | | [AdbController.enableDebug] | 调试开关三件套 + 原值快照 |
 * | | [AdbController.enableWireless] | 端口切换 + `persist` 兜底 + 回读 |
 * | | [AdbController.enableUsb] | USB 功能位切换 |
 * | | [AdbRuntime.diagnostics] | 属性清单 / 崩溃日志 / 权限自检 |
 * | | [AdbController.restoreAll] | 恢复全部设置 |
 */
object AdbBridge {

    /**
     * 走 shell 读系统属性（反射路径的兜底）。
     *
     * `getprop` 任何应用都能读，读不到就返回空串。
     */
    private fun readPropertyViaShell(key: String): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        text
    }.getOrDefault("")

    // ---------------------------------------------------------------- 后端

    /**
     * 系统能力后端。
     *
     * 所有「可能失败」的操作都**抛异常**（而不是返回 false），
     * 这样上层 [AdbController] 的「失败 → 回滚」链路才不会被静默吞掉。
     */
    class AndroidAdbBackend(context: Context) : AdbBackend {

        private val app = context.applicationContext
        private val pm: PackageManager = app.packageManager
        private val devComponent = ComponentName(AdbDebug.COMPONENT_PACKAGE, AdbDebug.COMPONENT_ACTIVITY)

        private val usbService: Any? by lazy { app.getSystemService(Context.USB_SERVICE) }

        override fun readSetting(key: String): String? =
            runCatching { Settings.Global.getString(app.contentResolver, key) }.getOrNull()

        override fun writeSetting(key: String, value: String?): Boolean =
            runCatching { Settings.Global.putString(app.contentResolver, key, value) }.isSuccess

        override fun componentState(): Int =
            runCatching { pm.getComponentEnabledSetting(devComponent) }
                .getOrDefault(AdbDebug.COMPONENT_STATE_DEFAULT)

        override fun writeComponentState(state: Int) {
            runCatching {
                pm.setComponentEnabledSetting(devComponent, state, PackageManager.DONT_KILL_APP)
            }.getOrElse {
                throw AdbException("切换开发者页面组件状态失败", it)
            }
        }

        override fun readProperty(key: String): String {
            val viaApi = runCatching {
                val method = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                (method.invoke(null, key) as? String).orEmpty()
            }.getOrNull()
            if (viaApi != null) return viaApi
            return readPropertyViaShell(key)
        }

        override fun writeProperty(key: String, value: String) {
            // 主路径：反射 `SystemProperties.set`。系统签名 + 系统 uid 才能写进去。
            val viaApi = runCatching {
                val method = Class.forName("android.os.SystemProperties")
                    .getMethod("set", String::class.java, String::class.java)
                method.invoke(null, key, value)
                true
            }
            if (viaApi.getOrDefault(false)) return
            // 兜底：走 shell 的 `setprop`（普通应用 uid 没有 shell 权限，通常会失败）。
            val exit = runCatching {
                Runtime.getRuntime().exec(arrayOf("setprop", key, value)).waitFor()
            }.getOrDefault(-1)
            if (exit != 0) {
                throw AdbException("写系统属性 $key 失败（setprop 退出码 $exit）")
            }
        }

        override fun usbFunctions(): Long {
            val service = usbService ?: throw AdbException("系统未提供 USB 管理服务")
            val value = runCatching {
                service.javaClass.getMethod("getCurrentFunctions").invoke(service)
            }.getOrElse {
                throw AdbException("读 USB 功能位失败（UsbManager.getCurrentFunctions 不可用）", it)
            }
            return (value as? Number)?.toLong() ?: 0L
        }

        override fun requestUsb(functions: Long) {
            val service = usbService ?: throw AdbException("系统未提供 USB 管理服务")
            runCatching {
                service.javaClass
                    .getMethod("setCurrentFunctions", Long::class.javaPrimitiveType)
                    .invoke(service, functions)
            }.getOrElse {
                throw AdbException("切换 USB 功能位失败（UsbManager.setCurrentFunctions 不可用）", it)
            }
        }

        override fun usbAdbFunction(): Long {
            // 不同 ROM 的位值可能不同，运行时从框架取；取不到才回落到 4（一致）。
            val candidates = listOf(
                "android.hardware.usb.UsbManager",
                "android.hardware.usb.UsbConstants",
            )
            for (className in candidates) {
                val value = runCatching {
                    val field = Class.forName(className).getField("FUNCTION_ADB")
                        .getInt(null)
                    field.toLong()
                }.getOrNull()
                if (value != null && value != 0L) return value
            }
            return AdbDebug.USB_FUNCTION_ADB_FALLBACK
        }

        override fun portOpen(): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", AdbDebug.WIRELESS_PORT.toInt()),
                    AdbDebug.PORT_PROBE_TIMEOUT_MS,
                )
                true
            }
        }.getOrDefault(false)

        override fun canManageUsb(): Boolean =
            app.checkSelfPermission(AdbDebug.PERM_MANAGE_USB) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------- 快照存储

    /**
     * 原值快照的落盘实现（三段三份偏好，和APK 的三份 `original_*` 一一对应）。
     *
     * 用 而不是：**快照没落盘就不能开始改系统**，
     * 否则中途失败时没有原值可退，是最危险的中间态。
     */
    class PrefsAdbSnapshotStore(context: Context) : AdbSnapshotStore {

        private val app = context.applicationContext
        private val debug = app.getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
        private val port = app.getSharedPreferences(PREFS_PORT, Context.MODE_PRIVATE)
        private val usb = app.getSharedPreferences(PREFS_USB, Context.MODE_PRIVATE)

        override fun loadDebug(): AdbDebugSnapshot? =
            if (debug.getBoolean(KEY_SAVED, false)) {
                AdbDebugSnapshot(
                    debug.getString(KEY_DEV, null),
                    debug.getString(KEY_ADB, null),
                    debug.getInt(KEY_COMPONENT, AdbDebug.COMPONENT_STATE_DEFAULT),
                )
            } else {
                null
            }

        override fun saveDebug(snapshot: AdbDebugSnapshot) {
            val ok = debug.edit()
                .putString(KEY_DEV, snapshot.dev)
                .putString(KEY_ADB, snapshot.adb)
                .putInt(KEY_COMPONENT, snapshot.component)
                .putBoolean(KEY_SAVED, true)
                .commit()
            if (!ok) throw AdbException("保存调试开关原值失败")
        }

        override fun clearDebug() {
            if (!debug.edit().clear().commit()) throw AdbException("清理调试恢复记录失败")
        }

        override fun loadPort(): AdbPortSnapshot? =
            if (port.getBoolean(KEY_SAVED, false)) {
                AdbPortSnapshot(
                    port.getString(KEY_SERVICE, "").orEmpty(),
                    port.getString(KEY_PERSIST, "").orEmpty(),
                )
            } else {
                null
            }

        override fun savePort(snapshot: AdbPortSnapshot) {
            val ok = port.edit()
                .putString(KEY_SERVICE, snapshot.service)
                .putString(KEY_PERSIST, snapshot.persist)
                .putBoolean(KEY_SAVED, true)
                .commit()
            if (!ok) throw AdbException("保存无线端口原值失败")
        }

        override fun clearPort() {
            if (!port.edit().clear().commit()) throw AdbException("清理端口恢复记录失败")
        }

        override fun loadUsb(): AdbUsbSnapshot? =
            if (usb.getBoolean(KEY_SAVED, false)) {
                AdbUsbSnapshot(usb.getLong(KEY_FUNCTIONS, 0L))
            } else {
                null
            }

        override fun saveUsb(snapshot: AdbUsbSnapshot) {
            val ok = usb.edit()
                .putLong(KEY_FUNCTIONS, snapshot.functions)
                .putBoolean(KEY_SAVED, true)
                .commit()
            if (!ok) throw AdbException("保存 USB 接口原值失败")
        }

        override fun clearUsb() {
            if (!usb.edit().clear().commit()) throw AdbException("清理 USB 恢复记录失败")
        }

        private companion object {
            const val PREFS_DEBUG = "adb_snapshot_debug"
            const val PREFS_PORT = "adb_snapshot_port"
            const val PREFS_USB = "adb_snapshot_usb"
            const val KEY_SAVED = "saved"
            const val KEY_DEV = "dev"
            const val KEY_ADB = "adb"
            const val KEY_COMPONENT = "component"
            const val KEY_SERVICE = "service"
            const val KEY_PERSIST = "persist"
            const val KEY_FUNCTIONS = "functions"
        }
    }

    // ---------------------------------------------------------------- 门面

    /**
     * 给 UI 用的门面：状态、诊断、操作入口都在这里。
     */
    object AdbRuntime {

        /**
         * 状态读取的**替身入口**。
         *
         * 正常路径永远是 null（直接读系统）；
         * 离屏渲染夹具用它注入一份**确定的状态**，
         * 好让设计板和 App 出图能逐像素比（真机上 uid / 签名 / IP 都不一样，
         * 不注入的话这一屏没法比。
         */
        @Volatile
        @JvmStatic
        var stateProvider: ((Context) -> AdbState)? = null

        /** 控制器实例（每次新建：无状态，状态都在快照存储里）。 */
        fun controller(context: Context): AdbController = AdbController(
            AndroidAdbBackend(context),
            PrefsAdbSnapshotStore(context),
        )

        /** 当前状态快照。 */
        fun state(context: Context): AdbState {
            val app = context.applicationContext
            stateProvider?.let { provider ->
                return runCatching { provider(app) }.getOrElse { readState(app) }
            }
            return readState(app)
        }

        private fun readState(app: Context): AdbState {
            val backend = AndroidAdbBackend(app)
            val store = PrefsAdbSnapshotStore(app)
            return AdbState(
                uid = Process.myUid(),
                platformSigned = SystemOps.isPlatformSigned(app),
                settingsSigned = isSignedAsPlatform(app, AdbDebug.COMPONENT_PACKAGE),
                devSetting = backend.readSetting(AdbDebug.SETTING_DEV),
                adbSetting = backend.readSetting(AdbDebug.SETTING_ADB),
                componentState = backend.componentState(),
                componentEnabled = componentEnabled(app),
                adbdService = backend.readProperty(AdbDebug.PROP_ADBD),
                servicePort = backend.readProperty(AdbDebug.PROP_SERVICE_PORT),
                persistPort = backend.readProperty(AdbDebug.PROP_PERSIST_PORT),
                usbFunctions = runCatching { backend.usbFunctions() }.getOrDefault(0L),
                portOpen = backend.portOpen(),
                localIp = localIPv4(),
                hasDebugSnapshot = store.loadDebug() != null,
                hasUsbSnapshot = store.loadUsb() != null,
                hasPortSnapshot = store.loadPort() != null,
            )
        }

        /** 前置条件检查的**替身入口**（渲染夹具注入确定结果用）。 */
        @Volatile
        @JvmStatic
        var supportProvider: ((Context) -> AdbSupport)? = null

        /** 前置条件检查。 */
        fun support(context: Context): AdbSupport {
            val app = context.applicationContext
            supportProvider?.let { provider ->
                runCatching { provider(app) }.getOrNull()?.let { return it }
            }
            return AdbSupport.check(
                sdkInt = Build.VERSION.SDK_INT,
                uid = Process.myUid(),
                platformSigned = SystemOps.isPlatformSigned(app),
                settingsSigned = isSignedAsPlatform(app, AdbDebug.COMPONENT_PACKAGE),
                permissions = AdbDebug.REQUIRED_PERMISSIONS.associateWith {
                    app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                },
                debuggingRestricted = isDebuggingRestricted(app),
            )
        }

        /** 诊断文本的**替身入口**（渲染夹具注入确定文本用）。 */
        @Volatile
        @JvmStatic
        var diagnosticsProvider: ((Context) -> String)? = null

        /** 诊断文本（属性 + 权限 + USB 功能 + 崩溃日志 + SELinux 上下文 + 本机 IP）。 */
        fun diagnostics(context: Context): String {
            val app = context.applicationContext
            diagnosticsProvider?.let { provider ->
                runCatching { provider(app) }.getOrNull()?.let { return it }
            }
            val backend = AndroidAdbBackend(app)
            val sb = StringBuilder()
            sb.append("调试服务诊断\n")
            val props = AdbDebug.DIAGNOSTIC_PROPERTIES.map { key ->
                key to runCatching { backend.readProperty(key) }.getOrDefault("")
            }
            sb.append(AdbDiagnostics.formatProperties(props)).append('\n')
            sb.append("USB 管理权限 = ")
                .append(if (backend.canManageUsb()) "已获得" else "未获得").append('\n')
            sb.append("当前 USB 功能 = ")
                .append(AdbDiagnostics.usbFunctionsText(runCatching { backend.usbFunctions() }.getOrDefault(0L)))
                .append('\n')
            sb.append("本机 IPv4 = ").append(localIPv4()).append('\n')
            sb.append("进程域：").append(processDomain()).append('\n')
            sb.append("\n原生设置最近的崩溃：\n")
            sb.append(crashLog(app))
            sb.append('\n')
            sb.append(policyDetails())
            return sb.toString()
        }

        /** 完整报告：状态 + 最近一次操作结论 + 诊断详情。 */
        fun report(context: Context, lastAction: String): String {
            val state = state(context)
            val support = support(context)
            val head = StringBuilder()
            head.append("身份 UID：").append(state.uid)
                .append("   开发者选项：").append(AdbDiagnostics.settingLabel(state.devSetting))
                .append("   USB 调试：").append(AdbDiagnostics.settingLabel(state.adbSetting)).append('\n')
            head.append("开发者页面：").append(AdbDiagnostics.componentLabel(state.componentState))
                .append("   ADB 服务：").append(if (state.adbdService.isEmpty()) "<空>" else state.adbdService).append('\n')
            head.append("无线端口：").append(if (state.wirelessPort.isEmpty()) "未配置" else state.wirelessPort)
                .append("   5555：").append(if (state.portOpen) "接受连接（本机）" else "未接受连接（本机）").append('\n')
            head.append("恢复记录：").append(state.restoreSummary).append('\n')
            head.append("检查：").append(support.reason)
            return "$head\n\n$lastAction\n\n${diagnostics(context)}"
        }

        /** 打开原生开发者选项页。 */
        fun openDevSettings(context: Context): Boolean {
            val intent = android.content.Intent().apply {
                component = ComponentName(AdbDebug.COMPONENT_PACKAGE, AdbDebug.COMPONENT_ACTIVITY)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }

        /** 设备策略是否禁止开启调试。 */
        fun isDebuggingRestricted(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            manager?.hasUserRestriction("no_debugging_features") == true
        }.getOrDefault(false)

        /** 开发者选项组件当前是否「入口可用」。 */
        fun componentEnabled(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            val info = pm.getActivityInfo(
                ComponentName(AdbDebug.COMPONENT_PACKAGE, AdbDebug.COMPONENT_ACTIVITY),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            val state = pm.getComponentEnabledSetting(
                ComponentName(AdbDebug.COMPONENT_PACKAGE, AdbDebug.COMPONENT_ACTIVITY),
            )
            when (state) {
                AdbDebug.COMPONENT_STATE_ENABLED -> true
                AdbDebug.COMPONENT_STATE_DISABLED -> false
                else -> info.enabled
            }
        }.getOrDefault(false)

        /** 某个包是否与系统平台同签名。 */
        fun isSignedAsPlatform(context: Context, packageName: String): Boolean = runCatching {
            context.packageManager.checkSignatures(context.packageName, packageName) ==
                PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)

        /** 本机 IPv4 地址（取第一个非回环、非链路本地）。 */
        fun localIPv4(): String {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                ?: return "<读不到>"
            for (item in interfaces) {
                val addresses = runCatching { item.inetAddresses.toList() }.getOrDefault(emptyList())
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        return address.hostAddress.orEmpty()
                    }
                }
            }
            return "<未连接>"
        }

        /** 进程的 SELinux 域。 */
        fun processDomain(): String = runCatching {
            BufferedReader(FileReader("/proc/self/attr/current")).use { reader ->
                reader.readLine()?.replace("\u0000", "") ?: "<空>"
            }
        }.getOrElse { it.javaClass.simpleName }

        /** SELinux 属性上下文里与 `adb.tcp.port` 相关的行（解释「为什么写不进去」）。 */
        fun policyDetails(): String {
            val sb = StringBuilder("端口属性上下文：\n")
            for (path in AdbDebug.PROPERTY_CONTEXT_FILES) {
                val file = File(path)
                if (!file.exists()) continue
                runCatching {
                    BufferedReader(FileReader(file)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.contains("adb.tcp.port")) sb.append(path).append(": ").append(line).append('\n')
                        }
                    }
                }
            }
            return sb.toString()
        }

        /** 系统设置 App 的崩溃日志（已挑出 com.android.settings 那一次）。 */
        fun crashLog(context: Context): String {
            if (context.checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                return "未获得读取日志权限"
            }
            val raw = runCatching {
                val process = ProcessBuilder("/system/bin/logcat", "-b", "crash", "-d", "-v", "brief", "-t", "160")
                    .redirectErrorStream(true)
                    .start()
                val text = process.inputStream.use { input ->
                    BufferedReader(InputStreamReader(input, "UTF-8")).use { reader ->
                        val sb = StringBuilder()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (sb.length >= 60000) break
                            sb.append(line).append('\n')
                        }
                        sb.toString()
                    }
                }
                process.destroy()
                text
            }.getOrElse { return "读取崩溃日志失败：${AdbDiagnostics.describe(it)}" }
            return AdbDiagnostics.filterSettingsCrash(raw)
        }

        /** 记录一次 ADB 操作（统一走 CarDesk/Sys 域，方便真机上 grep）。 */
        fun logOutcome(action: String, outcome: AdbOutcome) {
            if (outcome.ok) {
                Logx.i(LogTags.SYS, "ADB 操作完成", "action" to action, "detail" to outcome.detail)
            } else {
                Logx.w(LogTags.SYS, "ADB 操作失败", "action" to action, "detail" to outcome.detail)
            }
        }
    }
}
