package com.cardesk.launcher.core.media

/**
 * 场景页音乐卡的**进度环**与**封面光晕**几何（纯逻辑，可单测）。
 *
 *
 * - 进度环：（画法，
 *    喂 fraction
 * - 封面光晕：（渐变规格，
 *    裁剪
 */
object MusicCardVisuals {

    /**
     * 进度环 fraction（0..1）。
     *
     *
     * `
     * if (duration > 0) {
     *     long pos = max(0, min(position, duration));
     *     ring.setFraction((float) pos / (float) duration);
     * } else {
     *     resetMediaProgress(); // → setFraction(0f)
     * }
     * `
     * 所以**总时长不可用时是 0**（只画轨道），而不是拿 position 当比例。
     */
    fun ringFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val pos = positionMs.coerceIn(0L, durationMs)
        return (pos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 进度环半径。
     *
     *
     * `fMin = min(width / 2, height / 2) - max(1, track.getStrokeWidth())`。
     *
     * 容器 80×80、描边 2 → 40 − 2 = **38**（描边画在路径两侧，最外缘留 1px）。
     */
    fun ringRadius(size: Float, strokeWidth: Float): Float {
        val half = size / 2f
        return half - maxOf(1f, strokeWidth)
    }

    /**
     * 封面光晕的圆心 x（卡片设计坐标）。
     *
     * 光晕层 `marginStart/End = -24` → 铺满整张卡
     * 渐变圆心 = `(width * 0.1f, height * 0.5f)`。
     */
    fun glowCenterX(cardLeft: Float, cardWidth: Float): Float = cardLeft + cardWidth * 0.1f

    /** 封面光晕的圆心 y（卡片设计坐标）。 */
    fun glowCenterY(cardTop: Float, cardHeight: Float): Float = cardTop + cardHeight * 0.5f

    /**
     * 封面光晕的渐变半径。
     *
     * `Math.max(width, height)`。
     */
    fun glowRadius(cardWidth: Float, cardHeight: Float): Float = maxOf(cardWidth, cardHeight)

    /**
     * 封面光晕的起始色（argb）。
     *
     * `Color.argb(128, r, g, b)`。
     *  会把传入颜色的 alpha 强制成 0xFF
     * 所以这里只取 RGB。
     */
    fun glowColor(accent: Int, alpha: Int): Int = (alpha shl 24) or (accent and 0xFFFFFF)

    /**
     * 音乐卡里标题 / 歌手那一列的宽度。
     *
     * 卡宽 400、padding 24×2、封面 96
     * 标题列左边距 20、进度环容器 80 → 标题列 = 400 − 48 − 96 − 20 − 80 = **156**。
     *
     * 第四十九轮「浮屿」把底部一排统一收窄（音乐卡 400 → 338）。
     *
     * 第五十轮再对齐设计板 s01：封面 96 → 68、封面与文字列间距 20 → 14、
     * 进度环容器 80 → 64、环与文字列间距 16 → 14、内边距 24 → 18，
     * 标题列 = 338 − 36 − 68 − 14 − 14 − 64 = **142**。
     *
     * 数值全部参数化，改宽度时只要跟着改这组参数，不用再手算。
     */
    fun textColumnWidth(
        cardWidth: Float = 338f,
        pad: Float = 18f,
        cover: Float = 68f,
        coverGap: Float = 14f,
        ringGap: Float = 14f,
        ring: Float = 64f,
    ): Float = cardWidth - pad * 2f - cover - coverGap - ringGap - ring
}
