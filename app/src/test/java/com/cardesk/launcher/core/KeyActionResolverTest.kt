package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第218轮：**按键增强的动作解析**。
 *
 * 覆盖「哪个键、哪种手势 → 做什么动作」这一层的映射，
 * 以及「哪些 keyCode 归本模块管」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyActionResolverTest {

    private fun prefs(): Prefs = Prefs(RuntimeEnvironment.getApplication())

    @Test
    fun `语音键三种手势分别取各自的配置`() {
        val p = prefs()
        p.voiceClickAction = Prefs.ACTION_DRIVE_MODE
        p.voiceDoubleAction = Prefs.ACTION_360_TOGGLE
        p.voiceLongAction = Prefs.ACTION_VOICE_ASSIST

        assertEquals(
            Prefs.ACTION_DRIVE_MODE,
            KeyActionResolver.voice(p, KeyActionResolver.VoiceSlot.CLICK).action,
        )
        assertEquals(
            Prefs.ACTION_360_TOGGLE,
            KeyActionResolver.voice(p, KeyActionResolver.VoiceSlot.DOUBLE).action,
        )
        assertEquals(
            Prefs.ACTION_VOICE_ASSIST,
            KeyActionResolver.voice(p, KeyActionResolver.VoiceSlot.LONG).action,
        )
    }

    @Test
    fun `语音键动作带着自己的驾驶模式目标`() {
        val p = prefs()
        p.voiceClickAction = Prefs.ACTION_DRIVE_MODE
        p.voiceClickMode = 570491139
        val a = KeyActionResolver.voice(p, KeyActionResolver.VoiceSlot.CLICK)
        assertEquals(Prefs.ACTION_DRIVE_MODE, a.action)
        assertEquals(570491139, a.driveMode)
    }

    @Test
    fun `媒体键长按取各自的配置`() {
        val p = prefs()
        p.mediaPrevLongAction = Prefs.ACTION_DRIVE_MODE
        p.mediaNextLongAction = Prefs.ACTION_360_TOGGLE
        assertEquals(
            Prefs.ACTION_DRIVE_MODE,
            KeyActionResolver.media(p, KeyActionResolver.MediaSlot.PREV).action,
        )
        assertEquals(
            Prefs.ACTION_360_TOGGLE,
            KeyActionResolver.media(p, KeyActionResolver.MediaSlot.NEXT).action,
        )
    }

    @Test
    fun `默认全是无动作`() {
        val p = prefs()
        assertTrue(KeyActionResolver.voice(p, KeyActionResolver.VoiceSlot.CLICK).isNone)
        assertTrue(KeyActionResolver.voice(p, KeyActionResolver.VoiceSlot.DOUBLE).isNone)
        assertTrue(KeyActionResolver.media(p, KeyActionResolver.MediaSlot.PREV).isNone)
    }

    @Test
    fun `语音键候选 keyCode 包含 AOSP 标准的 108`() {
        assertTrue(KeyActionResolver.isVoiceKey(108))
        assertTrue(KeyActionResolver.isVoiceKey(84))
        assertTrue(KeyActionResolver.isVoiceKey(204))
        assertTrue(KeyActionResolver.isVoiceKey(112))
        assertFalse(KeyActionResolver.isVoiceKey(4))
    }

    /**
     * Android 14 起 `KeyEvent.KEYCODE_VOICE_ASSIST` 从 108 改成了 231。
     *
     * ⚠️ 本车（API 28）发的是 108，但新系统的语音键是 231 ⇒ 两个都得在候选表里。
     * （源码里**不能**写 `KeyEvent.KEYCODE_VOICE_ASSIST` —— compileSdk 34 上它是 231。）
     */
    @Test
    fun `语音键候选 keyCode 同时包含 Android14 的新值 231`() {
        assertTrue(KeyActionResolver.isVoiceKey(231))
        assertTrue(KeyActionResolver.isHandled(231))
    }

    @Test
    fun `媒体键 keyCode 识别`() {
        assertTrue(KeyActionResolver.isMediaPrev(88))
        assertTrue(KeyActionResolver.isMediaNext(87))
        assertFalse(KeyActionResolver.isMediaPrev(87))
        assertFalse(KeyActionResolver.isMediaNext(88))
    }

    @Test
    fun `只管语音键和两个媒体键`() {
        assertTrue(KeyActionResolver.isHandled(108))
        assertTrue(KeyActionResolver.isHandled(87))
        assertTrue(KeyActionResolver.isHandled(88))
        assertFalse(KeyActionResolver.isHandled(4)) // BACK
        assertFalse(KeyActionResolver.isHandled(66)) // ENTER
    }
}
