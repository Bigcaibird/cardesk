package com.cardesk.launcher.core.dock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.cardesk.launcher.core.SystemOps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生导航预留区（RRO 资源覆盖包）的安装/启用/还原。
 *
 * 第八十七轮（参考 uni-launcher `overlay/NavigationInsets.java`）：
 * cardesk 的 Dish 栏只有「进程内 View」一种形态，隐藏原车 Dock 后其它应用底部
 * 就是一片空白，而且自绘 Dock 也只在 HOME 里存在。要在别的应用里显示 Dock，
 * 必须让**系统自己**在底部留出 112px，然后把全局 Dock 窗口画进这条预留带：
 *
 * 1. `insets/` 里是一个没有任何代码的资源覆盖包（RRO），只改
 *    `navigation_bar_height*` 四个 dimen 到 112dp —— 于是所有普通应用的窗口
 *    都会在底部留出 112px（=1920 宽下 Dock 104 + 底边距 8）；
 * 2. 有了这条带子，全局 Dock 才能「占住系统 Dock 的位置」而不是盖在应用内容上；
 * 3. 覆盖包目标 `android`（framework-res），需要**平台签名**才能装/启，
 *    所以能力不足时全部降级（返回 false，由调用方退回 APPLICATION_OVERLAY）。
 *
 * ## 真机/模拟器的实测结论（第八十七轮，务必先读）
 *
 * **RRO 只能当可选增强，绝不能成为功能前置条件。** 2026-10-31 在模拟器
 * （Android 10 / SDK 29 / userdebug / test-keys）上用平台测试密钥签名的覆盖包：
 * `adb install` 成功、`cmd overlay list` 显示 `[x]`（`STATE_ENABLED`，target=android），
 * 但 `dumpsys window` 里 NavigationBar0 的 surface 恒为 `1920 x 56`；
 * 补上 sw600dp/sw720dp/sw900dp 四套 qualifier、`adb reboot` 之后依旧 56。
 * `adb remount` 失败（镜像没有 overlayfs），无法验证「预装进 /system/overlay」。
 * 结论：放在 /data 的 overlay 改不了 framework-res 的尺寸，AOSP 需要把它预装到
 * 系统分区才生效；真车上允不允许由 ROM 决定（UNI 的车机允许）。
 *
 * 因此调用方必须按 [applied] 分支：false 时全局 Dock 照常工作，只是「遮住」
 * 原车 Dock 而不是「顶替」它（高度差取决于原车 Dock 实际高度，见设计契约）。
 *
 * 全部反射 + `runCatching`：普通签名构建（无 CHANGE_OVERLAY_PACKAGES）不能崩，
 * 拿不到系统服务 / 权限时统一返回 false，由调用方降级。
 *
 * 公开签名由第八十七轮设计契约固定（`dock` 工作流已按此接线），实现内部可以调整。
 */
object NavInsets {

    /** 覆盖包包名（与 `insets/AndroidManifest.xml` 的 package 必须一致）。 */
    const val PACKAGE = "com.ecarx.cardesk.insets"

    /** 打包进 assets 的覆盖包文件名。 */
    const val ASSET = "cardesk-nav-insets.apk"

    /** 预留带高度（1920×1080@160dpi 下 = 112px = Dock 104 + 底边距 8）。 */
    const val HEIGHT_DP = 112

    private const val PREFS = "cardesk"
    private const val KEY_ORIGINAL = "nav_insets_original"
    private const val KEY_APPLIED = "nav_insets_applied"

    /** 管理 RRO 需要的能力（声明在 Manifest，系统变体才真正授予）。 */
    private const val PERMISSION_OVERLAY = "android.permission.CHANGE_OVERLAY_PACKAGES"
    private const val PERMISSION_STATUS_BAR = "android.permission.STATUS_BAR_SERVICE"

    /** 静默安装覆盖包后，等待 PackageInstaller 回执的最长时间。 */
    private const val INSTALL_TIMEOUT_MS = 30_000L

    // ---------------------------------------------------------------- 状态查询

    /**
     * 覆盖包当前是否已启用（= 预留区生效）。
     *
     * 先看自己记的 `nav_insets_applied`，**再回读系统里的真实状态**：
     * 车机重启、别的应用把它关掉、或系统回滚时，单靠 prefs 会误报 true，
     * 调用方就会用「预留区已存在」的假设去摆全局窗口，结果 Dock 盖住应用内容。
     * 读不到系统状态（没有 IOverlayManager）时才相信 prefs。
     */
    fun applied(context: Context): Boolean {
        val recorded = runCatching { prefs(context).getBoolean(KEY_APPLIED, false) }.getOrDefault(false)
        if (!recorded) return false
        val actual = runCatching { overlayManager()?.let { readEnabled(it, currentUser()) } }.getOrNull()
        if (actual != null && actual != recorded) {
            // 排障场景：prefs 与系统状态不一致 —— 以系统为准，并留下两边的值
            Logx.w(
                LogTags.SYS,
                "预留区状态不一致：以系统为准",
                "prefs" to recorded,
                "system" to actual,
            )
        }
        return actual ?: recorded
    }

