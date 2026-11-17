package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「收藏 / 播放模式」的**协议抽象层**（第一九五轮）。
 *
 * ## 这里断言的每一条都有出处
 *
 * ### QQ 音乐车机版（`com.tencent.qqmusiccar` 1.9.8.22 反编译 + 模拟器实测）
 *
 * - 收藏码：`BroadcastReceiverCenterForThird.controlPlay()` 的 `case 5 / 6`
 *   → `SongInfoUtilsForShow.s(context, songInfo, 5, "")`；
 * - 播放模式码：同类的 `MODE_SONG_REPEAT = 101` /
 *   `MODE_LIST_REPEAT = 103` / `MODE_SONG_SHUFFLE = 105`；
 * - 收藏态回传：`BroadcastSenderCenterForThird.putSongInfo()`
 *   → `key_is_fav`（**1 = 已收藏，0 = 未收藏**）。
 *
 * ### 网易云车机版（`com.netease.cloudmusic.iot` 6.2.81 反编译）
 *
 * - 收藏动作名：`"star_action"`（`vc/p.java` 的动作表 → `yc/s0`）；
 * - 播放模式动作名：`"play_mode_action"`（→ `yc/y`）；
 * - 读播放模式动作名：`"get_play_mode_action"`（→ `yc/i`）；
 * - 播放模式编码：车机侧 **1 = 顺序 / 2 = 随机 / 3 = 单曲**
 *   （`vc/g.java` 的 `onPlayModeChange`：网易云内部 0/1/2 → 车机 1/3/2）。
 *
 * ### 汽水音乐车机版（`com.luna.music.car`）
 *
 * browse 树为空、全包找不到收藏/播放模式的对外通道 → **两项都不支持**。
 *
 * 纯 Kotlin，不碰 Android —— 直接 JVM 单测。
 */
class MediaActionsTest {

    // ------------------------------------------------------------ 能力表

    @Test
    fun `QQ音乐支持收藏和播放模式`() {
        val caps = MediaActions.capsOf(QqBroadcast.PACKAGE)
        assertTrue(caps.favorite)
        assertTrue(caps.playMode)
    }

    @Test
    fun `网易云支持收藏和播放模式`() {
        val caps = MediaActions.capsOf(MediaButtonTransport.NETEASE_PACKAGE)
        assertTrue(caps.favorite)
        assertTrue(caps.playMode)
    }

    @Test
    fun `汽水音乐两项都不支持`() {
        val caps = MediaActions.capsOf("com.luna.music.car")
        assertFalse(caps.favorite)
        assertFalse(caps.playMode)
        assertFalse(MediaActions.supportsFavorite("com.luna.music.car"))
        assertFalse(MediaActions.supportsPlayMode("com.luna.music.car"))
    }

    @Test
    fun `能力表按包名查而不是按来源id查`() {
        // 同一个「网易云音乐」来源 id 下，另一个包（手机版 / clone）
        // 没有这两条通道 —— 能力跟包走，不跟 id 走。
        assertFalse(MediaActions.supportsFavorite("com.netease.cloudmusic"))
        assertFalse(MediaActions.supportsFavorite("com.tencent.qqmusic"))
    }

    @Test
    fun `没填进能力表的包一律不支持`() {
        assertFalse(MediaActions.supportsFavorite(null))
        assertFalse(MediaActions.supportsFavorite(""))
        assertFalse(MediaActions.supportsFavorite("   "))
        assertFalse(MediaActions.supportsPlayMode("com.example.player"))
        assertEquals(MediaActions.Caps.NONE, MediaActions.capsOf("com.example.player"))
    }

    // ------------------------------------------------------------ 收藏态

    @Test
    fun `收藏态是三态`() {
        assertFalse(MediaActions.FavoriteState.UNKNOWN.known)
        assertFalse(MediaActions.FavoriteState.UNKNOWN.isFavorited)
        assertTrue(MediaActions.FavoriteState.FAVORITED.known)
        assertTrue(MediaActions.FavoriteState.FAVORITED.isFavorited)
        assertTrue(MediaActions.FavoriteState.NOT_FAVORITED.known)
        assertFalse(MediaActions.FavoriteState.NOT_FAVORITED.isFavorited)
    }

