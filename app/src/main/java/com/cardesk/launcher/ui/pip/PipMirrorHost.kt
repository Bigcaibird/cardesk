package com.cardesk.launcher.ui.pip

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.VirtualDisplayIdResolver

/**
 * 画中画的「窗口内渲染」宿主（第226轮）。
 *
 * ## 它解决什么问题
 *
 * 视频壁纸走 `SurfaceView`（硬件图层，最省电）时，桌面窗口必须是 `TRANSLUCENT`。
 * 窗口一透明，`ActivityView` 内部那个 `SurfaceView` 就不再被窗口轮廓裁剪：
 *
 * - 圆角失效（直角）—— API 28 没有 `SurfaceView.setCornerRadius`，图层侧补不了；
 * - 承载画面与窗口的坐标/裁剪脱节 → 用户看到的「高德错乱/右侧裁切」；
 * - 切到首页后承载层还在合成 → 那个位置留一块黑卡。
 *
 * 这个宿主换一条路：**自己建 `VirtualDisplay`，它的输出 Surface 接到 `TextureView`**。
 * `TextureView` 的纹理是画进桌面窗口自己的缓冲的，所以：
 *
 * - 它是普通 View → `clipToOutline` 圆角成立、跟着页面一起隐藏（无残留）；
 * - 只有画中画那块（约 1287×867）需要 GPU 合成，视频仍然是硬件图层直通。
 *
 * ## 第228轮：把这条路补完整（真车逐条实测）
 *
 * 第226轮只做了「画面」这一半，**触摸**和**虚拟屏显示信息**两件事没做，
 * 于是用户在车机上看到三个现象：
 *
 * 1. **画中画触控不了** ⇒ 本类新增 [forwardTouch]：
 *    用 AOSP 9 `ActivityView` 用的同一套机制 ——
 *    `InputManager.getInstance().createInputForwarder(displayId)` → `forwardEvent(event)`。
 *    （**不是** `MotionEvent.setDisplayId` —— API 28 根本没有这个方法。）
 * 2. **切全屏后右侧缺 108px**（高德只画 1812 / 1920）⇒ 虚拟屏建好后必须调
 *    `wm.dontOverrideDisplayInfo(displayId)`（AOSP `ActivityView.initVirtualDisplay` 里就有这一句）。
 *    少了它，WM 会用自己的规则覆盖虚拟屏的显示信息 ⇒ 承载应用拿到的配置
 *    和虚拟屏实际尺寸对不上 ⇒ 它按另一套尺寸排版 ⇒ 右边空一块。
 * 3. **大小调整玻璃条看不见** ⇒ 那个是 `NavPage.placePipDivider()` 的坐标错位
 *    （row 局部坐标当成 NavPage 坐标用，差 44px），与本类无关，已在 NavPage 修。
 *
 * ## 生命周期
 *
 * 1. `TextureView` 挂到窗口、拿到 `SurfaceTexture` → 建 `VirtualDisplay`；
 * 2. 建好之后拿 displayId，用 `ActivityOptions.setLaunchDisplayId` 把承载应用**起在这块屏上**；
 * 3. 尺寸变化 → `VirtualDisplay.resize`（不重建，承载应用不重启）；
 * 4. `release()` → 释放 `VirtualDisplay`（**会**把承载应用的 task 推回主屏，所以只在真的不再承载时调）。
 */
class PipMirrorHost(private val context: Context) {

    /** 画中画的承载视图（普通 View，圆角/裁剪/缩放都按 View 规则走）。 */
    val view: TextureView = TextureView(context).apply {
        isOpaque = false
        // 视频壁纸那层在窗口之下，画中画要压在它上面 —— TextureView 天然就在窗口里，无需 z 序调整。
        //
        // 第228轮：触摸要转发给承载应用 ⇒ 这个 View 必须**先拿到**事件
        // （`setOnTouchListener` 的前提是 `isClickable`）。
        isClickable = true
    }

    /** `VirtualDisplay` 的 displayId；null = 还没建好。 */
    var displayId: Int? = null
        private set

