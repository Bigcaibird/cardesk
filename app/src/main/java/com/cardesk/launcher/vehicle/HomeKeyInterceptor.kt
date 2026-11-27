package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.HomeKeyRuntime
import com.cardesk.launcher.core.OemRecentsSuppressor
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Proxy

/**
 * 车机 Input 通道的 Home 键接管。
 *
 * ## 为什么不是 `dispatchKeyEvent`
 *
 * Home 键在 `PhoneWindowManager` 里就被吃掉了（回桌面 / 双击开系统最近任务），
 * 应用层的 `dispatchKeyEvent` 永远看不到。车机固件另外提供了一条给应用用的
 * 按键拦截通道（`ecarx.adapterapi` 里的 `com.ecarx.xui.adaptapi.input.Input`），
 * 可以让应用在**框架之前**拿到 Home 键并决定要不要吃掉：
 *
 * ```java
 * Input input = Input.create(context);
 * int[] accepted = input.requestKeysInterception(new int[]{ 3 }, callback);
 * // callback.onKeyPressed(3) 返回 true = 吃掉，框架不再处理
 * ```
 *
 * 白名单是固件里的 `CarInputHandlingService.InputFilter`，注册不在名单里的码会被
 * 静默过滤（返回值里没有它）。所以这里必须看 `accepted` 里到底有没有
 * `KEYCODE_HOME`，没有就明确报「不可用」，让设置页显示出来。
 *
 * ## 失败就是失败
 *
 * 反射/服务连接/固件差异任何一种失败都只记日志并把状态写进
 * [HomeKeyRuntime.interceptorStatus]，不抛异常 —— 这条通道是「锦上添花」，
 * 拿不到时 HOME intent 兜底路径仍然工作。
 */
class HomeKeyInterceptor(
    private val context: Context,
    private val prefs: Prefs,
) {

    companion object {
        /** `KeyEvent.KEYCODE_HOME`。 */
        const val KEYCODE_HOME = 3
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false

    private val keyListener = object : AdaptapiInputHub.KeyListener {
        override fun onKeyPressed(keyCode: Int): Boolean = handlePress(keyCode)

        override fun onKeyReleased(keyCode: Int): Boolean = HomeKeyRuntime.enabled(prefs)
    }

    private val statusSync: () -> Unit = { publishStatus() }

    /** 是否真的接管到了 Home 键。 */
    val active: Boolean
        get() = started && AdaptapiInputHub.isKeyAccepted(KEYCODE_HOME)

    /** 设置页展示的状态文案。 */
    val statusText: String
        get() {
            val failure = AdaptapiInputHub.lastFailure()
            val accepted = AdaptapiInputHub.acceptedKeys()
            return when {
                active -> "已接管（按键码 ${accepted.joinToString(",")}）"
                failure != null -> "不可用：$failure"
                started -> "已注册但固件未放行 Home 键（白名单：${accepted.joinToString(",").ifEmpty { "空" }}）"
                else -> "未启动"
            }
        }

    /** 启动接管；幂等。 */
    fun start() {
        if (active) return
        started = true
        AdaptapiInputHub.addStatusListener(statusSync)
        AdaptapiInputHub.register(context, intArrayOf(KEYCODE_HOME), keyListener)
        publishStatus()
    }

    private fun publishStatus() {
        HomeKeyRuntime.interceptorActive = active
        HomeKeyRuntime.interceptorStatus = statusText
        Logx.i(
            LogTags.HOME,
            "Home 键接管状态",
            "active" to active,
            "accepted" to AdaptapiInputHub.acceptedKeys().joinToString(","),
            "status" to statusText,
        )
        runCatching { OemRecentsSuppressor.sync(context, prefs) }
            .onFailure { Logx.w(LogTags.HOME, "原车最近任务同步失败", it) }
    }

    /** 停止接管并把按键还给系统。 */
    fun stop() {
        if (!started) return
        started = false
        AdaptapiInputHub.removeStatusListener(statusSync)
        AdaptapiInputHub.unregister(keyListener)
        HomeKeyRuntime.interceptorActive = false
        val failure = AdaptapiInputHub.lastFailure()
        HomeKeyRuntime.interceptorStatus = if (failure == null) "已停止" else "不可用：$failure"
        runCatching { OemRecentsSuppressor.release(context, prefs) }
            .onFailure { Logx.w(LogTags.HOME, "原车最近任务恢复失败", it) }
        Logx.i(LogTags.HOME, "Home 键接管已停止")
    }

    /**
     * 收到一次按下。
     *
     * @return true = 吃掉（框架不再处理）；false = 放行给系统。
     */
    private fun handlePress(code: Int): Boolean {
        val consume = HomeKeyRuntime.enabled(prefs)
        main.post {
            HomeKeyRuntime.onInterceptPress(context, prefs)
        }
        Logx.i(LogTags.HOME, "Home 键按下（Input 通道）", "code" to code, "consume" to consume)
        return consume
    }
}
