package com.cardesk.launcher.ui

import android.content.Context
import android.os.SystemClock
import com.cardesk.launcher.core.OemKeyProtocol
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.vehicle.VehicleController

/**
 * 第三百一十五轮：**方控按键的总机**。
 *
 * ## 它是干什么的
 *
 * 方控广播（[OemKeyReceiver]）随时可能来，但它**不保证桌面界面还活着**：
 * 用户在导航/音乐里按方控时，cardesk 的 Activity 可能是 `onStop` 甚至已销毁，
 * 进程里只有全局服务。所以广播不能直接找 [KeyActionExecutor] 的实例，
 * 得先问这个总机：
 *
 * - Activity 活着 ⇒ 用它自己的执行端（`onToggle360` 走 `LauncherView.toggleCam360()`，
 *   会带上「取景视图」那套完整行为）；
 * - Activity 不在 ⇒ 现造一个**无界面执行端**顶上（360 只做开关，不配取景；
 *   语音/驾驶模式/投屏照常）。
 *
 * ## 为什么不留全局静态单例的 Prefs
 *
 * 广播接收器只是在同一进程里被回调，`context` 一定是 application context，
 * 现造 [Prefs] 很便宜（只是包一层 SharedPreferences）；持有一个全局实例反而
 * 会在「多用户/多实例」时读到别人的配置。
 */
object OemKeyRuntime {

    /** Activity 挂上来的执行端（可能为 null）。 */
    @Volatile
    private var attached: KeyActionExecutor? = null

    /** Activity 挂上来的 application context（造无界面执行端用）。 */
    @Volatile
    private var appContext: Context? = null

    /** Activity 挂上来的 Prefs。 */
    @Volatile
    private var attachedPrefs: Prefs? = null

    /** 无界面兜底执行端（懒建，只在 Activity 不在时用）。 */
    @Volatile
    private var headless: KeyActionExecutor? = null

    /** 去重窗口（毫秒）。 */
    private const val DEDUPE_WINDOW_MS = 200L

    /** Input 与 car_signal 双通道仲裁窗口（毫秒）。 */
    private const val INPUT_SIGNAL_ARBITRATION_MS = 120L

    /** 实时通道接管后屏蔽同一次方控广播的窗口（毫秒；覆盖长按 500ms + 抬手延迟）。 */
    private const val RAW_VOICE_SUPPRESS_OEM_MS = 900L

    /** 清单接收器通路。 */
    const val PATH_MANIFEST = "manifest"

    /** 动态注册通路。 */
    const val PATH_DYNAMIC = "dynamic"

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 上一次处理的事件（去重用）。 */
    private var lastKeyCode = 0
    private var lastPress: OemKeyProtocol.Press? = null
    private var lastPath: String = ""
    private var lastAt = 0L

    @Volatile
    private var lastRawInputAt = -1L

    @Volatile
    private var lastRawVoiceAt = -1L

    /** Activity 就绪后挂上它的执行端。 */
    fun attach(context: Context, prefs: Prefs, executor: KeyActionExecutor) {
        appContext = context.applicationContext ?: context
        attachedPrefs = prefs
        attached = executor
        Logx.d(LogTags.KEYS, "按键增强：执行端已挂上（方控广播走 Activity 通路）")
    }

    /** Activity 销毁时摘掉（用例：配置变更 / 退出桌面）。 */
    fun detach(executor: KeyActionExecutor) {
        if (attached === executor) {
            attached = null
            Logx.d(LogTags.KEYS, "按键增强：执行端已摘下（方控广播改走无界面兜底）")
        }
    }

    /** 是否有 Activity 挂着的执行端（诊断用）。 */
    fun hasAttached(): Boolean = attached != null

