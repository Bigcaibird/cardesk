package com.cardesk.launcher.core.car3d

import android.content.Intent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第166轮：**调试姿态注入**的解析（`am broadcast -a …DEBUG_POSE --es lf 1`）。
 *
 * 只测 bundle → pose 的纯映射，不测注册（注册还要看变体是否 debuggable）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Car3dDebugPoseTest {

    private fun intent(vararg extras: Pair<String, Any>): Intent =
        Intent(Car3dDebugPose.ACTION).apply {
            extras.forEach { (k, v) ->
                when (v) {
                    is Int -> putExtra(k, v)
                    else -> putExtra(k, v.toString())
                }
            }
        }

    @Test
    fun `单个车门开`() {
        val p = Car3dDebugPose.poseFrom(intent("lf" to 1))
        assertTrue("lf 应为开", p!!.doors[0])
        assertFalse("其余门应关", p.doors[1] || p.doors[2] || p.doors[3])
        assertFalse("尾门应关", p.trunk)
    }

    @Test
    fun `多门加尾门`() {
        val p = Car3dDebugPose.poseFrom(intent("lf" to "1", "rb" to "true", "trunk" to 1))!!
        assertArrayEquals(booleanArrayOf(true, false, false, true), p.doors)
        assertTrue(p.trunk)
    }

    @Test
    fun `开窗`() {
        val p = Car3dDebugPose.poseFrom(intent("wins" to 1))!!
        assertArrayEquals(booleanArrayOf(true, true, true, true), p.windows)
        assertFalse("没给门参数时门应全关", p.doors.any { it })
    }

    @Test
    fun `clear 或没有参数都表示不覆盖`() {
        assertNull(Car3dDebugPose.poseFrom(intent("lf" to 1, "clear" to 1)))
        assertNull(Car3dDebugPose.poseFrom(intent()))
        assertNull(Car3dDebugPose.poseFrom(null))
    }

    @Test
    fun `值为 0 不算开`() {
        assertNull(Car3dDebugPose.poseFrom(intent("lf" to 0, "trunk" to "0")))
    }
}
