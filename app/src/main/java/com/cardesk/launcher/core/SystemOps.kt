package com.cardesk.launcher.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 系统能力层：把（系统 uid + 平台签名）用到的系统能力集中到一处
 * 能力不足时返回 false 由调用方降级。
 *
 * - 静默安装： `PackageInstaller.Session` + `INSTALL_PACKAGES`
 * - ADB / 无线调试：第五十五轮已独立成 `core/AdbDebug.kt`（纯逻辑）+ `core/AdbBridge.kt`（Android 侧），
 *   这里不再保留旧的 （它只写 `adb_enabled` 一个键，能力不足）
 * - 重启： `PowerManager.reboot(null)`，失败回退 `su -c reboot`
 * - 按键注入： `input keyevent` / + `INJECT_EVENTS`
 * - 策略位： `settings put global POLICY_CONTROL`
 */
object SystemOps {

    enum class Capability(val label: String, val permission: String?) {
        SILENT_INSTALL("静默安装", "android.permission.INSTALL_PACKAGES"),
        SECURE_SETTINGS("系统设置写入", "android.permission.WRITE_SECURE_SETTINGS"),
        REBOOT("重启", "android.permission.REBOOT"),
        INJECT_EVENTS("按键注入", "android.permission.INJECT_EVENTS"),
        SYSTEM_UID("系统 uid", null),
    }

    fun isPlatformSigned(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        pm.checkSignatures(context.packageName, "android") == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    fun isSystemUid(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, 0).uid == 1000
    }.getOrDefault(false)

    fun hasPermission(context: Context, permission: String?): Boolean {
        if (permission == null) return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isAvailable(context: Context, capability: Capability): Boolean {
        val ok = when (capability) {
            Capability.SYSTEM_UID -> isSystemUid(context)
            else -> hasPermission(context, capability.permission) || isPlatformSigned(context)
        }
        // 排障场景：设置页显示「不可用」时，确认到底是缺权限还是签名不匹配
        Logx.d(
            LogTags.SYS,
            "系统能力检测",
            "capability" to capability.name,
            "label" to capability.label,
            "permission" to capability.permission,
            "available" to ok,
        )
        return ok
    }

    /** 能力矩阵，用于设置页展示「当前构建能做什么」。 */
    fun capabilityReport(context: Context): List<Pair<Capability, Boolean>> =
        Capability.entries.map { it to isAvailable(context, it) }

    // ---------------------------------------------------------------- 静默安装

    /**
     * 用 PackageInstaller 安装 APK。需要 INSTALL_PACKAGES（system flavor / 平台签名），
     * 否则返回 false，调用方回退系统安装器。
     */
    fun installApk(context: Context, apk: File): Boolean {
        if (!apk.exists()) {
            // 排障场景：在线更新点「应用」后没动静 —— 先看 APK 到底有没有下下来
            Logx.w(LogTags.SYS, "静默安装失败：APK 文件不存在", "path" to apk.absolutePath)
            return false
        }
        if (!hasPermission(context, Capability.SILENT_INSTALL.permission)) {
            // 排障场景：静默安装被拒 —— 缺少 INSTALL_PACKAGES 时只能回退系统安装器
            Logx.w(
                LogTags.SYS,
                "静默安装失败：缺少 INSTALL_PACKAGES 权限",
                "path" to apk.absolutePath,
                "bytes" to apk.length(),
            )
            return false
        }
        val ok = runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) {
                // `setPackageSource` / `PACKAGE_SOURCE_DOWNLOADED_FILE` 是 **API 30** 才有的成员，
                // 本项目 compileSdk 29（一致）编不到 —— 运行时用反射调，失败就跳过。
                runCatching {
                    val src = Class.forName("android.content.pm.PackageInstaller")
                        .getField("PACKAGE_SOURCE_DOWNLOADED_FILE")
                        .getInt(null)
                    params.javaClass
                        .getMethod("setPackageSource", Int::class.javaPrimitiveType)
                        .invoke(params, src)
                }.onFailure {
                    Logx.w(LogTags.APP, "设置安装来源失败（忽略）", it)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(ACTION_INSTALL_COMMIT).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(callback.intentSender)
            }
            true
        }.getOrDefault(false)
        // 排障场景：静默安装是否真的提交成功（失败时通常是没有平台签名）
        if (ok) {
            Logx.i(LogTags.SYS, "静默安装已提交", "path" to apk.absolutePath, "bytes" to apk.length())
        } else {
            Logx.w(LogTags.SYS, "静默安装失败：创建安装会话异常", "path" to apk.absolutePath)
        }
        return ok
    }

