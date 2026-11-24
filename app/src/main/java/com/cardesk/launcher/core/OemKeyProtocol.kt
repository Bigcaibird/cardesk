package com.cardesk.launcher.core

/**
 * 第三百一十五轮：**方控按键广播协议**（纯逻辑，不 import Android ⇒ 可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 车机把方向盘按键做成**有序广播**发给应用，而不是标准 [android.view.KeyEvent]：
 * 桌面只有在前台、且拿到按键焦点时才收得到 KeyEvent ⇒ 用户在导航/音乐里按方控，
 * 桌面完全看不到（第215轮上线以来一直是这个状态）。
 *
 * 实车实测（`tools/handoff-r224.md` §11）：语音键按下时系统发出
 * `ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT`，带两个 extra：
 *
 * - `ecarx.extra.ECARX_KEY_EVENT_TYPE`：键码（语音键是 `200231`）；
 * - `ecarx.extra.ECARX_KEY_ACTION_TYPE`：`0` = 单击、`1` = 长按。
 *
 * **系统不提供双击**（它把双击当两次单击发）⇒ 双击必须由桌面自己按时序合成，
 * 见 [OemKeyGesture]。
 *
 * ## 分工
 *
 * | 层 | 文件 | 职责 |
 * |---|---|---|
 * | 协议 | **本文件** | action / extra / 键码常量 + 解析，不碰 Android |
 * | 手势 | `core/OemKeyGesture.kt` | 「单击/长按事件流」→ 单击/双击/长按 |
 * | 执行 | `ui/KeyActionExecutor.kt` | 手势 → 动作 |
 * | 接入 | `ui/OemKeyReceiver.kt` | 广播回来了怎么转给执行端 |
 */
object OemKeyProtocol {

    // ---------------------------------------------------------------- action

    /** 标准媒体键广播（车机方控的播放/上一曲/下一曲也走它）。 */
    const val ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"

    /** 语音键（实车实测就是它）。 */
    const val ACTION_VOICE = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"

    /** 自定义键（`*` 键一类）。 */
    const val ACTION_CUSTOM = "ecarx.intent.action.ECARX_KEY_CUSTOM_EVENT"

    /** 音源键。 */
    const val ACTION_SRC = "ecarx.intent.action.ECARX_KEY_RSRC_EVENT"

    /** 接听/挂断键。 */
    const val ACTION_CALL = "ecarx.intent.action.ECARX_KEY_RCALL_EVENT"

    /** 多功能键。 */
    const val ACTION_MULTI = "ecarx.intent.action.ECARX_KEY_RMULTIFUNCTION_EVENT"

    /**
     * 唤起车机**自己的**语音助手。
     *
     * 这是有序广播，接收方需要 `com.android.systemui.permission.PLUGIN`。
     * 本应用只能用 `sendOrderedBroadcast` 发（不能 `startActivity` 代替：
     * 车机语音不是普通 Activity）。
     */
    const val ACTION_VOICE_LAUNCH = "ecarx.intent.action.ECARX_KEY_EVENT_RVOICE_EVENT"

    /** [ACTION_VOICE_LAUNCH] 的接收方权限。 */
    const val PERMISSION_VOICE_LAUNCH = "com.android.systemui.permission.PLUGIN"

    /** 需要注册进清单的 OEM 按键 action（`MEDIA_BUTTON` 单独处理，见接收端）。 */
    val OEM_ACTIONS: List<String> = listOf(ACTION_VOICE, ACTION_CUSTOM, ACTION_SRC, ACTION_CALL, ACTION_MULTI)

    // ---------------------------------------------------------------- extras

    /** `MEDIA_BUTTON` 里的 KeyEvent（车载媒体键同样是这个 extra）。 */
    const val EXTRA_KEY_EVENT = "android.intent.extra.KEY_EVENT"

    /** OEM 按键广播里的键码（int）。 */
    const val EXTRA_KEY_TYPE = "ecarx.extra.ECARX_KEY_EVENT_TYPE"

    /** OEM 按键广播里的事件类型（int，0 单击 / 1 长按）。 */
    const val EXTRA_KEY_ACTION = "ecarx.extra.ECARX_KEY_ACTION_TYPE"

    /** [EXTRA_KEY_ACTION] = 单击。 */
    const val ACTION_TYPE_CLICK = 0

    /** [EXTRA_KEY_ACTION] = 长按（系统判定成立后才发）。 */
    const val ACTION_TYPE_LONG = 1

    // ------------------------------------------------------------- key codes

    /** 语音键：实车私有码。 */
    const val KEY_R_VOICE_ASSIST = 200231

    /** 语音键：部分车机的另一个私有码。 */
    const val KEY_R_VOICE_FRONT = 400001

    /** 语音键：`KEYCODE_ASSIST`。 */
    const val KEY_ASSIST = 219

    /** 语音键：`KEYCODE_VOICE_ASSIST`（部分 build 上等于 219，这里仍单独留着）。 */
    const val KEY_VOICE_ASSIST = 231

