package com.cardesk.launcher.core.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 通知使用权（Notification listener access）判定与引导。
 *
 * ## 两条路
 * - 没授权就跳系统设置页：
 *   `startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))`；
 * - 已授权就读白名单：
 *   `Settings.Secure.getString(contentResolver, "enabled_notification_listeners")`，
 *   看里面有没有本包的通知监听组件（按「包名/类名」精确串）。
 *
 * ## 为什么必须单独判定
 * AOSP 实测（API 29 模拟器，`cmd notification disallow_listener` 之后）：
 * `MediaSessionManager.getActiveSessions()` 会抛 `SecurityException`
 * （"Missing permission to control media."），而且 system uid（uid 1000）**也不豁免**。
 * 我们把异常兜成空列表，所以「扫不到会话」这件事本身分不清「没授权」和「当前没音乐在播」，
 * 必须单独看通知使用权状态。
 *
 * ## 判定用两个信号
 * 1. **白名单串**（同步、可立即读到）：`enabled_notification_listeners` 里
 *    命中我们的候选组件。替换版有**两个**候选组件，见 [candidateComponents]。
 * 2. **监听服务是否被系统绑定**（功能事实）：系统只有把本应用写进白名单，
 *    才会绑定 `NotificationListenerService`。车机 ROM 有可能按**包级**授权
 *    （白名单串里没有我们的组件，但服务照样被绑定），这时信号 1 会漏判，
 *    信号 2 才是准的。
 *
 * 任一信号成立即视为「已授予」。
 */
object NotificationAccess {

    /** 系统「通知使用权」设置页的 action（用的是同一个字符串常量）。 */
    const val SETTINGS_ACTION = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"

    /** 系统保存通知使用权白名单的 Secure 设置键。 */
    const val SECURE_KEY = "enabled_notification_listeners"

    /**
     * 替换版在 system 变体里额外注册的**与同名**的通知监听服务。
     *
     * 车机出厂时会在系统设置里预置一条白名单条目，指向
     * `com.ecarx.qq.tool/com.ecarx.qq.tool.service.MusicBridgeService`。
     * 这条条目是按「包名 + 类名」精确匹配的：替换版如果换了自己的类名，
     * 这条预置条目就成了死条目（系统根本不会来绑定我们），
     * 媒体会话也就拿不到。所以 system 变体再注册一个**同名的**服务，
     * 让车机预置的那条白名单重新生效。
     *
     * 该类只在 system 变体（`src/system/`）里存在，debug 变体运行时探测不到。
     */
    const val ALIAS_CLASS = "com.ecarx.qq.tool.service.MusicBridgeService"

    /** 诊断展示时白名单原文的截断长度。 */
    private const val RAW_PREVIEW_LIMIT = 160

    /** 白名单条目的分隔符（`:` 是标准写法，其余为个别 ROM 的变体）。 */
    private val SEPARATORS = Regex("[;:\\s]+")

    /**
     * 系统是否已经把通知监听服务绑定到我们（= 功能上确实拿到了通知使用权）。
     *
     * 由 [MusicSessionTracker] 在 `onListenerConnected` / `onListenerDisconnected` 时更新。
     */
    @Volatile
    var listenerConnected: Boolean = false
        private set

    /** 通知监听服务连上/断开时更新功能事实。 */
    fun markListenerConnected(connected: Boolean) {
        listenerConnected = connected
    }

    /**
     * 候选通知监听组件（扁平化「包名/类名」）。
     *
     * - 主服务：所有变体都有；
     * - 同名别名服务：只有 system 变体有（运行期探测类是否存在）。
     */
    fun candidateComponents(context: Context): List<String> {
        val pkg = context.packageName
        val out = ArrayList<String>(2)
        out.add(ComponentName(pkg, MediaNotificationListener::class.java.name).flattenToString())
        if (aliasClassPresent()) {
            out.add(ComponentName(pkg, ALIAS_CLASS).flattenToString())
        }
        return out
    }

    /** 同名别名服务这个类在当前 APK 里是否存在（system 变体才有）。 */
    fun aliasClassPresent(): Boolean = try {
        NotificationAccess::class.java.classLoader?.loadClass(ALIAS_CLASS) != null
    } catch (t: Throwable) {
        false
    }

    /**
     * 从白名单原文里找出命中的候选组件。
     *
     * 纯函数（不碰 Android API），方便单测。
     *
     * 匹配规则：**按条目精确相等**。
     * - 白名单是用分隔符隔开的一串「包名/类名」，逐条比对；
     * - 分隔符兼容 `:` `;` 和空白 —— 个别 ROM 写出来的分隔符不统一，
     *   这里放宽切分，但比对本身仍是精确相等，
     *   避免把 `…MusicBridgeServiceExtra` 误当成 `…MusicBridgeService`。
     *
     * 比 `string.contains(flattenToString)` 更精确；车机出厂预置的那条白名单
     * 本来就是「包名/类名」精确串，两者结论一致。
     */
    fun matchedComponents(enabled: String?, candidates: List<String>): List<String> {
        if (enabled.isNullOrEmpty() || candidates.isEmpty()) return emptyList()
        val entries = enabled.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return emptyList()
        return candidates.filter { c -> entries.any { it == c } }
    }

    /** 读系统白名单原文（读不到返回空串）。 */
    fun enabledRaw(context: Context): String = runCatching {
        Settings.Secure.getString(context.contentResolver, SECURE_KEY).orEmpty()
    }.getOrDefault("")

