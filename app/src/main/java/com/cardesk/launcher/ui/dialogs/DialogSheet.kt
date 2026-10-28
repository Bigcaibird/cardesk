package com.cardesk.launcher.ui.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.ui.shell.NavSlotBarView
import com.cardesk.launcher.ui.widget.OverlayBackdrop

/**
 * 各类对话框的公共外壳：全屏遮罩 + 浮屿面板 + **面板坐标系**。
 *
 * 第四十九轮起，面板按设计板 ~ 的**绝对坐标**排版：
 * - 面板本体 = 设计板 `.card`：`Skin.flat(Shape.CARD)`（`--surface` 纯色面 + 1px 描边 + 圆角 28，
 *   零投影、零玻璃；
 * - [body] 的左上角就是面板左上角，子视图用 [place] / [placeEnd] 按设计稿像素摆放；
 * - 面板在 1920×1080 设计坐标系里的位置由构造参数 `panelLeft` / `panelTop` 决定。
 *
 * 所有尺寸都是**设计坐标像素**（1920×1080），由上层 `content` 统一等比缩放。
 */
abstract class DialogSheet(
    context: Context,
    protected val pal: Palette,
    width: Int,
    height: Int,
    protected val panelTitle: String,
    panelLeft: Int? = null,
    panelTop: Int? = null,
) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null

    /** 面板本体（设计板 `.card`）。 */
    protected val sheet: FrameLayout

    /** 面板坐标系：(0,0) = 面板左上角。 */
    protected val body: FrameLayout

    /** 面板宽 / 高（设计 px）。 */
    protected val panelWidth: Int = width
    protected val panelHeight: Int = height

    init {
        // 排障场景：对话框「没弹出来 / 弹了两次」—— 开合都留一条
        Logx.event(LogTags.SETTING, "打开对话框", "dialog" to panelTitle, "type" to this.javaClass.simpleName)
        isClickable = true
        // 第五十轮：浮层背景板 = `.bgwash` + `.scrim`（**不透明**），与设计板 s06~s12 一致 ——
        // 遮罩下面是干净的背景，不会把桌面卡片从后面透出来。
        val backdrop = OverlayBackdrop(context).apply {
            palette = pal
            withScrim = true
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        sheet = FrameLayout(context).apply {
            // 设计板 `.card` = var(--surface) 纯色面 + 1px 描边 + 圆角 28。
            // 第五十一轮修正：之前误传 `strong = true`（= `--surfaceStrong`），
            // 面板底色比设计板深一档（日 #E8EBF1 vs #FFFFFF / 夜 #2A2F3B vs #1F232D）。
            // 这里必须走默认分支（`GLASS_STRONG_FILL` = `pal.surface`）。
            background = Skin.flat(Shape.CARD)
            isClickable = true
            setOnClickListener { }
        }
        val lp = LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
        if (panelLeft != null && panelTop != null) {
            lp.leftMargin = panelLeft
            lp.topMargin = panelTop
        } else {
            lp.gravity = Gravity.CENTER
        }
        addView(sheet, lp)
        body = FrameLayout(context)
        sheet.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 对话框给**增强导航栏**让位。
     *
     * 只有在**开启增强导航栏**时，
     * 对话框窗口高度才减去导航栏高度（遮罩不覆盖导航栏）；普通模式返回 0，
     * 对话框铺满整屏。
     */
    protected fun dockClearancePx(): Int {
        val enabled = runCatching { Prefs(context.applicationContext).enhancedNav }.getOrDefault(false)
        if (!enabled) return 0
        // 排障场景：对话框底部被增强导航栏盖住 —— 记下让位高度
        Logx.d(LogTags.SYS, "增强导航栏让位高度", "clearancePx" to NavSlotBarView.WIDE_HEIGHT_PX, "dialog" to panelTitle)
        return NavSlotBarView.WIDE_HEIGHT_PX
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val clearance = dockClearancePx()
        if (clearance <= 0) return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = clearance
        layoutParams = lp
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 排障场景：对话框关掉了但改动没生效 —— 看这条确认它确实被移除过
        Logx.event(LogTags.SETTING, "关闭对话框", "dialog" to panelTitle, "type" to this.javaClass.simpleName)
    }

    // --------------------------------------------------------- 面板坐标系摆放

    /** 按设计稿坐标（左上角）摆放一个子视图。 */
    protected fun <T : View> place(view: T, left: Int, top: Int, width: Int = -2, height: Int = -2): T {
        body.addView(
            view,
            FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START).apply {
                leftMargin = left
                topMargin = top
            },
        )
        return view
    }

    /** 按设计稿的 `right:` 摆放一个子视图（右对齐）。 */
    protected fun <T : View> placeEnd(view: T, right: Int, top: Int, width: Int = -2, height: Int = -2): T {
        body.addView(
            view,
            FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.END).apply {
                rightMargin = right
                topMargin = top
            },
        )
        return view
    }

    /** 标题（设计稿：38px bold，左上 40 / 34）。 */
    protected fun addTitle(
        text: String = panelTitle,
        sizePx: Float = 38f,
        left: Int = 40,
        top: Int = 34,
    ): TextView = place(Ui.text(context, text, sizePx, pal.text, bold = true), left, top)

    /**
     * 「关闭」胶囊（设计板 s05 右上角：150×66 圆角胶囊 + 1px 描边 + 24px 弱色字）。
     *
     * 设计板 s06~s12 的标题行没有画关闭键，但**关闭是必须保留的功能**，
     * 所以统一放在面板右上角，用设计语言里的胶囊形态。
     */
    protected fun addClose(right: Int = 36, top: Int = 36): TextView =
        placeEnd(closePill(), right, top, 150, 66)

    /** 「关闭」胶囊本体（不自动摆放，方便放到面板的其它位置）。 */
    protected fun closePill(): TextView = Ui.text(context, "关闭", 24f, pal.textDim).apply {
        gravity = Gravity.CENTER
        background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
        pressFeedback()
        setOnClickListener { onClose?.invoke() }
    }

    /** 1px 分割线（设计板 `.hairline`）。 */
    protected fun addHairline(left: Int, top: Int, width: Int): View =
        place(View(context).apply { setBackgroundColor(pal.stroke) }, left, top, width, 1)

    /**
     * 分段控件（设计板 `.seg`）：surfaceSoft 底 + 1px 描边 + 圆角 999 + padding 5 + gap 6；
     * 每项 20px、padding 9px 22px、圆角 999、字色 textDim；选中项 accent 底 + accentInk 字 + 粗体。
     */
    protected fun segmented(
        items: List<Pair<String, Boolean>>,
        sizePx: Float = 20f,
        onPick: ((Int) -> Unit)? = null,
    ): LinearLayout {
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            setPadding(5, 5, 5, 5)
        }
        items.forEachIndexed { index, entry ->
            val label = entry.first
            val selected = entry.second
            val item = Ui.text(context, label, sizePx, if (selected) pal.accentInk else pal.textDim, bold = selected).apply {
                gravity = Gravity.CENTER
                if (selected) background = Ui.round(pal.accent, Shape.PILL)
                setPadding(22, 9, 22, 9)
                if (onPick != null) {
                    pressFeedback()
                    setOnClickListener { onPick(index) }
                }
            }
            seg.addView(item, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) leftMargin = 6 })
        }
        return seg
    }

    /** 胶囊按钮（设计板 `.chip`：surfaceSoft 底 + 1px 描边 + 圆角 999 + textDim 字）。 */
    protected fun chip(
        label: String,
        active: Boolean = false,
        sizePx: Float = 20f,
        padH: Int = 22,
        padV: Int = 12,
        onClick: () -> Unit,
    ): TextView =
        Ui.text(context, label, sizePx, if (active) pal.accentInk else pal.textDim, bold = active).apply {
            background = if (active) {
                Ui.round(pal.accent, Shape.PILL)
            } else {
                Ui.round(Skin.surfaceSoft, Shape.PILL, 1f, Skin.NAV_STROKE)
            }
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            pressFeedback()
            setOnClickListener { onClick() }
        }

    /** 主按钮（accent 实底 + accentInk 字，胶囊）。 */
    protected fun primaryButton(label: String, sizePx: Float = 24f, onClick: () -> Unit): TextView =
        Ui.text(context, label, sizePx, pal.accentInk, bold = true).apply {
            background = Ui.round(pal.accent, Shape.PILL)
            gravity = Gravity.CENTER
            setPadding(36, 14, 36, 14)
            pressFeedback()
            setOnClickListener { onClick() }
        }

    /** 分组小标题（设计板 20px textFaint 的字段标签）。 */
    protected fun section(label: String): View = Ui.text(context, label, 20f, pal.textFaint)
        .also { it.layoutParams = Ui.linear(-1, 32) }

    /** 说明文字（19px textFaint）。 */
    protected fun note(label: String): View = Ui.text(context, label, 19f, pal.textFaint)
        .also { it.layoutParams = Ui.linear(-1, 30) }

    /**
     * 没有设计板规格的旧对话框（在线更新 / 重启确认 / 仪表歌词）用的纵向流式容器：
     * 标题 + 关闭键 + 可滚动内容。
     */
    protected fun flowContent(): LinearLayout {
        addTitle()
        addClose()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        place(column, 40, 120, panelWidth - 80, panelHeight - 120 - 32)
        return column
    }

    protected fun skinAccentSoft(): Int = pal.withAlpha(pal.accent, 0x40)

    companion object {
        /**
         * 对话框遮罩统一色。
         *
         * 第四十九轮「浮屿」改用 [Skin.SCRIM]（夜 `0xA808090C` / 日 `0x61181A20`），
         * 与设计板 ~ 的遮罩保持一致。
         */
        val OVERLAY_SCRIM_COLOR: Int = Skin.SCRIM
    }
}

