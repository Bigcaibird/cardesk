package com.cardesk.launcher.core

/**
 * 设计坐标系：整个桌面按 1920×1080 设计稿排版，整层等比缩放到实际屏幕。
 * 与「车机桌面」的做法一致——布局尺寸直接当设计像素用，最后统一 scale。
 */
object Design {
    const val W = 1920f
    const val H = 1080f

    const val STATUS_H = 76f
    const val DOCK_H = 132f

    fun scaleFor(screenW: Int, screenH: Int): Float {
        if (screenW <= 0 || screenH <= 0) return 1f
        return minOf(screenW / W, screenH / H).coerceAtLeast(0.15f)
    }
}

/**
 * 「浮屿 Flat Isle」的形状语言（第四十九轮）。
 *
 * 设计稿里只有四个圆角档位，全 App 共用，避免各页面各写各的：
 * - [CARD] 卡片（大圆角，识别度来源）
 * - [INNER] 卡片内嵌块 / 列表行
 * - [TILE] 图标底板
 * - [PILL] 胶囊（状态栏分段、Dock 媒体卡、开关）
 *
 * 所有数值都是**设计坐标像素**（1920×1080 坐标系）。
 */
object Shape {
    /**
     * 卡片圆角。
     *
     * 第五十八轮起**可由用户调整**（设置页「卡片风格」），
     * 改动统一走 [CardStyle.applyRadius]，内嵌块 / 图标底会等比跟着走。
     */
    var CARD = 28f

    /** 内嵌块 / 列表行圆角。 */
    var INNER = 18f

    /** 图标底板圆角。 */
    var TILE = 16f

    /** 胶囊圆角（等于半高即视觉上完全圆）。 */
    const val PILL = 999f

    /** 细分割线 / 进度条圆角。 */
    const val HAIRLINE = 4f
}