    /** 白名单命中的组件（空 = 没命中）。 */
    fun grantedByWhitelist(context: Context): List<String> =
        matchedComponents(enabledRaw(context), candidateComponents(context))

    /**
     * 白名单命中的**第一个**候选组件（解析回 ComponentName）。
     *
     * 这个组件就是可以直接拿去调
     * 那个 —— AOSP 的 `MediaSessionManager.getActiveSessions()` 文档写明
     * 「you must pass the ComponentName of your enabled listener」，
     * 权限校验是**按组件精确判定**的：传一个没被授权的组件进去，
     * 服务端会抛 `SecurityException("Missing permission to control media.")`。
     */
    fun grantedComponent(context: Context): ComponentName? {
        val hit = grantedByWhitelist(context).firstOrNull() ?: return null
        val slash = hit.indexOf('/')
        if (slash <= 0 || slash == hit.length - 1) return null
        return ComponentName(hit.substring(0, slash), hit.substring(slash + 1))
    }

    /**
     * 是否已获得通知使用权。
     *
     * 白名单命中，或系统已经把监听服务绑定到我们，都算已获得。
     */
    fun isGranted(context: Context): Boolean {
        if (grantedByWhitelist(context).isNotEmpty()) return true
        return listenerConnected
    }

    /**
     * **口径**的判定组件。
     *
     * 查的是
     * `com.ecarx.qq.tool.service.MusicBridgeService` 这一个组件（我们的 system 变体
     * 用的就是同一个类名，见 [ALIAS_CLASS]。
     *
     * - system 变体：别名服务在 APK 里 → 查它，与**逐字一致**
     * - debug 变体：别名服务不存在 → 退回查自己的主监听服务，
     *   否则 debug 包永远「未授予」，一启动就弹设置页，没法在模拟器上用。
     */
    fun referenceListenerComponent(context: Context): String {
        val cls = if (aliasClassPresent()) ALIAS_CLASS else MediaNotificationListener::class.java.name
        return ComponentName(context.packageName, cls).flattenToString()
    }

    /**
     * **口径**：系统白名单里有没有那个监听组件。
     *
     * 与 [isGranted] 的区别：后者还把「监听服务已被系统绑定」算作已授予，
     * 用于判断「功能上能不能拿到会话」；
     * 而引导只看白名单串 —— 组件不在白名单里就跳设置页。
     */
    fun isReferenceGranted(context: Context): Boolean =
        isReferenceGranted(enabledRaw(context), referenceListenerComponent(context))

    /** [isReferenceGranted] 的纯函数版本（便于单测）。 */
    fun isReferenceGranted(enabled: String?, component: String): Boolean =
        matchedComponents(enabled, listOf(component)).isNotEmpty()

    /**
     * 是否持有 `MEDIA_CONTENT_CONTROL` 权限。
     *
     * 也声明了它。AOSP 的判定是
     * 持有该权限 **或** 传入的组件是已启用的通知监听器 —— 二选一即可。
     *
     * 这是 `signature|privileged` 权限：平台签名的 system 包自动获得；
     * 装成普通 App（车机上的替换包就是这样）时拿不到，
     * 会话枚举只能走通知监听器那条路。
     */
    fun hasMediaContentControl(context: Context): Boolean = try {
        context.checkPermission(
            android.Manifest.permission.MEDIA_CONTENT_CONTROL,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /**
     * 媒体会话枚举**有没有可用通路**。
     *
     * 没有通路时，系统会拒绝 （抛 SecurityException），
     * 音乐区就一直是「暂无播放」—— 这种情况下才需要引导用户去授权。
     */
    fun sessionEnumerationAvailable(context: Context): Boolean =
        hasMediaContentControl(context) || isGranted(context)

    /**
     * 跳到系统的通知使用权设置页。
     *
     * 返回是否真的跳出去了：车机上如果被裁掉这个设置页，
     * `startActivity` 会抛用 try/catch 吞掉
     * 这里把结果返回给调用方，让调用方能记日志。
     */
    fun openSettings(context: Context): Boolean {
        val intent = Intent(SETTINGS_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Logx.w(
                LogTags.MEDIA, "打开通知使用权设置页失败（系统可能裁掉了该页面）",
                "action" to SETTINGS_ACTION, "error" to (t.message ?: t.javaClass.simpleName),
            )
            false
        }
    }

    /** 诊断用的一行状态描述。 */
    fun describe(context: Context): String {
        val hit = grantedByWhitelist(context)
        if (hit.isNotEmpty()) {
            return "已授予（命中 ${hit.joinToString(", ")}）"
        }
        if (listenerConnected) {
            return "已授予（通知监听服务已被系统绑定）"
        }
        val raw = enabledRaw(context)
        val shown = if (raw.length > RAW_PREVIEW_LIMIT) raw.take(RAW_PREVIEW_LIMIT) + "…" else raw
        return "未授予（系统白名单：${if (shown.isEmpty()) "空" else shown}）"
    }

    /** 诊断用：会话枚举通路的完整描述。 */
    fun describeEnumeration(context: Context): String {
        val mcc = hasMediaContentControl(context)
        return describe(context) +
            "；白名单组件=" + (if (isReferenceGranted(context)) "已授予" else "未授予") +
            "；MEDIA_CONTENT_CONTROL=" + (if (mcc) "已授予" else "未授予") +
            "；会话枚举=" + (if (sessionEnumerationAvailable(context)) "可用" else "不可用")
    }
}