    /** 覆盖包是否已安装。 */
    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 有没有能力管理覆盖包：CHANGE_OVERLAY_PACKAGES + STATUS_BAR_SERVICE + 包已安装 + 能拿到 overlay 服务。 */
    fun supported(context: Context): Boolean = runCatching {
        val missing = LinkedHashSet<String>()
        if (!granted(context, PERMISSION_OVERLAY)) missing.add(PERMISSION_OVERLAY)
        if (!granted(context, PERMISSION_STATUS_BAR)) missing.add(PERMISSION_STATUS_BAR)
        val hasService = overlayManager() != null
        val ok = installed(context) && missing.isEmpty() && hasService
        // 排障场景：「预留区没生效」时先看这台车有没有能力，缺哪一项
        Logx.d(
            LogTags.SYS,
            "预留区能力检测",
            "installed" to installed(context),
            "overlayService" to hasService,
            "missingPermissions" to missing.joinToString(","),
            "supported" to ok,
        )
        ok
    }.getOrDefault(false)

    /**
     * 缺包且有 INSTALL_PACKAGES 时，把 assets 里的覆盖包静默装进系统，
     * 然后回调 [onReady]。任何一步失败都直接回调（调用方自己再判 [supported]）。
     *
     * 为什么不用 [SystemOps.installApk]：那个接口只负责「提交安装」，
     * 结果走 `InstallResultReceiver` 弹 Toast，调用方拿不到「装完了」的回调；
     * 这里必须在安装回执之后才能判断 [supported]，所以自己建会话、
     * 用私有 action 的广播 PendingIntent 接回执（与参考实现一致）。
     *
     * 回调用 AtomicBoolean 保证只触发一次：超时兜底与回执广播可能同时到达，
     * 调用方（全局 Dock 服务）会把它当成「可以开始」的信号，重复回调会重复建窗口。
     */
    fun prepare(context: Context, onReady: () -> Unit) {
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) onReady()
        }

        if (installed(context)) {
            Logx.d(LogTags.SYS, "预留区：覆盖包已安装，跳过静默安装")
            finish()
            return
        }
        if (!SystemOps.isAvailable(context, SystemOps.Capability.SILENT_INSTALL)) {
            // 普通构建没有 INSTALL_PACKAGES：不尝试装，直接让调用方按「无预留区」降级。
            Logx.w(LogTags.SYS, "预留区：无 INSTALL_PACKAGES，跳过覆盖包安装")
            finish()
            return
        }

        val action = "$PACKAGE.INSETS_INSTALLED"
        val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
        var sessionId = -1
        var receiver: BroadcastReceiver? = null

        fun cleanup() {
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
            handler?.removeCallbacksAndMessages(null)
        }

        val timeout = Runnable {
            Logx.w(LogTags.SYS, "预留区：等待覆盖包安装回执超时", "timeoutMs" to INSTALL_TIMEOUT_MS)
            cleanup()
            finish()
        }

        val ok = runCatching {
            val installReceiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: -1
                    val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        Logx.i(LogTags.SYS, "预留区：覆盖包安装成功", "package" to PACKAGE)
                    } else {
                        Logx.w(
                            LogTags.SYS,
                            "预留区：覆盖包安装失败",
                            "status" to status,
                            "message" to message,
                        )
                    }
                    cleanup()
                    finish()
                }
            }
            receiver = installReceiver
            context.registerReceiver(installReceiver, IntentFilter(action))

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                context.assets.open(ASSET).use { input ->
                    session.openWrite("base.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                session.commit(callback.intentSender)
            }
            true
        }.getOrElse { error ->
            // 排障场景：覆盖包装不上 —— assets 缺失 / 会话创建失败 / 无权限
            Logx.w(LogTags.SYS, "预留区：覆盖包静默安装失败", error, "asset" to ASSET)
            false
        }

        if (!ok) {
            if (sessionId >= 0) {
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            cleanup()
            finish()
            return
        }

        Logx.i(
            LogTags.SYS,
            "预留区：覆盖包安装已提交，等待回执",
            "package" to PACKAGE,
            "asset" to ASSET,
        )
        handler?.postDelayed(timeout, INSTALL_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- 启用 / 还原

    /** 启用覆盖包（记下启用前的原状态以便 [restore]）。返回本次是否启用成功。 */
    fun activate(context: Context): Boolean {
        if (!supported(context)) {
            Logx.w(LogTags.SYS, "预留区：能力不足，不启用覆盖包")
            return false
        }
        val user = currentUser()
        val prefs = prefs(context)
        val ok = runCatching {
            val manager = overlayManager() ?: return@runCatching false
            val wasEnabled = readEnabled(manager, user)
            if (wasEnabled == null) {
                // 包已装但读不到 OverlayInfo（ROM 差异 / 权限问题）：不冒险改系统状态
                Logx.w(LogTags.SYS, "预留区：读不到覆盖包状态，放弃启用", "package" to PACKAGE)
                return@runCatching false
            }
            // 只记第一次进入前的状态：重复 activate 不能把「已启用」当成原状态，
            // 否则 restore 会把覆盖包永远留在启用状态。
            if (prefs.getString(KEY_ORIGINAL, null) == null) {
                prefs.edit().putString(KEY_ORIGINAL, wasEnabled.toString()).apply()
            }
            if (wasEnabled) {
                prefs.edit().putBoolean(KEY_APPLIED, true).apply()
                Logx.i(LogTags.SYS, "预留区：覆盖包原本已启用，直接标记生效")
                return@runCatching true
            }
            manager.overlayCall("setHighestPriority", PACKAGE, user)
            manager.overlayCall("setEnabled", PACKAGE, true, user)
            // setEnabled 在部分 ROM 上异步落盘；回读不到就相信调用成功（不能让
            // 状态回读的偶发失败掩盖「其实已经启用」）。
            val enabled = readEnabled(manager, user) ?: true
            if (enabled) {
                prefs.edit().putBoolean(KEY_APPLIED, true).apply()
                refreshSystemNavigationMetrics(context, user)
            }
            enabled
        }.getOrElse { error ->
            // 排障场景：覆盖包启用失败 —— 权限 / 反射 / ROM 差异
            Logx.w(LogTags.SYS, "预留区：启用覆盖包失败", error, "package" to PACKAGE)
            false
        }
        if (ok) {
            Logx.i(LogTags.SYS, "预留区：覆盖包已启用", "package" to PACKAGE, "heightDp" to HEIGHT_DP)
        }
        return ok
    }

    /** 恢复启用覆盖包之前的原状态。 */
    fun restore(context: Context): Boolean {
        val prefs = prefs(context)
        val recordedOriginal = prefs.getString(KEY_ORIGINAL, null)?.toBoolean() ?: false
        val wasApplied = prefs.getBoolean(KEY_APPLIED, false)
        val user = currentUser()
        var restored = false
        val result = runCatching {
            val manager = overlayManager()
            if (manager == null) {
                // 服务拿不到：包没装过就等于恢复成功；装过则说明系统状态没法改回去
                if (installed(context)) {
                    Logx.w(LogTags.SYS, "预留区：拿不到 overlay 服务，无法恢复覆盖包状态")
                    return@runCatching false
                }
                restored = true
                return@runCatching true
            }
            if (!installed(context)) {
                restored = true
                return@runCatching true
            }
            manager.overlayCall("setEnabled", PACKAGE, recordedOriginal, user)
            // 回读确认真的改回去了；读不到（null）时按调用成功处理
            val now = readEnabled(manager, user)
            restored = now == null || now == recordedOriginal
            if (!restored) {
                Logx.w(
                    LogTags.SYS,
                    "预留区：恢复后状态与原状态不一致",
                    "expect" to recordedOriginal,
                    "actual" to now,
                )
            }
            restored
        }.getOrElse { error ->
            // 排障场景：退出全局 Dock 时覆盖包没还原 —— 会不会是权限/反射问题
            Logx.w(LogTags.SYS, "预留区：恢复覆盖包状态失败", error, "package" to PACKAGE)
            false
        }

        if (restored) {
            prefs.edit().remove(KEY_ORIGINAL).putBoolean(KEY_APPLIED, false).apply()
            refreshSystemNavigationMetrics(context, user)
        }
        if (result) {
            Logx.i(
                LogTags.SYS,
                "预留区：覆盖包已恢复",
                "original" to recordedOriginal,
                "wasApplied" to wasApplied,
            )
        }
        return result
    }

    // ---------------------------------------------------------------- 诊断

    /** 诊断用：设置页/诊断包展示覆盖包状态。 */
    fun snapshot(context: Context): Map<String, Any?> {
        val user = currentUser()
        val manager = runCatching { overlayManager() }.getOrNull()
        return linkedMapOf(
            "package" to PACKAGE,
            "asset" to ASSET,
            "assetBundled" to assetBundled(context),
            "heightDp" to HEIGHT_DP,
            "installed" to installed(context),
            "supported" to supported(context),
            "applied" to applied(context),
            "original" to runCatching { prefs(context).getString(KEY_ORIGINAL, null) }.getOrNull(),
            "overlayService" to (manager != null),
            "overlayEnabled" to runCatching { manager?.let { readEnabled(it, user) } }.getOrNull(),
            "user" to user,
        )
    }

    // ---------------------------------------------------------------- 内部实现

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun granted(context: Context, permission: String): Boolean =
        runCatching { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun assetBundled(context: Context): Boolean = runCatching {
        context.assets.open(ASSET).use { true }
    }.getOrDefault(false)

    /** 覆盖包所在用户：系统服务 / 车机 launcher 一般跑在 uid 1000，user=0。 */
    private fun currentUser(): Int = runCatching { Process.myUid() / 100000 }.getOrDefault(0)

    /**
     * 反射拿 `IOverlayManager`。
     *
     * `overlay` 服务是隐藏服务，`ServiceManager` / `IOverlayManager$Stub` 都不在
     * 公开 SDK 里（compileSdk 29 直接引用编不过），所以全程反射；
     * 拿不到（普通构建 / 非 system uid）返回 null，调用方全部按「无预留区」降级。
     */
    private fun overlayManager(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "overlay") as? android.os.IBinder
            ?: return@runCatching null
        Class.forName("android.content.om.IOverlayManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
    }.getOrNull()

    /** 调 `IOverlayManager` 上的方法；方法不存在时抛异常，让外层 runCatching 记日志。 */
    private fun Any.overlayCall(name: String, vararg args: Any?): Any? {
        val method = javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterTypes.size == args.size &&
                candidate.parameterTypes.zip(args).all { (type, arg) ->
                    arg == null || type.isAssignableFrom(arg.javaClass) ||
                        (type == Int::class.javaPrimitiveType && arg is Int) ||
                        (type == Boolean::class.javaPrimitiveType && arg is Boolean)
                }
        } ?: throw NoSuchMethodException("IOverlayManager.$name")
        return method.invoke(this, *args)
    }

    /** `IOverlayManager.getOverlayInfo(package, user)` → `isEnabled`；读不到返回 null。 */
    private fun readEnabled(manager: Any, user: Int): Boolean? = runCatching {
        val info = manager.overlayCall("getOverlayInfo", PACKAGE, user) ?: return@runCatching null
        runCatching { info.javaClass.getField("isEnabled").getBoolean(info) }
            .recoverCatching { info.javaClass.getMethod("isEnabled").invoke(info) as? Boolean ?: false }
            .getOrNull()
    }.getOrNull()

    /**
     * 让系统重新测量导航栏（参考实现 `refreshPolicy`）。
     *
     * Android 9 会把 policy 尺寸缓存起来，overlay 改了 dimen 之后窗口不一定立刻
     * 重新布局；参考实现靠「刷新一次强制显示密度」触发 WMS 重新计算
     * （`display_density_forced` 非 0 时先读基准密度再写回，否则 clear）。
     * 全程 runCatching：这只是锦上添花，失败不能影响启用/还原的结果。
     */
    private fun refreshSystemNavigationMetrics(context: Context, user: Int) {
        runCatching {
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                runCatching { forceDisplayMetricsRefresh(context, user) }
                    .onFailure { Logx.w(LogTags.SYS, "预留区：刷新显示尺寸失败（忽略）", it) }
            }, 250)
        }.onFailure { Logx.w(LogTags.SYS, "预留区：无法安排显示尺寸刷新（忽略）", it) }
    }

    private fun forceDisplayMetricsRefresh(context: Context, user: Int) {
        val windowManager = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null) ?: return
        val forcedDensity = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                "display_density_forced",
                0,
            )
        }.getOrDefault(0)
        if (forcedDensity != 0) {
            val baseDensity = windowManager.javaClass
                .getMethod("getBaseDisplayDensity", Int::class.javaPrimitiveType)
                .invoke(windowManager, 0) as? Int ?: return
            windowManager.javaClass
                .getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(windowManager, 0, baseDensity, user)
            Logx.d(LogTags.SYS, "预留区：重置强制显示密度以触发重新布局", "density" to baseDensity)
        } else {
            windowManager.javaClass
                .getMethod(
                    "clearForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(windowManager, 0, user)
            Logx.d(LogTags.SYS, "预留区：清除强制显示密度以触发重新布局", "user" to user)
        }
    }
}
