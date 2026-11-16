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
                hideStatusPackages = emptySet(),
            )
        )
    }

    @Test
    fun buildPolicyValue_navigationOnly() {
        assertEquals(
            "immersive.navigation=*",
            OemDockPolicy.buildPolicyValue(true, false, emptySet()),
        )
    }

    @Test
    fun buildPolicyValue_bothJoinedWithColon() {
        assertEquals(
            "immersive.navigation=*:immersive.status=com.b",
            OemDockPolicy.buildPolicyValue(true, true, setOf("com.b")),
        )
    }

    @Test
    fun buildPolicyValue_statusOnly() {
        assertEquals(
            "immersive.status=com.b",
            OemDockPolicy.buildPolicyValue(false, true, setOf("com.b")),
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
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("com.cardesk.launcher"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_hideDockIsGlobalAndCoversImmersiveList() {
        // 第189轮：「隐藏原车 Dock」改成**全局生效** —— 所有 App 都去掉系统 Dock。
        // 以前是「只对名单里的 App 生效」（外加桌面自己），所以那个开关
        // 看起来只对桌面 App 管用。现在是 "*"，沉浸名单被它盖掉。
        assertEquals(
            "immersive.navigation=*",
            OemDockPolicy.buildPolicyValue(
                hideDock = true,
                hideStatus = false,
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("com.self"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_hideDockOffKeepsImmersiveListOnly() {
        // 没开「隐藏原车 Dock」时，只有「总是沉浸」的名单生效（一般是桌面自己）——
        // 这条名单**不**是「隐藏原车 Dock」的实现方式，两者别混。
        assertEquals(
            "immersive.navigation=com.self",
            OemDockPolicy.buildPolicyValue(
                hideDock = false,
                hideStatus = false,
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("com.self"),
            ),
        )
    }

    @Test
    fun buildPolicyValue_hideDockOnIgnoresOldHideList() {
        // 第189轮：「隐藏原车 Dock」改成全局生效后，**不再有**「Dock 生效应用」名单。
        // 老用户存下来的名单（Prefs.hideDockApps）不再参与生成策略 ——
        // 留着它会让开关看起来「只对那几个 App 生效」，正是用户要改掉的行为。
        assertEquals(
            "immersive.navigation=*",
            OemDockPolicy.buildPolicyValue(
                hideDock = true,
                hideStatus = false,
                hideStatusPackages = emptySet(),
                immersivePackages = emptyList(),
            ),
        )
    }

    @Test
    fun buildPolicyValue_hideDockOnKeepsStatusExemptions() {
        // 全局隐藏 Dock 不影响状态栏那一侧的名单（这里沿用 OemDockPolicy 的语义：
        // 名单里写什么就命中什么，空集才是「全部」）。
        assertEquals(
            "immersive.navigation=*:immersive.status=com.b",
            OemDockPolicy.buildPolicyValue(
                hideDock = true,
                hideStatus = true,
                hideStatusPackages = setOf("com.b"),
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
                hideStatusPackages = emptySet(),
                immersivePackages = listOf("  ", "", " com.self "),
            ),
        )
    }

    // ---------------------------------------------------------------- 第180轮：Dock 让位

    @Test
    fun `沉浸型播放器在前台时全局 Dock 让位`() {
        assertTrue(OemDockPolicy.hidesGlobalDockFor("com.netease.cloudmusic.iot"))
        assertTrue(OemDockPolicy.hidesGlobalDockFor("com.luna.music.car"))
    }

    @Test
    fun `遵守导航栏 inset 的应用不让位`() {
        // QQ 音乐车机版自己做了安全区适配，Dock 压不到它。
        assertFalse(OemDockPolicy.hidesGlobalDockFor("com.tencent.qqmusiccar"))
        assertFalse(OemDockPolicy.hidesGlobalDockFor("com.cardesk.launcher"))
        assertFalse(OemDockPolicy.hidesGlobalDockFor("com.ecarx.qq.tool"))
    }

    @Test
    fun `空包名不让位`() {
        assertFalse(OemDockPolicy.hidesGlobalDockFor(null))
        assertFalse(OemDockPolicy.hidesGlobalDockFor(""))
        assertFalse(OemDockPolicy.hidesGlobalDockFor("   "))
    }
}
