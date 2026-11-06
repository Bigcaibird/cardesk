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
        assertTrue(QqBroadcast.shouldAccept("com.tencent.qqmusiccar", "generic", "generic"))
        assertTrue(QqBroadcast.shouldAccept("com.tencent.qqmusic", "generic", "generic"))
    }

    @Test
    fun `没有绑定会话且默认源是QQ音乐时采纳`() {
        // 设置页里把「默认媒体源」设成了 QQ 音乐。
        assertTrue(QqBroadcast.shouldAccept("", "qq", "generic"))
        assertTrue(QqBroadcast.shouldAccept(null, "qq", "generic"))
    }

    @Test
    fun `没有绑定会话但当前源是QQ音乐时也采纳`() {
        // 第128轮的真车实况：用户在**卡片来源行**选了 QQ 音乐
        // （design_music_source_v1=qq），设置页的「默认媒体源」压根没写过。
        // 第126轮只读后者 → 4 条广播全被丢弃（真车诊断包实测）。
        assertTrue(QqBroadcast.shouldAccept("", "generic", "qq"))
        assertTrue(QqBroadcast.shouldAccept(null, "generic", "qq"))
        // 两个都选了也一样采纳
        assertTrue(QqBroadcast.shouldAccept("", "qq", "qq"))
    }

    @Test
    fun `没有绑定会话且两个源都不是QQ音乐时不采纳`() {
        assertFalse(QqBroadcast.shouldAccept("", "generic", "generic"))
        assertFalse(QqBroadcast.shouldAccept("", "local", "local"))
        assertFalse(QqBroadcast.shouldAccept(null, null, null))
        assertFalse(QqBroadcast.shouldAccept("", "generic", "netease"))
    }

    @Test
    fun `别的App在播时不许QQ广播盖掉`() {
        // 爱趣听在播（有会话）→ QQ 的广播不能来抢卡片。
        // 注意：即使两个源都选了 QQ 也不行 —— 有别的 App 在播就是让位。
        assertFalse(QqBroadcast.shouldAccept("com.tencent.wecarflow", "qq", "qq"))
        assertFalse(QqBroadcast.shouldAccept("com.android.bluetooth", "qq", "qq"))
        assertFalse(QqBroadcast.shouldAccept("com.netease.cloudmusic.iot", "qq", "qq"))
    }

    @Test
    fun `isQqSelected_认两个源中的任一个`() {
        assertTrue(QqBroadcast.isQqSelected("qq", "generic"))
        assertTrue(QqBroadcast.isQqSelected("generic", "qq"))
        assertTrue(QqBroadcast.isQqSelected("qq", "qq"))
        assertFalse(QqBroadcast.isQqSelected("generic", "generic"))
        assertFalse(QqBroadcast.isQqSelected(null, null))
        assertFalse(QqBroadcast.isQqSelected("netease", "soda"))
    }

    @Test
    fun `爱趣听不被当成QQ音乐`() {
        // 老实现里 `isQqMusicPackage` 特意排除了 wecarflow / aiquting / qqmusictv。
        assertFalse(QqLyric.isQqMusicPackage("com.tencent.wecarflow"))
        assertFalse(QqLyric.isQqMusicPackage("com.tencent.qqmusictv"))
        assertTrue(QqLyric.isQqMusicPackage("com.tencent.qqmusiccar"))
    }
}