    /** 语音键：`KEYCODE_SEARCH`（实车 `getevent` 里 `KEY_SEARCH` 就是它）。 */
    const val KEY_SEARCH = 84

    /** 语音键：`KEYCODE_VOICE_COMMAND` 一类的旧码。 */
    const val KEY_VOICE_COMMAND = 112

    /** 语音键：`KEYCODE_MEDIA_PLAY_PAUSE` 被部分车机复用为语音键。 */
    const val KEY_MEDIA_PLAY_PAUSE = 85

    /** 上一曲：标准码。 */
    const val KEY_MEDIA_PREV = 88

    /** 上一曲：方控私有码。 */
    const val KEY_R_MEDIA_PREV = 200088

    /** 下一曲：标准码。 */
    const val KEY_MEDIA_NEXT = 87

    /** 下一曲：方控私有码。 */
    const val KEY_R_MEDIA_NEXT = 200087

    /**
     * 语音键候选码。
     *
     * ⚠️ 顺序有意义：**只**用于「这是不是语音键」的判定与不认识按键的诊断，
     * 不参与持久化 ⇒ 以后加码不需要迁移。
     */
    val VOICE_KEY_CODES: IntArray = intArrayOf(
        KEY_R_VOICE_ASSIST,
        KEY_VOICE_ASSIST,
        KEY_ASSIST,
        KEY_SEARCH,
        108, // KEYCODE_VOICE_ASSIST 的旧值（compileSdk 变更前的常量值）
        204,
        KEY_VOICE_COMMAND,
        KEY_R_VOICE_FRONT,
    )

    /** 是否语音键（含方控私有码）。 */
    fun isVoiceKey(keyCode: Int): Boolean = VOICE_KEY_CODES.contains(keyCode)

    /** 是否上一曲（含方控私有码）。 */
    fun isMediaPrev(keyCode: Int): Boolean = keyCode == KEY_MEDIA_PREV || keyCode == KEY_R_MEDIA_PREV

    /** 是否下一曲（含方控私有码）。 */
    fun isMediaNext(keyCode: Int): Boolean = keyCode == KEY_MEDIA_NEXT || keyCode == KEY_R_MEDIA_NEXT

    /** 是否是本协议认识的按键（语音键或媒体键）。 */
    fun isHandled(keyCode: Int): Boolean = isVoiceKey(keyCode) || isMediaPrev(keyCode) || isMediaNext(keyCode)

    /** 是否是 OEM 按键广播的 action。 */
    fun isOemAction(action: String?): Boolean = action != null && OEM_ACTIONS.contains(action)

    /** 按键的一次事件：单击或长按。 */
    enum class Press { CLICK, LONG }

    /** 解析出来的一次按键。 */
    data class OemKeyEvent(
        /** 键码（`ecarx.extra.ECARX_KEY_EVENT_TYPE`）。 */
        val keyCode: Int,
        /** 单击 / 长按。 */
        val press: Press,
        /** 来源 action（排障用）。 */
        val sourceAction: String,
    )

    /**
     * 解析一条 OEM 按键广播。
     *
     * @return null = 这条广播不是我们能处理的（键码 0 / action 不认识）。
     */
    fun parse(sourceAction: String?, keyCode: Int, actionType: Int): OemKeyEvent? {
        if (!isOemAction(sourceAction) || keyCode <= 0) return null
        val press = if (actionType == ACTION_TYPE_LONG) Press.LONG else Press.CLICK
        return OemKeyEvent(keyCode, press, sourceAction ?: "")
    }

    /**
     * 键码 → 人读的名字（日志 / 设置页诊断行用）。
     *
     * 不自带 `KeyEvent.keyCodeToString`：那会把私有码显示成 `KEYCODE_UNKNOWN`，
     * 而这里恰恰要把 `200231` 这种码**原样**打出来给实车排障用。
     */
    fun keyName(keyCode: Int): String = when (keyCode) {
        KEY_R_VOICE_ASSIST -> "语音键(200231)"
        KEY_R_VOICE_FRONT -> "语音键(400001)"
        KEY_ASSIST -> "ASSIST(219)"
        KEY_VOICE_ASSIST -> "VOICE_ASSIST(231)"
        KEY_SEARCH -> "SEARCH(84)"
        KEY_VOICE_COMMAND -> "VOICE_COMMAND(112)"
        KEY_R_MEDIA_PREV -> "上一曲(200088)"
        KEY_MEDIA_PREV -> "上一曲(88)"
        KEY_R_MEDIA_NEXT -> "下一曲(200087)"
        KEY_MEDIA_NEXT -> "下一曲(87)"
        else -> "未知($keyCode)"
    }

    /** 一次按键事件 → 设置页「最近一次按键」那行文字。 */
    fun describe(event: OemKeyEvent): String {
        val press = if (event.press == Press.LONG) "长按" else "单击"
        return "${press} · ${keyName(event.keyCode)}"
    }
}
