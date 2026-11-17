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
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.MediaSources
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec

/**
 * 媒体来源切换菜单。
 *
 * 有三份同构的菜单（场景页/导航卡/音乐页各一份，27 个来源行 id）
 * 行为完全一致；这里按分析文档的建议**合并成一套**：
 * 一个全屏层里放「遮罩 + 320 宽面板」，锚点由调用方给出（View 或设计坐标 RectF）。
 *
 * 规格
 * - 面板 320 宽、padding 10、圆角 26、底色 `#eb161e2c`、描边 1px `#29ffffff`；
 * - 标题「切换音乐来源」15px `#61e6eefa`；
 * - 列表 ScrollView 固定高 240、行高 60、字号 20、左侧带来源品牌色圆点。
 *
 * 弹出位置规则：默认落在锚点下方 10px
 * 下方空间不足时翻到锚点上方；水平方向夹在父容器内（左右各留 8px）。
 */
class SourceMenuLayer(context: Context, private val pal: Palette) : FrameLayout(context) {

    companion object {
        private const val PANEL_W = 320
        private const val PANEL_PAD = 10
        private const val LIST_H = 240
        private const val ITEM_H = 60
        private const val HEADER_TEXT = 15f
        private const val ITEM_TEXT = 20f
        private const val MARGIN = 8f
        private const val GAP = 10f
    }

    /** 选中某个来源（传 sourceId）。 */
    var onPick: ((String) -> Unit)? = null

    /** 菜单打开/关闭（用于把来源行的箭头转 180°）。 */
    var onToggle: ((Boolean) -> Unit)? = null

    private val scrim = View(context)
    private val panel = LinearLayout(context)
    private val listBox = LinearLayout(context)

    /** 锚点：优先用设计坐标矩形，其次用真实 View。 */
    private var anchorRect: RectF? = null
    private var anchorView: View? = null
    private var opened = false

    /**
     * 进场动画待启动。
     *
     * 面板是**按锚点定位**的（[placePanel]），动画必须等摆好位置再开始 ——
     * 否则会看到面板先从屏幕左上角闪一下再滑到位。
     */
    private var enterPending = false

    init {
        // 遮罩： musicSourceScrim 是透明 View，靠 click 事件收起菜单。
        scrim.setBackgroundColor(0x00000000)
        scrim.isClickable = true
        // 遮罩的按压反馈：点空白处收起菜单的这一次点击，
        // 用的是和其它控件同一套按压节奏（60ms 按下 / 90ms 回弹）。
        //
        // 遮罩本身是**全透明**的，所以缩放看不出来 ——
        // 看得见的是「面板淡出退场」（见 [hide]）。
        // 这里仍然装上，是为了让「点空白收起」这一步也有明确的按压时刻。
        Motion.press(scrim)
        scrim.setOnClickListener { hide() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(PANEL_PAD, PANEL_PAD, PANEL_PAD, PANEL_PAD)
        panel.background = Ui.round(0xEB161E2C.toInt(), 26f, 1f, 0x29FFFFFF.toInt())
        panel.isClickable = true
        addView(panel, LayoutParams(PANEL_W, LayoutParams.WRAP_CONTENT))

        val header = Ui.text(context, "切换音乐来源", HEADER_TEXT, 0xFF61E6EEFA.toInt())
        header.setPadding(14, 8, 0, 10)
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LIST_H))

        listBox.orientation = LinearLayout.VERTICAL
        scroll.addView(listBox, ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        for (source in MediaSources.LIST) {
            val item = Ui.text(context, source.label, ITEM_TEXT, 0xFFEEF3FA.toInt())
            item.gravity = Gravity.CENTER_VERTICAL
            item.setPadding(14, 0, 14, 0)
            item.setCompoundDrawablesWithIntrinsicBounds(dot(source.brandColor), null, null, null)
            item.compoundDrawablePadding = 12
            item.isClickable = true
            // 来源行按压反馈：以前 27 行点下去是「啪」地直接切走，
            // 手指没有任何「按到了」的确认，容易连点两行。
            pressFeedback()
            item.setOnClickListener {
                // 排障场景：「来源菜单点了某一项没切过去」——选中项落盘。
                Logx.event(
                    LogTags.MEDIA,
                    "媒体来源菜单选中",
                    "id" to source.id,
                    "label" to source.label,
                )
                hide()
                onPick?.invoke(source.id)
            }
            listBox.addView(item, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, ITEM_H))
        }