/**
 * 设计板 `.switch`：84×48 胶囊轨 + 38 圆点。
 *
 * 不用 `widget` 包的 ToggleSwitch（那是 76×44），这里按设计稿尺寸自绘
 * 关闭态 = surfaceStrong 轨 + 1px 描边，开关态 = accent 轨 + accent 描边，
 * 圆点 38（关闭态用 surface 色，开启态用纯白）。
 */
internal class FlatSwitch(context: Context) : View(context) {

    var on: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var accent: Int = Skin.ACCENT

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(SWITCH_W, SWITCH_H)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f

        paint.style = Paint.Style.FILL
        paint.color = if (on) accent else Skin.surfaceStrong
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (on) accent else Skin.NAV_STROKE
        canvas.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, radius, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (on) 0xFFFFFFFF.toInt() else Skin.surface
        val knob = KNOB.toFloat()
        val cx = if (on) 40f + knob / 2f else 4f + knob / 2f
        canvas.drawCircle(cx, 4f + knob / 2f, knob / 2f, paint)
    }

    companion object {
        /** 设计板 `.switch` 尺寸。 */
        const val SWITCH_W = 84
        const val SWITCH_H = 48
        private const val KNOB = 38f
    }
}

/**
 * 设计板用的 lucide 线性图标（24×24 视框，1.8 描边）自绘。
 * 只画对话框真正用到的那几个：chevron / search / trash / cast。
 */
