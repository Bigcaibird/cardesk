package com.cardesk.launcher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.cardesk.launcher.core.DoorAlert
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dSettings
import com.cardesk.launcher.ui.dialogs.DoorAlertDialog
import com.cardesk.launcher.ui.dialogs.VehicleStatusDialog
import com.cardesk.launcher.ui.pages.ScenePage
import com.cardesk.launcher.ui.widget.CarTopStatusView
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 第163轮：写实车模（car3d）的**静态状态**预览图。
 *
 * 与 `PreviewRenderTest` 的区别：这里**不挂到窗口**（`View.isAttachedToWindow == false`），
 * `Car3dPainter` 对这种离屏绘制**不播动画**，直接画目标状态 —— 所以截到的是
 * 「闭门 / 单门开 / 四门开 / 开窗 / 尾门开」的确定态，适合和资产逐张对照。
 *
 * 产物在 `cardesk/preview/car3d-*.png`（`preview/` 不入库，本机验证用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Car3dPreviewTest {

    private val cardRegion = Rect(1488, 376, 1876, 756)
    private val tireCardRegion = Rect(1086, 780, 1436, 930)

    private val dir: File = File("../preview").apply { mkdirs() }

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

    private fun page(
        doors: List<Boolean?>,
        windows: BooleanArray = booleanArrayOf(false, false, false, false),
        trunkClosed: Boolean? = true,
    ): ScenePage {
        val page = ScenePage(RuntimeEnvironment.getApplication())
        page.bind(
            VehicleInfo(
                rangeKm = 386,
                fuelPercent = 62,
                tires = floatArrayOf(240f, 240f, 238f, 235f),
                doorsClosed = doors,
                doorsAllClosed = doors.all { it == true },
                hoodClosed = true,
                trunkClosed = trunkClosed,
                windowsOpen = windows,
                windowsAllClosed = windows.none { it },
            ),
            HvacState(),
            0,
        )
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        return page
    }

    private fun shot(page: ScenePage, name: String) {
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(full))
        val crop = Bitmap.createBitmap(full, cardRegion.left, cardRegion.top, cardRegion.width(), cardRegion.height())
        val big = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false)
        FileOutputStream(File(dir, "car3d-$name.png")).use {
            big.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun shotTire(page: ScenePage, name: String) {
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        page.draw(Canvas(full))
        val crop = Bitmap.createBitmap(
            full, tireCardRegion.left, tireCardRegion.top, tireCardRegion.width(), tireCardRegion.height(),
        )
        val big = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, false)
        FileOutputStream(File(dir, "car3d-$name.png")).use {
            big.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `第185轮 胎压与车轮同一水平线 位置与门态无关`() {
        val app = RuntimeEnvironment.getApplication()
        Car3dSettings.enabled = true
        data class R(val centers: List<android.graphics.PointF>, val ref: RectF, val image: RectF, val anchors: Map<String, com.cardesk.launcher.core.car3d.CardSnapshot.NormPoint>)

        fun render(doors: List<Boolean?>): R {
            val page = ScenePage(app)
            page.carModel = "01"
            page.showCarStatusCard = true
            page.bind(
                VehicleInfo(
                    rangeKm = 386, fuelPercent = 62,
                    doorsClosed = doors, doorsAllClosed = doors.all { it == true },
                    hoodClosed = true, trunkClosed = true,
                    windowsOpen = booleanArrayOf(false, false, false, false), windowsAllClosed = true,
                    tires = floatArrayOf(240f, 240f, 238f, 235f),
                ),
                HvacState(), 0,
            )
            page.measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            page.layout(0, 0, 1920, 1080)
            page.draw(Canvas(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)))
            val image = RectF(page.lastCarImageRect!!)
            val slot = Car3dAssets.slotOf(app, "lynk01", Car3dAssets.VIEW_CARDTOP)!!
            val n = Car3dAssets.closedBoundsNorm(app, "lynk01", Car3dAssets.VIEW_CARDTOP, slot.first, slot.second)!!
            val ref = RectF(
                image.left + n.left * image.width(), image.top + n.top * image.height(),
                image.left + n.right * image.width(), image.top + n.bottom * image.height(),
            )
            val anchors = Car3dAssets.anchors(app, "lynk01", Car3dAssets.VIEW_CARDTOP)!!
            return R(page.lastTirePillCenters, ref, image, anchors)
        }

        val a = render(listOf(true, true, true, true))
        val b = render(listOf(false, true, true, true))
        val closed = a.centers
        assertEquals("四颗胎压", 4, closed.size)
        for (k in 0 until 4) {
            assertEquals("第 $k 颗 x 与门态无关", closed[k].x, b.centers[k].x, 0.01f)
            assertEquals("第 $k 颗 y 与门态无关", closed[k].y, b.centers[k].y, 0.01f)
        }
        // y 和对应车轮锚点同一水平线；x 贴卡边（左 14..66 / 右 322..374）
        val keys = arrayOf("tires.fl", "tires.fr", "tires.rl", "tires.rr")
        for (k in 0 until 4) {
            val an = a.anchors[keys[k]] ?: error("缺锚点 $k")
            val wantY = a.image.top + an.y * a.image.height()
            assertEquals("第 $k 颗 y 应与锚点同水平线", wantY, closed[k].y, 0.6f)
            val wantX = if (an.x < 0.5f) a.ref.left - 24f - 26f else a.ref.right + 24f + 26f
            assertEquals("第 $k 颗 x 应离车体 24px", wantX, closed[k].x, 0.6f)
        }
        assertEquals("前轮两颗同 y", closed[0].y, closed[1].y, 0.01f)
        assertEquals("后轮两颗同 y", closed[2].y, closed[3].y, 0.01f)
        // 状态文字保守框：x 从卡内 16 起、基线 352、13px 字 → 取 339..356
        val statusRect = RectF(1488f + 16f, 376f + 339f, 1488f + 250f, 376f + 356f)
        for (k in 0 until 4) {
            val pill = RectF(closed[k].x - 26f, closed[k].y - 23f, closed[k].x + 26f, closed[k].y + 23f)
            assertFalse("第 $k 颗胶囊不能压状态文字：$pill vs $statusRect", RectF.intersects(pill, statusRect))
        }
        // 与车漆像素最近距离 ≥6px
        val slot3 = Car3dAssets.slotOf(app, "lynk01", Car3dAssets.VIEW_CARDTOP)!!
        val mask = Car3dAssets.closedBitmap(app, "lynk01", Car3dAssets.VIEW_CARDTOP, slot3.first, slot3.second)!!
        val sx = a.image.width() / mask.width
        val sy = a.image.height() / mask.height
        val dists = FloatArray(4)
        for (k in 0 until 4) {
            val pl = closed[k].x - 26f; val pt = closed[k].y - 23f
            val pr = closed[k].x + 26f; val pb = closed[k].y + 23f
            var best = Float.MAX_VALUE
            for (my in 0 until mask.height step 2) {
                for (mx in 0 until mask.width step 2) {
                    if ((mask.getPixel(mx, my) ushr 24) < 200) continue
                    val ax = a.image.left + mx * sx
                    val ay = a.image.top + my * sy
                    if (ax < pl - 80f || ax > pr + 80f || ay < pt - 80f || ay > pb + 80f) continue
                    val dx = when { ax < pl -> pl - ax; ax > pr -> ax - pr; else -> 0f }
                    val dy = when { ay < pt -> pt - ay; ay > pb -> ay - pb; else -> 0f }
                    val d = kotlin.math.hypot(dx, dy)
                    if (d < best) best = d
                }
            }
            dists[k] = best
            assertTrue("第 $k 颗胶囊离车漆应在 20~40px，实际 $best", best in 20f..40f)
        }
        val out = java.io.File("../preview/review13").apply { mkdirs() }
        java.io.File(out, "pill-centers-01.txt").writeText(
            "closed: " + closed.joinToString(" ") { "(%.1f, %.1f)".format(it.x - 1488f, it.y - 376f) } +
                "\nlf    : " + b.centers.joinToString(" ") { "(%.1f, %.1f)".format(it.x - 1488f, it.y - 376f) } +
                "\nanchors: fl(%.3f,%.3f) fr(%.3f,%.3f) rl(%.3f,%.3f) rr(%.3f,%.3f)".format(
                    a.anchors["tires.fl"]!!.x, a.anchors["tires.fl"]!!.y,
                    a.anchors["tires.fr"]!!.x, a.anchors["tires.fr"]!!.y,
                    a.anchors["tires.rl"]!!.x, a.anchors["tires.rl"]!!.y,
                    a.anchors["tires.rr"]!!.x, a.anchors["tires.rr"]!!.y,
                ) +
                "\ndist-to-paint(px): %.1f / %.1f / %.1f / %.1f".format(dists[0], dists[1], dists[2], dists[3]) +
                "\nimage: (%.1f, %.1f)-(%.1f, %.1f)".format(
                    a.image.left - 1488f, a.image.top - 376f, a.image.right - 1488f, a.image.bottom - 376f,
                ),
        )
    }

    @Test
    fun `第174轮 状态文字含车窗`() {
        // 0 处：沿用 19-card-c-closed 的「全车已关」。
        shot(page(listOf(true, true, true, true)), "40-status-zero")
        // 1 处（窗）：黄色单条。
        shot(
            page(
                listOf(true, true, true, true),
                windows = booleanArrayOf(true, false, false, false),
            ),
            "41-status-one-window",
        )
        // 2 处（门 + 窗）：混色逐项列出。
        shot(
            page(
                listOf(false, true, true, true),
                windows = booleanArrayOf(true, false, false, false),
            ),
            "42-status-two-mixed",
        )
        // 3 处以上：合并一句。
        shot(
            page(
                listOf(false, false, true, true),
                windows = booleanArrayOf(true, false, false, false),
            ),
            "43-status-many",
        )
    }

    @Test
    fun `车况卡各状态`() {
        shot(page(listOf(true, true, true, true)), "01-closed")
        shot(page(listOf(false, true, true, true)), "02-lf-open")
        shot(page(listOf(true, false, true, false)), "03-rf-rb-open")
        shot(page(listOf(false, false, false, false)), "04-all-open")
        shot(page(listOf(true, true, true, true), windows = booleanArrayOf(true, true, true, true)), "05-win-open")
        shot(
            page(listOf(true, true, true, true), windows = booleanArrayOf(true, false, false, false)),
            "05b-win-lf-only",
        )
        shot(page(listOf(true, true, true, true), trunkClosed = false), "06-trunk-open")
        shotTire(page(listOf(true, true, true, true)), "07-tire-card")
    }

    /** 整块弹窗面板（760×840），按面板位置裁出来。 */
    private fun shotDialog(dialog: DoorAlertDialog, name: String) {
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        dialog.draw(Canvas(full))
        val crop = Bitmap.createBitmap(full, 580, 70, 760, 840)
        FileOutputStream(File(dir, "car3d-$name.png")).use {
            crop.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun dialogFor(
        doors: List<Boolean?>,
        windows: BooleanArray = booleanArrayOf(false, false, false, false),
        hoodClosed: Boolean? = true,
    ) = DoorAlertDialog(
        RuntimeEnvironment.getApplication(),
        Palettes.night,
        DoorAlert.openItems(doors, hoodClosed, true),
        doors,
        windows,
        hoodClosed,
        true,
        "01",
    )

    @Test
    fun `第164轮 弹窗 3-4 大图`() {
        // 用户要的 4 张验图：10 左前门开 / 11 全关 / 12 车况卡 / 13 胎压卡。
        shotDialog(dialogFor(listOf(false, true, true, true)), "10-dialog-hero-lf-open")
        // 车是「全关」的：引擎盖开着（没有引擎盖状态图，车身仍是 closed），
        // 这样标题/胶囊行/说明都在，能单独看车身闭门的样子。
        shotDialog(dialogFor(listOf(true, true, true, true), hoodClosed = false), "11-dialog-hero-closed")
        // 附加：3/4 视角才看得见的两种状态（开窗 / 四门全开）。
        shotDialog(dialogFor(listOf(true, true, true, true), windows = booleanArrayOf(true, true, true, true)), "14-dialog-hero-win")
        shotDialog(dialogFor(listOf(false, false, false, false)), "15-dialog-hero-all-open")
    }

    @Test
    fun `第164轮 卡片真图 card 视角`() {
        shot(page(listOf(true, true, true, true)), "12-card-real")
        shotTire(page(listOf(true, true, true, true)), "13-tirecard-real")
        // lead 要的两张：车况卡开门 / 车况卡开窗，用来判 88×176 上读不读得出状态。
        shot(page(listOf(false, true, true, true)), "16-card-door-open")
        shot(
            page(listOf(true, true, true, true), windows = booleanArrayOf(true, true, true, true)),
            "17-card-win-open",
        )
    }

    /**
     * 第165轮：**动画中间帧**的证据图。
     *
     * 与上面那些静态图不同，这里必须把 View 挂进窗口（`isAttachedToWindow`），
     * `Car3dPainter` 才会播 220ms 的开门动画；然后把主线程时钟推进 110ms
     * （6 帧里第 3 帧附近）再截一张，证明过渡真的在播、不是「一帧跳过去」。
     *
     * 之所以用 `top` 视角：动画帧 `top/anim_lf_0..5` 是 top 家族出的
     * （hero/弹窗视角没有 anim 资产，自检会拒绝播，见 `Car3dAssets.canAnimate`）。
     */
    /** 车辆状态面板（同样按面板位置裁 760×840）。 */
    private fun shotVehicleStatus(
        doors: List<Boolean?>,
        name: String,
        windows: BooleanArray = booleanArrayOf(false, false, false, false),
        carModel: String = "01",
    ) {
        val dialog = VehicleStatusDialog(
            RuntimeEnvironment.getApplication(),
            Palettes.night,
            doors,
            windows,
            true,
            true,
            carModel,
        )
        dialog.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        dialog.layout(0, 0, 1920, 1080)
        val full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(0xFF141A26.toInt())
        dialog.draw(canvas)
        // 车辆状态面板矮一些（720）：大图 + 一行说明，没有胶囊行/列表。
        val crop = Bitmap.createBitmap(full, 580, 70, 760, 720)
        FileOutputStream(File(dir, "car3d-$name.png")).use {
            crop.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun `第167轮 快照卡胶囊`() {
        shot(page(listOf(true, true, true, true)), "28-card-snapshot-closed")
        // 八处异常（4 门 + 4 窗）：两行文字，验证不会压到胎压行。
        shot(
            page(
                listOf(false, false, false, false),
                windows = booleanArrayOf(true, true, true, true),
            ),
            "28b-card-snapshot-two-lines",
        )
        shot(page(listOf(false, true, true, true)), "29-card-snapshot-lf-open")
        // 四门 + 尾门 = 5 个异常 → 3 个胶囊 + `+2`
        shot(page(listOf(false, false, false, false), trunkClosed = false), "30-card-snapshot-4doors-trunk")
    }

    @Test
    fun `第167轮 三视角`() {
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
        shot(page(listOf(true, true, true, true)), "31-card-view-hero")
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_CARDTOP
        shot(page(listOf(true, true, true, true)), "32-card-view-cardtop")
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_FRONT
        shot(page(listOf(true, true, true, true)), "33-card-view-front")
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
    }

    @Test
    fun `第168轮 锚点落位与 05 车型`() {
        // 锚点到位后：胶囊贴车体（26~36px），不再拉边
        shot(page(listOf(true, true, true, true)), "34-card-anchored-closed")
        shot(page(listOf(false, true, true, true)), "35-card-anchored-lf-open")
        // 「正前 3/4」用新 card 512×360（不再回退 hero）
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_FRONT
        shot(page(listOf(true, true, true, true)), "36-card-front34")
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
        // 05 车型（hero，暂缺 card/anchors → 兜底排布）
        val p05 = page(listOf(true, true, true, true)).also { it.carModel = "05" }
        shot(p05, "37-card-05-closed")
        val p05open = page(listOf(false, true, true, true)).also { it.carModel = "05" }
        shot(p05open, "38-card-05-door-open")
    }

    @Test
    fun `第167轮 车况卡视角：俯视 vs 3-4`() {
        val hasCardTop = Car3dAssets.hasView(
            RuntimeEnvironment.getApplication(),
            "lynk01",
            Car3dAssets.VIEW_CARDTOP,
        )
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
        shot(page(listOf(true, true, true, true)), "24-card-34-closed")
        Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
        shot(page(listOf(false, true, true, true)), "25-card-34-door-open")
        if (hasCardTop) {
            Car3dSettings.cardView = Car3dSettings.CARD_VIEW_CARDTOP
            shot(page(listOf(true, true, true, true)), "26-card-top-closed")
            shot(page(listOf(false, true, true, true)), "27-card-top-door-open")
            Car3dSettings.cardView = Car3dSettings.CARD_VIEW_HERO
        }
    }

    @Test
    fun `第166轮 车况卡 C 版与车辆状态面板`() {
        shot(page(listOf(true, true, true, true)), "19-card-c-closed")
        shot(page(listOf(false, true, true, true)), "20-card-c-lf-open")
        shotTire(page(listOf(true, true, true, true)), "21-card-c-tire")
        shotVehicleStatus(listOf(false, true, true, true), "22-status-dialog-lf")
        shotVehicleStatus(listOf(true, true, true, true), "23-status-dialog-closed")
        // 第171轮：密集锚点的极端情况（4 门 + 4 窗全开），证明标签不叠、不压车体。
        val allWin = booleanArrayOf(true, true, true, true)
        shotVehicleStatus(listOf(false, false, false, false), "30-status-dialog-many", windows = allWin)
        shotVehicleStatus(listOf(false, false, false, false), "31-status-dialog-03-many", windows = allWin, carModel = "03")
    }

    private fun context() = RuntimeEnvironment.getApplication()

    private fun render(view: View): Bitmap {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // 车模视图本身是透明的；铺一层弹窗/卡片同色底，预览图看图更直观。
        canvas.drawColor(0xFF141A26.toInt())
        view.draw(canvas)
        return bmp
    }

    @Test
    fun `弹窗那张 280x500 的车模`() {
        val view = CarTopStatusView(RuntimeEnvironment.getApplication())
        view.carModel = "01"
        view.doorsClosed = listOf(false, true, true, true)
        view.windowsOpen = booleanArrayOf(false, false, false, false)
        view.hoodClosed = true
        view.trunkClosed = true
        view.measure(
            View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 280, 500)
        val bmp = Bitmap.createBitmap(280, 500, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, "car3d-08-dialog-lf-open.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
