package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.cardesk.launcher.core.CardStyle
import com.cardesk.launcher.core.GlassDrawable
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.widget.OverlayBackdrop
import com.cardesk.launcher.vehicle.HvacCodec
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.VehicleController
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 主驾温环的**语义色** —— 设计稿 `04-climate-day.png` / `n03-climate-night.png`
 * 里主驾环轨道就是这一支蓝（采样 #479FFE，规格 #3E9BFF）。
 *
 * 为什么不像其它颜色那样走 `Skin`：这支蓝/橙是「主驾 / 副驾」的**身份色**，
 * 不随日夜主题变（夜间画板里的环仍是蓝的），所以它是语义常量而不是主题令牌。
 */
private val RING_DRIVER = 0xFF3E9BFF.toInt()

/** 副驾温环的语义色 —— 同上，取自设计稿副驾环（规格 #F5A623）。 */
private val RING_PASSENGER = 0xFFF5A623.toInt()

/**
 * 气候整页：第 243 轮按设计板 `04-climate-day` / `n03-climate-night` 重做为
 * **一块漂浮在桌面上的大玻璃**（L2 浮层，圆角 28）。
 *
 * 版式全部按画板的**绝对设计像素**摆放（面板 1642×867 位于 `left 143 / top 73`），
 * 每个子 View 都写 `x, y, w, h` 四个数 —— 因为画板里各元素的间距并不等距
 * （7 个模式胶囊的宽度随文案变、风量条是阶梯而不是等高），用容器 + 权重排
 * 一定会和画板差几个像素。子 View 的定位见 [lp]。
 *
 * `
 * 顶栏   空调(40 粗) + 运行中(18 accent) + 电源胶囊(70×54) ……… 关闭胶囊(108×66)
 * 左列   主驾温环 300×300（环内 主驾/24.5°/－＋）+ 加热·通风胶囊
 * 中列   风量标题 22 + 范围 14 / 阶梯风量条 + MAX / 吹风模式 + 7 个组合胶囊
 *        4 个功能胶囊 / 2 个除霜胶囊 / 座舱出风示意 740×172
 * 右列   副驾温环（同左，橙）
 * `
 *
 * **功能不做减法**：7 个组合胶囊与原 3 个吹风开关（face/leg/win 三位掩码）
 * 一一对应；座椅加热/通风、MAX、座舱示意、温度同步全部保留。
 * 本轮只改「怎么画、摆在哪」，数据与回调一条没动。
 */