    private var virtualDisplay: Any? = null
    private var textureSurface: Surface? = null
    private var intent: Intent? = null
    private var pkg: String = ""
    private var launched = false
    private var released = false

    /**
     * 第228轮：输入转发器（AOSP `ActivityView` 用的同一个）。
     *
     * `InputManager.getInstance().createInputForwarder(displayId)` 返回的
     * `IInputForwarder` 能把触摸事件**投到指定显示屏**上 ——
     * 这是 API 28 上把触摸送进虚拟屏的正规做法。
     */
    private var inputForwarder: Any? = null

    /** 上一次 `VirtualDisplay` 的尺寸（用来去重重复的 resize）。 */
    private var lastVirtualW = 0
    private var lastVirtualH = 0

    /** 上一次更新「触摸排除区」的时刻（节流用）。 */
    private var lastTapExcludeAt = 0L

    /** 虚拟屏就绪回调（拿 displayId），在 [start] 里传。 */
    private var onReady: ((Int) -> Unit)? = null

    init {
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                ensureDisplay(st, w, h)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                // 尺寸变化不重建虚拟屏（重建 = 承载应用被搬到主屏 = 用户报的「高德跑回首页」）
                resize(w, h)
                updateTapExcludeRegion()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // 第227轮：**摘掉**虚拟屏的输出 surface（别让它往已销毁的纹理上画）。
                //
                // 下一��� `onSurfaceTextureAvailable` 会把虚拟屏**重新指到新的纹理上**
                // （见 [ensureDisplay]）—— 这条路是「画中画 ↔ 全屏」切换的关键：
                // 进/出全屏时承载面会被 `removeView` + `addView` 搬一次家，
                // `TextureView` 的 `SurfaceTexture` 因此被销毁重建。
                //
                // 第226轮那版在这里 `setSurface(null)` 之后**再也不接回去**
                // （`ensureDisplay` 一看虚拟屏已存在就 return）⇒
                // 切一次全屏，承载画面就变成**全黑**。
                runCatching { virtualDisplay?.javaClass?.getMethod("setSurface", Surface::class.java)?.invoke(virtualDisplay, null) }
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        // 第228轮：触摸转发（见类注释的「1」）。
        view.setOnTouchListener { _, event -> forwardTouch(event) }
    }

    /**
     * 开始承载某个应用。
     *
     * @param pkg 承载应用包名（只用于日志/迁移）
     * @param launchIntent 启动 Intent（调用方已加好 flag）
     * @param onReady 虚拟屏建好、应用已经起在上面的回调，参数是 displayId
     */
    fun start(pkg: String, launchIntent: Intent?, onReady: (Int) -> Unit) {
        this.pkg = pkg
        this.intent = launchIntent
        this.onReady = onReady
        val st = view.surfaceTexture
        if (st != null && view.width > 0 && view.height > 0) {
            ensureDisplay(st, view.width, view.height)
        }
        // 否则等 onSurfaceTextureAvailable
    }

    /**
     * 拖动调比例期间的「预览」态（第229轮）。
     *
     * 拖分隔条时承载面每帧都在变尺寸，`TextureView` 的
     * `onSurfaceTextureSizeChanged` 也会每帧回调 —— 每帧 `VirtualDisplay.resize()`
     * 会让承载应用每帧重排版重画（高德重画整张地图），主线程被它拖住，
     * 实测拖动只有 ~10fps、连 move 事件都被合并掉。
     * 所以拖动期间**只记下最新尺寸**，松手时一次性 resize。
     */
    private var dragPreview = false
    private var pendingW = 0
    private var pendingH = 0

    /** 拖动开始：暂停虚拟屏 resize。 */
    fun beginDragPreview() {
        dragPreview = true
        pendingW = 0
        pendingH = 0
    }

