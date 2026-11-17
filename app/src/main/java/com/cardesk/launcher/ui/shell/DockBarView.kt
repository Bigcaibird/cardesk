package com.cardesk.launcher.ui.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.DockLayout
import com.cardesk.launcher.core.DockMediaLayout
import com.cardesk.launcher.core.DockQuickApps
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.vehicle.HvacState
import kotlin.math.abs

/**
 * 底部一体化控制栏（布局：铺满屏宽×112（1920 板上 1920 宽），圆角 40，玻璃底 #6b162e2c）。
 *
 * 从左到右：主页 / 应用抽屉 / 分屏 / 360 · 媒体卡(400×88) · 弹性留白 ·
 * 电源 · 主驾温度 −/+ · 风量 7 段 · 副驾温度 −/+ · A/C · AUTO · 内外循环 · 前除霜 · 后除霜。
 *
 * 与对齐的细节
 * - 温度固定一位小数、缺省 23.5
 * - 播放键底色是近白实心圆 `#eef3fa`
 * - 按键按下缩放到 0.96（60ms）、抬起回 1.0（90ms）—— 与全 App 共用 `MotionSpec`
 *   （以前这里是 Dock 私有的 0.95 / 90ms / 140ms，和别处的按压手感对不上）
 * - 媒体卡左右滑动切歌：位移 ≥8px 且横向分量 > 纵向 ×1.5 才触发
 * - 主页键长按 = 原车多任务；分屏键长按 = 切换单画/双画
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
     * 封面容器 `clipToOutline=true`、圆角 **16**
     * 里面放的是专辑图；没有专辑图时才画默认音符占位。
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
        mediaTitle = title
        mediaArtist = artist
        mediaPlaying = playing
        mediaProgress = progress.coerceIn(0f, 1f)
        mediaArtwork = artwork
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

    /**
     * 没有专辑图时的封面底色。
     *
     * 第五十一轮按设计板 `dock` 改成 `--accentSoft` 底 + `--accent` 音符；
     * 之前是蓝色渐变（`#4A9BFF → #1D2A4A`），日间模式下和设计板对不上。
     */
    private val coverFallback = Skin.ACCENT_SOFT

    /**
     * Dock 圆角（设计 px）：构造时从 `Prefs` 读，设置页改了走 [refreshAll]。
     *
     * 第190轮：默认值 36 → 0（用户：「dock栏圆角默认改成0px吧」）。
     * 这个**字段初值**也要跟 `Prefs.dockCornerRadius` 的默认值一致 ——
     * 否则「还没 attach 到窗口」（比如预览渲染、单测里直接 new 出来）的那一瞬间
     * 仍然是 36px 的圆角，跟用户设置里的默认值对不上。
     */
    private var cornerRadius: Float = 0f

    private val bgPath = Path()

    private val r = RectF()
    private val hit = LinkedHashMap<String, RectF>()

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
        live.add(java.lang.ref.WeakReference(this))
        invalidate()
    }

    override fun onDetachedFromWindow() {
        live.removeAll { it.get() == null || it.get() === this }
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
        // 第四十九轮「浮屿」：Dock 底改成**不透明扁平面** + 1px 描边（不再是半透明玻璃）。
        // 第五十轮对齐设计板 s01：Dock 1872×96 / 圆角 36。
        // 第183轮：① 圆角改成设置项（`Prefs.dockCornerRadius`，第190轮默认 0，0..52）；
        //          ② Dock 紧贴屏幕底部，所以**只圆上面两个角**，下面两个是直角
        //             （否则贴底后屏幕最底部两侧会露出三角形缺口）。
        val radius = cornerRadius.coerceIn(0f, minOf(h, w) / 2f)
        val radii = floatArrayOf(
            radius, radius, // 左上
            radius, radius, // 右上
            0f, 0f,         // 右下（贴底）
            0f, 0f,         // 左下（贴底）
        )
        bgPath.reset()
        bgPath.addRoundRect(RectF(0f, 0f, w, h), radii, Path.Direction.CW)
        canvas.drawPath(bgPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = null
            color = Skin.NAV_BG
        })
        canvas.drawPath(bgPath, stroke.apply {
            color = Skin.NAV_STROKE
            strokeWidth = 1.4f
        })
        hit.clear()

        // ——— 左段：四个导航入口 ———
        var x = DockLayout.KEY_LEFT
        val cy = h / 2f
        drawHome(canvas, x, cy, DockLayout.KEY_SLOT)
        x += DockLayout.KEY_SLOT + DockLayout.KEY_GAP
        drawApps(canvas, x, cy, DockLayout.KEY_SLOT)
        x += DockLayout.KEY_SLOT + DockLayout.KEY_GAP
        drawSplit(canvas, x, cy, DockLayout.KEY_SLOT)
        x += DockLayout.KEY_SLOT + DockLayout.KEY_GAP
        draw360(canvas, x, cy, DockLayout.KEY_SLOT)

        // ——— 四段式布局（第五十四轮）———
        //
        // 第五十四轮：**音乐卡与空调簇对调**，段与段之间不再刻意留大空隙，
        // 省下来的宽度给最右边的**快捷应用**段。
        //
        // 「怎么摆」全部交给纯逻辑 [DockLayout.sections]，单测能锁住不压叠；
        // 「每块多宽」由 [climateClusterWidth] 与绘制逐项对应。
        val sections = DockLayout.sections(w, climateClusterWidth())
        drawClimateCluster(canvas, sections.clusterRight)

        val mediaX = sections.mediaLeft
        val mediaTop = (h - DockMediaLayout.CARD_H) / 2f
        val mediaW = sections.mediaWidth
        drawMedia(canvas, mediaX, mediaTop, mediaW, DockMediaLayout.CARD_H)
        hit["media"] = RectF(mediaX, mediaTop, mediaX + mediaW, mediaTop + DockMediaLayout.CARD_H)
        // 第三十九轮：三个键排成「上一首 | 播放暂停 | 下一首」，
        // 播放键放在前后曲**中间**并从 60 缩到 44；前后曲放大到 40，间隙收到 8。
        val keys = DockMediaLayout.keys(mediaW)
        val playLeft = mediaX + keys.playLeft
        val nextLeft = mediaX + keys.nextLeft
        val prevLeft = mediaX + keys.prevLeft
        hit["play"] = RectF(
            playLeft,
            mediaTop + (DockMediaLayout.CARD_H - DockMediaLayout.PLAY_BTN) / 2f,
            playLeft + DockMediaLayout.PLAY_BTN,
            mediaTop + (DockMediaLayout.CARD_H + DockMediaLayout.PLAY_BTN) / 2f,
        )
        hit["mediaNext"] = RectF(
            nextLeft,
            mediaTop + (DockMediaLayout.CARD_H - DockMediaLayout.SKIP_BTN) / 2f,
            nextLeft + DockMediaLayout.SKIP_BTN,
            mediaTop + (DockMediaLayout.CARD_H + DockMediaLayout.SKIP_BTN) / 2f,
        )
        hit["mediaPrev"] = RectF(
            prevLeft,
            mediaTop + (DockMediaLayout.CARD_H - DockMediaLayout.SKIP_BTN) / 2f,
            prevLeft + DockMediaLayout.SKIP_BTN,
            mediaTop + (DockMediaLayout.CARD_H + DockMediaLayout.SKIP_BTN) / 2f,
        )

        // ——— 最右段：快捷应用槽位（第五十四轮）———
        drawQuickApps(canvas, sections)
    }

    /**
     * 快捷应用槽位（第五十四轮）。
     *
     * 每个槽位是一格 [DockLayout.QUICK_TILE] 见方：
     * - 配了应用 → 直接画**真实应用图标**（不套底板，让应用自己的颜色说话）；
     * - 没配 → 画一个圆角方框 + 「+」，点它就是去配置。
     *
     * 槽位之间只有 [DockLayout.QUICK_GAP] 的间隙 —— 整段贴住 Dock 右边缘。
     */
    private fun drawQuickApps(canvas: Canvas, sections: DockLayout.Sections) {
        val cy = height / 2f
        val tile = DockLayout.QUICK_TILE
        for (i in 0 until DockQuickApps.SLOTS) {
            val left = DockLayout.quickSlotLeft(sections, i)
            val app = quickApps.getOrNull(i)
            val cx = left + tile / 2f
            // 快捷应用槽以前按下去毫无反应，现在跟别的键一样有按压反馈。
            withPress(canvas, "quick$i", cx, cy) {
                if (app == null || app.pkg.isEmpty()) {
                    // 空槽位：圆角方框 + 加号
                    r.set(left, cy - tile / 2f, left + tile, cy + tile / 2f)
                    canvas.drawRoundRect(r, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Skin.surfaceSoft
                    })
                    canvas.drawRoundRect(r, 18f, 18f, stroke.apply {
                        color = Skin.NAV_STROKE
                        strokeWidth = 1.4f
                    })
                    stroke.color = Skin.TEXT_DIM
                    stroke.strokeWidth = 2.6f
                    stroke.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(cx - 9f, cy, cx + 9f, cy, stroke)
                    canvas.drawLine(cx, cy - 9f, cx, cy + 9f, stroke)
                } else {
                    val icon = app.icon
                    if (icon != null) {
                        drawAppIcon(canvas, icon, cx, cy, tile - 6f)
                    } else {
                        // 取不到图标 → 首字占位（与应用选择器同一套兜底）
                        r.set(left, cy - tile / 2f, left + tile, cy + tile / 2f)
                        canvas.drawRoundRect(r, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Skin.surfaceSoft
                        })
                        text.textAlign = Paint.Align.CENTER
                        text.textSize = 26f
                        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        text.color = Skin.TEXT_SOFT
                        canvas.drawText(app.label.take(1), cx, cy + 9f, text)
                    }
                }
            }
            hit["quick$i"] = RectF(left, cy - tile / 2f, left + tile, cy + tile / 2f)
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
     * 空调簇的**占位宽度**（只量不画）。
     *
     * 第四十九轮「浮屿」把空调簇挪到 Dock 正中间，所以必须先知道它有多宽才能居中。
     * 数字全部取自 [CLIMATE_SLOT] / [FAN_W] / [TEMP_ITEM_W] 等常量，
     * 与 [drawClimateCluster] 一一对应 —— 改常量时两边同时生效，不会走偏。
     */
    private fun climateClusterWidth(): Float {
        val wAuto = textItemWidth("AUTO", CLIMATE_TEXT_SIZE)
        val wAc = textItemWidth("A/C", CLIMATE_TEXT_SIZE)
        // 与 [drawClimateCluster] 的从右往左排布**逐项对应**：
        //   defrostR / defrostF / circ / AUTO / A/C / 副驾温度 / 风扇 / 主驾温度 / 电源
        // 算式与实际消耗必须完全相等 —— 否则簇的左右边界会算偏，
        // 第五十三轮「平均铺开」就靠这个把两段间隙摆成一样宽。
        return CLIMATE_SLOT * 4 + // 循环 / 前除霜 / 后除霜 / 电源
            CLIMATE_ICON_GAP * 4 + // 三个图标之间 + A/C 之后
            CLIMATE_POWER_GAP + // 电源之前
            wAuto + wAc + // A/C · AUTO
            TEMP_ITEM_W * 2 + FAN_W // 副驾温度 / 风扇 / 主驾温度
    }

    /** 文字型空调项（A/C · AUTO）的占位宽度：文字宽 + 左右留白。 */
    private fun textItemWidth(label: String, size: Float): Float {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        return text.measureText(label) + CLIMATE_TEXT_PAD
    }

    /**
     * 右侧气候簇（从右往左排：电源 → 主驾温度 → 风扇 → 副驾温度 → A/C → AUTO → 三枚图标）。
     *
     * 排布顺序
     * `dockPower → dockTempDown → 主驾 → dockTempUp → dockFan → dockTempPDown → 副驾 → dockTempPUp
     *  dockAc → dockAuto → dockCirc → dockDefrostF → dockDefrostR`。
     *
     * 第四十九轮「浮屿」：这一簇被挪到 **Dock 正中间**（见 [onDraw]），
     * 槽宽从 70 收到 64、电源前的空档从 70 收到 16 —— 腾出的宽度让媒体卡能贴到右边缘。
     * 所有尺寸都来自常量，[climateClusterWidth] 用同一份数字，不会走偏。
     *
     * @param right 右边界
     * @return 这一整簇的**左边界**
     */
    private fun drawClimateCluster(canvas: Canvas, right: Float): Float {
        val h = height.toFloat()
        val cy = h / 2f
        var cur = right
        cur = climateItem(canvas, cur, CLIMATE_SLOT, "defrostR")
        cur -= CLIMATE_ICON_GAP
        cur = climateItem(canvas, cur, CLIMATE_SLOT, "defrostF")
        cur -= CLIMATE_ICON_GAP
        cur = climateItem(canvas, cur, CLIMATE_SLOT, "circ")
        cur -= CLIMATE_ICON_GAP
        cur = textItem(canvas, cur, "AUTO", CLIMATE_TEXT_SIZE, hvac?.auto == true)
        cur = textItem(canvas, cur, "A/C", CLIMATE_TEXT_SIZE, hvac?.ac == true)
        cur -= CLIMATE_ICON_GAP
        cur = tempItem(canvas, cur, passenger = true)
        cur -= FAN_W
        val fanTop = (h - 76f) / 2f
        // 风量键以前是「按了没反应」的一档（没有按压反馈）——
        // 现在跟别的键一样按下去会轻轻一沉。
        withPress(canvas, "fan", cur + FAN_W / 2f, fanTop + 38f) {
            drawFan(canvas, cur, fanTop, FAN_W, 76f)
        }
        hit["fan"] = RectF(cur, fanTop, cur + FAN_W, fanTop + 76f)
        cur = tempItem(canvas, cur, passenger = false)
        // 第五十三轮修重叠：[drawPower] 是**左锚点**画的（占 [left, left + slot]），
        // 原来这里只减了 16 的间隙就把左边界传进去 —— 64 宽的槽有 48 压在主驾温度上，
        // 「−」按钮被电源图标盖掉一半。现在整个槽挪到温度区左边。
        cur -= CLIMATE_POWER_GAP
        val powerLeft = cur - CLIMATE_SLOT
        drawPower(canvas, powerLeft, cy, CLIMATE_SLOT)
        hit["power"] = RectF(powerLeft, cy - 44f, powerLeft + CLIMATE_SLOT, cy + 44f)
        return powerLeft
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
     * 导航键底板（设计板 `.tile`：60×60 圆角 20，surfaceSoft 底 + 1px 描边）。
     *
     * 第四十九轮之前 Dock 左段是**裸图标**，第五十轮按设计板补上底板，
     * 四个键（主页 / 应用 / 分屏 / 360）共用同一块底板尺寸。
     */
    private fun drawKeyTile(canvas: Canvas, cx: Float, cy: Float) {
        val half = DockLayout.KEY_SLOT / 2f
        r.set(cx - half, cy - half, cx + half, cy + half)
        canvas.drawRoundRect(r, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.surfaceSoft })
        canvas.drawRoundRect(r, 22f, 22f, stroke.apply {
            color = Skin.NAV_STROKE
            strokeWidth = 1.4f
        })
    }

    /**
     * home 键（主页）。
     *
     * 第七十七轮之前画的是「圆环 + 圆点」——
     * 那个形状**看不出是主页**，用户反馈不直观。
     * 现在改成**房子**（[HomeHouseIcon]），
     * 和导航槽栏里的「主页」槽位**同一个形状**。
     *
     * 旧的圆环外径 28，这里房子的半宽取 13（宽高都是 26），
     * 视觉重量与原来基本一致。
     */
    private fun drawHome(canvas: Canvas, x: Float, cy: Float, slot: Float) {
        val cx = x + slot / 2f
        val active = activePage == 0
        drawKeyTile(canvas, cx, cy)
        withPress(canvas, "home", cx, cy) {
            stroke.color = if (active) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
            stroke.strokeWidth = 2.8f
            HomeHouseIcon.draw(canvas, cx, cy, HOME_ICON_UNIT, stroke)
        }
        hit["home"] = RectF(x, cy - 48f, x + slot, cy + 48f)
    }

    private fun drawApps(canvas: Canvas, x: Float, cy: Float, slot: Float) {
        val cx = x + slot / 2f
        val active = appsOpen
        drawKeyTile(canvas, cx, cy)
        withPress(canvas, "apps", cx, cy) {
            fill.color = if (active) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
            // 第192轮：宫格 7×7@11 → 8×8@12（跟 Dock 高 104 → 112 同一比例放大）。
            for (row in 0..2) for (col in 0..2) {
                canvas.drawRoundRect(
                    RectF(cx - 17f + col * 12f, cy - 17f + row * 12f, cx - 9f + col * 12f, cy - 9f + row * 12f),
                    2f, 2f, fill,
                )
            }
        }
        hit["apps"] = RectF(x, cy - 48f, x + slot, cy + 48f)
    }

    private fun drawSplit(canvas: Canvas, x: Float, cy: Float, slot: Float) {
        val cx = x + slot / 2f
        drawKeyTile(canvas, cx, cy)
        withPress(canvas, "split", cx, cy) {
            stroke.color = Skin.TEXT_SOFT
            stroke.strokeWidth = 2.6f
            // 第192轮：±14 → ±16 高、左右各外扩 2（跟 Dock 高 104 → 112 同一比例放大）。
            r.set(cx - 17f, cy - 16f, cx + 3f, cy + 16f)
            canvas.drawRoundRect(r, 4f, 4f, stroke)
            r.set(cx + 1f, cy - 16f, cx + 17f, cy + 16f)
            canvas.drawRoundRect(r, 4f, 4f, stroke)
        }
        hit["split"] = RectF(x, cy - 48f, x + slot, cy + 48f)
    }

    private fun draw360(canvas: Canvas, x: Float, cy: Float, slot: Float) {
        val cx = x + slot / 2f
        drawKeyTile(canvas, cx, cy)
        withPress(canvas, "360", cx, cy) {
            // 圆角 12、2px 描边 #9ee6eefa
            // 第五十一轮：日间模式下这层浅蓝在白底上看不见，改成跟随主题的弱文字色。
            val glyphColor = if (Skin.isNight) 0x9EE6EEFA.toInt() else Skin.TEXT_SOFT
            stroke.color = glyphColor
            stroke.strokeWidth = 2f
            // 第192轮：±24×±15 → ±26×±16、字 22 → 24（跟 Dock 高 104 → 112 同一比例放大）。
            r.set(cx - 26f, cy - 16f, cx + 26f, cy + 16f)
            canvas.drawRoundRect(r, 12f, 12f, stroke)
            text.color = glyphColor
            text.textSize = 24f
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("360", cx, cy + 8f, text)
        }
        hit["360"] = RectF(x, cy - 48f, x + slot, cy + 48f)
    }

    /**
     * 媒体卡。
     *
     * 整块（封面 / 歌名区）都是点击热区，所以按压反馈作用在**整张卡**上，
     * 围绕卡片自己的中心缩放 —— 视觉重心在卡片身上，而不是某个小图标。
     */
    private fun drawMedia(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        withPress(canvas, "media", left + w / 2f, top + h / 2f) {
            drawMediaCard(canvas, left, top, w, h)
        }
    }

    private fun drawMediaCard(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        r.set(left, top, left + w, top + h)
        // #12ffffff、圆角 28
        canvas.drawRoundRect(r, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.GLASS_FILL })

        // 第三十九轮：三键布局统一由 `DockMediaLayout` 算（上一首 | 播放 | 下一首）。
        val keys = DockMediaLayout.keys(w)

        // 封面（圆角 16，clipToOutline）—— 第192轮：边长用 [DockMediaLayout.COVER_SIZE]
        // （60 → 64），并且**垂直居中**在卡里，不再写死「距顶 14」。
        val coverSize = DockMediaLayout.COVER_SIZE
        val coverTop = top + (h - coverSize) / 2f
        val cover = RectF(
            left + DockMediaLayout.PAD_L,
            coverTop,
            left + DockMediaLayout.PAD_L + coverSize,
            coverTop + coverSize,
        )
        val art = mediaArtwork
        if (art != null) {
            // 有专辑图 → 按裁剪成圆角矩形贴上去
            val clip = Path().apply { addRoundRect(cover, 16f, 16f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(art, null, cover, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            // 第五十一轮：设计板用 `--accentSoft` 底 + `--accent` 音符（不再是蓝色渐变）。
            canvas.drawRoundRect(cover, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coverFallback })
            // 音符
            stroke.color = Skin.ACCENT
            stroke.strokeWidth = 2.6f
            canvas.drawLine(cover.centerX() + 4f, cover.centerY() - 12f, cover.centerX() + 4f, cover.centerY() + 6f, stroke)
            canvas.drawCircle(cover.centerX() - 1f, cover.centerY() + 8f, 5.5f, stroke)
            canvas.drawLine(cover.centerX() + 4f, cover.centerY() - 12f, cover.centerX() + 13f, cover.centerY() - 8f, stroke)
        }

        // 标题/艺术家（滑动时整体位移 + 淡出，并露出预览文案）
        val tx = left + keys.textLeft
        val textW = keys.textWidth
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
        text.textSize = 22f
        text.isFakeBoldText = false
        text.color = Skin.TEXT
        canvas.save()
        canvas.translate(swipeOffset, 0f)
        canvas.drawText(clip(mediaTitle, text, textW), tx, top + h / 2f - 4f, text)
        text.color = Skin.TEXT_DIM
        text.textSize = 16f
        canvas.drawText(clip(mediaArtist, text, textW), tx, top + h / 2f + 18f, text)
        canvas.restore()

        if (swipeFraction > 0f) {
            text.textAlign = Paint.Align.LEFT
            text.typeface = Typeface.DEFAULT
            text.textSize = 22f
            text.color = Skin.TEXT
            canvas.save()
            canvas.translate(-swipeOffset, 0f)
            canvas.drawText(clip(swipePreviewTitle, text, textW), tx, top + h / 2f - 4f, text)
            text.textSize = 16f
            text.color = Skin.TEXT_DIM
            canvas.drawText(clip(swipePreviewArtist, text, textW), tx, top + h / 2f + 18f, text)
            canvas.restore()
        }
        // 播放/暂停按钮 44×44：**去掉外圈白色圆底**，只留字形。
        //
        // 第四十轮（用户反馈「播放按钮外圈的白色圆环去掉，保持与上下切换的风格统一」）：
        // （#eef3fa 近白圆底）+ 26×26 图标
        // 但那个 60×60 的白圆在 88px 高的媒体卡里视觉权重过重，和左右两个
        // 「纯字形」的切歌按钮完全不是一套语言。这里按用户要求去掉圆底，
        // 字形改用与 [skipIcon] 完全相同的 `Skin.TEXT_SOFT` 软白，
        // 尺寸也按切歌三角形（24px 高）对齐。
        val playCx = left + keys.playLeft + DockMediaLayout.PLAY_BTN / 2f
        val by = top + h / 2f
        withPress(canvas, "play", playCx, by) {
            fill.color = Skin.TEXT_SOFT
            if (mediaPlaying) {
                // 暂停：两根竖条（圆角与切歌图标的小竖条一致）
                // 第192轮：随播放键 44 → 48 一起放大一档。
                val barW = 6f
                val barH = 12f
                canvas.drawRoundRect(
                    RectF(playCx - 7f, by - barH, playCx - 7f + barW, by + barH),
                    2f, 2f, fill,
                )
                canvas.drawRoundRect(
                    RectF(playCx + 1.5f, by - barH, playCx + 1.5f + barW, by + barH),
                    2f, 2f, fill,
                )
            } else {
                // 播放：三角形（高度与切歌三角形的 24px 一致）
                // 第192轮：随播放键 44 → 48 一起放大一档。
                val pth = Path().apply {
                    moveTo(playCx - 9f, by - 13f)
                    lineTo(playCx + 11f, by)
                    lineTo(playCx - 9f, by + 13f)
                    close()
                }
                canvas.drawPath(pth, fill)
            }
        }

        // 上一首 / 下一首（第三十七轮新增，第三十九轮调整位置与尺寸）：
        // 排成「上一首 | 播放暂停 | 下一首」，播放键夹在中间。
        val nextCx = left + keys.nextLeft + DockMediaLayout.SKIP_BTN / 2f
        val prevCx = left + keys.prevLeft + DockMediaLayout.SKIP_BTN / 2f
        withPress(canvas, "mediaNext", nextCx, by) {
            skipIcon(canvas, nextCx, by, next = true)
        }
        withPress(canvas, "mediaPrev", prevCx, by) {
            skipIcon(canvas, prevCx, by, next = false)
        }

        // 播放进度 3px（dockMediaProg：#3d7bff）
        if (mediaProgress > 0f) {
            fill.color = Skin.ACCENT
            canvas.drawRoundRect(
                RectF(left + 14f, top + h - 5f, left + 14f + (w - 28f) * mediaProgress, top + h - 2f),
                2f, 2f, fill,
            )
        }
    }

    /**
 * 一个温度块（主驾 / 副驾）。
 *
 * 结构是**三段并排**
 *
 * `xml
 * <FrameLayout id="dockTempDown" width=52 marginStart=8 /> ← − 按钮
 * <LinearLayout width=118 gravity=center_horizontal> ← 温度区
 *     <LinearLayout gravity=bottom>
 *         <TextView textSize=50 text="23.5" /> ← 数字
 *         <TextView textSize=26 text="°" /> ← 度号（跟在数字右边）
 *     </LinearLayout>
 *     <TextView textSize=13 text="主驾" />
 * </LinearLayout>
 * <FrameLayout id="dockTempUp" width=52 /> ← + 按钮
 * `
 *
 * 之前我们把温度区画在 `[right-118, right]`，**和 + 按钮重叠**，
 * 而数字（50px 字号，「23.5」约 97px 宽）又会盖住 + 按钮的箭头
 * —— 真机上就是「温度数字遮住了加减按钮」。
 *
 * 现在严格按三段排：`[− 52] [温度区 118] [+ 52]`，数字与 ° 作为**一组**居中，
 * 放不下时等比缩小字号，保证永远不会压到按钮上。
 */
private fun tempItem(canvas: Canvas, right: Float, passenger: Boolean): Float {
        val state = hvac
        val value = if (passenger) state?.tempPassenger ?: 23.5f else state?.tempDriver ?: 23.5f
        // （:425-430）：固定一位小数，<=0 时按 23.5 处理
        val shown = formatTemp(value)

        val plusW = TEMP_PLUS_W
        val zoneW = TEMP_ZONE_W
        val minusW = TEMP_MINUS_W
        val gap = TEMP_GAP

        // 从右往左量：+ 按钮 → 温度区 → − 按钮
        //
        // 第五十三轮修一处「算式与绘制对不上」的老毛病：
        // 原来 [zoneLeft = plusLeft - zoneW] 把中间那个 8px 间隙**漏掉**了，
        // 于是「+」按钮直接贴死在温度区上（视觉上像「温度数字压住了 + 按钮」），
        // 而空出来的 8px 跑到整块的最左边去了。
        // 现在两个间隙都在该在的位置：
        //   `[− 48] [8] [温度区 112] [8] [+ 48]` = 224 = [TEMP_ITEM_W]，**不多不少**。
        val plusRight = right
        val plusLeft = plusRight - plusW
        val zoneLeft = plusLeft - gap - zoneW
        val minusRight = zoneLeft - gap
        val minusLeft = minusRight - minusW
        val cx = (zoneLeft + plusLeft) / 2f

        // —— 数字 + ° 作为一组居中；放不下就等比缩小 ——
        val numSize = 50f
        val degSize = 26f
        text.textAlign = Paint.Align.LEFT
        text.textSize = numSize
        var numW = text.measureText(shown)
        text.textSize = degSize
        var degW = text.measureText("°")
        var groupW = numW + degW
        var scale = 1f
        val maxGroup = zoneW - 10f
        if (groupW > maxGroup) {
            scale = maxGroup / groupW
            text.textSize = numSize * scale
            numW = text.measureText(shown)
            text.textSize = degSize * scale
            degW = text.measureText("°")
            groupW = numW + degW
        }
        val startX = cx - groupW / 2f

        // 数字
        //
        // 温度是**自绘**的：值一变只能硬切，数字「啪」地跳过去，余光根本注意不到。
        // 变化时给这个数字补一个短暂的放大脉冲（比例 / 时长见 [MotionSpec.STATE_BUMP]）。
        val pulse = if (passenger) tempPassengerPulse.scale else tempDriverPulse.scale
        canvas.save()
        if (pulse != 1f) canvas.scale(pulse, pulse, cx, height / 2f)
        text.textSize = numSize * scale
        text.color = Skin.TEXT
        canvas.drawText(shown, startX, height / 2f + 12f * scale, text)
        // °（跟在数字右边；是 LinearLayout 水平排列）
        text.textSize = degSize * scale
        text.color = Skin.TEXT_SOFT
        canvas.drawText("°", startX + numW, height / 2f + 2f * scale, text)
        canvas.restore()
        // 主驾 / 副驾
        text.textSize = 13f
        text.color = Skin.TEXT_DIM
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(if (passenger) "副驾" else "主驾", cx, height / 2f + 38f, text)

        // —— 两个加减按钮（画在数字之后：万一有重叠，按钮在上层）——
        // 第五十三轮：按钮槽 52 → 48，所以字形中心从 +26 改成 +24（真正居中）。
        //
        // 加减键以前是「按了没反应」的一档（没有按压反馈），现在补上。
        val minusKey = if (passenger) "tempP-" else "temp-"
        val plusKey = if (passenger) "tempP+" else "temp+"
        withPress(canvas, minusKey, minusLeft + 24f, height / 2f) {
            plusMinus(canvas, minusLeft + 24f, height / 2f, plus = false)
        }
        withPress(canvas, plusKey, plusLeft + 24f, height / 2f) {
            plusMinus(canvas, plusLeft + 24f, height / 2f, plus = true)
        }
        hit[minusKey] =
            RectF(minusLeft, height / 2f - 44f, minusRight, height / 2f + 44f)
        hit[plusKey] =
            RectF(plusLeft, height / 2f - 44f, plusRight, height / 2f + 44f)

        // 整块占位 = 48 + 8 + 112 + 8 + 48 = 224 = [TEMP_ITEM_W]，
        // 返回**真实左边界**，算式与绘制从此完全一致（第五十三轮）。
        return minusLeft
    }

    /**
     * `DesignDock.format(float)`
     * 温度 ≤ 0 时按 23.5 处理，并且**固定一位小数**。
     */
    private fun formatTemp(value: Float): String {
        val v = if (value <= 0f) 23.5f else value
        return String.format(java.util.Locale.US, "%.1f", v)
    }

    private fun drawFan(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val state = hvac
        val level = state?.fanLevel ?: 0
        val auto = state?.fanAuto == true
        val cx = left + w / 2f
        // 风扇图标
        stroke.color = if (level > 0 || auto) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
        stroke.strokeWidth = 2.4f
        val fanCy = top + 24f
        canvas.drawCircle(cx, fanCy, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.color })
        for (i in 0..2) {
            val a = Math.toRadians((i * 120).toDouble())
            val bx = cx + (Math.cos(a) * 12f).toFloat()
            val by = fanCy + (Math.sin(a) * 12f).toFloat()
            canvas.drawCircle(bx, by, 8f, stroke)
        }
        // 7 段
        val segW = 9f
        val gap = 4f
        val total = 7 * segW + 6 * gap
        var sx = cx - total / 2f
        val sy = top + h - 19f
        // 风量档位变了 → 7 段短暂放大一下（「这里变了」要被看见）。
        val pulse = fanPulse.scale
        canvas.save()
        if (pulse != 1f) canvas.scale(pulse, pulse, cx, sy + 7.5f)
        for (i in 0 until 7) {
            fill.color = if (i < level) Skin.ACCENT_LIGHT else Skin.surfaceStrong
            canvas.drawRoundRect(RectF(sx, sy, sx + segW, sy + 15f), 3f, 3f, fill)
            sx += segW + gap
        }
        canvas.restore()
        if (auto) {
            text.color = Skin.ACCENT_LIGHT
            text.textSize = 13f
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("AUTO", cx, top + 4f, text)
        }
    }

    private fun textItem(canvas: Canvas, right: Float, label: String, size: Float, active: Boolean): Float {
        text.textAlign = Paint.Align.CENTER
        text.textSize = size
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.color = if (active) Skin.ACCENT else Skin.TEXT_SOFT
        // 第三十九轮：左右留白 40 → 28。
        // 这一簇是「从右往左量」的固定占位，留白越大，整簇就越宽；
        // 28 已经够两个标签各自独立（里 AUTO / A/C 就是 wrap_content，没有额外留白）。
        val w = text.measureText(label) + CLIMATE_TEXT_PAD
        withPress(canvas, label, right - w / 2f, height / 2f) {
            canvas.drawText(label, right - w / 2f, height / 2f + 10f, text)
        }
        hit[label] = RectF(right - w, height / 2f - 44f, right, height / 2f + 44f)
        return right - w
    }

    private fun climateItem(canvas: Canvas, right: Float, slot: Float, key: String): Float {
        val cx = right - slot / 2f
        val cy = height / 2f
        withPress(canvas, key, cx, cy) {
            stroke.strokeWidth = 2.6f
            when (key) {
                "circ" -> {
                    val inner = hvac?.circulation == 0
                    stroke.color = if (inner) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
                    r.set(cx - 16f, cy - 13f, cx + 16f, cy + 13f)
                    canvas.drawRoundRect(r, 13f, 13f, stroke)
                    val pth = Path().apply {
                        moveTo(cx - 8f, cy + 1f)
                        lineTo(cx - 2f, cy + 7f)
                        lineTo(cx + 10f, cy - 7f)
                    }
                    if (inner) canvas.drawPath(pth, stroke) else {
                        canvas.drawPath(Path().apply {
                            moveTo(cx + 8f, cy + 1f)
                            lineTo(cx + 2f, cy + 7f)
                            lineTo(cx - 10f, cy - 7f)
                        }, stroke)
                    }
                }
                "defrostF" -> {
                    val on = hvac?.defrostFront == true
                    stroke.color = if (on) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
                    r.set(cx - 17f, cy - 12f, cx + 17f, cy + 12f)
                    canvas.drawRoundRect(r, 8f, 8f, stroke)
                    for (i in 0..2) {
                        val x0 = cx - 10f + i * 10f
                        canvas.drawLine(x0, cy + 16f, x0 - 3f, cy + 24f, stroke)
                    }
                }
                "defrostR" -> {
                    val on = hvac?.defrostRear == true
                    stroke.color = if (on) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
                    r.set(cx - 17f, cy - 12f, cx + 17f, cy + 12f)
                    canvas.drawRoundRect(r, 8f, 8f, stroke)
                    for (i in 0..2) {
                        val x0 = cx - 10f + i * 10f
                        canvas.drawLine(x0, cy + 16f, x0, cy + 24f, stroke)
                    }
                }
            }
        }
        hit[key] = RectF(right - slot, cy - 44f, right, cy + 44f)
        return right - slot
    }

    private fun drawPower(canvas: Canvas, left: Float, cy: Float, slot: Float) {
        val on = hvac?.power == true
        val cx = left + slot / 2f
        withPress(canvas, "power", cx, cy) {
            stroke.color = if (on) Skin.ACCENT_LIGHT else Skin.TEXT_SOFT
            stroke.strokeWidth = 3f
            r.set(cx - 14f, cy - 16f, cx + 14f, cy + 14f)
            canvas.drawArc(r, 300f, 300f, false, stroke)
            canvas.drawLine(cx, cy - 22f, cx, cy - 4f, stroke)
        }
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
     * 切歌按钮的图形（第三十七轮新增）。
     *
     * - 下一首：三角形朝右 + 右边一条竖条；
     * - 上一首：左边一条竖条 + 三角形朝左。
     */
    private fun skipIcon(canvas: Canvas, cx: Float, cy: Float, next: Boolean) {
        fill.color = Skin.TEXT_SOFT
        // 第三十九轮：按钮从 36 放大到 40，图标跟着放大一点，
        // 和中间的 44px 播放圆**协调**（太小会显得像两个小点）。
        // 第192轮：按钮 40 → 44，图标同步放大（12 → 13 高、竖条 3.6 → 4）。
        val triH = 13f
        val barW = 4f
        if (next) {
            // ▶|：三角形朝右，右边一条竖条
            canvas.drawRoundRect(RectF(cx + 6f, cy - triH, cx + 6f + barW, cy + triH), 2f, 2f, fill)
            val p = Path().apply {
                moveTo(cx - 12f, cy - triH)
                lineTo(cx + 2f, cy)
                lineTo(cx - 12f, cy + triH)
                close()
            }
            canvas.drawPath(p, fill)
        } else {
            // |◀：左边一条竖条，三角形朝左
            canvas.drawRoundRect(RectF(cx - 10f, cy - triH, cx - 10f + barW, cy + triH), 2f, 2f, fill)
            val p = Path().apply {
                moveTo(cx + 12f, cy - triH)
                lineTo(cx - 2f, cy)
                lineTo(cx + 12f, cy + triH)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
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

        /**
         * home 键房子图标的**半宽**（像素）。
         *
         * 第七十七轮把原来的「圆环 + 圆点」（外径 28）换成房子，
         * 半宽取 13 ⇒ 宽高都是 26，视觉重量与原来基本一致。
         *
         * 第192轮：13 → 15（跟 Dock 高 104 → 112 同一比例放大）。
         */
        const val HOME_ICON_UNIT = 15f

        /**
         * 空调簇里**图标型**按钮（内外循环 / 前除霜 / 后除霜 / 电源）的槽宽。
         *
         * 第四十九轮「浮屿」从 70 收到 64；第五十三轮再收到 **48** ——
         * 图标本身只有 28~38px 宽，64 的槽白白留着；收窄之后
         * 整条 Dock 才能「平均铺开」（见 [DockLayout.GROUP_GAP]）。
         *
         * 第192轮：48 → 52（跟 Dock 高 104 → 112 同一比例放大）。
         */
        const val CLIMATE_SLOT = 52f

        /** 图标按钮之间的间隙。 */
        const val CLIMATE_ICON_GAP = 8f

        /** 电源与主驾温度之间的间隙。 */
        const val CLIMATE_POWER_GAP = 16f

        /** A/C · AUTO 的字号。 */
        const val CLIMATE_TEXT_SIZE = 28f

        /** A/C · AUTO 文字的左右留白。 */
        const val CLIMATE_TEXT_PAD = 28f

        /**
         * 风扇图标 + 7 段的占位宽。
         *
         * 第192轮：100 → 106（跟 Dock 高 104 → 112 同一比例放大）。
         */
        const val FAN_W = 106f

        /** 温度块里「−」按钮的槽宽。 */
        const val TEMP_MINUS_W = 48f

        /** 温度块里数字 + 度的占位宽。 */
        const val TEMP_ZONE_W = 112f

        /** 温度块里「+」按钮的槽宽。 */
        const val TEMP_PLUS_W = 48f

        /** 温度块内部（− 与数字区之间）的间隙。 */
        const val TEMP_GAP = 8f

        /**
         * 一个温度块的占位宽：− 按钮 48 + 间隙 8 + 温度区 112 + + 按钮 48 + 尾部间隙 8。
         *
         * 第五十三轮从 238 收到 **224**：数字区从 118 收到 112（放不下会等比缩字号），
         * 加减按钮从 52 收到 48（按钮里只画一条 18px 的横线）。
         */
        const val TEMP_ITEM_W = TEMP_MINUS_W + TEMP_ZONE_W + TEMP_PLUS_W + TEMP_GAP * 2

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

        /** 媒体卡文本区宽度（第三十七轮：媒体卡加宽到 496，文本区也跟着变宽）。 */
        const val SWIPE_TEXT_AREA = 236f

        // 第三十九轮：媒体卡宽度改为**动态**（按右侧空调簇实际占位收敛），
        // 三个键的尺寸与间隙、文本区宽度都在 `core/DockMediaLayout.kt`（纯逻辑、可单测）。
        // 第183轮：Dock 圆角可调（设置项 `dock_corner_radius`）。

        /** 活着的 DockBarView 实例（桌面里一份、全局 Dock 窗口里一份）。 */
        private val live =
            java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<DockBarView>>()

        /** 设置页改完圆角后调用：重新读偏好 + 重绘所有实例。 */
        fun refreshAll(context: Context) {
            val radius = runCatching {
                Prefs(context.applicationContext).dockCornerRadius.toFloat()
            }.getOrDefault(0f)
            for (ref in live) {
                val v = ref.get() ?: continue
                v.cornerRadius = radius
                v.invalidate()
            }
        }

    }
}
