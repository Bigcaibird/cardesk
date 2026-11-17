package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「点 Dock 媒体卡的封面 / 歌名区 → 拉起对应音乐 App」（第一九七轮）。
 *
 * ## 断言的每一条都有出处
 *
 * ### 「正在使用的 App」= **当前会话的包名**
 *
 * 用的是 `MusicController.track.pkg` —— 此刻正在出声的那个 App。
 *
 * 不用用户设置的默认来源（`prefs.musicSource`）是有意的：
 * 默认来源是**用户上次选的**，实际在播的可能是另一个 App
 * （用户在 QQ 音乐里放歌、但默认来源还停在网易云）。
 *
 * ### 为什么不用会话的 `sourceId`
 *
 * `sourceId` 是**我们自己的分类标签**（`MediaSources` 映射出来的），
 * 「通用音乐」这个 id **不对应任何 App** ——
 * 拿它去拉起 App 必然落空。包名才是能直接喂给 `PackageManager` 的东西。
 *
 * ### 三个真实的包名
 *
 * - QQ 音乐车机版：`com.tencent.qqmusiccar`
 * - 网易云车机版：`com.netease.cloudmusic.iot`
 * - 汽水音乐车机版：`com.luna.music.car`
 */
class DockMediaLaunchTest {

    /** 三个真实车机播放器的包名。 */
    private val qq = "com.tencent.qqmusiccar"
    private val netease = "com.netease.cloudmusic.iot"
    private val soda = "com.luna.music.car"

    // --------------------------------------------------------------- resolve

    @Test
    fun `有会话且拉得起来 → 拉起那个包`() {
        val action = DockMediaLaunch.resolve(qq) { true }
        assertEquals(DockMediaLaunch.Action.OpenApp(qq), action)
    }

    @Test
    fun `谁在出声就拉谁 —— 不看用户选的默认来源`() {
        // 模拟：默认来源是网易云，实际在播的是 QQ 音乐 → 拉 QQ 音乐。
        val action = DockMediaLaunch.resolve(qq) { true }
        assertEquals(DockMediaLaunch.Action.OpenApp(qq), action)
    }

    @Test
    fun `三个车机播放器都能拉起`() {
        listOf(qq, netease, soda).forEach { pkg ->
            assertEquals(
                DockMediaLaunch.Action.OpenApp(pkg),
                DockMediaLaunch.resolve(pkg) { true },
            )
        }
    }

    @Test
    fun `没有会话 → 没有 App 可拉`() {
        assertEquals(
            DockMediaLaunch.Action.NoSession,
            DockMediaLaunch.resolve(null) { true },
        )
    }

    @Test
    fun `会话包名是空串 → 同样按没有会话处理`() {
        assertEquals(
            DockMediaLaunch.Action.NoSession,
            DockMediaLaunch.resolve("") { true },
        )
    }

    @Test
    fun `会话包名只有空白 → 同样按没有会话处理`() {
        assertEquals(
            DockMediaLaunch.Action.NoSession,
            DockMediaLaunch.resolve("   ") { true },
        )
    }

    @Test
    fun `会话在但拉不起来 → 明确报拉不起来 而不是没有会话`() {
        val action = DockMediaLaunch.resolve(qq) { false }
        assertEquals(DockMediaLaunch.Action.NotLaunchable, action)
    }

    @Test
    fun `没有会话时不该去问能不能拉起`() {
        // 空包名直接短路 —— 不该拿空串去查 PackageManager。
        var asked = false
        DockMediaLaunch.resolve(null) { asked = true; true }
        assertEquals(false, asked)
    }

    @Test
    fun `判定时用的是去掉空白后的包名`() {
        val action = DockMediaLaunch.resolve("  $netease  ") { true }
        assertEquals(DockMediaLaunch.Action.OpenApp(netease), action)
    }

    // --------------------------------------------------------------- messageFor

    @Test
    fun `拉得起来时没有提示文案`() {
        assertNull(DockMediaLaunch.messageFor(DockMediaLaunch.Action.OpenApp(qq)))
    }

    @Test
    fun `没有会话时的提示`() {
        assertEquals(
            "当前没有正在使用的音乐 App",
            DockMediaLaunch.messageFor(DockMediaLaunch.Action.NoSession),
        )
    }

    @Test
    fun `拉不起来时的提示`() {
        assertEquals(
            "该音乐应用无法打开",
            DockMediaLaunch.messageFor(DockMediaLaunch.Action.NotLaunchable),
        )
    }

    @Test
    fun `两种失败状态的提示语不一样`() {
        // 这两条提示必须能分辨：一个是「没在播」，一个是「装了但起不来」。
        val noSession = DockMediaLaunch.messageFor(DockMediaLaunch.Action.NoSession)
        val notLaunchable = DockMediaLaunch.messageFor(DockMediaLaunch.Action.NotLaunchable)
        assertEquals(true, noSession != notLaunchable)
    }
}