class ClimatePage(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null

    private lateinit var dialDriver: ClimateDial
    private lateinit var dialPassenger: ClimateDial
    private lateinit var fanLabel: TextView
    private lateinit var fanBars: ClimateFanBars
    private lateinit var cabin: ClimateCabin
    private lateinit var powerStateText: TextView

    private val chips = LinkedHashMap<String, ChipButton>()
    private val seatChips = LinkedHashMap<String, ChipButton>()

    /** 面板（动画用）。 */
    private var panelView: View? = null

    /** 遮罩（动画用）。 */
    private var scrimView: View? = null

    /** 打开动画：面板上滑 + 遮罩淡入。 */
    fun playOpen() {
        OverlayAnim.open(panelView, scrimView)
    }

    /** 关闭动画：面板下滑 + 淡出。 */
    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panelView, scrimView, onEnd)
    }

    init {
        // 第 243 轮：整页不再是「把桌面盖死的不透明背景板」，而是**浮**在桌面上的一块玻璃。
        //
        // ⚠️ 第 244 轮修正：`seeThrough = true` 在真实壁纸下会**透出首页的四张底卡**
        // （面板 78% 白 + 底卡本身也是白玻璃，两层白叠在一起仍然看得见轮廓），
        // 与画板 `04-climate-day.png` 完全不符 —— 画板里面板覆盖的区域内是**看不到桌面卡片**的，
        // 只有面板外的边缘能看到底色渐变与 Dock。
        // 所以遮罩恢复成不透明的「.bgwash + .scrim」：面板本身仍是玻璃（对 bgwash 取样），
        // 桌面内容被挡住，观感与画板一致。
        val scrim = OverlayBackdrop(context).apply {
            palette = this@ClimatePage.pal
            withScrim = true
            seeThrough = false
            // 画板面板外 ≈ (226,237,242)，只压暗约 7% —— 用满 26% 会压到 (176,182,184)。
            scrimScale = 0.28f
            isClickable = true
            setOnClickListener { onClose?.invoke() }
        }
        scrimView = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val card = FrameLayout(context).apply {
            // 整页 = 一块大玻璃（L2 浮层）：圆角按浮层规格 28，面用 surfaceStrong ——
            // 面板是「压在最上面的一层玻璃」，比卡片（surface）更实一点。
            background = GlassDrawable(SHEET_RADIUS, Skin.surfaceStrong, Skin.GLASS_STROKE, true, true)
            // 吞掉面板内的空白点击，避免落到遮罩上误关闭（只有面板外点击才关闭）
            isClickable = true
            setOnClickListener { }
        }
        panelView = card
        addView(
            card,
            LayoutParams(PANEL_W, PANEL_H, Gravity.TOP or Gravity.START).apply {
                leftMargin = PANEL_LEFT
                topMargin = PANEL_TOP
            },
        )

        buildTopBar(card)
        buildDial(card, driver = true)
        buildDial(card, driver = false)
        buildFan(card)
        buildModes(card)
        buildFuncs(card)
        buildDefrost(card)
        buildCabin(card)
        refresh()
    }

    // ------------------------------------------------------------- 定位

    /**
     * 把子 View 放到**画板的绝对坐标**上（x/y 是设计稿里的屏幕坐标）。
     *
     * 面板左上角是 [PANEL_LEFT] / [PANEL_TOP]，所以减掉它才是面板内的边距。
     * 全部写绝对值而不是用容器排版：画板里模式胶囊的宽度是**跟着文案变**的
     * （两个字 76、三个字形 86），风量条是阶梯而不是等高 —— 用权重排会和画板差几像素。
     */
    private fun lp(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x - PANEL_LEFT
            topMargin = y - PANEL_TOP
        }

    /** 在绝对坐标上放一个文本（[gravity] 默认居中，因为画板里这些字都是盒子居中的）。 */
    private fun label(
        card: FrameLayout,
        text: String,
        px: Float,
        color: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        gravity: Int = Gravity.CENTER,
        bold: Boolean = false,
    ): TextView {
        val v = Ui.text(context, text, px, color, bold)
        v.gravity = gravity
        card.addView(v, lp(x, y, w, h))
        return v
    }

    /**
     * 定宽定高的胶囊按钮。
     *
     * 尺寸由调用方按画板给（不给 wrap_content），因为画板里同一行的胶囊
     * 是按文案手调的宽度，`wrap_content` 出来的宽度会差 2~6px。
     * 圆角走 [Shape.PILL]：这一版的胶囊高 47~66，半高做圆角正好是画板里的效果。
     */
    private fun pillChip(text: String, px: Float, active: Boolean = false): ChipButton =
        ChipButton(context, pal, px).apply {
            bind(text, active)
            setPadding(0, 0, 0, 0)
            (background as? GradientDrawable)?.cornerRadius = Shape.PILL
        }

    // ------------------------------------------------------------- 结构

    /** 顶栏：标题 + 运行状态 + 电源胶囊 / 右侧关闭胶囊。 */
    private fun buildTopBar(card: FrameLayout) {
        // 标题 40px 粗体：画板里「空调」的墨迹高 37px，40 号字的重心正好落在 y129。
        label(card, "空调", 40f, Skin.TEXT, 182, 100, 220, 60, Gravity.CENTER_VERTICAL, bold = true)

        // 运行状态：18px accent，紧跟在标题右侧（画板里它和标题基线对齐、比标题矮一档）。
        powerStateText = label(card, "已关机", 18f, Skin.ACCENT, 282, 113, 160, 48, Gravity.CENTER_VERTICAL)

        // 电源胶囊：70×54，激活态 = accentSoft 底 + accent 描边 + accent 字（= 画板里的淡青胶囊）。
        val power = pillChip("电源", 18f, VehicleController.state.power).apply {
            setOnClickListener {
                VehicleController.togglePower()
                refresh()
            }
        }
        card.addView(power, lp(377, 112, 70, 54))
        chips["power"] = power

        // 关闭：画板里是**白色胶囊**（day #FFFFFF / night 面板同色），字 20px 用正文色而不是弱化色 ——
        // 它是最主要的退出口，弱化会让人犹豫。
        val closeBtn = Ui.text(context, "关闭", 20f, Skin.TEXT).apply {
            gravity = Gravity.CENTER
            // 卡片面用 surface：比面板（surfaceStrong）透一档，压在玻璃上才像一块浮起来的小玻璃。
            background = Ui.round(Skin.surface, Shape.PILL, 1f, Skin.GLASS_STROKE)
            isClickable = true
            pressFeedback()
            setOnClickListener { onClose?.invoke() }
        }
        card.addView(closeBtn, lp(1630, 102, 108, 66))
    }

    /** 温环 + 环下「加热 / 通风」胶囊（主驾 / 副驾对称，只是颜色和位置不同）。 */
    private fun buildDial(card: FrameLayout, driver: Boolean) {
        val zone = if (driver) HvacIds.ZONE_DRIVER else HvacIds.ZONE_PASSENGER
        val dial = ClimateDial(context).apply {
            palette = this@ClimatePage.pal
            ringColor = if (driver) RING_DRIVER else RING_PASSENGER
            caption = if (driver) "主驾" else "副驾"
            onDelta = { delta -> VehicleController.stepTemp(zone, delta) }
            // 拖动结束回抛**绝对温度**，这里换算成增量（0.2℃ 死区，避免抬手时抖一下又发一条）
            onTemp = { target ->
                val cur = if (driver) VehicleController.state.tempDriver else VehicleController.state.tempPassenger
                val delta = target - cur
                if (abs(delta) >= 0.2f) VehicleController.stepTemp(zone, delta)
            }
        }
        card.addView(dial, lp(if (driver) DIAL_DRIVER_X else DIAL_PASSENGER_X, DIAL_TOP, DIAL_SIZE, DIAL_SIZE))
        if (driver) dialDriver = dial else dialPassenger = dial

        // 座椅加热 / 通风：贴在温环下方（左右各一对）。
        // 第244轮：用户反馈「加热和通风按钮有点小，放大一点」—— 70×47 → 96×56、字号 14 → 16，
        // 并保持「两个胶囊整体居中于温环」的摆位（X 随尺寸同步重算）。
        val heatKey = if (driver) "seatHL" else "seatHR"
        val ventKey = if (driver) "seatVL" else "seatVR"
        val rowX = if (driver) SEAT_DRIVER_X else SEAT_PASSENGER_X

        val heat = pillChip("加热", 16f).apply {
            showLevel = true
            setOnClickListener {
                VehicleController.cycleSeat(zone, true)
                refresh()
            }
        }
        val vent = pillChip("通风", 16f).apply {
            showLevel = true
            setOnClickListener {
                VehicleController.cycleSeat(zone, false)
                refresh()
            }
        }
        seatChips[heatKey] = heat
        seatChips[ventKey] = vent
        card.addView(heat, lp(rowX, SEAT_TOP, SEAT_W, SEAT_H))
        card.addView(vent, lp(rowX + SEAT_W + SEAT_GAP, SEAT_TOP, SEAT_W, SEAT_H))
    }

    /** 风量：标题 + 范围 + 阶梯条 + MAX。 */
    private fun buildFan(card: FrameLayout) {
        // 标题 22px：画板墨迹高 20、左缘 591，和下面的阶梯条左端对齐。
        fanLabel = label(card, "风量 0 档", 22f, Skin.TEXT, 591, 170, 240, 40, Gravity.CENTER_VERTICAL)

        // 范围提示：「1－7 档」14px 弱字，右端贴到 1330（= 风量条右端 1129 之外再多一截，
        // 与画板里它和 MAX 一起构成右侧的「量程说明」有关）。
        label(
            card, "1－7 档", 14f, Skin.TEXT_DIM,
            1180, 175, 150, 38,
            Gravity.CENTER_VERTICAL or Gravity.END,
        )

        fanBars = ClimateFanBars(context).apply {
            palette = this@ClimatePage.pal
            onLevel = { level ->
                VehicleController.stepFan(level - VehicleController.state.fanLevel)
                refresh()
            }
        }
        card.addView(fanBars, lp(FAN_X, FAN_TOP, FAN_W, FAN_H))

        val maxFan = pillChip("MAX", 14f).apply {
            setOnClickListener {
                VehicleController.toggleMaxFan()
                refresh()
            }
        }
        card.addView(maxFan, lp(1152, 296, 70, 58))
        chips["maxFan"] = maxFan
    }

    /** 「吹风模式」标题 + 7 个组合胶囊（一行，宽度随文案）。 */
    private fun buildModes(card: FrameLayout) {
        label(card, "吹风模式", 18f, Skin.TEXT_SOFT, 860, 402, 200, 36)

        val modes = listOf(
            "吹脸" to 1,
            "吹脚" to 2,
            "脸+脚" to 3,
            "吹窗" to 4,
            "脸+窗" to 5,
            "脚+窗" to 6,
            "全吹" to 7,
        )
        modes.forEachIndexed { i, (text, mask) ->
            val chip = pillChip(text, 18f).apply {
                setOnClickListener {
                    Logx.event(LogTags.HVAC, "空调浮层：吹风模式", "mask" to mask)
                    // 组合胶囊是互斥的：直接落成该掩码（0 档除外）。
                    if (mask != 0) VehicleController.setBlowingMask(mask)
                    refresh()
                }
            }
            chips["mode$mask"] = chip
            // 画板里这 7 个胶囊是**一行**排开的：起点 643/731/819/…、间距 12，
            // 两个字宽 76、三个字形（脸+脚）宽 86 —— 宽度差是为了让文字两侧的留白一致。
            card.addView(chip, lp(MODE_X[i], MODE_ROW_TOP, MODE_W[i], MODE_H))
        }
    }

    /** 4 个功能胶囊：A/C · AUTO · 外循环 · 温度同步。 */
    private fun buildFuncs(card: FrameLayout) {
        val funcs = listOf(
            "ac" to "A/C",
            "auto" to "AUTO",
            "circ" to "外循环",
            "sync" to "温度同步",
        )
        funcs.forEachIndexed { i, (key, text) ->
            val chip = pillChip(text, 18f).apply {
                setOnClickListener {
                    when (key) {
                        "ac" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleAc() }
                        "auto" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleAuto() }
                        "sync" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleSync() }
                        "circ" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleCirculation() }
                    }
                    refresh()
                }
            }
            chips[key] = chip
            card.addView(chip, lp(FUNC_X[i], FUNC_TOP, FUNC_W[i], FUNC_H))
        }
    }

    /** 2 个除霜胶囊：画板里这一行比上面窄（高 49、字 14），是「次要动作」。 */
    private fun buildDefrost(card: FrameLayout) {
        val defrosts = listOf("defF" to "前除霜", "defR" to "后除霜")
        defrosts.forEachIndexed { i, (key, text) ->
            val chip = pillChip(text, 14f).apply {
                setOnClickListener {
                    when (key) {
                        "defF" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleDefrostFront() }
                        "defR" -> { Logx.event(LogTags.HVAC, "空调浮层：开关", "key" to key); VehicleController.toggleDefrostRear() }
                    }
                    refresh()
                }
            }
            chips[key] = chip
            card.addView(chip, lp(DEFROST_X[i], DEFROST_TOP, DEFROST_W, DEFROST_H))
        }
    }

    /** 座舱出风示意（画板里的内嵌槽，可点按切换吹脸 / 吹脚 / 吹窗）。 */
    private fun buildCabin(card: FrameLayout) {
        cabin = ClimateCabin(context).apply {
            palette = this@ClimatePage.pal
            onZoneTap = { bit ->
                val current = HvacCodec.blowingMask(VehicleController.state.blowing)
                val next = current xor bit
                if (next != 0) {
                    Logx.event(LogTags.HVAC, "空调浮层：座舱出风方向", "bit" to bit, "from" to current, "to" to next)
                    VehicleController.setBlowingMask(next)
                    refresh()
                }
            }
        }
        card.addView(cabin, lp(CABIN_X, CABIN_TOP, CABIN_W, CABIN_H))
    }

    // ------------------------------------------------------------- 刷新

    fun refresh() {
        val s = VehicleController.state
        // 第 243 轮：温环弧与温度数值**始终满色**（关机也是），只有「同步态」轻微弱化 ——
        // 画板里两个环都是满色，关机与否不改变环的画法。
        val dim = s.tempSynced
        dialDriver.palette = pal
        dialPassenger.palette = pal
        dialDriver.temperature = s.tempDriver
        dialPassenger.temperature = s.tempPassenger
        dialDriver.dimmed = dim
        dialPassenger.dimmed = dim
        dialDriver.disabled = !s.power
        dialPassenger.disabled = !s.power
        dialDriver.caption = if (s.tempSynced) "主驾 · 同步" else "主驾"
        dialPassenger.caption = if (s.tempSynced) "副驾 · 同步" else "副驾"

        // —— 关机不显示风量；开机但读回 0 时显示 1 档
        val shownFan = when {
            !s.power -> 0
            s.fanLevel <= 0 -> 1
            else -> s.fanLevel
        }
        val fanText = "风量 $shownFan 档"
        if (fanLabel.text?.toString() != fanText) fanLabel.text = fanText
        fanBars.level = if (s.power) s.fanLevel else 0
        fanBars.auto = s.fanAuto
        val powerText = if (s.power) "运行中" else "已关机"
        if (powerStateText.text?.toString() != powerText) powerStateText.text = powerText

        chips["power"]?.let { applyChip(it, "电源", s.power) }
        chips["ac"]?.let { applyChip(it, "A/C", s.ac) }
        chips["auto"]?.let { applyChip(it, "AUTO", s.auto) }
        chips["sync"]?.let { applyChip(it, "温度同步", s.tempSynced) }
        // —— 只有内循环才高亮
        chips["circ"]?.let { applyChip(it, if (s.circulation == 0) "内循环" else "外循环", s.circulation == 0) }
        chips["defF"]?.let { bindChip(it, "前除霜", s.defrostFront) }
        chips["defR"]?.let { bindChip(it, "后除霜", s.defrostRear) }

        // —— 吹风按 face=1 / leg=2 / window=4 三位掩码显示
        val blowing = HvacCodec.blowingMask(s.blowing)
        for (mask in 1..7) {
            chips["mode$mask"]?.let { applyChip(it, MODE_LABELS[mask] ?: "", blowing == mask) }
        }
        chips["maxFan"]?.let { applyChip(it, "MAX", s.fanLevel >= HvacIds.FAN_MAX) }

        cabin.palette = pal
        cabin.setFlow(blowing and 1 != 0, blowing and 2 != 0, blowing and 4 != 0)

        // 座椅 chip 只写「加热 / 通风」，**不带档位数字**（档位由激活态表达）。
        // 第248轮：座椅加热/通风是 0~3 档，胶囊只表达开关，看不出档位 ——
        // `bindChip` 之后把 level 一起写进去（[ChipButton.showLevel] 在右上角画三根竖条）。
        seatChips["seatHL"]?.let { bindChip(it, "加热", s.seatHeatDriver > 0); it.level = s.seatHeatDriver }
        seatChips["seatVL"]?.let { bindChip(it, "通风", s.seatVentDriver > 0); it.level = s.seatVentDriver }
        seatChips["seatHR"]?.let { bindChip(it, "加热", s.seatHeatPassenger > 0); it.level = s.seatHeatPassenger }
        seatChips["seatVR"]?.let { bindChip(it, "通风", s.seatVentPassenger > 0); it.level = s.seatVentPassenger }
    }

    /**
     * chip 重新绑定 + 保证圆角是胶囊。
     *
     * [ChipButton] 自带的圆角是 22，胶囊要的是 [Shape.PILL]。这里改**它自己的那个
     * drawable** 而不是另换一个 background —— 换了的话 [ChipButton.setActive]
     * 内部渐变改的就不是真正显示的那个 drawable，动画等于白做。
     */
    private fun bindChip(chip: ChipButton, text: String, active: Boolean) {
        (chip.background as? GradientDrawable)?.let { gd ->
            if (gd.cornerRadius != Shape.PILL) gd.cornerRadius = Shape.PILL
        }
        applyChip(chip, text, active)
    }

    /**
     * chip 状态刷新：**颜色渐变**过去 + 状态确认。
     *
     * ## 为什么用 [ChipButton.setActive] 而不是 `bind()`
     *
     * `bind()` 是「首次绑定」的语义 —— 它把颜色**硬切**到目标值；
     * `setActive()` 只在状态**真的变了**的时候把颜色渐变过去，
     * 并附带一次「鼓一下」，正好符合「状态切换要给二次确认」的动效口径。
     *
     * ## 文案变了为什么要用**旧状态**去 bind
     *
     * `bind()` 会顺手把颜色刷成它那个 `active` 参数对应的颜色。
     * 如果这里直接 `bind(text, active)`，刚要渐变过去的颜色会被**硬切**回终点，
     * 动画就废了；所以先用**旧状态**绑（颜色不变、只是文案变了），
     * 再交给 `setActive()` 去渐变。
     */
    private fun applyChip(chip: ChipButton?, text: String, active: Boolean) {
        if (chip == null) return
        if (chip.text?.toString() != text) chip.bind(text, chip.active)
        chip.setActive(active)
    }

    companion object {
        /** 浮层圆角（设计规格：浮层 28，卡片 24）。 */
        const val SHEET_RADIUS = 28f

        /** 空调面板宽（设计 px）。 */
        const val PANEL_W = 1642

        /** 空调面板高（设计 px）。 */
        const val PANEL_H = 867

        /** 空调面板左边距（设计 px）—— 居中（1920 − 1642 = 278，两侧各 139/143）。 */
        const val PANEL_LEFT = 143

        /** 空调面板上边距（设计 px）。 */
        const val PANEL_TOP = 73

        // —— 温环 ——

        /** 温环直径（含描边）：画板里环外接框 279×279。 */
        const val DIAL_SIZE = 300

        /** 主驾温环左上角。 */
        const val DIAL_DRIVER_X = 202

        /** 副驾温环左上角。 */
        const val DIAL_PASSENGER_X = 1420

        /** 温环顶部（两个环同高）。 */
        const val DIAL_TOP = 175

        // —— 座椅胶囊 ——

        /** 主驾加热胶囊左边缘。 */
        const val SEAT_DRIVER_X = 250

        /** 副驾加热胶囊左边缘。 */
        const val SEAT_PASSENGER_X = 1468

        /** 座椅胶囊顶部。 */
        const val SEAT_TOP = 502

        /** 座椅胶囊宽。 */
        const val SEAT_W = 96

        /** 座椅胶囊高。 */
        const val SEAT_H = 56

        /** 加热 / 通风之间的间距。 */
        const val SEAT_GAP = 12

        // —— 风量 ——

        /** 风量阶梯条的视图左上角 / 尺寸（7 段 × 62 宽，含下方数字行）。 */
        const val FAN_X = 696
        const val FAN_TOP = 216
        const val FAN_W = 434
        const val FAN_H = 140

        // —— 模式行 ——

        /** 模式行顶部 / 高。 */
        const val MODE_ROW_TOP = 447
        const val MODE_H = 60

        /** 7 个模式胶囊的起点（画板逐个对齐：间距 12，宽度随文案）。 */
        val MODE_X = intArrayOf(643, 731, 819, 917, 1005, 1103, 1201)

        /** 7 个模式胶囊的宽度：两个字 76、三个字形 86。 */
        val MODE_W = intArrayOf(76, 76, 86, 76, 86, 86, 76)

        // —— 功能行 ——

        /** 功能胶囊行顶部 / 高。 */
        const val FUNC_TOP = 561
        const val FUNC_H = 60

        /** 4 个功能胶囊的起点。 */
        val FUNC_X = intArrayOf(743, 837, 946, 1059)

        /** 4 个功能胶囊的宽度（A/C 82、AUTO 96、外循环 101、温度同步 118）。 */
        val FUNC_W = intArrayOf(82, 96, 101, 118)

        // —— 除霜行 ——

        /** 除霜行顶部 / 高（画板里这行比上面矮一档）。 */
        const val DEFROST_TOP = 636
        const val DEFROST_H = 49

        /** 两个除霜胶囊的起点。 */
        val DEFROST_X = intArrayOf(858, 968)

        /** 除霜胶囊宽。 */
        const val DEFROST_W = 93

        // —— 座舱示意 ——

        /** 座舱示意块左上角 / 尺寸（740×172，与模式与功能行同宽地居中）。 */
        const val CABIN_X = 590
        const val CABIN_TOP = 739
        const val CABIN_W = 740
        const val CABIN_H = 172

        /** 吹风模式按钮文案（掩码 → 文案）。 */
        val MODE_LABELS = mapOf(
            1 to "吹脸",
            2 to "吹脚",
            3 to "脸+脚",
            4 to "吹窗",
            5 to "脸+窗",
            6 to "脚+窗",
            7 to "全吹",
        )
    }
}

