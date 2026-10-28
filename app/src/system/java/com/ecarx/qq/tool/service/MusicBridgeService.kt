package com.ecarx.qq.tool.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cardesk.launcher.core.media.MusicSessionTracker

/**
 * 替换版专属：**与完全同名的通知监听服务**。
 *
 * ## 为什么要有这一个
 * 车机出厂时，系统设置里的「通知使用权」白名单通常预置了一条指向条目
 *
 * `
 * com.ecarx.qq.tool/com.ecarx.qq.tool.service.MusicBridgeService
 * `
 *
 * 这条白名单是按「包名 + 类名」**精确匹配**的（见
 * 替换版虽然包名一样
 * （`com.ecarx.qq.tool`），但类名是自己的
 * `com.cardesk.launcher.core.media.MediaNotificationListener` ——
 * 那条预置条目就成了**死条目**：系统根本不会来绑定我们的服务，
 * 通知使用权拿不到， 只会静默返回空列表（实测不抛异常）。
 *
 * 所以这里再注册一个**与同名**的服务，让车机预置的那条白名单重新指向我们。
 * 两个服务共用同一个 [MusicSessionTracker]，谁先被绑定都能工作；
 * 两个都被授权时系统会各绑定一次，追踪器内部做了 attach 幂等，不会重复注册。
 *
 * ## 与系统版的差异
 * - 还有一个 `SessionBridge`（走 MediaCenter 授权通道）和 3 秒 tick；
 *   我们没有复制这些 —— 那套的注释里就写了
 *   "MediaSession only; MediaCenter auth via original accessibility"，
 *   媒体会话走 MediaSession 就够了（我们的实现本来就是纯 MediaSession 路线）。
 * - 这个类只在 system 变体（`app/src/system/`）里存在；debug 变体运行期探测不到，
 *   不会出现在候选组件列表里。
 */
class MusicBridgeService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        // 与主服务共用同一个追踪器（内部幂等，重复 start 不会出问题）
        MusicSessionTracker.get().start(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        MusicSessionTracker.get().onListenerConnected(this)
    }

    override fun onListenerDisconnected() {
        MusicSessionTracker.get().onListenerDisconnected(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        MusicSessionTracker.get().requestRescan(debounceMs = 250)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        MusicSessionTracker.get().requestRescan(debounceMs = 250)
    }
}