    /** 拖动结束：按拖动过程中记下的最终尺寸 resize 一次。 */
    fun endDragPreview() {
        if (!dragPreview) return
        dragPreview = false
        val w = pendingW
        val h = pendingH
        pendingW = 0
        pendingH = 0
        if (w > 0 && h > 0) resize(w, h)
    }

    /** 尺寸变化时把虚拟屏同步成内容区尺寸。 */
    fun resize(w: Int, h: Int) {
        if (released || w <= 0 || h <= 0) return
        if (dragPreview) {
            // 拖动中只记尺寸，松手那次再 resize（见 [beginDragPreview]）。
            pendingW = w
            pendingH = h
            return
        }
        // 第228轮：**尺寸没变就别再 resize**。
        //
        // 拖分隔条时每帧都会走到这里（承载面尺寸变了 → `PipSurface.syncVirtualDisplay`
        // 与 `onSurfaceTextureSizeChanged` 各调一次）⇒ 同一帧里 resize 两次。
        // `VirtualDisplay.resize()` 会让承载应用**重新排版**（高德重画整张地图），
        // 车机上这一步很贵 ⇒ 重复调用直接吃掉一帧。
        if (w == lastVirtualW && h == lastVirtualH) return
        lastVirtualW = w
        lastVirtualH = h
        val m = cachedMethod(
            virtualDisplay?.javaClass ?: return,
            "resize",
            3,
        ) { p -> p == Int::class.javaPrimitiveType }
        runCatching {
            m?.isAccessible = true
            m?.invoke(virtualDisplay, w, h, densityDpi())
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染：resize 失败", it, "size" to "${w}x$h")
        }
    }

    /** 切页动画落位后刷新触摸排除区到当前屏幕坐标。 */
    fun refreshTapExcludeRegion() {
        if (released || displayId == null) return
        updateTapExcludeRegion(force = true)
    }

    /** 释放虚拟屏与 Surface（只在真的不再承载时调用）。 */
    fun release() {
        if (released) return
        released = true
        inputForwarder = null
        runCatching { virtualDisplay?.javaClass?.getMethod("release")?.invoke(virtualDisplay) }
        runCatching { textureSurface?.release() }
        virtualDisplay = null
        textureSurface = null
        displayId = null
        Logx.i(LogTags.NAV_UI, "画中画窗口内渲染已释放", "pkg" to pkg)
    }

    // —— 内部 ——

