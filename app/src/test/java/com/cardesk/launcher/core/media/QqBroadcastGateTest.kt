package com.cardesk.launcher.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 广播的**采纳门**（第126轮）。
 *
 * 老实现的门是「当前绑定的会话必须是 QQ 音乐」—— 而 QQ 音乐车机版
 * **不发布会话**，桌面永远绑不到它 → 这道门永远关着 → 歌词和歌名永远不来。
 *
 * 纯逻辑，不碰 Android。
 */
class QqBroadcastGateTest {

    @Test
    fun `绑定的就是QQ音乐时采纳`() {
        assertTrue(QqBroadcast.shouldAccept("com.tencent.qqmusiccar", "generic"))
        assertTrue(QqBroadcast.shouldAccept("com.tencent.qqmusic", "generic"))
    }

    @Test
    fun `没有绑定会话且默认源是QQ音乐时采纳`() {
        // 真车上的实际情况：QQ 音乐不发会话，桌面绑不到它，
        // 但用户把「默认媒体源」设成了 QQ音乐。
        assertTrue(QqBroadcast.shouldAccept("", "qq"))
        assertTrue(QqBroadcast.shouldAccept(null, "qq"))
    }

    @Test
    fun `没有绑定会话但默认源不是QQ音乐时不采纳`() {
        assertFalse(QqBroadcast.shouldAccept("", "generic"))
        assertFalse(QqBroadcast.shouldAccept("", "local"))
        assertFalse(QqBroadcast.shouldAccept(null, null))
    }

    @Test
    fun `别的App在播时不许QQ广播盖掉`() {
        // 爱趣听在播（有会话）→ QQ 的广播不能来抢卡片。
        assertFalse(QqBroadcast.shouldAccept("com.tencent.wecarflow", "qq"))
        assertFalse(QqBroadcast.shouldAccept("com.android.bluetooth", "qq"))
        assertFalse(QqBroadcast.shouldAccept("com.netease.cloudmusic.iot", "qq"))
    }

    @Test
    fun `爱趣听不被当成QQ音乐`() {
        // 老实现里 `isQqMusicPackage` 特意排除了 wecarflow / aiquting / qqmusictv。
        assertFalse(QqLyric.isQqMusicPackage("com.tencent.wecarflow"))
        assertFalse(QqLyric.isQqMusicPackage("com.tencent.qqmusictv"))
        assertTrue(QqLyric.isQqMusicPackage("com.tencent.qqmusiccar"))
    }
}
