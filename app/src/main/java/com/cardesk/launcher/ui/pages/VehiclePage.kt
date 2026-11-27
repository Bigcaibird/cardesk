package com.cardesk.launcher.ui.pages

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.GlassDrawable
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.core.car3d.Car3dAssets
import com.cardesk.launcher.core.car3d.Car3dDebugPose
import com.cardesk.launcher.core.car3d.Car3dPainter
import com.cardesk.launcher.core.car3d.CarPose
import com.cardesk.launcher.vehicle.AdaptapiSensors
import com.cardesk.launcher.vehicle.BatteryVoltageProbe
import com.cardesk.launcher.vehicle.TireOrder
import com.cardesk.launcher.vehicle.VehicleInfo
import kotlin.math.abs

/*
 * 车模的「车漆 / 玻璃 / 轮胎」色。
 *
 * 这几个不是主题色：画板 05（日间）和 n04（夜间）里车模是**同一辆浅色车**，
 * 并不随日夜反相（夜间只是卡片变深、车身依旧浅白）。所以不接 Skin token 硬套，
 * 而是作为「这辆车的材质」定义在文件内；尾灯仍用 Skin.ACCENT，跟着主题走。
 */
private val CAR_PAINT_TOP = 0xFFFFFFFF.toInt()
private val CAR_PAINT_BOTTOM = 0xFFE1E8EE.toInt()
private val CAR_PAINT_EDGE = 0xFFD4DDE5.toInt()
private val CAR_GLASS = 0xFF3B4754.toInt()
private val CAR_ROOF = 0xFFD1DBE2.toInt()
private val CAR_TIRE = 0xFF4B525D.toInt()

/** 车模落影（中性黑，不是品牌色；只用来把车从白卡上「托」起来）。 */
private val CAR_SHADOW = 0x14000000

/**
 * cardtop 资产右侧的烘焙柔影裁切线（占视图宽度的比例）。
 *
 * 资产 `car3d/<model>/cardtop/<color>/closed.webp` 在 x210..222 有一条 alpha 16..35 的
 * 竖向柔影（镜像/车侧的投影，出图时被裁成了硬边）。首页车况卡把它压到 190px 宽、
 * 又用 fit=false 拉伸，肉眼几乎看不出；车辆页是 300px 宽、fit=true，它就成了
 * 14px 宽、266px 高的灰条（实测 sRGB 220），在白卡上非常扎眼。
 * 车身右缘落在视图宽度的 78.5%，从这里裁掉，只留车身，不动首页。
 */
private const val CAR3D_CLIP_RIGHT = 0.785f

/**
 * 车辆页面板的实底（画板 05 / n04 实测：日 `#FFFFFF`、夜 `#171E26`）。
 *
 * 为什么不用 `Skin.SHEET_FILL`：它在 `Skin.apply()` 里等于 `pal.surface`
 * （日间 62% 白 / 夜间 66%），是**玻璃**填充；而画板里车辆页是整屏实底页面，
 * 透出主页面的「386 / 车况卡 / 天气」正是实机截图上最明显的偏差。
 */
private val PAGE_FILL_DAY = 0xFFFFFFFF.toInt()
private val PAGE_FILL_NIGHT = 0xFF171E26.toInt()

/**
 * 车辆页：续航 / 胎压 / 车门状态 / 一些模拟快捷动作。
 *
 * ## 这一版为什么整个重写布局
 *
 * 旧版是「卡片流」：`LinearLayout` 自上而下堆，尺寸（860/32f 等）和画板对不上，
 * 字阶也偏大（标题 44、卡标题 24、门态 24）。画板 05/n04 是**绝对栅格**：
 * 一张 1836×880 的玻璃浮层里，左列两张卡（490 宽）、中列车模卡（782）、
 * 右列快捷卡（436），间距 30/31，全部按像素定死。所以这里改成
 * **FrameLayout + 绝对坐标**，坐标直接从设计稿量出来，注释里写的是画板像素。
 *
 * 功能与回调保持兼容：`bind(info)`、四门明细、胎压、续航、数据来源、
 * 颜色渐变与 `Motion.nudge` 全部保留。
 *
 * 右列按画板改成「快捷入口」**3 个真实入口**（空调 / 360 / 原厂设置），
 * 不再是 6 个只弹 Toast 的占位动作；入口通过 [onOpenClimate] / [onOpen360] /
 * [onOpenOem] 三个公开回调交给宿主接线。
 */
@SuppressLint("ViewConstructor")
class VehiclePage(context: Context, private val pal: Palette) : FrameLayout(context) {

    private val rangeValue: TextView

    /** 「油量」的百分比读数（画板 78% 那一行）。 */
    private val fuelValue: TextView

    /** 「总里程」读数（单位 km）。 */
    private val odometerValue: TextView

    /** 「平均油耗」读数（单位 L/100km）。 */
    private val avgFuelValue: TextView

    /** 「保养里程」读数（单位 km）。 */
    private val maintenanceValue: TextView

    /** 「实时车速」读数（单位 km/h）。 */
    private val speedValue: TextView

    /** 「电瓶电压」读数（单位 V）。 */
    private val batteryVoltageValue: TextView

