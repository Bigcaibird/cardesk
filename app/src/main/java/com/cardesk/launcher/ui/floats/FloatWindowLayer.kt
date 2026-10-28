package com.cardesk.launcher.ui.floats

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pressFeedback
import kotlin.math.roundToInt

/**
 * 浮窗层， /。
 *
 * 是系统 App，用 `ActivityView + VirtualDisplay` 把别的应用真正嵌进桌面
 * 本应用没有系统签名，`ActivityView.setVirtualDisplay` 属 @SystemApi，运行时用反射尝试，
 * 失败则降级成「应用卡片窗」（图标 + 名称 + 全屏打开），保证窗口数量、拖动、缩放与
 * 一致，并在标题栏标注承载方式。
 *
 * ## 与对齐的行为
 *
 * | 行为 |出处 |
 * |---|---|
 * | 同包重复打开 → 恢复已存在窗口，不新开 | |
 * | 超过 2 个 → 关掉最老的腾位 | |
 * | 默认 x = 28；已有窗口占左半屏时 = 972 | |
 * | 默认 y 垂直居中于「场景页 assist 区 ↔ 底部卡」之间 | |
 * | 缩放上限 = 初始尺寸（不能比初始大） | |
 * | 拖动边界 x ∈ [160-w, 1920-160]，y ∈ [20, 1080-200] | |
 * | 点根视图 → 全屏化 / 再点还原（带动画） | |
 * | 点窗口 → 置顶 + 其余窗口去掉高亮框 | |
 * | 最小化按钮 → 隐藏窗口，气泡留在气泡列 | |
 * | 切到非首页 → 全部最小化，气泡变灰不可点 | |
 * | 打开同包全屏应用 → 关闭该包浮窗（不杀应用） | |
 */
