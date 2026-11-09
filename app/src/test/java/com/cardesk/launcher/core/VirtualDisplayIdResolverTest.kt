package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟显示器 displayId 的解析规则（[VirtualDisplayIdResolver]）单测（第140轮，纯逻辑）。
 *
 * ## 背景
 *
 * 画中画黑屏的权威判据是
 * `task.displayId == virtualDisplay.getDisplayId()`，
 * 所以「拿不到我们自己的 displayId」= **判据整条失效**。
 *
 * 第140轮在 **API 28**（车机同版本）上查出：
 * AOSP 9 的 `android.app.ActivityView` 持有的是
 * `android.hardware.display.VirtualDisplay` —— **它不是 `android.view.Display`**，
 * 旧代码只认 `Display` → **在 API 28 上恒取不到** → 黑屏自愈那一级等于没生效。
 */
class VirtualDisplayIdResolverTest {

    // ------------------------------------------------------------------ 取舍规则

    @Test
    fun `取第一个已确认是虚拟显示器的候选`() {
        val id = VirtualDisplayIdResolver.pick(
            listOf(
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.OWN_VIRTUAL_DISPLAY,
                    displayId = 9,
                    confirmedVirtual = true,
                ),
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.HOST_VIRTUAL_DISPLAY,
                    displayId = 12,
                    confirmedVirtual = true,
                ),
            ),
        )
        assertEquals("优先级高的先取", 9, id)
    }

    @Test
    fun `未确认是虚拟显示器的候选一律不认`() {
        // 第130轮的坑：ActivityViewHost.mDisplay 是承载面自己所在的那块屏
        val id = VirtualDisplayIdResolver.pick(
            listOf(
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.HOST_DISPLAY,
                    displayId = 0,
                    confirmedVirtual = false,
                ),
            ),
        )
        assertNull("没确认是虚拟显示器 → 不认（宁可漏判也不能误判）", id)
    }

    @Test
    fun `displayId 大于 0 才作数`() {
        assertNull(
            "displayId 0 是主显示器",
            VirtualDisplayIdResolver.pick(
                listOf(
                    VirtualDisplayIdResolver.Candidate(
                        VirtualDisplayIdResolver.Source.OWN_VIRTUAL_DISPLAY,
                        displayId = 0,
                        confirmedVirtual = true,
                    ),
                ),
            ),
        )
        assertNull(
            "负数更不可能是虚拟屏",
            VirtualDisplayIdResolver.pick(
                listOf(
                    VirtualDisplayIdResolver.Candidate(
                        VirtualDisplayIdResolver.Source.OWN_VIRTUAL_DISPLAY,
                        displayId = -1,
                        confirmedVirtual = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `等于我们自己的 displayId 的候选要排除`() {
        val id = VirtualDisplayIdResolver.pick(
            listOf(
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.OWN_VIRTUAL_DISPLAY,
                    displayId = 3,
                    confirmedVirtual = true,
                ),
            ),
            ownDisplayId = 3,
        )
        assertNull("车机是多屏的：我们窗口所在那块屏肯定不是虚拟屏", id)
    }

    @Test
    fun `读不到 id 的候选会被跳过`() {
        val id = VirtualDisplayIdResolver.pick(
            listOf(
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.PUBLIC_API,
                    displayId = null,
                    confirmedVirtual = true,
                ),
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.OWN_VIRTUAL_DISPLAY,
                    displayId = 7,
                    confirmedVirtual = true,
                ),
            ),
        )
        assertEquals("前面那路读不到就顺延到下一路", 7, id)
    }

    @Test
    fun `一个候选都没有时返回 null`() {
        assertNull(VirtualDisplayIdResolver.pick(emptyList()))
    }

    @Test
    fun `全部候选都不可用时返回 null 而不是瞎猜`() {
        val id = VirtualDisplayIdResolver.pick(
            listOf(
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.PUBLIC_API,
                    displayId = null,
                    confirmedVirtual = true,
                ),
                VirtualDisplayIdResolver.Candidate(
                    VirtualDisplayIdResolver.Source.HOST_DISPLAY,
                    displayId = 2,
                    confirmedVirtual = false,
                ),
            ),
        )
        assertNull("判据不可用时按「不知道」处理", id)
    }

    // ------------------------------------------------------------------ 形态识别

    /** 假装自己是 `android.view.Display`：有 `getDisplayId()`。 */
    private class FakeDisplay(private val id: Int) {
        fun getDisplayId(): Int = id
    }

    /** 假装是 AOSP 9 的 `android.hardware.display.VirtualDisplay`：**不是** Display，包着一个 Display。 */
    private class FakeVirtualDisplay(private val inner: FakeDisplay) {
        fun getDisplay(): FakeDisplay = inner
    }

    /** 类名里带 VirtualDisplay 的（跟真机上的类名判定同规则）。 */
    private class FakeNamedVirtualDisplay(private val id: Int) {
        fun getDisplayId(): Int = id
    }

    @Test
    fun `本身是 Display 的直接取 id`() {
        assertEquals(9, VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(FakeDisplay(9)))
    }

    @Test
    fun `包着 Display 的壳要先剥一层`() {
        // 这就是 API 28 的形态：mVirtualDisplay 是 VirtualDisplay，不是 Display
        val id = VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(
            FakeVirtualDisplay(FakeDisplay(6)),
        )
        assertEquals("剥一层取到内层 Display 的 id", 6, id)
    }

    @Test
    fun `取不到时返回 null`() {
        assertNull(VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(null))
        assertNull(VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike("不是显示器"))
    }

    @Test
    fun `互相持有时不会死循环`() {
        val a = FakeVirtualDisplay(FakeDisplay(1))
        assertEquals(1, VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(a))
        // 自引用也该有上限，不能栈溢出
        val self = object {
            fun getDisplay(): Any = this
        }
        assertNull(VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(self))
    }

    @Test
    fun `类名带 VirtualDisplay 的算虚拟显示器`() {
        assertTrue(
            VirtualDisplayIdResolver.looksLikeVirtualDisplay(FakeNamedVirtualDisplay(5)),
        )
    }

    @Test
    fun `普通 Display 不算虚拟显示器`() {
        assertFalse(VirtualDisplayIdResolver.looksLikeVirtualDisplay(FakeDisplay(0)))
    }

    @Test
    fun `null 不算虚拟显示器`() {
        assertFalse(VirtualDisplayIdResolver.looksLikeVirtualDisplay(null))
    }
}
