package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 音乐车机版**广播协议**的解析与控制（第126轮）。
 *
 * ## 这里的每一条断言都来自**实测**，不是猜的
 *
 * 第126轮在模拟器上装了真的 `com.tencent.qqmusiccar` 1.9.8.22
 * （去掉 native 库重签后安装），`adb logcat` 抓到的**原文**如下：
 *
 * ```
 * D BroadcastSenderCenterForThird: updatePlayState commandString :{"module":"play",
 *   "command":{"method":"update_state","data":{"state":0,"curr_time":0,"total_time":0,
 *   "key_title":"","key_artist":[{"singer":""}],"key_album":"","key_is_fav":0}}}
 * ```
 *
 * 于是三件事被钉死了：
 *
 * 1. 顶层是 `{module, command:{method, data}}`；
 * 2. 歌名是 `key_title`、专辑是 `key_album`；
 * 3. **歌手 `key_artist` 是数组** `[{"singer":"…"}]` —— 以前按字符串解析，
 *    所以永远取不到歌手。
 *
 * 纯 Kotlin，不碰 Android —— 直接 JVM 单测。
 */
class QqBroadcastTest {

    // ------------------------------------------------------------ 报文解析

    @Test
    fun `解析实测抓到的update_state报文`() {
        // 这一行是从模拟器 logcat 里原样抄下来的（2026-11-05）。
        val raw = """{"module":"play","command":{"method":"update_state","data":{"state":0,""" +
            """"curr_time":0,"total_time":0,"key_title":"","key_artist":[{"singer":""}],""" +
            """"key_album":"","key_is_fav":0}}}"""
        val p = QqBroadcast.parse(raw)
        assertNotNull("应该能解析出内容", p)
        assertEquals("play", p!!.module)
        assertEquals("update_state", p.method)
        assertEquals(0L, p.positionMs)
        assertEquals(0L, p.durationMs)
        assertEquals("", p.title)
        assertEquals("", p.artist)
        assertEquals("", p.album)
        assertEquals(QqBroadcast.STATE_NONE, p.state)
        assertFalse("空歌名不算有歌", p.hasSong)
        assertFalse("没有歌词", p.hasLyric)
        assertFalse("state=0 不是在放", p.playing)
    }

    @Test
    fun `解析update_song拿歌名歌手专辑`() {
        val raw = """
            {"module":"play","command":{"method":"update_song","data":{"key_title":"春风吹",
            "key_artist":[{"singer":"方大同"}],"key_album":"十五","key_is_fav":0}}}
        """.trimIndent()
        val p = QqBroadcast.parse(raw)!!
        assertEquals("update_song", p.method)
        assertEquals("春风吹", p.title)
        // 关键：歌手在**数组**里，解析器要能取到第一个 singer
        assertEquals("方大同", p.artist)
        assertEquals("十五", p.album)
        assertTrue(p.hasSong)
        assertFalse(p.hasLyric)
    }

    @Test
    fun `解析update_lyric拿歌词和进度`() {
        val raw = """
            {"module":"play","command":{"method":"update_lyric","data":{"code":0,
            "lyric":"[ti:春风吹]\n[ar:方大同]\n[00:00.00]春风吹过玉门关","curr_time":115208,
            "total_time":200666,"key_title":"春风吹","key_artist":[{"singer":"方大同"}],
            "key_album":"十五","key_is_fav":1}}}
        """.trimIndent()
        val p = QqBroadcast.parse(raw)!!
        assertEquals("update_lyric", p.method)
        assertTrue(p.hasLyric)
        assertTrue(p.lyric.contains("春风吹过玉门关"))
        assertEquals(115208L, p.positionMs)
        assertEquals(200666L, p.durationMs)
        assertEquals("春风吹", p.title)
        assertEquals("方大同", p.artist)
    }

    @Test
    fun `歌手是纯字符串时也能取到`() {
        // 防御性：万一某个版本把 key_artist 写成字符串。
        val raw = """
            {"command":{"method":"update_song","data":{"key_title":"夜曲","key_artist":"周杰伦"}}}
        """.trimIndent()
        assertEquals("周杰伦", QqBroadcast.parse(raw)!!.artist)
    }

