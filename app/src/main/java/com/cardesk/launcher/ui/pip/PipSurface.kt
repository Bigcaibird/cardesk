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
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.HomeForeground
import com.cardesk.launcher.core.HostTaskDisplay
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.PipRenderPlan
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.embed.ActivityEmbed
import com.cardesk.launcher.ui.embed.HostTaskMigration
import kotlin.math.roundToInt

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
    var role: String = ROLE_MAIN
        set(value) {
            field = value
            // 窗头胶囊跟角色走：主画 = 「画中画一」、副画 = 「画中画二」（设计稿 09-pip）。
            header.badge = badgeFor(value)
        }

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
     * ## 这个机制是干什么的
     *
     * 底部 Dock 栏（铺满屏宽×112、贴底）是**浮在页面之上**的，
     * 而画中画承载面在导航页里是 `MATCH_PARENT` 高度 ——
     * 万一承载面的底边伸进 Dock 那一段，应用里的底部按钮/POI 标签就点不到、也看不见。
     * 留白就是给这种情况用的：把承载内容整体收进来。
     *
     * ## 当前取值为什么是 0（第159轮实测订正）
     *
     * **当前布局下并不需要留白** —— 导航页整块内容已经被压到 Dock 顶边之上了：
     *
     * - `LauncherView` 把导航页高度设成 `DOCK_TOP_Y - navTop`（底边 = Dock 顶边 968）；
     * - 导航行再有 `bottomMargin = NAV_BOTTOM_GAP`（28）。
     *
     * 于是画中画卡底边 = 968 − 28 = **940**，在 Dock 顶边 968 **上方 28px** ——
     * 内容**从来没有被挡过**（离屏渲染逐像素实测：卡片描边底边 y=939）。
     *
     * ⚠️ 这段注释以前写的是「画面底部约 112px 被 Dock 压住」「组合卡用
     * `paddingBottom=126` 避开了这一段，但画中画那张卡没有对应的留白 —— 这里是缺陷」。
     * 那是**第 87 轮的老状态**：导航页在第 49 轮整体重排过，缺陷早没了，
     * 注释却没跟着更新 —— 第159轮据此误判并加了一段多余的留白，已撤销。
     * **注释会过期，量像素才是事实。**
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
        // 窗头**叠在画面之上**，不再把画面整体下移 40px。
        //
        // 试过「画面从窗头下方开始 + VirtualDisplay 高度 -40」：ActivityView 的
        // surface 尺寸与虚拟屏不一致，画面直接黑屏（13:01 实测左卡纯黑）。
        // 保持虚拟屏满高、窗头半透明压在顶部 40px 上，功能与出画都正常。
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

    /**
     * 第226轮：窗口透明时的承载宿主（自建虚拟屏 + `TextureView` 镜像）。
     *
     * 只在 [translucentWindow] 为 true 时创建；释放走 [clearContent]。
     */
    private var mirrorHost: PipMirrorHost? = null

    /**
     * 窗口当前是否**半透明**（视频壁纸走 `SurfaceView` 时为 true，第226轮）。
     *
     * 半透明窗口下承载层的图层不再被窗口轮廓裁剪（圆角失效、错位、切页残留），
     * 所以承载面要改走「窗口内渲染」（[PipMirrorHost]）。由 `LauncherView.refreshPip()`
     * 在每次切页前同步。
     */
    var translucentWindow: Boolean = false

    /**
     * 画面探针（**只有嵌入式承载才有**；降级卡片是静态的，不需要）。
     *
     * 用来判「画中画是不是黑了」→ 黑了就自动重新拉起承载应用
     * （见 [checkBlackScreen]）。
     */
    private var frameProbe: PipFrameProbe? = null

    /** 上次黑屏自愈的时刻（冷却用，避免连着重启承载应用）。 */
    private var lastBlackHealAt = 0L

    private var packageName: String = ""

    /** 承载应用的启动 Intent（看门狗重新拉起时复用）。 */
    private var launchIntent: Intent? = null

    /**
     * 这一轮承载面**自愈到第几级**（第130轮）。
     *
     * 原来是 `blackHealDone: Boolean`（**一次性**）——
     * 自愈一次不奏效就**永久黑屏**，因为再也不会有第二次机会。
     *
     * 现在改成**分级**：
     * - [PipBlackHeal.STAGE_NONE]：还没自愈过；
     * - [PipBlackHeal.STAGE_RELAUNCH]：已经重发过一次启动；
     * - [PipBlackHeal.STAGE_REBUILD]：已经重建过一次承载面
     *   （再黑就**放弃** —— 反复重启承载应用比一直黑更烦人）。
     */
    private var blackHealStage = PipBlackHeal.STAGE_NONE

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

    /**
     * 窗头（设计稿 `09-pip`）：应用图标 + 应用名 + 「画中画一/二」胶囊 + 右侧 ⋮。
     *
     * **叠在画面之上**：窗头固定 [HEADER_H] 高，画面仍从卡顶开始（窗头半透明压在顶部）
     * （见 [insetParams] / [syncVirtualDisplay]），画面从窗头下面开始 —— 与画板一致。
     *
     * 手势：窗头这一条自己吃掉触摸（[setOnTouchListener]），既当**拖动把手**，
     * 又不让这一条上的手势漏给下面的页面；画面区域在它下面，点击不受影响。
     */
    private val header = PipHeaderView(context).apply {
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            // 把窗头这一条的事件全部吃掉：它只是把手，不做点击。
            true
        }
    }

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
        // 窗头最后加：它压在画面顶部（半透明玻璃条），保留应用的 3 点/标题不被裁。
        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, HEADER_H, Gravity.TOP),
        )
        header.badge = badgeFor(role)
        header.bind(TEXT_PICK_APP, null)
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

    /** 承载内容 / 占位层用的 LayoutParams（带上下留白 + 窗头）。 */
    private fun insetParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = contentInsetTop
            bottomMargin = contentInsetBottom
        }

    /** 当前承载的包名（空表示没在承载任何应用）。 */
    val currentPackage: String get() = packageName

    /**
     * 现在**真的有承载内容**吗（第139轮）。
     *
     * 和 [currentPackage] 的区别：包名记着不代表画面还在 ——
     * 换肤重建时我们会把承载内容**摘走**（[detachContent]），
     * 那之后包名还在、但承载内容已经不在别人手上了。
     */
    val hasContent: Boolean get() = content != null

    /**
     * 当前承载内容**实际**走的渲染路线（第228轮）。
     *
     * null = 没在承载 / 降级成静态卡片（两条路线都不成立）。
     *
     * ## 为什么要有它
     *
     * 窗口的透明性会**在运行中变**（用户开关视频壁纸 ⇒ `applyVideoWindowFormat`
     * 改窗口 format）。这时「应该走哪条路线」变了，但承载面还挂着**旧路线**的内容：
     *
     * - 窗口变不透明了，承载面却还是镜像路线（`TextureView`）⇒
     *   与「不用视频壁纸」时的老行为（`ActivityView`）不一致 ⇒ 全屏 / 拖动都别扭；
     * - 窗口变半透明了，承载面却还是 `ActivityView` ⇒ 圆角失效、错位、切页残留。
     *
     * ⇒ `show()` 要拿它跟「现在该走的路线」比一比，**不一样就重建**。
     */
    private var activeMode: PipRenderPlan.Mode? = null

    /**
     * 切换到某个应用的画中画。
     *
     * 同包名且承载面还在、**路线也还对得上**就直接复用；
     * 否则重建承载面（路线变了也必须重建 —— 见 [activeMode]）。
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
        // 窗头：应用图标 + 应用名（「画中画一/二」胶囊由 [role] 决定）
        header.bind(label, pkg)
        // 窗头必须**始终在最上层**：承载内容是在 init 之后才 addView 的，
        // 后加的子 View 会盖住先加的窗头（13:04 实测卡上完全看不到窗头）。
        header.bringToFront()
        val wantMode = PipRenderPlan.modeFor(translucentWindow)
        if (pkg == packageName && ready && content != null && !PipRenderPlan.needsRebuild(activeMode, wantMode)) {
            // 排障场景：「切换应用后画中画还是上一个」——重复包名直接复用时要说明。
            Logx.d(LogTags.NAV_UI, "画中画已承载该包名，复用当前画面", "pkg" to pkg, "role" to role)
            return true
        }
        if (pkg == packageName && ready && content != null) {
            // 第228轮：**路线变了**（窗口透明性变了）⇒ 必须重建，
            // 否则承载的还是上一条路线的内容（用户反馈：「关掉视频壁纸后全屏/拖动不对」）。
            Logx.event(
                LogTags.NAV_UI,
                "画中画路线变了，重建承载面",
                "pkg" to pkg,
                "role" to role,
                "from" to (activeMode?.name ?: "none"),
                "to" to wantMode.name,
            )
        }
        clearContent()
        packageName = pkg
        launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(ActivityEmbed.PIP_LAUNCH_FLAGS)
        }.getOrNull()

        return embedContent(pkg, label)
    }

    /**
     * **真正把承载面建起来**（第130轮从 [show] 里抽出来）。
     *
     * 抽出来是因为黑屏自愈的**第二级**（重建承载面）要用同一套动作 ——
     * 两处各写一份的话，改一处忘了另一处，黑屏自愈就又坏了。
     *
     * @return true = 承载面已就位（嵌入成功，或降级成卡片）
     */
    private fun embedContent(pkg: String, label: String): Boolean {
        // 第228轮：路线按「窗口是不是半透明」选（见 `core/PipRenderPlan.kt`），
        // 主路线建不起来就换另一条，两条都不行才降级成静态卡片。
        //
        // 真机实测（第228轮）：
        // - 窗口不透明 ⇒ `ActivityView`：圆角成立、**触控自带**、切全屏满宽；
        // - 窗口半透明 ⇒ 镜像（`TextureView`）：圆角成立，但**要自己转发触摸**
        //   且建屏后必须 `dontOverrideDisplayInfo`（否则切全屏右侧缺一块）。
        val plan = PipRenderPlan.modeFor(translucentWindow)
        if (plan == PipRenderPlan.Mode.MIRROR) {
            if (embedMirror(pkg, label)) return true
            Logx.w(
                LogTags.NAV_UI,
                "画中画：镜像路线建不起来，改走 ActivityView",
                "pkg" to pkg,
                "role" to role,
            )
            if (embedActivityView(pkg, label)) return true
        } else {
            if (embedActivityView(pkg, label)) return true
            Logx.w(
                LogTags.NAV_UI,
                "画中画：ActivityView 不可用，改走镜像路线兜底",
                "pkg" to pkg,
                "role" to role,
            )
            if (embedMirror(pkg, label)) return true
        }
        // 降级：应用卡片
        // ready 也要置位 —— 否则切页回来时 show() 会以为"换了应用"，
        // 把承载面拆了重建，里承载的应用是一直活着的。
        ready = true
        activeMode = null
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
     * 第228轮：`ActivityView` 路线（承载层是独立图层，窗口在它上面挖洞）。
     *
     * 窗口**不透明**时走这条：圆角由窗口挖洞决定、**触摸由 `ActivityView` 自带转发**
     * （AOSP 9 `ActivityView.onTouchEvent` → `InputManager.createInputForwarder`）。
     *
     * @return true = `ActivityView` 嵌入成功
     */
    private fun embedActivityView(pkg: String, label: String): Boolean {
        val view = ActivityEmbed.embed(context, pkg) ?: return false
        content = view
        ready = true
        activeMode = PipRenderPlan.Mode.LAYER
        addView(view, insetParams())
        placeholder.visibility = View.GONE
        // 圆角（→ `applyRoundClip`）
        // ActivityView 自身裁剪 + 内部 SurfaceView 裁剪。
        RoundClip.apply(view, cornerRadius)
        // 画面图层压在窗口之下 → 上方那三个点才不会被盖住
        //
        RoundClip.applySurfaceZOrder(view)
        // 画面探针：黑屏自愈用（降级卡片不需要）
        frameProbe = PipFrameProbe(view)
        // 第130轮：本轮还没自愈过（分级自愈的起点）。
        blackHealStage = PipBlackHeal.STAGE_NONE
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

    /**
     * 第226轮：窗口内渲染（镜像路线）的承载面建起来。
     *
     * 与 [embedContent] 的区别只有「画面画到哪」：
     * 这里自己建 `VirtualDisplay`、把输出接到 `TextureView`，
     * 承载应用用 `ActivityOptions.setLaunchDisplayId` 起在那块屏上。
     * 其余（圆角、探针、看门狗、虚拟屏 id 登记）都一样。
     */
    private fun embedMirror(pkg: String, label: String): Boolean {
        val host = PipMirrorHost(context)
        mirrorHost = host
        content = host.view
        ready = true
        activeMode = PipRenderPlan.Mode.MIRROR
        addView(host.view, insetParams())
        placeholder.visibility = View.GONE
        // 普通 View → `clipToOutline` 圆角成立（这正是本路线存在的理由）
        RoundClip.apply(host.view, cornerRadius)
        frameProbe = PipFrameProbe(host.view)
        blackHealStage = PipBlackHeal.STAGE_NONE
        host.start(pkg, launchIntent) { displayId ->
            // 登记到 ActivityEmbed：搬移守护 / 黑屏自愈都靠它拿「我们的虚拟屏 id」
            ActivityEmbed.bindHostedDisplay(host.view, displayId)
            HostTaskMigration.schedule(host.view, pkg)
            post { syncVirtualDisplay() }
        }
        startWatchdog()
        Logx.event(
            LogTags.NAV_UI,
            "画中画显示",
            "pkg" to pkg,
            "app" to label,
            "mode" to "mirror",
            "role" to role,
        )
        return true
    }

    /**
     * 把承载内容**摘出来**（**不释放** `VirtualDisplay`），交给别的承载面接管（第139轮）。
     *
     * ## 为什么要有「摘 / 接」这一对
     *
     * 换肤重建（`LauncherView.build`）以前是「先 `release` 旧承载面、再建新的」，
     * 而 [release] 会**销毁旧的 `VirtualDisplay`** ——
     * 挂在它上面的承载应用 task 会被系统**搬到主显示器**
     * （Android 9 的 `ActivityStackManager` 就是这么处理的；
     * AOSP 的 VirtualDisplay Launcher 示例也是靠 `moveTaskToDisplay`
     * 把 task 从默认显示器搬回虚拟显示器）。
     *
     * → 高德的 task 被搬到 display 0 → **高德从画中画变成全屏抢屏**
     * （用户第139轮报的现象），画中画那边只剩一块黑。
     *
     * 现在改成「**摘走 → 释放外壳 → 交给新承载面**」：
     * `VirtualDisplay` 全程不死，承载应用的 task 一直在我们的 display 上
     * → 既不会被搬到主屏抢屏，也不会被重启（正在导航的路线不中断）。
     *
     * ## 注意
     *
     * 1. 摘的时候**不能**调 [ActivityEmbed.release] —— 那一脚就是销毁 `VirtualDisplay`；
     * 2. 搬家过程中 surface 会销毁重建一次，那一次**不重新拉起**承载应用
     *    （见 [ActivityEmbed.suppressNextRelaunch]）——
     *    否则高德会被**从头再跑一遍**（退回首页、导航中断）。
     *
     * @return 摘出来的承载 View（没有承载内容时返回 null）
     */
    fun detachContent(): View? {
        val v = content ?: return null
        content = null
        frameProbe = null
        stopWatchdog()
        (v.parent as? ViewGroup)?.removeView(v)
        // 搬家必然让 surface 销毁重建一次 —— 那一轮**不重新拉起**承载应用。
        ActivityEmbed.suppressNextRelaunch(v)
        Logx.i(
            LogTags.NAV_UI,
            "画中画承载面摘出（VirtualDisplay 保留）",
            "pkg" to packageName,
            "role" to role,
        )
        return v
    }

    /**
     * 第228轮：把**镜像宿主**也摘出来（**不释放**它的虚拟屏），交给新承载面。
     *
     * ## 为什么镜像宿主也要摘
     *
     * [detachContent] 只摘了承载 View（`TextureView`），
     * **镜像宿主（`PipMirrorHost`）还留在旧承载面上** ⇒
     * 旧承载面随后被 `release` ⇒ 它的 `clearContent()` 会把镜像宿主释放掉
     * ⇒ **虚拟屏被销毁** ⇒ 承载应用的 task 被推回主屏（高德抢屏）+ 应用被重启一遍。
     *
     * 镜像路线必须和 `ActivityView` 路线一样「整块搬家」。
     *
     * @return 摘出来的镜像宿主（不是镜像路线时返回 null）
     */
    fun detachMirrorHost(): PipMirrorHost? {
        val h = mirrorHost ?: return null
        mirrorHost = null
        Logx.i(
            LogTags.NAV_UI,
            "画中画镜像宿主摘出（虚拟屏保留）",
            "pkg" to packageName,
            "role" to role,
        )
        return h
    }

    /**
     * 接管 [detachContent] 摘出来的承载内容（第139轮）。
     *
     * 接管之后这块承载面**就是原来那块** —— `VirtualDisplay`、承载应用的 task
     * 全都没变，所以画面是连续的（不会黑一下，也不会把应用重启一遍）。
     *
     * @param view 摘出来的承载 View
     * @param pkg 它承载的应用包名
     * @param host 摘出来的镜像宿主（镜像路线才有；给了就说明这条承载面走的是镜像路线）
     * @return true = 接管成功
     */
    fun adoptContent(view: View, pkg: String, host: PipMirrorHost? = null): Boolean {
        if (content != null) return false
        packageName = pkg
        // 窗头：接管过来的承载面也要按新包名刷图标/名称
        header.bind(labelOf(pkg), pkg)
        header.bringToFront()
        launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(ActivityEmbed.PIP_LAUNCH_FLAGS)
        }.getOrNull()
        content = view
        ready = true
        // 第228轮：镜像宿主一起接管（否则新承载面不知道自己的虚拟屏 id，
        // resize / 重新拉起 / 释放全都做不了）。
        mirrorHost = host
        activeMode = if (host != null) PipRenderPlan.Mode.MIRROR else PipRenderPlan.Mode.LAYER
        addView(view, insetParams())
        placeholder.visibility = View.GONE
        // 圆角 / 图层顺序：跟 [embedContent] 一样
        // （换肤之后圆角可能变了，要重套一次）。
        RoundClip.apply(view, cornerRadius)
        RoundClip.applySurfaceZOrder(view)
        frameProbe = PipFrameProbe(view)
        blackHealStage = PipBlackHeal.STAGE_NONE
        post { syncVirtualDisplay() }
        startWatchdog()
        Logx.event(
            LogTags.NAV_UI,
            "画中画显示",
            "pkg" to pkg,
            "app" to labelOf(pkg),
            "mode" to "transferred",
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
        header.bringToFront()
        // 空窗也保留窗头（只是图标退化成占位圆点、名称换成提示文案）。
        header.bind(text, null)
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
        frameProbe = null
        activeMode = null
        // 第226轮：镜像路线自己建的虚拟屏要自己释放（`ActivityView.release` 管不到它）
        mirrorHost?.let { host ->
            ActivityEmbed.unbindHostedDisplay(host.view)
            host.release()
            mirrorHost = null
        }
        if (v != null) {
            ActivityEmbed.release(v)
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    /**
     * 第225轮：把承载层**藏起来但别拆**（页切走了还要留着画面）。
     *
     * ## 为什么必须显式藏
     *
     * 画中画挂在导航页的地图卡里；切到首页时导航页被 `restPage()` 置成 `GONE`，
     * 但承载层内部的 `SurfaceView` 是**独立图层**，AOSP 9 上祖先变 `GONE`
     * 并不会可靠地把它从 SurfaceFlinger 合成里摘掉 —— 实测：切到首页后，
     * 首页原画中画位置残留一块**黑色圆角矩形**（22:17 截图），画中画置空就没有。
     *
     * 这里用 `INVISIBLE`（不是 `GONE`、更不是 [hide]）：
     * 不释放 `ActivityView` / `VirtualDisplay` / 承载应用的 task，只是让图层不出图，
     * 切回来时原画面还在（不重启应用）。
     */
    fun setContentVisible(visible: Boolean) {
        val v = content ?: return
        // **不要改 visibility**：实测 `INVISIBLE` 会让 `ActivityView` 把
        // VirtualDisplay 的 surface 拆掉，切回来画面**再也回不来**（一直黑）。
        // 改成「整块挪出屏外」：图层位置随 View 的 translation 走，
        // 切回来挪回原位，承载面全程不拆 —— 应用不重启、画面不丢。
        val target = if (visible) 0f else -OFFSCREEN_SHIFT
        if (v.translationX == target) return
        v.translationX = target
        Logx.d(
            LogTags.NAV_UI,
            "画中画承载层移位",
            "visible" to visible,
            "translationX" to target,
            "pkg" to packageName,
            "role" to role,
        )
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
        header.bind(TEXT_PICK_APP, null)
        // 排障场景：「画中画关不掉 / 切应用后画面残留」——隐藏也要留痕。
        if (prev.isNotEmpty()) {
            Logx.event(LogTags.NAV_UI, "画中画隐藏", "pkg" to prev, "role" to role)
        } else {
            Logx.d(LogTags.NAV_UI, "画中画清空（原本没有承载应用）", "role" to role)
        }
    }

    /**
     * **彻底拆掉承载面**（释放 `ActivityView` / `VirtualDisplay`）。
     *
     * ## 为什么 [hide] 不够（第十二轮）
     *
     * [hide] 是「用户把画中画关掉」，它会把包名清掉并显示占位；
     * 而桌面**换肤重建**（`LauncherView.build`）时，旧的那套承载面必须
     * **先释放掉**，否则会出两件事：
     *
     * 1. **泄漏**：旧的 `ActivityView` + `VirtualDisplay` 还活着，
     *    反复切日夜就会在车机上攒出一堆 VirtualDisplay 和残留 task；
     * 2. **新承载面黑屏**（用户报的现象）：
     *    高德的 task 还挂在**旧 VirtualDisplay** 上，
     *    新承载面用同一套 `NEW_TASK | RESET_TASK_IF_NEEDED` 旗标再启动一次高德时，
     *    系统会把**旧 task 拉到前台** —— 而旧 task 在旧 display 上 →
     *    **新承载面里就只剩一块黑**。
     *
     * 用户的绕法是「全屏一次再还原」：那条路会 `relaunchAfterResize`
     * 重新下发启动，把 task 搬到当前 display 上 —— 于是恢复了。
     *
     * 所以这里在重建前把旧承载面**拆干净**，让新承载面能干净地接管。
     *
     * @param reason 释放原因（只进日志，便于事后对账）
     */
    fun release(reason: String) {
        val prev = packageName
        stopWatchdog()
        clearContent()
        ready = false
        packageName = ""
        launchIntent = null
        blackHealStage = PipBlackHeal.STAGE_NONE
        // 承载内容没了 → 占位回来（**绝不留一块黑屏**）
        placeholder.text = TEXT_PICK_APP
        placeholder.visibility = View.VISIBLE
        header.bind(TEXT_PICK_APP, null)
        if (prev.isNotEmpty()) {
            Logx.i(
                LogTags.NAV_UI,
                "画中画承载面释放",
                "pkg" to prev,
                "role" to role,
                "reason" to reason,
            )
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

    /** 「承载任务在哪个 display」诊断日志的限频计数（见 [logTaskDisplay]）。 */
    private var taskDisplayLogTick = 0

    /** 上一次「承载任务是否在我们的 display 上」（变化时总记日志）。 */
    private var lastTaskOnOurDisplay: Boolean? = null

    /** 上一次解析出来的「我们的 VirtualDisplay id」（变化时总记日志，见 [logOurDisplayId]）。 */
    private var lastOurDisplayId: Int? = null

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
        // —— 第130轮：桌面不在前台 → 不做黑屏自愈 ——
        //
        // 用户点「全屏打开」把应用拉到全屏时，它的 task 确实从我们的
        // VirtualDisplay **搬到了默认显示器**上 —— 这时候自愈会把应用
        // **抢回画中画**，打断用户正在用的全屏应用（比黑屏更烦人）。
        //
        // 桌面回到前台后（onResume）看门狗自然恢复工作，那时才该自愈。
        if (!HomeForeground.isForeground) return
        // —— 黑屏自愈（第六十七轮）：进程活着但画面没了的情形
        checkBlackScreen()
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
                relaunch("watchdog")
            }
        }
    }

    /**
     * 黑屏自愈（第六十七轮；第130轮重做了判据）。
     *
     * ## 现象
     *
     * 画中画**黑屏**，用户要「全屏一次再还原画中画」才看得到。
     *
     * 也就是说：**承载应用的进程还在，但它的 task 已经不在 VirtualDisplay 上了**
     * （Activity 被系统回收 / 应用自己退出了 / 换肤重建后 task 挂到了旧 display）
     * → ActivityView 里只剩一块黑。
     *
     * ## 第130轮：判据换成「task 还在不在我们的 display 上」
     *
     * 原来的判据是**像素**（`PixelCopy` 采一帧看是不是黑的），
     * 但实测（模拟器 + 高德）：
     *
     * ```
     * 画中画画面采样：状态变化 black=true maxLuma=0 avgLuma=0
     * ```
     *
     * 同一时刻屏幕截图上高德明明在渲染地图 ——
     * 因为 `ActivityView` 内部是 `SurfaceView`，画面画在**独立图层**上，
     * 窗口缓冲在那个位置是**透明的洞**，`PixelCopy` 拷窗口根本拷不到它。
     *
     * 于是「应用画面是黑的」和「我们看不见应用画面」在数据上完全一样 ——
     * 拿它当判据，实测把**正常的高德判成了黑屏**并重启了它。
     *
     * 所以权威判据改成 **task 的 displayId**（跟 AOSP 官方
     * `VirtualDisplay Launcher` 示例判活的方式一致）：
     * 承载应用的 Activity 不在我们的 VirtualDisplay 上 → 黑屏。
     *
     * 像素判黑**降级成辅助**：只有先证明采样能看到应用画面（`sawContent`）才认。
     *
     * ## 分级动作
     *
     * 判据与动作在纯逻辑 [PipBlackHeal] 里，这里只负责执行。
     */
    private fun checkBlackScreen() {
        val probe = frameProbe ?: return
        probe.request()
        val since = if (lastBlackHealAt == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - lastBlackHealAt
        // —— 第130轮：权威判据 ——
        //
        // 承载应用的 Activity 还挂在我们的 VirtualDisplay 上吗？
        // `null` = 判据不可靠（拿不到 displayId / 看不到别的进程 / 还没有 task）
        // → 只能当成「不是黑屏」，绝不能据此重启承载应用。
        //
        // 注意方向：返回 true = **在**我们的 display 上（= 正常），
        // 所以「不在上面」是 `== false`（null 不算）。
        val onOurDisplay = isTaskOnOurDisplay(packageName)
        val taskOff = onOurDisplay == false
        val decision = PipBlackHeal.decide(
            healStage = blackHealStage,
            sawContent = probe.sawContent,
            taskOffDisplay = taskOff,
            consecutiveBlack = probe.consecutiveBlack,
            sinceLastHealMs = since,
        )
        when (decision.action) {
            PipBlackHeal.Action.NONE -> return
            PipBlackHeal.Action.RELAUNCH -> {
                lastBlackHealAt = System.currentTimeMillis()
                blackHealStage = PipBlackHeal.STAGE_RELAUNCH
                Logx.w(
                    LogTags.NAV_UI,
                    "画中画黑屏自愈：重新拉起承载应用",
                    "pkg" to packageName,
                    "role" to role,
                    "reason" to decision.reason,
                    "streak" to probe.consecutiveBlack,
                    "samples" to probe.samples,
                )
                // 一次黑屏只自愈一次：清掉计数，也忘掉「见过非黑画面」
                //（免得反复重启承载应用 —— 那比一直黑更烦人）
                probe.markHealed()
                relaunch("black-heal")
            }
            PipBlackHeal.Action.REBUILD -> {
                lastBlackHealAt = System.currentTimeMillis()
                blackHealStage = PipBlackHeal.STAGE_REBUILD
                Logx.w(
                    LogTags.NAV_UI,
                    "画中画黑屏自愈：重建承载面",
                    "pkg" to packageName,
                    "role" to role,
                    "reason" to decision.reason,
                )
                probe.markHealed()
                rebuildContent()
            }
        }
    }

    /**
     * 承载应用的 Activity 还挂在**我们的 VirtualDisplay** 上吗？
     *
     * ## 为什么这是黑屏的权威判据
     *
     * `ActivityView` 的画面 = 它那个 VirtualDisplay 上的内容。
     * 应用的 task 挂到别的 display 上（用户全屏拉走 / 换肤重建后旧 display
     * 没释放干净）时，我们的 VirtualDisplay 上就**什么都没有** → 一块黑。
     *
     * 判法跟 AOSP 官方 `VirtualDisplay Launcher` 示例一样：
     * `task.displayId == virtualDisplay.getDisplayId()`。
     *
     * ## 返回 null 的三种情况（都按「不是黑屏」处理）
     *
     * - 拿不到我们这个 VirtualDisplay 的 displayId（反射失败）；
     * - 看不到别的进程/任务（debug 版非特权身份）；
     * - **还没找到**承载应用的 task（应用刚起来 / 已被系统回收）——
     *   这时由「进程看门狗」那条路负责（进程没了 → 重新拉起）。
     *
     * @return true=在 / false=不在（黑屏）/ null=不知道
     */
    @Suppress("DEPRECATION")
    private fun isTaskOnOurDisplay(pkg: String): Boolean? {
        if (pkg.isBlank()) return null
        val v = content ?: return null
        // —— 第224轮：判据**下沉**到 ActivityEmbed，与 ReadyStarter 共用一份 ——
        //
        // 第141轮这里自己查过 `getRunningTasks`（含 baseIntent 那个 NoSuchFieldError 的坑）。
        // 第224轮 ReadyStarter 也要判同一件事（surface 重建时该不该重新拉起承载应用），
        // 两份实现必然分叉 → 统一到 [ActivityEmbed.hostTaskInfo]，这里只做**四态→三态**映射：
        //
        // - ON_OUR_DISPLAY  → true
        // - OFF_OUR_DISPLAY → false（真黑屏，可以自愈）
        // - NOT_FOUND / UNKNOWN → null（「不知道」，不据此判黑）
        //
        // 为什么 NOT_FOUND 在这里也算「不知道」：黑屏自愈的动作是**重启承载应用**，
        // 而「找不到 task」更可能是权限/时序问题 —— 交给进程看门狗
        // （连续 [WATCHDOG_MISS_LIMIT] 次看不到进程才动手）更稳，
        // 不要凭一次 task 快照就把用户正在用的应用重启。
        val info = ActivityEmbed.hostTaskInfo(v, pkg)
        logOurDisplayId(info.ourDisplayId)
        val ourId = info.ourDisplayId ?: return null
        return when (info.state) {
            HostTaskDisplay.State.ON_OUR_DISPLAY -> {
                logTaskDisplay(ourId, info.taskDisplayIds, true)
                true
            }
            HostTaskDisplay.State.OFF_OUR_DISPLAY -> {
                logTaskDisplay(ourId, info.taskDisplayIds, false)
                false
            }
            HostTaskDisplay.State.NOT_FOUND, HostTaskDisplay.State.UNKNOWN -> null
        }
    }

    /**
     * 「我们这个 VirtualDisplay 的 id」诊断日志（**限频**：变化时才记）。
     *
     * ## 为什么单独记一行
     *
     * 判据「承载任务的 displayId == 我们的 displayId」要成立，
     * 先得**拿到我们自己的 displayId**。
     *
     * 第140轮查出：API 28（车机同版本）上以前**恒取不到**（AOSP 9 的
     * `ActivityView` 持有的是 `android.hardware.display.VirtualDisplay`，
     * 不是 `android.view.Display`）→ 判据整条失效，而**日志上一行都没有**
     * （取不到就直接 return null，什么都不记）—— 这种「静默失效」最难查。
     *
     * 所以这里把「解析结果」单独记一行：**变化时才记**（看门狗 3s 一轮，
     * 不能每轮都记），取不到时记 WARN（那是真故障）。
     */
    private fun logOurDisplayId(id: Int?) {
        if (id == lastOurDisplayId) return
        lastOurDisplayId = id
        if (id == null) {
            Logx.w(
                LogTags.NAV_UI,
                "取不到画中画虚拟显示器 id（黑屏判据不可用）",
                "pkg" to packageName,
                "role" to role,
            )
        } else {
            Logx.d(
                LogTags.NAV_UI,
                "画中画虚拟显示器 id",
                "our" to id,
                "pkg" to packageName,
                "role" to role,
            )
        }
    }

    /**
     * 「承载任务在哪个 display」的诊断日志（**限频**）。
     *
     * 看门狗 3s 一轮 —— 每轮都记，一天 2.8 万条，车机磁盘扛不住。
     * 所以：**状态变化时总记**，其余每 [TASK_DISPLAY_LOG_EVERY] 轮记一条。
     *
     * 「不在我们的 display 上」是**真故障** → WARN（真车诊断包要能看到）；
     * 「在」是常态 → DEBUG。
     */
    private fun logTaskDisplay(ourId: Int, taskIds: List<Int?>, onOurs: Boolean) {
        val changed = onOurs != lastTaskOnOurDisplay
        lastTaskOnOurDisplay = onOurs
        taskDisplayLogTick++
        if (!changed && taskDisplayLogTick % TASK_DISPLAY_LOG_EVERY != 0) return
        val fields = arrayOf<Pair<String, Any?>>(
            "our" to ourId,
            "tasks" to taskIds.toString(),
            "onOurs" to onOurs,
        )
        if (onOurs) {
            Logx.d(LogTags.NAV_UI, "画中画承载任务所在 display", *fields)
        } else {
            Logx.w(
                LogTags.NAV_UI,
                "画中画承载任务不在我们的 VirtualDisplay 上",
                *fields,
            )
        }
    }

    /**
     * **重建承载面**（第130轮：黑屏自愈的第二级）。
     *
     * ## 为什么「重发启动」不够
     *
     * 承载应用的 task 可能还挂在**旧 VirtualDisplay** 上
     * （旧承载面拆过 / 换肤重建过 / 全屏来回走过）。
     * 这时再发一次启动，系统会把**旧 task 拉到前台** ——
     * 而旧 task 在旧 display 上 → 新承载面里**还是一块黑**。
     *
     * 所以第二级要把**旧承载面连同旧 VirtualDisplay 一起拆掉**，
     * 再干净地重建，让承载应用在新 display 上重新起来
     * （第十二轮换肤重建用的就是这一招）。
     */
    private fun rebuildContent() {
        val pkg = packageName
        if (pkg.isBlank()) return
        val label = labelOf(pkg)
        Logx.w(
            LogTags.NAV_UI,
            "画中画承载面重建",
            "pkg" to pkg,
            "role" to role,
        )
        // 拆旧（连同旧 VirtualDisplay 一起释放）
        clearContent()
        ready = false
        embedContent(pkg, label)
        // embedContent 会把阶段复位成「没自愈过」——
        // 这里要**留在 REBUILD**：重建完还黑就放弃，不能再重建一次。
        blackHealStage = PipBlackHeal.STAGE_REBUILD
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
        // —— 第130轮：**多进程 App** 的判据 ——
        //
        // 高德这类 App 有多个进程（地图渲染跑在子进程里）。
        // 以前只认**主进程名**：主进程刚好不在时就判「进程没了」→
        // 看门狗误伤 → 反复重发启动去打断**正在正常工作**的承载应用
        // （实测日志里就有「承载进程不在」而高德明明还在发导航状态）。
        //
        // 现在改成：该包名的**任意**进程在跑就算活着。
        return list.any {
            it.uid == ai.uid &&
                (it.processName == proc || it.processName.startsWith("$proc:"))
        }
    }

    /**
     * 重新把承载应用拉起来。
     *
     * @param reason 谁触发的（看门狗 / 尺寸变化 / 黑屏自愈）——
     *   **必须区分**：以前这段日志写死成「画中画看门狗：…」，
     *   而黑屏自愈和尺寸变化也走这里 → 排障时根本分不清是谁拉的
     *   （第129轮排查时就被它误导过）。
     */
    private fun relaunch(reason: String) {
        val v = content ?: return
        val intent = launchIntent ?: return
        // 第227轮：**按渲染路线分派**。
        //
        // 镜像路线（第226轮）的承载面是 `TextureView` —— 它**没有** `startActivity` 方法，
        // 走 `ActivityEmbed.startActivity` 只会打一条「ActivityView 启动承载应用失败」
        // 然后**什么都没做** ⇒ 画中画全屏↔还原之后画面不重排（被放大裁切 / 地图图层黑掉）。
        //
        // 镜像路线要重新拉起，得由宿主自己发（它知道自己的虚拟屏 id）。
        mirrorHost?.let { host ->
            Logx.w(
                LogTags.NAV_UI,
                "画中画重新拉起承载应用",
                "pkg" to packageName, "role" to role, "by" to reason,
                "route" to "mirror",
            )
            host.relaunch()
            return
        }
        Logx.w(
            LogTags.NAV_UI,
            "画中画重新拉起承载应用",
            "pkg" to packageName, "role" to role, "by" to reason,
            "route" to "layer",
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
        relaunch("resize")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变了 → surface 会重建 → 重新同步 VirtualDisplay 尺寸。
        //
        // 第229轮：**拖动调比例的这段时间里不同步**。
        // 原因：拖分隔条时卡宽每一帧都在变 ⇒ 每帧 resize 一次虚拟屏 ⇒
        // 承载应用每帧重排版重画一张地图，UI 线程被它拖住，拖动就卡成 10fps。
        // 拖动期间只让卡片跟着变宽（内容由 `TextureView` 自己缩放），
        // 松手时 [endDragPreview] 再做**一次**真实 resize。
        if (dragPreview) return
        if (content != null) {
            syncVirtualDisplay()
        }
    }

    /**
     * 正在拖分隔条调比例（第229轮）。
     *
     * 拖动期间**不** resize 虚拟屏 —— 否则承载应用每帧重排版，UI 线程被拖住，
     * 实测拖动只有 ~10fps；改成松手时一次 resize 后，中间帧只走 View 缩放。
     */
    private var dragPreview = false

    /** 拖动开始：进入「预览」态，暂停虚拟屏 resize。 */
    fun beginDragPreview() {
        if (dragPreview) return
        dragPreview = true
        // 第229轮：镜像路径也要一起进预览态 —— 它的
        // `onSurfaceTextureSizeChanged` 会绕过 [onSizeChanged] 直接 resize 虚拟屏。
        mirrorHost?.beginDragPreview()
        Logx.d(
            LogTags.NAV_UI,
            "画中画拖动预览开始",
            "pkg" to packageName,
            "role" to role,
            "size" to "${width}x$height",
        )
    }

    /** 拖动结束：恢复 resize（一次性同步成最终尺寸，承载应用重排一次）。 */
    fun endDragPreview() {
        if (!dragPreview) return
        dragPreview = false
        mirrorHost?.endDragPreview()
        Logx.d(
            LogTags.NAV_UI,
            "画中画拖动预览结束",
            "pkg" to packageName,
            "role" to role,
            "size" to "${width}x$height",
        )
        if (content != null) {
            syncVirtualDisplay()
        }
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
        // 内容区 = 承载面去掉上下留白，**再减掉窗头**
        val h = height - contentInsetTop - contentInsetBottom
        if (w <= 0 || h <= 0) return
        // 第226轮：镜像路线的虚拟屏由宿主自己同步尺寸
        mirrorHost?.let { host ->
            host.resize(w, h)
            return
        }
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
     * 窗头（设计稿 `09-pip` 的窗口顶栏）。
     *
     * 版式（设计坐标 px，从左到右）：
     * 左内边距 [HEADER_PAD_START]=16 → 应用图标 [HEADER_ICON]=20 → 间距 [HEADER_GAP]=8
     * → 应用名 [HEADER_NAME_TEXT]=14（次色 [Skin.TEXT_SOFT]，超宽省略号）
     * → 间距 [HEADER_GAP]=8 → 标签胶囊（文字 [HEADER_BADGE_TEXT]=12、高 [HEADER_BADGE_H]=22、
     * 圆角 [Shape.PILL]、底 `pal.withAlpha(Skin.TEXT, 0x14)`）
     * → 右侧 ⋮（三枚 [HEADER_DOT]=3px 圆点、间距 [HEADER_DOT_GAP]=5、[Skin.TEXT_DIM]），
     * 距右 [HEADER_PAD_END]=16。
     *
     * 图标优先用应用自身图标；拿不到（包名空 / 未安装）就画一枚 [Skin.TEXT_DIM] 的占位圆点，
     * **绝不留空白**。
     *
     * 自绘而不是拼 TextView：验收要按设计稿量到 px，自绘能精确控制基线/胶囊/点距，
     * 也保证颜色只走 `Skin.*` 与 [Palette.withAlpha]，不新增颜色字面量。
     */
    private inner class PipHeaderView(context: Context) : View(context) {

        /** 应用名（空窗时是「请选择应用」/「未安装: …」）。 */
        var label: String = TEXT_PICK_APP
            set(value) {
                field = value
                invalidate()
            }

        /** 右上「画中画一」/「画中画二」胶囊文字（由 [role] 决定）。 */
        var badge: String = BADGE_MAIN
            set(value) {
                field = value
                invalidate()
            }

        /** 应用图标（null = 用占位圆点）。 */
        private var icon: Drawable? = null

        private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** 绑定一个应用：名称 + 图标（拿不到图标就退化成占位圆点）。 */
        fun bind(appLabel: String, pkg: String?) {
            label = appLabel
            icon = pkg?.takeIf { it.isNotBlank() }?.let { p ->
                runCatching { context.packageManager.getApplicationIcon(p) }.getOrNull()
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cy = h / 2f

            // 第244轮（用户反馈）：窗头**只留中间三枚圆点**当拖动手柄。
            //
            // 原来这里是「应用图标 + 应用名 + 画中画一/二胶囊」在左、一个 ⋮ 在右；
            // 导航页里那一排字整体压在地图上很吵，而窗口身份看左右分区就知道。
            // 现在只剩一枚居中的小胶囊 + 三枚圆点：不挡画面，又能看出「这里可以拖」。
            platePaint.color = pal.withAlpha(pal.surfaceStrong, 0xE8)
            val pillW = minOf(HEADER_HANDLE_W, w - 16f)
            val pillH = minOf(HEADER_HANDLE_H, h - 8f)
            val pillR = minOf(Shape.PILL, pillH / 2f)
            canvas.drawRoundRect(
                (w - pillW) / 2f,
                cy - pillH / 2f,
                (w + pillW) / 2f,
                cy + pillH / 2f,
                pillR,
                pillR,
                platePaint,
            )
            dotPaint.color = Skin.TEXT_DIM
            val dotCx = w / 2f
            val dotR = HEADER_DOT / 2f
            for (i in -1..1) {
                canvas.drawCircle(dotCx + i * HEADER_HANDLE_GAP, cy, dotR, dotPaint)
            }
        }
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

        /** 主画角色（[PipSurface.role] 的默认值）。 */
        const val ROLE_MAIN = "主画"

        /** 副画角色（`NavPage` 构造副画时设置）。 */
        const val ROLE_SECONDARY = "副画"

        /** 窗头胶囊：主画 = 「画中画一」。 */
        const val BADGE_MAIN = "画中画一"

        /** 窗头胶囊：副画 = 「画中画二」。 */
        const val BADGE_SECONDARY = "画中画二"

        /** 窗头高度（设计坐标 px）：窗头占这么高，画面从它下面开始。 */
        const val HEADER_H = 40

        /** 窗头左内边距。 */
        private const val HEADER_PAD_START = 16f

        /** 窗头右内边距（⋮ 到右边）。 */
        private const val HEADER_PAD_END = 16f

        /** 应用图标边长。 */
        private const val HEADER_ICON = 20f

        /** 窗头各元素间距。 */
        private const val HEADER_GAP = 8f

        /** 应用名字号。 */
        private const val HEADER_NAME_TEXT = 14f

        /** 胶囊文字字号。 */
        private const val HEADER_BADGE_TEXT = 12f

        /** 胶囊高度。 */
        private const val HEADER_BADGE_H = 22f

        /** 胶囊左右内边距。 */
        private const val HEADER_BADGE_PAD = 8f

        /** ⋮ 圆点直径。 */
        private const val HEADER_DOT = 3f

        /** ⋮ 圆点间距（圆心到圆心）。 */
        private const val HEADER_DOT_GAP = 5f

        /** 拖动胶囊（窗头中央那枚小胶囊）的宽 / 高。 */
        private const val HEADER_HANDLE_W = 72f
        private const val HEADER_HANDLE_H = 22f

        /** 拖动胶囊里三枚圆点的圆心间距。 */
        private const val HEADER_HANDLE_GAP = 9f

        /** 角色 → 胶囊文字：主画「画中画一」、副画「画中画二」。 */
        private fun badgeFor(role: String): String =
            if (role == ROLE_SECONDARY) BADGE_SECONDARY else BADGE_MAIN

        /** 第225轮：离开导航页时把承载层挪到屏外（见 [setContentVisible]）。 */
        private const val OFFSCREEN_SHIFT = 8000f
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

        /** 连续几帧全黑才判定「黑屏」（见 [PipBlackHeal]）。 */
        const val BLACK_STREAK_LIMIT = PipBlackHeal.BLACK_STREAK_LIMIT

        /**
         * 查「承载应用的 task 在哪个 display 上」时，一次取多少个任务。
         *
         * `ActivityManager.getRunningTasks(maxNum)` 按最近使用排序返回，
         * 取少了可能漏掉我们的 task（漏掉 = 判据不可靠 = 不自愈，安全方向）。
         */
        const val MAX_RUNNING_TASKS = 50

        /** 「承载任务在哪个 display」诊断日志的限频：每 N 轮记一条。 */
        const val TASK_DISPLAY_LOG_EVERY = 30

        /**
         * 尺寸变化后重新拉起承载应用的延迟（毫秒）。
         *
         * 等的是 240ms
         * 这里取 300ms 留够布局时间。
         */
        const val RELAUNCH_AFTER_RESIZE_MS = 300L
    }
}
