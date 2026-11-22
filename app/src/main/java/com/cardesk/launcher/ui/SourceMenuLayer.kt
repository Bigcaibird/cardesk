package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.GlassDrawable
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.MediaSources
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 媒体来源切换菜单（设计板 `11-media-day` / `n05-media-night`）。
 *
 * ## 版式（画板实测，1920×1080）
 *
 * - 面板 **361×625 @ (44,73)**，圆角 **28**，L2 浅玻璃（`pal.surfaceStrong` + 1px 内描边）；
 * - 标题「切换音乐来源」**13px** `Skin.TEXT_DIM`，左内边 16、上内边 16；
 * - 9 行来源，行高 **64**，行内：品牌色圆点 12 + 名称 **20px**；
 * - 当前来源整行铺 `18%` accent 胶囊，右侧「当前」**12px** accent；
 * - 面板**固定**在左上角弹出（画板就是这样），不再按锚点翻转 ——
 *   老实现贴着屏幕顶被裁掉，独立验收判 P0。
 *
 * ## 为什么固定位置
 *
 * 这个菜单的入口有三处（场景页来源行 / Dock 媒体卡 / 音乐页来源行），
 * 锚点各不相同；老实现按锚点算位置，Dock 媒体卡在屏幕底部时菜单翻到上方，
 * 高度 240 的列表区只露出 4 项、还被状态栏裁掉。
 * 画板给出的答案很明确：**它就是一个固定位置的列表弹层**。
 */
class SourceMenuLayer(context: Context, private val pal: Palette) : FrameLayout(context) {

    companion object {
        private const val PANEL_W = 361
        private const val PANEL_H = 625
        private const val PANEL_X = 44
        private const val PANEL_Y = 73
        private const val PANEL_PAD = 8
        private const val HEADER_H = 44
        private const val ITEM_H = 64
        private const val HEADER_TEXT = 13f
        private const val ITEM_TEXT = 20f
        private const val BADGE_TEXT = 12f
    }

    /**
     * 弹层定位方式（第244轮，用户反馈）。
     *
     * - [FIXED]：画板 11 的固定左上角（44,73）—— 用于没有明确载体的场合；
     * - [ABOVE]：**贴在锚点（媒体卡）上方** —— 首页音乐卡的来源弹层；
     * - [OVER]：**盖在锚点（音乐卡）上** —— 导航组合页音乐卡的来源弹层。
     */
    enum class Placement { FIXED, ABOVE, OVER }

    /** 选中某个来源（传 sourceId）。 */
    var onPick: ((String) -> Unit)? = null

    /** 菜单打开/关闭（用于把来源行的箭头转 180°）。 */
    var onToggle: ((Boolean) -> Unit)? = null

    /** 当前来源 id（打开前由宿主写入），用于高亮「当前」行。 */
    var currentId: String = ""

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val listBox = LinearLayout(context)
    private val rows = HashMap<String, View>()

    private var opened = false

    /** 当前锚点（设计坐标）与定位方式，见 [Placement]。 */
    private var anchor: RectF? = null
    private var placement: Placement = Placement.FIXED

    /**
     * 进场动画待启动。
     *
     * 面板是**按固定坐标**定位的，动画必须等摆好位置再开始 ——
     * 否则会看到面板先从屏幕左上角闪一下再滑到位。
     */
    private var enterPending = false

