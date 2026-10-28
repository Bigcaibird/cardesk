package com.cardesk.launcher.vehicle

/**
 * 四轮的**下标语义**与**页面摆放位置**。
 *
 * ## 数据下标（全局唯一口径）
 *
 * 胎压数组的下标语义由 TPMS 通道决定（`AdaptapiTpms.POS_TO_INDEX`）：
 *
 * | 下标 | 轮位 | TPMS pos |
 * |---|---|---|
 * | 0 | 左前 FL | 1 |
 * | 1 | 右前 FR | 3 |
 * | 2 | 左后 RL | 2 |
 * | 3 | 右后 RR | 4 |
 *
 * 这个顺序与 `TireData[] = {new TireData(0), new TireData(2), new TireData(1), new TireData(3)}`
 * 下标语义一致（0=FL、1=FR、2=RL、3=RR）。
 *
 * ## 页面摆放
 *
 * 我们**第一页（场景页）与第二页（导航页）统一**采用**竖置车模、车头朝上**：
 *
 * `
 *   左列 右列
 *   左前 FL（上） 右前 FR（上）
 *   左后 RL（下） 右后 RR（下）
 * `
 *
 * 第二页就是这个布局
 * 第一页用的是**横置车模、车头朝左**
 * 左列 FR/FL、右列 RR/RL —— 与第二页天然对不上。
 *
 * 我们第一页统一到第二页的布局（用户诉求：「第一页的胎压显示顺序和第二页对不上」），
 * 代价是与第一页的布局不同，但四轮与车模的相对关系始终自洽。
 */
object TireOrder {

    /** 左前。 */
    const val FL = 0

    /** 右前。 */
    const val FR = 1

    /** 左后。 */
    const val RL = 2

    /** 右后。 */
    const val RR = 3

    /** 左列：上 = 左前，下 = 左后。 */
    val LEFT_COLUMN = intArrayOf(FL, RL)

    /** 右列：上 = 右前，下 = 右后。 */
    val RIGHT_COLUMN = intArrayOf(FR, RR)

    /** 左列第 row 行的数据下标（0=上，1=下）。 */
    fun leftIndex(row: Int): Int = LEFT_COLUMN[row]

    /** 右列第 row 行的数据下标（0=上，1=下）。 */
    fun rightIndex(row: Int): Int = RIGHT_COLUMN[row]

    /** 四个轮位的名称（与下标一一对应）。 */
    val NAMES = arrayOf("左前", "右前", "左后", "右后")
}