    /** 「车内 PM2.5」读数（单位 µg/m³）。 */
    private val pm25Value: TextView

    /** 「车内 AQI」读数。 */
    private val aqiValue: TextView

    /**
     * 「海拔」读数。
     *
     * 海拔来自定位通道，异步到达（见 [com.cardesk.launcher.core.AltitudeDesk]），
     * 所以不在 [bind] 里刷，而是由宿主把 `AltitudeDesk.text()` 赋给 [altitudeText]。
     */
    private val altitudeValue: TextView

    /** 右下「传感器监测」胶囊：电源 / 蓄电池 / 机油 / 制动液 / 冷却液 / 油量告警。 */
    private val sensorIgnitionPill: TextView
    private val sensorBatteryPill: TextView
    private val sensorOilPill: TextView
    private val sensorBrakeFluidPill: TextView
    private val sensorCoolantPill: TextView
    private val sensorFuelWarnPill: TextView
    private val tireValues = arrayOfNulls<TextView>(4)
    private val carView: TopCarView
    private val doorLabels = ArrayList<TextView>()

    /**
     * 后备箱状态胶囊（第246轮按用户「车辆详情页没有后备箱的开关显示」补上）。
     *
     * 四门 + 后备箱是「离车前要确认的五个开口」，画板只画了四门；
     * 后备箱单独一行、字号/胶囊与四门完全同构，读的是 [com.cardesk.launcher.core.VehicleInfo.trunkClosed]。
     */
    private var trunkLabel: TextView? = null

    /** 后备箱胶囊当前的文字色（与 [doorColors] 同职责，供渐变动画取起点）。 */
    private var trunkColor: Int = 0

    /**
     * 每个车门标签**当前显示中**的颜色。
     *
     * `TextView` 没法反查自己现在的文字色，所以自己记一份 ——
     * 渐变要从「现在显示的颜色」出发，中途再切状态才接得上。
     */
    private val doorColors = IntArray(4)
    private val sourceLabel: TextView

    /** 关闭车辆详情页回调（点按页面外、下滑或左右滑动关闭）。 */
    var onClose: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val swipeSlop = 24f
    private val dismissDistance = 80f

    /**
     * 右列三个真实入口的回调。
     *
     * 车辆页自己不知道该跳哪个页面（空调页 / 360 画面 / 原厂设置都在宿主侧），
     * 所以只负责「点了通知一声」——和原来 6 个动作的定位一样，但换成真实入口。
     * [onOpenOem] 为空表示本机没有原厂设置包：那一行按画板置灰、不响应点击。
     */
    /**
     * 车型（`CarModels.MODEL_*`）：车辆页的车模要和首页车况卡**同一台车**。
     *
     * 第244轮：用户反馈「车况详情页的车模也应该用实际的车模俯视图，不要用自绘的」。
     * 现在这里走 `Car3dPainter` 的 cardtop 烘焙图（和车况卡同一套资产、同一个视角），
     * 只有资产缺失/3D 关掉时才回落到本文件原来的自绘俯视图。
     */
    var carModel: String = CarModels.MODEL_03
        set(value) {
            field = value
            carView.carModel = value
        }

    /**
     * 海拔展示文案（如 `42 m`；无值 `--`）。
     *
     * 海拔是定位通道的异步数据，不跟着 [bind] 的车辆信号走；宿主把
     * `AltitudeDesk.text()` 赋进来即可。文案真的变化时才更新并「顶一下」，
     * 避免定时注入时数字一直抖。
     */
    var altitudeText: String = "--"
        set(value) {
            if (field == value) return
            field = value
            if (altitudeValue.text?.toString() != value) {
                altitudeValue.text = value
            }
        }

    var onOpenClimate: (() -> Unit)? = null
    var onOpen360: (() -> Unit)? = null
    var onOpenOem: (() -> Unit)? = null