    @Test
    fun `QQ的key_is_fav读成收藏态`() {
        // 出处：putSongInfo() → `i4 = (songInfo == null || !MyFavManager.H().R(songInfo)) ? 0 : 1`
        assertEquals(MediaActions.FavoriteState.FAVORITED, MediaActions.favoriteFromRaw(1))
        assertEquals(MediaActions.FavoriteState.NOT_FAVORITED, MediaActions.favoriteFromRaw(0))
        // -1 = 这条广播里没有这个字段 → **不知道**（不能当成「未收藏」）
        assertEquals(MediaActions.FavoriteState.UNKNOWN, MediaActions.favoriteFromRaw(-1))
    }

    @Test
    fun `未知不覆盖已知`() {
        // QQ 的 update_state 广播里没有 key_is_fav →
        // 若直接覆盖，已知的「已收藏」会被冲成「不知道」→ 界面会闪一下。
        val known = MediaActions.FavoriteState.FAVORITED
        assertEquals(known, MediaActions.mergeFavorite(known, MediaActions.FavoriteState.UNKNOWN))
        assertEquals(known, MediaActions.mergeFavorite(known, null))
        // 确实带了这个字段的新值 → 更新
        assertEquals(
            MediaActions.FavoriteState.NOT_FAVORITED,
            MediaActions.mergeFavorite(known, MediaActions.FavoriteState.NOT_FAVORITED),
        )
    }

    // ------------------------------------------------------------ 播放模式

    @Test
    fun `播放模式按顺序单曲随机循环`() {
        assertEquals(
            MediaActions.PlayMode.REPEAT_ONE,
            MediaActions.nextPlayMode(MediaActions.PlayMode.REPEAT_ALL),
        )
        assertEquals(
            MediaActions.PlayMode.SHUFFLE,
            MediaActions.nextPlayMode(MediaActions.PlayMode.REPEAT_ONE),
        )
        assertEquals(
            MediaActions.PlayMode.REPEAT_ALL,
            MediaActions.nextPlayMode(MediaActions.PlayMode.SHUFFLE),
        )
    }

    @Test
    fun `播放模式未知时先落到顺序播放`() {
        // 最保守的那个档位：随机播放会让「下一首听到的歌跟预期不一样」
        assertEquals(MediaActions.PlayMode.REPEAT_ALL, MediaActions.nextPlayMode(null))
    }

    @Test
    fun `网易云的播放模式编码`() {
        // 车机侧：1 = 顺序 / 2 = 随机 / 3 = 单曲
        assertEquals(1, MediaActions.neteasePlayModeCode(MediaActions.PlayMode.REPEAT_ALL))
        assertEquals(2, MediaActions.neteasePlayModeCode(MediaActions.PlayMode.SHUFFLE))
        assertEquals(3, MediaActions.neteasePlayModeCode(MediaActions.PlayMode.REPEAT_ONE))
    }

    @Test
    fun `网易云的播放模式编码能反解回来`() {
        assertEquals(
            MediaActions.PlayMode.REPEAT_ALL,
            MediaActions.playModeFromNeteaseCode(MediaActions.NeteasePlayMode.REPEAT_ALL),
        )
        assertEquals(
            MediaActions.PlayMode.SHUFFLE,
            MediaActions.playModeFromNeteaseCode(MediaActions.NeteasePlayMode.SHUFFLE),
        )
        assertEquals(
            MediaActions.PlayMode.REPEAT_ONE,
            MediaActions.playModeFromNeteaseCode(MediaActions.NeteasePlayMode.REPEAT_ONE),
        )
        // 不认识的值 → 当「不知道」处理，别瞎猜
        assertNull(MediaActions.playModeFromNeteaseCode(0))
        assertNull(MediaActions.playModeFromNeteaseCode(9))
    }

