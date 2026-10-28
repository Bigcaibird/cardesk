package com.cardesk.launcher.core

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Process
import android.provider.Settings
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 定位权限处理。
 *
 * ## 为什么要有这一套
 *
 * 真机（领克 dcy11 / Android 9）实测：桌面 App 的
 * `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` 在 **manifest 里声明了**，
 * 诊断包里 `checkSelfPermission` 也显示「**已授予**」——
 * 但 对 `passive` 和 `gps` **都返回 null**，
 *  订阅后 **19 分钟没等到一次 fix**。
 *
 * ## 根因在哪
 *
 * Android 的位置权限有**两层**：
 *
 * 1. **permission grant**（`checkSelfPermission`）—— 诊断包看的是这一层，显示「已授予」；
 * 2. **AppOps mode**（`AppOpsManager`）—— `LocationManagerService` 真正拦人的是这一层。
 *
 * 用 `adb shell pm grant` 批下来的权限**只保证第 1 层**；
 * 很多车机 ROM 上第 2 层仍是 `MODE_IGNORED`（或 uid 级 `MODE_FOREGROUND`），
 * 于是表现就是「权限显示已授予，但一个位置都拿不到」——
 * **和真机的现象完全一致**。
 *
 * 真正走一次系统授权流程（用户在弹窗里点「允许」），
 * 系统会**同时**写 permission grant 和 AppOps mode，所以能解开。
 *
 * ## 三条并行的路
 *
 * | 路 | 条件 | 说明 |
 * |---|---|---|
 * | 1. 运行时授权 | 任何安装方式 | `requestPermissions` → 系统弹窗 → 用户点允许（同时打开 AppOps） |
 * | 2. 直接写 AppOps | system app + `SET_APPOP` | `appOps.setMode(OP_FINE_LOCATION, MODE_ALLOWED)`，不依赖弹窗 UI |
 * | 3. 引导去系统设置 | 已被永久拒绝 | 弹窗不再出现时，只能引导用户去「应用权限」里手动开 |
 *
 * 另外还有一路**不依赖定位权限**的：车机天气 SDK 自己的「无位置入口」
 * （见 [CarWeatherApi.findNoLocationQueryMethod]）。
 */
object Permissions {

    /** 请求定位权限的 requestCode。 */
    const val REQUEST_LOCATION = 4401

    /**
     * 定位相关的 AppOps op 名（**公开 API 用的是字符串**，int 常量 `OP_FINE_LOCATION` 是 @hide 的）。
     */
    const val OP_FINE = "android:fine_location"
    const val OP_COARSE = "android:coarse_location"

    /** 两个定位 op 都试一下（App 拿到的权限粒度取决于系统怎么批的）。 */
    private val LOCATION_OPS = listOf(OP_FINE, OP_COARSE)

    /** 录音 op 名（公开 API 里只有 `OPSTR_RECORD_AUDIO` 的字符串形式可用）。 */
    const val OP_RECORD_AUDIO = "android:record_audio"

    /** 定位权限的两种。 */
    private val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** 权限是否已授予（只看 permission grant 这一层）。 */
    fun hasLocationPermission(context: Context): Boolean =
        LOCATION_PERMISSIONS.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * 确保定位权限可用：已授予就什么都不做；没授予就发起运行时授权。
     *
     * @return true = 已有权限（可以立刻取位置）
     */
    fun ensureLocation(activity: Activity): Boolean {
        if (hasLocationPermission(activity)) {
            Logx.i(
                LogTags.WEATHER, "定位权限已具备",
                "granted" to true,
                "state" to describeLocationState(activity),
            )
            return true
        }
        // 先试特权路径：system app 可以直接把自己的 AppOps 打开，
        // 不依赖车机有没有权限弹窗 UI。
        if (tryGrantAppOps(activity)) {
            return true
        }
        // 再走系统弹窗。
        try {
            activity.requestPermissions(LOCATION_PERMISSIONS, REQUEST_LOCATION)
            Logx.i(LogTags.WEATHER, "已发起定位权限申请", "requestCode" to REQUEST_LOCATION)
        } catch (t: Throwable) {
            Logx.w(LogTags.WEATHER, "发起定位权限申请失败", t)
        }
        return false
    }

    /**
     * 用 `SET_APPOP` 特权直接把本应用的定位 AppOps 打开。
     *
     * 只对**装在 `/system/priv-app` 且平台签名**的 system app 有效
     * （`SET_APPOP` 是 signature|privileged 权限）。
     *
     * @return true = 已成功打开
     */
    fun tryGrantAppOps(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val uid = Process.myUid()
        val pkg = context.packageName
        var changed = false
        for (op in LOCATION_OPS) {
            val mode = runCatching { appOps.unsafeCheckOpNoThrow(op, uid, pkg) }.getOrNull() ?: continue
            if (mode == AppOpsManager.MODE_ALLOWED) continue
            val ok = setMode(appOps, op, uid, pkg, AppOpsManager.MODE_ALLOWED)
            if (ok) {
                changed = true
                Logx.i(
                    LogTags.WEATHER, "已直接打开定位 AppOps",
                    "op" to op, "from" to modeName(mode), "to" to "allow",
                )
            }
        }
        return changed
    }

