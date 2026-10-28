package com.cardesk.launcher.core

/**
 * 车门「张开」的姿态（**纯 Kotlin**，不依赖 Android，可 JVM 单测）。
 *
 * ## 要的是什么
 *
 * 车门示意是一张**俯视车模**，4 扇门各自是一块可以
 * **绕铰链旋转 ±45°** 的独立面板：
 *
 * - 每扇门的开合角度是 ±45°：左前门 `rotation(45f)`、右前门 `rotation(-45f)`、
 *   左后门 `rotation(45f)`、右后门 `rotation(-45f)`，时长都是 **1000ms**；
 * - 铰链位置是**每扇门单独设的 pivot**（`setPivotX/setPivotY`）。
 *
 * 不复制照片素材，只做**设计**：
 * 「门 = 一块能绕铰链翻开的板」。
 * 所以这里给出**一套与素材无关的铰链几何**：
 * 每扇门按它在车身上的位置，推断出铰链在哪一侧、往哪个方向翻。
 *
 * ## 几何约定
 *
 * 俯视图里车头朝上（屏幕坐标 y 向下，**车尾在 y 大的一侧**）。
 * 一扇门在车身上的位置由 [CarStatusGeometry] 给出：
 * 左门的 `left` 是**外沿**、`right` 是**内沿**；右门反之。
 *
 * 真实车门的铰链是**竖直轴**：
 *
 * - **前门**铰在前端（A 柱）→ 门板向后延伸，开门时**后缘**向外（并略向前）摆出去；
 * - **后门**铰在后端（C 柱）→ 门板向前延伸，开门时**前缘**向外（并略向后）摆出去。
 *
 * 俯视投影下，把门板矩形**绕铰链旋转**正好就是这个效果：
 * 门板长度方向转成 45°，自由端既向外又向另一侧收——
 * 和真车「门半开」的俯视剪影一致。
 *
 * 于是：
 *
 * | 门 | 铰链 | 旋转角（屏幕坐标，顺时针为正） |
 * | --- | --- | --- |
 * | 左前 | 前缘中点 | **+45°** |
 * | 右前 | 前缘中点 | **−45°** |
 * | 左后 | 后缘中点 | **−45°** |
 * | 右后 | 后缘中点 | **+45°** |
 *
 * 角度大小一致（±45°），只是左右两扇后门与相反——
 * 后门照片裁片里铰链画在前端，我们按真实车门（C 柱）画。
 */
object DoorSwing {

    /** 门板张开角（度）。与 `rotation(±45f)` 一致。 */
    const val OPEN_ANGLE_DEG = 45f

    /** 左前门在 [CarStatus.rects] / 车门明细里的下标。 */
    const val FL = 0

    /** 右前门下标。 */
    const val FR = 1

    /** 左后门下标。 */
    const val RL = 2

    /** 右后门下标。 */
    const val RR = 3

    /**
     * 一扇门的张开姿态。
     *
     * @param hingeX 铰链点 x（设计空间）
     * @param hingeY 铰链点 y（设计空间）
     * @param angleDeg 旋转角（度，屏幕坐标顺时针为正）
     */
    data class Pose(
        val hingeX: Float,
        val hingeY: Float,
        val angleDeg: Float,
    )

    /**
     * 推算某扇门在「打开」状态下的姿态。
     *
     * @param doorIndex 车门下标（[FL] / [FR] / [RL] / [RR]）
     * @param rect 门在设计空间里的矩形（[GeoRect]）
     */
    fun pose(doorIndex: Int, rect: GeoRect): Pose {
        val midX = (rect.left + rect.right) / 2f
        return when (doorIndex) {
            // 前门铰在前缘，后门铰在后缘
            FL -> Pose(midX, rect.top, OPEN_ANGLE_DEG)
            FR -> Pose(midX, rect.top, -OPEN_ANGLE_DEG)
            RL -> Pose(midX, rect.bottom, -OPEN_ANGLE_DEG)
            RR -> Pose(midX, rect.bottom, OPEN_ANGLE_DEG)
            else -> Pose(midX, rect.top, OPEN_ANGLE_DEG)
        }
    }

    /**
     * 把门板矩形绕铰链旋转后的**四个角**算出来（设计空间）。
     *
     * 主要给单测用（画的时候直接用 `Canvas.rotate` 更省事）。
     *
     * @return 8 个数：`left, top, right, bottom` × 2（原始矩形、旋转后矩形）
     */
    fun swingCorners(doorIndex: Int, rect: GeoRect): FloatArray {
        val pose = pose(doorIndex, rect)
        val rad = Math.toRadians(pose.angleDeg.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        val corners = floatArrayOf(
            rect.left, rect.top,
            rect.right, rect.top,
            rect.right, rect.bottom,
            rect.left, rect.bottom,
        )
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val dx = corners[i * 2] - pose.hingeX
            val dy = corners[i * 2 + 1] - pose.hingeY
            out[i * 2] = pose.hingeX + dx * cos - dy * sin
            out[i * 2 + 1] = pose.hingeY + dx * sin + dy * cos
        }
        return out
    }

    /**
     * 车门张开后**离车身中线最远**的那个角离中线有多远（设计空间）。
     *
     * 用来确认「张开的门不会压到旁边的文字列」—— 车况卡左右两列文字
     * 是贴着车模摆的，门张开太狠就会盖到字。
     */
    fun maxOutwardOffset(doorIndex: Int, rect: GeoRect): Float {
        val corners = swingCorners(doorIndex, rect)
        var max = 0f
        for (i in 0 until 4) {
            val dx = kotlin.math.abs(corners[i * 2] - (rect.left + rect.right) / 2f)
            if (dx > max) max = dx
        }
        return max
    }
}
