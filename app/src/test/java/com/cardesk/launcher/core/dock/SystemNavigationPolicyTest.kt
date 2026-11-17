package com.cardesk.launcher.core.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八十七轮：`SystemNavigationPolicy.merge` 是纯函数，覆盖这里就覆盖了
 * 「隐藏原车 Dock 时 policy_control 到底写什么」的全部决策，不需要模拟器/Roborule。
 *
 * 重点守住的约定（记录设计与实测结论，见 SystemNavigationPolicy 的 KDoc）：
 * - 默认**不写** `immersive.navigation` / `immersive.full`；
 * - 第189轮起多了一个 `hideNavigation` 开关：「隐藏原车 Dock」改成**全局生效**
 *   时才写 `immersive.navigation=*`（所有 App 都去掉系统 Dock）；
 * - `navInsets` 不改变输出（覆盖包只当可选增强，模拟器上 /data 装的 overlay 不生效）；
 * - 非沉浸策略段原样保留，别把别的功能正在用的策略抹掉。
 */
class SystemNavigationPolicyTest {

    private fun merge(
        current: String?,
        hideStatus: Boolean = true,
        hideStatusPackages: Set<String> = emptySet(),
        navInsets: Boolean = false,
        hideNavigation: Boolean = false,
    ): String? = SystemNavigationPolicy.merge(
        current,
        hideStatus,
        hideStatusPackages,
        navInsets,
        hideNavigation,
    )

    // ---------------------------------------------------------------- 基础输入

    @Test
    fun `current 为 null 且不隐藏状态栏时返回 null`() {
        assertNull(merge(null, hideStatus = false))
    }

    @Test
    fun `current 为空串且不隐藏状态栏时返回 null`() {
        assertNull(merge("", hideStatus = false))
    }

    @Test
    fun `只有无关策略段时原样保留`() {
        assertEquals("foo=bar:baz=qux", merge("foo=bar:baz=qux", hideStatus = false))
    }

    @Test
    fun `空段被丢弃`() {
        assertEquals("foo=bar", merge("::foo=bar::", hideStatus = false))
    }

    @Test
    fun `空白段被丢弃`() {
        assertEquals("foo=bar", merge("  : foo=bar : ", hideStatus = false))
    }

    // ---------------------------------------------------------------- 剥旧 immersive.*

    @Test
    fun `剥掉旧的 immersive navigation 段后没有剩余策略`() {
        assertNull(merge("immersive.navigation=*", hideStatus = false))
    }

    @Test
    fun `剥掉旧的沉浸段但保留其它段`() {
        assertEquals("foo=bar", merge("immersive.status=*:foo=bar", hideStatus = false))
    }

    @Test
    fun `旧的 immersive full 段也会被剥掉`() {
        assertNull(merge("immersive.full=com.a", hideStatus = false))
    }

    // ---------------------------------------------------------------- hideStatus = true

    @Test
    fun `隐藏状态栏时写入 immersive status 星号`() {
        assertEquals("immersive.status=*", merge(null, hideStatus = true))
    }

    @Test
    fun `隐藏名单写成豁免包`() {
        assertEquals(
            "immersive.status=*,-com.a,-com.b",
            merge("", hideStatus = true, hideStatusPackages = setOf("com.b", "com.a")),
        )
    }

    @Test
    fun `隐藏名单为空白项时忽略`() {
        assertEquals(
            "immersive.status=*,-com.a",
            merge("", hideStatus = true, hideStatusPackages = setOf("com.a", " ", "")),
        )
    }

    @Test
    fun `隐藏名单去重`() {
        assertEquals(
            "immersive.status=*,-com.a",
            merge("", hideStatus = true, hideStatusPackages = setOf("com.a", "com.a")),
        )
    }

    @Test
    fun `保留非沉浸段并追加新的 status 段`() {
        assertEquals(
            "foo=bar:immersive.status=*",
            merge("foo=bar:immersive.navigation=*", hideStatus = true),
        )
    }

    @Test
    fun `旧的 immersive status 段不会重复出现`() {
        assertEquals(
            "immersive.status=*,-com.a",
            merge("immersive.status=*,-com.old", hideStatus = true, hideStatusPackages = setOf("com.a")),
        )
    }

    // ---------------------------------------------------------------- navInsets 不改变输出

    @Test
    fun `navInsets 真假时 hideStatus 为真的输出一致`() {
        val current = "foo=bar:immersive.status=*:immersive.navigation=*"
        assertEquals(
            merge(current, hideStatus = true, hideStatusPackages = setOf("com.a"), navInsets = false),
            merge(current, hideStatus = true, hideStatusPackages = setOf("com.a"), navInsets = true),
        )
    }

    @Test
    fun `navInsets 真假时 hideStatus 为假的输出一致`() {
        val current = "foo=bar:immersive.status=*"
        assertEquals(
            merge(current, hideStatus = false, navInsets = false),
            merge(current, hideStatus = false, navInsets = true),
        )
    }

