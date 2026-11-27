package com.cardesk.launcher.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 车机 Wi-Fi 热点控制器（自动热点 + 自动任务「开启/关闭热点」统一执行端）。
 *
 * ## 底层通道顺序（核对自领克 820A 原厂 `com.ecarx.settings.wifiap.ap.APSupport`）
 *
 * 1. **API 26+ 优先 `WifiManager.startSoftAp(null)` / `stopSoftAp()`**：
 *    与 820A 原厂设置（`APSupport.enableSoftAP`）完全一致，沿用车机已保存的热点 SSID/密码；
 *    Android 9（API 28）上 `startSoftAp(null)` 会直接以 `IFACE_IP_MODE_TETHERED` 拉起热点与共享。
 * 2. **兜底 `ConnectivityManager.startTethering(TETHERING_WIFI, false, null, null)` / `stopTethering(0)`**：
 *    部分 ROM 对 `startSoftAp` 单独加权限拦截或只走 Tethering 通道时自动回退。
 * 3. **旧版兜底 `WifiManager.setWifiApEnabled(null, enable)`**（API < 26 或定制固件）。
 *
 * 需要 `system` 变体（AOSP platform 签名 + `uid 1000`）及 `TETHER_PRIVILEGED` / `NETWORK_SETTINGS` /
 * `CHANGE_WIFI_STATE` 权限；无系统权限时如实返回失败原因，不吞异常。
 */
object HotspotController {

    // Android WifiManager 隐藏状态码（WIFI_AP_STATE_*）
    const val STATE_DISABLING = 10
    const val STATE_DISABLED = 11
    const val STATE_ENABLING = 12
    const val STATE_ENABLED = 13
    const val STATE_FAILED = 14
    const val STATE_UNKNOWN = -1

    /** ConnectivityManager.TETHERING_WIFI = 0。 */
    private const val TETHERING_WIFI = 0

    /** 开机自动热点的冷却窗口（10 分钟），防止开机广播与桌面启动重复触发或覆盖用户手动关闭。 */
    const val AUTO_COOLDOWN_MS = 10 * 60 * 1000L

    /** 开机时等待 Wi-Fi 服务就绪的最长时间。 */
    private const val WAIT_TIMEOUT_MS = 15_000L
    private const val WAIT_POLL_MS = 500L

    /** 下发后回读确认状态变化的轮询参数（最多等 2.5 秒）。 */
    private const val VERIFY_POLL_MS = 250L
    private const val VERIFY_ATTEMPTS = 10

    const val KEY_AUTO_LAST_AT = "hotspot_auto_last_at"
    const val KEY_AUTO_LAST_RESULT = "hotspot_auto_last_result"

    private val mainHandler = Handler(Looper.getMainLooper())

    data class Result(
        val ok: Boolean,
        val method: String,
        val message: String,
    )

    // ------------------------------------------------------------------ 纯逻辑判据（可 JVM 单测）

    fun isActiveState(state: Int): Boolean = state == STATE_ENABLED || state == STATE_ENABLING

    fun isOffState(state: Int): Boolean = state == STATE_DISABLED || state == STATE_DISABLING

    fun stateLabel(state: Int): String = when (state) {
        STATE_ENABLED -> "已开启"
        STATE_ENABLING -> "开启中"
        STATE_DISABLED -> "已关闭"
        STATE_DISABLING -> "关闭中"
        STATE_FAILED -> "异常"
        else -> "未知"
    }