    // ------------------------------------------------------------ QQ 协议码

    @Test
    fun `QQ的收藏与播放模式命令码`() {
        // 出处：BroadcastReceiverCenterForThird 的常量
        assertEquals(QqBroadcast.CMD_FAVORITE, QqBroadcast.commandFor(QqBroadcast.ACTION_FAVORITE))
        assertEquals(QqBroadcast.CMD_UNFAVORITE, QqBroadcast.commandFor(QqBroadcast.ACTION_UNFAVORITE))
        assertEquals(QqBroadcast.MODE_SONG_REPEAT, QqBroadcast.commandFor(QqBroadcast.MODE_REPEAT_ONE))
        assertEquals(QqBroadcast.MODE_LIST_REPEAT, QqBroadcast.commandFor(QqBroadcast.MODE_REPEAT_ALL))
        assertEquals(QqBroadcast.MODE_SONG_SHUFFLE, QqBroadcast.commandFor(QqBroadcast.MODE_SHUFFLE))
        assertEquals(5, QqBroadcast.CMD_FAVORITE)
        assertEquals(6, QqBroadcast.CMD_UNFAVORITE)
        assertEquals(101, QqBroadcast.MODE_SONG_REPEAT)
        assertEquals(103, QqBroadcast.MODE_LIST_REPEAT)
        assertEquals(105, QqBroadcast.MODE_SONG_SHUFFLE)
    }

    @Test
    fun `QQ的收藏与播放模式走URI通道`() {
        // 只有 action=20 才会真正下发给播放器（controlPlay(m0)）
        assertEquals(
            "qqmusiccar://?action=20&m0=5",
            QqBroadcast.controlUri(QqBroadcast.ACTION_FAVORITE),
        )
        assertEquals(
            "qqmusiccar://?action=20&m0=6",
            QqBroadcast.controlUri(QqBroadcast.ACTION_UNFAVORITE),
        )
        assertEquals(
            "qqmusiccar://?action=20&m0=101",
            QqBroadcast.controlUri(QqBroadcast.MODE_REPEAT_ONE),
        )
        assertEquals(
            "qqmusiccar://?action=20&m0=105",
            QqBroadcast.controlUri(QqBroadcast.MODE_SHUFFLE),
        )
    }

    @Test
    fun `QQ广播里能读出收藏态`() {
        // 这一行是从模拟器 logcat 里原样抄下来的（2026-11-05）
        val raw = """{"module":"play","command":{"method":"update_song","data":{"state":2,""" +
            """"curr_time":115208,"total_time":200666,"lyric":"[ti:春风吹]",""" +
            """"key_title":"春风吹","key_artist":[{"singer":"方大同"}],""" +
            """"key_album":"十五","key_is_fav":1}}}"""
        val payload = QqBroadcast.parse(raw)
        assertTrue(payload != null)
        assertEquals(1, payload!!.favorite)
        assertTrue(payload.favoriteKnown)
        assertTrue(payload.favorited)
    }

    @Test
    fun `QQ广播里没有收藏字段时是不知道`() {
        val raw = """{"module":"play","command":{"method":"update_state","data":{"state":0,""" +
            """"curr_time":0,"total_time":0,"key_title":"","key_artist":[{"singer":""}],""" +
            """"key_album":"","key_is_fav":0}}}"""
        val payload = QqBroadcast.parse(raw)
        assertTrue(payload != null)
        // 这一条里**有** key_is_fav（= 0）→ 明确「未收藏」
        assertTrue(payload!!.favoriteKnown)
        assertFalse(payload.favorited)

        // 真的没有这个字段的那一条
        val noFav = """{"module":"play","command":{"method":"update_state","data":{"state":2}}}"""
        val p2 = QqBroadcast.parse(noFav)
        assertTrue(p2 != null)
        assertEquals(-1, p2!!.favorite)
        assertFalse(p2.favoriteKnown)
        assertEquals(MediaActions.FavoriteState.UNKNOWN, MediaActions.favoriteFromRaw(p2.favorite))
    }
}
