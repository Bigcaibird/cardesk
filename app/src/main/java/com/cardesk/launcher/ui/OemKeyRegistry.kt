package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.cardesk.launcher.core.OemKeyProtocol
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 第三百一十五轮：**方控广播的动态注册**。
 *
 * ## 为什么清单里注册了还要再动态注册一遍
 *
 * Android 8.0 起，**隐式广播**（只有 action、没点名包名的广播）不再投递给
 * 后台应用的清单接收器 —— 系统会直接打
 * `W BroadcastQueue: Background execution not allowed`。
 * 车机的方控广播就是隐式广播，而桌面进程在导航/音乐里通常是后台态。
 *
 * 两条通路各有覆盖范围，缺一不可：
 *
 * | 通路 | 什么时候能收到 |
 * |---|---|
 * | 清单接收器（`AndroidManifest.xml`） | 发送方带了 `FLAG_RECEIVER_INCLUDE_BACKGROUND`（车机固件发方控事件一般会带），进程冷启动也能拉起 |
 * | 动态注册（本类） | 进程还活着就一定能收到（前台应用不受隐式广播限制），不依赖发送方标志 |
 *
 * 两条同时收到时由 [OemKeyRuntime.dispatch] 做时间窗去重，不会执行两次。
 */
object OemKeyRegistry {

    /** 当前注册的接收器（null = 没注册）。 */
    private var registered: OemKeyReceiver? = null

    /** 动态注册（幂等）。 */
    fun start(context: Context) {
        if (registered != null) return
        val app = context.applicationContext ?: context
        val filter = IntentFilter().apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addAction(OemKeyProtocol.ACTION_MEDIA_BUTTON)
            OemKeyProtocol.OEM_ACTIONS.forEach { addAction(it) }
            // 动态注册的 priority 上限是 1000；清单里的 2147483647 会先拿到事件，
            // 这里只负责「清单通路被系统拦掉」时兜底。
            priority = 1000
        }
        val receiver = OemKeyReceiver(dynamic = true)
        runCatching { app.registerReceiver(receiver, filter) }
            .onSuccess {
                registered = receiver
                Logx.i(LogTags.KEYS, "按键增强：方控广播已动态注册")
            }
            .onFailure { Logx.w(LogTags.KEYS, "按键增强：方控广播动态注册失败", it) }
    }

    /** 取消动态注册（幂等）。 */
    fun stop(context: Context) {
        val receiver = registered ?: return
        registered = null
        val app = context.applicationContext ?: context
        runCatching { app.unregisterReceiver(receiver) }
            .onFailure { Logx.w(LogTags.KEYS, "按键增强：方控广播注销失败", it) }
    }

    /** 是否已动态注册（诊断用）。 */
    fun isRegistered(): Boolean = registered != null
}