// =====================================================================
// 以下是本页专用的自绘控件。
//
// 为什么不用 `ui.widget` 里的 TempDialView / FanBarsView / CabinView：
// 那三个控件是**上一版设计板**的固定几何（环半径 114、风量 56×56 等大方块、
// 车体线稿），画板的几何完全不同。本轮只允许改本文件，所以在这里按画板重画，
// 但把它们的**对外语义（回调 / setFlow / disabled）原样保留**，
// 这样 refresh() 一行都不用改。
// =====================================================================

/**
 * 温环：主驾 / 副驾。
 *
 * 几何全部来自设计稿（`04-climate-day.png` 量得，左右环同构）：
 * - 视图 300×300，环心在正中；
 * - 轨道半径 130.5、描边 18（外径 139.5）；
 * - 环内一层白盘 r=104（把环内底色抬一档，字才压得住）；
 * - 环内自上而下：caption 14px（环心上方 63.5）、温度 56px 粗体（上方 14.5）、
 *   － ＋ 两个 44×44 的圆角块（下方 55.5，左右各 30）。
 *
 * 交互与原 [com.cardesk.launcher.ui.widget.TempDialView] **完全一致**：
 * 点 ± / 长按连发 / 在环内拖动吸附 0.5℃ / 抬手回抛绝对温度 / 关机后不响应。
 */
