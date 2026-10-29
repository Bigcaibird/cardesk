package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HiddenApiCompat] 的**方法解析**单测。
 *
 * ## 为什么这条要单测
 *
 * 隐藏 API 豁免一旦写错，症状是「**什么都报不出来**」：
 * 依赖隐藏 API 的功能（频谱取 `audioSessionId`、AppOps `setMode`）
 * 全部静默失效，而日志里只有一句「未放开(NoSuchMethodException: …)」。
 *
 * 真实签名是 `setHiddenApiExemptions(String[])` ——
 * 当年写成 `String::class.java`，必然 `NoSuchMethodException`，
 * 于是豁免从来没生效过。这里用**两个重载都在**的假类锁住
 * 「必须挑 `String[]` 那个」这条规则。
 */
class HiddenApiCompatTest {

    /**
     * 假的 VMRuntime。
     *
     * Android 9（API 28）上只有 `String[]` 那一个重载；
     * Android 10 之后 AOSP 又加了单参数 `String` 的重载 ——
     * 两个都在，才检验得出「有没有挑错」。
     */
    class FakeVmRuntime {
        /** 实际被调用的重载（"array" / "single"）。 */
        var called: String? = null

        /** 收到的参数。 */
        var args: Array<String>? = null

        @Suppress("unused")
        fun setHiddenApiExemptions(exemptions: String) {
            called = "single"
            args = arrayOf(exemptions)
        }

        @Suppress("unused")
        fun setHiddenApiExemptions(exemptions: Array<String>) {
            called = "array"
            args = exemptions
        }
    }

    @Test
    fun `豁免前缀是 L`() {
        assertEquals("L", HiddenApiCompat.EXEMPT_PREFIX)
        assertEquals(listOf("L"), HiddenApiCompat.exemptionArgs().toList())
    }

    @Test
    fun `找到的是 String 数组那个重载`() {
        val m = HiddenApiCompat.findSetHiddenApiExemptions(FakeVmRuntime::class.java)
        assertNotNull("应该找得到 setHiddenApiExemptions", m)
        assertEquals("setHiddenApiExemptions", m!!.name)
        assertEquals(1, m.parameterTypes.size)
        assertEquals(Array<String>::class.java, m.parameterTypes[0])
    }

    @Test
    fun `按真实签名调用能传进数组`() {
        val vm = FakeVmRuntime()
        val m = HiddenApiCompat.findSetHiddenApiExemptions(FakeVmRuntime::class.java)!!
        // `Method.invoke(Object, Object...)` 是 Java 可变参数：
        // 从 Kotlin 传数组**不会自动展开**（数组会被当成一个参数），
        // 必须用 `*` 展开 —— 否则真正调用到的是 `setHiddenApiExemptions(Object[])`
        // → `IllegalArgumentException: argument type mismatch`。
        val args = arrayOf<Any>(HiddenApiCompat.exemptionArgs())
        m.invoke(vm, *args)
        assertEquals("array", vm.called)
        assertEquals(listOf("L"), vm.args?.toList())
    }

    @Test
    fun `类里没有这个方法就返回 null`() {
        assertNull(HiddenApiCompat.findSetHiddenApiExemptions(String::class.java))
    }

    @Test
    fun `豁免失败时仍然给得出结论文案`() {
        // 兜底文案要保留（诊断包靠它显示「未放开」），但不能抛异常
        val text = HiddenApiCompat.tryExempt()
        assertTrue("结论不能为空", text.isNotBlank())
        assertTrue(
            "结论要么是「已放开」要么是「未放开(...)」：$text",
            text == "已放开" || text.startsWith("未放开("),
        )
    }
}
