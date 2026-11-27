package com.cardesk.launcher.ui.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.CardStyle
import com.cardesk.launcher.core.DockOrder
import com.cardesk.launcher.core.DockQuickApps
import com.cardesk.launcher.core.Glass
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.vehicle.HvacState
import kotlin.math.abs

/**
 * 底部一体化控制栏（铺满屏宽 × 112，贴屏幕底边）。
 *
 * ## 第三百轮重做：按 `cardesk-ui/design/redesign/Main.png` 的版式重排
 *
 * 旧版是「四段式」（导航键 / 媒体卡 / 空调簇 / 快捷槽），由
 * `DockLayout.sections()` 按可用宽度自动分配。设计稿是**一条定尺的仪表带**：
 * 每个元素的 x 都是画板上的实测值，元素之间靠留白分组成
 * 「导航 → 媒体 → 电源 → 主驾温区 → 风量 → 副驾温区 → A/C · AUTO → 功能键」。
 *
 * 所以这里改成**按设计坐标绝对排布**（[DESIGN_W] = 1920，`dx()` 按实际宽度线性换算），
 * 不再让宽度分配算法决定位置 —— 分配算法在 1872 宽的全局 Dock 上会把
 * 媒体卡压窄、把温区推开，和画板对不上。**尺寸本身不随宽度缩放**
 * （缩放只作用在 x 坐标上），这样 1872 时各元素的视觉大小和 1920 完全一致。
 *
 * 逐项实测（Main.png，误差 ±2px）与画板的对应关系写在每个绘制函数的注释里。
 *
 * ## 保持不变的东西（硬性约束）
 *
 * - 所有点击 / 长按 / 滑动回调、`hit` 的 key 名、Dock 快捷槽逻辑、按压与脉冲动画；
 * - `Prefs.dockCornerRadius`（Dock 顶部圆角设置项）：这里仍然读它，只是默认档 0
 *   正好等于设计稿的「方角通栏」；
 * - `DockQuickApps.SLOTS` 只渲染**配置里有的**功能键，不新增也不删除。
 *
 * ## 历史说明（保留）
 *
 * - 与对齐的细节：温度固定一位小数、缺省 23.5；按键按下缩放到 0.96（60ms）、
 *   抬起回 1.0（90ms），与全 App 共用 `MotionSpec`；
 * - 媒体卡左右滑动切歌：位移 ≥8px 且横向分量 > 纵向 ×1.5 才触发；
 * - 主页键长按 = 原车多任务；分屏键长按 = 切换单画/双画。
 */
class DockBarView(context: Context) : View(context) {

    // —— 外部数据 ——
    private var hvac: HvacState? = null
    private var mediaTitle: String = "暂无播放"
    private var mediaArtist: String = ""
    private var mediaPlaying = false
    private var mediaProgress = 0f

    /**
     * 媒体卡封面。
     *
     * 设计稿的封面是 56×56、圆角 14 的方形（实测 x373..428 / y996..1051），
     * 里面放专辑图；没有专辑图时才画默认音符占位。
     */
    private var mediaArtwork: android.graphics.Bitmap? = null

