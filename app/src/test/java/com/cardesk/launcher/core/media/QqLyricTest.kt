package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 车机歌词广播解析的纯逻辑单测。
 *
 *
 * 只认 `command.method == "update_lyric"` 的 `data.lyric`。
 */
class QqLyricTest {

    @Test
    fun parseLyric_readsUpdateLyricCommand() {
        val raw = """
            {"command":{"method":"update_lyric","data":{"lyric":"[00:00.00]第一行"}}}
        """.trimIndent()
        assertEquals("[00:00.00]第一行", QqLyric.parseLyric(raw))
    }

    @Test
    fun parseLyric_ignoresOtherMethods() {
        val song = """{"command":{"method":"update_song","data":{"lyric":"[00:00.00]x"}}}"""
        assertEquals("", QqLyric.parseLyric(song))

        val album = """{"command":{"method":"update_album","data":{"album_url":"http://x"}}}"""
        assertEquals("", QqLyric.parseLyric(album))
    }

    @Test
    fun parseLyric_ignoresMalformedPayloads() {
        assertEquals("", QqLyric.parseLyric(null))
        assertEquals("", QqLyric.parseLyric(""))
        assertEquals("", QqLyric.parseLyric("not json"))
        assertEquals("", QqLyric.parseLyric("""{"other":1}"""))
        assertEquals("", QqLyric.parseLyric("""{"command":{"method":"update_lyric"}}"""))
        assertEquals("", QqLyric.parseLyric("""{"command":{"method":"update_lyric","data":{}}}"""))
    }

    @Test
    fun isQqMusicPackage_onlyAcceptsQqMusicCar() {
        assertTrue(QqLyric.isQqMusicPackage("com.tencent.qqmusiccar"))
        assertFalse(QqLyric.isQqMusicPackage("com.tencent.wecarflow"))
        assertFalse(QqLyric.isQqMusicPackage("com.tencent.qqmusictv"))
        assertFalse(QqLyric.isQqMusicPackage("com.netease.cloudmusic.iot"))
        assertFalse(QqLyric.isQqMusicPackage(null))
    }
}
