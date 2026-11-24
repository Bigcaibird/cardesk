package com.cardesk.launcher.core

/**
 * 第218轮：**按键增强的动作解析**（纯逻辑，可 JVM 单测）。
 *
 * ## 这一层解决什么
 *
 * 设置里给每个「按键 × 手势」配了一个动作码（[Prefs.ACTION_*]），
 * 动作码里还可能带一个**驾驶模式目标**（切到舒适 / 运动 / 经济）。
 *
 * 这里负责把「哪个键、哪种手势」翻译成「做什么动作、切到哪个模式」。
 *
 * ## 为什么单独抽出来
 *
 * 这一层**不碰任何 Android API** ⇒ 可以直接 JVM 单测；
 * 而真正去写车控（切驾驶模式 / 开 360）在 `ui/KeyActionExecutor`，
 * 那边要车机通道，测不了。
 */
object KeyActionResolver {

    /**
     * 一个待执行的动作。
     *
     * @param action [Prefs.ACTION_NONE] / [Prefs.ACTION_DRIVE_MODE] / …
     * @param driveMode 动作是「切驾驶模式」时的目标模式值；其它动作为 0。
     */
    data class Action(
        val action: Int,
        val driveMode: Int,
    ) {
        /** 是不是「什么都不做」。 */
        val isNone: Boolean get() = action == Prefs.ACTION_NONE
    }

    /** 语音键：单击 / 双击 / 长按。 */
    enum class VoiceSlot { CLICK, DOUBLE, LONG }

    /** 媒体键：上一曲 / 下一曲（只配了长按）。 */
    enum class MediaSlot { PREV, NEXT }

    /**
     * 语音键某一种手势对应的动作。
     *
     * @param action 该手势配置的动作码
     * @param driveMode 该手势配置的驾驶模式目标
     */
    fun voice(prefs: Prefs, slot: VoiceSlot): Action = when (slot) {
        VoiceSlot.CLICK -> Action(prefs.voiceClickAction, prefs.voiceClickMode)
        VoiceSlot.DOUBLE -> Action(prefs.voiceDoubleAction, prefs.voiceDoubleMode)
        VoiceSlot.LONG -> Action(prefs.voiceLongAction, prefs.voiceLongMode)
    }

    /** 媒体键长按对应的动作。 */
    fun media(prefs: Prefs, slot: MediaSlot): Action = when (slot) {
        MediaSlot.PREV -> Action(prefs.mediaPrevLongAction, prefs.mediaPrevLongMode)
        MediaSlot.NEXT -> Action(prefs.mediaNextLongAction, prefs.mediaNextLongMode)
    }

    /**
     * 语音键的**候选 keyCode**。
     *
     * ## ⚠️ 为什么是「一组候选」而不是写死一个
     *
     * 「语音键」在不同车机上的 keyCode 并不统一：
     *
     * | keyCode | 常量 | 谁在用 |
     * |---|---|---|
     * | 108 | `KEYCODE_VOICE_ASSIST`（**Android 13 及以下**） | 本车（API 28）用它，多数车机也是它 |
     * | 231 | `KEYCODE_VOICE_ASSIST`（**Android 14 起改值**） | 新一点的系统 |
     * | 84 | `KEYCODE_SEARCH` | 一些车机把语音键映射成搜索键 |
     * | 204 | `KEYCODE_ASSIST` | 另一些车机用它 |
     * | 112 | `KEYCODE_VOICE_COMMAND` | 少数车机 |
     *
     * ## ⚠️⚠️ 为什么这里写**字面量**而不是 `KeyEvent.KEYCODE_VOICE_ASSIST`
     *
     * 本工程 compileSdk 是 34，而 **`KeyEvent.KEYCODE_VOICE_ASSIST` 在 Android 14 被改了值**：
     * 老系统是 **108**，Android 14 起变成 **231**。
     * ⇒ 源码里写 `KeyEvent.KEYCODE_VOICE_ASSIST` 会编译成 **231**，
     * 拿到 API 28 的车机上就**对不上真实按键**（车机发的是 108）。
     * ⇒ 这里只能写**字面量**，并且两个都收。
     *
     * 实车（领克 AAOS 820A）的真实 keyCode **未知** ⇒ 先把这五个都接上，
     * 同时把**所有到达桌面的 keyCode 记进日志**（见 `ui/KeyActionExecutor`），
     * 用户按一次语音键，诊断包里就能看到真实值，下一轮就能收窄。
     *
     * ⚠️ 风险：这些键码在别的场景可能另有含义（比如 `KEYCODE_SEARCH` 在导航里是搜索）。
     * 因此**只有「按键增强总开关」打开时**才接这些键，
     * 且接住之后**不再往下传**（不会触发系统默认行为）。
     *
     * 第三百一十五轮：候选表移到 [OemKeyProtocol.VOICE_KEY_CODES]（单一来源）——
     * 方控广播里语音键是**私有码 `200231`**，卡在 KeyEvent 通路的那份老清单里认不出来。
     */
    val VOICE_KEY_CODES: IntArray get() = OemKeyProtocol.VOICE_KEY_CODES

    /** 媒体上一曲 keyCode（`KEYCODE_MEDIA_PREVIOUS`，标准码）。 */
    const val KEYCODE_MEDIA_PREVIOUS = 88

    /** 媒体下一曲 keyCode（`KEYCODE_MEDIA_NEXT`，标准码）。 */
    const val KEYCODE_MEDIA_NEXT = 87

    /** 是否是候选语音键。 */
    fun isVoiceKey(keyCode: Int): Boolean = OemKeyProtocol.isVoiceKey(keyCode)

    /** 是媒体上一曲键（含方控私有码 `200088`）。 */
    fun isMediaPrev(keyCode: Int): Boolean = OemKeyProtocol.isMediaPrev(keyCode)

    /** 是媒体下一曲键（含方控私有码 `200087`）。 */
    fun isMediaNext(keyCode: Int): Boolean = OemKeyProtocol.isMediaNext(keyCode)

    /** 是不是本模块关心的按键（语音键 / 媒体上一曲 / 下一曲）。 */
    fun isHandled(keyCode: Int): Boolean =
        isVoiceKey(keyCode) || isMediaPrev(keyCode) || isMediaNext(keyCode)
}