    /**
     * Dock **快捷应用**槽位（第五十四轮新增）。
     *
     * @param pkg 包名（空串 = 该槽位没配）
     * @param label 应用名（画占位符号时取首字）
     * @param icon 应用图标（取不到时为 null）
     */
    data class QuickApp(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

    /** 快捷应用槽位（固定 [DockQuickApps.SLOTS] 个，没配的用空串占位）。 */
    private var quickApps: List<QuickApp> = emptyList()

    /**
     * 点击某个快捷应用槽位。
     *
     * @param index 槽位下标
     * @param pkg 该槽位的包名（空串 = 空槽位，此时应改为「打开配置」）
     */
    var onQuickApp: ((index: Int, pkg: String) -> Unit)? = null

    /**
     * 长按某个快捷应用槽位 → 打开该槽位的配置（第五十四轮）。
     *
     * @param index 槽位下标
     */
    var onQuickAppLongPress: ((index: Int) -> Unit)? = null

    var activePage: Int = 0
        set(v) { field = v; invalidate() }

    /**
     * 抽屉浮层是否打开
     * 打开时「应用」键高亮。
     */
    var appsOpen: Boolean = false
        set(v) { field = v; invalidate() }

    // —— 回调 ——
    var onBack: (() -> Unit)? = null
    var onHome: (() -> Unit)? = null
    var onApps: (() -> Unit)? = null
    var onSplit: (() -> Unit)? = null
    var on360: (() -> Unit)? = null

    /**
     * 媒体卡**封面 / 歌名区**被点（第一九七轮：拉起对应音乐 App）。
     *
     * 与 [onPlay] / [onMediaNext] / [onMediaPrev] 互斥 ——
     * 三个键在媒体卡**内部**，命中时优先匹配它们。
     *
     * ## 为什么不是「进我们自己的音乐页」
     *
     * 第一九七轮之前这里跳的是**第二页音乐卡**（`onMediaTap`）。
     * 用户要的是「点这块 → 打开**正在使用的那个**音乐 App」，
     * 所以语义换成了「拉起 App」，跳转自己的音乐页改由长按 / 其它入口承担。
     */
    var onMediaOpenApp: (() -> Unit)? = null

    var onPlay: (() -> Unit)? = null
    var onPower: (() -> Unit)? = null
    var onTemp: ((zone: Int, delta: Int) -> Unit)? = null
    var onFan: (() -> Unit)? = null
    var onAc: (() -> Unit)? = null
    var onAuto: (() -> Unit)? = null
    var onCirc: (() -> Unit)? = null
    var onDefrost: ((front: Boolean) -> Unit)? = null

    /**
     * 「应用」键 → 打开**抽屉浮层**。
     * 优先使用本回调；没有设置时退回 [onApps]。
     */
    var onAppsDrawer: (() -> Unit)? = null

    /** 主页键长按：打开原车多任务。 */
    var onHomeLongPress: (() -> Unit)? = null

    /** 分屏键长按：切换单画 / 双画。 */
    var onSplitLongPress: (() -> Unit)? = null

    /** 媒体卡向左滑：下一首（`skipToNext`）。 */
    var onMediaNext: (() -> Unit)? = null

    /**
     * 媒体卡**长按**：打开媒体浮窗（设计板 `s13-float` 的 460×250 小播放器）。
     *
     * 与左右滑动切歌互斥：滑动一旦触发就取消长按。
     */
    var onMediaLongPress: (() -> Unit)? = null

    /** 媒体卡向右滑：上一首（`skipToPrevious`）。 */
    var onMediaPrev: (() -> Unit)? = null

    fun bind(state: HvacState?) {
        val prev = hvac
        if (prev == state) return
        hvac = state
        // 数值变化的脉冲：只在**真的变了**的时候给信号。
        //
        // 第一次绑定（prev == null）是「初始状态」而不是「变化」，不脉冲；
        // state == null（车端没数据）同理 —— 否则整排数字会莫名其妙地抖一下。
        if (prev != null && state != null) {
            if (prev.fanLevel != state.fanLevel) fanPulse.fire()
            if (shownTemp(prev, passenger = false) != shownTemp(state, passenger = false)) {
                tempDriverPulse.fire()
            }
            if (shownTemp(prev, passenger = true) != shownTemp(state, passenger = true)) {
                tempPassengerPulse.fire()
            }
        }
        invalidate()
    }

    /**
     * 温度的**显示值**。
     *
     * ≤0 按 23.5 处理（与 [formatTemp] 同一个口径）——
     * 否则车端还没回数据时（0 / 缺省）会被误判成「温度变了」而乱脉冲。
     */
    private fun shownTemp(state: HvacState, passenger: Boolean): Float {
        val raw = if (passenger) state.tempPassenger else state.tempDriver
        return if (raw <= 0f) 23.5f else raw
    }

    fun bindMedia(
        title: String,
        artist: String,
        playing: Boolean,
        progress: Float = 0f,
        artwork: android.graphics.Bitmap? = null,
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        if (mediaTitle == title &&
            mediaArtist == artist &&
            mediaPlaying == playing &&
            kotlin.math.abs(mediaProgress - clamped) < 0.0005f &&
            mediaArtwork === artwork
        ) {
            return
        }
        mediaTitle = title
        mediaArtist = artist
        mediaPlaying = playing
        mediaProgress = clamped
        if (mediaArtwork !== artwork) {
            mediaArtwork = artwork
            coverArtShaderBmp = null
            coverArtShader = null
        }
        invalidate()
    }

    /**
     * 绑定 Dock 快捷应用槽位（第五十四轮）。
     *
     * 传进来的列表按槽位顺序，**不足 [DockQuickApps.SLOTS] 个的补空串**，
     * 免得「配了 2 个」和「配了 3 个」在渲染时算出不同的槽位位置。
     */
    fun bindQuickApps(apps: List<QuickApp>) {
        val padded = DockQuickApps.pad(apps.map { it.pkg })
        quickApps = List(DockQuickApps.SLOTS) { i ->
            val pkg = padded[i]
            if (pkg.isEmpty()) {
                QuickApp("", "", null)
            } else {
                apps.firstOrNull { it.pkg == pkg } ?: QuickApp(pkg, pkg, null)
            }
        }
        invalidate()
    }

    /**
     * 直接从 [MusicController] 取真实媒体信息刷新媒体卡
     * （数据来源）。
     *
     * 文案规则：标题没有内容时显示「暂无播放」，**歌手没有内容时是空串**
     * 不要塞占位文案。
     */
    fun refreshFromController() {
        val track = com.cardesk.launcher.core.media.MusicController.get().track
        val title = track?.title?.takeIf { it.isNotBlank() } ?: "暂无播放"
        val artist = track?.artist.orEmpty()
        val duration = track?.durationMs ?: 0L
        val progress = if (duration > 0L) {
            ((track?.positionMs ?: 0L).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        bindMedia(title, artist, track?.playing == true, progress, track?.artwork)
    }

    // —— 绘制资源 ——
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coverBitmapMatrix = Matrix()
    private var coverArtShaderBmp: Bitmap? = null
    private var coverArtShader: BitmapShader? = null

    /**
     * 没有专辑图时的封面底色。
     *
     * 设计稿里封面是**青绿渐变方块 + 白色音符**（`#00A9A0 → #2FD0C4`），
     * 正好是 `ACCENT → ACCENT_LIGHT` 两个 token，所以不再写死颜色。
     *
     * ⚠️ 不能 `by lazy` 缓存成 val：这两个 token 在**日夜切换**时会换值，
     * 而 Shader 一旦建好就把颜色固化了 —— 缓存的 shader 会在换主题后
     * 继续拿旧颜色画（夜间的封面还是白天的青绿）。所以按 `Skin.isNight`
     * 记一个快照，主题变了才重建。
     */
    private var coverShaderCache: Shader? = null
    private var coverShaderNight: Boolean? = null

    private fun coverShader(): Shader {
        val night = Skin.isNight
        coverShaderCache?.let { if (coverShaderNight == night) return it }
        return LinearGradient(
            0f, 0f, COVER_SIZE, COVER_SIZE,
            Skin.ACCENT, Skin.ACCENT_LIGHT,
            Shader.TileMode.CLAMP,
        ).also {
            coverShaderCache = it
            coverShaderNight = night
        }
    }

    /**
     * Dock 圆角（设计 px）：构造时从 `Prefs` 读，设置页改了走 [refreshAll]。
     *
     * 第190轮：默认值 36 → 0（用户：「dock栏圆角默认改成0px吧」）。
     * 这个**字段初值**也要跟 `Prefs.dockCornerRadius` 的默认值一致 ——
     * 否则「还没 attach 到窗口」（比如预览渲染、单测里直接 new 出来）的那一瞬间
     * 仍然是 36px 的圆角，跟用户设置里的默认值对不上。
     */
    private var cornerRadius: Float = 0f

    /** 当前 Dock 按钮与控件的显示顺序（16 项，从 Prefs.dockOrder 加载）。 */
    private var itemOrder: List<String> = runCatching { Prefs(context).dockOrder }.getOrDefault(DockOrder.DEFAULT)
    private var lastSlots: List<DockOrder.Slot> = emptyList()

    // —— 自定义拖动排序状态 ——
    private var draggingId: String? = null
    private var dragTouchOffsetX = 0f
    private var dragPointerX = 0f
    private var dragSlotWidth = 0f
    private var dragChanged = false

    private val reorderStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val dragHighlightStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** 当前按钮顺序（供单测与日志核对）。 */
    fun currentOrder(): List<String> = itemOrder

    /** 仅用于单测或外部同步设置顺序。 */
    fun applyOrder(order: List<String>) {
        itemOrder = DockOrder.normalize(order)
        invalidate()
    }

    private val bgPath = Path()

    private val r = RectF()
    private val hit = LinkedHashMap<String, RectF>()

    /**
     * 布局横向缩放比（onDraw 里按实际宽度算，见 [dx]）。
     *
     * 设计坐标是 1920 宽的；全局 Dock 是 1872 宽，按这个比例把 x 换算过去，
     * 元素之间的**留白**跟着等比收一点，元素的**尺寸**不变。
     */
    private var layoutScale = 1f

    /** 把设计稿上的 x 换算成当前宽度下的 x。 */
    private fun dx(x: Float): Float = x * layoutScale

    // —— 按压反馈——
    private var pressedKey: String? = null
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null

    // —— 数值变化脉冲（风量 / 主驾温度 / 副驾温度）——
    //
    // 温度和风量都是**自绘**的：值一变只能硬切，数字「啪」地跳过去，
    // 余光根本注意不到「这里变了」。这里给变化的那一处补一个短暂的放大脉冲。
    private val fanPulse = ValuePulse()
    private val tempDriverPulse = ValuePulse()
    private val tempPassengerPulse = ValuePulse()

    // —— 媒体卡滑动切歌——
    private val main = Handler(Looper.getMainLooper())
    private var swipeTracking = false
    private var swipeTriggered = false
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDx = 0f
    private var swipeDir = 0          // 1 = 向左（下一首），-1 = 向右（上一首）
    private var swipeOffset = 0f      // 当前文本块的横向位移
    private var swipeFraction = 0f    // 0..1 滑动进度
    private var swipePreviewTitle = ""
    private var swipePreviewArtist = ""

    // —— 长按（主页 / 分屏）——
    private var longPressKey: String? = null
    private val longPressRunnable = Runnable { fireLongPress(longPressKey) }
    private var longPressFired = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        cornerRadius = runCatching { Prefs(context).dockCornerRadius.toFloat() }.getOrDefault(0f)
        itemOrder = runCatching { Prefs(context).dockOrder }.getOrDefault(DockOrder.DEFAULT)
        live.add(java.lang.ref.WeakReference(this))
        invalidate()
    }

    override fun onDetachedFromWindow() {
        live.removeAll { it.get() == null || it.get() === this }
        draggingId = null
        // 视图离开窗口了，脉冲动画就别再空转着重绘。
        fanPulse.cancel()
        tempDriverPulse.cancel()
        tempPassengerPulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        layoutScale = w / DESIGN_W
        val cy = h / 2f

        drawBackdrop(canvas, w, h)
        hit.clear()

        val slots = DockOrder.layout(itemOrder, w)
        lastSlots = slots
        val activeDrag = if (reorderMode) draggingId else null

        // 先画其余控件（若正在拖动某个控件，先在其目标位画半透明占位框）
        for (slot in slots) {
            if (slot.id == activeDrag) {
                fill.color = CardStyle.scaleAlpha(Skin.ACCENT, 0.14f)
                val placeholder = RectF(slot.left, cy - 40f, slot.right, cy + 40f)
                canvas.drawRoundRect(placeholder, 20f, 20f, fill)
                reorderStroke.color = CardStyle.scaleAlpha(Skin.ACCENT, 0.7f)
                canvas.drawRoundRect(placeholder, 20f, 20f, reorderStroke)
                continue
            }
            drawDockItem(canvas, slot.id, slot.left, slot.width, cy)
            if (reorderMode) {
                reorderStroke.color = CardStyle.scaleAlpha(Skin.ACCENT, 0.55f)
                canvas.drawRoundRect(RectF(slot.left - 2f, cy - 40f, slot.right + 2f, cy + 40f), 20f, 20f, reorderStroke)
            }
        }

        // 最后把正在拖动的控件浮在最上层，放大并描亮边框
        if (activeDrag != null) {
            val dragW = dragSlotWidth.takeIf { it > 0f } ?: DockOrder.widthOf(activeDrag)
            val dragLeft = (dragPointerX - dragTouchOffsetX).coerceIn(8f, (w - dragW - 8f).coerceAtLeast(8f))
            val dragCx = dragLeft + dragW / 2f
            canvas.save()
            canvas.scale(1.06f, 1.06f, dragCx, cy)
            drawDockItem(canvas, activeDrag, dragLeft, dragW, cy)
            dragHighlightStroke.color = Skin.ACCENT
            canvas.drawRoundRect(RectF(dragLeft - 2f, cy - 40f, dragLeft + dragW + 2f, cy + 40f), 22f, 22f, dragHighlightStroke)
            canvas.restore()
        }
    }

    /** 按控件 ID 在指定水平区域绘制对应按钮或控件。 */
    private fun drawDockItem(canvas: Canvas, id: String, left: Float, w: Float, cy: Float) {
        val cx = left + w / 2f
        when (id) {
            DockOrder.ITEM_BACK -> drawBack(canvas, cx, cy)
            DockOrder.ITEM_HOME -> drawHome(canvas, left, cy)
            DockOrder.ITEM_APPS -> drawApps(canvas, cx, cy)
            DockOrder.ITEM_SPLIT -> drawSplit(canvas, cx, cy)
            DockOrder.ITEM_360 -> draw360(canvas, cx, cy)
            DockOrder.ITEM_MEDIA -> drawMedia(canvas, left, cy - MEDIA_H / 2f, w, MEDIA_H)
            DockOrder.ITEM_POWER -> drawPower(canvas, cx, cy)
            DockOrder.ITEM_TEMP_DRIVER -> drawTempPill(canvas, left, cy, w, passenger = false)
            DockOrder.ITEM_FAN -> drawFan(canvas, left, cy - FAN_SIZE / 2f, w)
            DockOrder.ITEM_TEMP_PASS -> drawTempPill(canvas, left, cy, w, passenger = true)
            DockOrder.ITEM_AC -> drawClimateChip(canvas, left, cy, w, "A/C", active = hvac?.ac == true)
            DockOrder.ITEM_AUTO -> drawClimateChip(canvas, left, cy, w, "AUTO", active = hvac?.auto == true)
            DockOrder.ITEM_CIRC -> drawRightKey(canvas, cx, cy, "circ")
            DockOrder.ITEM_DEFROST_R -> drawRightKey(canvas, cx, cy, "defrostR")
            DockOrder.ITEM_DEFROST_F -> drawRightKey(canvas, cx, cy, "defrostF")
            DockOrder.ITEM_QUICK0 -> drawRightKey(canvas, cx, cy, "quick0")
            DockOrder.ITEM_QUICK1 -> drawRightKey(canvas, cx, cy, "quick1")
        }
    }

    // ================================================================
    // 底板
    // ================================================================

    /**
     * Dock 通栏玻璃底。
     *
     * 画板上这条带子是一条**满宽的浅色玻璃**，顶沿有一道 1~2px 亮边
     * （实测 y968 处 #EFF5FB），下面没有阴影 —— 它贴屏幕底边，阴影无处可落。
     *
     * 圆角仍然走设置项 `dockCornerRadius`（默认 0 = 方角）：只有**上面两个角**
     * 参与圆角，下面两个是直角（贴底后若圆下角，屏幕最底部两侧会露出三角缺口，
     * 第183轮的结论继续有效）。
     */
    /** Dock 在设计坐标系下的纵向起点，使玻璃背景采样与屏幕底部壁纸对齐。 */
    private fun dockGlassOffsetY(): Float = (com.cardesk.launcher.core.Design.H - height).coerceAtLeast(0f)

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        val radius = cornerRadius.coerceIn(0f, minOf(h, w) / 2f)
        val rect = RectF(0f, 0f, w, h)
        val oy = dockGlassOffsetY()
        if (radius <= 0f) {
            Glass.card(canvas, rect, 0f, Skin.NAV_BG, shadow = false, sheen = true, stroke = Skin.GLASS_STROKE, offsetY = oy)
            return
        }
        // 有圆角时：先裁成「上圆下方」的形状，再让玻璃按整块矩形去画
        // （clipPath 没有抗锯齿，只影响最外侧那一圈像素，实际不可见）。
        bgPath.reset()
        bgPath.addRoundRect(
            rect,
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )
        canvas.save()
        canvas.clipPath(bgPath)
        Glass.card(canvas, rect, 0f, Skin.NAV_BG, shadow = false, sheen = true, stroke = 0, offsetY = oy)
        canvas.restore()
        stroke.color = Skin.GLASS_STROKE
        stroke.strokeWidth = 1f
        canvas.drawPath(bgPath, stroke)
    }

    // ================================================================
    // 左段：返回 / 首页 / 网格 / 分屏 / 360
    // ================================================================

    /** 返回键（最左侧：简洁向左折线箭头，与网格 / 分屏 / 360 同 72×72 命中区）。 */
    private fun drawBack(canvas: Canvas, cx: Float, cy: Float) {
        withPress(canvas, "back", cx, cy) {
            stroke.color = Skin.TEXT_SOFT
            stroke.strokeWidth = 3.6f
            val p = Path().apply {
                moveTo(cx + 8f, cy - 17f)
                lineTo(cx - 9f, cy)
                lineTo(cx + 8f, cy + 17f)
            }
            canvas.drawPath(p, stroke)
        }
        registerIconHit("back", cx, cy, NAV_TILE)
    }

    /**
     * 首页键（画板：72×72 圆角 20，accent-soft 底 + accent 房子）
     *
     * 当前页高亮：`activePage == 0` 时是 accent-soft 底 + accent 图标；
     * 不在首页时降为普通玻璃底 + 弱色图标 —— 高亮语义（原先由
     * `Skin.ACCENT_LIGHT` 描边表达）保持不变，只是换成画板的「块状高亮」。
     */
    private fun drawHome(canvas: Canvas, left: Float, cy: Float) {
        val size = HOME_SIZE
        val active = activePage == 0
        val tile = RectF(left, cy - size / 2f, left + size, cy + size / 2f)
        withPress(canvas, "home", tile.centerX(), cy) {
            Glass.card(
                canvas, tile, HOME_RADIUS,
                if (active) Skin.ACCENT_SOFT else Skin.surfaceStrong,
                shadow = false, sheen = true, stroke = Skin.GLASS_STROKE,
                offsetY = dockGlassOffsetY(),
            )
            stroke.color = if (active) Skin.ACCENT else Skin.TEXT_SOFT
            stroke.strokeWidth = 2.6f
            HomeHouseIcon.draw(canvas, tile.centerX(), cy, HOME_ICON_UNIT, stroke)
        }
        // 命中区：整块 + 上下留一点余量（≥56，画板 72 已满足）。
        hit["home"] = RectF(left, cy - HIT_MIN / 2f, left + size, cy + HIT_MIN / 2f)
    }

    /**
     * 「应用」键（网格图标）。
     *
     * 画板上非活跃态是**裸图标**（无底板），所以底板只在抽屉打开
     * （[appsOpen] = true）时出现 —— 状态反馈不能因为去了底板就丢掉。
     */
    private fun drawApps(canvas: Canvas, cx: Float, cy: Float) {
        val active = appsOpen
        withPress(canvas, "apps", cx, cy) {
            if (active) {
                val tile = RectF(cx - NAV_TILE / 2f, cy - NAV_TILE / 2f, cx + NAV_TILE / 2f, cy + NAV_TILE / 2f)
                Glass.card(
                    canvas, tile, HOME_RADIUS, Skin.ACCENT_SOFT,
                    shadow = false, sheen = true, stroke = Skin.GLASS_STROKE,
                    offsetY = dockGlassOffsetY(),
                )
            }
            fill.color = if (active) Skin.ACCENT else Skin.TEXT_SOFT
            // 3×3 宫格：每格 12×12、间距 6 —— 总宽 48，落在 72 的键面里。
            for (row in 0..2) for (col in 0..2) {
                val left = cx - 24f + col * 18f
                val top = cy - 24f + row * 18f
                canvas.drawRoundRect(RectF(left, top, left + 12f, top + 12f), 3f, 3f, fill)
            }
        }
        registerIconHit("apps", cx, cy, NAV_TILE)
    }

    /** 分屏键（画板：两个并排的圆角矩形，无底板）。 */
    private fun drawSplit(canvas: Canvas, cx: Float, cy: Float) {
        withPress(canvas, "split", cx, cy) {
            stroke.color = Skin.TEXT_SOFT
            stroke.strokeWidth = 3.2f
            r.set(cx - 24f, cy - 20f, cx - 2f, cy + 20f)
            canvas.drawRoundRect(r, 6f, 6f, stroke)
            r.set(cx + 2f, cy - 20f, cx + 24f, cy + 20f)
            canvas.drawRoundRect(r, 6f, 6f, stroke)
        }
        registerIconHit("split", cx, cy, NAV_TILE)
    }

    /** 360 键（画板：圆角框 + 「360」字，无底板）。 */
    private fun draw360(canvas: Canvas, cx: Float, cy: Float) {
        withPress(canvas, "360", cx, cy) {
            // 圆角 10、1.8px 描边：日间模式下这层浅蓝在白底上看不见，沿用主题弱文字色。
            stroke.color = if (Skin.isNight) Skin.TEXT else Skin.TEXT_SOFT
            stroke.strokeWidth = 2.6f
            r.set(cx - 28f, cy - 20f, cx + 28f, cy + 20f)
            canvas.drawRoundRect(r, 14f, 14f, stroke)
            text.color = stroke.color
            text.textSize = 22f
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("360", cx, cy + 8f, text)
        }
        registerIconHit("360", cx, cy, NAV_TILE)
    }

    /**
     * 裸图标键的统一命中区：**不小于 56×56**（设计稿视觉 40）。
     *
     * 用户明确要求「画板视觉 40~56，点击区要放大到 56，用透明命中盒」——
     * 视觉不放大，命中盒放大，手不用瞄得那么准。
     */
    private fun registerIconHit(key: String, cx: Float, cy: Float, size: Float = HIT_MIN) {
        val half = maxOf(size, HIT_MIN) / 2f
        hit[key] = RectF(cx - half, cy - half, cx + half, cy + half)
    }

    // ================================================================
    // 媒体胶囊
    // ================================================================

    /**
     * 媒体胶囊（画板 x365..735 / 高 72 / 圆角 28 / 灰白玻璃）。
     *
     * 内部从左到右：封面 56×56（圆角 14，青绿渐变 + 音符）→ 标题 19 粗 + 副标题 14 弱
     * → ⏮（中心 629）→ 播放（白色实心圆，直径 40，中心 666.5）→ ⏭（中心 705）。
     * 三个键的 x 由**右边缘**反推，和画板一致，也与旧版 `DockMediaLayout` 的
     * 「从右往左量」同一套思路。
     */
    private fun drawMedia(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        withPress(canvas, "media", left + w / 2f, top + h / 2f) {
            drawMediaCard(canvas, left, top, w, h)
        }
        hit["media"] = RectF(left, top, left + w, top + h)
    }

    private fun drawMediaCard(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val cy = top + h / 2f
        r.set(left, top, left + w, top + h)
        Glass.card(canvas, r, MEDIA_RADIUS, Skin.surfaceStrong, shadow = false, sheen = true, stroke = Skin.GLASS_STROKE, offsetY = dockGlassOffsetY())

        // —— 封面（56×56，圆角 14）——
        val coverTop = cy - COVER_SIZE / 2f
        val cover = RectF(left + COVER_PAD, coverTop, left + COVER_PAD + COVER_SIZE, coverTop + COVER_SIZE)
        val art = mediaArtwork
        if (art != null && art.width > 0 && art.height > 0) {
            // 有专辑图 → BitmapShader 单遍圆角绘制，避免 clipPath 模板裁剪闪屏
            val shader = if (coverArtShaderBmp === art && coverArtShader != null) {
                coverArtShader!!
            } else {
                BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    coverArtShaderBmp = art
                    coverArtShader = it
                }
            }
            coverBitmapMatrix.reset()
            coverBitmapMatrix.setScale(cover.width() / art.width.toFloat(), cover.height() / art.height.toFloat())
            coverBitmapMatrix.postTranslate(cover.left, cover.top)
            shader.setLocalMatrix(coverBitmapMatrix)
            coverBitmapPaint.shader = shader
            canvas.drawRoundRect(cover, COVER_RADIUS, COVER_RADIUS, coverBitmapPaint)
        } else {
            // 设计稿：青绿渐变底 + 白色音符（渐变端点取自 ACCENT / ACCENT_LIGHT）
            fill.shader = coverShader()
            canvas.drawRoundRect(cover, COVER_RADIUS, COVER_RADIUS, fill)
            fill.shader = null
            stroke.color = ON_ACCENT
            stroke.strokeWidth = 2.2f
            val nx = cover.centerX() + 2f
            val ny = cover.centerY()
            canvas.drawLine(nx + 4f, ny - 11f, nx + 4f, ny + 5f, stroke)
            canvas.drawCircle(nx - 2f, ny + 7f, 4.6f, stroke)
            canvas.drawLine(nx + 4f, ny - 11f, nx + 12f, ny - 7f, stroke)
        }

        // —— 标题 / 副标题（滑动时整体位移 + 露出预览文案）——
        val textLeft = left + COVER_PAD + COVER_SIZE + MEDIA_TEXT_GAP
        val textW = (left + w - MEDIA_SKIP_FROM_RIGHT - 18f) - textLeft
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.isFakeBoldText = false
        canvas.save()
        canvas.translate(swipeOffset, 0f)
        text.textSize = 19f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.color = Skin.TEXT
        canvas.drawText(clip(mediaTitle, text, textW), textLeft, cy - 5f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 14f
        text.color = Skin.TEXT_SOFT
        canvas.drawText(clip(mediaArtist, text, textW), textLeft, cy + 19f, text)
        canvas.restore()

        if (swipeFraction > 0f) {
            text.textAlign = Paint.Align.LEFT
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = 19f
            text.color = Skin.TEXT
            canvas.save()
            canvas.translate(-swipeOffset, 0f)
            canvas.drawText(clip(swipePreviewTitle, text, textW), textLeft, cy - 5f, text)
            text.typeface = Typeface.DEFAULT
            text.textSize = 14f
            text.color = Skin.TEXT_SOFT
            canvas.drawText(clip(swipePreviewArtist, text, textW), textLeft, cy + 19f, text)
            canvas.restore()
        }

        // —— 播放键：白色实心圆 40（画板 647..686 / 1004..1043）——
        val playCx = left + w - MEDIA_PLAY_FROM_RIGHT
        val nextCx = left + w - MEDIA_NEXT_FROM_RIGHT
        val prevCx = left + w - MEDIA_PREV_FROM_RIGHT
        withPress(canvas, "play", playCx, cy) {
            fill.color = Skin.surfaceStrong
            canvas.drawCircle(playCx, cy, PLAY_BTN / 2f, fill)
            fill.color = Skin.TEXT
            if (mediaPlaying) {
                // 暂停：两根竖条
                canvas.drawRoundRect(RectF(playCx - 6f, cy - 9f, playCx - 6f + 5f, cy + 9f), 2f, 2f, fill)
                canvas.drawRoundRect(RectF(playCx + 1f, cy - 9f, playCx + 1f + 5f, cy + 9f), 2f, 2f, fill)
            } else {
                // 播放：朝右的三角形（视觉重心右移 1px，才不显得偏左）
                val p = Path().apply {
                    moveTo(playCx - 5f, cy - 8f)
                    lineTo(playCx + 9f, cy)
                    lineTo(playCx - 5f, cy + 8f)
                    close()
                }
                canvas.drawPath(p, fill)
            }
        }
        withPress(canvas, "mediaNext", nextCx, cy) { skipIcon(canvas, nextCx, cy, next = true) }
        withPress(canvas, "mediaPrev", prevCx, cy) { skipIcon(canvas, prevCx, cy, next = false) }

        // —— 播放进度：只在真的有进度时画一条 3px 的细线（画板静止态没有它）——
        if (mediaProgress > 0f) {
            fill.color = Skin.ACCENT
            canvas.drawRoundRect(
                RectF(left + COVER_PAD, top + h - 5f, left + COVER_PAD + (w - COVER_PAD * 2) * mediaProgress, top + h - 2f),
                2f, 2f, fill,
            )
        }

        // —— 三个键的命中区（纵向撑满胶囊，横向 56）——
        val keyTop = cy - HIT_MIN / 2f
        val keyBottom = cy + HIT_MIN / 2f
        hit["play"] = RectF(playCx - HIT_MIN / 2f, keyTop, playCx + HIT_MIN / 2f, keyBottom)
        hit["mediaNext"] = RectF(nextCx - HIT_MIN / 2f, keyTop, nextCx + HIT_MIN / 2f, keyBottom)
        hit["mediaPrev"] = RectF(prevCx - HIT_MIN / 2f, keyTop, prevCx + HIT_MIN / 2f, keyBottom)
    }

    // ================================================================
    // 电源
    // ================================================================

    /** 电源键（画板：中心 x≈805，只有一个描边图标，无底板）。 */
    private fun drawPower(canvas: Canvas, cx: Float, cy: Float) {
        val on = hvac?.power == true
        withPress(canvas, "power", cx, cy) {
            stroke.color = if (on) Skin.ACCENT else Skin.TEXT_SOFT
            stroke.strokeWidth = 3.4f
            r.set(cx - 20f, cy - 21f, cx + 20f, cy + 19f)
            canvas.drawArc(r, 300f, 300f, false, stroke)
            canvas.drawLine(cx, cy - 28f, cx, cy - 4f, stroke)
        }
        // 命中区 56×56（视觉 28，点击区放大）。
        hit["power"] = RectF(cx - NAV_TILE / 2f, cy - NAV_TILE / 2f, cx + NAV_TILE / 2f, cy + NAV_TILE / 2f)
    }

    // ================================================================
    // 温区胶囊
    // ================================================================

    /**
     * 主驾 / 副驾温区胶囊（画板 x845..1050 / x1166..1373，高 72、圆角 28）。
     *
     * 内部：`−`（距左 15）· 温度（26 粗）与 `°`（13）居中 · 下方 11px「主驾 / 副驾」· `+`（距右 14）。
     * 注意这三个数字都是**画板实测**：温度是 26 而不是旧版的 50，
     * 因为新版把「加减」并进了胶囊内部，数字必须让位。
     */
    private fun drawTempPill(canvas: Canvas, left: Float, cy: Float, w: Float, passenger: Boolean) {
        val rect = RectF(left, cy - TEMP_PILL_H / 2f, left + w, cy + TEMP_PILL_H / 2f)
        Glass.card(canvas, rect, TEMP_PILL_RADIUS, Skin.surfaceStrong, shadow = false, sheen = true, stroke = Skin.GLASS_STROKE, offsetY = dockGlassOffsetY())

        val cx = rect.centerX()
        val state = hvac
        val value = if (passenger) state?.tempPassenger ?: 23.5f else state?.tempDriver ?: 23.5f
        val shown = formatTemp(value)

        // —— 温度数字 + °（作为一组居中；放不下就等比缩小）——
        val numSize = 26f
        val degSize = 13f
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = numSize
        var numW = text.measureText(shown)
        text.textSize = degSize
        var degW = text.measureText("°")
        var groupW = numW + degW
        var k = 1f
        // 数字区可用宽度 = 胶囊宽 − 两端各 56 的命中区 − 8 的呼吸位
        // （命中区不再围着加减键的圆心，而是从胶囊两端各切 56，见下面的 hit 赋值）。
        val maxGroup = w - 2f * HIT_MIN - 8f
        if (groupW > maxGroup && groupW > 0f) {
            k = maxGroup / groupW
            text.textSize = numSize * k
            numW = text.measureText(shown)
            text.textSize = degSize * k
            degW = text.measureText("°")
            groupW = numW + degW
        }
        val startX = cx - groupW / 2f

        val pulse = if (passenger) tempPassengerPulse.scale else tempDriverPulse.scale
        canvas.save()
        if (pulse != 1f) canvas.scale(pulse, pulse, cx, cy)
        text.textSize = numSize * k
        text.color = Skin.TEXT
        // 数字基线：画板上数字 bbox 1004..1023、中心 1013.5（相对 1024 偏上 10.5），
        // 26px 字的视觉中心大约在基线上方 9.5 ⇒ 基线落在 cy - 1。
        canvas.drawText(shown, startX, cy - 1f, text)
        text.textSize = degSize * k
        text.color = Skin.TEXT_SOFT
        canvas.drawText("°", startX + numW, cy - 11f, text)
        canvas.restore()

        // —— 主驾 / 副驾（11px 弱字，居中在数字下方）——
        text.typeface = Typeface.DEFAULT
        text.textSize = 14f
        text.color = Skin.TEXT_DIM
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(if (passenger) "副驾" else "主驾", cx, cy + 20f, text)

        // —— 加减键（画板：− 距左 14、+ 距右 17，统一取 16）——
        val minusCx = left + TEMP_ARROW_INSET
        val plusCx = left + w - TEMP_ARROW_INSET
        val minusKey = if (passenger) "tempP-" else "temp-"
        val plusKey = if (passenger) "tempP+" else "temp+"
        withPress(canvas, minusKey, minusCx, cy) { plusMinus(canvas, minusCx, cy, plus = false) }
        withPress(canvas, plusKey, plusCx, cy) { plusMinus(canvas, plusCx, cy, plus = true) }
        // 命中区：**从胶囊两端各切 56 宽**、纵向撑满 72，而不是在圆心周围画 56 的方框。
        // 为什么：两个圆心只隔 ~170，若各自套一个 56 的盒子，中间会叠出 30 多 px，
        // 而 `hit` 是按插入顺序取第一个命中的 —— 叠住的那段永远只归减号，加号会「半失灵」。
        // 从两端切分后两块各 56 宽且互不重叠，落点稳定。
        hit[minusKey] = RectF(left, cy - TEMP_PILL_H / 2f, left + HIT_MIN, cy + TEMP_PILL_H / 2f)
        hit[plusKey] = RectF(left + w - HIT_MIN, cy - TEMP_PILL_H / 2f, left + w, cy + TEMP_PILL_H / 2f)
    }

    /**
     * 温度调节按钮的图形（第三十七轮：由左右箭头改成 **「−」/「+」**）。
     *
     * 用户要求：Dock 空调的温度调整按钮改成 “+/-” 按钮。
     * 之前画的是一对左右尖角，在深色玻璃底上不太像「加减」，
     * 也和中间那组温度数字的语义对不上；现在就是标准的减号 / 加号。
     */
    private fun plusMinus(canvas: Canvas, cx: Float, cy: Float, plus: Boolean) {
        stroke.color = Skin.TEXT_SOFT
        stroke.strokeWidth = 2.8f
        stroke.strokeCap = Paint.Cap.ROUND
        val half = 9f
        canvas.drawLine(cx - half, cy, cx + half, cy, stroke)
        if (plus) canvas.drawLine(cx, cy - half, cx, cy + half, stroke)
    }

    /**
     * `DesignDock.format(float)`
     * 温度 ≤ 0 时按 23.5 处理，并且**固定一位小数**。
     */
    private fun formatTemp(value: Float): String {
        val v = if (value <= 0f) 23.5f else value
        return String.format(java.util.Locale.US, "%.1f", v)
    }

    // ================================================================
    // 风量
    // ================================================================

    /**
     * 风量键（画板 x1082..1138 / 56×56 / 圆角 18 / 白色玻璃方块）。
     *
     * 画板上的图标是**「风」字形**（三条横线带卷曲端头），
     * 不是旧版的「三叶风扇 + 7 段档位条」。旧版的 7 段档位条在这里没有位置
     * （56 的方块塞不下 7 段），所以档位改成**颜色 + 变化脉冲**表达：
     * 开了风（或 AUTO）图标就是强调色，换档时图标会「鼓」一下。
     */
    private fun drawFan(canvas: Canvas, left: Float, top: Float, size: Float) {
        val state = hvac
        val level = state?.fanLevel ?: 0
        val auto = state?.fanAuto == true
        val on = level > 0 || auto
        val cx = left + size / 2f
        val cy = top + size / 2f
        val rect = RectF(left, top, left + size, top + size)

        withPress(canvas, "fan", cx, cy) {
            Glass.card(canvas, rect, FAN_RADIUS, Skin.surfaceStrong, shadow = false, sheen = true, stroke = Skin.GLASS_STROKE, offsetY = dockGlassOffsetY())
            val glyphColor = if (on) Skin.ACCENT else Skin.TEXT_SOFT
            val pulse = fanPulse.scale
            canvas.save()
            if (pulse != 1f) canvas.scale(pulse, pulse, cx, cy)
            fanGlyph(canvas, cx, cy, glyphColor, level, auto)
            canvas.restore()
        }
        hit["fan"] = RectF(cx - FAN_SIZE / 2f, cy - FAN_SIZE / 2f, cx + FAN_SIZE / 2f, cy + FAN_SIZE / 2f)
    }

    /**
     * 风量键图标：**圆罩 + 三片扇叶 + 轮毂**（第246轮按用户「中间那个空调图标
     * 不明所以」重画）。
     *
     * 旧版画的是三根带卷钩的横线（airflow），56 的键上读不出「风量」，
     * 用户直接问「这是什么」。现在改成一眼能认的**风扇**：
     * 外圈 Ø23.2 的细描边、三片 120° 均分的实心扇叶（外缘 r9.1、
     * 内缘 r2.9、扫过 44°）、中心 r3 的轮毂（用键底色挖空，把三片叶子分开）。
     *
     * 颜色只有两种：开风（或 AUTO）= 强调色，关 = 弱字色 —— 与旧版语义一致。
     */
    private fun fanGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int, level: Int, auto: Boolean) {
        // 图标 = 设计板 Dock 里那枚「风」：三条横线，上条右端往上卷、下条左端往下卷。
        // 第 246 轮先试过「圆罩 + 四片楔形扇叶」，56px 上读成十字/警示牌，用户否掉了；
        // 改回画板原样的「风」，档位单独用下面一排点表示 —— 图标负责「是什么」，
        // 点负责「几档风」，两者不抢。
        stroke.color = color
        stroke.strokeWidth = 3.6f
        stroke.strokeCap = Paint.Cap.ROUND

        // 三条线整体上移 4px，把下半部分让给档位点。
        val gy = cy - 4f
        val top = Path()
        top.moveTo(cx - 15f, gy - 10f)
        top.lineTo(cx + 4f, gy - 10f)
        top.cubicTo(cx + 11.5f, gy - 10f, cx + 11.5f, gy - 20f, cx + 3f, gy - 20f)
        canvas.drawPath(top, stroke)

        canvas.drawLine(cx - 12f, gy, cx + 12f, gy, stroke)

        val bottom = Path()
        bottom.moveTo(cx + 15f, gy + 10f)
        bottom.lineTo(cx - 4f, gy + 10f)
        bottom.cubicTo(cx - 11.5f, gy + 10f, cx - 11.5f, gy + 20f, cx - 3f, gy + 20f)
        canvas.drawPath(bottom, stroke)

        // 档位点：7 档，亮到第 level 颗；风关着（0 档且非 AUTO）时整排不画 ——
        // 一排灰点会被读成「这里坏了」，而不是「0 档」。
        val shown = if (auto) 7 else level.coerceIn(0, 7)
        if (shown <= 0) return
        val dotOn = if (auto) CardStyle.scaleAlpha(color, 0.75f) else color
        val dotOff = CardStyle.scaleAlpha(Skin.TEXT_SOFT, 0.55f)
        val pitch = 6.4f
        val startX = cx - pitch * 3f
        for (i in 0 until 7) {
            fill.color = if (i < shown) dotOn else dotOff
            canvas.drawCircle(startX + i * pitch, cy + 27f, 2.3f, fill)
        }
    }

