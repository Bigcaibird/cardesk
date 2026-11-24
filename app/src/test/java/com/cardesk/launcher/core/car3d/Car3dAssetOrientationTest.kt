package com.cardesk.launcher.core.car3d

import android.content.Context
import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Rule

/**
 * 俯视 32 态资产（第179轮）的合约测试。
 *
 * 01 现在只装 `cardtop` 一套：16 种车门组合 × 尾门 2 种 = 32 张状态图。
 * （第180轮起门缝橙色描边已下线，运行时不读 `<state>__gap`。）
 *
 * 盯四件事：
 * 1. `cardtop` 存在且槽位是 356×264；
 * 2. 不同门组合画出来**确实不同**（不是同一张图改名）；
 * 3. 三门组合缺图时按「门数递减」回退，且命中状态可解释；
 * 4. 尾门组合（`_trunk`）能解出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Car3dAssetOrientationTest {
    @get:Rule val carAssets = TestCarAssets.Rule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Car3dAssets.reset()
        Car3dSettings.enabled = true
    }

    @After
    fun tearDown() {
        Car3dAssets.reset()
        Car3dSettings.enabled = true
    }

    private fun top(state: String): Bitmap? =
        Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_CARDTOP, state, W, H)

    private fun diffRatio(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height)
        var diff = 0
        var opaque = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                if ((pa ushr 24) < 128 && (pb ushr 24) < 128) continue
                opaque++
                val d = kotlin.math.abs((pa and 0xFF) - (pb and 0xFF)) +
                    kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                    kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                if (d > 40) diff++
            }
        }
        return if (opaque == 0) 0.0 else diff.toDouble() / opaque
    }

    @Test
    fun `cardtop 资产存在且尺寸正确`() {
        val closed = top("closed")
        assertNotNull("cardtop/closed 必须能解码", closed)
        assertEquals(W, closed!!.width)
        assertEquals(H, closed.height)
    }

    @Test
    fun `不同门组合画出来确实不同`() {
        val closed = top("closed")!!
        val lf = top("lf")!!
        val rf = top("rf")!!
        val all = top("all")!!
        assertTrue("lf 与 closed 应明显不同", diffRatio(closed, lf) > 0.002)
        assertTrue("lf 与 rf 应是不同图（不是镜像/同一张）", diffRatio(lf, rf) > 0.001)
        assertTrue("all 与 closed 应明显不同", diffRatio(closed, all) > 0.01)
    }

    @Test
    fun `三门组合直接命中 缺图时按门数递减回退`() {
        // 32 态资产包含全部 16 种门组合：三门就该直接命中自己。
        val exact = Car3dAssets.stateBitmapNamed(
            context, "lynk01", Car3dAssets.VIEW_CARDTOP, "lf_rf_lb", W, H,
        )
        assertNotNull("三门组合必须有图", exact)
        assertEquals("lf_rf_lb 应精确命中", "lf_rf_lb", exact!!.second)

        // 不存在的组合：必须回退到 closed，而不是空白/崩溃。
        val unknown = Car3dAssets.stateBitmapNamed(
            context, "lynk01", Car3dAssets.VIEW_CARDTOP, "zz_yy", W, H,
        )
        assertNotNull("未知状态也要有图（回退 closed）", unknown)
        assertEquals("closed", unknown!!.second)
    }

    @Test
    fun `尾门组合能解出来`() {
        val trunk = Car3dAssets.stateBitmapNamed(
            context, "lynk01", Car3dAssets.VIEW_CARDTOP, "lf_trunk", W, H,
        )
        assertNotNull("lf_trunk 必须有图", trunk)
        assertTrue("应命中带 trunk 的状态，实际 ${trunk!!.second}", trunk.second.endsWith("trunk"))
        assertNotNull("尾门单态必须是 closed_trunk", top("closed_trunk"))
    }

    private companion object {
        const val W = 356
        const val H = 264
    }
}
