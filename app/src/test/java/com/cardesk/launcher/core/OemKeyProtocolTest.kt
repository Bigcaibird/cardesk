package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三百一十五轮：**方控按键广播协议**（纯逻辑，JVM 单测）。
 *
 * 覆盖三类：
 * 1. 只有 OEM 按键广播才算「我们的广播」；
 * 2. extra 里的键码/事件类型能正确解析成单击 / 长按；
 * 3. 车机私有键码（`200231` 这些）必须被认成语音键 ——
 *    第218轮只认 AOSP 的 `108/231/84/204/112`，实车按下去「不认识」。
 */
class OemKeyProtocolTest {

    @Test
    fun `语音键实车 action 被认作我们的广播`() {
        assertTrue(OemKeyProtocol.isOemAction(OemKeyProtocol.ACTION_VOICE))
        assertTrue(OemKeyProtocol.isOemAction(OemKeyProtocol.ACTION_CUSTOM))
        assertTrue(OemKeyProtocol.isOemAction(OemKeyProtocol.ACTION_SRC))
        assertTrue(OemKeyProtocol.isOemAction(OemKeyProtocol.ACTION_CALL))
        assertTrue(OemKeyProtocol.isOemAction(OemKeyProtocol.ACTION_MULTI))
    }

    @Test
    fun `别的广播不算我们的`() {
        assertFalse(OemKeyProtocol.isOemAction(null))
        assertFalse(OemKeyProtocol.isOemAction(""))
        assertFalse(OemKeyProtocol.isOemAction("android.intent.action.BOOT_COMPLETED"))
        assertFalse(OemKeyProtocol.isOemAction(OemKeyProtocol.ACTION_VOICE_LAUNCH))
    }

    @Test
    fun `单击与长按的解析`() {
        val click = OemKeyProtocol.parse(
            OemKeyProtocol.ACTION_VOICE,
            OemKeyProtocol.KEY_R_VOICE_ASSIST,
            OemKeyProtocol.ACTION_TYPE_CLICK,
        )
        assertEquals(OemKeyProtocol.Press.CLICK, click?.press)
        assertEquals(200231, click?.keyCode)

        val long = OemKeyProtocol.parse(
            OemKeyProtocol.ACTION_VOICE,
            OemKeyProtocol.KEY_R_VOICE_ASSIST,
            OemKeyProtocol.ACTION_TYPE_LONG,
        )
        assertEquals(OemKeyProtocol.Press.LONG, long?.press)
    }

    @Test
    fun `未知事件类型按单击处理`() {
        val event = OemKeyProtocol.parse(OemKeyProtocol.ACTION_VOICE, 200231, 7)
        assertEquals(OemKeyProtocol.Press.CLICK, event?.press)
    }

    @Test
    fun `不是我们的广播或缺键码都不解析`() {
        assertNull(OemKeyProtocol.parse("android.intent.action.BOOT_COMPLETED", 200231, 0))
        assertNull(OemKeyProtocol.parse(OemKeyProtocol.ACTION_VOICE, 0, 0))
        assertNull(OemKeyProtocol.parse(OemKeyProtocol.ACTION_VOICE, -1, 0))
        assertNull(OemKeyProtocol.parse(null, 200231, 0))
    }

    @Test
    fun `实车私有键码必须是语音键`() {
        // 实车实测：语音键广播的 ECARX_KEY_EVENT_TYPE 就是 200231
        assertTrue(OemKeyProtocol.isVoiceKey(200231))
        assertTrue(OemKeyProtocol.isVoiceKey(OemKeyProtocol.KEY_R_VOICE_FRONT))
        assertTrue(OemKeyProtocol.isVoiceKey(OemKeyProtocol.KEY_ASSIST))
        assertTrue(OemKeyProtocol.isVoiceKey(OemKeyProtocol.KEY_SEARCH))
        assertTrue(OemKeyProtocol.isVoiceKey(108))
        assertTrue(OemKeyProtocol.isVoiceKey(231))
    }

    @Test
    fun `方控媒体键两个码都认`() {
        assertTrue(OemKeyProtocol.isMediaPrev(OemKeyProtocol.KEY_MEDIA_PREV))
        assertTrue(OemKeyProtocol.isMediaPrev(OemKeyProtocol.KEY_R_MEDIA_PREV))
        assertTrue(OemKeyProtocol.isMediaNext(OemKeyProtocol.KEY_MEDIA_NEXT))
        assertTrue(OemKeyProtocol.isMediaNext(OemKeyProtocol.KEY_R_MEDIA_NEXT))
        // 媒体键不是语音键；语音键不是媒体键
        assertFalse(OemKeyProtocol.isVoiceKey(OemKeyProtocol.KEY_R_MEDIA_PREV))
        assertFalse(OemKeyProtocol.isMediaNext(200231))
    }

    @Test
    fun `键名带原值方便实车排障`() {
        assertEquals("语音键(200231)", OemKeyProtocol.keyName(200231))
        assertEquals("上一曲(200088)", OemKeyProtocol.keyName(200088))
        assertEquals("未知(4242)", OemKeyProtocol.keyName(4242))
    }

    @Test
    fun `诊断行描述包含手势与键码`() {
        val event = OemKeyProtocol.OemKeyEvent(
            keyCode = OemKeyProtocol.KEY_R_VOICE_ASSIST,
            press = OemKeyProtocol.Press.LONG,
            sourceAction = OemKeyProtocol.ACTION_VOICE,
        )
        assertEquals("长按 · 语音键(200231)", OemKeyProtocol.describe(event))
    }
}
