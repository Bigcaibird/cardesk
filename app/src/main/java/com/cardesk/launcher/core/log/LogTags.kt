package com.cardesk.launcher.core.log

/**
 * 日志 tag 规范。
 *
 * 全部 tag 统一带 `CarDesk/` 前缀，方便在真车上用一条命令筛出本 App 的日志：
 *
 * `bash
 * adb logcat | grep CarDesk/
 * `
 *
 * 分域规则（一位主责方一个 tag，方便按功能收集）：
 *
 * | tag | 负责的功能域 |
 * |---|---|
 * | `CarDesk/App` | 启动 / 生命周期 / 全局 |
 * | `CarDesk/Vehicle` | 车控通道（AdaptAPI / AOSP / 模拟）连接与读写 |
 * | `CarDesk/Hvac` | 空调：写入、回读、失败回滚 |
 * | `CarDesk/Cam360` | 360 环影 |
 * | `CarDesk/Window` | 车窗 |
 * | `CarDesk/Signal` | 车辆信号：胎压 / 续航 / 车门 / 油量 / 驾驶模式 |
 * | `CarDesk/Media` | 媒体会话、切歌、歌词、封面、来源 |
 * | `CarDesk/MusicUI` | 音乐页交互 |
 * | `CarDesk/NavUI` | 导航页 / 画中画 |
 * | `CarDesk/Scene` | 场景页卡片交互 |
 * | `CarDesk/Dock` | 底部 Dock |
 * | `CarDesk/Drawer` | 应用抽屉 |
 * | `CarDesk/Setting` | 设置页变更 |
 * | `CarDesk/Task` | 计划任务 |
 * | `CarDesk/Lan` | 局域网传输 |
 * | `CarDesk/Weather` | 天气 |
 * | `CarDesk/Keys` | 按键增强 / 按键拦截 |
 * | `CarDesk/Sys` | 系统能力：policy_control / overscan / 状态栏 / 导航栏 |
 * | `CarDesk/Theme` | 日夜模式 / 主题 |
 * | `CarDesk/Float` | 浮窗 |
 * | `CarDesk/Log` | 日志系统自身（导出、滚动、失败） |
 */
object LogTags {

    const val APP = "CarDesk/App"
    const val VEHICLE = "CarDesk/Vehicle"
    const val HVAC = "CarDesk/Hvac"
    const val CAM360 = "CarDesk/Cam360"
    const val WINDOW = "CarDesk/Window"
    const val SIGNAL = "CarDesk/Signal"
    const val MEDIA = "CarDesk/Media"
    const val MUSIC_UI = "CarDesk/MusicUI"
    const val NAV_UI = "CarDesk/NavUI"
    const val SCENE = "CarDesk/Scene"
    const val DOCK = "CarDesk/Dock"
    const val DRAWER = "CarDesk/Drawer"
    const val SETTING = "CarDesk/Setting"
    const val TASK = "CarDesk/Task"
    const val LAN = "CarDesk/Lan"
    const val WEATHER = "CarDesk/Weather"
    const val KEYS = "CarDesk/Keys"
    const val SYS = "CarDesk/Sys"
    const val THEME = "CarDesk/Theme"
    const val FLOAT = "CarDesk/Float"
    const val LOG = "CarDesk/Log"

    /** 全部 tag（导出诊断包时列出，方便排查「哪个域没日志」）。 */
    val ALL: List<String> = listOf(
        APP, VEHICLE, HVAC, CAM360, WINDOW, SIGNAL, MEDIA, MUSIC_UI, NAV_UI, SCENE,
        DOCK, DRAWER, SETTING, TASK, LAN, WEATHER, KEYS, SYS, THEME, FLOAT, LOG,
    )
}
