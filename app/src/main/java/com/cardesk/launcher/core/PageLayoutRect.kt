package com.cardesk.launcher.core

/**
 * 切页动画前「手动量 / 摆一个 GONE 页面」时要用的矩形（第201轮）。
 *
 * ## 为什么单独抽出来
 *
 * 页面平时是 `GONE`，没量过；切页动画要先手动 `measure` + `layout` 一次。
 * 原来的实现直接按 `pageHost` 的整块尺寸（1920×1080）摆，**无视页面自己的
 * LayoutParams** —— 导航页本来是 `topMargin=73`、`height=867`（让出状态栏和
 * Dock），被摆成 1080 高之后，画中画承载面的 SurfaceView 高度会从 1052 跳到
 * 867，VirtualDisplay 的 resize 跟不上，底部留下一条黑块。
 *
 * 抽成纯函数之后：口径可单测，也不用再起一个 View 才能验证。
 */
object PageLayoutRect {

    /** 一个页面的摆放矩形（设计坐标）。 */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * @param lpWidth / [lpHeight] 页面的 LayoutParams 尺寸（`MATCH_PARENT` = -1 / `WRAP_CONTENT` = -2）
     * @return null = 算不出有效矩形（宿主还没量过 / 边距把空间吃光了）
     */
    fun of(
        hostW: Int,
        hostH: Int,
        lpWidth: Int,
        lpHeight: Int,
        leftMargin: Int = 0,
        topMargin: Int = 0,
        rightMargin: Int = 0,
        bottomMargin: Int = 0,
    ): Box? {
        if (hostW <= 0 || hostH <= 0) return null
        val w = if (lpWidth > 0) lpWidth else hostW - leftMargin - rightMargin
        val h = if (lpHeight > 0) lpHeight else hostH - topMargin - bottomMargin
        if (w <= 0 || h <= 0) return null
        return Box(leftMargin, topMargin, w, h)
    }
}
