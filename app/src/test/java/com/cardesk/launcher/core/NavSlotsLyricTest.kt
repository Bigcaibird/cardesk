package com.cardesk.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「歌词归导航栏还是归首页中央」的**归属规则**单测。
 *
 * 歌词只出现在导航栏歌词槽
 * ；我们额外加了首页「中央大字歌词」
 * （第二十四轮按用户要求），两者**只能显示一处**。
 */
class NavSlotsLyricTest {

    private val withLyric = listOf(
        NavSlot.createAction(NavSlot.ACTION_BACK, "返回"),
        NavSlot.createLyric(),
    )

    private val withoutLyric = listOf(
        NavSlot.createAction(NavSlot.ACTION_BACK, "返回"),
        NavSlot.createMedia(),
    )

    /** 默认形态（增强导航栏关 / 设计 Dock）→ 歌词留在首页中央。 */
    @Test
    fun lyricStaysOnHomeByDefault() {
        assertFalse(NavSlots.lyricBelongsToNavBar(false, 0, withLyric))
        assertFalse(NavSlots.lyricBelongsToNavBar(true, 0, withLyric))
    }

    /** 槽位条形态 + 配了歌词槽 → 歌词归导航栏。 */
    @Test
    fun lyricGoesToNavBarInSlotMode() {
        assertTrue(
            NavSlots.lyricBelongsToNavBar(
                enhancedNav = true,
                navStyle = NavSlots.NAV_STYLE_SLOTS,
                slots = withLyric,
            ),
        )
    }

    /** 槽位条形态但**没配**歌词槽 → 歌词仍然留在首页中央。 */
    @Test
    fun lyricStaysOnHomeWhenNoLyricSlot() {
        assertFalse(
            NavSlots.lyricBelongsToNavBar(
                enhancedNav = true,
                navStyle = NavSlots.NAV_STYLE_SLOTS,
                slots = withoutLyric,
            ),
        )
    }

    /** 增强导航栏没开 → 就算样式值是槽位条也不算数。 */
    @Test
    fun enhancedNavMustBeOn() {
        assertFalse(
            NavSlots.lyricBelongsToNavBar(
                enhancedNav = false,
                navStyle = NavSlots.NAV_STYLE_SLOTS,
                slots = withLyric,
            ),
        )
    }

    /** 槽位表为空 → 不归导航栏。 */
    @Test
    fun emptySlotListStaysOnHome() {
        assertFalse(
            NavSlots.lyricBelongsToNavBar(
                enhancedNav = true,
                navStyle = NavSlots.NAV_STYLE_SLOTS,
                slots = emptyList(),
            ),
        )
    }

    /** 默认 15 槽里没有歌词槽 → 默认状态歌词在首页中央。 */
    @Test
    fun referenceDefaultSlotsHaveNoLyricSlot() {
        val defaults = NavSlots.defaults()
        assertFalse(defaults.any { it.type == NavSlot.TYPE_LYRIC })
        assertFalse(
            NavSlots.lyricBelongsToNavBar(
                enhancedNav = true,
                navStyle = NavSlots.NAV_STYLE_SLOTS,
                slots = defaults,
            ),
        )
    }

    /** `NavSlots.NAV_STYLE_SLOTS` 与 `EnhancedNavBarView.MODE_SLOTS` 必须是同一个值。 */
    @Test
    fun navStyleConstantMatchesEnhancedNavBarView() {
        assertTrue(NavSlots.NAV_STYLE_SLOTS == com.cardesk.launcher.ui.shell.EnhancedNavBarView.MODE_SLOTS)
    }
}