    private fun ensureDisplay(st: SurfaceTexture, w: Int, h: Int) {
        if (released || w <= 0 || h <= 0) return
        val surface = Surface(st)
        // 旧的 Surface 对象要放掉（否则 native 资源漏着）。
        textureSurface?.let { old -> if (old !== surface) runCatching { old.release() } }
        textureSurface = surface

        // —— 第227轮：虚拟屏**已经建好**的情况 ——
        //
        // 「画中画 ↔ 全屏」切换时，承载面会被 `removeView` + `addView` 搬一次家，
        // `TextureView` 的 `SurfaceTexture` 因此销毁重建 ⇒ 这里会**再进来一次**。
        //
        // 第226轮那版在这里直接 `return` ⇒ 虚拟屏一直指着**已销毁**的旧 surface
        // ⇒ 切一次全屏，承载画面就是**全黑**（第227轮实测发现）。
        //
        // 修法：把虚拟屏**重新指到新的 surface 上**（`setSurface`），
        // 并同步尺寸；**不重建虚拟屏**（重建 = 承载应用 task 被推回主屏 = 老毛病）。
        val existing = virtualDisplay
        if (existing != null) {
            val ok = runCatching {
                existing.javaClass
                    ?.getMethod("setSurface", Surface::class.java)
                    ?.invoke(existing, surface)
                existing.javaClass
                    ?.getMethod("resize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    ?.invoke(existing, w, h, densityDpi())
            }.onFailure {
                Logx.w(LogTags.NAV_UI, "画中画窗口内渲染：重新接上 surface 出错", it, "pkg" to pkg)
            }.isSuccess
            Logx.i(
                LogTags.NAV_UI,
                "画中画窗口内渲染：已重新接上新的 surface",
                "pkg" to pkg,
                "display" to displayId,
                "size" to "${w}x$h",
                "ok" to ok,
            )
            // displayId 没变，但登记可能被清过（承载面重建过）⇒ 再登记一次（幂等）。
            displayId?.let { onReady?.invoke(it) }
            updateTapExcludeRegion(force = true)
            return
        }

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: run {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染失败：拿不到 DisplayManager")
            return
        }
        val vd = runCatching {
            dm.javaClass.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(dm, "CarDesk 画中画", w, h, densityDpi(), surface, FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY)
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染失败：建虚拟屏出错", it, "pkg" to pkg)
        }.getOrNull() ?: return
        virtualDisplay = vd
        val id = VirtualDisplayIdResolver.displayIdOfVirtualDisplayLike(vd)
        if (id == null) {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染失败：拿不到虚拟屏 id", "pkg" to pkg)
            return
        }
        displayId = id
        Logx.i(
            LogTags.NAV_UI,
            "画中画窗口内渲染就绪",
            "pkg" to pkg,
            "display" to id,
            "size" to "${w}x$h",
        )
        // 第228轮：**告诉 WM 别覆盖这块虚拟屏的显示信息**（AOSP `ActivityView` 同一句）。
        // 少了它，承载应用拿到的显示配置和虚拟屏实际尺寸对不上 ⇒ 切全屏后右侧空一块。
        dontOverrideDisplayInfo(id)
        // 第228轮：建输入转发器，触摸才能送到承载应用（见类注释的「1」）。
        createInputForwarder(id)
        launch(id)
        onReady?.invoke(id)
    }

    /**
     * 第228轮：`wm.dontOverrideDisplayInfo(displayId)`。
     *
     * AOSP 9 `ActivityView.initVirtualDisplay()` 里紧跟着建屏就有这一句：
     * 「别用你的规则改这块虚拟屏的显示信息，按我给的来」。
     *
     * 少了它 ⇒ WM 会覆盖虚拟屏的显示信息（app 区域/insets/密度）⇒
     * 承载应用拿到的配置与虚拟屏实际尺寸对不上 ⇒ 它按另一套尺寸排版
     * ⇒ 真车实测：切全屏后高德只画 1812 / 1920，右侧空 108px。
     */
    private fun dontOverrideDisplayInfo(id: Int) {
        val wm = windowManagerService()
        if (wm == null) {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染：拿不到 WindowManagerService", "display" to id)
            return
        }
        val ok = callReflective(
            wm,
            "dontOverrideDisplayInfo",
            listOf(id),
        ) { p -> p == Int::class.javaPrimitiveType }
        Logx.i(LogTags.NAV_UI, "画中画窗口内渲染：dontOverrideDisplayInfo", "display" to id, "ok" to ok)
    }

    /**
     * 拿 `IWindowManager`（系统窗口服务）。
     *
     * ## 包名这件事（第228轮真车踩到的）
     *
     * `WindowManagerGlobal` 在 **Android 9 里搬了家**：
     *
     * - AOSP 8 及更早：`com.android.internal.view.WindowManagerGlobal`
     * - **AOSP 9（本车机）**：`android.view.WindowManagerGlobal`
     *
     * AOSP 9 的 `ActivityView.java` 顶部就是 `import android.view.WindowManagerGlobal;`。
     * 写成 `com.android.internal.view.…` 的话 `Class.forName` 直接抛
     * `ClassNotFoundException` ⇒ **反射一次都没打中**（真车日志 `ok=false`）。
     *
     * ⇒ 两个包名都试一遍，谁在用谁。
     */
    private fun windowManagerService(): Any? {
        for (cls in WINDOW_MANAGER_GLOBAL_CLASSES) {
            val svc = runCatching {
                Class.forName(cls).getMethod("getWindowManagerService").invoke(null)
            }.getOrNull()
            if (svc != null) {
                Logx.d(LogTags.NAV_UI, "画中画窗口内渲染：WindowManagerService 来源", "cls" to cls)
                return svc
            }
        }
        return null
    }

    /**
     * 拿 `IWindowSession`（窗口会话；`updateTapExcludeRegion` 在它上面）。
     *
     * 包名同 [windowManagerService]（Android 9 搬家）。
     */
    private fun windowSession(): Any? {
        for (cls in WINDOW_MANAGER_GLOBAL_CLASSES) {
            val session = runCatching {
                Class.forName(cls).getMethod("getWindowSession").invoke(null)
            }.getOrNull()
            if (session != null) {
                Logx.d(LogTags.NAV_UI, "画中画窗口内渲染：WindowSession 来源", "cls" to cls)
                return session
            }
        }
        return null
    }

    /**
     * 第228轮：建输入转发器（触摸送进虚拟屏）。
     *
     * AOSP 9 `ActivityView` 的做法：`InputManager.getInstance().createInputForwarder(displayId)`。
     *
     * 返回的是 `android.app.IInputForwarder`（AIDL 接口），它的
     * `forwardEvent(InputEvent)` 是**唯一**能把触摸投递到指定显示屏的正规做法
     * —— API 28 的 `MotionEvent` 还没有 `setDisplayId()`。
     */
    private fun createInputForwarder(id: Int) {
        val forwarder = runCatching {
            val im = Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
            im.javaClass.getMethod("createInputForwarder", Int::class.javaPrimitiveType).invoke(im, id)
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染：建输入转发器失败", it, "display" to id)
        }.getOrNull()
        inputForwarder = forwarder
        Logx.i(LogTags.NAV_UI, "画中画窗口内渲染：输入转发器就绪", "display" to id, "ok" to (forwarder != null))
    }

    /**
     * 第228轮：把画中画上的触摸**转发**给承载应用。
     *
     * 坐标：虚拟屏尺寸 == `TextureView` 尺寸（建屏时按它建、变化时同步 resize），
     * 所以是 1:1；万一不同步（切全屏的那一瞬间），按比例换算，
     * 免得点击落在错误的位置。
     *
     * @return true = 事件已转发（消费掉，别让桌面再处理）
     */
    private fun forwardTouch(event: MotionEvent): Boolean {
        val forwarder = inputForwarder ?: return false
        val id = displayId ?: return false
        // 别让桌面的横滑（切页）把画中画里的手势抢走。
        // （`requestDisallowInterceptTouchEvent` 会**逐级向上**传递，
        //   所以从承载面的直接父容器起手就够。）
        (view.parent as? android.view.ViewGroup)?.requestDisallowInterceptTouchEvent(true)
        val out = rescale(event)
        val raw = invokeReflective(
            forwarder,
            "forwardEvent",
            listOf(out),
        ) { p -> p.isAssignableFrom(MotionEvent::class.java) }
        if (out !== event) out.recycle()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            Logx.i(
                LogTags.NAV_UI,
                "画中画触摸转发",
                "display" to id,
                "x" to event.x,
                "y" to event.y,
                "ok" to (raw != null),
            )
        }
        // 事件已经交给虚拟屏 ⇒ **消费掉**。
        // 不消费的话，事件会继续冒泡到桌面 → 桌面横滑（切页）把画中画里的手势抢走。
        return true
    }

