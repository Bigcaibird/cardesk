package com.cardesk.launcher.ui.pip

import android.graphics.Outline
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 画中画圆角裁剪（`clipOne` / `clipEmbedded`）。
 *
 * ##在做什么
 *
 *
 * `
 * applyRoundClip(view, radius) {
 *     clipOne(view, radius); // 目标自身
 *     clipEmbedded(view, radius); // 递归子 ViewGroup 里的 ActivityView / SurfaceView
 * }
 * `
 *
 * `clipOne`三件事：
 * 1. 装一个 `ViewOutlineProvider`，按 `radius` 画圆角矩形 → `setClipToOutline(true)`；
 * 2. 目标是 `ActivityView` / `SurfaceView` 时把背景刷成透明（否则白底会露在圆角外）；
 * 3. 目标是 `SurfaceView` 时反射调 `setCornerRadius(float)`（隐藏 API）。
 *
 * ## 为什么第 3 步是必须的
 *
 * `ActivityView` 内部是 **SurfaceView** —— 它的画面走的是独立的 SurfaceFlinger 图层，
 * **不是**画在窗口画布上的。所以：
 *
 * - 父级的 `clipToOutline` 只能把「窗口上被擦出来的洞」裁成圆角，
 *   图层本身还是方的（洞的形状由窗口决定，所以其实够用）；
 * - `SurfaceView.setCornerRadius(float)`（Android 10 引入的隐藏 API）会把 SurfaceView
 *   视口擦成圆角（用 画圆角矩形）。
 *
 * 两条路都做：平台支持就用平台 API（最干净），不支持就靠父级裁剪兜底。
 *
 * ## 半径取值
 *
 *
 *  是 Float 就用它，否则 `DesignCanvas.m1183df(28.0f)`。
 *  不是 dp→px 换算，只是 1280 画布的缩放系数
 * 在 1920 设计画布上**原样返回 28**。
 * 所以半径就是 **28 设计 px**，与我们其它卡片（`Skin.glass(28f)`）一致。
 */
object RoundClip {

    /** 默认圆角半径（设计坐标 px， `m1183df(28.0f)`）。 */
    const val DEFAULT_RADIUS = 28f

    /**
     * 给目标及其子 View 装上圆角裁剪（`applyRoundClip`）。
     *
     * @param root 目标 View（承载面 / ActivityView / 卡片根布局）
     * @param radius 半径（设计坐标 px）
     */
    fun apply(root: View?, radius: Float) {
        if (root == null || radius <= 0f) return
        clipOne(root, radius)
        clipEmbedded(root, radius)
    }

    /**
     * 单个 View 的圆角裁剪（`clipOne`）。
     *
     * 目标自身画布按圆角裁剪；对 `SurfaceView` 额外反射 `setCornerRadius`。
     */
    private fun clipOne(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val w = v.width
                val h = v.height
                if (w <= 0 || h <= 0) {
                    outline.setEmpty()
                    return
                }
                outline.setRoundRect(0, 0, w, h, radius)
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
        // ActivityView / SurfaceView 的默认背景是白的
        // 不刷透明的话圆角外会露出一圈白边。
        if (isSurfaceLike(view)) {
            runCatching { view.setBackgroundColor(0) }
        }
        if (view is SurfaceView) {
            applySurfaceCornerRadius(view, radius)
        }
    }

    /**
     * 递归子 View 里的 SurfaceView。
     *
     * `ActivityView` 内部就是 SurfaceView，不递归就裁不到真正显示画面的那一层。
     */
    private fun clipEmbedded(view: View, radius: Float) {
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is SurfaceView) {
                clipOne(child, radius)
            }
            clipEmbedded(child, radius)
        }
    }

    /** 是不是「画面层」（`ActivityView` 是隐藏类，只能按类名判断）。 */
    private fun isSurfaceLike(view: View): Boolean =
        view is SurfaceView || view.javaClass.name == "android.app.ActivityView"

    /**
     * 反射调用隐藏 API `SurfaceView.setCornerRadius(float)`（Android 10 / API 29 起有）。
     *
     * - 平台支持 → 画面图层本身是圆角（最干净的做法）；
     * - 平台不支持 / 调用被拦 → 只能靠父级 `clipToOutline` 兜底（视觉上一样是圆角）。
     *
     * @return true = 调用成功
     */
    fun applySurfaceCornerRadius(surface: SurfaceView, radius: Float): Boolean {
        val ok = runCatching {
            surface.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .invoke(surface, radius)
            true
        }.getOrDefault(false)
        if (!ok) {
            // 平台不支持是常态（Android 9 及以下没有这个方法），用 DEBUG 记一笔即可。
            Logx.d(
                LogTags.NAV_UI,
                "SurfaceView 圆角 API 不可用（改用父级裁剪）",
                "radius" to radius,
            )
        }
        return ok
    }

    /**
     * 把承载面的 SurfaceView 压到窗口之下。
     *
     * 画中画上方那三个点是桌面自己画的 —— 画面图层必须在窗口之下，
     * 三个点才不会被盖住。
     */
    fun applySurfaceZOrder(root: View?) {
        if (root == null) return
        forEachSurface(root) { sv ->
            runCatching {
                sv.setZOrderOnTop(false)
                sv.setZOrderMediaOverlay(false)
            }
        }
    }

    /** 递归找出所有 SurfaceView。 */
    private fun forEachSurface(view: View, action: (SurfaceView) -> Unit) {
        if (view is SurfaceView) action(view)
        if (view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            forEachSurface(view.getChildAt(i), action)
        }
    }
}