        visibility = View.GONE
    }

    /** 来源品牌色圆点（用的是各家的品牌图标，这里用同色圆点表达同一语义）。 */
    private fun dot(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(28, 28)
        }

    /** 以真实 View 为锚点弹出（如音乐页的来源行）。 */
    fun toggle(anchor: View) {
        if (opened && anchorView === anchor) {
            hide()
            return
        }
        anchorView = anchor
        anchorRect = null
        show()
    }

    /** 以设计坐标矩形为锚点弹出（如自绘的场景页来源行）。 */
    fun toggle(rect: RectF) {
        if (opened && anchorRect == rect) {
            hide()
            return
        }
        anchorRect = rect
        anchorView = null
        show()
    }

    private fun show() {
        opened = true
        enterPending = true
        panel.visibility = View.INVISIBLE
        visibility = View.VISIBLE
        requestLayout()
        // 布局跑完再摆一次位置并起进场动画 ——
        // 面板是按锚点算出来的（[placePanel]），位置没定下来之前动画没有意义。
        post {
            if (opened) placePanel()
        }
        // 排障场景：「来源菜单没弹出来 / 弹的位置不对」——打开落盘（含锚点）。
        Logx.event(
            LogTags.MEDIA,
            "媒体来源菜单打开",
            "anchorView" to (anchorView != null),
            "anchor" to (anchorRect?.let { "${it.left},${it.top}" } ?: ""),
        )
        onToggle?.invoke(true)
    }

    fun hide() {
        if (!opened) return
        opened = false
        // 退场：先播动画，**动画结束后**才摘层。
        //
        // 以前是 `visibility = GONE` 一步到位 —— 菜单「啪」地就没了，
        // 眼睛还没跟上，手指已经落回屏幕上了。
        // 现在按全 App 对话框那一套退场节奏来（下滑 + 淡出）。
        //
        // 这里用 ViewPropertyAnimator 而不是 [Motion.exitSheet]：
        // 退场的这 240ms 里用户可能马上又点开菜单，那时 [Motion.enterSheet]
        // 会 `animate().cancel()` 掉这一段 —— 两套动画抢同一个属性会抖。
        panel.animate().cancel()
        // 清掉上一次可能留下的 end action ——
        // `view.animate()` 每次拿到的都是同一个实例，回调会一直留在上面，
        // 下次**进场**动画结束时又会被触发一次（会把面板的入场结果直接改掉）。
        panel.animate().withEndAction(null)
        panel.animate()
            .alpha(0f)
            .scaleX(MotionSpec.SHEET_ENTER_SCALE)
            .scaleY(MotionSpec.SHEET_ENTER_SCALE)
            .translationY(MotionSpec.SHEET_SLIDE_PX)
            .setDuration(MotionSpec.SHEET_EXIT_DURATION)
            .setInterpolator(Motion.EMPHASIS)
            .withEndAction {
                // 这一段跑完就不再挂回调（否则会绕回无限循环）。
                panel.animate().withEndAction(null)
                visibility = View.GONE
                panel.visibility = View.VISIBLE
                // 复位：下次打开是从「起点」进场，而不是从残留位置滑。
                panel.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.translationY = 0f
            }
            .start()
        // 排障场景：「来源菜单关不掉」——关闭落盘。
        Logx.event(LogTags.MEDIA, "媒体来源菜单关闭")
        onToggle?.invoke(false)
    }

    val isOpen: Boolean get() = opened

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (opened) placePanel()
    }

    /** 端口（锚点换算到本层坐标后再定位）。 */
    private fun placePanel() {
        val anchor = resolveAnchor() ?: return
        val parentH = height.toFloat()
        val panelH = panel.measuredHeight.toFloat()
        var y = anchor.bottom + GAP
        if (panelH + y > parentH - MARGIN) {
            // 下方放不下就翻到锚点上方
            y = (anchor.top - panelH - GAP).coerceAtLeast(MARGIN)
        }
        val maxX = (width - panel.measuredWidth).toFloat() - MARGIN
        val x = anchor.left.coerceIn(MARGIN, maxOf(MARGIN, maxX))
        panel.x = x
        panel.y = y
        panel.visibility = View.VISIBLE
        // 位置定了才起进场动画（上滑 + 淡入 + 轻微放大）——
        // 否则会看到面板先在左上角闪一下再滑到位。
        if (enterPending) {
            enterPending = false
            Motion.enterSheet(panel, null)
        }
    }

    /** 把锚点换算成本层（设计坐标）的矩形。 */
    private fun resolveAnchor(): RectF? {
        anchorRect?.let { return it }
        val view = anchorView ?: return null
        if (view.width == 0 || view.height == 0) return null
        val a = IntArray(2)
        val b = IntArray(2)
        view.getLocationInWindow(a)
        getLocationInWindow(b)
        val scale = cumulativeScale()
        if (scale <= 0f) return null
        val left = (a[0] - b[0]) / scale
        val top = (a[1] - b[1]) / scale
        return RectF(left, top, left + view.width / scale, top + view.height / scale)
    }

    /** 本层及其祖先的累计缩放（桌面整层按设计稿等比缩放）。 */
    private fun cumulativeScale(): Float {
        var scale = 1f
        var v: View? = this
        while (v != null) {
            scale *= v.scaleX
            v = v.parent as? View
        }
        return scale
    }
}