    /**
     * 虚拟屏尺寸与 `TextureView` 不一致时按比例换算坐标。
     *
     * 一致时**原样返回**（保留多指信息）；不一致时重建一个单指事件。
     */
    private fun rescale(event: MotionEvent): MotionEvent {
        val vw = view.width
        val vh = view.height
        if (vw <= 0 || vh <= 0) return event
        val dw = virtualWidth()
        val dh = virtualHeight()
        if (dw <= 0 || dh <= 0) return event
        if (dw == vw && dh == vh) return event
        val sx = dw.toFloat() / vw
        val sy = dh.toFloat() / vh
        val out = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.actionMasked,
            event.x * sx,
            event.y * sy,
            event.metaState,
        )
        out.source = event.source
        return out
    }

    /** 虚拟屏当前尺寸（拿不到就返回 0）。 */
    private fun virtualWidth(): Int = virtualDisplaySize().first
    private fun virtualHeight(): Int = virtualDisplaySize().second

    private fun virtualDisplaySize(): Pair<Int, Int> {
        val vd = virtualDisplay ?: return 0 to 0
        return runCatching {
            val d = vd.javaClass.getMethod("getDisplay").invoke(vd)
            val metrics = d.javaClass.getMethod("getRealMetrics", android.util.DisplayMetrics::class.java)
            val m = android.util.DisplayMetrics()
            metrics.invoke(d, m)
            m.widthPixels to m.heightPixels
        }.getOrDefault(0 to 0)
    }

    /**
     * 第228轮：把画中画这块区域登记成「触摸排除区」。
     *
     * AOSP `ActivityView.updateLocation()` 做的同一件事：
     * 告诉 WM「这块矩形里的触摸别用来切焦点」，否则点画中画会把焦点切到虚拟屏里的应用。
     *
     * AOSP 9 的调用是：
     * ```
     * WindowManagerGlobal.getWindowSession()
     *     .updateTapExcludeRegion(getWindow(), hashCode(), x, y, width, height);
     * ```
     * 注意**第一个参数是 `android.view.Window`（不是 IBinder）**、
     * **第二个是 View 的 hashCode（不是 displayId）**。
     */
    private fun updateTapExcludeRegion(force: Boolean = false) {
        // 第228轮：**节流**。
        //
        // 拖分隔条时每帧都会走到这里 ⇒ 每帧一次「反射 + IPC 到 WMS」。
        // 真车实测单帧要 **9~25ms** ⇒ 直接把拖动打到 10~15fps。
        //
        // 「触摸排除区」只是告诉 WM「这块别用来切焦点」，**不需要每帧都准** ⇒ 节流。
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastTapExcludeAt < TAP_EXCLUDE_THROTTLE_MS) return
        lastTapExcludeAt = now
        // `View.getWindow()` / `Window.getWindowToken()` **不在公开 SDK 里** ⇒ 只能反射取。
        val window = runCatching {
            view.javaClass.getMethod("getWindow").invoke(view)
        }.onFailure {
            Logx.d(LogTags.NAV_UI, "画中画触摸排除区：取不到 View.getWindow", it)
        }.getOrNull()
        val session = windowSession()
        if (window == null || session == null) {
            Logx.d(
                LogTags.NAV_UI,
                "画中画触摸排除区：拿不到窗口/会话",
                "window" to (window != null),
                "session" to (session != null),
            )
            return
        }
        val m = findMethod(session.javaClass, "updateTapExcludeRegion", 6)
        if (m == null) {
            Logx.d(LogTags.NAV_UI, "画中画触摸排除区：会话里没有 updateTapExcludeRegion")
            return
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val p0 = m.parameterTypes[0]
        // OEM 改过框架的话，第一个参数可能是 `Window` 也可能是 `IBinder` —— 两种都试。
        val first: Any? = when {
            p0.isAssignableFrom(android.view.Window::class.java) -> window
            p0.isAssignableFrom(IBinder::class.java) -> runCatching {
                window.javaClass.getMethod("getWindowToken").invoke(window)
            }.getOrNull()
            else -> null
        }
        val ok = runCatching {
            m.isAccessible = true
            m.invoke(
                session,
                first,
                view.hashCode(),
                loc[0],
                loc[1],
                view.width,
                view.height,
            )
            true
        }.onFailure {
            Logx.d(LogTags.NAV_UI, "画中画触摸排除区设置失败", it)
        }.getOrDefault(false)
        Logx.d(
            LogTags.NAV_UI,
            "画中画触摸排除区",
            "ok" to ok,
            "display" to displayId,
            "rect" to "${loc[0]},${loc[1]} ${view.width}x${view.height}",
        )
    }

    /**
     * 反射调用（只关心「有没有打中」）。
     *
     * @return true = 方法找到了**并且**调用没抛异常（返回值可能仍是 null —— void 方法就是这样）
     */
    private fun callReflective(
        target: Any,
        name: String,
        args: List<Any?>,
        accept: (Class<*>) -> Boolean = { true },
    ): Boolean {
        val m = cachedMethod(target.javaClass, name, args.size, accept)
        if (m == null) {
            Logx.w(LogTags.NAV_UI, "反射调用失败：找不到方法 $name", "cls" to target.javaClass.name)
            return false
        }
        return runCatching {
            m.isAccessible = true
            m.invoke(target, *args.toTypedArray())
            true
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "反射调用异常：$name", it, "cls" to target.javaClass.name)
        }.getOrDefault(false)
    }

    /**
     * 反射调用（要返回值）。
     *
     * @return 方法返回值；找不到方法或调用抛异常时返回 null（并落日志）
     */
    private fun invokeReflective(
        target: Any,
        name: String,
        args: List<Any?>,
        accept: (Class<*>) -> Boolean = { true },
    ): Any? {
        val m = cachedMethod(target.javaClass, name, args.size, accept)
        if (m == null) {
            Logx.w(LogTags.NAV_UI, "反射调用失败：找不到方法 $name", "cls" to target.javaClass.name)
            return null
        }
        return runCatching {
            m.isAccessible = true
            m.invoke(target, *args.toTypedArray())
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "反射调用异常：$name", it, "cls" to target.javaClass.name)
        }.getOrNull()
    }

    /**
     * 找方法（**带缓存**）。
     *
     * ## 为什么必须缓存（第228轮：拖动卡顿的元凶之一）
     *
     * `Class.getDeclaredMethods()` / `Class.getMethods()` **每次调用都会新建一个
     * `Method` 数组**（JDK 不缓存这层）⇒ 一个几十上百个方法的类，
     * 每次扫描都要复制上百个 `Method` 对象。
     *
     * 拖动分隔条时每帧都会走到这条路上（尺寸变了 → resize + 触摸排除区），
     * 实测真车上单帧要多花 **9ms** —— 直接把拖动从 30fps 打到 15fps。
     *
     * ⇒ 方法对象**解析一次就存起来**，找不到也存 null（别反复扫）。
     */
    private fun cachedMethod(
        cls: Class<*>,
        name: String,
        arity: Int,
        accept: (Class<*>) -> Boolean,
    ): java.lang.reflect.Method? = synchronized(methodCache) {
        val key = cls.name + "#" + name + "/" + arity
        if (methodCache.containsKey(key)) return@synchronized methodCache[key]
        val m = findMethod(cls, name, arity, accept)
        methodCache[key] = m
        m
    }

    /**
     * 按「方法名 + 参数个数 + 参数类型是否可接受」找方法。
     *
     * ## 为什么不用 `getMethod(name, 精确参数类型)`
     *
     * 1. **参数类型**未必与 AOSP 一致（车机 framework 被 OEM 改写过），
     *    所以按「方法名 + 参数个数」找，再用 [accept] 判断参数类型吃不吃得下实参；
     * 2. **声明类可能不是 public**（AIDL 代理类 / 匿名实现类），
     *    不设 `isAccessible = true` 的话 `invoke` 会抛 `IllegalAccessException`。
     *    本应用是 platform 签名（uid 1000），不受 API 28 的隐藏 API 限制。
     *
     * 先扫类继承链，再扫接口（AIDL 代理的方法声明在接口上时更稳）。
     */
    private fun findMethod(
        cls: Class<*>,
        name: String,
        arity: Int,
        accept: (Class<*>) -> Boolean = { true },
    ): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterCount == arity && accept(m.parameterTypes[0])
            }?.let { return it }
            c = c.superclass
        }
        cls.interfaces.forEach { itf ->
            itf.methods.firstOrNull { m ->
                m.name == name && m.parameterCount == arity && accept(m.parameterTypes[0])
            }?.let { return it }
        }
        return null
    }

    /**
     * 第227轮：**重新**把承载应用起在我们的虚拟屏上（尺寸变了之后用）。
     *
     * ## 为什么需要
     *
     * 承载应用是在我们的虚拟屏上跑的。虚拟屏改了尺寸之后，
     * 应用**不一定**会收到配置变化并重新排版 ——
     * 画面要么按旧尺寸绘制（被放大裁切），要么地图图层直接黑掉。
     *
     * 旧路线（`ActivityView`）靠 `ActivityEmbed.startActivity` 重新发一次启动解决。
     * 但**镜像路线**的承载面是 `TextureView`，它**没有** `startActivity` 方法 ⇒
     * 第226轮那版走 `ActivityEmbed.startActivity` 只会打一条
     * 「ActivityView 启动承载应用失败」⇒ **根本没重新拉起** ⇒
     * 画中画全屏↔还原之后画面不对。
     *
     * 这里直接用自己的 `ActivityOptions.setLaunchDisplayId` 再发一次启动，
     * 应用就回到我们的虚拟屏上，并按新尺寸重排。
     */
    fun relaunch() {
        if (released) return
        val id = displayId ?: return
        val i = intent ?: return
        Logx.i(
            LogTags.NAV_UI,
            "画中画窗口内渲染：重新拉起承载应用",
            "pkg" to pkg,
            "display" to id,
        )
        launch(id, force = true)
    }

    /**
     * 把承载应用起在我们的虚拟屏上（`ActivityOptions.setLaunchDisplayId`）。
     *
     * @param force 第227轮：尺寸变化后的**重新**拉起（绕过「只启一次」的闸门）。
     */
    private fun launch(displayId: Int, force: Boolean = false) {
        if (launched && !force) return
        val i = intent ?: return
        val ok = runCatching {
            val cls = Class.forName("android.app.ActivityOptions")
            val opts = cls.getMethod("makeBasic").invoke(null)
            cls.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType).invoke(opts, displayId)
            val bundle = cls.getMethod("toBundle").invoke(opts) as Bundle
            context.startActivity(i, bundle)
            true
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "画中画窗口内渲染：启动承载应用失败", it, "pkg" to pkg)
        }.getOrDefault(false)
        if (ok) {
            launched = true
            Logx.i(LogTags.NAV_UI, "画中画窗口内渲染：承载应用已启动", "pkg" to pkg, "display" to displayId)
        }
    }

    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    private companion object {
        /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC`。 */
        const val FLAG_PUBLIC = 1

        /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`。 */
        const val FLAG_OWN_CONTENT_ONLY = 8

        /**
         * `WindowManagerGlobal` 的候选包名（**Android 9 搬家了**）。
         *
         * - AOSP 8 及更早：`com.android.internal.view.WindowManagerGlobal`
         * - AOSP 9（领克 AAOS 车机实测）：`android.view.WindowManagerGlobal`
         *
         * 真车证据：`framework.jar` 的 dex 字符串池里只有
         * `Landroid/view/WindowManagerGlobal;`，**没有** `com/android/internal/view/…`
         * ⇒ 写成老包名会直接 `ClassNotFoundException`（第228轮踩过）。
         */
        val WINDOW_MANAGER_GLOBAL_CLASSES = arrayOf(
            "android.view.WindowManagerGlobal",
            "com.android.internal.view.WindowManagerGlobal",
        )

        /**
         * 反射方法的缓存（第228轮）。
         *
         * `Class.getDeclaredMethods()` **每次调用都会新建一个 `Method` 数组**
         * （JDK 不缓存这层）⇒ 拖分隔条时每帧都要扫一遍几十上百个方法，
         * 实测真车上单帧多花 9~25ms。
         *
         * ⇒ 解析一次就存起来（找不到也存 null，别反复扫）。
         */
        private val methodCache = HashMap<String, java.lang.reflect.Method?>()

        /** 「触摸排除区」更新的最小间隔（毫秒）。 */
        private const val TAP_EXCLUDE_THROTTLE_MS = 400L
    }
}