    init {
        // 整页浮层：画板是 44,67 起、1836×880 的玻璃面（圆角 28）。
        // 页面本身不铺底色 —— 浮层外还能看到桌面，和抽屉保持同一套「浮层」语言。
        // 面板必须是**不透明**的：画板 05/n04 里车辆页盖住整屏，下面看不到桌面卡片；
        // 用 `Skin.surface`（62% 白）实机截图会把主页面的「386 / 车况卡 / 天气」透出来
        // （`/tmp/v-vehicle-page.png`）。读数区就是这样被干扰的。
        val panel = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener { }
            background = GlassDrawable(
                28f,
                if (pal.night) PAGE_FILL_NIGHT else PAGE_FILL_DAY,
                Skin.GLASS_STROKE,
                true,
                true,
            )
        }
        // 页面级 L0 背景：画板 05/n04 的面板外是**同一张设计底**（不是桌面卡片）。
        // 单独铺一层，HOME 底部四卡就不会从面板下沿（947）与卡片底（953）之间透出来
        // —— 独立验收 P1-5 的「y940..960 露出首页白卡」。
        addView(
            com.cardesk.launcher.ui.widget.BackgroundView(context).apply {
                palette = this@VehiclePage.pal
                isClickable = true
                setOnClickListener { onClose?.invoke() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(panel, Ui.frame(1836, 880, Gravity.TOP or Gravity.START, 44, 67))

        // 标题 28px：画板文字 bbox 是 x77..128 / y100..118，落到面板内约 (32, 26)。
        panel.addView(
            Ui.text(context, "车辆", 28f, Skin.TEXT, bold = true),
            Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 32, 28),
        )
        // 右上「数据来源」（画板是「↻ 模拟数据」）：保留原字段，只把字号压到 14。
        sourceLabel = Ui.text(context, "", 14f, Skin.TEXT_DIM).apply { gravity = Gravity.END }
        panel.addView(sourceLabel, endFrame(360, 20, 36, 27))

        /* ---------- 左列：续航与油量（占满左列 32,71 / 490×786，放大字号 + 电瓶电压） ---------- */
        val range = card()
        panel.addView(range, Ui.frame(490, 786, Gravity.TOP or Gravity.START, 32, 71))

        range.addView(
            Ui.text(context, "续航与油量", 18f, Skin.TEXT_SOFT),
            Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 26, 22),
        )
        // 「386」68px + 「km」26px：放大续航数字与单位，更易读。
        val valueRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        rangeValue = Ui.text(context, "--", 68f, Skin.TEXT, bold = true)
        valueRow.addView(rangeValue, Ui.linear(-2, -2))
        valueRow.addView(
            Ui.text(context, "km", 26f, Skin.TEXT_DIM),
            Ui.linear(-2, -2).apply {
                leftMargin = 10
                bottomMargin = 12
            },
        )
        range.addView(valueRow, Ui.frame(436, 92, Gravity.TOP or Gravity.START, 26, 56))

        // 指标明细：油量、总里程、平均油耗、保养里程、车速、电瓶电压、PM2.5、AQI、海拔
        val metricTop = 176
        val metricStep = 64
        fun addMetricRow(index: Int, key: String, valueSp: Float = 20f, labelSp: Float = 18f): TextView {
            val row = kvRow(key, "--", valueSp, labelSp)
            val y = metricTop + index * metricStep
            range.addView(row, Ui.frame(436, 44, Gravity.TOP or Gravity.START, 26, y))
            if (index > 0) {
                range.addView(
                    View(context).apply { setBackgroundColor(Skin.GLASS_STROKE) },
                    Ui.frame(436, 1, Gravity.TOP or Gravity.START, 26, y - 10),
                )
            }
            return (row.getChildAt(1) as TextView).apply {
                fontFeatureSettings = "tnum"
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }

        fuelValue = addMetricRow(0, "油量", 24f, 19f)
        odometerValue = addMetricRow(1, "总里程", 21f, 18f)
        avgFuelValue = addMetricRow(2, "平均油耗", 21f, 18f)
        maintenanceValue = addMetricRow(3, "保养里程", 20f, 18f)
        speedValue = addMetricRow(4, "实时车速", 20f, 18f)
        batteryVoltageValue = addMetricRow(5, "电瓶电压", 22f, 18f)
        pm25Value = addMetricRow(6, "车内 PM2.5", 20f, 18f)
        aqiValue = addMetricRow(7, "车内 AQI", 20f, 18f)
        altitudeValue = addMetricRow(8, "海拔", 20f, 18f).apply { minWidth = 110 }

        /* ---------- 中列：车况与胎压（画板 552,71 / 782×786） ---------- */
        val model = card()
        panel.addView(model, Ui.frame(782, 786, Gravity.TOP or Gravity.START, 552, 71))
        model.addView(
            Ui.text(context, "车门与胎压", 18f, Skin.TEXT_SOFT),
            Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 24, 20),
        )

        // 车模：在左/右胎压徽标之间精确水平居中（中心 x=258），两侧留出等距胎压位
        carView = TopCarView(context, pal)
        model.addView(carView, Ui.frame(260, 360, Gravity.TOP or Gravity.START, 128, 170))

        // 四轮胎压（FL=0, FR=1, RL=2, RR=3），相对车模中心 x=258 严格左右对称（各距中心 114px）
        val tirePos = arrayOf(
            24 to 215,   // 左前（x=24..144）
            372 to 215,  // 右前（x=372..492）
            24 to 415,   // 左后（x=24..144）
            372 to 415,  // 右后（x=372..492）
        )
        for (i in 0 until 4) {
            val badge = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(10, 10, 10, 10)
                background = Ui.round(tint(Skin.TEXT, 0x10), 16f)
            }
            badge.addView(
                Ui.text(context, TireOrder.NAMES[i], 13f, Skin.TEXT_DIM),
                Ui.linear(-2, -2),
            )
            val tireRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val v = Ui.text(context, "--", 26f, Skin.TEXT, bold = true)
            tireValues[i] = v
            tireRow.addView(v, Ui.linear(-2, -2))
            tireRow.addView(
                Ui.text(context, "kPa", 12f, Skin.TEXT_DIM),
                Ui.linear(-2, -2).apply {
                    leftMargin = 3
                    bottomMargin = 3
                },
            )
            badge.addView(tireRow, Ui.linear(-2, -2).apply { topMargin = 2 })
            model.addView(
                badge,
                Ui.frame(120, 74, Gravity.TOP or Gravity.START, tirePos[i].first, tirePos[i].second),
            )
        }

