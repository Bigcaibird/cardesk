package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dock 媒体卡**三键布局**的纯逻辑单测。
 *
 * 第三十九轮（4385）：播放键挪到前后曲**中间**，同时缩小到 44；
 * 前后曲放大到 40，间隙收到 8 —— 这组数值必须互相咬合。
 */
class DockMediaLayoutTest {

    private val eps = 1e-3f

    private val cardW = DockMediaLayout.MEDIA_CARD_MAX_W

    @Test
    fun keys_mediaCardWidth() {
        val k = DockMediaLayout.keys(cardW)
        assertEquals(342f, k.prevLeft, eps)
        assertEquals(390f, k.playLeft, eps)
        assertEquals(442f, k.nextLeft, eps)
    }

    @Test
    fun keys_textArea() {
        val k = DockMediaLayout.keys(cardW)
        assertEquals(90f, k.textLeft, eps)
        assertEquals(236f, k.textWidth, eps)
    }

    /** 顺序必须是「上一首 → 播放 → 下一首」。 */
    @Test
    fun keys_orderIsPrevPlayNext() {
        val k = DockMediaLayout.keys(cardW)
        assertTrue("上一首应在播放左边", k.prevLeft < k.playLeft)
        assertTrue("下一首应在播放右边", k.playLeft < k.nextLeft)
    }

    /** 播放键**夹在**前后曲中间。 */
    @Test
    fun keys_playIsBetweenPrevAndNext() {
        val k = DockMediaLayout.keys(cardW)
        assertTrue(k.prevLeft < k.playLeft && k.playLeft < k.nextLeft)
        // 播放键整个都在前后曲之间
        assertTrue(k.prevLeft + DockMediaLayout.SKIP_BTN <= k.playLeft)
        assertTrue(k.playLeft + DockMediaLayout.PLAY_BTN <= k.nextLeft)
    }

    /** 三个键互不重叠，间隙正好是 KEY_GAP。 */
    @Test
    fun keys_gapsAreExact() {
        val k = DockMediaLayout.keys(cardW)
        assertEquals(
            DockMediaLayout.KEY_GAP,
            k.playLeft - (k.prevLeft + DockMediaLayout.SKIP_BTN),
            eps,
        )
        assertEquals(
            DockMediaLayout.KEY_GAP,
            k.nextLeft - (k.playLeft + DockMediaLayout.PLAY_BTN),
            eps,
        )
    }

    /** 下一个键紧贴卡片右内边距。 */
    @Test
    fun keys_nextTouchesRightPadding() {
        val k = DockMediaLayout.keys(cardW)
        assertEquals(cardW - DockMediaLayout.PAD_R, k.nextLeft + DockMediaLayout.SKIP_BTN, eps)
    }

    /** 文本区左边界 = 左边距 + 封面 + 封面间隙。 */
    @Test
    fun keys_textStartsAfterCover() {
        val k = DockMediaLayout.keys(cardW)
        assertEquals(
            DockMediaLayout.PAD_L + DockMediaLayout.COVER_SIZE + DockMediaLayout.COVER_GAP,
            k.textLeft,
            eps,
        )
    }

    /** 文本区右边界与第一个按钮之间留 TEXT_GAP。 */
    @Test
    fun keys_textEndsBeforeFirstButton() {
        val k = DockMediaLayout.keys(cardW)
        assertEquals(DockMediaLayout.TEXT_GAP, k.prevLeft - (k.textLeft + k.textWidth), eps)
    }

    /** 卡片太窄时文本区宽度不能为负。 */
    @Test
    fun keys_neverNegativeTextWidth() {
        val k = DockMediaLayout.keys(200f)
        assertTrue("文本区宽度不应为负: ${k.textWidth}", k.textWidth >= 0f)
    }

    /** 播放键比 60 小，比前后曲大 —— 层级合理且不突兀。 */
    @Test
    fun keys_buttonSizesAreBalanced() {
        assertTrue(DockMediaLayout.PLAY_BTN < 60f)
        assertTrue(DockMediaLayout.PLAY_BTN > DockMediaLayout.SKIP_BTN)
        // 差距不能太大，否则又显得不协调
        assertTrue(DockMediaLayout.PLAY_BTN - DockMediaLayout.SKIP_BTN <= 8f)
    }

    /**
     * 第三十九轮：媒体卡宽度是**动态**的（按右侧空调簇实际占位收敛），
     * 任何宽度下三键都必须排得下、不重叠。
     */
    @Test
    fun keys_workForAnyCardWidth() {
        var w = DockMediaLayout.MIN_MEDIA_W
        while (w <= DockMediaLayout.MEDIA_CARD_MAX_W) {
            val k = DockMediaLayout.keys(w)
            assertTrue("宽度 $w：上一首键越界", k.prevLeft >= 0f)
            assertTrue(
                "宽度 $w：三个键装不下",
                k.nextLeft + DockMediaLayout.SKIP_BTN <= w - DockMediaLayout.PAD_R + eps,
            )
            assertTrue("宽度 $w：文本区宽度为负", k.textWidth >= 0f)
            assertTrue(
                "宽度 $w：播放键没夹在前后曲中间",
                k.prevLeft < k.playLeft && k.playLeft < k.nextLeft,
            )
            w += 4f
        }
    }

    /** 媒体卡最宽也不能超过上限（空调簇要留得下位置）。 */
    @Test
    fun keys_mediaWidthHasUpperBound() {
        assertEquals(496f, DockMediaLayout.MEDIA_CARD_MAX_W, eps)
        assertTrue(DockMediaLayout.MEDIA_CARD_MAX_W > DockMediaLayout.MIN_MEDIA_W)
        assertTrue(DockMediaLayout.MEDIA_CLUSTER_GAP > 0f)
    }
}
