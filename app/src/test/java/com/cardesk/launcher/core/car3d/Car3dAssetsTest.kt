package com.cardesk.launcher.core.car3d

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [Car3dAssets] 的资产契约测试（Robolectric，真实读 `app/src/main/assets`）。
 *
 * 盯的是**占位资产与运行时的契约**：
 * - `model.json` 能被解析（format / views / states / anim / attribution）；
 * - 状态图按调用方目标尺寸解码（卡片 79×158、弹窗 251×448 都要对）；
 * - 目录不存在 / 状态缺失 / 视角缺失时的降级链（回退 closed、hero 回退 top、全缺 → null）；
 * - 失败**永久记死**（第二次调用不再试 IO，`isBroken` 为 true）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Car3dAssetsTest {

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

    @Test
    fun `资产 model_json 可解析`() {
        val m = checkNotNull(Car3dAssets.model(context, "lynk01")) { "lynk01 的 model.json 必须能解析" }
        assertEquals("01", m.model)
        assertTrue("车型名不能为空", m.name.isNotEmpty())
        // 第179轮：01 换成「俯视 32 态」单套资产，只声明 cardtop。
        assertTrue("必须有 cardtop 视角：${m.views.keys}", m.views.containsKey(Car3dAssets.VIEW_CARDTOP))
        // 第182轮：竖放资产 slot 变 [264, 356]（横版是 [356, 264]），这里只要求「正数」，
        // 不锁死方向 —— 卡片按 slot 宽高比算缩放，横/竖由资产决定。
        assertTrue("slot 必须为正", m.views[Car3dAssets.VIEW_CARDTOP]!!.slotW > 0)
        assertTrue("slot 必须为正", m.views[Car3dAssets.VIEW_CARDTOP]!!.slotH > 0)
        assertTrue(
            "状态缺项：${m.states}",
            m.states.containsAll(listOf("closed", "lf", "rf", "lb", "rb", "all", "closed_trunk")),
        )
        assertTrue("32 态资产应有 ≥32 个状态，实际 ${m.states.size}", m.states.size >= 32)
        // 动画帧可选（俯视 32 态不烘动画）：有就必须至少两帧、220ms
        m.anims["lf"]?.let {
            assertTrue("门动画至少两帧", it.frames >= 2)
            assertEquals(220, it.ms)
        }
        // 窗动画可选（烘焙分批到货）：有就必须至少一帧
        m.anims["win"]?.let { assertTrue("窗动画至少一帧", it.frames >= 1) }
        // 尾门动画可选（烘焙分批到货）：有就必须至少一帧
        m.anims["trunk"]?.let { assertTrue("尾门动画至少一帧", it.frames >= 1) }
        assertTrue("授权署名不能为空", m.attribution.isNotEmpty())
    }

    @Test
    fun `状态图按目标尺寸解码`() {
        val small = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "closed", 79, 158)
        assertNotNull(small)
        assertEquals(79, small!!.width)
        assertEquals(158, small.height)

        val big = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "all", 251, 448)
        assertNotNull(big)
        assertEquals(251, big!!.width)
        assertEquals(448, big.height)
    }

    @Test
    fun `同样的图只解码一次 走缓存`() {
        val a = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "closed", 72, 144)
        val b = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "closed", 72, 144)
        assertNotNull(a)
        assertTrue("同 key 应命中缓存（返回同一个 Bitmap 实例）", a === b)
    }

    @Test
    fun `缺失状态回退到 closed`() {
        val bmp = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "not_a_state", 100, 180)
        assertNotNull("缺状态必须回退 closed，不能空图", bmp)
    }

    @Test
    fun `hero 视角缺失时回退 top`() {
        val bmp = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_HERO, "closed", 344, 224)
        assertNotNull("hero 缺失应回退 top", bmp)
        assertEquals(344, bmp!!.width)
    }

    // 第180轮：门缝橙色描边已整体下线（用户要求「所有机型的橙色描边都去掉」）。
    // 运行时只取 `<state>` 底图、不再 decode `<state>__gap`；资产里即使留着 gap 文件也不影响渲染。
    // 渲染层面的验证在 Car3dPaintTest「不再叠加门缝描边」。

    @Test
    fun `未知目录回退主资产且不崩`() {
        // model() 本身仍严格（该目录就是坏的，记死，不再重试 IO）
        assertNull(Car3dAssets.model(context, "no_such_model"))
        assertNull(Car3dAssets.model(context, "no_such_model"))
        assertTrue("解析失败必须永久记死（不再重试 IO）", Car3dAssets.isBroken("no_such_model"))
        // 但取图走 effectiveDir → 回退 lynk01，不空白、不崩
        assertEquals("lynk01", Car3dAssets.effectiveDir(context, "no_such_model"))
        assertNotNull(Car3dAssets.stateBitmap(context, "no_such_model", Car3dAssets.VIEW_TOP, "closed", 79, 158))
    }

    @Test
    fun `空目录名与空尺寸都是 null`() {
        assertNull(Car3dAssets.model(context, null))
        assertNull(Car3dAssets.model(context, ""))
        assertNull(Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "closed", 0, 0))
    }

    @Test
    fun `合法目录不会被误记为坏`() {
        assertNotNull(Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_TOP, "closed", 79, 158))
        assertTrue(!Car3dAssets.isBroken("lynk01"))
    }


    @Test
    fun `车漆颜色解析 defaultColor 与回退`() {
        val withColors = Car3dAssets.Model(
            dir = "x",
            model = "01",
            name = "x",
            views = emptyMap(),
            states = emptySet(),
            anims = emptyMap(),
            attribution = "",
            colors = listOf("dark", "white"),
            defaultColor = "white",
        )
        // 命中当前色
        assertEquals("white", Car3dAssets.effectiveColor(withColors, "white"))
        assertEquals("dark", Car3dAssets.effectiveColor(withColors, "dark"))
        // 未命中 → defaultColor
        assertEquals("white", Car3dAssets.effectiveColor(withColors, "pink"))
        assertEquals("white", Car3dAssets.effectiveColor(withColors, null))

        // 老资产（没有 colors 声明）→ null，走老路径，不换色也不空白
        val legacy = Car3dAssets.Model(
            dir = "x",
            model = "01",
            name = "x",
            views = emptyMap(),
            states = emptySet(),
            anims = emptyMap(),
            attribution = "",
        )
        assertNull(Car3dAssets.effectiveColor(legacy, "white"))
    }


    @Test
    fun `纯俯视资产自动识别 topDownOnly`() {
        // 01 已迁到「只声明 cardtop」的纯俯视资产。
        assertTrue(
            "lynk01 应识别为纯俯视",
            Car3dAssets.topDownOnly(context, "lynk01"),
        )
        // 未知目录回退到主资产（lynk01），同样应识别为纯俯视。
        assertTrue(
            "未知目录回退后也应识别为纯俯视",
            Car3dAssets.topDownOnly(context, "does-not-exist"),
        )
    }

    @Test
    fun `缺 cardtop 时俯视视角回退 hero`() {
        val ctx = RuntimeEnvironment.getApplication()
        val hasCardTop = Car3dAssets.hasView(ctx, "lynk01", Car3dAssets.VIEW_CARDTOP)
        val hero = Car3dAssets.stateBitmap(ctx, "lynk01", Car3dAssets.VIEW_HERO, "closed", 256, 188)
        val cardTop = Car3dAssets.stateBitmap(ctx, "lynk01", Car3dAssets.VIEW_CARDTOP, "closed", 256, 188)
        assertNotNull("hero 图应能解码", hero)
        assertNotNull("cardtop 缺资产时应回退 hero 而不是空白", cardTop)
        if (!hasCardTop) {
            assertTrue("没有 cardtop 资产时必须与 hero 完全一致", hero!!.sameAs(cardTop))
        }
    }
}
