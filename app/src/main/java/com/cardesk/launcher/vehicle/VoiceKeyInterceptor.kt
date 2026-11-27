package com.cardesk.launcher.vehicle

import android.content.Context
import com.cardesk.launcher.core.KeyActionResolver
import com.cardesk.launcher.core.OemKeyProtocol
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.OemKeyRuntime

/**
 * 通过进程内共享的 [AdaptapiInputHub] 实时拦截方控语音键按下/抬起，
 * 避免与 `HomeKeyInterceptor` 互相覆盖 `dcy11_input_service` 回调，
 * 并绕开 `XCInputService` 固件对 200231 快速连点的硬屏蔽。
 */
class VoiceKeyInterceptor(private val context: Context) {

    @Volatile
    private var started: Boolean = false

    private val app: Context = context.applicationContext ?: context

    private val keyListener = object : AdaptapiInputHub.KeyListener {
        override fun onKeyPressed(keyCode: Int): Boolean = handleEvent(keyCode, down = true)
        override fun onKeyReleased(keyCode: Int): Boolean = handleEvent(keyCode, down = false)
    }

    val active: Boolean
        get() = started && INTERCEPT_KEYS.any { AdaptapiInputHub.isKeyAccepted(it) }

    val statusText: String
        get() {
            val accepted = INTERCEPT_KEYS.filter { AdaptapiInputHub.isKeyAccepted(it) }
            val fail = AdaptapiInputHub.lastFailure()
            return when {
                active -> "已接管（按键码 ${accepted.joinToString(",")}）"
                fail != null -> "不可用：$fail"
                started -> "已注册但固件未放行语音键"
                else -> "未启动"
            }
        }

    fun start() {
        if (started) return
        started = true
        val accepted = AdaptapiInputHub.register(app, INTERCEPT_KEYS, keyListener)
        Logx.i(
            LogTags.KEYS,
            "按键增强：语音键 Input 拦截已启动",
            "active" to active,
            "accepted" to accepted.joinToString(","),
            "status" to statusText,
        )
    }

    fun stop() {
        if (!started) return
        started = false
        AdaptapiInputHub.unregister(keyListener)
    }

    private fun handleEvent(code: Int, down: Boolean): Boolean {
        if (!OemKeyProtocol.isVoiceKey(code)) return false
        val prefs = Prefs(app)
        val voiceConfigured = KeyActionResolver.VoiceSlot.values().any { !KeyActionResolver.voice(prefs, it).isNone }
        if (!prefs.keysEnhanced || !voiceConfigured) return false
        if (down) {
            Logx.i(LogTags.KEYS, "语音键按下（Input 通道）", "code" to code)
        }
        OemKeyRuntime.onRawVoiceKey(app, code, down, source = "input")
        return true
    }

    companion object {
        private val INTERCEPT_KEYS = intArrayOf(
            OemKeyProtocol.KEY_R_VOICE_ASSIST,
            OemKeyProtocol.KEY_R_VOICE_FRONT,
            OemKeyProtocol.KEY_VOICE_ASSIST,
            OemKeyProtocol.KEY_ASSIST,
        )
    }
}
