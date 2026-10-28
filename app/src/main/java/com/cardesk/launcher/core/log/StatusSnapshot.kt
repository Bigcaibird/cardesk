package com.cardesk.launcher.core.log

import android.content.Context
import android.os.Build
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.vehicle.VehicleController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行状态速览（**只读**）。
 *
 * 用于两处：
 * - 设置页「诊断日志」页顶部：用户在车上不用导出就能看到关键状态；
 * - 诊断包 `summary.txt` 的状态段。
 *
 * 原则：**只读**，绝不触发任何写入车机的动作
 * （尤其不调用 `VehicleController.selfTest()`——它会真的写一次空调再写回）。
 */
object StatusSnapshot {

    /** 状态速览文本（多行）。 */
    fun text(context: Context): String {
        val sb = StringBuilder()
        sb.append("CarDesk ").append(versionName(context)).append('\n')
        sb.append("车控通道：").append(channelLine()).append('\n')
        sb.append("空调状态：").append(hvacLine()).append('\n')
        sb.append("车辆信号：").append(signalLine()).append('\n')
        sb.append("媒体：").append(mediaLine()).append('\n')
        sb.append("通知使用权：").append(notificationLine(context)).append('\n')
        sb.append("日志：级别 ").append(Logx.level().label)
            .append("，文件 ").append(Logx.logDirectory()?.listFiles()?.size ?: 0).append(" 份")
            .append("，占用 ").append(Logx.totalBytes() / 1024).append(" KB")
            .append("（上限 ").append(FileLogSink.MAX_TOTAL_BYTES / 1024).append(" KB）\n")
        // 落盘降级 = 车机存储不够了，日志只剩 Logcat —— 要让用户一眼看到
        if (Logx.degraded()) {
            sb.append("⚠ 日志落盘已降级：磁盘剩余空间不足，只写 Logcat\n")
        }
        sb.append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
        return sb.toString()
    }

    /** 当前生效的车控通道。 */
    fun channelLine(): String = runCatching {
        buildString {
            append(VehicleController.sourceLabel)
            if (VehicleController.isMockFallback) append("（已回退模拟车，车控不会作用于实车）")
            if (!VehicleController.lastWriteOk) {
                append("；最近一次写入失败：")
                append(VehicleController.lastWriteError ?: "未知原因")
            }
        }
    }.getOrDefault("未初始化")

    /** 空调状态摘要。 */
    fun hvacLine(): String = runCatching {
        val s = VehicleController.state
        "电源=${if (s.power) "开" else "关"} " +
            "A/C=${if (s.ac) "开" else "关"} " +
            "AUTO=${if (s.auto) "开" else "关"} " +
            "风量=${s.fanLevel} " +
            "主驾=${s.tempDriver}℃ 副驾=${s.tempPassenger}℃"
    }.getOrDefault("读取失败")

    /**
 * 车辆信号摘要（续航 / 油量 / 胎压 / 环温）。
 *
 * 真机上这三个值之前一直是「不可读」，光看日志很难判断是「通道没接上」
 * 还是「车端没值」，所以直接放进诊断包快照。
 */
fun signalLine(): String = runCatching {
        val info = com.cardesk.launcher.vehicle.currentVehicleInfo()
        buildString {
            append("续航=").append(if (info.rangeKm >= 0) "${info.rangeKm}km" else "不可读")
            append(" 油量=").append(info.fuelPercent?.let { "$it%" } ?: "不可读")
            append(" 胎压=").append(
                info.tires.joinToString(",") { if (it.isNaN()) "-" else String.format(Locale.US, "%.1f", it) },
            )
            append(" 环温=").append(if (info.ambientTemp.isNaN()) "不可读" else "${info.ambientTemp.toInt()}℃")
        }
    }.getOrDefault("读取失败")

    /** 媒体状态摘要。 */
    fun mediaLine(): String = runCatching {        val mc = MusicController.get()
        val title = mc.currentTitle.ifBlank { "暂无播放" }
        "歌名=$title 歌手=${mc.currentArtist.ifBlank { "-" }} " +
            "歌词=${mc.lyricState.ifBlank { "-" }}"
    }.getOrDefault("读取失败")

    /**
     * 通知使用权摘要。
     *
     * 没授权时系统不会返回任何媒体会话（抛 SecurityException，
     * 被我们兜成空列表，音乐区会一直显示占位 —— 这一行让「没音乐」和「没权限」一眼可分。
     *
     * 同时给出 `MEDIA_CONTENT_CONTROL` 的状态：它是会话枚举的**另一条通路**
     * （AOSP：持有该权限 或 组件是已启用的通知监听器），
     * 平台签名的 system 版能拿到，所以枚举通常不依赖通知使用权白名单。
     */
    fun notificationLine(context: Context): String = runCatching {
        com.cardesk.launcher.core.media.NotificationAccess.describeEnumeration(context)
    }.getOrDefault("读取失败")

    private fun versionName(context: Context): String = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        "${pi.versionName ?: "?"} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()})"
    }.getOrDefault("?")
}
