package com.cardesk.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.cardesk.launcher.core.OemKeyProtocol
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 第三百一十五轮：**方控按键广播接收端**。
 *
 * ## 为什么必须是广播接收器
 *
 * 车机的方向盘按键**不走 KeyEvent**：系统把它做成有序广播发给应用。
 * 之前 cardesk 只在 `MainActivity.dispatchKeyEvent` 里收按键 ⇒
 * 只有桌面在前台、且拿到按键焦点时才有反应；用户在导航/音乐里按方控，
 * 桌面完全收不到（这就是「按键增强有问题」的根因）。
 *
 * 实车实测（`tools/handoff-r224.md`）：语音键按下时系统发
 * `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT`，带
 * `ecarx.extra.ECARX_KEY_EVENT_TYPE=200231` 与
 * `ecarx.extra.ECARX_KEY_ACTION_TYPE`（0 单击 / 1 长按）。
 *
 * ## 为什么 priority 拉满
 *
 * 系统语音助手也监听这些广播；我们要在它前面拿到事件并决定是否吃掉。
 * 吃掉 = `abortBroadcast()`（只有有序广播能中止）；放行 = 什么都不做，
 * 系统继续唤醒语音助手。
 *
 * ## 吃什么、不吃什么
 *
 * | 事件 | 处理 |
 * |---|---|
 * | 语音键，配了动作 | 吃掉（执行我们的动作） |
 * | 语音键，没配动作 | 不吃（退回系统语音助手） |
 * | 语音键，配「唤起语音助手」 | 吃掉，然后发车机语音广播（[OemKeyProtocol.ACTION_VOICE_LAUNCH]） |
 * | `MEDIA_BUTTON` 的上一曲/下一曲 | **一律不吃**（只处理语音键） |
 *
 * 后两条都是本轮修的：第218轮的实现会把媒体键短按一起吞掉，导致方控切歌失灵。
 */
class OemKeyReceiver(
    /** true = 动态注册的实例（进程内），false = 清单里的实例。 */
    private val dynamic: Boolean = false,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        val event = parse(intent, action) ?: return
        val handled = OemKeyRuntime.dispatch(
            context = context.applicationContext ?: context,
            event = event,
            path = if (dynamic) OemKeyRuntime.PATH_DYNAMIC else OemKeyRuntime.PATH_MANIFEST,
        )
        if (!handled) return

        // 只有有序广播能中止；普通广播会抛异常 ⇒ 吞掉。
        runCatching { abortBroadcast() }.onFailure {
            Logx.d(LogTags.KEYS, "按键增强：不是有序广播，无法中止", "action" to action)
        }
        Logx.i(
            LogTags.KEYS,
            "按键增强：方控广播已处理",
            "action" to action,
            "keyCode" to event.keyCode,
            "press" to event.press.name,
        )
    }

    /** 把广播解析成一次按键事件；不是我们的就不返回。 */
    private fun parse(intent: Intent, action: String): OemKeyProtocol.OemKeyEvent? {
        if (action == OemKeyProtocol.ACTION_MEDIA_BUTTON) {
            // 媒体键广播只用来补语音键（部分车机把语音键塞进 MEDIA_BUTTON）：
            // 上一曲/下一曲一律放行，绝不拦。
            val event: KeyEvent? = intent.getParcelableExtra(OemKeyProtocol.EXTRA_KEY_EVENT)
            if (event == null) {
                Logx.d(LogTags.KEYS, "按键增强：媒体键广播没有 KeyEvent")
                return null
            }
            if (!OemKeyProtocol.isVoiceKey(event.keyCode)) return null
            if (event.action != KeyEvent.ACTION_UP) return null
            Logx.d(
                LogTags.KEYS,
                "按键增强：MEDIA_BUTTON 里的语音键",
                "keyCode" to event.keyCode,
                "keyName" to OemKeyProtocol.keyName(event.keyCode),
            )
            return OemKeyProtocol.OemKeyEvent(
                keyCode = event.keyCode,
                press = OemKeyProtocol.Press.CLICK,
                sourceAction = action,
            )
        }

        if (!OemKeyProtocol.isOemAction(action)) return null

        val keyCode = intent.getIntExtra(OemKeyProtocol.EXTRA_KEY_TYPE, 0)
        val actionType = intent.getIntExtra(OemKeyProtocol.EXTRA_KEY_ACTION, 0)
        val parsed = OemKeyProtocol.parse(action, keyCode, actionType)
        if (parsed == null) {
            // 键码为 0 / 不在协议里：记一笔，实车排障就靠它看真实码。
            Logx.i(
                LogTags.KEYS,
                "按键增强：方控广播解析为空",
                "action" to action,
                "keyCode" to keyCode,
                "actionType" to actionType,
            )
        }
        return parsed
    }
}
