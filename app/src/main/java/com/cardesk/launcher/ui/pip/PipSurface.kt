package com.cardesk.launcher.ui.pip

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.embed.ActivityEmbed

/**
 * 画中画（PIP）承载面。
 *
 * 把 PIP 画面挂在地图卡 里（`this.activityView`）
 * 画面本体是 `android.app.ActivityView` + `VirtualDisplay`，
 * 触摸要注入到虚拟显示、并把桌面自己的面板区域从触摸区域里扣掉。
 *
 * 这里做三件事：
 * 1. 能嵌就嵌（平台签名 / uid 1000 时 `ActivityView` 可用）；
 * 2. 不能嵌就降级成「应用卡片」（图标 + 名称 + 全屏打开），窗口位置一致；
 * 3. **没有承载应用时显示占位**
 *    绝不留一块黑屏。
 *
 * 触摸注入需要向 VirtualDisplay 发 `MotionEvent`，只有平台签名可用；
 * 降级模式下由 [onOpenFullscreen] 走全屏启动。
 */
class PipSurface(context: Context, private val pal: Palette) : FrameLayout(context) {

    /**
     * 本承载面的角色（`主画` / `副画`），只用于日志区分。
     *
     * 里主副画是两个独立的 （第二个挂在）
     * 我们用两个 [PipSurface] 实例分别承载。
     */
    var role: String = "主画"

    /** 点「全屏打开」时回调。 */
    var onOpenFullscreen: ((String) -> Unit)? = null

    /** 点占位（是 `clickable=true`）时回调。 */
    var onPickApp: (() -> Unit)? = null

    /**
     * 画中画圆角半径（**设计坐标 px**）。
     *
     *
     * tag 是 Float 就用它，否则 `DesignCanvas.m1183df(28.0f)`。
     *  在 1920 设计画布上原样返回 28
     * 所以默认 **28**，与我们其它卡片（`Skin.glass(28f)`）同一套圆角语言。
     */
    var cornerRadius: Float = RoundClip.DEFAULT_RADIUS
        set(value) {
            if (field == value) return
            field = value
            applyRoundClip()
        }

    /**
     * **内容区留白**（设计坐标 px）。
     *
     * ## 为什么需要
     *
     * 画中画承载面在导航页里是 `MATCH_PARENT` 高度
     *  都是 `match_parent`，
     * 而底部 Dock 栏（1864×104，距底 8）是**浮在导航页之上**的
     * （里 排在 之后）。
     *
     * 于是承载应用的画面底部约 **112px 被 Dock 压住** ——
     * 应用里的底部按钮/POI 标签点不到、也看不见。
     *
     * **组合卡**用 `paddingBottom=126`（根布局
     * `paddingTop=8 / paddingBottom=126`避开了这一段，但**画中画那张卡
     * 没有对应的留白** ——这里是缺陷。
     *
     * 我们按组合卡的口径给画中画也留出同样的上下边距，
     * 两栏顶部/底部对齐，画面不再被 Dock 压住。
     *
     * 全屏模式下置 0（画中画全屏就该铺满）。
     */
    var contentInsetTop: Int = 0
        set(value) {
            if (field == value) return
            field = value
            applyInset()
        }

    /** 内容区底部留白（设计坐标 px），见 [contentInsetTop]。 */
    var contentInsetBottom: Int = 0
        set(value) {
            if (field == value) return
            field = value
            applyInset()
        }