    @Test
    fun `歌手是对象时也能取到`() {
        val raw = """
            {"command":{"method":"update_song","data":{"key_title":"夜曲","key_artist":{"singer":"周杰伦"}}}}
        """.trimIndent()
        assertEquals("周杰伦", QqBroadcast.parse(raw)!!.artist)
    }

    @Test
    fun `歌手数组里第一个空就取第二个`() {
        val raw = """
            {"command":{"method":"update_song","data":{"key_title":"夜曲",
            "key_artist":[{"singer":""},{"singer":"方大同"}]}}}
        """.trimIndent()
        assertEquals("方大同", QqBroadcast.parse(raw)!!.artist)
    }

    @Test
    fun `没有state字段时state是负一`() {
        // -1 = 这条广播里根本没有播放态（区别于「有但是 0」）。
        val raw = """
            {"command":{"method":"update_song","data":{"key_title":"稻香"}}}
        """.trimIndent()
        val p = QqBroadcast.parse(raw)!!
        assertEquals(-1, p.state)
        assertFalse(p.playing)
    }

    @Test
    fun `播放态判定覆盖五个取值`() {
        fun stateOf(v: Int) = QqBroadcast.parse(
            """{"command":{"method":"update_state","data":{"state":$v}}}"""
        )!!.state

        assertEquals(QqBroadcast.STATE_NONE, stateOf(0))
        assertEquals(QqBroadcast.STATE_BUFFERING, stateOf(1))
        assertEquals(QqBroadcast.STATE_PLAYING, stateOf(2))
        assertEquals(QqBroadcast.STATE_PAUSED, stateOf(3))
        assertEquals(QqBroadcast.STATE_STOPPED, stateOf(4))

        // 只有「播放中」和「缓冲中」算在放；暂停了就不该抢原车的播放权。
        assertTrue(QqBroadcast.parse("""{"command":{"data":{"state":2}}}""")!!.playing)
        assertTrue(QqBroadcast.parse("""{"command":{"data":{"state":1}}}""")!!.playing)
        assertFalse(QqBroadcast.parse("""{"command":{"data":{"state":3}}}""")!!.playing)
        assertFalse(QqBroadcast.parse("""{"command":{"data":{"state":4}}}""")!!.playing)
        assertFalse(QqBroadcast.parse("""{"command":{"data":{"state":0}}}""")!!.playing)
    }

    @Test
    fun `不是JSON或没有command时返回空`() {
        assertNull(QqBroadcast.parse(null))
        assertNull(QqBroadcast.parse(""))
        assertNull(QqBroadcast.parse("这不是 JSON"))
        assertNull(QqBroadcast.parse("""{"foo":1}"""))
        assertNull(QqBroadcast.parse("""{"command":"不是对象"}"""))
    }

    @Test
    fun `data缺失时不崩`() {
        val p = QqBroadcast.parse("""{"module":"play","command":{"method":"update_song"}}""")
        assertNotNull(p)
        assertEquals("update_song", p!!.method)
        assertEquals("", p.title)
        assertFalse(p.hasSong)
    }

    // ------------------------------------------------------------ 命令码映射

    @Test
    fun `动作翻译成QQ音乐的命令码`() {
        // 这套编号在**广播通道**和 **URI 通道**里是同一套
        // （反编译 + 实测都对上了：m0=2 → playPrev、m0=3 → playNext）。
        assertEquals(QqBroadcast.CMD_PLAY, QqBroadcast.commandFor("play"))
        assertEquals(QqBroadcast.CMD_PAUSE, QqBroadcast.commandFor("pause"))
        assertEquals(QqBroadcast.CMD_NEXT, QqBroadcast.commandFor("next"))
        assertEquals(QqBroadcast.CMD_PREVIOUS, QqBroadcast.commandFor("previous"))
        assertEquals(QqBroadcast.CMD_STOP, QqBroadcast.commandFor("stop"))
        assertNull(QqBroadcast.commandFor("seek"))
        assertNull(QqBroadcast.commandFor(""))
    }

