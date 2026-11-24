package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import com.cardesk.launcher.core.KeyActionResolver
import com.cardesk.launcher.core.KeyGesture
import com.cardesk.launcher.core.OemKeyGesture
import com.cardesk.launcher.core.OemKeyProtocol
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
 * | 识别 | `core/KeyGesture.kt` | KeyEvent 通道的纯状态机（按下/抬起 → 单击/双击/长按） |
 * | 识别 | `core/OemKeyGesture.kt` | 方控广播通道的纯状态机（单击/长按事件流 → 单击/双击/长按） |
 * | 协议 | `core/OemKeyProtocol.kt` | 方控广播的 action / extra / 键码（含车机私有码） |
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

    // ------------------------------------------------------------ 方控广播通路
    //
    // 第三百一十五轮：车机把方向盘按键做成**有序广播**，而不是 KeyEvent
    // （协议见 `core/OemKeyProtocol`）。车上用户在导航/音乐里按方控时，
    // 桌面收不到 KeyEvent，只有这条广播通路能拿到键。
    //
    // 广播只给「单击 / 长按」两种事件（没有按下/抬起），所以双击要在这条
    // 通路上自己合成 —— 由 [oemDetector] 负责。

    /** 方控广播的手势机。 */
    private val oemDetector = OemKeyGesture.Detector()

    /** 方控广播上「挂起的单击」结算定时器。 */
    private val oemSingleRunnable = Runnable {
        val result = oemDetector.expire(SystemClock.uptimeMillis())
        if (result.gesture == OemKeyGesture.Gesture.SINGLE) {
            executeOrOemVoice(
                KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.CLICK),
                "方控语音键单击",
            )
        }
    }

    /** 媒体键按下时记下键码（KeyEvent 通道：短按要补发给系统）。 */
    private var mediaPressKey = 0

    /** 这次媒体键按下是否已经升级成长按（是 ⇒ 抬手时不再补发短按）。 */
    private var mediaLongFired = false

    /** 最近一次方控广播（设置页诊断行读它）。 */
    @Volatile
    var lastOemKey: String = ""
        private set

    /** 音频服务：媒体键短按要「补发」给系统，否则切歌会失灵。 */
    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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

        val voice = KeyActionResolver.isVoiceKey(code)

        // 第三百一十五轮：**媒体键不再无条件吃掉**。
        //
        // 第218轮的实现里，媒体键只要进了 isHandled 就一律 `return true`：
        // 哪怕长按动作配的是「无」，短按切歌也被吞掉了 ⇒ 方控切歌失灵。
        // 现在：没配长按动作 ⇒ 整颗键放行，系统爱怎么处理怎么处理。
        if (!voice && longActionOf(code).isNone) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Logx.d(LogTags.KEYS, "按键增强：媒体键放行", "keyCode" to code, "reason" to "未配置长按动作")
            }
            return false
        }

        // 语音键：三个槽位都没配动作 ⇒ 放行，让系统自己的语音助手接管。
        if (voice && !voiceConfigured()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Logx.d(LogTags.KEYS, "按键增强：语音键放行", "keyCode" to code, "reason" to "未配置动作")
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
                if (!voice) {
                    mediaPressKey = code
                    mediaLongFired = false
                }
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
                    if (voice) {
                        val double = KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.DOUBLE)
                        if (double.isNone) {
                            // 没配双击动作 ⇒ 退化成一次单击（用户预期是「点两下 = 点一下」）
                            executeOrOemVoice(
                                KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.CLICK),
                                "语音键双击（按单击执行）",
                            )
                        } else {
                            execute(double, "语音键双击")
                        }
                    }
                } else if (voice) {
                    // 单击：等双击窗口结束再定夺
                    handler.removeCallbacks(singleRunnable)
                    handler.postDelayed(singleRunnable, KeyGesture.DOUBLE_WINDOW_MS)
                } else if (mediaPressKey == code) {
                    // 第三百一十五轮：短按不再吞 —— 长按没触发就把短按补发给系统
                    if (!mediaLongFired) injectMediaKey(code)
                    mediaPressKey = 0
                }
                Logx.d(LogTags.KEYS, "按键增强：抬起", "keyCode" to code, "gesture" to gesture.name)
                return true
            }
        }
        return false
    }

    /**
     * 喂一条**方控广播**按键进来（第三百一十五轮新增）。
     *
     * 与 [onKeyEvent] 的区别：广播只给「单击 / 长按」两种语义事件，
     * 双击在这里合成（见 [oemDetector]）。
     *
     * @return true = 这条广播被吃掉了（接收端应 `abortBroadcast()`，
     *         避免系统语音助手同时也被唤起）。
     */
    fun onOemKey(
        event: OemKeyProtocol.OemKeyEvent,
        now: Long = SystemClock.uptimeMillis(),
    ): Boolean {
        if (!prefs.keysEnhanced) return false
        if (!KeyActionResolver.isHandled(event.keyCode)) {
            logUnknownKey(event.keyCode, now)
            return false
        }
        lastOemKey = OemKeyProtocol.describe(event)
        prefs.setStringValue(KEY_DIAG_LAST, lastOemKey)
        Logx.i(
            LogTags.KEYS,
            "按键增强：收到方控广播",
            "keyCode" to event.keyCode,
            "keyName" to OemKeyProtocol.keyName(event.keyCode),
            "press" to event.press.name,
            "action" to event.sourceAction,
        )

        if (KeyActionResolver.isVoiceKey(event.keyCode)) {
            val clickAction = KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.CLICK)

            // 长按：没配动作就放行（车机自己的长按语义还能用），配了才吞。
            if (event.press == OemKeyProtocol.Press.LONG) {
                handler.removeCallbacks(oemSingleRunnable)
                oemDetector.onLong(event.keyCode)
                val longAction = KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.LONG)
                if (longAction.isNone) {
                    Logx.i(LogTags.KEYS, "按键增强：语音键长按放行车机", "keyCode" to event.keyCode)
                    return false
                }
                execute(longAction, "方控语音键长按")
                return true
            }

            val gesture = oemDetector.onClick(event.keyCode, now)
            when (gesture.gesture) {
                OemKeyGesture.Gesture.DOUBLE -> {
                    handler.removeCallbacks(oemSingleRunnable)
                    val doubleAction =
                        KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.DOUBLE)
                    val target = if (doubleAction.isNone) clickAction else doubleAction
                    if (target.isNone) {
                        // 两个槽位都没配动作：这一下也不吞，车机自己会处理第二次单击。
                        Logx.i(LogTags.KEYS, "按键增强：语音键双击放行车机", "keyCode" to event.keyCode)
                        return false
                    }
                    execute(
                        target,
                        if (doubleAction.isNone) "方控语音键双击（按单击执行）" else "方控语音键双击",
                    )
                    return true
                }

                OemKeyGesture.Gesture.NONE -> {
                    if (clickAction.isNone) {
                        // 单击槽位没配动作 ⇒ **立刻放行**给车机自己的语音助手。
                        //
                        // 真车实测（2026-11-24）：以前这里先 `abortBroadcast()` 吞掉、等双击
                        // 窗口到期再用 `ECARX_KEY_EVENT_RVOICE_EVENT` 补发；车机不认这条
                        // 补发广播，于是语音键彻底哑掉（设置页「模拟测试」却正常，因为它
                        // 不经广播）。现在「没配动作」= 用户就是要车机原生语音，直接放行，
                        // 按压仍然喂给手势机，所以紧跟的第二下照样能判成双击。
                        Logx.i(
                            LogTags.KEYS,
                            "按键增强：语音键单击放行车机",
                            "keyCode" to event.keyCode,
                        )
                        return false
                    }
                    // 单击配了动作 ⇒ 必须挂起等双击窗口，窗口到期执行单击动作。
                    handler.removeCallbacks(oemSingleRunnable)
                    handler.postDelayed(oemSingleRunnable, OemKeyGesture.DOUBLE_WINDOW_MS)
                    return true
                }

                else -> return true
            }
        }

        // 媒体键：广播已经区分了单击/长按 ⇒ 单击直接放行给系统（切歌/暂停），
        // 只有配了长按动作时才由我们处理。
        if (event.press != OemKeyProtocol.Press.LONG) return false
        val action = longActionOf(event.keyCode)
        if (action.isNone) return false
        execute(action, "方控媒体键长按")
        return true
    }

    /**
     * 停止执行端（Activity 销毁时调用）。
     *
     * 不清配置、不清 prefs，只把定时器与手势状态收掉，
     * 免得已经 set 的 Runnable 在控件销毁后还去碰 UI。
     */
    fun stop() {
        handler.removeCallbacks(longRunnable)
        handler.removeCallbacks(singleRunnable)
        handler.removeCallbacks(oemSingleRunnable)
        pendingLongKey = 0
        mediaPressKey = 0
        mediaLongFired = false
        oemDetector.reset()
    }

    /** 语音键三个槽位里是否至少配了一个动作。 */
    private fun voiceConfigured(): Boolean =
        !KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.CLICK).isNone ||
            !KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.DOUBLE).isNone ||
            !KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.LONG).isNone

    /** 某颗键的「长按动作」（媒体键没有双击/单击槽位）。 */
    private fun longActionOf(code: Int): KeyActionResolver.Action = when {
        KeyActionResolver.isVoiceKey(code) ->
            KeyActionResolver.voice(prefs, KeyActionResolver.VoiceSlot.LONG)

        KeyActionResolver.isMediaPrev(code) ->
            KeyActionResolver.media(prefs, KeyActionResolver.MediaSlot.PREV)

        KeyActionResolver.isMediaNext(code) ->
            KeyActionResolver.media(prefs, KeyActionResolver.MediaSlot.NEXT)

        else -> KeyActionResolver.Action(Prefs.ACTION_NONE, 0)
    }

    /**
     * 执行动作；配置是「无」时**不吞按键**，改为唤起系统语音助手。
     *
     * 原因：总开关打开但某个手势配「无」，用户仍然期待语音键能唤语音 ——
     * 第218轮的实现在这种情况下什么都不做，等于把语音键废掉了。
     */
    private fun executeOrOemVoice(action: KeyActionResolver.Action, source: String) {
        if (action.isNone) {
            launchOemVoice("$source（未配置动作）")
            return
        }
        execute(action, source)
    }

    /**
     * 唤起**车机自带**的语音助手（有序广播）。
     *
     * 车机语音不是普通 Activity，`startActivity` 唤不起来 ——
     * 正确做法是发有序广播 `ecarx.intent.action.ECARX_KEY_EVENT_RVOICE_EVENT`，
     * 接收方需要 `com.android.systemui.permission.PLUGIN` 权限。
     *
     * 广播发不出去（没权限/系统没有接收方）时退回 [launchVoiceAssist]。
     */
    private fun launchOemVoice(source: String) {
        // 第三百一十九轮：**不再带 receiverPermission 发送**。
        // 原来 `sendOrderedBroadcast(intent, PLUGIN_PERMISSION)` 的语义是「接收方必须持有
        // 这个权限」，而车机的语音接收方并不持有它 ⇒ 广播被直接过滤掉，实车上什么都没发生。
        // 现在按普通有序广播发，并在发之前查一次「到底有没有接收方」，没有就退回 Activity。
        val intent = Intent(OemKeyProtocol.ACTION_VOICE_LAUNCH).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            // 带上与真实按键广播一致的 extras，接收方按方控事件解析时不会因为缺字段丢弃。
            putExtra(OemKeyProtocol.EXTRA_KEY_TYPE, OemKeyProtocol.KEY_R_VOICE_ASSIST)
            putExtra(OemKeyProtocol.EXTRA_KEY_ACTION, OemKeyProtocol.ACTION_TYPE_CLICK)
        }
        val receivers = runCatching {
            context.packageManager.queryBroadcastReceivers(intent, 0).size
        }.getOrDefault(0)
        val sent = runCatching {
            context.sendOrderedBroadcast(intent, null)
            true
        }.getOrElse { error ->
            Logx.w(LogTags.KEYS, "按键增强：唤起车机语音广播失败", error, "source" to source)
            false
        }
        Logx.i(
            LogTags.KEYS,
            "按键增强：唤起车机语音",
            "source" to source,
            "action" to OemKeyProtocol.ACTION_VOICE_LAUNCH,
            "receivers" to receivers,
            "sent" to sent,
        )
        if (receivers > 0) return
        // 没有接收方 ⇒ 补发注定没人听，改用 Activity 路径兜底。
        Logx.w(LogTags.KEYS, "按键增强：唤起车机语音没有接收方，改走 Activity", "source" to source)
        launchVoiceAssist(source)
    }

    /**
     * 把媒体键短按**补发**给系统。
     *
     * 配了「媒体键长按 = 某动作」之后，短按必须先被我们截住（否则等不到长按），
     * 抬手时再判断：没升级成长按 ⇒ 补发一次按下+抬起，系统照常切歌。
     * 不做这一步的话，配了长按就等于把短按切歌弄丢了。
     */
    private fun injectMediaKey(code: Int) {
        val am = audioManager ?: return
        val now = SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0))
        }.onFailure { Logx.w(LogTags.KEYS, "按键增强：短按转交系统失败", it, "keyCode" to code) }
        Logx.d(
            LogTags.KEYS,
            "按键增强：短按转交系统",
            "keyCode" to code,
            "keyName" to KeyEvent.keyCodeToString(code),
        )
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
        if (!KeyActionResolver.isVoiceKey(code)) mediaLongFired = true
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
            Prefs.ACTION_VOICE_ASSIST -> launchOemVoice(source)
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

        /**
         * 「最近一次方控按键」落在哪个 pref 里（设置页诊断行读它）。
         *
         * 用通用键而不是新增 Prefs 字段：这是纯诊断信息，不进备份、不做迁移。
         */
        const val KEY_DIAG_LAST = "key_diag_last"
    }
}