    init {
        // 遮罩：透明 View，靠 click 事件收起菜单。
        scrim.setBackgroundColor(0x00000000)
        scrim.isClickable = true
        Motion.press(scrim)
        scrim.setOnClickListener { hide() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(PANEL_PAD, PANEL_PAD, PANEL_PAD, PANEL_PAD)
        // L2 浮层：浅玻璃（夜間同一套 token 反转）+ 1px 内描边 + 柔阴影。
        panel.background = GlassDrawable(
            radius = 28f,
            fill = pal.surfaceStrong,
            strokeColor = Skin.GLASS_STROKE,
            shadow = true,
            sheen = true,
        )
        panel.isClickable = true
        // 用 margin 绝对定位：x/y 在部分父容器（带 scale 的桌面层）里会被布局覆盖，
        // 实机曾出现「x 生效、y 没生效」——面板顶到状态栏上。
        addView(
            panel,
            LayoutParams(PANEL_W, PANEL_H).apply {
                leftMargin = PANEL_X
                topMargin = PANEL_Y
            },
        )

        val header = Ui.text(context, "切换音乐来源", HEADER_TEXT, Skin.TEXT_DIM)
        header.setPadding(10, 0, 0, 0)
        header.gravity = Gravity.CENTER_VERTICAL
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, HEADER_H))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        listBox.orientation = LinearLayout.VERTICAL
        scroll.addView(listBox, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        for (source in MediaSources.LIST) {
            val row = buildRow(source.id, source.label, dot(source.brandColor))
            rows[source.id] = row
            listBox.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ITEM_H))
        }

        visibility = View.GONE
    }

    /**
     * 一行来源：圆点 + 名称（左），当前标记（右）。
     *
     * 用 FrameLayout 而不是 TextView compound drawable：画板的「当前」在行尾，
     * compound drawable 只能挂在文字左边或右边，位置会跟着文字长度跑。
     */
    private fun buildRow(id: String, label: String, dotDrawable: GradientDrawable): FrameLayout {
        val row = FrameLayout(context)
        val name = Ui.text(context, label, ITEM_TEXT, Skin.TEXT)
        name.gravity = Gravity.CENTER_VERTICAL
        name.setPadding(18, 0, 0, 0)
        name.setCompoundDrawablesWithIntrinsicBounds(dotDrawable, null, null, null)
        name.compoundDrawablePadding = 14
        row.addView(
            name,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        val badge = Ui.text(context, "当前", BADGE_TEXT, Skin.ACCENT)
        badge.gravity = Gravity.CENTER_VERTICAL
        val badgeLp = FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.MATCH_PARENT,
            Gravity.END,
        )
        badgeLp.rightMargin = 16
        row.addView(badge, badgeLp)
        row.tag = badge
        row.isClickable = true
        // 来源行按压反馈：以前 9 行点下去是「啪」地直接切走，手指没有「按到了」的确认。
        row.pressFeedback()
        row.setOnClickListener {
            Logx.event(LogTags.MEDIA, "媒体来源菜单选中", "id" to id, "label" to label)
            currentId = id
            paintRows()
            hide()
            onPick?.invoke(id)
        }
        return row
    }

    /** 按 [currentId] 打高亮 + 「当前」标记。 */
    private fun paintRows() {
        for ((id, row) in rows) {
            val current = id == currentId
            row.background = if (current) {
                Ui.round(pal.withAlpha(pal.accent, 0x1F), 20f)
            } else {
                null
            }
            (row.tag as? TextView)?.visibility = if (current) View.VISIBLE else View.INVISIBLE
        }
    }

    /** 来源品牌色圆点（用各家的品牌色，画板就是彩色小圆点）。 */
    private fun dot(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(24, 24)
        }

    /** 以真实 View 为锚点弹出（保留签名；位置按画板固定，不看锚点）。 */
    fun toggle(anchor: View) {
        if (opened) {
            hide()
            return
        }
        show()
    }

    /** 以设计坐标矩形为锚点弹出，按 [placement] 决定位置。 */
    fun toggle(rect: RectF?, placement: Placement = Placement.FIXED) {
        if (opened) {
            hide()
            return
        }
        this.anchor = rect
        this.placement = placement
        show()
    }

    private fun show() {
        opened = true
        enterPending = true
        paintRows()
        panel.visibility = View.INVISIBLE
        visibility = View.VISIBLE
        requestLayout()
        // 布局跑完再摆一次位置并起进场动画。
        post {
            if (opened) placePanel()
        }
        Logx.event(LogTags.MEDIA, "媒体来源菜单打开", "currentId" to currentId)
        onToggle?.invoke(true)
    }

    fun hide() {
        if (!opened) return
        opened = false
        // 退场：先播动画，**动画结束后**才摘层。
        // 用 ViewPropertyAnimator 而不是 [Motion.exitSheet]：退场的这 240ms 里
        // 用户可能马上又点开菜单，那时 [Motion.enterSheet] 会 cancel 掉这一段。
        panel.animate().cancel()
        panel.animate().withEndAction(null)
        panel.animate()
            .alpha(0f)
            .scaleX(MotionSpec.SHEET_ENTER_SCALE)
            .scaleY(MotionSpec.SHEET_ENTER_SCALE)
            .translationY(MotionSpec.SHEET_SLIDE_PX)
            .setDuration(MotionSpec.SHEET_EXIT_DURATION)
            .setInterpolator(Motion.EMPHASIS)
            .withEndAction {
                panel.animate().withEndAction(null)
                visibility = View.GONE
                panel.visibility = View.VISIBLE
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.translationY = 0f
            }
            .start()
        Logx.event(LogTags.MEDIA, "媒体来源菜单关闭")
        onToggle?.invoke(false)
    }

    val isOpen: Boolean get() = opened

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (opened) placePanel()
    }

    /**
     * 摆位置（第244轮：支持三种定位）。
     *
     * 位置一律通过 LayoutParams 的 `leftMargin/topMargin` 落地。
     * 这里**不能**再写 `panel.x = 0f`：`View.setX` 是**绝对坐标**
     * （`translationX = x - left`），会把 leftMargin 抵消成负数 ——
     * 第 2 轮验收实测面板贴死屏幕左缘就是这个原因。
     */
    private fun placePanel() {
        panel.visibility = View.VISIBLE
        placeByAnchor()
        // 位置定了才起进场动画（上滑 + 淡入 + 轻微放大）。
        if (enterPending) {
            enterPending = false
            Motion.enterSheet(panel, null)
        }
    }

    /** 按 [placement] 算出面板左上角，并写回 LayoutParams。 */
    private fun placeByAnchor() {
        val a = anchor
        val dw = Design.W
        val dh = Design.H
        val x: Float
        val y: Float
        when {
            a == null || placement == Placement.FIXED -> {
                x = PANEL_X.toFloat()
                y = PANEL_Y.toFloat()
            }

            placement == Placement.ABOVE -> {
                // 卡片上沿再抬 14px，水平与卡片居中对齐；越界时收进屏幕内。
                x = (a.centerX() - PANEL_W / 2f).coerceIn(16f, dw - 16f - PANEL_W)
                y = (a.top - 14f - PANEL_H).coerceAtLeast(Design.STATUS_H + 8f)
            }

            else -> {
                // 盖在卡片上：两轴都居中，并夹在状态栏之下、屏幕之内。
                x = (a.centerX() - PANEL_W / 2f).coerceIn(16f, dw - 16f - PANEL_W)
                y = (a.centerY() - PANEL_H / 2f).coerceIn(Design.STATUS_H + 8f, dh - 8f - PANEL_H)
            }
        }
        val lp = panel.layoutParams as? LayoutParams ?: return
        val li = x.toInt()
        val ti = y.toInt()
        if (lp.leftMargin == li && lp.topMargin == ti) return
        lp.leftMargin = li
        lp.topMargin = ti
        panel.layoutParams = lp
    }
}
