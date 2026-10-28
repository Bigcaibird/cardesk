package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原车 Dock/状态栏隐藏策略单测（`formatApps`）。
 */
class OemDockPolicyTest {

    @Test
    fun formatApps_emptySetMeansAll() {
        assertEquals("*", OemDockPolicy.formatApps(null))
        assertEquals("*", OemDockPolicy.formatApps(emptySet()))
    }

    @Test
    fun formatApps_joinsPackagesWithComma() {
        assertEquals("a.b,c.d", OemDockPolicy.formatApps(listOf("a.b", "c.d")))
    }

    @Test
    fun formatApps_skipsBlankEntries() {
        assertEquals("a.b", OemDockPolicy.formatApps(listOf("  ", "a.b", "")))
        assertEquals("*", OemDockPolicy.formatApps(listOf("  ", "")))
    }

    @Test
    fun buildPolicyValue_nullWhenNothingEnabled() {
        assertNull(
            OemDockPolicy.buildPolicyValue(
                hideDock = false,
                hideStatus = false,
                hideDockPackages = emptySet(),
                hideStatusPackages = emptySet(),
            )
        )
    }

    @Test
    fun buildPolicyValue_navigationOnly() {
        assertEquals(
            "immersive.navigation=*",
            OemDockPolicy.buildPolicyValue(true, false, emptySet(), emptySet()),
        )
    }

    @Test
    fun buildPolicyValue_bothJoinedWithColon() {
        assertEquals(
            "immersive.navigation=com.a:immersive.status=com.b",
            OemDockPolicy.buildPolicyValue(true, true, setOf("com.a"), setOf("com.b")),
        )
    }

    @Test
    fun buildPolicyValue_statusOnly() {
        assertEquals(
            "immersive.status=com.b",
            OemDockPolicy.buildPolicyValue(false, true, emptySet(), setOf("com.b")),
        )
    }

    @Test
    fun matchesHideList_emptySetMatchesEverything() {
        assertTrue(OemDockPolicy.matchesHideList(emptySet(), "com.any"))
        assertTrue(OemDockPolicy.matchesHideList(null, "com.any"))
    }

    @Test
    fun matchesHideList_sentinelMeansNone() {
        val set = setOf(OemDockPolicy.NO_APPS)
        assertFalse(OemDockPolicy.matchesHideList(set, "com.any"))
    }

    @Test
    fun matchesHideList_exactPackageHit() {
        assertTrue(OemDockPolicy.matchesHideList(setOf("com.a"), "com.a"))
        assertFalse(OemDockPolicy.matchesHideList(setOf("com.a"), "com.b"))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val set = setOf("com.b", "com.a")
        val encoded = OemDockPolicy.encodePackages(set)
        assertEquals("com.a,com.b", encoded)
        assertEquals(set, OemDockPolicy.decodePackages(encoded))
    }

    @Test
    fun decodePackages_blankMeansEmptySet() {
        assertEquals(emptySet<String>(), OemDockPolicy.decodePackages(""))
        assertEquals(emptySet<String>(), OemDockPolicy.decodePackages(null))
        assertEquals(emptySet<String>(), OemDockPolicy.decodePackages("  ,  "))
    }

    @Test
    fun buildEnhancedPolicy_alwaysImmersesNavigation() {
        assertEquals(
            "immersive.navigation=*",
            OemDockPolicy.buildEnhancedPolicy(false, emptySet()),
        )
        assertEquals(
            "immersive.navigation=*:immersive.status=com.b",
            OemDockPolicy.buildEnhancedPolicy(true, setOf("com.b")),
        )
    }

    // ----------------------------------------------- 第五十六轮：沉浸系统导航栏

    @Test
    fun buildPolicyValue_immersivePackageIsAppliedEvenWhenHideDockIsOff() {
        // 「沉浸系统导航栏」与「隐藏原车 Dock」是两个开关：
        // 前者只影响桌面自己，后者才是对指定应用隐藏原车 Dock。
        assertEquals(
            "immersive.navigation=com.cardesk.launcher",
            OemDockPolicy.buildPolicyValue(
                hideDock = false,
                hideStatus = false,
                hideDockPackages = emptySet(),
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("com.cardesk.launcher"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_immersivePackagesMergeWithHideList() {
        assertEquals(
            "immersive.navigation=com.self,com.other",
            OemDockPolicy.buildPolicyValue(
                hideDock = true,
                hideStatus = false,
                hideDockPackages = setOf("com.other"),
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("com.self"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_immersiveListWinsWhenHideDockListIsEmpty() {
        // 「隐藏原车 Dock」打开但名单为空 = 全部应用；
        // 此时沉浸名单是「自己 ∪ 全部」→ 还是 "*"。
        assertEquals(
            "immersive.navigation=*",
            OemDockPolicy.buildPolicyValue(
                hideDock = true,
                hideStatus = false,
                hideDockPackages = emptySet(),
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("com.self"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_immersiveAndStatusJoinWithColon() {
        assertEquals(
            "immersive.navigation=com.self:immersive.status=com.b",
            OemDockPolicy.buildPolicyValue(
                hideDock = false,
                hideStatus = true,
                hideDockPackages = emptySet(),
                hideStatusPackages = setOf("com.b"),
                immersivePackages = listOf("com.self"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_blankEntriesAreDropped() {
        assertEquals(
            "immersive.navigation=com.self",
            OemDockPolicy.buildPolicyValue(
                hideDock = false,
                hideStatus = false,
                hideDockPackages = emptySet(),
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("  ", "", " com.self "),
            ),
        )
    }
}
