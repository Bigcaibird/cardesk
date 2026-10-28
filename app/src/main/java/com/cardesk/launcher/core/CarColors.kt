package com.cardesk.launcher.core

/**
 * 俯视车模的**日夜两套颜色**。
 *
 * ## 为什么单独一个文件
 *
 * 车模的颜色**不能**写死成夜间的深蓝：日间卡是白底，
 * 深蓝车身在日间会变成「白底上摆了一辆夜里的车」。
 * 设计板 `s01-home-day` / `s13-float-day` 的车模是
 * 车漆 `#8C97B0`、玻璃 `#C9D2E4`、轮胎 `#2A2F3A`、门缝 = `--stroke`。
 *
 * 所以这里把车模用到的每一笔颜色都列成日夜两套，
 * 由 [ScenePage] 按当前主题挑一套（[Skin.isNight]）。
 *
 * 夜间的取值 = 第四十九轮之前的写死值（不变，避免夜间回退）；
 * 日间的取值 = 设计板 `build_design_board.py:car_svg` 的日间 token。
 */
data class CarColors(
    /** 车身渐变两端（暗）。 */
    val bodyEdge: Int,
    /** 车身渐变中间（车顶高光）。 */
    val bodyMid: Int,
    /** 车身描边。 */
    val bodyEdgeLine: Int,
    /** 玻璃上沿。 */
    val glassTop: Int,
    /** 玻璃下沿。 */
    val glassBottom: Int,
    /** 车顶天幕（半透）。 */
    val roof: Int,
    /** 车顶天幕描边。 */
    val roofEdge: Int,
    /** 轮胎。 */
    val tire: Int,
    /** 轮胎描边。 */
    val tireEdge: Int,
    /** 前灯。 */
    val headLight: Int,
    /** 尾灯。 */
    val tailLight: Int,
    /** 关闭状态的门/窗面板填充。 */
    val panelClosedFill: Int,
    /** 关闭状态的门/窗面板描边。 */
    val panelClosedLine: Int,
    /** 开启状态的门/窗面板填充。 */
    val panelOpenFill: Int,
    /** 开启状态的门/窗面板描边。 */
    val panelOpenLine: Int,
    /** 状态未知时的描边。 */
    val panelUnknownLine: Int,
    /** 开门时「门原来的位置」留的暗洞填充。 */
    val panelGapFill: Int,
    /** 暗洞描边。 */
    val panelGapLine: Int,
) {
    companion object {
        /** 夜间（第四十九轮之前的写死值）。 */
        val night = CarColors(
            bodyEdge = 0xFF33405F.toInt(),
            bodyMid = 0xFF5A6EA6.toInt(),
            bodyEdgeLine = 0x8C93A9D4.toInt(),
            glassTop = 0xFF233152.toInt(),
            glassBottom = 0xFF121A2A.toInt(),
            roof = 0x80233052.toInt(),
            roofEdge = 0x6693A9D4.toInt(),
            tire = 0xFF0F141F.toInt(),
            tireEdge = 0xFF394562.toInt(),
            headLight = 0xE6D9E7FF.toInt(),
            tailLight = 0xE6FF7A7A.toInt(),
            panelClosedFill = 0x1AE6EEFA,
            panelClosedLine = 0x66E6EEFA,
            panelOpenFill = 0x4DFF9F43,
            panelOpenLine = 0xFFFF9F43.toInt(),
            panelUnknownLine = 0x73FFFFFF,
            panelGapFill = 0x59000000,
            panelGapLine = 0x66E6EEFA,
        )

        /**
         * 日间（设计板 的日间 token）。
         *
         * 车漆基准 = `--carBody` = `#8C97B0`，玻璃 `#C9D2E4`，轮胎 `#2A2F3A`，
         * 门缝 = `--stroke`（日间 `#DFE3EA`）—— 门/窗面板的描边改用中性灰，
         * 不能沿用夜间的浅青（`#E6EEFA` 在白底上几乎看不见）。
         */
        val day = CarColors(
            bodyEdge = 0xFF6E7A94.toInt(),
            bodyMid = 0xFFA7B2C8.toInt(),
            bodyEdgeLine = 0x8C4E586E.toInt(),
            glassTop = 0xFFC9D2E4.toInt(),
            glassBottom = 0xFFB4C0D8.toInt(),
            roof = 0x8099A5BE.toInt(),
            roofEdge = 0x666E7A94.toInt(),
            tire = 0xFF2A2F3A.toInt(),
            tireEdge = 0xFF6E7A94.toInt(),
            headLight = 0xE6FFFFFF.toInt(),
            tailLight = 0xE6E07373.toInt(),
            panelClosedFill = 0x1A4E586E,
            panelClosedLine = 0x8C4E586E.toInt(),
            panelOpenFill = 0x4D1F9D5F,
            panelOpenLine = 0xFFB7791F.toInt(),
            panelUnknownLine = 0x738B92A0.toInt(),
            panelGapFill = 0x59000000,
            panelGapLine = 0x8C4E586E.toInt(),
        )
    }
}