    // ================================================================
    // A/C · AUTO
    // ================================================================

    /**
     * A/C 与 AUTO 胶囊（高 56、圆角 22）。
     *
     * 底板与右侧空调/功能键保持一致的 `Skin.surfaceStrong` 玻璃面，
     * 通过文字颜色区分开关态（开 = `Skin.ACCENT`，关 = `Skin.TEXT_SOFT`），
     * 避免 AUTO 未开启时因特殊底色显得与其它图标风格不统一。
     */
    private fun drawClimateChip(canvas: Canvas, left: Float, cy: Float, w: Float, label: String, active: Boolean) {
        val rect = RectF(left, cy - CLIMATE_CHIP_H / 2f, left + w, cy + CLIMATE_CHIP_H / 2f)
        withPress(canvas, label, rect.centerX(), cy) {
            Glass.card(
                canvas, rect, CLIMATE_CHIP_RADIUS,
                Skin.surfaceStrong,
                shadow = false, sheen = true, stroke = Skin.GLASS_STROKE,
                offsetY = dockGlassOffsetY(),
            )
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textSize = 17f
            text.color = if (active) Skin.ACCENT else Skin.TEXT_SOFT
            canvas.drawText(label, rect.centerX(), cy + 6.5f, text)
        }
        // 命中区：横向用**胶囊自身的宽度**（A/C 56、AUTO 64，都 ≥56），纵向放大到 56。
        // 若在这里也套 56 宽的中心盒，A/C 与 AUTO 之间只隔 11，两个盒子会叠 17px，
        // 而 A/C 先插入 ⇒ AUTO 左边缘那条按不动。用自身宽度就天然不重叠。
        hit[label] = RectF(rect.left, cy - HIT_MIN / 2f, rect.right, cy + HIT_MIN / 2f)
    }