class FloatWindowLayer(
    context: Context,
    private val pal: Palette,
) : FrameLayout(context) {

    var onChanged: (() -> Unit)? = null

    /** 最小化气泡的宿主需要知道哪些窗口处于最小态。 */
    var onBubbleState: ((List<WindowBox>) -> Unit)? = null

    private val windows = mutableListOf<WindowBox>()

    /**
     * 当前所在的桌面页（0 = 场景首页）。
     *
     * = `SceneDesktop.currentPage() == 0`。
     * 我们由 [onPageChanged] 维护。
     */
    private var currentPage: Int = 0

    /** 当前是否在桌面首页（浮窗唯一可用的页面）。 */
    fun isFirstPage(): Boolean = currentPage == 0

    class WindowBox(
        val view: View,
        val label: String,
        val packageName: String,
        var rect: RectF,
        var embedded: Boolean,
        internal var activityView: View? = null,
        /** 是否处于全屏化状态。 */
        var full: Boolean = false,
        /** 是否已最小化（只剩气泡）。 */
        var minimized: Boolean = false,
        var restoreX: Int = 0,
        var restoreY: Int = 0,
        var restoreW: Int = 0,
        var restoreH: Int = 0,
        var maxResizeW: Int = 0,
        var maxResizeH: Int = 0,
        /** 非桌面首页时气泡不可点。 */
        var bubbleInteractive: Boolean = true,
    )

    val count: Int get() = windows.size

    /** 当前置顶（focus）的窗口。 */
    private var active: WindowBox? = null

    /**
     * 打开一个应用浮窗。
     *
     *
     *  - **非桌面首页直接返回，什么都不做**（的 `!isFirstPage()`）；
     *  - 同包已存在 → 恢复并置顶，不新开；
     *  - 超过 2 个 → 关掉最老的腾位。
     */
    fun open(pkg: String, title: String? = null, avatar: Drawable? = null): WindowBox? {
        // 979 —— 不在桌面首页时静默不打开
        if (!isFirstPage()) {
            // 排障场景：「在导航页点应用没反应 / 浮窗叠在当前页上」——
            // 就是静默忽略，这里落一条 WARN 免得当成崩溃。
            Logx.w(
                LogTags.FLOAT,
                "浮窗打开被忽略：当前不在桌面首页",
                "pkg" to pkg,
                "page" to currentPage,
            )
            return null
        }
        windows.firstOrNull { it.packageName == pkg }?.let { existing ->
            // 排障场景：「同一个应用重复点只恢复旧窗 / 以为没打开」——复用已有窗口要落盘。
            Logx.event(
                LogTags.FLOAT,
                "浮窗打开（同包复用已有窗口）",
                "pkg" to pkg,
                "label" to existing.label,
                "count" to windows.size,
            )
            restore(existing)
            focus(existing)
            return existing
        }
        closeOldestUntilSlotAvailable()

        val label = title ?: runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val box = buildWindow(pkg, label, avatar)
        // 默认位置：x = 28 或 972，y 垂直居中（open():994-1005 + applyDefaultBounds():195-214）
        val left = defaultLeft()
        val top = defaultTop()
        box.rect = RectF(left.toFloat(), top.toFloat(), (left + DEFAULT_W).toFloat(), (top + DEFAULT_H).toFloat())
        windows.add(box)
        addView(box.view, layoutParamsOf(box))
        place(box)
        focus(box)
        // 排障场景：「浮窗没出来 / 出来是黑屏」——打开结果 + 承载方式落盘。
        Logx.event(
            LogTags.FLOAT,
            "浮窗打开",
            "pkg" to pkg,
            "label" to label,
            "embedded" to box.embedded,
            "count" to windows.size,
            "x" to left,
            "y" to top,
        )
        onChanged?.invoke()
        return box
    }

    fun close(box: WindowBox) {
        if (!windows.remove(box)) return
        releaseActivityView(box)
        removeView(box.view)
        if (active === box) active = windows.lastOrNull()
        active?.let { focus(it) }
        // 排障场景：「浮窗关不掉 / 关了还在」——关闭落盘。
        Logx.event(
            LogTags.FLOAT,
            "浮窗关闭",
            "pkg" to box.packageName,
            "label" to box.label,
            "count" to windows.size,
        )
        onChanged?.invoke()
        onBubbleState?.invoke(minimizedWindows())
    }

    private fun releaseActivityView(box: WindowBox) {
        val av = box.activityView ?: return
        runCatching { av.javaClass.getMethod("release").invoke(av) }
        box.activityView = null
    }

    fun closeAll() {
        windows.toList().forEach { close(it) }
        onChanged?.invoke()
    }

    /**
     * 打开某包的全屏应用时，关闭该包的浮窗（**不**停止应用）。
     *
     */
    fun closeForFullscreen(pkg: String) {
        val targets = windows.filter { it.packageName == pkg }.toList()
        // 排障场景：「全屏打开后浮窗没自动关掉」——联动关闭落盘。
        Logx.i(
            LogTags.FLOAT,
            "全屏联动：关闭同包浮窗",
            "pkg" to pkg,
            "count" to targets.size,
        )
        targets.forEach { close(it) }
    }

    /** 是否存在未最小化且可见的浮窗。。 */
    fun hasExpandedWindow(): Boolean = windows.any { !it.minimized && it.view.visibility == View.VISIBLE }

    /** 当前处于最小化、需要显示气泡的窗口。 */
    fun minimizedWindows(): List<WindowBox> = windows.filter { it.minimized }

    /**
     * 桌面切页时的浮窗联动。
     *
     *
     * 只有桌面首页（page 0）能显示浮窗；切到别的页时把所有浮窗最小化，
     * 且气泡变为不可点（alpha 0.45）。
     */
    fun onPageChanged(page: Int) {
        currentPage = page
        val home = page == 0
        for (box in windows) {
            if (!home && !box.minimized) {
                // 排障场景：「切到别的页浮窗自己缩了」——联动行为要落盘。
                Logx.i(
                    LogTags.FLOAT,
                    "切页联动：浮窗自动最小化",
                    "pkg" to box.packageName,
                    "label" to box.label,
                    "page" to page,
                )
                minimize(box)
            }
            box.bubbleInteractive = home
        }
        onBubbleState?.invoke(minimizedWindows())
    }

    /** 恢复一个被最小化的窗口。。 */
    fun restore(box: WindowBox) {
        if (!windows.contains(box)) return
        // 1122 —— `if (isFirstPage() && WINS.contains(win))`
        // 非首页时气泡是灰的、不可点，这里同样拒绝还原。
        if (!isFirstPage()) {
            Logx.w(
                LogTags.FLOAT,
                "浮窗还原被忽略：当前不在桌面首页",
                "pkg" to box.packageName,
                "label" to box.label,
                "page" to currentPage,
            )
            return
        }
        box.minimized = false
        box.view.visibility = View.VISIBLE
        focus(box)
        // 排障场景：「最小化后点气泡恢复不了」——还原落盘。
        Logx.event(
            LogTags.FLOAT,
            "浮窗还原",
            "pkg" to box.packageName,
            "label" to box.label,
        )
        onBubbleState?.invoke(minimizedWindows())
        onChanged?.invoke()
    }

    /** 最小化一个窗口（隐藏窗口本体，气泡留在气泡列）。 */
    fun minimize(box: WindowBox) {
        if (!windows.contains(box) || box.minimized) return
        box.minimized = true
        box.view.visibility = View.GONE
        // 排障场景：「点了最小化窗口没消失 / 气泡没出来」——最小化落盘。
        Logx.event(
            LogTags.FLOAT,
            "浮窗最小化",
            "pkg" to box.packageName,
            "label" to box.label,
        )
        onBubbleState?.invoke(minimizedWindows())
        onChanged?.invoke()
    }

    /**
     * 置顶某个窗口，并给其它窗口去掉高亮框。
     *
     */
    fun focus(box: WindowBox) {
        if (!windows.contains(box)) return
        active = box
        box.view.bringToFront()
        for (other in windows) {
            // 媒体浮窗同时只能有一个，不需要「选中框」来区分（设计板 `s13-float` 也没有），
            // 否则整张卡会多一圈强调色描边。
            other.view.foreground =
                if (other === box && other.packageName != MEDIA_FLOAT_KEY) activeFrame() else null
        }
    }

    /** 窗口高亮边框。 */
    private fun activeFrame(): android.graphics.drawable.Drawable =
        Ui.round(0x00000000, 30f, 2f, pal.accent)

    /**
     * 全屏化 / 还原。
     *
     * 全屏化时保存原位置尺寸，动画到铺满；再点一次动画回原位。
     */
    fun toggleFullscreen(box: WindowBox) {
        if (!windows.contains(box)) return
        if (box.full) {
            animateBounds(box, box.restoreX, box.restoreY, box.restoreW, box.restoreH)
            box.full = false
            // 排障场景：「浮窗全屏后还原不回原位」——还原落盘（含还原目标位置）。
            Logx.event(
                LogTags.FLOAT,
                "浮窗退出全屏",
                "pkg" to box.packageName,
                "label" to box.label,
                "x" to box.restoreX,
                "y" to box.restoreY,
                "w" to box.restoreW,
                "h" to box.restoreH,
            )
            return
        }
        val lp = box.view.layoutParams as? LayoutParams ?: return
        box.restoreX = lp.leftMargin
        box.restoreY = lp.topMargin
        box.restoreW = lp.width
        box.restoreH = lp.height
        animateBounds(box, 0, 0, Design.W.toInt(), Design.H.toInt())
        box.full = true
        Logx.event(
            LogTags.FLOAT,
            "浮窗进入全屏",
            "pkg" to box.packageName,
            "label" to box.label,
            "fromX" to box.restoreX,
            "fromY" to box.restoreY,
            "fromW" to box.restoreW,
            "fromH" to box.restoreH,
        )
    }

    /** 尺寸/位置动画。 */
    private fun animateBounds(box: WindowBox, x: Int, y: Int, w: Int, h: Int) {
        val lp = box.view.layoutParams as? LayoutParams ?: return
        val fromX = lp.leftMargin
        val fromY = lp.topMargin
        val fromW = lp.width
        val fromH = lp.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                lp.leftMargin = (fromX + (x - fromX) * t).roundToInt()
                lp.topMargin = (fromY + (y - fromY) * t).roundToInt()
                lp.width = (fromW + (w - fromW) * t).roundToInt().coerceAtLeast(MIN_W)
                lp.height = (fromH + (h - fromH) * t).roundToInt().coerceAtLeast(MIN_H)
                box.view.layoutParams = lp
                syncVirtualDisplay(box)
            }
            start()
        }
    }

    /**
     * 打开**媒体浮窗**（设计板 `s13-float` 的 460×250 卡片）。
     *
     * 与 [open] 的「承载三方 App 的浮窗」是两种形态：
     * - 承载 App 的浮窗：抽屉「小窗打开」走 [open]（口径不变）
     * - 媒体浮窗：桌面自带的小播放器，入口是 **Dock 媒体卡长按**。
     *
     * 复用同一套浮窗基础设施（拖动 / 缩放 / 关闭 / 置顶 / 数量上限）。
     */
    fun openMediaFloat(
        windowTitle: String = MEDIA_FLOAT_TITLE,
        track: String = "暂无播放",
        artist: String = "",
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ): WindowBox? {
        // 一致：不在桌面首页时静默不打开。
        if (!isFirstPage()) {
            Logx.w(
                LogTags.FLOAT,
                "媒体浮窗打开被忽略：当前不在桌面首页",
                "page" to currentPage,
            )
            return null
        }
        windows.firstOrNull { it.packageName == MEDIA_FLOAT_KEY }?.let { existing ->
            updateMediaFloat(existing, track, artist, positionMs, durationMs)
            restore(existing)
            focus(existing)
            return existing
        }
        closeOldestUntilSlotAvailable()

        val card = MediaFloatCard(context).apply {
            palette = this@FloatWindowLayer.pal
            this.windowTitle = windowTitle
            trackTitle = track
            this.artist = artist
            this.positionMs = positionMs
            this.durationMs = durationMs
        }
        val root = FrameLayout(context).apply {
            // 「浮屿」：浮窗卡片 = 纯扁平面 + 1px 描边，圆角 28。
            background = Ui.round(Skin.surface, com.cardesk.launcher.core.Shape.CARD, 1f, Skin.NAV_STROKE)
            isClickable = true
            clipToOutline = true
            layoutParams = LayoutParams(MediaFloatCard.CARD_W, MediaFloatCard.CARD_H)
        }
        root.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 右下角缩放把手（透明；媒体浮窗默认尺寸固定，用户想拉大才拉）
        val grip = View(context).apply { alpha = 0.01f }
        root.addView(grip, LayoutParams(46, 46, Gravity.END or Gravity.BOTTOM))

        // 右上角关闭键：44×44 圆形（设计板 `s13-float`）
        val close = Ui.text(context, "✕", 24f, pal.textFaint).apply {
            gravity = Gravity.CENTER
            background = Ui.round(pal.surfaceSoft, 999f)
        }
        root.addView(
            close,
            LayoutParams(44, 44, Gravity.END or Gravity.TOP).apply {
                rightMargin = 22
                topMargin = 16
            },
        )

        val box = WindowBox(
            view = root,
            label = windowTitle,
            packageName = MEDIA_FLOAT_KEY,
            rect = RectF(),
            embedded = false,
        )
        box.rect = RectF(
            MediaFloatCard.CARD_LEFT.toFloat(),
            MediaFloatCard.CARD_TOP.toFloat(),
            (MediaFloatCard.CARD_LEFT + MediaFloatCard.CARD_W).toFloat(),
            (MediaFloatCard.CARD_TOP + MediaFloatCard.CARD_H).toFloat(),
        )
        close.setOnClickListener {
            Logx.event(LogTags.FLOAT, "媒体浮窗关闭按钮点击", "label" to windowTitle)
            close(box)
        }
        attachDrag(root, card, grip)

        windows.add(box)
        addView(root, layoutParamsOf(box))
        place(box)
        focus(box)
        Logx.event(
            LogTags.FLOAT,
            "媒体浮窗打开",
            "track" to track,
            "artist" to artist,
            "x" to MediaFloatCard.CARD_LEFT,
            "y" to MediaFloatCard.CARD_TOP,
        )
        onChanged?.invoke()
        onBubbleState?.invoke(minimizedWindows())
        return box
    }

    /** 刷新已打开的媒体浮窗内容（曲名 / 歌手 / 进度）。 */
    fun updateMediaFloat(track: String, artist: String, positionMs: Long, durationMs: Long) {
        val box = windows.firstOrNull { it.packageName == MEDIA_FLOAT_KEY } ?: return
        updateMediaFloat(box, track, artist, positionMs, durationMs)
    }

    private fun updateMediaFloat(box: WindowBox, track: String, artist: String, positionMs: Long, durationMs: Long) {
        val card = (box.view as? FrameLayout)?.getChildAt(0) as? MediaFloatCard ?: return
        card.trackTitle = track
        card.artist = artist
        card.positionMs = positionMs
        card.durationMs = durationMs
        card.invalidate()
    }

    /**
     * 关掉最老的窗口直到有位置。
     */
    private fun closeOldestUntilSlotAvailable() {
        while (windows.size >= MAX_WINDOWS) {
            val oldest = windows.first()
            // 排障场景：「浮窗开不到第三个 / 最老的那个被莫名关掉」——
            // 是「关掉最老的腾位」，这里明确落盘，避免被当成崩溃。
            Logx.w(
                LogTags.FLOAT,
                "浮窗数量达到上限，关闭最老的窗口腾位",
                "pkg" to oldest.packageName,
                "label" to oldest.label,
                "max" to MAX_WINDOWS,
            )
            close(oldest)
        }
    }

    private fun layoutParamsOf(box: WindowBox): LayoutParams =
        LayoutParams(box.view.layoutParams?.width ?: DEFAULT_W, box.view.layoutParams?.height ?: DEFAULT_H).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = box.rect.left.toInt()
            topMargin = box.rect.top.toInt()
        }

    private fun buildWindow(pkg: String, appLabel: String, avatar: Drawable?): WindowBox {
        val root = FrameLayout(context).apply {
            // 第四十九轮「浮屿」：浮窗卡片 = 纯扁平面 + 1px 描边，圆角 28。
            background = Ui.round(Skin.surface, com.cardesk.launcher.core.Shape.CARD, 1f, Skin.NAV_STROKE)
            isClickable = true
            clipToOutline = true
        }

        val embeddedView = tryEmbedActivity(pkg)
        val embedded = embeddedView != null
        // 排障场景：「浮窗是黑屏 / 降级成图标卡」——承载方式（真嵌入 / 降级）落盘。
        Logx.i(
            LogTags.FLOAT,
            "浮窗承载 Activity",
            "pkg" to pkg,
            "label" to appLabel,
            "mode" to if (embedded) "embedded" else "fallback",
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 0, 14, 0)
            background = Ui.round(0x33101826, 30f, 0f, 0)
        }
        val icon = ImageView(context)
        if (avatar != null) icon.setImageDrawable(avatar)
        header.addView(icon, Ui.linear(34, 34, 0f, 14))
        header.addView(Ui.text(context, appLabel, 24f, pal.text, bold = true), Ui.linear(0, -2, 1f))
        val modeLabel = if (embedded) "嵌入承载" else "降级承载"
        header.addView(Ui.text(context, modeLabel, 16f, pal.textDim), Ui.linear(-2, -2, 0f, 12))

        // 最小化按钮（item_float_window 的 fwMin）
        val min = Ui.text(context, "—", 22f, pal.textDim).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.CHIP_FILL, com.cardesk.launcher.core.Shape.PILL, 1f, Skin.CHIP_STROKE)
            setPadding(18, 10, 18, 10)
            pressFeedback()
        }
        header.addView(min, Ui.linear(-2, 42))

        val close = Ui.text(context, "✕", 22f, pal.textDim).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.CHIP_FILL, com.cardesk.launcher.core.Shape.PILL, 1f, Skin.CHIP_STROKE)
            setPadding(18, 10, 18, 10)
            pressFeedback()
        }
        header.addView(close, Ui.linear(-2, 42))
        root.addView(header, LayoutParams(-1, 58))

        if (embeddedView != null) {
            root.addView(embeddedView, LayoutParams(-1, -1).apply { topMargin = 58 })
        } else {
            root.addView(fallbackCard(appLabel, pkg), LayoutParams(-1, -1).apply { topMargin = 58 })
        }

        val grip = Ui.text(context, "◢", 20f, pal.textDim).apply { gravity = Gravity.CENTER }
        root.addView(grip, LayoutParams(46, 46, Gravity.END or Gravity.BOTTOM))

        attachDrag(root, header, grip)

        val box = WindowBox(
            view = root,
            label = appLabel,
            packageName = pkg,
            rect = RectF(),
            embedded = embedded,
            activityView = embeddedView,
            maxResizeW = DEFAULT_W,
            maxResizeH = DEFAULT_H,
        )
        close.setOnClickListener {
            // 排障场景：「浮窗标题栏的 ✕ / — 点不动」——按钮点击单独落盘。
            Logx.event(LogTags.FLOAT, "浮窗关闭按钮点击", "pkg" to pkg, "label" to appLabel)
            close(box)
        }
        min.setOnClickListener {
            Logx.event(LogTags.FLOAT, "浮窗最小化按钮点击", "pkg" to pkg, "label" to appLabel)
            minimize(box)
        }
        // 点根视图 → 全屏化 / 还原（`viewInflate.setOnClickListener(... fullscreen)`）
        root.setOnClickListener {
            Logx.event(LogTags.FLOAT, "浮窗点击：切换全屏", "pkg" to pkg, "label" to appLabel)
            toggleFullscreen(box)
        }
        return box
    }

    private fun fallbackCard(label: String, pkg: String): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        val pm = context.packageManager
        val icon = runCatching { ImageView(context).also { it.setImageDrawable(pm.getApplicationIcon(pkg)) } }.getOrNull()
        if (icon != null) {
            box.addView(icon, Ui.linear(96, 96))
        } else {
            box.addView(
                Ui.text(context, label.take(1), 44f, pal.text, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(pal.withAlpha(pal.accent, 0x66), 24f)
                },
                Ui.linear(96, 96),
            )
        }
        box.addView(
            Ui.text(context, label, 28f, pal.text, bold = true).apply { gravity = Gravity.CENTER },
            Ui.linear(-1, 44, 0f, 0, 16),
        )
        box.addView(
            Ui.text(
                context,
                "普通应用无法把其他应用嵌进自己的窗口（ActivityView 需系统权限）。\n窗口数量/拖动/缩放保持一致；可点「全屏打开」继续使用。",
                18f,
                pal.textDim,
            ).apply { gravity = Gravity.CENTER },
            Ui.linear(-1, 84),
        )
        box.addView(
            Ui.text(context, "全屏打开", 22f, pal.text).apply {
                background = Ui.round(pal.withAlpha(pal.accent, 0x40), 20f, 1f, pal.accent)
                setPadding(28, 12, 28, 12)
                pressFeedback()
                setOnClickListener {
                    // 排障场景：「降级浮窗里的『全屏打开』点了没反应」。
                    Logx.event(LogTags.FLOAT, "降级浮窗全屏打开点击", "pkg" to pkg, "label" to label)
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }.onFailure {
                        Logx.w(LogTags.FLOAT, "降级浮窗全屏打开失败", it, "pkg" to pkg)
                    }
                }
            },
            Ui.linear(-2, 52, 0f, 0, 12),
        )
        return box
    }

    /**
     * 做法
     * `new ActivityView(context, null, 0)` + `setCallback`，在 `onActivityViewReady` 里
     * `startActivity(intent)`，随后用内部 `mVirtualDisplay` 同步尺寸。
     *
     * 这一整套都是 @SystemApi / 隐藏 API：平台签名或 sharedUserId=android.uid.system 时可用，
     * 普通应用抛异常 → 返回 null，由调用方降级成卡片窗。
     */
    private fun tryEmbedActivity(pkg: String): View? =
        com.cardesk.launcher.ui.embed.ActivityEmbed.embed(context, pkg)

    /** 窗口尺寸变化时同步 VirtualDisplay，否则画面会拉伸/黑边（resizeVirtualDisplay）。 */
    private fun resizeVirtualDisplay(view: View, w: Int, h: Int) {
        com.cardesk.launcher.ui.embed.ActivityEmbed.resizeVirtualDisplay(view, w, h)
    }

    private fun attachDrag(root: View, header: View, grip: View) {
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        var originW = 0
        var originH = 0
        header.setOnTouchListener { _, ev ->
            val lp = root.layoutParams as? LayoutParams ?: return@setOnTouchListener false
            val box = windows.firstOrNull { it.view === root }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    originX = lp.leftMargin
                    originY = lp.topMargin
                    originW = lp.width
                    originH = lp.height
                    // 排障场景：「浮窗拖不动」——拖动开始落盘（含包名）。
                    Logx.event(
                        LogTags.FLOAT,
                        "浮窗拖动开始",
                        "pkg" to (box?.packageName ?: ""),
                        "label" to (box?.label ?: ""),
                        "x" to originX,
                        "y" to originY,
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // drag():576-580 —— 整层已按设计稿缩放，位移要除以累计缩放比
                    val scale = com.cardesk.launcher.core.Design.scaleFor(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    val dx = (ev.rawX - startX) / scale
                    val dy = (ev.rawY - startY) / scale
                    lp.leftMargin = (originX + dx)
                        .roundToInt()
                        .coerceIn(EDGE_X - originW, (Design.W.toInt() - EDGE_X))
                    lp.topMargin = (originY + dy)
                        .roundToInt()
                        .coerceIn(EDGE_TOP, (Design.H.toInt() - EDGE_BOTTOM))
                    root.layoutParams = lp
                    // 排障场景：「浮窗拖到边界就卡住」——位移每帧用 DEBUG，不落盘。
                    Logx.d(
                        LogTags.FLOAT,
                        "浮窗拖动中",
                        "dx" to dx,
                        "dy" to dy,
                        "left" to lp.leftMargin,
                        "top" to lp.topMargin,
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    syncRect(root)
                    // 排障场景：「浮窗拖完位置没记住」——最终位置落盘。
                    Logx.event(
                        LogTags.FLOAT,
                        "浮窗拖动结束",
                        "pkg" to (box?.packageName ?: ""),
                        "label" to (box?.label ?: ""),
                        "left" to lp.leftMargin,
                        "top" to lp.topMargin,
                        "cancel" to (ev.actionMasked == MotionEvent.ACTION_CANCEL),
                    )
                    true
                }
                else -> true
            }
        }
        var downX = 0f
        var downY = 0f
        var startW = 0
        var startH = 0
        grip.setOnTouchListener { _, ev ->
            val lp = root.layoutParams as? LayoutParams ?: return@setOnTouchListener false
            val box = windows.firstOrNull { it.view === root }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startW = lp.width
                    startH = lp.height
                    // 排障场景：「浮窗缩放把手点不动」——缩放开始落盘。
                    Logx.event(
                        LogTags.FLOAT,
                        "浮窗缩放开始",
                        "pkg" to (box?.packageName ?: ""),
                        "label" to (box?.label ?: ""),
                        "w" to startW,
                        "h" to startH,
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val scale = com.cardesk.launcher.core.Design.scaleFor(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    val dx = (ev.rawX - downX) / scale
                    val dy = (ev.rawY - downY) / scale
                    // drag():573-575 —— 上限是初始尺寸（maxResizeW/H），不能比初始大
                    val maxW = box?.maxResizeW?.takeIf { it > 0 } ?: startW
                    val maxH = box?.maxResizeH?.takeIf { it > 0 } ?: startH
                    lp.width = (startW + dx).roundToInt().coerceIn(MIN_W, maxW)
                    lp.height = (startH + dy).roundToInt().coerceIn(MIN_H, maxH)
                    root.layoutParams = lp
                    syncVirtualDisplay(box ?: return@setOnTouchListener true)
                    // 排障场景：「浮窗缩放超过初始尺寸 / 缩到很小」——每帧用 DEBUG。
                    Logx.d(
                        LogTags.FLOAT,
                        "浮窗缩放中",
                        "dx" to dx,
                        "dy" to dy,
                        "w" to lp.width,
                        "h" to lp.height,
                        "maxW" to maxW,
                        "maxH" to maxH,
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    syncRect(root)
                    // 排障场景：「浮窗缩放后尺寸没生效」——最终尺寸落盘。
                    Logx.event(
                        LogTags.FLOAT,
                        "浮窗缩放结束",
                        "pkg" to (box?.packageName ?: ""),
                        "label" to (box?.label ?: ""),
                        "w" to lp.width,
                        "h" to lp.height,
                        "cancel" to (ev.actionMasked == MotionEvent.ACTION_CANCEL),
                    )
                    true
                }
                else -> true
            }
        }
    }

    /**
     * 默认位置
     * 竖直方向居中于「场景页 assist 区 ↔ 底部卡」之间的空档，至少留 20px 顶距。
     *
     * 用 量出这段空档；本应用没有 Activity 视图树
     * 用设计稿里的等效位置（assist 区底部 ~ 300、底部卡顶部 ~ 780）来算。
     */
    private fun defaultTop(): Int {
        val gapTop = 300f
        val gapBottom = 780f
        val centered = ((gapTop + gapBottom) - DEFAULT_H) / 2f
        // 里两个窗口共用同一个纵向位置，靠 x 区分（28 / 972）
        return maxOf(EDGE_TOP, centered.roundToInt())
    }

    /**
     * 默认 x
     * 已有窗口占住左半屏时新窗口放右侧（972），否则放左侧（28）。
     */
    private fun defaultLeft(): Int {
        val occupiedLeft = windows.any {
            val lp = it.view.layoutParams as? LayoutParams
            (lp?.leftMargin ?: 0) < Design.W.toInt() / 2
        }
        return if (occupiedLeft) 972 else 28
    }

    /** 把窗口按当前 x/y/尺寸摆好。 */
    private fun place(box: WindowBox) {
        val lp = box.view.layoutParams as? LayoutParams ?: return
        lp.leftMargin = box.rect.left.toInt()
        lp.topMargin = box.rect.top.toInt()
        lp.width = if (lp.width > 0) lp.width else DEFAULT_W
        lp.height = if (lp.height > 0) lp.height else DEFAULT_H
        box.view.layoutParams = lp
        box.maxResizeW = lp.width
        box.maxResizeH = lp.height
        box.rect = RectF(lp.leftMargin.toFloat(), lp.topMargin.toFloat(), (lp.leftMargin + lp.width).toFloat(), (lp.topMargin + lp.height).toFloat())
    }

    private fun syncVirtualDisplay(box: WindowBox) {
        val av = box.activityView ?: return
        val lp = box.view.layoutParams as? LayoutParams ?: return
        resizeVirtualDisplay(av, lp.width, (lp.height - 58).coerceAtLeast(MIN_H))
    }

    private fun syncRect(root: View) {
        val lp = root.layoutParams as? LayoutParams ?: return
        windows.firstOrNull { it.view === root }?.rect =
            RectF(lp.leftMargin.toFloat(), lp.topMargin.toFloat(), (lp.leftMargin + lp.width).toFloat(), (lp.topMargin + lp.height).toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 只在窗口还没被用户拖动过（rect 为空）时给默认位置，
        // 否则会覆盖用户已经摆好的位置（没有这一步）
        windows.forEach { box ->
            if (box.rect.width() > 0f) return@forEach
            val lp = box.view.layoutParams as? LayoutParams ?: return@forEach
            lp.leftMargin = defaultLeft()
            lp.topMargin = defaultTop()
            box.view.layoutParams = lp
            box.rect = RectF(
                lp.leftMargin.toFloat(), lp.topMargin.toFloat(),
                (lp.leftMargin + lp.width).toFloat(), (lp.topMargin + lp.height).toFloat(),
            )
        }
    }

    companion object {
        const val MAX_WINDOWS = 2
        const val DEFAULT_W = 920
        const val DEFAULT_H = 676
        const val MIN_W = 340
        const val MIN_H = 240

        /** 媒体浮窗在浮窗体系里的去重键（不是真的包名）。 */
        const val MEDIA_FLOAT_KEY = "media://now-playing"

        /** 媒体浮窗卡片顶部的小标题。 */
        const val MEDIA_FLOAT_TITLE = "CarDesk 浮窗"

        /** 左右边距（design(160)）。 */
        const val EDGE_X = 160

        /** 顶边距（design(20)）。 */
        const val EDGE_TOP = 20

        /** 底边距（design(200)）。 */
        const val EDGE_BOTTOM = 200
    }
}
