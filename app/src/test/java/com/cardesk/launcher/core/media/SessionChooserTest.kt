package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话筛选 / 选择的纯逻辑单测。
 *
 * （isUsablePackage:374-380、isMediaCenter:365-367
 * chooseBest:192-299。
 */
class SessionChooserTest {

    private val self = "com.cardesk.launcher"

    // ---------------------------------------------------------------- 过滤

    @Test
    fun isUsablePackage_filtersOwnAndSystemPackages() {
        assertFalse(SessionChooser.isUsablePackage(self, self))
        assertFalse(SessionChooser.isUsablePackage("android", self))
        assertFalse(SessionChooser.isUsablePackage("com.android.systemui", self))
        assertFalse(SessionChooser.isUsablePackage("com.oem.launcher", self))
        assertFalse(SessionChooser.isUsablePackage("", self))
        assertTrue(SessionChooser.isUsablePackage("com.tencent.qqmusiccar", self))
    }

    @Test
    fun isMediaCenter_detectsMediaCenterPackages() {
        assertTrue(SessionChooser.isMediaCenter("com.oem.mediacenter"))
        assertTrue(SessionChooser.isMediaCenter("com.oem.MediaCenter"))
        assertFalse(SessionChooser.isMediaCenter("com.tencent.qqmusiccar"))
    }

    // ---------------------------------------------------------------- 选择

    @Test
    fun choose_activeNonMediaCenterWinsOverMediaCenter() {
        val normal = SessionCandidate("com.a", playing = true, hasMetadata = true, updatedAt = 100)
        val media = SessionCandidate("com.b", playing = true, hasMetadata = true, updatedAt = 200, mediaCenter = true)
        assertEquals("com.a", SessionChooser.choose(listOf(media, normal), null, 300)?.pkg)
    }

    /** 256-270 +:292-297 —— 同为「有元数据」时非 mediacenter 优先。 */
    @Test
    fun choose_mediaCenterIsDownweightedAmongMetadataSessions() {
        val normal = SessionCandidate("com.a", playing = false, hasMetadata = true, updatedAt = 500)
        val media = SessionCandidate("com.b", playing = false, hasMetadata = true, updatedAt = 900, mediaCenter = true)
        assertEquals("com.a", SessionChooser.choose(listOf(media, normal), null, 1000)?.pkg)
    }

    /** 290 —— 已选中的会话要领先 80ms 才被换掉。 */
    @Test
    fun choose_stickySelectionNeeds80msLead() {
        val selected = SessionCandidate("com.a", playing = false, hasMetadata = true, updatedAt = 1000, tokenKey = "tokA")
        val challenger = SessionCandidate("com.b", playing = false, hasMetadata = true, updatedAt = 1050, tokenKey = "tokB")
        assertEquals(
            "com.a",
            SessionChooser.choose(listOf(challenger, selected), null, 2000, selectedTokenKey = "tokA")?.pkg,
        )

        val farChallenger = SessionCandidate("com.b", playing = false, hasMetadata = true, updatedAt = 1200, tokenKey = "tokB")
        assertEquals(
            "com.b",
            SessionChooser.choose(listOf(farChallenger, selected), null, 2000, selectedTokenKey = "tokA")?.pkg,
        )
    }

    /**
     * **真机根因回归**：同一个音乐 App 会同时发布两个，
     * 其中一个 metadata 是空的（真机 `com.tencent.wecarflow` 实测）。
     *
     * 候选是**会话级**的，所以带歌的那个会话必须能被选中 ——
     * 按包名去重会让空会话占坑，永远拿不到歌名。
     */
    @Test
    fun choose_samePackageTwoSessions_picksTheOneWithMetadata() {
        val empty = SessionCandidate(
            "com.tencent.wecarflow", playing = false, hasMetadata = false, updatedAt = 2000, tokenKey = "tokEmpty",
        )
        val withSong = SessionCandidate(
            "com.tencent.wecarflow", playing = false, hasMetadata = true, updatedAt = 1000,
            tokenKey = "tokSong", title = "等你下课",
        )
        val picked = SessionChooser.choose(listOf(empty, withSong), null, 3000)
        assertEquals("tokSong", picked?.tokenKey)
        assertEquals("等你下课", picked?.title)
    }

    /** 锁定包时，同包的多个会话都要能被考虑（:197-213 遍历的是会话列表）。 */
    @Test
    fun choose_lockedPackageConsidersEverySessionOfThatPackage() {
        val empty = SessionCandidate(
            "com.tencent.wecarflow", playing = false, hasMetadata = false, updatedAt = 3000, tokenKey = "tokEmpty",
        )
        val withSong = SessionCandidate(
            "com.tencent.wecarflow", playing = false, hasMetadata = true, updatedAt = 1000, tokenKey = "tokSong",
        )
        val picked = SessionChooser.choose(
            listOf(empty, withSong), lockedPkg = "com.tencent.wecarflow", now = 4000,
        )
        assertEquals("tokSong", picked?.tokenKey)
    }

    /** 80ms 稳定判定是**会话级**的：同包的另一个会话不能把已选会话顶掉。 */
    @Test
    fun choose_stickyIsSessionLevelNotPackageLevel() {
        val selected = SessionCandidate(
            "com.a", playing = false, hasMetadata = true, updatedAt = 1000, tokenKey = "tokA",
        )
        // 同一个包、同样的 touch 时刻，但会话不同
        val sibling = SessionCandidate(
            "com.a", playing = false, hasMetadata = true, updatedAt = 1000, tokenKey = "tokA2",
        )
        val picked = SessionChooser.choose(listOf(selected, sibling), null, 2000, selectedTokenKey = "tokA")
        assertEquals("tokA", picked?.tokenKey)
    }

    @Test
    fun choose_lockedPackageOnlyPicksInsideLockedPackage() {
        val other = SessionCandidate("com.a", playing = true, hasMetadata = true, updatedAt = 100)
        assertNull(SessionChooser.choose(listOf(other), lockedPkg = "com.b", now = 200))

        val lockedIdle = SessionCandidate("com.b", playing = false, hasMetadata = true, updatedAt = 100)
        assertEquals(
            "com.b",
            SessionChooser.choose(listOf(other, lockedIdle), lockedPkg = "com.b", now = 200)?.pkg,
        )
    }

    @Test
    fun choose_emptyReturnsNull() {
        assertNull(SessionChooser.choose(emptyList(), null, 0L))
    }
}
