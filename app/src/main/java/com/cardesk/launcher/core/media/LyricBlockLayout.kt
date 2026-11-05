package com.cardesk.launcher.core.media

/**
 * 首页中央大字歌词块的**排版规则**（纯 Kotlin，可在 JVM 单测里覆盖）。
 *
 * 桌面正中那块大字歌词（`ScenePage`）和设置页里的**预览条**
 * 走的是同一份规则 —— 如果两边各写一套，预览跟实际画出来的东西对不上，
 * 用户就白调滑杆了。
 *
 * ## 行数不再写死
 *
 * 第117轮起有两种布局（[LyricLayout.THREE] 三行 / [LyricLayout.KTV] 两行），
 * 行数、当前行在哪都由布局清单给，这里只管**按给定的行数算基线**。
 *
 * ## 行序约定
 *
 * 行序 = **最上 → 最下**。当前行**不固定在中间**：
 * 三行布局的当前行在中间（下标 1），
 * KTV 两行布局的当前行在**最上面**（下标 0）——
 * 唱过的那句往上走掉，屏幕上留「正在唱的大字 + 马上要唱的小字」。
 */
object LyricBlockLayout {

    /**
     * 算各行的基线（**相对当前行**，当前行 = 0）。
     *
     * 相邻两行的基线间距 = 下行的 ascent + 行距 + 上行的 descent。
     *
     * **为什么基线要算**：ascent / descent 是「基线往上 / 往下」的字形留白，
     * 随字号和字体变。写死基线一改字号就会「两行粘在一起」或「中间裂开一大段」。
     *
     * @param asc 每行的 ascent（**正值**）
     * @param desc 每行的 descent（**正值**）
     * @param gap 行距（像素）
     * @param rows 行数
     * @param current 当前行在行序里的下标
     * @return 长度 [rows] 的数组，下标 = 行序（0 = 最上一行）
     */
    fun baselines(
        asc: FloatArray,
        desc: FloatArray,
        gap: Float,
        rows: Int,
        current: Int,
    ): FloatArray {
        require(asc.size >= rows && desc.size >= rows) { "每行都要有 ascent / descent" }
        require(rows >= 1) { "至少要一行" }
        require(current in 0 until rows) { "当前行下标越界：current=$current rows=$rows" }
        val base = FloatArray(rows)
        base[current] = 0f
        // 往上：每一行都离当前行更近一点
        for (i in current - 1 downTo 0) {
            base[i] = base[i + 1] - (asc[i + 1] + gap + desc[i])
        }
        // 往下：同理
        for (i in current + 1 until rows) {
            base[i] = base[i - 1] + (desc[i - 1] + gap + asc[i])
        }
        return base
    }

    /**
     * 整块的平移量：让块的中心落在 [target]。
     *
     * 块的范围：从**最上一行**的字顶（`base[0] - ascent[0]`）
     * 到**最下一行**的字底（`base[rows-1] + descent[rows-1]`）。
     *
     * **为什么要锚块中心而不是当前行**：字号可调之后整块高度会变；
     * 锚在当前行基线上，放大时整块往上顶、缩小时往下坠，位置跟着字号漂。
     * 锚在块中心上，字号怎么调，块都在同一个中心。
     *
     * @param asc 每行的 ascent（正值）
     * @param desc 每行的 descent（正值）
     * @param base [baselines] 算出来的基线（相对当前行）
     * @param target 目标中心 y
     * @param rows 行数
     * @return 所有行都要加上的平移量
     */
    fun centerShift(
        asc: FloatArray,
        desc: FloatArray,
        base: FloatArray,
        target: Float,
        rows: Int,
    ): Float {
        require(base.size >= rows) { "基线数组长度不够" }
        require(rows >= 1) { "至少要一行" }
        val top = base[0] - asc[0]
        val bottom = base[rows - 1] + desc[rows - 1]
        return target - (top + bottom) / 2f
    }
}
