package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.cardesk.launcher.core.KeyActionResolver
import com.cardesk.launcher.core.KeyGesture
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.vehicle.DriveModeSwitch
import com.cardesk.launcher.vehicle.VehicleController

/**
 * 第218轮：**按键增强的执行端**。
 *
 * ## 背景
 *
 * 「按键增强」以前只有配置界面，没有执行链路 ——
 * 设置里配了「语音键单击 = 切换驾驶模式」，按键下去什么都不会发生
 * （实车诊断包实测：配置写进 pref 了，一个动作都没触发）。
 *
 * 本类把「按键事件 → 手势 → 动作」这条链接上。
 *
 * ## 分工
 *
 * | 层 | 文件 | 职责 |
 * |---|---|---|
 * | 识别 | `core/KeyGesture.kt` | 纯状态机（单击/双击/长按），不碰 Android ⇒ 可 JVM 单测 |
 * | 解析 | `core/KeyActionResolver.kt` | 「哪个键、哪种手势」→ 「做什么动作」 |
 * | **执行** | **本类** | 真的去切驾驶模式 / 开 360 / 唤起语音助手 |
 *
 * ## ⚠️ 为什么单击要延迟
 *
 * 用户能同时配「单击 = A」和「双击 = B」。
 * 若抬手就立刻发单击，双击时就会**先触发 A 再触发 B**。
 * ⇒ 单击延迟到双击窗口结束（[KeyGesture.DOUBLE_WINDOW_MS]）才发。
 */
