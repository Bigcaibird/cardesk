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
 * 第一九五轮结论「两项都不支持」，**第一九六轮推翻**：
 *
 * - 收藏：**标准评分通道**（`CoreRemoteControlListener.onSetRating`
 *   → `handleCollectOrUnCollectCommand(rating.hasHeart())`）；
 * - 播放模式：只在**进程内**指令中心（`LunaCommandKeys.SWITCH_PLAY_MODE`），
 *   由 BYD 语音通道触发，第三方接不上 → **不支持**。
 *
 * ### 未适配的音乐 App（通用兜底）
 *
 * 靠**运行时探测**（[MediaActions.Probe]）决定亮不亮按钮：
 * 会话发了**心形评分** → 支持收藏；会话**暴露了**播放模式 → 支持播放模式。
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
    fun `汽水音乐支持收藏但不支持播放模式`() {
        // 第一九六轮：汽水的收藏走**标准评分通道**（onSetRating → 收藏），
        // 播放模式没有对外通道 → 按钮隐藏。
        val caps = MediaActions.capsOf(SodaMediaSource.PACKAGE)
        assertTrue(caps.favorite)
        assertFalse(caps.playMode)
        assertTrue(MediaActions.supportsFavorite(SodaMediaSource.PACKAGE))
        assertFalse(MediaActions.supportsPlayMode(SodaMediaSource.PACKAGE))
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

    // ---------------------------------------------------- 通用兜底（运行时探测）

    @Test
    fun `未适配的App探测到心形评分就支持收藏`() {
        // 会话发了心形评分 → 说明它用标准收藏语义 → 亮收藏按钮
        val probe = MediaActions.Probe(favoriteHeart = false)
        val caps = MediaActions.capsOf("com.example.player", probe)
        assertTrue(caps.favorite)
        assertFalse(caps.playMode)
    }

    @Test
    fun `未适配的App没发心形评分就不支持收藏`() {
        // 没有心形评分项 → 不猜 → 不亮收藏按钮
        val probe = MediaActions.Probe(favoriteHeart = null)
        assertFalse(MediaActions.capsOf("com.example.player", probe).favorite)
    }

    @Test
    fun `未适配的App暴露了播放模式就支持播放模式`() {
        val probe = MediaActions.Probe(
            favoriteHeart = null,
            repeatMode = MediaActions.RepeatMode.ALL,
            shuffleMode = MediaActions.ShuffleMode.NONE,
        )
        val caps = MediaActions.capsOf("com.example.player", probe)
        assertFalse(caps.favorite)
        assertTrue(caps.playMode)
    }

    @Test
    fun `未适配的App没探测结果就什么都不支持`() {
        // 没有会话 → 探不到 → 两个按钮都不亮
        val caps = MediaActions.capsOf("com.example.player", null)
        assertEquals(MediaActions.Caps.NONE, caps)
        assertFalse(MediaActions.supportsFavorite("com.example.player"))
        assertFalse(MediaActions.supportsPlayMode("com.example.player"))
    }

    @Test
    fun `已知包不受探测结果影响`() {
        // 网易云 / 汽水在能力表里 → 探测结果说什么都不改它们的结论
        val probe = MediaActions.Probe(favoriteHeart = null)
        assertTrue(MediaActions.capsOf(MediaButtonTransport.NETEASE_PACKAGE, probe).favorite)
        assertTrue(MediaActions.capsOf(MediaButtonTransport.NETEASE_PACKAGE, probe).playMode)
        // 汽水：收藏仍然支持，播放模式仍然不支持（探测到模式字段也不给开）
        assertTrue(MediaActions.capsOf(SodaMediaSource.PACKAGE, probe).favorite)
        assertFalse(MediaActions.capsOf(SodaMediaSource.PACKAGE, probe).playMode)
    }

    // ---------------------------------------------------- 播放模式字段（标准口径）

    @Test
    fun `暴露播放模式的判据是大于NONE`() {
        // API 28 的 PlaybackState 默认值是 NONE(0)（UNSET(-1) 是 API 29 才有）
        // → 必须按「大于 0」判，否则每个会话都会「暴露」播放模式
        assertFalse(MediaActions.exposesPlayMode(MediaActions.RepeatMode.NONE, MediaActions.ShuffleMode.NONE))
        assertFalse(MediaActions.exposesPlayMode(MediaActions.RepeatMode.UNSET, MediaActions.ShuffleMode.UNSET))
        assertTrue(MediaActions.exposesPlayMode(MediaActions.RepeatMode.ALL, MediaActions.ShuffleMode.NONE))
        assertTrue(MediaActions.exposesPlayMode(MediaActions.RepeatMode.ONE, MediaActions.ShuffleMode.NONE))
        assertTrue(MediaActions.exposesPlayMode(MediaActions.RepeatMode.NONE, MediaActions.ShuffleMode.ALL))
    }

    @Test
    fun `从播放状态字段读播放模式`() {
        assertEquals(
            MediaActions.PlayMode.SHUFFLE,
            MediaActions.playModeFromState(MediaActions.RepeatMode.NONE, MediaActions.ShuffleMode.ALL),
        )
        assertEquals(
            MediaActions.PlayMode.REPEAT_ONE,
            MediaActions.playModeFromState(MediaActions.RepeatMode.ONE, MediaActions.ShuffleMode.NONE),
        )
        assertEquals(
            MediaActions.PlayMode.REPEAT_ALL,
            MediaActions.playModeFromState(MediaActions.RepeatMode.ALL, MediaActions.ShuffleMode.NONE),
        )
        // 随机优先于重复
        assertEquals(
            MediaActions.PlayMode.SHUFFLE,
            MediaActions.playModeFromState(MediaActions.RepeatMode.ALL, MediaActions.ShuffleMode.ALL),
        )
        // 没设置 → 不知道
        assertNull(MediaActions.playModeFromState(MediaActions.RepeatMode.NONE, MediaActions.ShuffleMode.NONE))
        assertNull(MediaActions.playModeFromState(MediaActions.RepeatMode.UNSET, MediaActions.ShuffleMode.UNSET))
    }

    @Test
    fun `通用播放模式编码跟网易云那套不是一回事`() {
        // 通用：1=顺序 / 2=单曲 / 3=随机
        assertEquals(1, MediaActions.genericPlayModeCode(MediaActions.PlayMode.REPEAT_ALL))
        assertEquals(2, MediaActions.genericPlayModeCode(MediaActions.PlayMode.REPEAT_ONE))
        assertEquals(3, MediaActions.genericPlayModeCode(MediaActions.PlayMode.SHUFFLE))
        // 网易云：1=顺序 / 2=随机 / 3=单曲 → 同一个模式的编码不一样
        assertEquals(2, MediaActions.neteasePlayModeCode(MediaActions.PlayMode.SHUFFLE))
        assertEquals(3, MediaActions.neteasePlayModeCode(MediaActions.PlayMode.REPEAT_ONE))
    }

    @Test
    fun `通用下发时给的两套参数值一致`() {
        // play_mode（通用编码）与 repeat_mode / shuffle_mode（Android 标准值）
        // 必须指向同一个模式 —— 两套一起发，认哪套的播放器都能听懂。
        val mode = MediaActions.PlayMode.SHUFFLE
        assertEquals(MediaActions.RepeatMode.NONE, MediaActions.androidRepeatMode(mode))
        assertEquals(MediaActions.ShuffleMode.ALL, MediaActions.androidShuffleMode(mode))
        val all = MediaActions.PlayMode.REPEAT_ALL
        assertEquals(MediaActions.RepeatMode.ALL, MediaActions.androidRepeatMode(all))
        assertEquals(MediaActions.ShuffleMode.NONE, MediaActions.androidShuffleMode(all))
        val one = MediaActions.PlayMode.REPEAT_ONE
        assertEquals(MediaActions.RepeatMode.ONE, MediaActions.androidRepeatMode(one))
        assertEquals(MediaActions.ShuffleMode.NONE, MediaActions.androidShuffleMode(one))
    }

    // ---------------------------------------------------- 收藏结果回报

    @Test
    fun `收藏成功按目标态校正`() {
        // 出处：网易云 yc/s0.java → session event code=0 "操作成功"
        assertEquals(
            MediaActions.FavoriteState.FAVORITED,
            MediaActions.favoriteStateFromResult(MediaActions.FavoriteResult.OK, target = true),
        )
        assertEquals(
            MediaActions.FavoriteState.NOT_FAVORITED,
            MediaActions.favoriteStateFromResult(MediaActions.FavoriteResult.OK, target = false),
        )
    }

    @Test
    fun `已经是该状态也按目标态校正`() {
        // code=-2 "已经是收藏状态了" —— 虽然没真改，
        // 但它明确说了「当前状态就是你要的那个」→ 界面要跟上
        assertEquals(
            MediaActions.FavoriteState.FAVORITED,
            MediaActions.favoriteStateFromResult(MediaActions.FavoriteResult.ALREADY, target = true),
        )
        assertEquals(
            MediaActions.FavoriteState.NOT_FAVORITED,
            MediaActions.favoriteStateFromResult(MediaActions.FavoriteResult.ALREADY, target = false),
        )
    }

    @Test
    fun `收藏失败返回null让上层回滚`() {
        assertNull(MediaActions.favoriteStateFromResult(MediaActions.FavoriteResult.FAILED, target = true))
        assertNull(MediaActions.favoriteStateFromResult(-99, target = true))
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