    // ---------------------------------------------------------------- 默认不写 navigation / full

    @Test
    fun `默认不写 immersive navigation 或 immersive full`() {
        val currents = listOf(
            null,
            "",
            "immersive.navigation=*",
            "immersive.full=*",
            "immersive.status=*,-com.a",
            "foo=bar",
            "foo=bar:immersive.navigation=*:immersive.full=*",
        )
        val packageSets = listOf(emptySet(), setOf("com.a"), setOf("com.b", "com.a"))
        for (current in currents) {
            for (hideStatus in listOf(true, false)) {
                for (packages in packageSets) {
                    for (navInsets in listOf(true, false)) {
                        val output = merge(current, hideStatus, packages, navInsets)
                        assertFalse(
                            "输出不应包含 immersive.navigation：$output",
                            output.orEmpty().contains("immersive.navigation"),
                        )
                        assertFalse(
                            "输出不应包含 immersive.full：$output",
                            output.orEmpty().contains("immersive.full"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `隐藏状态栏时输出里只有一条 status 段`() {
        val output = merge("immersive.status=*:foo=bar", hideStatus = true, hideStatusPackages = setOf("com.a"))
        assertEquals("foo=bar:immersive.status=*,-com.a", output)
        assertTrue(output!!.split(':').count { it.startsWith("immersive.status=") } == 1)
    }

    // ---------------------------------------- 第189轮：hideNavigation = 全局隐藏原车 Dock

    @Test
    fun `hideNavigation 打开时写出全局导航栏沉浸`() {
        assertEquals(
            "immersive.navigation=*",
            merge(null, hideStatus = false, hideNavigation = true),
        )
    }

    @Test
    fun `hideNavigation 打开时与状态栏沉浸同时存在`() {
        assertEquals(
            "immersive.navigation=*:immersive.status=*,-com.a",
            merge(null, hideStatus = true, hideStatusPackages = setOf("com.a"), hideNavigation = true),
        )
    }

    @Test
    fun `hideNavigation 打开时保留非沉浸策略段`() {
        assertEquals(
            "foo=bar:immersive.navigation=*",
            merge("foo=bar:immersive.navigation=com.old", hideStatus = false, hideNavigation = true),
        )
    }

    @Test
    fun `hideNavigation 打开时不会写出 immersive full`() {
        val output = merge("immersive.full=com.old", hideStatus = false, hideNavigation = true)
        assertEquals("immersive.navigation=*", output)
        assertFalse(output.orEmpty().contains("immersive.full"))
    }

    @Test
    fun `hideNavigation 开关在 navInsets 为真时被强制关闭`() {
        // 第193轮：预留区在时**一律不写** immersive.navigation=* ——
        // 写了就把 112px 预留带从所有 App 的窗口布局里摘掉，
        // 于是 App 全屏、Dock 盖内容、沉浸探测也被全局标志位污染（所有 App 都让位）。
        val current = "foo=bar"
        assertEquals(
            "foo=bar:immersive.navigation=*:immersive.status=*,-com.a",
            merge(current, hideStatus = true, hideStatusPackages = setOf("com.a"), navInsets = false, hideNavigation = true),
        )
        assertEquals(
            "foo=bar:immersive.status=*,-com.a",
            merge(current, hideStatus = true, hideStatusPackages = setOf("com.a"), navInsets = true, hideNavigation = true),
        )
    }

    @Test
    fun `预留区在时输出里绝不含 immersive navigation`() {
        // 穷举：无论 current / hideStatus / 名单怎么组合，预留区路径都不许写导航沉浸。
        val currents = listOf(
            null,
            "",
            "immersive.navigation=*",
            "immersive.full=*",
            "foo=bar",
            "foo=bar:immersive.navigation=*:immersive.full=*",
        )
        for (current in currents) {
            for (hideStatus in listOf(true, false)) {
                for (packages in listOf(emptySet(), setOf("com.a"))) {
                    val output = merge(current, hideStatus, packages, navInsets = true, hideNavigation = true)
                    assertFalse(
                        "预留区路径不该写 immersive.navigation：$output",
                        output.orEmpty().contains("immersive.navigation"),
                    )
                    assertFalse(
                        "预留区路径不该写 immersive.full：$output",
                        output.orEmpty().contains("immersive.full"),
                    )
                }
            }
        }
    }

    @Test
    fun `预留区在时旧的沉浸段照样被剥掉`() {
        // 车机上残留的 immersive.navigation=* 必须被清掉，
        // 否则这一版装上去症状照旧。
        assertNull(merge("immersive.navigation=*", hideStatus = false, navInsets = true, hideNavigation = true))
        assertEquals(
            "foo=bar",
            merge("immersive.navigation=*:foo=bar", hideStatus = false, navInsets = true, hideNavigation = true),
        )
    }
}
