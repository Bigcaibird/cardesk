package com.cardesk.launcher.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReleaseVersion] 单测（纯逻辑）。
 *
 * 这条规则决定「有没有新版本」的判断对不对：
 * 远端 tag 是 `v1.0.1035`，车机上装的是 `1.0.1038-system` ——
 * 两边形态不一样，必须先归一再比。
 */
class ReleaseVersionTest {

    @Test
    fun `归一去掉 v 前缀和 -system 后缀`() {
        assertEquals("1.0.1035", ReleaseVersion.normalize("v1.0.1035"))
        assertEquals("1.0.1035", ReleaseVersion.normalize("V1.0.1035"))
        assertEquals("1.0.1038", ReleaseVersion.normalize("1.0.1038-system"))
        assertEquals("1.0.1038", ReleaseVersion.normalize("  v1.0.1038-system  "))
        assertEquals("", ReleaseVersion.normalize(""))
    }

    @Test
    fun `数字分段`() {
        assertEquals(listOf(1, 0, 1038), ReleaseVersion.parts("1.0.1038"))
        assertEquals(listOf(1, 0, 1038), ReleaseVersion.parts("v1.0.1038-system"))
        assertEquals(listOf(1, 0), ReleaseVersion.parts("1.0"))
        assertEquals(emptyList<Int>(), ReleaseVersion.parts(""))
    }

    @Test
    fun `比较大小`() {
        assertTrue(ReleaseVersion.compare("1.0.1039", "1.0.1038") > 0)
        assertTrue(ReleaseVersion.compare("1.0.1038", "1.0.1039") < 0)
        assertEquals(0, ReleaseVersion.compare("1.0.1038", "1.0.1038"))
        // 归一之后两边应该相等
        assertEquals(0, ReleaseVersion.compare("v1.0.1038", "1.0.1038-system"))
    }

    @Test
    fun `远端更新才算新`() {
        assertTrue(ReleaseVersion.isNewer("v1.0.1039", "1.0.1038-system"))
        assertFalse(ReleaseVersion.isNewer("v1.0.1038", "1.0.1038-system"))
        assertFalse(ReleaseVersion.isNewer("v1.0.1035", "1.0.1038-system"))
    }

    @Test
    fun `段数不同也能比`() {
        // 1.0.1038 与 1.0.1038.1 → 少的那段按 0 算
        assertTrue(ReleaseVersion.compare("1.0.1038.1", "1.0.1038") > 0)
        assertTrue(ReleaseVersion.compare("1.0.1038", "1.0.1038.1") < 0)
        // 主版本号大就更新
        assertTrue(ReleaseVersion.compare("1.1.0", "1.0.999") > 0)
    }

    @Test
    fun `看不懂的版本号不抛异常`() {
        // 不能崩：仓库里要是有人发了个 `nightly` 之类的 tag，检测也得能跑完
        ReleaseVersion.compare("nightly", "1.0.1")
        ReleaseVersion.compare("", "")
        assertFalse(ReleaseVersion.isNewer("", "1.0.1"))
    }
}