    // ================================================================
    // 右侧功能键
    // ================================================================

    /**
     * 右侧 5 枚 46×46（圆角 16）玻璃方块，中心 x = 1627 / 1684 / 1741 / 1798 / 1855。
     *
     * ## 顺序为什么是这样
     *
     * 画板这 5 格是「内循环 / 后除霜 / ✳ / + / +」。我们能渲染的功能键只有
     * `circ / defrostF / defrostR / quick0 / quick1` 五个（配置项里就这些，
     * 不新增也不删除），所以 ✳ 那一格承载**前除霜**（用户原话里前除霜就排在
     * 星号快捷之前），两个 `+` 是自定义快捷槽。
     */
    private fun drawRightKeys(canvas: Canvas, cy: Float) {
        val keys = arrayOf("circ", "defrostR", "defrostF", "quick0", "quick1")
        keys.forEachIndexed { i, key ->
            val cx = dx(RIGHT_FIRST_CX + RIGHT_PITCH * i)
            drawRightKey(canvas, cx, cy, key)
        }
    }

    private fun drawRightKey(canvas: Canvas, cx: Float, cy: Float, key: String) {
        val half = RIGHT_KEY / 2f
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        withPress(canvas, key, cx, cy) {
            // 快捷槽里已经配了应用 → 只画应用图标本身（不套底板，让应用自己的颜色说话）；
            // 空槽 / 功能键 → 玻璃方块底。
            val quick = if (key.startsWith("quick")) quickApps.getOrNull(key.removePrefix("quick").toIntOrNull() ?: 0) else null
            val hasIcon = quick?.icon != null
            if (!hasIcon) {
                Glass.card(canvas, rect, RIGHT_RADIUS, Skin.surfaceStrong, shadow = false, sheen = true, stroke = Skin.GLASS_STROKE, offsetY = dockGlassOffsetY())
            }
            when {
                hasIcon -> drawAppIcon(canvas, quick!!.icon!!, cx, cy, RIGHT_KEY - 16f)
                quick != null && quick.pkg.isNotEmpty() -> {
                    // 有包名但取不到图标 → 首字占位
                    text.textAlign = Paint.Align.CENTER
                    text.textSize = 22f
                    text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    text.color = Skin.TEXT_SOFT
                    canvas.drawText(quick.label.take(1), cx, cy + 7f, text)
                }
                key.startsWith("quick") -> {
                    // 空槽：一个「+」
                    stroke.color = Skin.TEXT_SOFT
                    stroke.strokeWidth = 2.4f
                    canvas.drawLine(cx - 12f, cy, cx + 12f, cy, stroke)
                    canvas.drawLine(cx, cy - 12f, cx, cy + 12f, stroke)
                }
                key == "circ" -> circulationGlyph(canvas, cx, cy)
                key == "defrostR" -> defrostGlyph(canvas, cx, cy, front = false)
                key == "defrostF" -> defrostGlyph(canvas, cx, cy, front = true)
            }
        }
        hit[key] = RectF(cx - RIGHT_KEY / 2f, cy - RIGHT_KEY / 2f, cx + RIGHT_KEY / 2f, cy + RIGHT_KEY / 2f)
    }