internal class IconView(
    context: Context,
    private val glyph: Glyph,
    private val iconSize: Float,
) : View(context) {

    enum class Glyph { CHEVRON_RIGHT, SEARCH, TRASH, CAST }

    var iconColor: Int = Skin.TEXT_DIM

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(iconSize.toInt(), iconSize.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val scale = iconSize / 24f
        canvas.save()
        canvas.scale(scale, scale)
        paint.color = iconColor
        paint.strokeWidth = 1.8f
        canvas.drawPath(buildPath(glyph), paint)
        canvas.restore()
    }

    private fun buildPath(glyph: Glyph): Path {
        val p = Path()
        when (glyph) {
            Glyph.CHEVRON_RIGHT -> {
                p.moveTo(9f, 6f)
                p.lineTo(15f, 12f)
                p.lineTo(9f, 18f)
            }

            Glyph.SEARCH -> {
                p.addCircle(11f, 11f, 7f, Path.Direction.CW)
                p.moveTo(20f, 20f)
                p.lineTo(16.5f, 16.5f)
            }

            Glyph.TRASH -> {
                p.moveTo(3f, 6f)
                p.lineTo(21f, 6f)
                p.moveTo(8f, 6f)
                p.lineTo(8f, 4f)
                p.lineTo(16f, 4f)
                p.lineTo(16f, 6f)
                p.moveTo(6f, 6f)
                p.lineTo(7f, 20f)
                p.lineTo(17f, 20f)
                p.lineTo(18f, 6f)
            }

            Glyph.CAST -> {
                p.addRoundRect(RectF(2f, 4f, 22f, 18f), 2f, 2f, Path.Direction.CW)
                p.addArc(RectF(-3.1f, 15.9f, 7.1f, 26.1f), -90f, 90f)
                p.addArc(RectF(-7.95f, 12.05f, 11.95f, 31.95f), -90f, 90f)
                p.moveTo(2f, 20f)
                p.lineTo(4f, 20f)
            }
        }
        return p
    }
}

/**
 * 设计板 s11 的虚线占位框：surfaceSoft 底 + 1px **虚线**描边 + 圆角 22。
 */
internal class DashBox(context: Context) : FrameLayout(context) {

    var fill: Int = Skin.surfaceSoft
    var stroke: Int = Skin.NAV_STROKE
    var corner: Float = 22f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // ViewGroup 默认 WILL_NOT_DRAW，不主动关掉的话 onDraw 不会被调用。
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        paint.color = fill
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        paint.color = stroke
        canvas.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, corner, corner, paint)
        paint.pathEffect = null
    }
}
