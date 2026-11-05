package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话选择里「**锁定包**」的兜底（第126轮）。
 *
 * ## 背景（真车回归）
 *
 * 第125轮加了「默认媒体源 → 桌面启动时锁包」。用户把默认源设成 QQ音乐，
 * 而 QQ 音乐车机版**不发布可枚举的 MediaSession** → 锁定包永远不在候选里 →
 * 老的 `SessionChooser` 在锁定分支里 `return fallback`（null）→
 * **每 2.5s 选一次都选不中 → 界面被清空**（没歌名、没歌手、没歌词），
 * OEM 媒体桥也拿不到 controller → **方向盘方控还在原车音源（爱趣听）上**。
 *
 * 现在：锁定包**选不出可用会话**时，回落到正常选择 —— 锁定是「优先」，
 * 不是「一票否决」。
 */
class SessionChooserFallbackTest {

    private fun candidate(
        pkg: String,
        active: Boolean = false,
        hasMetadata: Boolean = false,
        updatedAt: Long = 0L,
    ) = SessionCandidate(
        pkg = pkg,
        playing = active,
        hasMetadata = hasMetadata,
        updatedAt = updatedAt,
        active = active,
        tokenKey = "$pkg#token",
    )

    @Test
    fun `锁定包不在候选里时不能选空`() {
        // 回归本体：锁的是 qqmusiccar，候选里只有蓝牙和爱趣听。
        val picked = SessionChooser.choose(
            candidates = listOf(
                candidate("com.android.bluetooth", active = true),
                candidate("com.tencent.wecarflow", hasMetadata = true),
            ),
            lockedPkg = "com.tencent.qqmusiccar",
            now = 1000L,
        )
        // 老实现这里返回 null（界面清空）；现在必须选出一个来。
        assertNotNull("锁定包没有会话时应该回落，而不是选空", picked)
    }

    @Test
    fun `锁定包在候选里但没歌没元数据时也不能选空`() {
        val picked = SessionChooser.choose(
            candidates = listOf(
                candidate("com.tencent.qqmusiccar", active = false, hasMetadata = false),
                candidate("com.tencent.wecarflow", active = true),
            ),
            lockedPkg = "com.tencent.qqmusiccar",
            now = 1000L,
        )
        assertNotNull("锁定包只有个空会话时应该回落", picked)
    }

    @Test
    fun `锁定包活跃时仍然优先选它`() {
        val picked = SessionChooser.choose(
            candidates = listOf(
                candidate("com.tencent.wecarflow", active = true),
                candidate("com.tencent.qqmusiccar", active = true),
            ),
            lockedPkg = "com.tencent.qqmusiccar",
            now = 1000L,
        )
        assertEquals("com.tencent.qqmusiccar", picked?.pkg)
    }

    @Test
    fun `锁定包有元数据时仍然选它`() {
        val picked = SessionChooser.choose(
            candidates = listOf(
                candidate("com.tencent.wecarflow", hasMetadata = true),
                candidate("com.tencent.qqmusiccar", hasMetadata = true),
            ),
            lockedPkg = "com.tencent.qqmusiccar",
            now = 1000L,
        )
        assertEquals("com.tencent.qqmusiccar", picked?.pkg)
    }

    @Test
    fun `候选为空时仍然选空`() {
        val picked = SessionChooser.choose(
            candidates = emptyList(),
            lockedPkg = "com.tencent.qqmusiccar",
            now = 1000L,
        )
        assertNull("一个候选都没有时不该硬造一个", picked)
    }

    @Test
    fun `不锁时按活跃优先`() {
        val picked = SessionChooser.choose(
            candidates = listOf(
                candidate("com.tencent.wecarflow", hasMetadata = true),
                candidate("com.android.bluetooth", active = true),
            ),
            lockedPkg = null,
            now = 1000L,
        )
        assertEquals("com.android.bluetooth", picked?.pkg)
    }
}
