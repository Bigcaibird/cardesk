package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaResume] 单测（纯逻辑，不依赖 Android）。
 *
 * 这条规则直接决定真机上「按了播放没反应」会不会复现：
 * 车机音乐 App 暂停时把会话一起销毁 → 我们拿不到活动会话 →
 * 全局媒体键没人接 → 必须**定向**给「上一次使用的播放器」发一个播放事件。
 */
class MediaResumeTest {

    private val qq = "com.tencent.qqmusiccar"
    private val wecar = "com.tencent.wecarflow"

    @Test
    fun `目标包优先用绑定会话的包`() {
        assertEquals(
            qq,
            MediaResume.targetPackage(boundPkg = qq, fallbackPkg = wecar, rememberedPkg = "com.netease.cloudmusic.iot"),
        )
    }

    @Test
    fun `没有会话时用本次恢复指定的包`() {
        assertEquals(
            qq,
            MediaResume.targetPackage(boundPkg = null, fallbackPkg = qq, rememberedPkg = "com.netease.cloudmusic.iot"),
        )
    }

    @Test
    fun `都没有时退回记住的上次音乐包`() {
        assertEquals(
            wecar,
            MediaResume.targetPackage(boundPkg = "", fallbackPkg = null, rememberedPkg = wecar),
        )
    }

    @Test
    fun `全空时目标为空串`() {
        assertEquals("", MediaResume.targetPackage(null, null, null))
        assertEquals("", MediaResume.targetPackage("", "  ", null))
    }

    @Test
    fun `没有活动会话时一律发定向播放事件`() {
        // —— 第六十八轮补的这一路：目标不是爱趣听类（QQ音乐车机版）也要发
        assertTrue(MediaResume.shouldSendTargetedPlay(boundPkg = null, target = qq, isAiquting = false))
        assertTrue(MediaResume.shouldSendTargetedPlay(boundPkg = "", target = qq, isAiquting = false))
        assertTrue(MediaResume.shouldSendTargetedPlay(boundPkg = null, target = wecar, isAiquting = true))
    }

    @Test
    fun `有活动会话时只对爱趣听类发`() {
        // 有会话 → transportControls.play() 本来就管用，别给别的 App 塞多余广播
        assertFalse(MediaResume.shouldSendTargetedPlay(boundPkg = qq, target = qq, isAiquting = false))
        assertTrue(MediaResume.shouldSendTargetedPlay(boundPkg = wecar, target = wecar, isAiquting = true))
    }

    @Test
    fun `目标为空时不发`() {
        assertFalse(MediaResume.shouldSendTargetedPlay(boundPkg = null, target = "", isAiquting = false))
        assertFalse(MediaResume.shouldSendTargetedPlay(boundPkg = null, target = "   ", isAiquting = true))
    }

    @Test
    fun `QQ音乐车机版不属于爱趣听类`() {
        // 口径回归：定向广播不能只发给爱趣听类
        // （真机上 QQ音乐车机版暂停后销毁会话，按播放就是没反应）
        assertFalse(MediaResume.shouldSendTargetedPlay(boundPkg = qq, target = qq, isAiquting = false))
        assertTrue(MediaResume.shouldSendTargetedPlay(boundPkg = null, target = qq, isAiquting = false))
    }
}