class KeyActionExecutor(
    private val context: Context,
    private val prefs: Prefs,
    /** 切换 360 环影（开↔关）。 */
    private val onToggle360: () -> Unit,
    /** 切换导航投屏（本机不支持时为 null）。 */
    private val onToggleNaviCast: (() -> Unit)? = null,
) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 动作执行时的回调（观测口）。
     *
     * 单测靠它断言「到底执行了哪个动作」——
     * 否则只能看到车控有没有被写，读不出**是哪个手势触发的**。
     */
    var onExecuted: ((KeyActionResolver.Action, String) -> Unit)? = null

    /** 语音键的状态机（认单击/双击/长按）。 */
    private val voiceDetector = KeyGesture.Detector()

    /** 媒体上一曲的状态机（只认长按）。 */
    private val prevDetector = KeyGesture.Detector()

    /** 媒体下一曲的状态机（只认长按）。 */
    private val nextDetector = KeyGesture.Detector()

    /** 当前正在等待长按判定的 keyCode（0 = 没有）。 */
    private var pendingLongKey = 0

    /** 上一次记进日志的「不认识」的 keyCode（0 = 没有）。 */
    private var lastUnknownKey = 0

    /** 上一次记「不认识」的 keyCode 的时刻（毫秒）。 */
    private var lastUnknownAt = 0L

    /**
     * 长按定时器。
     *
     * 同一时刻只有一个键会被按住 ⇒ 一个定时器够用。
     */
    private val longRunnable = Runnable {
        val code = pendingLongKey
        pendingLongKey = 0
        if (code == 0) return@Runnable
        val detector = detectorOf(code) ?: return@Runnable
        if (detector.onLongTimeout(SystemClock.uptimeMillis()) == KeyGesture.Gesture.LONG) {
            onLong(code)
        }
    }

    /** 单击定时器（双击窗口到期后触发）。 */
    private val singleRunnable = Runnable {
        if (voiceDetector.onSingleTimeout() == KeyGesture.Gesture.SINGLE) {
            execute(KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.CLICK), "语音键单击")
        }
    }

    private fun detectorOf(keyCode: Int): KeyGesture.Detector? = when {
        KeyActionResolver.isVoiceKey(keyCode) -> voiceDetector
        KeyActionResolver.isMediaPrev(keyCode) -> prevDetector
        KeyActionResolver.isMediaNext(keyCode) -> nextDetector
        else -> null
    }

    /**
     * 喂一个按键事件进来。
     *
     * @return true = 这个事件被按键增强**吃掉了**（不该再往下传）。
     */
    fun onKeyEvent(event: KeyEvent, now: Long): Boolean {
        if (!prefs.keysEnhanced) return false
        val code = event.keyCode
        if (!KeyActionResolver.isHandled(code)) {
            // 第218轮：**不认识 keyCode 也要留痕**。
            //
            // 实车的语音键真实 keyCode **未知**（各家车机不一样），
            // 现在只能靠候选列表覆盖 ⇒ 用户按一次语音键，诊断包里就能看到
            // 到底是哪个码，下一轮把它加进候选表就收窄了。
            //
            // 用 INFO 级别（DEBUG 默认不落盘 ⇒ 诊断包里看不到）。
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                logUnknownKey(code, now)
            }
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 长按的自动重复事件：长按由我们自己的定时器判定，重复事件直接忽略。
                if (event.repeatCount > 0) return true
                val detector = detectorOf(code) ?: return false
                detector.onDown(code, now)
                pendingLongKey = code
                handler.removeCallbacks(longRunnable)
                handler.postDelayed(longRunnable, KeyGesture.LONG_THRESHOLD_MS)
                Logx.d(LogTags.KEYS, "按键增强：按下", "keyCode" to code)
                return true
            }

            KeyEvent.ACTION_UP -> {
                val detector = detectorOf(code) ?: return false
                handler.removeCallbacks(longRunnable)
                if (pendingLongKey == code) pendingLongKey = 0
                val gesture = detector.onUp(code, now)
                if (gesture == KeyGesture.Gesture.DOUBLE) {
                    // 双击成立 ⇒ 挂起的单击作废
                    handler.removeCallbacks(singleRunnable)
                    execute(
                        KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.DOUBLE),
                        "语音键双击",
                    )
                } else if (KeyActionResolver.isVoiceKey(code)) {
                    // 单击：等双击窗口结束再定夺
                    handler.removeCallbacks(singleRunnable)
                    handler.postDelayed(singleRunnable, KeyGesture.DOUBLE_WINDOW_MS)
                }
                Logx.d(LogTags.KEYS, "按键增强：抬起", "keyCode" to code, "gesture" to gesture.name)
                return true
            }
        }
        return false
    }

    /**
     * 记住并记录一个**不认识**的 keyCode。
 *
     * ## 为什么要有它
 *
 * 实车（领克 AAOS 820A）的语音键真实 keyCode 未知，
 * 现在只能靠 `KeyActionResolver.VOICE_KEY_CODES` 的候选列表覆盖。
 * ⇒ 用户在实车上按一次语音键，诊断包里就能看到真实值，下一轮收窄候选表。
 *
 * ## 为什么限流
 *
 * 同一个键连按十次就只记一次 —— 免得诊断包被同一个码刷屏。
 */
    private fun logUnknownKey(code: Int, now: Long) {
    if (code == lastUnknownKey && now - lastUnknownAt < UNKNOWN_KEY_LOG_INTERVAL_MS) return
    lastUnknownKey = code
    lastUnknownAt = now
    Logx.i(
        LogTags.KEYS,
        "按键增强：不认识的按键",
        "keyCode" to code,
        "keyName" to KeyEvent.keyCodeToString(code),
    )
}

