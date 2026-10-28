package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dock **四段式布局**的纯逻辑单测（第五十四轮）。
 *
 * 第五十三轮锁的是「三组等距」（两段间隙相等）；
 * 第五十四轮按用户要求改成：
 * **导航键 · 音乐卡 · 空调簇 · 快捷应用**，段间**不再刻意留白**，
 * 腾出来的宽度给最右边的快捷应用段。
 *
 * 所以断言换成了：
 * - 四段**按顺序**排开、互不压叠；
 * - 快捷应用段**贴着右边缘**；
 * - 音乐卡宽度在合法区间；
 * - 快捷应用槽位不越界。
 */
class DockLayoutTest {

    private val eps = 1e-3f

    /** Dock 视图的实际宽度（`LauncherView` 里 1872 宽，左右各留 24）。 */
    private val dockW = 1872f

    /**
     * 空调簇的**实测**占位宽。
     *
     * 真机上 A/C · AUTO 两段是按字体量的：28px 粗体下 "AUTO" 约 76px、
     * 28 "A/C" 约 47px，各加 28 留白 → 104 + 75。
     * 第五十四轮簇尺寸没变，还是 967。
     */
    private val clusterW = 192f + 32f + 16f + 104f + 75f + 224f * 2 + 100f

    @Test
    fun fourSectionsAreInOrder() {
        val s = DockLayout.sections(dockW, clusterW)
        assertTrue("导航键应在最左", s.keysLeft < s.keysRight)
        assertTrue("音乐卡应在导航键右边", s.keysRight <= s.mediaLeft)
        assertTrue("空调簇应在音乐卡右边", s.mediaRight <= s.clusterLeft)
        assertTrue("快捷应用应在最右", s.clusterRight <= s.quickLeft)
    }

    @Test
    fun fourSectionsDoNotOverlap() {
        val s = DockLayout.sections(dockW, clusterW)
        assertTrue("导航键与音乐卡不能压叠", s.keysRight <= s.mediaLeft)
        assertTrue("音乐卡与空调簇不能压叠", s.mediaRight <= s.clusterLeft)
        assertTrue("空调簇与快捷应用不能压叠", s.clusterRight <= s.quickLeft)
    }

    @Test
    fun quickSectionIsPinnedToRightEdge() {
        val s = DockLayout.sections(dockW, clusterW)
        assertEquals(
            "快捷应用段右边应贴着 Dock 右边缘",
            dockW - DockLayout.MEDIA_RIGHT_INSET,
            s.quickRight,
            eps,
        )
        assertEquals(
            "快捷应用段宽度 = 槽数 × 槽宽 + 间隙",
            DockQuickApps.SLOTS * DockLayout.QUICK_TILE +
                (DockQuickApps.SLOTS - 1) * DockLayout.QUICK_GAP,
            s.quickRight - s.quickLeft,
            eps,
        )
    }

    @Test
    fun quickSlotsStayInsideSection() {
        val s = DockLayout.sections(dockW, clusterW)
        for (i in 0 until DockQuickApps.SLOTS) {
            val left = DockLayout.quickSlotLeft(s, i)
            assertTrue("第 $i 个槽位不能越过右边界", left + DockLayout.QUICK_TILE <= s.quickRight + eps)
            assertTrue("第 $i 个槽位不能越过左边界", left >= s.quickLeft - eps)
        }
    }

    @Test
    fun mediaCardStaysInLegalRange() {
        val s = DockLayout.sections(dockW, clusterW)
        assertTrue(
            "音乐卡宽度 ${s.mediaWidth} 必须 ≥ ${DockMediaLayout.MIN_MEDIA_W}",
            s.mediaWidth >= DockMediaLayout.MIN_MEDIA_W,
        )
        assertTrue(
            "音乐卡宽度 ${s.mediaWidth} 必须 ≤ ${DockMediaLayout.MEDIA_CARD_MAX_W}",
            s.mediaWidth <= DockMediaLayout.MEDIA_CARD_MAX_W,
        )
    }

    @Test
    fun navKeysOnLeft() {
        val s = DockLayout.sections(dockW, clusterW)
        assertEquals(DockLayout.KEY_LEFT, s.keysLeft, eps)
        assertEquals(
            DockLayout.KEY_COUNT * DockLayout.KEY_SLOT + (DockLayout.KEY_COUNT - 1) * DockLayout.KEY_GAP,
            s.keysRight - s.keysLeft,
            eps,
        )
    }

    /**
     * 簇特别宽时（字体异常 / 大字号），音乐卡可以让窄，
     * 但**绝不能压到导航键**，快捷应用段也不能被挤没。
     */
    @Test
    fun oversizedClusterNeverOverlapsNavKeys() {
        val huge = 1400f
        val s = DockLayout.sections(dockW, huge)
        assertTrue("音乐卡不能压到导航键", s.mediaLeft >= s.keysRight)
        assertTrue("快捷应用段必须还在", s.quickRight - s.quickLeft > 0f)
    }

    /** 簇很窄时，快捷应用段依然贴右边缘，槽位位置不变。 */
    @Test
    fun narrowClusterKeepsQuickSectionPinned() {
        val narrow = 600f
        val s = DockLayout.sections(dockW, narrow)
        assertEquals(
            "窄簇时快捷应用段仍贴右边缘",
            dockW - DockLayout.MEDIA_RIGHT_INSET,
            s.quickRight,
            eps,
        )
    }

    /** 整条 Dock 必须放得下：四段 + 三段间隙 ≤ Dock 可用宽度。 */
    @Test
    fun wholeBarFits() {
        val s = DockLayout.sections(dockW, clusterW)
        assertTrue(
            "四段放不下：keys ${s.keysRight} / media ${s.mediaWidth} / cluster $clusterW / quick ${s.quickRight - s.quickLeft}",
            s.quickRight <= dockW,
        )
    }
}