private class ClimateDial(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    /** 环的进度色（主驾蓝 / 副驾橙）。 */
    var ringColor: Int = RING_DRIVER
        set(value) {
            field = value
            invalidate()
        }

    var caption: String = "主驾"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 温度同步时的轻微弱化。 */
    var dimmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 关机后不再响应触摸（环仍画，只是按不动）。 */
    var disabled: Boolean = false

    /** ±0.5℃ 的增量回调（点按 / 长按连发）。 */
    var onDelta: ((Float) -> Unit)? = null

    /** 拖动结束时的**绝对**温度。 */
    var onTemp: ((Float) -> Unit)? = null

    /** 目标温度（外部写入）。 */
    var temperature: Float = 23.5f
        set(value) {
            val v = value.coerceIn(TEMP_MIN, TEMP_MAX)
            if (field == v) return
            field = v
            animateTo(v)
        }

    /** 实际画出来的温度（动画中间值）。 */
    private var shown = 23.5f

    private var animToken: Any? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pressedKey: Int = 0
    private var dragging = false
    private var dragTemp = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = TEMP_TEXT
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = CAPTION_TEXT
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    /** 长按连发：与原 TempDialView 一样 150ms 一步，不用系统的 repeat 计数。 */
    private val repeatTask = object : Runnable {
        override fun run() {
            if (pressedKey == 0) return
            val delta = if (pressedKey == 1) -TEMP_STEP else TEMP_STEP
            onDelta?.invoke(delta)
            handler.postDelayed(this, REPEAT_INTERVAL)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun animateTo(target: Float) {
        val from = shown
        val token = Any()
        animToken = token
        Motion.animateValue(from, target, MotionSpec.VALUE_FAST_DURATION) {
            if (animToken !== token) return@animateValue
            shown = it
            invalidate()
        }
    }

    // ------------------------------------------------------------- 画

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        // 环内白盘：比面板亮一档，温度数字压在上面才不显脏。
        discPaint.color = palette.surface
        canvas.drawCircle(cx, cy, DISC_R, discPaint)

        // 轨道：整圈拉一条极淡的墨色（= 画板里的 #E6E9EA / #2A3038，
        // 用 9% 的正文色算出来正好，随日夜自动走，不用写死灰）。
        ringPaint.color = CardStyle.scaleAlpha(palette.text, 0.09f)
        canvas.drawCircle(cx, cy, RING_R, ringPaint)

        // 进度弧：12 点起顺时针。满量程 32℃ 对应满圈 —— 画板里 24.5/23.5 的弧口都在
        // 9 点过一点的 266° 左右，用 temp/32 算出来是 276°/264°，和画板几乎重合，
        // 同时它还是个**能读懂**的量（不是画板那种为好看而定死的装饰弧）。
        val frac = (shown / TEMP_MAX).coerceIn(0f, 1f)
        if (frac > 0.002f) {
            ringPaint.color = ringColor
            arcRect.set(cx - RING_R, cy - RING_R, cx + RING_R, cy + RING_R)
            canvas.drawArc(arcRect, -90f, 360f * frac, false, ringPaint)
        }

        // caption（「主驾」，14px 弱字）
        captionPaint.color = if (dimmed) palette.textFaint else palette.textDim
        canvas.drawText(caption, cx, cy + CAPTION_DY + CAPTION_TEXT * 0.35f, captionPaint)

        // 温度（56px 粗体，数字宽度按小数点数过：24.5° 宽 125）
        tempPaint.color = if (dimmed) CardStyle.scaleAlpha(palette.text, 0.6f) else palette.text
        canvas.drawText(formatTemp(shown), cx, cy + TEMP_DY + TEMP_TEXT * 0.35f, tempPaint)

        // － ＋：画板里这两个是 44×44 的淡色圆角块（夜间看得见，日间融进白盘）。
        drawStepButton(canvas, cx - BTN_DX, cy + BTN_DY, minus = true)
        drawStepButton(canvas, cx + BTN_DX, cy + BTN_DY, minus = false)
    }

    private fun drawStepButton(canvas: Canvas, x: Float, y: Float, minus: Boolean) {
        val half = BTN_SIZE / 2f
        btnPaint.color = palette.surfaceSoft
        canvas.drawRoundRect(x - half, y - half, x + half, y + half, BTN_RADIUS, BTN_RADIUS, btnPaint)

        glyphPaint.color = if (disabled) palette.textFaint else palette.textDim
        canvas.drawLine(x - 7f, y, x + 7f, y, glyphPaint)
        if (!minus) canvas.drawLine(x, y - 7f, x, y + 7f, glyphPaint)
    }

    private fun formatTemp(t: Float): String {
        // 0.5 步进：整数时也保留一位小数（画板写 24.5，23.0 时应显示 23.0 而不是 23）
        val v = (t * 2f).roundToInt() / 2f
        return String.format(java.util.Locale.US, "%.1f°", v)
    }

    // ------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (disabled) return false
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 先判 ± 键：按下就立刻走一步，并挂上长按连发（和原表盘一致的手感）。
                val hitY = cy + BTN_DY
                if (abs(event.y - hitY) <= BTN_SIZE / 2f + 6f) {
                    val dx = event.x - cx
                    if (abs(dx - (-BTN_DX)) <= BTN_SIZE / 2f + 6f) {
                        pressedKey = 1
                        onDelta?.invoke(-TEMP_STEP)
                        handler.postDelayed(repeatTask, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    if (abs(dx - BTN_DX) <= BTN_SIZE / 2f + 6f) {
                        pressedKey = 2
                        onDelta?.invoke(TEMP_STEP)
                        handler.postDelayed(repeatTask, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                }
                // 剩下的都当拖动：从当前值起算，手感上是「抓住温度转盘」。
                dragging = true
                dragTemp = shown
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedKey != 0 || !dragging) return true
                val angle = Math.toDegrees(
                    atan2((event.x - cx).toDouble(), (-(event.y - cy)).toDouble()),
                ).toFloat()
                val clamped = angle.coerceIn(-135f, 135f)
                val t = TEMP_MIN + (clamped + 135f) / 270f * (TEMP_MAX - TEMP_MIN)
                val snapped = (t * 2f).roundToInt() / 2f
                if (snapped != dragTemp) {
                    dragTemp = snapped
                    // 拖动走**直接落值**（不渐变）：手指带着弧走，中间插值反而拖影。
                    shown = snapped
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatTask)
                if (pressedKey != 0) {
                    pressedKey = 0
                    return true
                }
                if (dragging) {
                    // 先清 dragging 再回调：回调里会 refresh() 回写真实温度，
                    // 不清的话这时的动画值会被当成用户的新输入。
                    dragging = false
                    onTemp?.invoke(dragTemp)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        const val TEMP_MIN = 16f
        const val TEMP_MAX = 32f
        const val TEMP_STEP = 0.5f
        const val REPEAT_INTERVAL = 150L

        /** 环轨道半径（画板：外径 139.5 − 半描边 9）。 */
        const val RING_R = 130.5f

        /** 环描边（画板 18）。 */
        const val RING_STROKE = 18f

        /** 环内白盘半径。 */
        const val DISC_R = 104f

        /** 温度字号（画板 56 粗）。 */
        const val TEMP_TEXT = 56f

        /** caption 字号。 */
        const val CAPTION_TEXT = 14f

        /** 温度相对环心的纵向偏移（画板：字心在环心上方 14.5）。 */
        const val TEMP_DY = -14.5f

        /** caption 相对环心的纵向偏移（画板：字心在上方 63.5）。 */
        const val CAPTION_DY = -63.5f

        /** ± 按钮相对环心的纵向偏移。 */
        const val BTN_DY = 55.5f

        /** ± 按钮块边长。 */
        const val BTN_SIZE = 44f

        /** ± 按钮块圆角。 */
        const val BTN_RADIUS = 14f

        /** ± 按钮相对环心的横向偏移。 */
        const val BTN_DX = 30f
    }
}

/**
 * 风量阶梯条：7 段**紧挨着**的柱子，越靠右越高（画板：段宽 62、间距 0，
 * 底边同高，高度 49 起步每格 +9）。
 *
 * 为什么不是等高方块：画板里它是「音量条 / 阶梯」的隐喻 —— 高度本身就在表达档位，
 * 等高方块要数格子才知道现在几档。下方 1..7 的数字保留，方便直接读数。
 */
private class ClimateFanBars(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    /** 当前档位（0 = 关机）。 */
    var level: Int = 0
        set(value) {
            val v = value.coerceIn(0, BAR_COUNT)
            if (field == v) return
            field = v
            animateTo(v)
        }

    /**
     * AUTO 状态。
     *
     * 画板里风量条不区分自动/手动（AUTO 由上面的功能胶囊表达），这里只保留字段
     * 让 [ClimatePage.refresh] 的既有赋值不用改。
     */
    var auto: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onLevel: ((Int) -> Unit)? = null

    private var shown = 0f
    private var animToken: Any? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = NUM_TEXT
    }

    private fun animateTo(target: Int) {
        val from = shown
        val token = Any()
        animToken = token
        Motion.animateValue(from, target.toFloat(), MotionSpec.VALUE_DURATION) {
            if (animToken !== token) return@animateValue
            shown = it
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (i in 0 until BAR_COUNT) {
            // 每格按「浮点档位」给出 0..1 的填充度：换档时颜色是滚过去的，不是硬切。
            val t = (shown - i).coerceIn(0f, 1f)
            val h = BAR_H0 + i * BAR_DH
            val left = i * BAR_W
            barPaint.color = MotionSpec.lerpColor(palette.accentSoft, palette.accent, t)
            canvas.drawRoundRect(
                left, BAR_BOTTOM - h, left + BAR_W, BAR_BOTTOM,
                BAR_RADIUS, BAR_RADIUS, barPaint,
            )

            // 数字：已经在用的档位用正文色，没用到的用最弱的灰（画板里就是这层对比）。
            numPaint.color = if (i < level) palette.text else palette.textFaint
            canvas.drawText(
                (i + 1).toString(),
                left + BAR_W / 2f,
                BAR_BOTTOM + NUM_GAP + NUM_TEXT * 0.35f,
                numPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        if (event.y > BAR_BOTTOM + NUM_GAP + NUM_TEXT) return true
        val i = (event.x / BAR_W).toInt().coerceIn(0, BAR_COUNT - 1)
        onLevel?.invoke(i + 1)
        return true
    }

    companion object {
        const val BAR_COUNT = 7

        /** 段宽（画板 62，段与段之间没有间隙）。 */
        const val BAR_W = 62f

        /** 最矮一段的高度。 */
        const val BAR_H0 = 49f

        /** 每往右一段长高多少。 */
        const val BAR_DH = 9f

        /** 柱子底边（视图内坐标 = 画板 y326 − 视图顶 216）。 */
        const val BAR_BOTTOM = 110f

        /** 柱子圆角（画板 16）。 */
        const val BAR_RADIUS = 16f

        /** 数字字号。 */
        const val NUM_TEXT = 14f

        /** 数字与柱底之间的空隙。 */
        const val NUM_GAP = 6f
    }
}

/**
 * 座舱出风示意：三个座位 + 下方「吹脸 / 吹脚 / 吹窗」，随吹风模式亮灭。
 *
 * 语义与原 [com.cardesk.launcher.ui.widget.CabinView] 一致（[setFlow] 三个布尔），
 * 只是画法换成画板里的「三个圆形图标 + 文字标签」。
 */
private class ClimateCabin(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var onZoneTap: ((bit: Int) -> Unit)? = null

    private var face = false
    private var feet = false
    private var wind = false
    private var downZone = -1

    fun setFlow(face: Boolean, feet: Boolean, wind: Boolean) {
        if (this.face == face && this.feet == feet && this.wind == wind) return
        this.face = face
        this.feet = feet
        this.wind = wind
        invalidate()
    }

    private val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = LABEL_TEXT
    }
    private val glyphPath = Path()

    override fun onDraw(canvas: Canvas) {
        // 内嵌槽：画板里这块**比面板暗一档**（day #E3E7E9 / night #2A3038）。
        // 这里不能用 surfaceSoft —— 日间的 surfaceSoft 是「比面板更白的白」，
        // 铺上去整块直接消失；用 6% 的正文色压一层，日间变灰、夜间自然变亮，正好同向。
        wellPaint.color = CardStyle.scaleAlpha(palette.text, 0.06f)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), WELL_RADIUS, WELL_RADIUS, wellPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = palette.textDim
        canvas.drawText("座舱出风示意", PAD, TITLE_BASELINE, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = HINT_TEXT
        textPaint.color = palette.textFaint
        canvas.drawText("点按切换出风方向", width - PAD, TITLE_BASELINE, textPaint)
        textPaint.textSize = LABEL_TEXT

        drawZone(canvas, ZONE_X[0], "吹脸", face, window = false)
        drawZone(canvas, ZONE_X[1], "吹脚", feet, window = false)
        drawZone(canvas, ZONE_X[2], "吹窗", wind, window = true)
    }

    private fun drawZone(canvas: Canvas, cx: Float, label: String, active: Boolean, window: Boolean) {
        // 亮态 = accentSoft 圆底 + accent 图标 + accent 字；灭态 = 不画底、图标和字都退成最弱的灰。
        if (active) {
            circlePaint.color = palette.accentSoft
            canvas.drawCircle(cx, ICON_CY, ICON_R, circlePaint)
        }
        glyphPaint.color = if (active) palette.accent else palette.textFaint
        if (window) drawWindowGlyph(canvas, cx, ICON_CY) else drawAirGlyph(canvas, cx, ICON_CY)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = if (active) palette.accent else palette.textFaint
        canvas.drawText(label, cx, LABEL_BASELINE, textPaint)
    }

    /** 「吹脸 / 吹脚」的风纹：三条向左收的波浪，左端一个小卷。 */
    private fun drawAirGlyph(canvas: Canvas, cx: Float, cy: Float) {
        glyphPath.reset()
        for (i in -1..1) {
            val y = cy + i * 9f
            glyphPath.moveTo(cx + 14f, y)
            glyphPath.cubicTo(cx + 5f, y - 4f, cx - 1f, y + 4f, cx - 7f, y)
        }
        canvas.drawPath(glyphPath, glyphPaint)
        // 左端的小卷：画板里三条线的左端都收进一个半圆
        canvas.drawArc(cx - 18f, cy - 6f, cx - 6f, cy + 6f, 90f, 250f, false, glyphPaint)
    }

    /** 「吹窗」的图标：前风挡梯形 + 下方三道除霜气流。 */
    private fun drawWindowGlyph(canvas: Canvas, cx: Float, cy: Float) {
        glyphPath.reset()
        glyphPath.moveTo(cx - 15f, cy - 9f)
        glyphPath.lineTo(cx + 15f, cy - 9f)
        glyphPath.lineTo(cx + 11f, cy + 5f)
        glyphPath.lineTo(cx - 11f, cy + 5f)
        glyphPath.close()
        canvas.drawPath(glyphPath, glyphPaint)
        for (i in -1..1) {
            val x = cx + i * 8f
            canvas.drawLine(x + 3f, cy + 10f, x - 1f, cy + 16f, glyphPaint)
        }
    }

    private fun zoneAt(x: Float, y: Float): Int {
        if (y < 36f || y > height.toFloat()) return -1
        for (i in ZONE_X.indices) {
            if (abs(x - ZONE_X[i]) <= ZONE_HIT_HALF_W) return i
        }
        return -1
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downZone = zoneAt(event.x, event.y)
                return downZone != -1
            }
            MotionEvent.ACTION_UP -> {
                val hit = downZone
                downZone = -1
                if (hit != -1 && zoneAt(event.x, event.y) == hit) {
                    performClick()
                    onZoneTap?.invoke(ZONE_BITS[hit])
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> downZone = -1
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** 块内左右留白。 */
        const val PAD = 23f

        /** 块圆角（画板 18）。 */
        const val WELL_RADIUS = 18f

        /** 标题 / 提示的字号。 */
        const val LABEL_TEXT = 14f
        const val HINT_TEXT = 12f

        /** 标题基线（画板墨迹底 y772 − 块顶 739）。 */
        const val TITLE_BASELINE = 34f

        /** 三个座位图标的中心 x（画板 719 / 956 / 1192 − 块左 590）。 */
        val ZONE_X = floatArrayOf(129f, 366f, 602f)

        /** 三个按钮对应的吹风掩码位：吹脸(1) / 吹脚(2) / 吹窗(4)。 */
        val ZONE_BITS = intArrayOf(1, 2, 4)

        /** 单个出风按钮的水平点击热区半宽。 */
        const val ZONE_HIT_HALF_W = 110f

        /** 图标中心 y 与亮态圆底半径。 */
        const val ICON_CY = 84f
        const val ICON_R = 32f

        /** 标签基线（画板墨迹底 y883 − 块顶 739）。 */
        const val LABEL_BASELINE = 144f
    }
}
