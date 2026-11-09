package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换肤重建时旧画中画承载面的处置规则（[PipRebuildPolicy]）单测（第139轮，纯逻辑）。
 *
 * 背景（用户第139轮报的现象）：**切日夜模式时高德从画中画变成全屏**。
 *
 * 根因：换肤重建前 `release` 旧承载面 → **旧的 `VirtualDisplay` 被销毁** →
 * 挂在它上面的高德 task 被系统**搬到主显示器** → 高德全屏抢屏。
 *
 * 这一轮的修法是「**摘走 → 释放外壳 → 交给新承载面**」：
 * `VirtualDisplay` 全程不死 → task 一直在我们的 display 上。
 */
class PipRebuildPolicyTest {

    // ------------------------------------------------------------------ 主画

    @Test
    fun `主画有承载内容时搬家而不是释放`() {
        assertEquals(
            "有承载内容就必须搬家（释放会销毁 VirtualDisplay → 高德被搬到主屏）",
            PipRebuildPolicy.Action.TRANSFER,
            PipRebuildPolicy.mainAction(hasContent = true),
        )
    }

    @Test
    fun `主画没有承载内容时释放`() {
        assertEquals(
            "没有内容就没什么可搬的，直接放掉",
            PipRebuildPolicy.Action.RELEASE,
            PipRebuildPolicy.mainAction(hasContent = false),
        )
    }

    // ------------------------------------------------------------------ 副画

    @Test
    fun `副画双画开着且选了应用时搬家`() {
        assertEquals(
            "副画跟主画同一条规则：搬，别拆",
            PipRebuildPolicy.Action.TRANSFER,
            PipRebuildPolicy.secondaryAction(
                hasContent = true,
                dualPip = true,
                secondaryApp = "com.autonavi.amapautp.yh",
            ),
        )
    }

    @Test
    fun `副画双画关掉时释放`() {
        assertEquals(
            "双画都关了，副画的 VirtualDisplay 本来就该拆掉（留着就是泄漏）",
            PipRebuildPolicy.Action.RELEASE,
            PipRebuildPolicy.secondaryAction(
                hasContent = true,
                dualPip = false,
                secondaryApp = "com.autonavi.amapautp.yh",
            ),
        )
    }

    @Test
    fun `副画没选应用时释放`() {
        assertEquals(
            "副画压根没选应用，没什么可搬的",
            PipRebuildPolicy.Action.RELEASE,
            PipRebuildPolicy.secondaryAction(
                hasContent = true,
                dualPip = true,
                secondaryApp = "",
            ),
        )
    }

    @Test
    fun `副画没有承载内容时释放`() {
        assertEquals(
            "没有内容就没什么可搬的，直接放掉",
            PipRebuildPolicy.Action.RELEASE,
            PipRebuildPolicy.secondaryAction(
                hasContent = false,
                dualPip = true,
                secondaryApp = "com.autonavi.amapautp.yh",
            ),
        )
    }

    @Test
    fun `副画的应用名只有空格也当没选`() {
        assertEquals(
            "空白应用名等于没选",
            PipRebuildPolicy.Action.RELEASE,
            PipRebuildPolicy.secondaryAction(
                hasContent = true,
                dualPip = true,
                secondaryApp = "   ",
            ),
        )
    }

    // ------------------------------------------------------------------ 搬完之后要不要重新拉起

    @Test
    fun `搬家之后不重新拉起承载应用`() {
        assertFalse(
            "搬家时承载应用的 task 一直挂在同一个 VirtualDisplay 上，" +
                "重新拉起只会让高德从头再跑一遍（退回首页、导航中断）",
            PipRebuildPolicy.shouldRelaunchOnRecreate(PipRebuildPolicy.Action.TRANSFER),
        )
    }

    @Test
    fun `释放之后要重新拉起承载应用`() {
        assertTrue(
            "承载面整个换掉了，新承载面必须自己把应用拉起来",
            PipRebuildPolicy.shouldRelaunchOnRecreate(PipRebuildPolicy.Action.RELEASE),
        )
    }
}