    @Test
    fun `传输控制URI带action20和m0`() {
        // `action=20` 才是真正下发到播放器的（controlPlay(m0)）；
        // 走广播通道的 action=0/2/3 只会把 QQ 音乐的界面打开。
        assertEquals(
            "qqmusiccar://?action=20&m0=0",
            QqBroadcast.controlUri("play"),
        )
        assertEquals("qqmusiccar://?action=20&m0=1", QqBroadcast.controlUri("pause"))
        assertEquals("qqmusiccar://?action=20&m0=2", QqBroadcast.controlUri("previous"))
        assertEquals("qqmusiccar://?action=20&m0=3", QqBroadcast.controlUri("next"))
        assertNull(QqBroadcast.controlUri("seek"))
    }

    @Test
    fun `协议常量与真包一致`() {
        // 改错任何一个常量，QQ 音乐就收不到我们的命令 —— 这里钉死。
        assertEquals(
            "com.tencent.qqmusiccar.action.PLAY_COMMAND_SEND_FOR_THIRD",
            QqBroadcast.ACTION_FROM_QQ,
        )
        assertEquals("com.tencent.qqmusiccar.EXTRA_COMMAND_DATA", QqBroadcast.EXTRA)
        assertEquals("com.tencent.qqmusiccar.action", QqBroadcast.ACTION_TO_QQ)
        assertEquals("action", QqBroadcast.EXTRA_ACTION)
        assertEquals("pull_from", QqBroadcast.EXTRA_PULL_FROM)
        assertEquals(45124L, QqBroadcast.DEFAULT_PULL_FROM)
        assertEquals(100, QqBroadcast.CMD_ALLOW_SEND_OUTSIDE)
        assertEquals("qqmusiccar", QqBroadcast.SCHEME)
        assertEquals(20, QqBroadcast.URI_ACTION_CONTROL)
        assertEquals("com.tencent.qqmusiccar", QqBroadcast.PACKAGE)
    }

    // ------------------------------------------------------------ method 提取（第128轮）

    @Test
    fun `methodOf能取出广播里的method`() {
        // 真车诊断包里收到的那一条（QQ 音乐说「我在不在前台」，本来就不含歌名）：
        val raw = """{"module":"app","command":{"method":"update_app_foreground","data":{"isForeground":2}}}"""
        assertEquals("update_app_foreground", QqBroadcast.methodOf(raw))
    }

    @Test
    fun `methodOf对play模块的曲目广播也有效`() {
        val raw = """{"module":"play","command":{"method":"update_song","data":{"key_title":"春风吹"}}}"""
        assertEquals("update_song", QqBroadcast.methodOf(raw))
        val raw2 = """{"module":"play","command":{"method":"update_lyric","data":{"lyric":"[00:00.00]x"}}}"""
        assertEquals("update_lyric", QqBroadcast.methodOf(raw2))
    }

    @Test
    fun `methodOf解析不出来时返回空串而不是抛异常`() {
        assertEquals("", QqBroadcast.methodOf(null))
        assertEquals("", QqBroadcast.methodOf(""))
        assertEquals("", QqBroadcast.methodOf("不是JSON"))
        assertEquals("", QqBroadcast.methodOf("""{"module":"play"}"""))
        assertEquals("", QqBroadcast.methodOf("""{"command":{"data":{}}}"""))
    }

    // ------------------------------------------------------------ LRC 头

    @Test
    fun `LRC元数据能单独解析`() {
        val meta = LrcParser.parseMeta("[ti:春风吹]\n[ar:方大同]\n[al:十五]\n[00:00.00]x")
        assertEquals("春风吹", meta.title)
        assertEquals("方大同", meta.artist)
        assertEquals("十五", meta.album)
        assertEquals("", LrcParser.parseMeta(null).title)
    }
}