    /** 下载并安装（更新链）。 */
    fun downloadAndInstall(context: Context, url: String): Boolean {
        if (url.isBlank()) {
            Logx.w(LogTags.SYS, "更新下载失败：更新地址为空")
            return false
        }
        val file = File(context.cacheDir, "cardesk-update.apk")
        // 排障场景：在线更新卡住 / 下载失败 —— 记录地址、耗时与落地文件大小
        val started = System.currentTimeMillis()
        val ok = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.inputStream.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
            file.length() > 1024
        }.getOrDefault(false)
        val elapsed = System.currentTimeMillis() - started
        if (!ok) {
            Logx.w(
                LogTags.SYS,
                "更新下载失败",
                "url" to url,
                "bytes" to file.length(),
                "elapsedMs" to elapsed,
            )
            return false
        }
        Logx.i(LogTags.SYS, "更新下载完成", "url" to url, "bytes" to file.length(), "elapsedMs" to elapsed)
        return installApk(context, file)
    }

    /** 回退：交给系统安装器（普通应用唯一可用的路径）。 */
    fun openInstaller(context: Context, apk: File) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    android.net.Uri.fromFile(apk),
                    "application/vnd.android.package-archive",
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    // ---------------------------------------------------------------- 系统设置

    /**
     * 写 `policy_control` 策略。
     *
     * 注意：`policy_control` 是 **String** 型全局设置，必须用 `putString`；
     * 用 `putInt` 会抛 ClassCastException，静默失败。
     */
    fun setPolicyControl(context: Context, value: String?): Boolean {
        if (!hasPermission(context, Capability.SECURE_SETTINGS.permission)) {
            Logx.w(LogTags.SYS, "写入 policy_control 失败：缺少 WRITE_SECURE_SETTINGS 权限", "policy" to value)
            return false
        }
        val ok = runCatching {
            Settings.Global.putString(context.contentResolver, POLICY_CONTROL, value ?: "null")
        }.isSuccess
        // 排障场景：策略写入是否成功（失败一般是没有系统权限）
        if (ok) {
            Logx.i(LogTags.SYS, "写入 policy_control 成功", "policy" to value)
        } else {
            Logx.w(LogTags.SYS, "写入 policy_control 失败", "policy" to value)
        }
        return ok
    }

    // ---------------------------------------------------------------- 原车 Dock 策略

    /**
     * 写 `policy_control` 字符串策略。
     *
     * 顺序是「先写 `null` 复位、再写真值」，失败再退到
     * `settings put global policy_control` shell，最后回读校验是否已经生效。
     */
    fun applyPolicyControl(context: Context, value: String?): Boolean {
        if (!hasPermission(context, Capability.SECURE_SETTINGS.permission) && !isPlatformSigned(context)) {
            // 排障场景：「隐藏原车 Dock」没反应 —— 多半是这台车机没有系统权限
            Logx.w(
                LogTags.SYS,
                "应用 policy_control 失败：缺少 WRITE_SECURE_SETTINGS 权限且非平台签名",
                "policy" to value,
            )
            return false
        }
        // 先复位再写真值，避免旧策略残留
        runCatching { Settings.Global.putString(context.contentResolver, POLICY_CONTROL, "null") }
        execSettingsPut(POLICY_CONTROL, value ?: "null")
        val written = runCatching {
            Settings.Global.putString(context.contentResolver, POLICY_CONTROL, value ?: "null")
        }.isSuccess
        val ok = written || policyMatches(context, value)
        // 排障场景：隐藏 Dock / 状态栏策略到底写进去了没有
        if (ok) {
            Logx.i(LogTags.SYS, "应用 policy_control 成功", "policy" to value, "directWrite" to written)
        } else {
            Logx.w(LogTags.SYS, "应用 policy_control 失败", "policy" to value, "directWrite" to written)
        }
        return ok
    }

    /** 回读 `policy_control` 判断策略是否已经生效（`policyMatches`）。 */
    fun policyMatches(context: Context, value: String?): Boolean {
        val current = runCatching {
            Settings.Global.getString(context.contentResolver, POLICY_CONTROL)
        }.getOrNull()
        // 排障场景：策略写完不生效时，回读一次看系统里到底是什么值
        Logx.d(LogTags.SYS, "回读 policy_control", "current" to current, "expect" to value)
        return if (value == null) {
            current == null || current.isBlank() || current == "null"
        } else {
            current == value
        }
    }

    /** 隐藏/恢复系统状态栏（StatusBarManager 反射）。 */
    fun setStatusBarDisabled(context: Context, flags: Int): Boolean {
        val result = runCatching {
            val service = context.getSystemService("statusbar") ?: return@runCatching false
            val method = service.javaClass.getMethod("disable", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(service, flags)
            true
        }
        // 排障场景：「隐藏原车状态栏」没反应 —— 反射方法在别的 ROM 上可能不存在，异常要留痕
        val error = result.exceptionOrNull()
        if (error != null) {
            Logx.w(LogTags.SYS, "隐藏原车状态栏失败：反射 StatusBarManager.disable 异常", error, "flags" to flags)
            return false
        }
        val ok = result.getOrDefault(false)
        if (ok) {
            Logx.i(LogTags.SYS, "隐藏原车状态栏成功：已反射调用 StatusBarManager.disable", "flags" to flags)
        } else {
            Logx.w(LogTags.SYS, "隐藏原车状态栏失败：拿不到 statusbar 服务", "flags" to flags)
        }
        return ok
    }

    /**
     * 设置 overscan（反射实现）。
     * 原车 Dock 下移时把底部裁掉 50px。
     */
    fun setOverscan(context: Context, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val result = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                ?: return@runCatching false
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return@runCatching false
            val method = display.javaClass.methods.firstOrNull { m ->
                m.name == "setOverscan" && m.parameterTypes.size == 5 &&
                    m.parameterTypes.drop(1).all { it == Int::class.javaPrimitiveType }
            } ?: return@runCatching false
            method.isAccessible = true
            method.invoke(display, left, top, right, bottom)
            true
        }
        // 排障场景：「下移 Dock」没效果 —— 反射 setOverscan 在部分车机上不存在
        val error = result.exceptionOrNull()
        if (error != null) {
            Logx.w(LogTags.SYS, "设置 overscan 失败：反射 setOverscan 异常", error, "bottom" to bottom)
            return false
        }
        val ok = result.getOrDefault(false)
        // 排障场景：overscan 每次都会调，记下最终裁剪值
        if (ok) {
            Logx.d(LogTags.SYS, "设置 overscan 成功", "l" to left, "t" to top, "r" to right, "b" to bottom)
        } else {
            Logx.w(LogTags.SYS, "设置 overscan 失败：反射 setOverscan 不可用", "l" to left, "t" to top, "r" to right, "b" to bottom)
        }
        return ok
    }

    /** 走 shell `settings put global`。 */
    private fun execSettingsPut(key: String, value: String) {
        // 排障场景：policy_control 走 shell 的兜底路径，记录退出码
        val exit = runCatching {
            Runtime.getRuntime().exec(arrayOf("settings", "put", "global", key, value)).waitFor()
        }.getOrDefault(-1)
        Logx.d(LogTags.SYS, "shell settings put global", "key" to key, "value" to value, "exit" to exit)
    }

    // ---------------------------------------------------------------- 重启

    /** MainActivity.4204/4209：PowerManager.reboot(null)，失败 su -c reboot。 */
    fun reboot(context: Context): Boolean {
        val direct = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.reboot(null)
            true
        }.getOrDefault(false)
        // 排障场景：「重启车机」没反应 —— 记录两条路径各自的结果
        if (direct) {
            Logx.i(LogTags.SYS, "重启车机：PowerManager.reboot 已下发")
            return true
        }
        val su = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            process.waitFor() == 0
        }.getOrDefault(false)
        if (su) {
            Logx.i(LogTags.SYS, "重启车机：su -c reboot 已下发")
        } else {
            Logx.w(LogTags.SYS, "重启车机失败：无系统权限且 su 不可用")
        }
        return su
    }

    // ---------------------------------------------------------------- 按键注入

    /** SessionBridge：InputManager.injectInputEvent(KeyEvent)，需要 INJECT_EVENTS。 */
    /**
     * 以分屏模式启动一个应用：Android 7+ 的 ActivityOptions#setLaunchWindowingMode(3)
     * 是隐藏 API（WINDOWING_MODE_SPLIT_SCREEN_PRIMARY），用反射调用；失败则普通启动。
     * 返回 true 表示至少成功发出了启动请求。
     */
    fun startInSplit(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            // 排障场景：目标应用没有启动入口，分屏启动自然起不来
            Logx.w(LogTags.SYS, "分屏启动应用失败：找不到启动入口", "pkg" to pkg)
            return false
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = runCatching { android.app.ActivityOptions.makeBasic() }.getOrNull()
        var splitOk = false
        if (options != null) {
            splitOk = runCatching {
                options.javaClass.getMethod("setLaunchWindowingMode", Integer.TYPE).invoke(options, 3)
                true
            }.getOrDefault(false)
        }
        val ok = runCatching {
            if (splitOk) context.startActivity(intent, options!!.toBundle())
            else context.startActivity(intent)
            true
        }.getOrDefault(false)
        // 排障场景：分屏启动没反应 —— 区分「反射分屏失败」和「startActivity 失败」
        if (ok) {
            Logx.i(LogTags.SYS, "分屏启动应用", "pkg" to pkg, "splitMode" to splitOk)
        } else {
            Logx.w(LogTags.SYS, "分屏启动应用失败", "pkg" to pkg, "splitMode" to splitOk)
        }
        return ok
    }

    fun injectKey(context: Context, keyCode: Int): Boolean {
        if (!hasPermission(context, Capability.INJECT_EVENTS.permission)) {
            // 排障场景：按键增强没反应 —— 缺 INJECT_EVENTS 就注入不了
            Logx.w(LogTags.SYS, "注入按键失败：缺少 INJECT_EVENTS 权限", "keyCode" to keyCode)
            return false
        }
        val result = runCatching {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE)
                ?: return@runCatching false
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val method = inputManager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            val result = method.invoke(inputManager, event, 0) as? Boolean ?: false
            result
        }.getOrDefault(false)
        // 排障场景：按键增强到底有没有把事件打进去
        if (result) {
            Logx.i(LogTags.SYS, "注入按键成功", "keyCode" to keyCode)
        } else {
            Logx.w(LogTags.SYS, "注入按键失败", "keyCode" to keyCode)
        }
        return result
    }

    /** Shell 调用 `input keyevent N` 的回退路径。 */
    fun injectKeyViaShell(keyCode: Int): Boolean {
        val ok = runCatching {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString())).waitFor() == 0
        }.getOrDefault(false)
        // 排障场景：shell 注入按键的退出码
        if (ok) {
            Logx.i(LogTags.SYS, "shell 注入按键成功", "keyCode" to keyCode)
        } else {
            Logx.w(LogTags.SYS, "shell 注入按键失败", "keyCode" to keyCode)
        }
        return ok
    }

    const val ACTION_INSTALL_COMMIT = "com.cardesk.launcher.action.INSTALL_COMMIT"

    /** 原车 Dock/状态栏策略键。 */
    const val POLICY_CONTROL = "policy_control"

    /** 安装结果回执。 */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_INSTALL_COMMIT) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            val ok = status == PackageInstaller.STATUS_SUCCESS
            // 排障场景：静默安装到底成没成，回执状态码在这里
            if (ok) {
                Logx.i(LogTags.SYS, "安装回执：成功", "status" to status)
            } else {
                Logx.w(LogTags.SYS, "安装回执：失败", "status" to status, "message" to message)
            }
            android.widget.Toast.makeText(
                context,
                if (ok) "安装完成" else "安装失败：$message",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