    fun cooldownElapsed(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now - lastAt >= AUTO_COOLDOWN_MS

    /** 开机自动热点本次是否应当执行（纯逻辑，供单测钉死）。 */
    fun shouldAutoStart(enabled: Boolean, alreadyOn: Boolean, lastAt: Long, now: Long): Boolean =
        enabled && !alreadyOn && cooldownElapsed(lastAt, now)

    fun autoSkipReason(enabled: Boolean, alreadyOn: Boolean, lastAt: Long, now: Long): String = when {
        !enabled -> "开关关闭"
        alreadyOn -> "热点已开启"
        !cooldownElapsed(lastAt, now) -> "冷却中（距上次 ${now - lastAt}ms）"
        else -> ""
    }

    // ------------------------------------------------------------------ 状态读取

    fun getApState(context: Context): Int {
        val wm = wifiManager(context) ?: return STATE_UNKNOWN
        return runCatching {
            wm.javaClass.getMethod("getWifiApState").invoke(wm) as? Int ?: STATE_UNKNOWN
        }.getOrElse {
            runCatching {
                val on = wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
                when (on) {
                    true -> STATE_ENABLED
                    false -> STATE_DISABLED
                    null -> STATE_UNKNOWN
                }
            }.getOrDefault(STATE_UNKNOWN)
        }
    }

    fun isEnabled(context: Context): Boolean = getApState(context) == STATE_ENABLED

    fun isEnablingOrEnabled(context: Context): Boolean = isActiveState(getApState(context))

    @Suppress("DEPRECATION")
    fun readSsid(context: Context): String? {
        val wm = wifiManager(context) ?: return null
        return runCatching {
            val cfg = wm.javaClass.getMethod("getWifiApConfiguration").invoke(wm) as? WifiConfiguration
            cfg?.SSID?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 开关执行

    /**
     * 同步开启或关闭热点（会短暂轮询回读状态，建议在后台线程调用）。
     */
    fun setEnabled(context: Context, enable: Boolean, trigger: String, verify: Boolean = true): Result {
        val app = context.applicationContext ?: context
        val wm = wifiManager(app)
        if (wm == null) {
            val msg = "拿不到 WifiManager 服务"
            Logx.w(LogTags.SYS, "热点切换失败", "enable" to enable, "trigger" to trigger, "reason" to msg)
            return Result(false, "none", msg)
        }

        val currentState = getApState(app)
        if (enable && isActiveState(currentState)) {
            Logx.i(LogTags.SYS, "热点已处于开启状态，跳过重复操作", "state" to currentState, "trigger" to trigger)
            return Result(true, "noop", "热点已开启")
        }
        if (!enable && currentState == STATE_DISABLED) {
            Logx.i(LogTags.SYS, "热点已处于关闭状态，跳过重复操作", "state" to currentState, "trigger" to trigger)
            return Result(true, "noop", "热点已关闭")
        }

        val errors = ArrayList<String>()

        // 1) Android 8+ 首选 WifiManager.startSoftAp(null) / stopSoftAp()（与 820A 原厂设置一致）
        if (Build.VERSION.SDK_INT >= 26) {
            val softAp = trySoftAp(wm, enable)
            if (softAp.first) {
                val verified = if (verify) verifyState(app, enable) else true
                val stateAfter = getApState(app)
                Logx.i(
                    LogTags.SYS,
                    if (enable) "开启热点已下发（startSoftAp）" else "关闭热点已下发（stopSoftAp）",
                    "trigger" to trigger,
                    "verified" to verified,
                    "stateAfter" to stateAfter,
                )
                if (verified) {
                    return Result(true, "startSoftAp", if (enable) "热点已开启" else "热点已关闭")
                }
                // startSoftAp 返回 true 但状态没变（例如被 Tethering 层拦截），继续尝试 startTethering 兜底
                errors += "startSoftAp 未生效(state=${stateLabel(stateAfter)})"
            } else {
                softAp.second?.let { errors += "startSoftAp:$it" }
            }
        }

        // 2) 兜底 ConnectivityManager.startTethering / stopTethering
        val tether = tryTethering(app, enable)
        if (tether.first) {
            val verified = if (verify) verifyState(app, enable) else true
            val stateAfter = getApState(app)
            Logx.i(
                LogTags.SYS,
                if (enable) "开启热点已下发（startTethering）" else "关闭热点已下发（stopTethering）",
                "trigger" to trigger,
                "verified" to verified,
                "stateAfter" to stateAfter,
            )
            return Result(
                ok = verified,
                method = "tethering",
                message = when {
                    verified && enable -> "热点已开启"
                    verified && !enable -> "热点已关闭"
                    else -> "指令已下发，但状态未变化（${stateLabel(stateAfter)}）"
                },
            )
        } else {
            tether.second?.let { errors += "tethering:$it" }
        }

        // 3) 旧版 / 定制 ROM 兜底 setWifiApEnabled(null, enable)
        val legacy = tryLegacySetWifiApEnabled(wm, enable)
        if (legacy.first) {
            val verified = if (verify) verifyState(app, enable) else true
            val stateAfter = getApState(app)
            Logx.i(
                LogTags.SYS,
                if (enable) "开启热点已下发（setWifiApEnabled）" else "关闭热点已下发（setWifiApEnabled）",
                "trigger" to trigger,
                "verified" to verified,
                "stateAfter" to stateAfter,
            )
            return Result(
                ok = verified,
                method = "legacy",
                message = when {
                    verified && enable -> "热点已开启"
                    verified && !enable -> "热点已关闭"
                    else -> "指令已下发，但状态未变化（${stateLabel(stateAfter)}）"
                },
            )
        } else {
            legacy.second?.let { errors += "legacy:$it" }
        }

        val detail = errors.joinToString("; ").ifEmpty { "系统拒绝或缺少系统签名权限" }
        Logx.w(
            LogTags.SYS,
            if (enable) "开启热点失败" else "关闭热点失败",
            "trigger" to trigger,
            "errors" to detail,
            "platformSigned" to SystemOps.isPlatformSigned(app),
            "systemUid" to SystemOps.isSystemUid(app),
        )
        return Result(false, "failed", "${if (enable) "开启" else "关闭"}热点失败：$detail")
    }

    /** 异步执行热点切换并在主线程回调结果（供设置页手动按钮使用）。 */
    fun setEnabledAsync(context: Context, enable: Boolean, trigger: String, onResult: (Result) -> Unit) {
        val app = context.applicationContext ?: context
        Thread {
            val res = runCatching { setEnabled(app, enable, trigger) }.getOrElse {
                Logx.e(LogTags.SYS, "热点切换线程异常", it, "enable" to enable, "trigger" to trigger)
                Result(false, "exception", "热点切换异常：${it.message}")
            }
            mainHandler.post { onResult(res) }
        }.apply {
            name = "hotspot-toggle"
            isDaemon = true
        }.start()
    }

    // ------------------------------------------------------------------ 自动热点（开机 / 进桌面）

    /**
     * 开机广播 / 桌面启动入口：若「设置 → 自动热点」开启且不在冷却期内，在后台线程自动拉起热点。
     */
    fun applyAutoStart(context: Context, trigger: String) {
        val app = context.applicationContext ?: context
        val prefs = Prefs(app)
        val enabled = ScheduledTasks.isHotspotAutoEnabled(prefs)
        if (!enabled) {
            Logx.d(LogTags.SYS, "自动热点：开关关闭", "trigger" to trigger)
            return
        }
        val now = System.currentTimeMillis()
        val lastAt = prefs.longValue(KEY_AUTO_LAST_AT, 0L)
        val alreadyOn = isEnablingOrEnabled(app)
        if (!shouldAutoStart(enabled, alreadyOn, lastAt, now)) {
            Logx.i(
                LogTags.SYS,
                "自动热点：本次跳过",
                "trigger" to trigger,
                "reason" to autoSkipReason(enabled, alreadyOn, lastAt, now),
            )
            return
        }
        // 先记冷却时间，避免 BootReceiver 与 LauncherView 几乎同时触发两次
        prefs.setLongValue(KEY_AUTO_LAST_AT, now)
        Thread {
            runCatching {
                val ready = awaitWifiManager(app)
                if (!ready) {
                    val msg = "失败：Wi-Fi 服务超时未就绪"
                    prefs.setStringValue(KEY_AUTO_LAST_RESULT, msg)
                    Logx.w(LogTags.SYS, "自动热点：$msg", "trigger" to trigger)
                    return@runCatching
                }
                if (isEnablingOrEnabled(app)) {
                    prefs.setStringValue(KEY_AUTO_LAST_RESULT, "热点已开启")
                    return@runCatching
                }
                val res = setEnabled(app, enable = true, trigger = "自动热点($trigger)")
                prefs.setStringValue(KEY_AUTO_LAST_RESULT, res.message)
                Logx.i(
                    LogTags.SYS,
                    "自动热点执行完成",
                    "trigger" to trigger,
                    "ok" to res.ok,
                    "method" to res.method,
                    "message" to res.message,
                )
            }.onFailure {
                val msg = "异常：${it.message}"
                prefs.setStringValue(KEY_AUTO_LAST_RESULT, msg)
                Logx.w(LogTags.SYS, "自动热点执行异常", it, "trigger" to trigger)
            }
        }.apply {
            name = "hotspot-auto"
            isDaemon = true
        }.start()
    }

    /** 自动任务动作执行（`ACTION_HOTSPOT_ON` / `ACTION_HOTSPOT_OFF`）。 */
    fun executeTaskAction(context: Context, task: ScheduledTask) {
        val enable = task.action == ScheduledTasks.ACTION_HOTSPOT_ON
        val app = context.applicationContext ?: context
        setEnabledAsync(app, enable, "自动任务:${task.id}") { res ->
            if (res.ok) {
                Logx.i(LogTags.TASK, "自动任务热点动作成功", "id" to task.id, "enable" to enable, "method" to res.method)
            } else {
                Logx.w(LogTags.TASK, "自动任务热点动作失败", "id" to task.id, "enable" to enable, "reason" to res.message)
            }
            Toast.makeText(app, res.message, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private fun wifiManager(context: Context): WifiManager? =
        runCatching { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()

    private fun awaitWifiManager(context: Context): Boolean {
        var waited = 0L
        while (waited < WAIT_TIMEOUT_MS) {
            if (wifiManager(context) != null && getApState(context) != STATE_UNKNOWN) return true
            Thread.sleep(WAIT_POLL_MS)
            waited += WAIT_POLL_MS
        }
        return wifiManager(context) != null
    }

    private fun verifyState(context: Context, wantOn: Boolean): Boolean {
        repeat(VERIFY_ATTEMPTS) {
            val state = getApState(context)
            if (wantOn && isActiveState(state)) return true
            if (!wantOn && state == STATE_DISABLED) return true
            Thread.sleep(VERIFY_POLL_MS)
        }
        val finalState = getApState(context)
        return if (wantOn) isActiveState(finalState) else isOffState(finalState)
    }

    private fun trySoftAp(wm: WifiManager, enable: Boolean): Pair<Boolean, String?> = try {
        val ok = if (enable) {
            val m = wm.javaClass.getMethod("startSoftAp", WifiConfiguration::class.java)
            (m.invoke(wm, null) as? Boolean) == true
        } else {
            val m = wm.javaClass.getMethod("stopSoftAp")
            (m.invoke(wm) as? Boolean) == true
        }
        ok to if (ok) null else "返回 false"
    } catch (t: Throwable) {
        val cause = t.cause ?: t
        false to "${cause.javaClass.simpleName}:${cause.message.orEmpty()}"
    }

    private fun tryTethering(context: Context, enable: Boolean): Pair<Boolean, String?> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false to "无 ConnectivityManager"
            if (enable) {
                val cbClass = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                val m4 = runCatching {
                    cm.javaClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        cbClass,
                        Handler::class.java,
                    )
                }.getOrNull()
                if (m4 != null) {
                    m4.invoke(cm, TETHERING_WIFI, false, null, null)
                    true to null
                } else {
                    val m3 = cm.javaClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        cbClass,
                    )
                    m3.invoke(cm, TETHERING_WIFI, false, null)
                    true to null
                }
            } else {
                val m = cm.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                m.invoke(cm, TETHERING_WIFI)
                true to null
            }
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            false to "${cause.javaClass.simpleName}:${cause.message.orEmpty()}"
        }
    }

    private fun tryLegacySetWifiApEnabled(wm: WifiManager, enable: Boolean): Pair<Boolean, String?> = try {
        val m = wm.javaClass.getMethod(
            "setWifiApEnabled",
            WifiConfiguration::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val ok = (m.invoke(wm, null, enable) as? Boolean) == true
        ok to if (ok) null else "返回 false"
    } catch (t: Throwable) {
        val cause = t.cause ?: t
        false to "${cause.javaClass.simpleName}:${cause.message.orEmpty()}"
    }
}
