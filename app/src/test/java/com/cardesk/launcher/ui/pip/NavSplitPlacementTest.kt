package com.cardesk.launcher.ui.pip

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 导航页「画中画全屏按钮」挂载与定位规则的纯逻辑单测。
 *
 * 第129轮新增 —— 钉死三条不变量：
 * 1. 按钮尺寸沿用老值（36×110），没被改小；
 * 2. **非全屏（悬浮在画中画上）与全屏两态的定位参数逐字相同**；
 * 3. 非全屏态的宿主是**画中画承载面**，不是导航页本体
 *    （挂回导航页本体就等于回到「独占一条留白」的旧行为）。
 */
class NavSplitPlacementTest {

    @Test
    fun size_staysLegacyValue() {
        // 沿用第129轮之前的尺寸 —— 本轮只改归属容器，不改大小
        assertEquals(36, NavSplitPlacement.WIDTH)
        assertEquals(110, NavSplitPlacement.HEIGHT)
    }

    @Test
    fun gravity_isLeftCenterVertical() {
        // 左侧 + 垂直居中
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, NavSplitPlacement.GRAVITY)
    }

    @Test
    fun gravity_sameInBothStates() {
        // —— 核心不变量：两态位置必须一致，用户不用记两个位置
        assertEquals(
            NavSplitPlacement.gravity(fullscreen = false),
            NavSplitPlacement.gravity(fullscreen = true),
        )
        assertEquals(NavSplitPlacement.GRAVITY, NavSplitPlacement.gravity(fullscreen = false))
        assertEquals(NavSplitPlacement.GRAVITY, NavSplitPlacement.gravity(fullscreen = true))
    }

    @Test
    fun host_overlaid_isPipCard_notNavPage() {
        // 非全屏：按钮挂在**画中画承载面**里，叠在画面左边缘
        assertEquals(NavSplitPlacement.HOST_PIP_CARD, NavSplitPlacement.host(fullscreen = false))
        assertEquals(NavSplitPlacement.HOST_OVERLAID, NavSplitPlacement.host(fullscreen = false))
        // —— 旧实现是挂在导航页本体（独占左侧 44px 留白），这条不能回退
        assertNotEquals("navPage", NavSplitPlacement.host(fullscreen = false))
    }

    @Test
    fun host_fullscreen_isDesktopRoot() {
        // 全屏：画中画被提到桌面根容器，按钮也得提上去当还原入口
        assertEquals(
            NavSplitPlacement.HOST_DESKTOP_ROOT,
            NavSplitPlacement.host(fullscreen = true),
        )
        assertEquals(NavSplitPlacement.HOST_FULLSCREEN, NavSplitPlacement.host(fullscreen = true))
    }

    @Test
    fun pipCardLayoutDirection_isLtr() {
        // 承载面必须 LTR：换位靠外层 navRow 的 RTL，承载面自己保持 LTR。
        // 一旦被镜像，按钮（Gravity.START）就会跑到画中画的右边，跟画面错位。
        assertEquals(PipSides.DIR_LTR, NavSplitPlacement.PIP_CARD_LAYOUT_DIRECTION)
        assertEquals(0, NavSplitPlacement.PIP_CARD_LAYOUT_DIRECTION)
    }
}