    /**
     * 把留白应用到承载内容与占位层。
     *
     * 承载内容（ActivityView / 降级卡片）与占位层都放在**带边距**的位置，
     * 可见区域就是「内容区」—— VirtualDisplay 的尺寸也按内容区算（见 [syncVirtualDisplay]），
     * 否则画面会按整个承载面的尺寸渲染再被裁掉一截。
     */
    private fun applyInset() {
        val top = contentInsetTop
        val bottom = contentInsetBottom
        placeholder.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            this.topMargin = top
            this.bottomMargin = bottom
        }
        placeholder.requestLayout()
        val v = content ?: return
        (v.parent as? ViewGroup)?.let { parent ->
            v.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                this.topMargin = top
                this.bottomMargin = bottom
            }
            parent.requestLayout()
        }
        syncVirtualDisplay()
    }

    /** 当前承载的内容（ActivityView 或降级卡片）。 */
    private var content: View? = null

    private var packageName: String = ""

    /** 承载应用的启动 Intent（看门狗重新拉起时复用）。 */
    private var launchIntent: Intent? = null

    /** 嵌入是否已就绪。 */
    private var ready: Boolean = false

    /**
     * 占位层。
     *
     * 布局是：`LinearLayout(gravity=center)` 里放
     * 44dp 的 （alpha 0.45）+ 12sp 的说明文字，**默认 visible** ——
     * 只有画中画真正启动起来才隐藏。
     * 所以永远不会出现「一块黑屏」，我们照抄这个行为。
     */
    private val placeholder = PlaceholderView(context)

    init {
        setBackgroundColor(0x00000000)
        addView(placeholder, insetParams())
        placeholder.visibility = View.VISIBLE
        // 占位底下垫一层玻璃（：tag="r28"
        // 由 控制「有应用时隐藏、显示占位时显示」。
        // 我们占位与承载内容互斥（占位可见 ⇔ 没在承载应用），所以直接垫在占位上。
        placeholder.background = Skin.glass(cornerRadius)
        placeholder.onClick = {
            Logx.event(LogTags.NAV_UI, "画中画占位点击（选应用）", "role" to role)
            onPickApp?.invoke()
        }
        applyRoundClip()
    }

    /**
     * 把圆角裁剪套到承载内容 / 占位层上。
     *
     * ## 裁在哪一层
     *
     * 承载内容（ActivityView）与占位层都带上下留白（见 [contentInsetTop]），
     * 所以圆角要落在**它们自己的边界**上 —— 画面的四个角才是真正被裁掉的地方。
     *
     * 承载应用那一路是 `ActivityView`（内部是 SurfaceView，独立图层）：
     * - 父级 `clipToOutline` 负责把窗口上擦出来的洞裁成圆角；
     * - `SurfaceView.setCornerRadius`（隐藏 API，Android 10+）负责把图层本身也裁圆。
     */
    private fun applyRoundClip() {
        val radius = cornerRadius
        if (radius <= 0f) return
        RoundClip.apply(placeholder, radius)
        content?.let { RoundClip.apply(it, radius) }
    }

    /** 承载内容 / 占位层用的 LayoutParams（带上下留白）。 */
    private fun insetParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = contentInsetTop
            bottomMargin = contentInsetBottom
        }

    /** 当前承载的包名（空表示没在承载任何应用）。 */
    val currentPackage: String get() = packageName

    /**
     * 切换到某个应用的画中画。
     *
     * 同包名且承载面还在就直接复用
     * 否则重建承载面。
     *
     * @return true 表示已经承载（嵌入成功或降级卡片已就位）
     */
    fun show(pkg: String, appName: String? = null): Boolean {
        if (pkg.isBlank()) {
            // 排障场景：「画中画切到空包名就黑了」——空包名要单独留痕。
            Logx.w(LogTags.NAV_UI, "画中画显示失败：包名为空", "role" to role)
            showPlaceholder(TEXT_PICK_APP)
            return false
        }
        // 显示名：调用方没给就自己查（降级卡片显示包名很难看，用户认不出是哪个应用）
        val label = appName?.takeIf { it.isNotBlank() } ?: labelOf(pkg)
        if (pkg == packageName && ready && content != null) {
            // 排障场景：「切换应用后画中画还是上一个」——重复包名直接复用时要说明。
            Logx.d(LogTags.NAV_UI, "画中画已承载该包名，复用当前画面", "pkg" to pkg, "role" to role)
            return true
        }
        clearContent()
        packageName = pkg
        launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(ActivityEmbed.PIP_LAUNCH_FLAGS)
        }.getOrNull()

        val view = ActivityEmbed.embed(context, pkg)
        if (view != null) {
            content = view
            ready = true
            addView(view, insetParams())
            placeholder.visibility = View.GONE
            // 圆角（→ `applyRoundClip`）
            // ActivityView 自身裁剪 + 内部 SurfaceView 裁剪。
            RoundClip.apply(view, cornerRadius)
            // 画面图层压在窗口之下 → 上方那三个点才不会被盖住
            //
            RoundClip.applySurfaceZOrder(view)
            post { syncVirtualDisplay() }
            startWatchdog()
            // 排障场景：「画中画不显示」——嵌入成功 / 降级必须能一眼看出来。
            Logx.event(
                LogTags.NAV_UI,
                "画中画显示",
                "pkg" to pkg,
                "app" to label,
                "mode" to "embedded",
                "role" to role,
            )
            return true
        }
        // 降级：应用卡片
        // ready 也要置位 —— 否则切页回来时 show() 会以为"换了应用"，
        // 把承载面拆了重建，里承载的应用是一直活着的。
        ready = true
        val card = fallbackCard(label, pkg)
        content = card
        addView(card, insetParams())
        // 降级卡片也是画中画区域 → 同样要圆角
        RoundClip.apply(card, cornerRadius)
        placeholder.visibility = View.GONE
        Logx.event(
            LogTags.NAV_UI,
            "画中画显示",
            "pkg" to pkg,
            "app" to label,
            "mode" to "fallback",
            "role" to role,
        )
        return true
    }

    /**
     * 显示占位。
     *
     * 三种文案
     * - 没选应用 → `请选择应用`
     * - 应用没装 → `未安装: <pkg>`
     *
     * 我们额外加了一条「与主画相同」——同一个应用不能同时挂到两个 VirtualDisplay，
     * 第二次启动会把它的 task 从主画拉走，主画直接黑屏（见 [LauncherView.refreshPip]）。
     */
    fun showPlaceholder(text: String) {
        clearContent()
        packageName = ""
        launchIntent = null
        ready = false
        stopWatchdog()
        placeholder.text = text
        placeholder.visibility = View.VISIBLE
        Logx.i(
            LogTags.NAV_UI,
            "画中画显示占位",
            "text" to text,
            "role" to role,
        )
    }

    /** 查应用显示名（查不到就退回包名）。 */
    private fun labelOf(pkg: String): String {
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
    }

    /**
     * 清掉承载内容（释放 ActivityView 的 VirtualDisplay）。
     *
     * —— 先释放承载面（setCallback(null) + release）
     * 不释放会漏 VirtualDisplay 和被承载应用的 task。
     */
    private fun clearContent() {
        val v = content
        content = null
        if (v != null) {
            ActivityEmbed.release(v)
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    fun hide() {
        val prev = packageName
        // —— 先释放承载面（setCallback(null) + release）
        // 不释放会漏 VirtualDisplay 和被承载应用的 task。
        clearContent()
        ready = false
        packageName = ""
        launchIntent = null
        stopWatchdog()
        // **不整块隐藏** ——这时是显示占位
        // 整块隐藏就是一块黑屏（没有这个状态）。
        placeholder.text = TEXT_PICK_APP
        placeholder.visibility = View.VISIBLE
        // 排障场景：「画中画关不掉 / 切应用后画面残留」——隐藏也要留痕。
        if (prev.isNotEmpty()) {
            Logx.event(LogTags.NAV_UI, "画中画隐藏", "pkg" to prev, "role" to role)
        } else {
            Logx.d(LogTags.NAV_UI, "画中画清空（原本没有承载应用）", "role" to role)
        }
    }

    // ------------------------------------------------------------------ 看门狗

    /**
     * 画中画看门狗。
     *
     * ##在做什么
     *
     * 状态报「在放」但被承载应用的**进程已经没了**时，ActivityView 还在、画面却是黑的 ——
     * 每 1 秒查一次进程
     * - 第 1 次没找到（重新把应用拉起来）；
     * - 第 2 次还没找到（把承载面整个重建）。
     *
     * ## 我们的保守化
     *
     * 进程判据是 `ActivityManager.getRunningAppProcesses()` 匹配 uid + 进程名。
     * 这个 API 在 Android 5.1 之后**只有特权进程能看到别人的进程**：
     * - system 版（uid 1000）能看到 → 判据可靠；
     * - debug 版（`com.cardesk.launcher`，非特权）只能看到自己 → 判据不可靠。
     *
     * 所以这里加了一道闸：**看不到别的进程就完全不动作**（返回 null = 不知道），
     * 避免 debug 版把好的画中画反复重建。
     */
    private val handler = Handler(Looper.getMainLooper())

    /** 连续几次没找到进程。 */
    private var watchdogMisses = 0

    private val watchdog = object : Runnable {
        override fun run() {
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            runCatching { checkProcess() }
                .onFailure { Logx.w(LogTags.NAV_UI, "画中画看门狗异常", it, "role" to role) }
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdog)
        watchdogMisses = 0
    }

    /** 看门狗的一轮检查。 */
    private fun checkProcess() {
        val pkg = packageName
        if (pkg.isBlank() || !ready || content == null) return
        // 承载面不可见（导航页不在前台）时不查 —— 这时 surface 都没了，
        // 进程可能在后台被系统回收，等回到这一页 surface 重建会自己拉起。
        if (!isShown) return
        when (isProcessAlive(pkg)) {
            null -> {
                // 判据不可靠（看不到别的进程）→ 不动作
                watchdogMisses = 0
            }
            true -> watchdogMisses = 0
            false -> {
                watchdogMisses++
                Logx.w(
                    LogTags.NAV_UI,
                    "画中画看门狗：承载进程不在",
                    "pkg" to pkg, "misses" to watchdogMisses, "role" to role,
                )
                if (watchdogMisses < WATCHDOG_MISS_LIMIT) return
                watchdogMisses = 0
                relaunch()
            }
        }
    }

    /**
     * 被承载应用的进程还在不在。
     *
     * @return true=在 / false=不在 / **null=判据不可靠**（看不到别的进程）
     */
    private fun isProcessAlive(pkg: String): Boolean? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val list = runCatching { am.runningAppProcesses }.getOrNull() ?: return null
        if (list.isEmpty()) return null
        // 只能看到自己进程的 App（debug 版）→ 判据不可靠
        if (list.none { it.uid != Process.myUid() }) return null
        val ai = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val proc = ai.processName
        return list.any { it.uid == ai.uid && it.processName == proc }
    }

    /** 重新把承载应用拉起来。 */
    private fun relaunch() {
        val v = content ?: return
        val intent = launchIntent ?: return
        Logx.w(
            LogTags.NAV_UI,
            "画中画看门狗：重新拉起承载应用",
            "pkg" to packageName, "role" to role,
        )
        ActivityEmbed.startActivity(v, intent)
    }

    /**
     * 显示尺寸变了之后，重新给承载应用发一次启动，让它按新尺寸重排。
     *
     * ## 为什么需要
     *
     * 承载应用是在 VirtualDisplay 上跑的，VirtualDisplay 改尺寸后，
     * 应用**不一定**会收到配置变化并重新排版：
     * 模拟器实测（高德）：画中画全屏（1920 宽）→ 还原（1363 宽）之后，
     * 画面要么按旧尺寸绘制（被放大裁切），要么地图图层直接黑掉。
     *
     * 对这种情况是**重新发一次启动**的
     * （：resize 稳定 240ms 后重新加载）。
     * 我们只在「全屏 ↔ 还原」这一条路径上用 —— 分隔条拖比例时尺寸一直在变，
     * 那条路上重新发启动会把应用反复重启（也是明确跳过拖动中的重载）。
     */
    fun relaunchAfterResize(delayMs: Long = RELAUNCH_AFTER_RESIZE_MS) {
        if (content == null || packageName.isBlank()) return
        handler.removeCallbacks(relaunchRunnable)
        handler.postDelayed(relaunchRunnable, delayMs)
    }

    private val relaunchRunnable = Runnable {
        if (content == null || packageName.isBlank()) return@Runnable
        Logx.i(
            LogTags.NAV_UI,
            "画中画尺寸变化后重新拉起承载应用",
            "pkg" to packageName,
            "role" to role,
        )
        relaunch()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (content != null) syncVirtualDisplay()
    }

    /**
     * 把 VirtualDisplay 尺寸同步成**内容区**尺寸。
     *
     * 关键：不能按整个承载面的尺寸同步 —— 承载面上下有留白，
     * 按整个尺寸渲染的话，应用画面会铺满到留白区里，
     * 底部一截仍然压在 Dock 栏底下（用户看到的「上下铺满、被 Dock 遮挡」）。
     */
    private fun syncVirtualDisplay() {
        val av = content ?: return
        val w = width
        val h = height - contentInsetTop - contentInsetBottom
        if (w <= 0 || h <= 0) return
        ActivityEmbed.resizeVirtualDisplay(av, w, h)
    }

    /** 降级卡片：图标 + 名称 + 「全屏打开」。 */
    private fun fallbackCard(label: String, pkg: String): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            background = Skin.glass(26f, true, Skin.GLASS_STRONG_FILL)
            isClickable = true
            setOnClickListener {
                // 排障场景：「画中画显示降级卡片」——点它会走全屏启动。
                Logx.event(LogTags.NAV_UI, "画中画降级卡片点击（转全屏）", "pkg" to pkg)
                onOpenFullscreen?.invoke(pkg)
            }
        }
        val icon = android.widget.ImageView(context)
        runCatching {
            val pm = context.packageManager
            icon.setImageDrawable(pm.getApplicationIcon(pkg))
        }
        column.addView(icon, LinearLayout.LayoutParams(96, 96))
        val name = Ui.text(context, label, 22f, Skin.TEXT)
        column.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12 })
        val hint = Ui.text(context, "画中画不可用，点按全屏打开", 15f, Skin.TEXT_DIM)
        column.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6 })
        return column
    }

    /**
     * 占位视图。
     *
     * 版式：`LinearLayout(gravity=center)` =
     * 44dp 的「+」图标（alpha 0.45，marginBottom 8dp）+ 12sp 说明文字。
     * 图标是自己的资源 不能复制 → 这里 Canvas 自绘。
     */
    private class PlaceholderView(context: Context) : View(context) {

        /** 说明文字。 */
        var text: String = TEXT_PICK_APP
            set(value) {
                field = value
                invalidate()
            }

        /** 点击占位 → 打开应用选择器（是 `clickable=true`）。 */
        var onClick: (() -> Unit)? = null

        init {
            isClickable = true
            setOnClickListener { onClick?.invoke() }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 第五十一轮：这里原来写死了夜间的浅蓝（`0x73E6EEFA` / `0x99E6EEFA`），
        // 日间模式下白底上几乎看不见（整个地图卡变成一张白卡）。
        // 改成跟随主题的弱文字色，并在 onDraw 里取，保证日夜都对得上。
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Skin.TEXT_SOFT
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            textSize = TEXT_SIZE
            color = Skin.TEXT_DIM
        }

        override fun onDraw(canvas: Canvas) {
            iconPaint.color = Skin.TEXT_SOFT
            labelPaint.color = Skin.TEXT_DIM
            val cx = width / 2f
            // 图标 44dp ≈ 70px（项目口径 1dp ≈ 1.6px）
            val iconSize = ICON_SIZE
            val iconTop = (height - iconSize - LABEL_GAP - TEXT_SIZE) / 2f
            // 「+」：圆圈 + 十字（alpha 0.45）
            val r = iconSize / 2f
            iconPaint.alpha = 160
            canvas.drawCircle(cx, iconTop + r, r - iconPaint.strokeWidth, iconPaint)
            val arm = r * 0.42f
            canvas.drawLine(cx - arm, iconTop + r, cx + arm, iconTop + r, iconPaint)
            canvas.drawLine(cx, iconTop + r - arm, cx, iconTop + r + arm, iconPaint)
            // 说明文字
            labelPaint.alpha = 255
            canvas.drawText(text, cx, iconTop + iconSize + LABEL_GAP + TEXT_SIZE * 0.8f, labelPaint)
        }
    }

    companion object {
        /** 「请选择应用」。 */
        const val TEXT_PICK_APP = "请选择应用"

        /** 双画选了同一个应用时的提示（我们补的，靠设置页拦截）。 */
        const val TEXT_SAME_AS_MAIN = "与主画相同，无法同时承载"

        /** 占位图标尺寸（44dp × 1.6）。 */
        const val ICON_SIZE = 70f

        /** 图标与文字的间距（8dp × 1.6）。 */
        const val LABEL_GAP = 13f

        /** 占位文字字号（12sp × 1.6）。 */
        const val TEXT_SIZE = 19f

        /** 看门狗检查间隔（这里放慢到 3s 降低误判影响）。 */
        const val WATCHDOG_INTERVAL_MS = 3_000L

        /** 连续几次没找到进程才动手（是 miss 1 软恢复、miss 2 重建；这里更保守）。 */
        const val WATCHDOG_MISS_LIMIT = 3

        /**
         * 尺寸变化后重新拉起承载应用的延迟（毫秒）。
         *
         * 等的是 240ms
         * 这里取 300ms 留够布局时间。
         */
        const val RELAUNCH_AFTER_RESIZE_MS = 300L
    }
}