        // 四门 + 后备箱状态：放在中列右侧（x=514..758），与四轮胎压互不遮挡
        val states = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        model.addView(states, Ui.frame(244, 240, Gravity.TOP or Gravity.START, 514, 250))
        for (label in listOf("主驾车门", "副驾车门", "左后车门", "右后车门", "后备箱")) {
            val rowView = FrameLayout(context)
            rowView.addView(
                Ui.text(context, label, 16f, Skin.TEXT),
                Ui.frame(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START, 0, 0),
            )
            // 状态胶囊 12px：绿=已关闭、橙=已打开、灰=读不到。
            // 初值是「--」而不是「已关闭」：数据没来之前不假装门是关的。
            val pill = Ui.text(context, "--", 12f, pal.textFaint).apply {
                setPadding(12, 6, 12, 6)
                background = Ui.round(tint(pal.textFaint, 0x1E), Shape.PILL)
            }
            if (label == "后备箱") {
                trunkLabel = pill
                trunkColor = pal.textFaint
            } else {
                doorLabels.add(pill)
                doorColors[doorLabels.size - 1] = pal.textFaint
            }
            rowView.addView(
                pill,
                Ui.frame(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END, 0, 0),
            )
            states.addView(rowView, Ui.linear(-1, 48))
        }

        /* ---------- 右列上：快捷入口（1364,71 / 436×364） ---------- */
        val actions = card()
        panel.addView(actions, Ui.frame(436, 364, Gravity.TOP or Gravity.START, 1364, 71))
        // 画板标题「快捷入口」16px（和其它卡标题同一档）。
        actions.addView(
            Ui.text(context, "快捷入口", 16f, Skin.TEXT_SOFT),
            Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 22, 20),
        )
        val entries = listOf(
            Triple(0, "空调控制", "打开空调调整页"),
            Triple(1, "360 环影", "厂商 AVM 画面"),
            Triple(2, "原厂设置", "原厂设置包存在时"),
        )
        for ((i, entry) in entries.withIndex()) {
            val (type, title, sub) = entry
            val enabled = true
            val row = FrameLayout(context).apply {
                background = Ui.round(tint(Skin.TEXT, 0x12), 20f)
                if (enabled) {
                    isClickable = true
                    Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
                    setOnClickListener {
                        when (i) {
                            0 -> onOpenClimate?.invoke()
                            1 -> onOpen360?.invoke()
                            else -> onOpenOem?.invoke()
                        }
                    }
                } else {
                    alpha = 0.45f
                }
            }
            row.addView(
                QuickIcon(context, type),
                Ui.frame(24, 24, Gravity.CENTER_VERTICAL or Gravity.START, 18, 0),
            )
            val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(Ui.text(context, title, 16f, Skin.TEXT, bold = true))
            textCol.addView(
                Ui.text(context, sub, 12f, Skin.TEXT_DIM),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2 },
            )
            row.addView(textCol, Ui.frame(270, -2, Gravity.CENTER_VERTICAL or Gravity.START, 54, 0))
            row.addView(
                Ui.text(context, "›", 20f, Skin.TEXT_DIM).apply { gravity = Gravity.END },
                Ui.frame(24, 24, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = 16 },
            )
            actions.addView(row, Ui.frame(392, 88, Gravity.TOP or Gravity.START, 22, 54 + i * 100))
        }

        /* ---------- 右列下：传感器监测（1364,457 / 436×400） ---------- */
        val sensorsCard = card()
        panel.addView(sensorsCard, Ui.frame(436, 400, Gravity.TOP or Gravity.START, 1364, 457))
        sensorsCard.addView(
            Ui.text(context, "传感器监测", 16f, Skin.TEXT_SOFT),
            Ui.frame(-2, -2, Gravity.TOP or Gravity.START, 22, 20),
        )
        val sensorList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sensorsCard.addView(sensorList, Ui.frame(392, 324, Gravity.TOP or Gravity.START, 22, 56))
        fun addSensorRow(label: String, divider: Boolean): TextView {
            if (divider) {
                sensorList.addView(
                    View(context).apply { setBackgroundColor(Skin.GLASS_STROKE) },
                    Ui.linear(-1, 1),
                )
            }
            val rowView = FrameLayout(context)
            rowView.addView(
                Ui.text(context, label, 16f, Skin.TEXT),
                Ui.frame(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START, 0, 0),
            )
            val pill = Ui.text(context, "--", 13f, pal.textFaint).apply {
                setPadding(14, 6, 14, 6)
                background = Ui.round(tint(pal.textFaint, 0x1E), Shape.PILL)
            }
            rowView.addView(
                pill,
                Ui.frame(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END, 0, 0),
            )
            sensorList.addView(rowView, Ui.linear(-1, 53))
            return pill
        }
        sensorIgnitionPill = addSensorRow("电源状态", false)
        sensorBatteryPill = addSensorRow("蓄电池电量", true)
        sensorOilPill = addSensorRow("机油液位", true)
        sensorBrakeFluidPill = addSensorRow("制动液位", true)
        sensorCoolantPill = addSensorRow("冷却液", true)
        sensorFuelWarnPill = addSensorRow("油量告警", true)
    }

    /**
     * 一张 L3 实底卡片。
     *
     * 画板里车辆页的卡是**不透明的白卡**（夜间深色卡），和抽屉里的玻璃卡不是一层材质：
     * 续航/胎压/门态是读数区，背景透出壁纸会让数字对比度随壁纸忽高忽低。
     * 所以这里用 `Skin.surfaceStrong` 的纯色 + 1px 边，不用 [GlassDrawable] 的模糊底。
     */
    private fun card(): FrameLayout = FrameLayout(context).apply {
        // 画板 05 实测：面板与卡面**都是纯白**（日 (300,300)=255、夜 #171E26），
        // 卡与卡之间靠 1px 描边分隔；只有卡内的小胎压块是叠加墨色（日 236）。
        // 之前卡面也用 7% 墨 → 238 vs 255（Δ17，第 3 轮复核点名）。
        background = Ui.round(pal.withAlpha(pal.surfaceStrong, 0xFF), Shape.CARD, 1f, Skin.GLASS_STROKE)
    }

    /**
     * 一行「左标签 + 右值」，值右对齐（画板里 78% / 14.2 kWh 都贴卡右）。
     *
     * @param valueSp 右值字号。画板里百分比行是 16px、单位较长的行是 14px；
     *   显式传入，避免「初值是 `--`」时按后缀猜字号、数据到位后字号不跟着变。
     */
    private fun kvRow(
        key: String,
        value: String,
        valueSp: Float = 14f,
        labelSp: Float = 16f,
    ): FrameLayout = FrameLayout(context).apply {
        addView(
            Ui.text(context, key, labelSp, Skin.TEXT_SOFT),
            Ui.frame(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START, 0, 0),
        )
        addView(
            Ui.text(context, value, valueSp, Skin.TEXT),
            Ui.frame(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END, 0, 0),
        )
    }

    /** 右对齐的绝对定位（END 重力下 leftMargin 不生效，必须写 rightMargin）。 */
    private fun endFrame(w: Int, h: Int, right: Int, top: Int): FrameLayout.LayoutParams =
        Ui.frame(w, h, Gravity.TOP or Gravity.END).apply {
            rightMargin = right
            topMargin = top
        }

    /**
     * 把语义色变成「同色相、指定透明度」的底色。
     *
     * 画板里的灰格/胶囊底其实是主文字色的低透明度版本，不是另调的一支灰；
     * 这样日夜切换时底色会跟着文字色自动配对，不用维护两套灰。
     */
    private fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private var lastCarPose: CarPose? = null
    private val doorBgColors = IntArray(4) { 0 }
    private var trunkBgColor: Int = 0

    private fun setTextIfChanged(view: TextView, text: String) {
        if (view.text?.toString() != text) view.text = text
    }

    fun bind(info: VehicleInfo) {
        // 排障场景：「车辆页数据不刷新」——数据绑定用 DEBUG，不落盘。
        Logx.d(
            LogTags.SCENE,
            "车辆页数据绑定",
            "source" to info.source,
            "rangeKm" to info.rangeKm,
        )
        // 车模姿态：和首页车况卡同一份数据（调试注入优先，模拟器截图用）。
        val pose = Car3dDebugPose.pose ?: CarPose.of(
            info.doorsClosed,
            info.windowsOpen,
            info.hoodClosed,
            info.trunkClosed,
        )
        val poseChanged = pose != lastCarPose
        if (poseChanged) {
            lastCarPose = pose
            carView.setPose(pose)
        }
        // 「386」与「km」拆成两个 TextView 后，这里只写数字，单位常驻。
        setValue(rangeValue, if (info.rangeKm >= 0) "${info.rangeKm}" else "--")
        // 油量：百分比和升数两个口径都有，卡片这一行用百分比（和画板 78% 同一档信息量）。
        setTextIfChanged(fuelValue, info.fuelPercent?.let { "$it%" } ?: "--")
        setTextIfChanged(
            odometerValue,
            if (info.odometerKm > 0) {
                String.format(java.util.Locale.US, "%,d km", info.odometerKm)
            } else {
                "--"
            },
        )
        // 平均油耗：数据层给的是 L/100km 的 Double，保留一位小数。
        // `0.0` 是车机「没读到」时的占位值（物理上不可能 0 L/100km），按 `--` 处理，不假装有数。
        setTextIfChanged(
            avgFuelValue,
            info.avgFuelConsumption?.takeIf { it > 0.0 }?.let {
                String.format(java.util.Locale.US, "%.1f L/100km", it)
            } ?: "--",
        )
        setTextIfChanged(
            maintenanceValue,
            info.maintenanceKm?.takeIf { it > 0 }?.let {
                String.format(java.util.Locale.US, "%,d km", it)
            } ?: "--",
        )
        setTextIfChanged(speedValue, "${info.speedKmh.coerceAtLeast(0)} km/h")
        setTextIfChanged(
            batteryVoltageValue,
            info.batteryVoltage?.takeIf { it > 0f }?.let {
                String.format(java.util.Locale.US, "%.1f V", it)
            } ?: "--",
        )
        setTextIfChanged(pm25Value, info.pm25Indoor?.takeIf { it >= 0 }?.let { "$it µg/m³" } ?: "--")
        setTextIfChanged(aqiValue, info.aqiIndoor?.takeIf { it >= 0 }?.let { "$it" } ?: "--")

        // 右下「传感器监测」胶囊刷新
        bindSensorPill(
            sensorIgnitionPill,
            AdaptapiSensors.ignitionLabel(info.ignitionState),
            when (info.ignitionState) {
                AdaptapiSensors.IGNITION_DRIVING, AdaptapiSensors.IGNITION_ACTIVE -> pal.accent
                AdaptapiSensors.IGNITION_CONVENIENCE -> pal.ok
                AdaptapiSensors.IGNITION_INACTIVE, AdaptapiSensors.IGNITION_ABANDON -> pal.textDim
                else -> pal.textFaint
            },
        )
        bindSensorPill(
            sensorBatteryPill,
            when (info.batteryEnergyLevel) {
                BatteryVoltageProbe.ENERGY_LEVEL_HIGH -> "正常"
                BatteryVoltageProbe.ENERGY_LEVEL_LOW -> "电量偏低"
                else -> "--"
            },
            when (info.batteryEnergyLevel) {
                BatteryVoltageProbe.ENERGY_LEVEL_HIGH -> pal.ok
                BatteryVoltageProbe.ENERGY_LEVEL_LOW -> pal.warn
                else -> pal.textFaint
            },
        )
        bindSensorPill(
            sensorOilPill,
            AdaptapiSensors.oilLevelLabel(info.oilLevelEvent),
            when (info.oilLevelEvent) {
                AdaptapiSensors.OIL_LEVEL_OK -> pal.ok
                AdaptapiSensors.OIL_LEVEL_LOW1, AdaptapiSensors.OIL_LEVEL_HIGH -> pal.warn
                AdaptapiSensors.OIL_LEVEL_LOW2 -> pal.danger
                else -> pal.textFaint
            },
        )
        bindSensorPill(
            sensorBrakeFluidPill,
            AdaptapiSensors.brakeFluidLabel(info.brakeFluidEvent),
            when (info.brakeFluidEvent) {
                AdaptapiSensors.BRAKE_FLUID_OK -> pal.ok
                AdaptapiSensors.BRAKE_FLUID_LOW -> pal.danger
                else -> pal.textFaint
            },
        )
        bindSensorPill(
            sensorCoolantPill,
            AdaptapiSensors.coolantWarnLabel(info.coolantWarnEvent),
            when (info.coolantWarnEvent) {
                AdaptapiSensors.WARN_NORMAL -> pal.ok
                AdaptapiSensors.WARN_ACTIVE -> pal.danger
                else -> pal.textFaint
            },
        )
        bindSensorPill(
            sensorFuelWarnPill,
            AdaptapiSensors.fuelLowWarnLabel(info.fuelLowWarnEvent),
            when (info.fuelLowWarnEvent) {
                AdaptapiSensors.WARN_NORMAL -> pal.ok
                AdaptapiSensors.WARN_ACTIVE -> pal.warn
                else -> pal.textFaint
            },
        )
        for (i in tireValues.indices) {
            val t = info.tires.getOrNull(i) ?: Float.NaN
            // 第三十七轮：单位是 kPa（真车 TPMS 报 240/227/237/235），按整数显示。
            val v = tireValues[i] ?: continue
            setValue(v, if (t.isNaN()) "--" else String.format(java.util.Locale.US, "%d", Math.round(t)))
        }
        setTextIfChanged(sourceLabel, "数据来源：${info.source}")
        // 车门：优先用四门明细；**整组明细都没有**时才退回整体状态（UI 只有二态）。
        //
        // 第四十五轮起明细是**逐门独立**的，所以「这一扇读不到」与
        // 「整组读不到」要分开：前者显示「--」（不猜），后者才退回二态。
        val doors = info.doorsClosed
        val allClosed = info.doorsAllClosed
        for (i in doorLabels.indices) {
            val closed = if (doors != null) doors.getOrNull(i) else allClosed
            val v = doorLabels[i] ?: continue
            val labelText = when (closed) {
                true -> "已关闭"
                false -> "已打开"
                null -> "--"
            }
            setTextIfChanged(v, labelText)
            val target = when (closed) {
                true -> pal.ok
                false -> pal.warn
                null -> pal.textFaint
            }
            // 胶囊底跟着状态换色：只在颜色真变时才重建 background，避免每秒 5 次 requestLayout
            if (doorBgColors[i] != target) {
                doorBgColors[i] = target
                v.background = Ui.round(tint(target, 0x1E), Shape.PILL)
            }
            fadeDoorColor(i, v, target)
        }
        // 后备箱：与四门同一套三态语义（true 已关闭 / false 已打开 / null 读不到）。
        // `trunkClosed == null` 时不猜 —— 显示「--」而不是默认「已关闭」。
        trunkLabel?.let { v ->
            val closed = info.trunkClosed
            val labelText = when (closed) {
                true -> "已关闭"
                false -> "已打开"
                null -> "--"
            }
            setTextIfChanged(v, labelText)
            val target = when (closed) {
                true -> pal.ok
                false -> pal.warn
                null -> pal.textFaint
            }
            if (trunkBgColor != target) {
                trunkBgColor = target
                v.background = Ui.round(tint(target, 0x1E), Shape.PILL)
            }
            if (trunkColor != target) {
                val from = trunkColor
                Motion.animateProgress(v) { t ->
                    val c = MotionSpec.lerpColor(from, target, t)
                    v.setTextColor(c)
                    trunkColor = c
                }
                Motion.nudge(v)
            }
        }
        if (poseChanged) carView.invalidate()
    }

    /**
     * 车门状态的颜色**渐变**过去，而不是硬切。
     *
     * 「已关闭 → 已打开」是绿变橙 —— 硬切的话余光只会觉得「颜色闪了一下」，
     * 渐变才能让人看清「这扇门的状态变了」。
     */
    private fun fadeDoorColor(index: Int, label: TextView, target: Int) {
        val from = doorColors.getOrElse(index) { target }
        if (from == target) return
        Motion.animateProgress(label) { t ->
            val c = MotionSpec.lerpColor(from, target, t)
            label.setTextColor(c)
            if (index in doorColors.indices) doorColors[index] = c
        }
        // 颜色之外再补一下位移：车门状态是**安全**信息，值得更明显一点。
        Motion.nudge(label)
    }

    /**
     * 数值变化时「顶一下」（[Motion.nudge]）。
     *
     * 续航 / 胎压是驾驶员会盯着看的数字 —— 变了要能立刻被注意到。
     * 只在文本**真的变了**时才顶：车控是定时刷新的，
     * 不比较的话每轮都在顶，数字会一直抖。
     */
    private fun bindSensorPill(pill: TextView, label: String, color: Int) {
        if (pill.text?.toString() == label && pill.currentTextColor == color) return
        pill.text = label
        pill.setTextColor(color)
        pill.background = Ui.round(tint(color, 0x1E), Shape.PILL)
    }

    private fun setValue(view: TextView, text: String) {
        if (view.text?.toString() == text) return
        view.text = text
        Motion.nudge(view)
    }

    /**
     * 俯视车模，纯绘制。
     *
     * 按画板 05/n04 重绘：圆角车身的浅色车漆 + 深色前挡/后窗 + 浅灰车顶玻璃 +
     * 青色尾灯 + 四只外凸的轮胎 + 一层很淡的落影。车头朝上（和胎压格的
     * 左前/右前 对应关系一致），不自转、不做动画。
     */
    private class TopCarView(context: Context, private val pal: Palette) : View(context) {
        /**
         * 写实车模（第244轮）：和首页车况卡同一套 `cardtop` 烘焙图。
         *
         * 用法和 `ScenePage` 一样：`setPose` 喂状态、`paint` 贴图；
         * 返回 false（3D 关 / 资产缺失）时才回落下面的自绘俯视图。
         */
        private val car3d = Car3dPainter.Scene(this)
        private var pose: CarPose? = null

        var carModel: String = CarModels.MODEL_03
            set(value) {
                field = value
                invalidate()
            }

        fun setPose(newPose: CarPose?) {
            if (pose == newPose) return
            pose = newPose
            invalidate()
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = CAR_PAINT_EDGE
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // 先试写实车模（cardtop 烘焙图）：成功就直接返回，不再画自绘车身。
            val dir = CarModels.assetDir(carModel)
            val p = pose
            if (p != null && dir != null) {
                car3d.setPose(context, dir, p, rich = true, view = Car3dAssets.VIEW_CARDTOP)
                // 裁掉资产右侧那条烘焙柔影（见 CAR3D_CLIP_RIGHT 注释）：车身右缘以外不画。
                val save = canvas.save()
                canvas.clipRect(0f, 0f, w * CAR3D_CLIP_RIGHT, h)
                val painted = car3d.paint(
                    canvas,
                    context,
                    Car3dAssets.VIEW_CARDTOP,
                    RectF(w * 0.02f, h * 0.02f, w * 0.98f, h * 0.98f),
                    fit = true,
                )
                canvas.restoreToCount(save)
                if (painted) return
            }
            val cx = w / 2f
            val cy = h / 2f

            // 车身占视图的 50%×85%：视图 300×400 → 车身 150×340，和画板一致。
            val bodyW = w * 0.50f
            val bodyH = h * 0.85f
            val top = cy - bodyH / 2f
            val left = cx - bodyW / 2f
            val right = cx + bodyW / 2f
            val body = RectF(left, top, right, top + bodyH)

            // 落影：一层椭圆就够（画板是很淡的柔影，不需要真模糊，省一次离屏）。
            paint.color = CAR_SHADOW
            canvas.drawOval(
                RectF(cx - bodyW * 0.72f, top + bodyH - 20f, cx + bodyW * 0.72f, top + bodyH + 30f),
                paint,
            )

            // 轮胎在车身之前画，让车身盖住内侧，只露出外沿 —— 画板就是这个层次。
            paint.color = CAR_TIRE
            val wheelW = 30f
            val wheelH = bodyH * 0.176f
            for (wheelTop in floatArrayOf(top + bodyH * 0.03f, top + bodyH * 0.53f)) {
                canvas.drawRoundRect(
                    RectF(left - wheelW * 0.55f, wheelTop, left + wheelW * 0.45f, wheelTop + wheelH),
                    9f, 9f, paint,
                )
                canvas.drawRoundRect(
                    RectF(right - wheelW * 0.45f, wheelTop, right + wheelW * 0.55f, wheelTop + wheelH),
                    9f, 9f, paint,
                )
            }

            // 车漆：上浅下深的轻渐变，避免整块死白。
            bodyPaint.shader = LinearGradient(
                left, top, right, top + bodyH,
                CAR_PAINT_TOP, CAR_PAINT_BOTTOM, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(body, bodyW * 0.34f, bodyW * 0.34f, bodyPaint)
            bodyPaint.shader = null
            canvas.drawRoundRect(body, bodyW * 0.34f, bodyW * 0.34f, edgePaint)

            // 前挡（深色，靠车头）。
            paint.color = CAR_GLASS
            canvas.drawRoundRect(
                RectF(cx - bodyW * 0.30f, top + bodyH * 0.10f, cx + bodyW * 0.30f, top + bodyH * 0.28f),
                20f, 20f, paint,
            )
            // 车顶玻璃（比车漆略深一档的浅灰，画板里是浅色不是深色）。
            paint.color = CAR_ROOF
            canvas.drawRoundRect(
                RectF(cx - bodyW * 0.33f, top + bodyH * 0.32f, cx + bodyW * 0.33f, top + bodyH * 0.47f),
                8f, 8f, paint,
            )
            // 后窗（深色，比前挡更宽更圆）。
            paint.color = CAR_GLASS
            canvas.drawRoundRect(
                RectF(cx - bodyW * 0.31f, top + bodyH * 0.57f, cx + bodyW * 0.31f, top + bodyH * 0.80f),
                22f, 22f, paint,
            )
            // 尾灯：画板唯一的高饱和色，用主题 accent（日青夜亮青）。
            paint.color = pal.accent
            canvas.drawRoundRect(
                RectF(cx - bodyW * 0.16f, top + bodyH * 0.835f, cx + bodyW * 0.16f, top + bodyH * 0.872f),
                5f, 5f, paint,
            )
        }
    }
    /**
     * 右列入口的小图标（24×24 线稿）。
     *
     * 画板里是青色线性图标；工程里没有这三个入口的图标资源，
     * 又不值得为一个 24px 的图标引入依赖/资源，所以用 Canvas 画几何图形：
     * 0 = 雪花（空调控制）、1 = 同心圆（360 环影）、2 = 三条滑杆（原厂设置）。
     * 线色用 [Skin.ACCENT]，日夜自动跟随主题。
     */
    private class QuickIcon(context: Context, private val type: Int) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            strokeCap = Paint.Cap.ROUND
            color = Skin.ACCENT
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.30f
            when (type) {
                0 -> {
                    // 雪花：三根过圆心的轴，每根轴 2/3 处再挑两根短枝。
                    for (k in 0 until 3) {
                        val a = Math.toRadians(30.0 + k * 60.0)
                        val dx = (Math.cos(a) * r).toFloat()
                        val dy = (Math.sin(a) * r).toFloat()
                        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke)
                        for (end in intArrayOf(-1, 1)) {
                            val bx = cx + dx * 0.66f * end
                            val by = cy + dy * 0.66f * end
                            val branch = Math.toRadians(35.0)
                            val ux = (Math.cos(a + branch) * r * 0.32f).toFloat()
                            val uy = (Math.sin(a + branch) * r * 0.32f).toFloat()
                            canvas.drawLine(bx, by, bx + ux * end, by + uy * end, stroke)
                            val vx = (Math.cos(a - branch) * r * 0.32f).toFloat()
                            val vy = (Math.sin(a - branch) * r * 0.32f).toFloat()
                            canvas.drawLine(bx, by, bx + vx * end, by + vy * end, stroke)
                        }
                    }
                }
                1 -> {
                    canvas.drawCircle(cx, cy, r, stroke)
                    canvas.drawCircle(cx, cy, r * 0.34f, stroke)
                }
                else -> {
                    for (k in 0 until 3) {
                        val y = cy + (k - 1) * r * 0.78f
                        canvas.drawLine(cx - r, y, cx + r, y, stroke)
                        val knobX = when (k) {
                            0 -> cx - r * 0.42f
                            1 -> cx + r * 0.30f
                            else -> cx - r * 0.10f
                        }
                        canvas.drawCircle(knobX, y, r * 0.28f, stroke)
                    }
                }
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if ((abs(dx) > swipeSlop && abs(dx) > abs(dy)) || (dy > swipeSlop && dy > abs(dx))) {
                    swiping = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if ((abs(dx) > swipeSlop && abs(dx) > abs(dy)) || (dy > swipeSlop && dy > abs(dx))) {
                    swiping = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val shouldClose = swiping && (abs(dx) >= dismissDistance || dy >= dismissDistance)
                swiping = false
                if (shouldClose) {
                    onClose?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return super.onTouchEvent(ev)
    }

}