/** 长按判定成立。 */
    private fun onLong(code: Int) {
        val action = when {
            KeyActionResolver.isVoiceKey(code) ->
                KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.LONG)
            KeyActionResolver.isMediaPrev(code) ->
                KeyActionResolver.media(prefs, KeyActionResolver.MediaSlot.PREV)
            KeyActionResolver.isMediaNext(code) ->
                KeyActionResolver.media(prefs, KeyActionResolver.MediaSlot.NEXT)
            else -> return
        }
        execute(action, "长按 keyCode=$code")
    }

    /** 执行一个动作。 */
    private fun execute(action: KeyActionResolver.Action, source: String) {
        if (action.isNone) {
            Logx.d(LogTags.KEYS, "按键增强：动作是「无」", "source" to source)
            return
        }
        Logx.event(
            LogTags.KEYS,
            "按键增强：执行动作",
            "source" to source,
            "action" to action.action,
            "driveMode" to action.driveMode,
        )
        onExecuted?.invoke(action, source)
        when (action.action) {
            Prefs.ACTION_DRIVE_MODE -> switchDriveMode(action.driveMode, source)
            Prefs.ACTION_360_TOGGLE -> toggle360(source)
            Prefs.ACTION_VOICE_ASSIST -> launchVoiceAssist(source)
            Prefs.ACTION_DIM_NAVI_CAST_TOGGLE -> toggleNaviCast(source)
            else -> Logx.w(LogTags.KEYS, "按键增强：未知动作", "source" to source, "action" to action.action)
        }
    }

    /**
     * 切驾驶模式。
     *
     * 目标非法 / 已经在目标模式 ⇒ 记日志后不动（不打扰用户）。
     */
    private fun switchDriveMode(target: Int, source: String) {
        if (!DriveModeSwitch.isValidTarget(target)) {
            Logx.w(LogTags.KEYS, "按键增强：驾驶模式目标非法", "source" to source, "target" to target)
            return
        }
        VehicleController.setDriveMode(target) { ok, message ->
            Logx.i(
                LogTags.KEYS,
                "按键增强：驾驶模式结果",
                "source" to source,
                "target" to target,
                "ok" to ok,
                "message" to message,
            )
            if (!ok) {
                Toast.makeText(context, message.ifEmpty { "驾驶模式切换失败" }, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 切换 360 环影。 */
    private fun toggle360(source: String) {
        Logx.i(LogTags.KEYS, "按键增强：切换 360", "source" to source, "current" to VehicleController.is360On)
        onToggle360()
    }

    /**
     * 唤起语音助手。
     *
     * ## ⚠️ 车机的语音助手包名各家不同
     *
     * 领克车机用哪个包名**未知** ⇒ 这里按顺序试：
     * AOSP 标准的 [Intent.ACTION_VOICE_ASSIST]（系统会路由到默认语音助手）、
     * 再试几个领克/ECARX 常见的自定义 action。
     * 全都解析不到 ⇒ 记日志 + Toast，让用户知道要填包名。
     */
    private fun launchVoiceAssist(source: String) {
        val pm = context.packageManager
        for (intent in voiceAssistIntents()) {
            val resolved = runCatching {
                pm.resolveActivity(intent, 0)
            }.getOrNull()
            if (resolved != null) {
                Logx.i(
                    LogTags.KEYS,
                    "按键增强：唤起语音助手",
                    "source" to source,
                    "action" to intent.action,
                    "pkg" to resolved.activityInfo?.packageName,
                )
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        Logx.w(LogTags.KEYS, "按键增强：唤起语音助手失败", "source" to source, "action" to intent.action)
                    }
                return
            }
        }
        Logx.w(LogTags.KEYS, "按键增强：找不到语音助手", "source" to source)
        Toast.makeText(context, "没找到语音助手应用", Toast.LENGTH_SHORT).show()
    }

    /**
     * 语音助手的候选 intent（按优先级）。
     *
     * ⚠️ 用**字面量**而不是 `Intent.ACTION_VOICE_ASSIST` ——
     * 本工程的 compileSdk 里没有这个常量（`aapt`/IDE 都会报 unresolved）。
     */
    private fun voiceAssistIntents(): List<Intent> = listOf(
        Intent("android.intent.action.VOICE_ASSIST"),
        Intent("com.ecarx.voiceassistant.action.VOICE_ASSIST"),
        Intent("com.ecarx.voice.action.SPEAK"),
        Intent("android.intent.action.ASSIST"),
    )

    /** 切换导航投屏。 */
    private fun toggleNaviCast(source: String) {
        val cb = onToggleNaviCast
        if (cb == null) {
            Logx.w(LogTags.KEYS, "按键增强：本机不支持导航投屏切换", "source" to source)
            Toast.makeText(context, "本机不支持导航投屏切换", Toast.LENGTH_SHORT).show()
            return
        }
        cb()
    }

    companion object {
        /** 同一个未知 keyCode 的记录间隔（毫秒）。 */
        private const val UNKNOWN_KEY_LOG_INTERVAL_MS = 3000L
    }
}