    /**
     * 写 AppOps mode（**隐藏 API，必须反射**）。
     *
     * `AppOpsManager.setMode(...)` 不在公开 API 里（公开的只有读方法和 `OPSTR_*` 常量），
     * 所以只能反射。我们有 hiddenapi 白名单
     * （`system-patch/hiddenapi-package-` 里有 `com.ecarx.qq.tool`），
     * 所以在 system 变体上能调到。
     *
     * 需要 `android.permission.SET_APPOP`（signature|privileged，已加进
     * system manifest + privapp 白名单。
     *
     * 调不通也不要紧 —— 运行时 `requestPermissions` 那条路能同时写 AppOps。
     */
    private fun setMode(appOps: AppOpsManager, op: String, uid: Int, pkg: String, mode: Int): Boolean {
        val m = runCatching {
            AppOpsManager::class.java.getMethod(
                "setMode",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (m == null) {
            Logx.d(LogTags.WEATHER, "AppOps setMode 方法不可用（隐藏 API 未放行）", "op" to op)
            return false
        }
        val ok = runCatching { m.invoke(appOps, op, uid, pkg, mode); true }.getOrDefault(false)
        if (!ok) {
            Logx.w(LogTags.WEATHER, "AppOps setMode 调用失败", "op" to op, "uid" to uid)
        }
        return ok
    }

    // ------------------------------------------------------------------ 录音（频谱采集用）

    /**
     * 录音权限（`RECORD_AUDIO`）当前能不能用。
     *
     * 抓**别的 App** 的音频输出必须要它（`Visualizer` 挂在别人会话上的前提）。
     *
     * 和定位一样是**两层**：permission grant + AppOps mode。
     * system app 装在 `/system/priv-app` 且平台签名时，权限在 privapp 白名单里就给到了；
     * 但 AppOps 那一层仍可能是 `MODE_IGNORED` —— 所以两层都要查。
     */
    fun hasRecordAudioAppOp(context: Context): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return true
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(OP_RECORD_AUDIO, Process.myUid(), context.packageName)
        }.getOrNull()
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 用 `SET_APPOP` 特权直接把本应用的录音 AppOps 打开（system app 才行）。
     *
     * @return true = 已成功打开
     */
    fun tryGrantRecordAudioAppOps(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val uid = Process.myUid()
        val pkg = context.packageName
        val mode = runCatching { appOps.unsafeCheckOpNoThrow(OP_RECORD_AUDIO, uid, pkg) }.getOrNull()
            ?: return false
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        val ok = setMode(appOps, OP_RECORD_AUDIO, uid, pkg, AppOpsManager.MODE_ALLOWED)
        if (ok) {
            Logx.i(
                LogTags.AMBIENT, "已直接打开录音 AppOps",
                "op" to OP_RECORD_AUDIO, "from" to modeName(mode), "to" to "allow",
            )
        }
        return ok
    }

    /**
     * 定位状态的一行诊断。
     *
     * 一次把「permission grant / AppOps mode / 全局位置开关 / provider 启用情况 / uid」全打出来 ——
     * 真机上「拿不到位置」的原因有好几种，这一行能直接分辨。
     */
    fun describeLocationState(context: Context): String {
        val sb = StringBuilder()
        // 1) uid（是不是真 system app）
        sb.append("uid=").append(Process.myUid())
        sb.append(" systemApp=").append(Process.myUid() == 1000)
        // 2) permission grant
        sb.append(" permFine=").append(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
        sb.append(" permCoarse=").append(
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
        // 3) AppOps（真正拦人的那一层）
        sb.append(" appOpsFine=").append(appOpsModeName(context, OP_FINE))
        sb.append(" appOpsCoarse=").append(appOpsModeName(context, OP_COARSE))
        // 4) 全局位置开关（Android 9 有效）
        sb.append(" locationMode=").append(globalLocationMode(context))
        // 5) provider 启用情况
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            val all = runCatching { lm.getProviders(false) }.getOrDefault(emptyList())
            sb.append(" providersEnabled=").append(enabled.joinToString(","))
            sb.append(" providersAll=").append(all.joinToString(","))
        }
        return sb.toString()
    }

    /**
     * AppOps 的模式名（`allow` / `ignore` / `foreground` / `errored` / `default`）。
     *
     * op 用**字符串名**（公开 API；`OP_FINE_LOCATION` 那些 int 常量是 @hide 的）。
     */
    fun appOpsModeName(context: Context, op: String): String {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return "无AppOps服务"
        val uid = Process.myUid()
        val pkg = context.packageName
        // `unsafeCheckOpNoThrow` 不抛异常，但调用者没有 GET_APP_OPS_STATS 时会返回 MODE_ERRORED。
        val mode = runCatching { appOps.unsafeCheckOpNoThrow(op, uid, pkg) }.getOrNull()
            ?: return "读不到"
        return modeName(mode)
    }

    /** 模式值 → 名字。 */
    fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "errored"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        AppOpsManager.MODE_DEFAULT -> "default"
        else -> "mode$mode"
    }

    /** AppOps 的 op 名字（诊断用）。 */
    fun opName(op: String): String = op.removePrefix("android:")

    /**
     * 全局位置开关（`Settings.Secure.LOCATION_MODE`）。
     *
     * Android 9 上有效（API 29 之后这个键被废弃，但车机是 API 28）。
     * 值：0=off / 1=sensors only / 2=battery saving / 3=high accuracy。
     */
    fun globalLocationMode(context: Context): Int = try {
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF,
        )
    } catch (t: Throwable) {
        -1
    }
}
