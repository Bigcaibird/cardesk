package com.cardesk.launcher.core.media

/**
 * 五行歌词块的**排版规则**（纯 Kotlin，可在 JVM 单测里覆盖）。
 *
 * 桌面正中那块大字歌词（`ScenePage`）和设置页里的**预览条**
 * 走的是同一份规则 —— 如果两边各写一套，预览跟实际画出来的东西对不上，
 * 用户就白调滑杆了。
 *
 * 行序固定：**上 2 / 上 1 / 当前 / 下 1 / 下 2**。
 */
object LyricBlockLayout {

    /** 行数。 */
    const val ROWS = 5

    /** 当前行在行序里的下标。 */
    const val CURRENT = 2

    /**
     * 算 5 行的基线（**相对当前行**，当前行 = 0）。
     *
     * 相邻两行的基线间距 = 下行的 ascent + 行距 + 上行的 descent。
     *
     * **为什么基线要算**：ascent / descent 是「基线往上 / 往下」的字形留白，
     * 随字号和字体变。写死基线一改字号就会「两行粘在一起」或「中间裂开一大段」。
     *
     * @param asc 每行的 ascent（**正值**）
     * @param desc 每行的 descent（**正值**）
     * @param gapMain 当前句与相邻衬句之间的行距
     * @param gapSide 衬句与衬句之间的行距
     * @return 长度 [ROWS] 的数组：0=上2、1=上1、2=当前、3=下1、4=下2
     */
    fun baselines(asc: FloatArray, desc: FloatArray, gapMain: Float, gapSide: Float): FloatArray {
        require(asc.size >= ROWS && desc.size >= ROWS) { "每行都要有 ascent / descent" }
        val base = FloatArray(ROWS)
        base[CURRENT] = 0f
        base[1] = base[CURRENT] - (asc[CURRENT] + gapMain + desc[1])
        base[0] = base[1] - (asc[1] + gapSide + desc[0])
        base[3] = base[CURRENT] + (desc[CURRENT] + gapMain + asc[3])
        base[4] = base[3] + (desc[3] + gapSide + asc[4])
        return base
    }

    /**
     * 整块的平移量：让块的中心落在 [target]。
     *
     * 块的范围：从上 2 句的字顶（`base[0] - ascent[0]`）到下 2 句的字底
     * （`base[4] + descent[4]`）。
     *
     * **为什么要锚块中心而不是当前行**：字号可调之后整块高度会变；
     * 锚在当前行基线上，放大时整块往上顶、缩小时往下坠，位置跟着字号漂。
     * 锚在块中心上，字号怎么调，块都在同一个中心。
     *
     * @param asc 每行的 ascent（正值）
     * @param desc 每行的 descent（正值）
     * @param base [baselines] 算出来的基线（相对当前行）
     * @param target 目标中心 y
     * @return 5 条基线都要加上的平移量
     */
    fun centerShift(asc: FloatArray, desc: FloatArray, base: FloatArray, target: Float): Float {
        require(base.size >= ROWS) { "基线数组长度不够" }
        val top = base[0] - asc[0]
        val bottom = base[4] + desc[4]
        return target - (top + bottom) / 2f
    }
}