    /**
     * 内外循环：两个首尾相接的圆弧 + 箭头（画板上的 refresh 形）。
     *
     * 内循环（`hvac.circulation == 0`）用强调色，外循环用弱色 —— 与旧版语义一致。
     */
    private fun circulationGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val inner = hvac?.circulation == 0
        stroke.color = if (inner) Skin.ACCENT else Skin.TEXT_SOFT
        stroke.strokeWidth = 2.6f
        r.set(cx - 17f, cy - 14f, cx + 17f, cy + 14f)
        canvas.drawArc(r, 30f, 140f, false, stroke)
        canvas.drawArc(r, 210f, 140f, false, stroke)
        // 箭头（向右上 / 向左下），与圆弧端头接上
        val p = Path().apply {
            moveTo(cx + 15f, cy - 3f)
            lineTo(cx + 15f, cy - 11f)
            lineTo(cx + 7f, cy - 11f)
        }
        canvas.drawPath(p, stroke)
        val q = Path().apply {
            moveTo(cx - 15f, cy + 3f)
            lineTo(cx - 15f, cy + 11f)
            lineTo(cx - 7f, cy + 11f)
        }
        canvas.drawPath(q, stroke)
    }

    /**
     * 除霜：一个圆角矩形（风挡）+ 三条气流线。
     *
     * 后除霜的线是**竖直**的，前除霜带一点斜度（与旧版一致：前除霜气流朝外散）。
     */
    private fun defrostGlyph(canvas: Canvas, cx: Float, cy: Float, front: Boolean) {
        val on = if (front) hvac?.defrostFront == true else hvac?.defrostRear == true
        stroke.color = if (on) Skin.ACCENT else Skin.TEXT_SOFT
        stroke.strokeWidth = 2.4f
        r.set(cx - 20f, cy - 19f, cx + 20f, cy + 4f)
        canvas.drawRoundRect(r, 7f, 7f, stroke)
        for (i in 0..2) {
            val x0 = cx - 12f + i * 12f
            if (front) {
                canvas.drawLine(x0, cy + 8f, x0 - 3.5f, cy + 16f, stroke)
            } else {
                canvas.drawLine(x0, cy + 8f, x0, cy + 16f, stroke)
            }
        }
    }

    /**
     * 画应用图标（按 [ScenePage.drawIcon] 同一套等比缩放逻辑）。
     */
    private fun drawAppIcon(canvas: Canvas, icon: android.graphics.drawable.Drawable, cx: Float, cy: Float, size: Float) {
        val iw = icon.intrinsicWidth
        val ih = icon.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            icon.setBounds(
                (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                (cx + size / 2f).toInt(), (cy + size / 2f).toInt(),
            )
            icon.draw(canvas)
            return
        }
        val scale = minOf(size / iw, size / ih)
        val w = iw * scale
        val h = ih * scale
        icon.setBounds(
            (cx - w / 2f).toInt(), (cy - h / 2f).toInt(),
            (cx + w / 2f).toInt(), (cy + h / 2f).toInt(),
        )
        icon.draw(canvas)
    }

    /**
     * 按下时把该按键缩放到 [MotionSpec.PRESS_SCALE]，抬起回 1.0。
     *
     * 只缩**这一个键**（围绕它自己的中心），不是缩整条 Dock ——
     * 整条一起缩的话，视觉上像是整排控件在动，看不出是「按到了哪个」。
     */
    private fun withPress(canvas: Canvas, key: String, cx: Float, cy: Float, block: () -> Unit) {
        val s = if (pressedKey == key) pressScale else 1f
        canvas.save()
        if (s != 1f) canvas.scale(s, s, cx, cy)
        block()
        canvas.restore()
    }

    /**
     * 切歌按钮的图形（第三十七轮新增）。
     *
     * - 下一首：三角形朝右 + 右边一条竖条；
     * - 上一首：左边一条竖条 + 三角形朝左。
     */
    private fun skipIcon(canvas: Canvas, cx: Float, cy: Float, next: Boolean) {
        fill.color = Skin.TEXT_SOFT
        // 画板：切歌图标高约 26、竖条 3.6 宽，和 40 的播放圆协调。
        val triH = 11f
        val barW = 3.4f
        if (next) {
            canvas.drawRoundRect(RectF(cx + 5f, cy - triH, cx + 5f + barW, cy + triH), 1.7f, 1.7f, fill)
            val p = Path().apply {
                moveTo(cx - 10f, cy - triH)
                lineTo(cx + 2f, cy)
                lineTo(cx - 10f, cy + triH)
                close()
            }
            canvas.drawPath(p, fill)
        } else {
            canvas.drawRoundRect(RectF(cx - 8f, cy - triH, cx - 8f + barW, cy + triH), 1.7f, 1.7f, fill)
            val p = Path().apply {
                moveTo(cx + 10f, cy - triH)
                lineTo(cx - 2f, cy)
                lineTo(cx + 10f, cy + triH)
                close()
            }
            canvas.drawPath(p, fill)
        }
    }

    private fun clip(s: String, paint: Paint, max: Float): String =
        com.cardesk.launcher.core.TextClip.ellipsize(s, { paint.measureText(it) }, max)

    // --------------------------------------------------------------- 按压反馈

    private fun startPress(key: String?) {
        pressedKey = key
        pressAnimator?.cancel()
        // 排障场景：「按压动画没触发 / 按键没高亮」——按压动画每帧都会走，用 DEBUG 不落盘。
        Logx.d(LogTags.DOCK, "Dock 按压动画开始", "key" to (key ?: ""), "label" to keyLabel(key ?: ""))
        pressAnimator = ValueAnimator.ofFloat(pressScale, PRESS_DOWN_SCALE).apply {
            duration = PRESS_DOWN_MS
            // 按下用标准减速：起步快、尾巴长 —— 手指还没抬起来就已经看到缩放了。
            interpolator = Motion.STANDARD
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }.also { it.start() }
    }

    private fun endPress() {
        pressedKey = null
        pressAnimator?.cancel()
        Logx.d(LogTags.DOCK, "Dock 按压动画结束")
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1f).apply {
            duration = PRESS_UP_MS
            // 松手带回弹：略微冲过 1 再回来，按键才「活」。
            interpolator = Motion.REBOUND
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }.also { it.start() }
    }

    /**
     * 数值脉冲：数值变了就短暂「鼓一下」。
     *
     * 温度 / 风量都是**自绘**的，改完只能硬切 —— 数字「啪」地跳过去，
     * 余光根本注意不到「这里变了」。这里在值变化时让它放大一下再回来
     * （1 → [MotionSpec.STATE_BUMP] → 1），让「这里变了」这件事被看见。
     *
     * 比例与时长直接复用 [MotionSpec.STATE_BUMP]：数值变化和状态切换在车机上
     * 都是同一类「要确认一下」的事，不另起一套数字，全 App 的手感才是一套。
     */
    private inner class ValuePulse {
        /** 当前缩放（1 = 原样）。 */
        var scale = 1f
            private set

        private var animator: ValueAnimator? = null

        /** 触发一次脉冲。 */
        fun fire() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(1f, MotionSpec.STATE_BUMP, 1f).apply {
                duration = MotionSpec.STATE_BUMP_DURATION
                interpolator = Motion.STANDARD
                addUpdateListener {
                    scale = it.animatedValue as Float
                    invalidate()
                }
            }.also { it.start() }
        }

        /** 视图离开窗口时停掉动画，避免空转重绘。 */
        fun cancel() {
            animator?.cancel()
            animator = null
            scale = 1f
        }
    }

    /**
     * Dock 按键的可读名（真机 grep 时直接看中文，不用对照代码里的 key）。
     */
    private fun keyLabel(key: String): String = when (key) {
        "back" -> "返回"
        "home" -> "首页"
        "apps" -> "应用"
        "split" -> "分屏"
        "360" -> "360"
        "media" -> "媒体卡"
        "play" -> "播放键"
        "mediaNext" -> "下一首"
        "mediaPrev" -> "上一首"
        "power" -> "电源"
        "fan" -> "风量"
        "A/C" -> "A/C"
        "AUTO" -> "AUTO"
        "circ" -> "内外循环"
        "defrostF" -> "前除霜"
        "defrostR" -> "后除霜"
        "temp-" -> "主驾温度减"
        "temp+" -> "主驾温度加"
        "tempP-" -> "副驾温度减"
        "tempP+" -> "副驾温度加"
        "quick0" -> "快捷应用 1"
        "quick1" -> "快捷应用 2"
        // 第九十六轮槽位从 3 减到 2：quick2 不再存在。
        else -> key
    }

    // --------------------------------------------------------------- 长按

    private fun scheduleLongPress(key: String) {
        cancelPendingLongPress()
        longPressKey = key
        longPressFired = false
        main.postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun cancelPendingLongPress() {
        main.removeCallbacks(longPressRunnable)
        longPressKey = null
    }

    private fun fireLongPress(key: String?) {
        if (key == null || longPressFired) return
        longPressFired = true
        // 排障场景：「Dock 长按没触发」——长按动作必须落盘。
        Logx.event(
            LogTags.DOCK,
            "Dock 按键长按",
            "key" to key,
            "label" to keyLabel(key),
        )
        when (key) {
            "home" -> onHomeLongPress?.invoke()
            "split" -> onSplitLongPress?.invoke()
            "media" -> onMediaLongPress?.invoke()
            // 快捷应用槽位（第五十四轮）：长按 = 配置这一格
            "quick0", "quick1" -> {
                val index = key.removePrefix("quick").toIntOrNull() ?: 0
                Logx.event(
                    LogTags.DOCK,
                    "Dock 快捷应用槽位长按",
                    "index" to index,
                    "pkg" to quickApps.getOrNull(index)?.pkg.orEmpty(),
                )
                onQuickAppLongPress?.invoke(index)
            }
        }
    }

    // --------------------------------------------------------------- 媒体滑动

    /**
     * `DesignDock.ViewOnTouchListenerC05404.move/finish`
     * - 触发阈值：`|dx| >= 8` 且 `|dx| > |dy| * 1.5`
     * - 位移上限：`min(28, 文本区宽度 * 0.16)`
     * - 进度：`min(1, |dx| / 24)`
     * - 方向：`dx < 0` → 下一首，`dx > 0` → 上一首
     */
    private fun updateSwipe(dx: Float) {
        swipeDx = dx
        val dir = if (dx < 0f) 1 else -1
        if (dir != swipeDir) {
            swipeDir = dir
            swipePreviewTitle = if (dir > 0) "下一首" else "上一首"
            swipePreviewArtist = "准备播放"
        }
        val limit = minOf(SWIPE_MAX_OFFSET, SWIPE_TEXT_AREA * SWIPE_AREA_RATIO)
        val fraction = minOf(1f, abs(dx) / SWIPE_PROGRESS_FULL)
        swipeFraction = fraction
        swipeOffset = (if (dx < 0f) -1f else 1f) * limit * fraction
        // 排障场景：「媒体卡滑动没反应」——滑动位移用 DEBUG，不落盘。
        Logx.d(
            LogTags.DOCK,
            "Dock 媒体卡滑动中",
            "dx" to dx,
            "fraction" to fraction,
            "dir" to if (dir > 0) "next" else "prev",
        )
    }

    private fun resetSwipe() {
        swipeTracking = false
        swipeTriggered = false
        swipeDx = 0f
        swipeDir = 0
        swipeOffset = 0f
        swipeFraction = 0f
        swipePreviewTitle = ""
        swipePreviewArtist = ""
        invalidate()
    }

    /** 编辑排序模式下的触摸处理：按住任意控件左右拖动实时换位，抬手持久化并同步全部 Dock 实例。 */
    private fun handleReorderTouch(event: MotionEvent): Boolean {
        val x = event.x
        val w = width.toFloat().takeIf { it > 0f } ?: DESIGN_W
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val slots = lastSlots.ifEmpty { DockOrder.layout(itemOrder, w) }
                val target = slots.firstOrNull { x in (it.left - 6f)..(it.right + 6f) } ?: return true
                draggingId = target.id
                dragTouchOffsetX = x - target.left
                dragPointerX = x
                dragSlotWidth = target.width
                dragChanged = false
                Logx.event(LogTags.DOCK, "开始拖动 Dock 控件", "id" to target.id, "from" to itemOrder.indexOf(target.id))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = draggingId ?: return true
                dragPointerX = x
                val dragW = dragSlotWidth.takeIf { it > 0f } ?: DockOrder.widthOf(id)
                val dragLeft = (dragPointerX - dragTouchOffsetX).coerceIn(8f, (w - dragW - 8f).coerceAtLeast(8f))
                val dragCenterX = dragLeft + dragW / 2f
                val from = itemOrder.indexOf(id)
                val to = DockOrder.targetIndexForCenter(itemOrder, id, dragCenterX, w)
                if (from >= 0 && to >= 0 && to != from) {
                    itemOrder = DockOrder.move(itemOrder, from, to)
                    dragChanged = true
                    Logx.d(LogTags.DOCK, "Dock 控件拖动换位", "id" to id, "from" to from, "to" to to)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val id = draggingId
                val changed = dragChanged
                draggingId = null
                dragChanged = false
                if (changed) {
                    runCatching { Prefs(context.applicationContext).dockOrder = itemOrder }
                    Logx.event(
                        LogTags.DOCK,
                        "保存 Dock 自定义顺序",
                        "id" to (id ?: ""),
                        "order" to itemOrder.joinToString(","),
                    )
                    refreshOrderAll(context)
                } else {
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (reorderMode) return handleReorderTouch(event)
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 第五十六轮：Dock 落在**系统导航栏的触摸区**上（系统 Dock 画在更下层）。
                // 按下就先把事件流锁在这一层，别让根视图的边缘手势把它抢走 ——
                // 抢走的话，一次点击会被当成「从边缘滑入」，把系统自带 Dock 唤出来。
                parent?.requestDisallowInterceptTouchEvent(true)
                // 播放键在媒体卡内部，优先命中（否则会被 media 抢走）
                val key = when {
                    hit["play"]?.contains(x, y) == true -> "play"
                    hit["mediaNext"]?.contains(x, y) == true -> "mediaNext"
                    hit["mediaPrev"]?.contains(x, y) == true -> "mediaPrev"
                    else -> hit.keys.firstOrNull { hit[it]?.contains(x, y) == true }
                }
                longPressFired = false
                if (key == null) return true
                startPress(key)
                if (key == "media") {
                    swipeTracking = true
                    swipeTriggered = false
                    swipeDownX = x
                    swipeDownY = y
                    swipeDir = 0
                    swipeOffset = 0f
                    swipeFraction = 0f
                }
                // 长按调度。
                //
                // 第197轮修掉一个**既有 bug**：媒体卡（`key == "media"`）被上面的
                // `if` 先匹配走了，这里的 `else if` 里那句 `key == "media"` 是**死代码**
                // —— 媒体卡的长按（开媒体浮窗）**从来没生效过**。
                //
                // 以前无所谓：点媒体卡是「进我们自己的音乐页」，长按失效只是少个浮窗；
                // 第197轮点击改成「拉起音乐 App」之后，长按就成了**唯一的替代入口**
                // （想看自己桌面的音乐卡，只能长按），不能是坏的。
                if (key == "home" || key == "split" || key == "media" || key.startsWith("quick")) {
                    scheduleLongPress(key)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeTracking) {
                    val dx = x - swipeDownX
                    val dy = y - swipeDownY
                    if (!swipeTriggered &&
                        abs(dx) >= SWIPE_TRIGGER_PX &&
                        abs(dx) > abs(dy) * SWIPE_AXIS_RATIO
                    ) {
                        swipeTriggered = true
                    }
                    if (swipeTriggered) {
                        // 滑动切歌与「长按开浮窗」互斥 —— 滑动一旦成立就取消长按。
                        cancelPendingLongPress()
                        // 媒体卡滑动切歌优先于根视图的翻页手势（自己消费触摸）
                        parent?.requestDisallowInterceptTouchEvent(true)
                        updateSwipe(dx)
                        invalidate()
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val key = when {
                    hit["play"]?.contains(x, y) == true -> "play"
                    hit["mediaNext"]?.contains(x, y) == true -> "mediaNext"
                    hit["mediaPrev"]?.contains(x, y) == true -> "mediaPrev"
                    else -> hit.keys.firstOrNull { hit[it]?.contains(x, y) == true }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                endPress()
                cancelPendingLongPress()
                if (swipeTracking) {
                    val dx = x - swipeDownX
                    val dy = y - swipeDownY
                    val swiped = swipeTriggered ||
                        (abs(dx) >= SWIPE_TRIGGER_PX && abs(dx) > abs(dy) * SWIPE_AXIS_RATIO)
                    if (swiped) {
                        // 排障场景：「媒体卡左右滑动不切歌」——滑动方向与位移要落盘。
                        Logx.event(
                            LogTags.DOCK,
                            "Dock 媒体卡滑动切歌",
                            "dir" to if (dx < 0f) "next" else "prev",
                            "dx" to dx,
                            "dy" to dy,
                            "title" to mediaTitle,
                        )
                        if (dx < 0f) onMediaNext?.invoke() else onMediaPrev?.invoke()
                        resetSwipe()
                        performClick()
                        return true
                    }
                    if (abs(dx) < SWIPE_TRIGGER_PX && abs(dy) < SWIPE_TRIGGER_PX) {
                        // 第三十七轮：上一首 / 下一首按钮
                        if (hit["mediaNext"]?.contains(x, y) == true) {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 按键点击",
                                "key" to "mediaNext",
                                "label" to keyLabel("mediaNext"),
                                "title" to mediaTitle,
                            )
                            onMediaNext?.invoke()
                            resetSwipe()
                            performClick()
                            return true
                        }
                        if (hit["mediaPrev"]?.contains(x, y) == true) {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 按键点击",
                                "key" to "mediaPrev",
                                "label" to keyLabel("mediaPrev"),
                                "title" to mediaTitle,
                            )
                            onMediaPrev?.invoke()
                            resetSwipe()
                            performClick()
                            return true
                        }
                        // 点在播放键上 → 播放/暂停；点在封面 / 歌名区 → 拉起对应音乐 App（第一九七轮）
                        //
                        // `longPressFired` 判据：长按媒体卡会开媒体浮窗，
                        // 抬手时**不能再**当成点击去拉起 App —— 否则「长按」会同时做两件事。
                        if (hit["play"]?.contains(x, y) == true) {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 按键点击",
                                "key" to "play",
                                "label" to keyLabel("play"),
                                "playing" to mediaPlaying,
                                "title" to mediaTitle,
                            )
                            onPlay?.invoke()
                        } else if (!longPressFired) {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 按键点击",
                                "key" to "media",
                                "label" to keyLabel("media"),
                                "title" to mediaTitle,
                            )
                            // 第一九七轮：这里**不再**跳我们自己的音乐页，
                            // 改成「拉起当前正在使用的音乐 App」。
                            // 上下曲 / 播放三个键在上面各自先判过了，
                            // 走到这里的只可能是封面 / 歌名区。
                            onMediaOpenApp?.invoke()
                        }
                        resetSwipe()
                        performClick()
                        return true
                    }
                    resetSwipe()
                    return true
                }
                if (key != null && !longPressFired) {
                    // 排障场景：「Dock 按键点了没反应」——每个按键的点击都落盘（按键名 + 中文名）。
                    Logx.event(
                        LogTags.DOCK,
                        "Dock 按键点击",
                        "key" to key,
                        "label" to keyLabel(key),
                        "activePage" to activePage,
                        "appsOpen" to appsOpen,
                    )
                    when (key) {
                        "back" -> onBack?.invoke()
                        "home" -> onHome?.invoke()
                        "apps" -> (onAppsDrawer ?: onApps)?.invoke()
                        "split" -> onSplit?.invoke()
                        "360" -> on360?.invoke()
                        "play" -> onPlay?.invoke()
                        "mediaNext" -> onMediaNext?.invoke()
                        "mediaPrev" -> onMediaPrev?.invoke()
                        "power" -> onPower?.invoke()
                        "fan" -> onFan?.invoke()
                        "A/C" -> onAc?.invoke()
                        "AUTO" -> onAuto?.invoke()
                        "circ" -> onCirc?.invoke()
                        "defrostF" -> onDefrost?.invoke(true)
                        "defrostR" -> onDefrost?.invoke(false)
                        // 温度 ±：带上分区与当前温度，方便核对「按了几下」
                        "temp-" -> {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 温度调节",
                                "zone" to "driver",
                                "delta" to -1,
                                "temp" to (hvac?.tempDriver ?: -1f),
                            )
                            onTemp?.invoke(1, -1)
                        }
                        "temp+" -> {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 温度调节",
                                "zone" to "driver",
                                "delta" to 1,
                                "temp" to (hvac?.tempDriver ?: -1f),
                            )
                            onTemp?.invoke(1, 1)
                        }
                        "tempP-" -> {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 温度调节",
                                "zone" to "passenger",
                                "delta" to -1,
                                "temp" to (hvac?.tempPassenger ?: -1f),
                            )
                            onTemp?.invoke(4, -1)
                        }
                        "tempP+" -> {
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 温度调节",
                                "zone" to "passenger",
                                "delta" to 1,
                                "temp" to (hvac?.tempPassenger ?: -1f),
                            )
                            onTemp?.invoke(4, 1)
                        }
                        // 快捷应用（第五十四轮；第九十六轮起 2 个）：槽位下标在 key 里（quick0 / quick1）
                        "quick0", "quick1" -> {
                            val index = key.removePrefix("quick").toIntOrNull() ?: 0
                            val pkg = quickApps.getOrNull(index)?.pkg.orEmpty()
                            Logx.event(
                                LogTags.DOCK,
                                "Dock 快捷应用点击",
                                "index" to index,
                                "pkg" to pkg,
                                "empty" to pkg.isEmpty(),
                            )
                            onQuickApp?.invoke(index, pkg)
                        }
                    }
                    performClick()
                } else if (key == null) {
                    // 排障场景：「Dock 上某个位置点了没反应」——没命中任何按键也要留痕。
                    Logx.d(LogTags.DOCK, "Dock 点击未命中按键", "x" to x, "y" to y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                endPress()
                cancelPendingLongPress()
                resetSwipe()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Dock 显隐切换。
     *
     * 排障场景：「Dock 整条不见了 / 隐藏策略没生效」——
     * 宿主页（LauncherView）负责设置 visibility，这里在真正发生变化时补一条落盘日志。
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        Logx.event(
            LogTags.DOCK,
            "Dock 显隐切换",
            "visible" to (visibility == View.VISIBLE),
            "activePage" to activePage,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 第九十四轮：Dock 铺满屏宽。
        //
        // 以前这里把宽度钉死 1864，而宿主给的是 1872（桌面）/1920（全局 Dock），
        // 于是 Dock 玻璃条两侧永远空一条缝 —— 在其它应用里尤其明显：
        // 左边 24 + 右边 32 露出底层不透明条，看起来像「Dock 没铺满」。
        // 现在按父布局给的实际宽度测量，只保留 112 的固定设计高
        // （第192轮：104 → 112，和系统导航预留带一样高）。
        val w = when (View.MeasureSpec.getMode(widthMeasureSpec)) {
            View.MeasureSpec.EXACTLY,
            View.MeasureSpec.AT_MOST,
            -> View.MeasureSpec.getSize(widthMeasureSpec)

            else -> com.cardesk.launcher.core.Design.W.toInt()
        }
        setMeasuredDimension(w, DOCK_HEIGHT)
    }

    companion object {
        /**
         * Dock 的**设计高**（px）。
         *
         * 第192轮：104 → 112。
         *
         * 为什么必须是 112：全局 Dock 走的是「系统导航预留带」这条路
         * （`NavInsets` 的 RRO 覆盖包把 framework-res 的 `navigation_bar_height*`
         * 改成 112dp），系统因此在屏幕底部留出 **112px**。
         * Dock 本身只有 104 高时，别的 App 让出了 112，而 Dock 只画 104 ——
         * 中间 8px 就是一条「系统留了位、却没人画」的缝。
         * 让 Dock 正好 112，这 8px 才被填上，预留带与 Dock 严丝合缝。
         */
        const val DOCK_HEIGHT = 112

        // ================================================================
        // 第三百轮：设计坐标常量（全部来自 Main.png 的 1:1 实测）
        //
        // 一切 x 都是**画板绝对值**，绘制时经 [dx] 按实际宽度换算；
        // 尺寸（宽 / 高 / 字号）不换算 —— 1872 宽时元素还是那么大，
        // 只是留白等比收一点。
        // ================================================================

        /** 设计稿宽度（布局缩放的分母）。 */
        const val DESIGN_W = 1920f

        /** 首页键：左边距 28、72×72、圆角 20。 */
        const val HOME_LEFT = 28f
        const val HOME_SIZE = 72f
        const val HOME_RADIUS = 20f

        /**
         * 网格 / 分屏 / 360 三枚图标键的中心 x。
         *
         * 第三百二十轮：三枚键从「32 左右的裸字形」改成 72×72 的键面（与首页块同尺寸），
         * 中心随之间距 84 排开（148 / 232 / 316）。
         */
        const val NAV_GRID_CX = 146f
        const val NAV_SPLIT_CX = 228f
        const val NAV_360_CX = 310f

        /** 图标键的键面边长（与首页块同尺寸）。 */
        const val NAV_TILE = 72f

        /** 媒体胶囊：x365、370×72、圆角 28（实测，不是全圆）。 */
        const val MEDIA_LEFT = 356f
        const val MEDIA_W = 390f
        const val MEDIA_H = 72f
        const val MEDIA_RADIUS = 28f

        /** 封面：56×56、圆角 14、距胶囊左 8。 */
        const val COVER_SIZE = 56f
        const val COVER_RADIUS = 14f
        const val COVER_PAD = 8f

        /** 封面与标题之间的间距。 */
        const val MEDIA_TEXT_GAP = 16f

        /** 三个媒体键的圆心距**胶囊右边缘**的距离（画板：705 / 666.5 / 629，右边缘 735）。 */
        const val MEDIA_NEXT_FROM_RIGHT = 30f
        const val MEDIA_PLAY_FROM_RIGHT = 68f
        const val MEDIA_PREV_FROM_RIGHT = 106f

        /** 文本区右边界与「上一首」键之间留的间距（给切歌图标让位）。 */
        const val MEDIA_SKIP_FROM_RIGHT = 124f

        /** 播放键白色实心圆直径（画板 40）。 */
        const val PLAY_BTN = 40f

        /** 电源键中心 x（只有一个描边图标，无底板）。 */
        const val POWER_CX = 792f

        /** 主驾温区胶囊：x845、宽 205、高 72、圆角 28。 */
        const val TEMP_DRIVER_LEFT = 838f
        const val TEMP_DRIVER_W = 205f

        /** 副驾温区胶囊：x1166、宽 207。 */
        const val TEMP_PASS_LEFT = 1135f
        const val TEMP_PASS_W = 207f

        /** 温区胶囊高度。 */
        const val TEMP_PILL_H = 72f
        const val TEMP_PILL_RADIUS = 28f

        /** 温区胶囊里 − / + 的中心距胶囊左右边缘的距离（画板实测 14 / 17，取 16）。 */
        const val TEMP_ARROW_INSET = 16f

        /** 风量键：x1082、56×56、圆角 18。 */
        const val FAN_LEFT = 1053f
        const val FAN_SIZE = 72f
        const val FAN_RADIUS = 24f

        /** A/C：x1416、56 宽；AUTO：x1483、64 宽；都高 44、圆角 22。 */
        const val AC_LEFT = 1352f
        const val AC_W = 56f
        const val AUTO_LEFT = 1418f
        const val AUTO_W = 64f
        const val CLIMATE_CHIP_H = 56f
        const val CLIMATE_CHIP_RADIUS = 22f

        /**
         * 右侧 5 枚功能键：首格中心 1608、步距 64、56×56、圆角 18。
         *
         * 第246轮按用户「右侧图标有点小，不方便操作」放大：
         * 46×46 / 步距 57 → 56×56 / 步距 64，右缘仍落在 1892（= W - 28），
         * 与 A/C·AUTO 组（右到 1547）之间留 33。
         */
        const val RIGHT_FIRST_CX = 1530f
        const val RIGHT_PITCH = 82f
        const val RIGHT_KEY = 72f
        const val RIGHT_RADIUS = 24f

        /**
         * 最小命中区边长（用户要求：画板视觉 40~56，点击区放大到 56）。
         *
         * 用透明命中盒实现 —— 视觉一点不放大，只是 `hit` 里那块 RectF 变大了。
         */
        const val HIT_MIN = 56f

        /**
         * 强调色块上的前景色（封面里的音符）。
         *
         * 出处：设计稿 `Main.png` / `n01-home-night.png` 里封面都是「青绿渐变 + **纯白**音符」，
         * 两套主题都不翻 —— 它压在 accent 上，不属于主题 token 体系。
         */
        const val ON_ACCENT = 0xFFFFFFFF.toInt()

        /**
         * home 键房子图标的**半宽**（像素）。
         *
         * 第七十七轮把原来的「圆环 + 圆点」（外径 28）换成房子，
         * 半宽取 13 ⇒ 宽高都是 26，视觉重量与原来基本一致。
         *
         * 第192轮：13 → 15（跟 Dock 高 104 → 112 同一比例放大）。
         * 第三百轮：72 的方块里房子保持 15（宽高 30），
         * 比旧版 64 方块里的 15 更「松」，与画板一致。
         */
        const val HOME_ICON_UNIT = 15f

        /**
         * 按下缩放到 0.96。
         *
         * 以前这里是 Dock 私有的 0.95，和 App 里其它按压反馈（0.96）对不上 ——
         * 现在直接指向 [MotionSpec.PRESS_SCALE]，全 App 只有一套按压手感。
         */
        const val PRESS_DOWN_SCALE = MotionSpec.PRESS_SCALE

        /** 按下动画 60ms（手指还没抬起来就已经看到缩放了）。 */
        const val PRESS_DOWN_MS = MotionSpec.PRESS_DOWN_DURATION

        /** 抬起回 1.0，动画 90ms（留一点「弹回去」的余韵）。 */
        const val PRESS_UP_MS = MotionSpec.PRESS_UP_DURATION

        /** 系统长按超时（用 `setOnLongClickListener`，默认 500ms）。 */
        const val LONG_PRESS_MS = 500L

        /** 滑动触发阈值 8px。 */
        const val SWIPE_TRIGGER_PX = 8f

        /** 横向必须大于纵向 ×1.5。 */
        const val SWIPE_AXIS_RATIO = 1.5f

        /** 位移上限 min(28, 文本区宽度 × 0.16)。 */
        const val SWIPE_MAX_OFFSET = 28f
        const val SWIPE_AREA_RATIO = 0.16f

        /** 进度 = min(1, |dx| / 24)。 */
        const val SWIPE_PROGRESS_FULL = 24f

        /** 媒体卡文本区宽度（第三百轮：新胶囊里文本区约 190，取 180 与实测接近）。 */
        const val SWIPE_TEXT_AREA = 180f

        /** 活着的 DockBarView 实例（桌面里一份、全局 Dock 窗口里一份）。 */
        private val live =
            java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<DockBarView>>()

        /** 是否处于 Dock 拖动排序编辑态（桌面 Dock 与全局 Dock 共享）。 */
        @Volatile
        var reorderMode: Boolean = false
            private set

        /** 切换 Dock 拖动排序编辑态，并通知所有活着实例重绘。 */
        fun setReorderMode(context: Context, enabled: Boolean) {
            reorderMode = enabled
            val order = runCatching { Prefs(context.applicationContext).dockOrder }.getOrDefault(DockOrder.DEFAULT)
            for (ref in live) {
                val v = ref.get() ?: continue
                v.itemOrder = order
                if (!enabled) v.draggingId = null
                v.invalidate()
            }
            Logx.event(LogTags.DOCK, "Dock 排序编辑态切换", "enabled" to enabled)
        }

        /** 顺序变更后刷新全部活着的 DockBarView 实例（桌面 Dock + 全局 Dock）。 */
        fun refreshOrderAll(context: Context) {
            val order = runCatching { Prefs(context.applicationContext).dockOrder }.getOrDefault(DockOrder.DEFAULT)
            for (ref in live) {
                val v = ref.get() ?: continue
                v.itemOrder = order
                v.invalidate()
            }
        }

        /** 恢复默认 Dock 顺序并落盘，同时刷新所有实例。 */
        fun resetOrder(context: Context) {
            runCatching { Prefs(context.applicationContext).dockOrder = DockOrder.DEFAULT }
            Logx.event(LogTags.DOCK, "恢复默认 Dock 按钮顺序")
            refreshOrderAll(context)
        }

        /** 设置页改完圆角后调用：重新读偏好 + 重绘所有实例（同时刷新槽位条与全局 Dock 宿主圆角）。 */
        fun refreshAll(context: Context) {
            val radius = runCatching {
                Prefs(context.applicationContext).dockCornerRadius.toFloat()
            }.getOrDefault(0f)
            for (ref in live) {
                val v = ref.get() ?: continue
                v.cornerRadius = radius
                v.invalidate()
            }
            NavSlotBarView.refreshAll(context)
            com.cardesk.launcher.core.dock.GlobalDockService.refreshCornerRadius()
        }

    }
}
