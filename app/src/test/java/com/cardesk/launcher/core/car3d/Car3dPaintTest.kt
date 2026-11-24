package com.cardesk.launcher.core.car3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.cardesk.launcher.ui.widget.CarTopStatusView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Rule

/**
 * [Car3dPainter] 的绘制契约测试（Robolectric 离屏，真实解码占位资产）。
 *
 * 盯四件事：
 * 1. `paint` 在资产可用时**真的画了东西**（返回 true + 非透明像素）；
 * 2. 不同状态画出来的图**明显不同**（开门/开窗/尾门能被眼睛分辨，不是同一张图）；
 * 3. 开关关掉 / 资产缺失时 `paint` 返回 false —— 调用方据此回退自绘，不空图不崩；
 * 4. `CarTopStatusView`（车门未关弹窗用的那张卡，280×500）整条路径能出图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Car3dPaintTest {
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

    /**
     * 第180轮：门缝橙色描边下线。
     *
     * 资产里还留着 `<state>__gap` 文件（留档），但运行时只该画状态底图；
     * 这里逐像素比对「painter 画出来的」与「直接用 stateBitmap 画的」，相等才说明没再叠 gap。
     */
    @Test
    fun `不再叠加门缝描边 渲染结果与状态底图逐像素一致`() {
        val p = pose(lf = true)
        val (drew, bmp) = paintScene("lynk01", p, 356, 264, Car3dAssets.VIEW_CARDTOP)
        assertTrue("应画出车模", drew)
        val state = CarPoseResolver.resolve(null, p, rich = false).stateKey
        val expected = Car3dAssets.stateBitmap(
            context, "lynk01", Car3dAssets.VIEW_CARDTOP, state, 356, 264,
        )
        assertNotNull("应有 $state 底图", expected)
        assertTrue(
            "画面必须与状态底图完全一致（不再叠 __gap 描边），state=$state",
            bmp.sameAs(expected!!),
        )
    }

    private fun pose(
        lf: Boolean = false,
        rf: Boolean = false,
        lb: Boolean = false,
        rb: Boolean = false,
        win: Boolean = false,
        trunk: Boolean = false,
    ) = CarPose(
        doors = booleanArrayOf(lf, rf, lb, rb),
        windows = booleanArrayOf(win, win, win, win),
        trunk = trunk,
    )

    /** 用 painter 直接画到一张透明位图，返回 (是否画成功, 位图)。 */
    private fun paintScene(
        dir: String?,
        pose: CarPose,
        w: Int,
        h: Int,
        view: String = Car3dAssets.VIEW_TOP,
    ): Pair<Boolean, Bitmap> {
        val host = FrameLayout(context)
        val scene = Car3dPainter.Scene(host)
        scene.setPose(context, dir, pose, rich = w >= CarPoseResolver.RICH_MIN_WIDTH_PX)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val drew = scene.paint(Canvas(bmp), context, view, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        return drew to bmp
    }

    private fun opaqueCount(bmp: Bitmap): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.count { (it ushr 24) != 0 }
    }

    /** 两张图不同像素的比例。 */
    private fun diffRatio(a: Bitmap, b: Bitmap): Double {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(b.width * b.height)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var diff = 0
        for (i in pa.indices) if (pa[i] != pb[i]) diff++
        return diff.toDouble() / pa.size
    }

    @Test
    fun `资产可用时画得出东西`() {
        val (drew, bmp) = paintScene("lynk01", pose(), 251, 448)
        assertTrue("资产可用时 paint 必须返回 true", drew)
        val pixels = opaqueCount(bmp)
        assertTrue("画布不能是空的，非透明像素=$pixels", pixels > 251 * 448 / 20)
    }

    @Test
    fun `开门 开窗 尾门三种状态与全关明显不同`() {
        val (_, closed) = paintScene("lynk01", pose(), 251, 448)
        val (_, doorOpen) = paintScene("lynk01", pose(lf = true), 251, 448)
        val (_, winOpen) = paintScene("lynk01", pose(win = true), 251, 448)
        val (_, trunkOpen) = paintScene("lynk01", pose(trunk = true), 251, 448)

        val doorDiff = diffRatio(closed, doorOpen)
        val winDiff = diffRatio(closed, winOpen)
        val trunkDiff = diffRatio(closed, trunkOpen)
        assertTrue("开门应画得出来且与全关不同，diff=$doorDiff", doorDiff > 0.008)
        // 第179轮：俯视 32 态资产**没有 win 图**——车窗开合只由卡面文字表达，
        // 车模图必须与全关一致（不能凭空出现一块黄）。
        assertTrue("窗开不应改变车模图（俯视版靠文字），diff=$winDiff", winDiff < 0.0005)
        val closedArt = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_CARDTOP, "closed", 356, 264)
        val trunkArt = Car3dAssets.stateBitmap(context, "lynk01", Car3dAssets.VIEW_CARDTOP, "closed_trunk", 356, 264)
        val hasTrunkArt = closedArt != null && trunkArt != null && diffRatio(closedArt, trunkArt) > 0.0005
        assertTrue("32 态资产应含 trunk 状态图", hasTrunkArt)
        assertTrue("尾门应画得出来且与全关不同，diff=$trunkDiff", trunkDiff > 0.003)
        assertFalse("开门与全关不应只差几个像素，diff=$doorDiff", doorDiff < 0.0001)
    }

    @Test
    fun `卡片槽位 72x144 也能出图`() {
        val (drew, bmp) = paintScene("lynk01", pose(), 72, 144)
        assertTrue(drew)
        assertTrue(opaqueCount(bmp) > 72 * 144 / 20)
    }

    @Test
    fun `开关关掉时返回 false 交由调用方回退`() {
        Car3dSettings.enabled = false
        val (drew, bmp) = paintScene("lynk01", pose(), 251, 448)
        assertFalse("开关关掉必须返回 false", drew)
        assertEquals("返回 false 时不应画任何东西", 0, opaqueCount(bmp))
    }

    @Test
    fun `未知目录回退主资产出图 不空图`() {
        // 目录坏掉时模型层回退 lynk01（第168轮多车型接入），画得出来才不崩。
        val (drew, bmp) = paintScene("no_such_model", pose(), 251, 448)
        assertTrue("未知目录应回退主资产出图", drew)
        assertTrue(opaqueCount(bmp) > 0)
        assertTrue(Car3dAssets.isBroken("no_such_model"))
    }

    @Test
    fun `读不到车门时按关着画 不空图`() {
        val unknown = CarPose(unknown = true)
        val (drew, bmp) = paintScene("lynk01", unknown, 251, 448)
        assertTrue("unknown 也要出图（按关着画 + 角标）", drew)
        assertTrue(opaqueCount(bmp) > 0)
        // 角标会让它与正常 closed 不同
        val (_, closed) = paintScene("lynk01", pose(), 251, 448)
        assertTrue(diffRatio(closed, bmp) > 0.0)
    }

    /**
     * 动画自检不过 → **不播动画**，直接画目标状态（不会出现「放大/位移后再跳回来」）。
     *
     * 只有当资产确实不可动画时才有意义（当前 v4 就是这一支）；资产修好后本条会 skip，
     * 由 [Car3dAssetOrientationTest.`动画帧与状态图同机位（严格零平移）`] 接管（去掉 @Ignore）。
     */
    @Test
    fun `动画不可用时直接画目标态`() {
        val usable = Car3dAssets.canAnimate(context, "lynk01", Car3dAssets.VIEW_TOP, "lf", 280, 500)
        assumeTrue("资产已修好（动画可用），本降级用例不再适用", !usable)

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        assertTrue("测试前提：host 必须已经上屏，否则不会走动画分支", host.isAttachedToWindow)

        val rect = RectF(0f, 0f, 280f, 500f)
        val scene = Car3dPainter.Scene(host)
        scene.setPose(context, "lynk01", pose(), rich = false, view = Car3dAssets.VIEW_TOP)
        scene.paint(Canvas(Bitmap.createBitmap(280, 500, Bitmap.Config.ARGB_8888)), context, Car3dAssets.VIEW_TOP, rect)

        // 变化到「左前门开」：本来该播 anim_lf，自检拒绝 → 必须直接出 lf 状态图。
        scene.setPose(context, "lynk01", pose(lf = true), rich = false, view = Car3dAssets.VIEW_TOP)
        val out = Bitmap.createBitmap(280, 500, Bitmap.Config.ARGB_8888)
        assertTrue("要能出图", scene.paint(Canvas(out), context, Car3dAssets.VIEW_TOP, rect))

        // 第179轮：俯视资产没有动画帧 —— 第一次绘制就是终态，再画一次必须完全一致。
        val again = Bitmap.createBitmap(280, 500, Bitmap.Config.ARGB_8888)
        scene.paint(Canvas(again), context, Car3dAssets.VIEW_TOP, rect)
        assertEquals("没有动画帧时，两次绘制必须逐像素一致", 0.0, diffRatio(out, again), 0.0)
        val closed = paintScene("lynk01", pose(), 280, 500).second
        assertTrue("目标态必须与全关不同（真的画了 lf 状态）", diffRatio(out, closed) > 0.003)
    }

    @Test
    fun `弹窗那张 CarTopStatusView 能出图`() {
        val view = CarTopStatusView(context).apply {
            carModel = "01"
            doorsClosed = listOf(false, true, true, true)
            windowsOpen = BooleanArray(4)
            hoodClosed = true
            trunkClosed = true
        }
        val w = 280
        val h = 500
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        assertTrue("弹窗车模不能空白", opaqueCount(bmp) > w * h / 20)
    }
}