    /**
     * 广播来了：转给执行端。
     *
     * @return true = 被吃掉了（接收端应 `abortBroadcast()`）。
     */
    fun dispatch(
        context: Context,
        event: OemKeyProtocol.OemKeyEvent,
        path: String = PATH_MANIFEST,
    ): Boolean {
        val app = context.applicationContext ?: context
        val prefs = attachedPrefs ?: Prefs(app)
        if (!prefs.keysEnhanced) {
            Logx.d(LogTags.KEYS, "按键增强：总开关关闭，方控广播不拦", "keyCode" to event.keyCode)
            return false
        }
        // 去重：**同一条事件**从两条通路各来一次时只执行一次。
        //
        // ⚠️ 不能只按「同键同手势 + 时间窗」去重：双击的两次单击间隔本来就可能
        // 小于窗口（实测可到 25ms），那样第二次单击会被吃掉、双击永远合成不出来。
        // ⇒ 只有「同一事件走了不同通路」才判定为重复。
        val now = SystemClock.uptimeMillis()
        val samePath = path == lastPath
        if (!samePath && event.keyCode == lastKeyCode && event.press == lastPress &&
            now - lastAt <= DEDUPE_WINDOW_MS
        ) {
            Logx.d(
                LogTags.KEYS,
                "按键增强：重复方控事件已忽略",
                "keyCode" to event.keyCode,
                "press" to event.press.name,
                "deltaMs" to (now - lastAt),
            )
            return false
        }
        lastKeyCode = event.keyCode
        lastPress = event.press
        lastPath = path
        lastAt = now

        val executor = attached ?: headless(app, prefs).also {
            Logx.d(LogTags.KEYS, "按键增强：桌面界面不在，用无界面执行端处理方控")
        }
        if (OemKeyProtocol.isVoiceKey(event.keyCode) &&
            lastRawVoiceAt >= 0L &&
            now - lastRawVoiceAt <= RAW_VOICE_SUPPRESS_OEM_MS
        ) {
            Logx.i(LogTags.KEYS, "按键增强：语音键已由实时通道接管，吞掉广播", "keyCode" to event.keyCode)
            return true
        }
        return runCatching { executor.onOemKey(event) }.getOrElse { error ->
            Logx.w(LogTags.KEYS, "按键增强：处理方控广播失败", error, "keyCode" to event.keyCode)
            false
        }
    }

    /**
     * 来自 `Input.requestKeysInterception` 或 `EcarxSignals.SIG_VOICE` 的实时按下/抬起。
     */
    fun onRawVoiceKey(context: Context, keyCode: Int, down: Boolean, source: String) {
        val now = SystemClock.uptimeMillis()
        if (source == "signal" && lastRawInputAt >= 0L && now - lastRawInputAt <= INPUT_SIGNAL_ARBITRATION_MS) {
            return
        }
        if (source == "input") {
            lastRawInputAt = now
        }
        lastRawVoiceAt = now
        val app = context.applicationContext ?: context
        val prefs = attachedPrefs ?: Prefs(app)
        if (!prefs.keysEnhanced) return
        val executor = attached ?: headless(app, prefs)
        val action = if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP
        mainHandler.post {
            executor.onKeyEvent(android.view.KeyEvent(action, keyCode), now)
        }
    }

    /**
     * 造 / 复用无界面执行端。
     *
     * 360 只能做「开↔关」：没有界面就没有取景视图那套联动，
     * 开环影时不会自动切到某个视角（挡位联动由 `Camera360Link` 单独负责）。
     */
    private fun headless(context: Context, prefs: Prefs): KeyActionExecutor {
        headless?.let { return it }
        synchronized(this) {
            headless?.let { return it }
            val created = KeyActionExecutor(
                context = context,
                prefs = prefs,
                onToggle360 = {
                    val target = !VehicleController.is360On
                    Logx.i(LogTags.KEYS, "按键增强：无界面切换 360", "target" to target)
                    VehicleController.set360(target)
                },
            )
            headless = created
            appContext = context
            return created
        }
    }

    /** 进程内复位（单测/调试用）。 */
    fun reset() {
        attached?.stop()
        attached = null
        attachedPrefs = null
        headless?.stop()
        headless = null
        appContext = null
    }
}
