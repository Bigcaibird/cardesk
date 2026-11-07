package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ArtworkStore] 里**不依赖 Android 运行时**的那部分（第132轮）。
 *
 * ## 这条测试守住什么
 *
 * 仪表的封面走 `content://com.ecarx.cardesk.artwork/cover/<key>`，
 * 这个 Uri 是**别的进程**（仪表）读的，所以：
 * - key 必须能当**文件名**（歌名/歌手里什么字符都有：空格、`/`、`:`、emoji…）；
 * - 还得**不能穿越目录**（`../`）。
 */
class ArtworkStoreTest {

    @Test
    fun `Uri的形状固定且authority跟applicationId走`() {
        // 调试包（com.cardesk.launcher）与系统包（com.ecarx.cardesk）不能抢同一个 authority
        assertEquals(
            "content://com.cardesk.launcher.artwork/cover/abc",
            ArtworkStore.uriFor(FakePkg("com.cardesk.launcher"), "abc"),
        )
        assertEquals(
            "content://com.ecarx.cardesk.artwork/cover/abc",
            ArtworkStore.uriFor(FakePkg("com.ecarx.cardesk"), "abc"),
        )
    }

    /** 只需要 packageName 的最小 Context 替身。 */
    private class FakePkg(private val pkg: String) : android.content.ContextWrapper(null) {
        override fun getPackageName(): String = pkg
    }

    @Test
    fun `普通字符原样保留`() {
        assertEquals("com.tencent.qqmusiccar_春风吹_方大同", ArtworkStore.sanitize("com.tencent.qqmusiccar|春风吹|方大同"))
    }

    @Test
    fun `斜杠等危险字符被换掉_不能穿越目录`() {
        val s = ArtworkStore.sanitize("../../etc/passwd")
        assertTrue("不能出现 ..", !s.contains(".."))
        assertTrue("不能出现 /", !s.contains("/"))
    }

    @Test
    fun `空格与冒号被换掉`() {
        val s = ArtworkStore.sanitize("a b:c|d")
        assertTrue("不能有空格", !s.contains(" "))
        assertTrue("不能有冒号", !s.contains(":"))
        assertTrue("不能有竖线", !s.contains("|"))
    }

    @Test
    fun `空key得到空串`() {
        assertEquals("", ArtworkStore.sanitize(""))
        assertEquals("", ArtworkStore.sanitize("   "))
    }

    @Test
    fun `超长key被截断但仍然唯一`() {
        val long = "x".repeat(300)
        val a = ArtworkStore.sanitize(long + "a")
        val b = ArtworkStore.sanitize(long + "b")
        assertTrue("要短下来", a.length < 80)
        assertTrue("不同的歌不能撞名", a != b)
    }
}
