package com.cardesk.launcher.core.nav

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock

/**
 * 高德广播数据源的**运行期计数**（第 107 轮，诊断用）。
 *
 * 巡航红绿灯收不到时，先要回答三个问题：
 * 1. 高德的引导广播到底有没有来？（`sendFrames`）
 * 2. 修改版的巡航红绿灯广播到底有没有来？（`cruiseFrames`）
 * 3. 来的帧里有没有灯态字段？（`lightFrames` / `lastLightAt`）
 *
 * 计数落在对象里、由 [com.cardesk.launcher.core.log.Diagnostics] 读取写进 summary.txt，
 * 这样用户导一份诊断包就能直接判断「是发包方没发」还是「我们没解析」。
 */
object AmapGuideStats {

    @Volatile var registered: Boolean = false
    @Volatile var sendFrames: Int = 0
    @Volatile var cruiseFrames: Int = 0
    @Volatile var lightFrames: Int = 0
    @Volatile var lastCruiseJsonLen: Int = -1
    @Volatile var lastLightAtMs: Long = 0L
    @Volatile var lastSendAtMs: Long = 0L
    @Volatile var lastCruiseAtMs: Long = 0L
    @Volatile var lastState: Int = -1

    /** 每类 KEY_TYPE 的帧数（只保留出现过的，上限很小）。 */
    val keyTypeCounts: MutableMap<Int, Int> = java.util.LinkedHashMap()

    fun onRegistered(value: Boolean) {
        registered = value
    }

    fun onSendFrame(keyType: Int) {
        sendFrames++
        lastSendAtMs = SystemClock.elapsedRealtime()
        synchronized(keyTypeCounts) {
            if (keyTypeCounts.size < 24 || keyTypeCounts.containsKey(keyType)) {
                keyTypeCounts[keyType] = (keyTypeCounts[keyType] ?: 0) + 1
            }
        }
    }

    fun onCruiseFrame(jsonLen: Int) {
        cruiseFrames++
        lastCruiseAtMs = SystemClock.elapsedRealtime()
        lastCruiseJsonLen = jsonLen
    }

    fun onLightFrame() {
        lightFrames++
        lastLightAtMs = SystemClock.elapsedRealtime()
    }

    fun onState(state: Int) {
        lastState = state
    }

    fun reset() {
        registered = false
        sendFrames = 0
        cruiseFrames = 0
        lightFrames = 0
        lastCruiseJsonLen = -1
        lastLightAtMs = 0L
        lastSendAtMs = 0L
        lastCruiseAtMs = 0L
        lastState = -1
        synchronized(keyTypeCounts) { keyTypeCounts.clear() }
    }

    private fun age(ms: Long): String {
        if (ms <= 0L) return "(从未)"
        val d = (SystemClock.elapsedRealtime() - ms) / 1000
        return "${d}s 前"
    }

    /** 诊断包里的一整段（多行）。 */
    fun describe(context: Context): String {
        val sb = StringBuilder()
        sb.append("接收器已注册: ").append(if (registered) "是" else "否（导航数据源没启动或注册失败）").append('\n')
        val pkg = try {
            com.cardesk.launcher.core.AmapCitySource.findAmapPackage(context)
        } catch (e: Throwable) {
            null
        }
        sb.append("高德包名: ").append(pkg ?: "(未安装)").append('\n')
        if (pkg != null) {
            try {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                sb.append("高德版本: ").append(info.versionName).append(" (").append(info.versionCode).append(")\n")
            } catch (e: PackageManager.NameNotFoundException) {
                sb.append("高德版本: (读不到)\n")
            }
        }
        sb.append("引导广播帧: ").append(sendFrames).append("（最近 ").append(age(lastSendAtMs)).append("）\n")
        sb.append("巡航红绿灯广播: ").append(cruiseFrames).append(" 帧（最近 ").append(age(lastCruiseAtMs)).append("）")
        if (lastCruiseJsonLen >= 0) sb.append("，最近 data 长度=").append(lastCruiseJsonLen)
        sb.append('\n')
        sb.append("解析到灯态帧: ").append(lightFrames).append("（最近 ").append(age(lastLightAtMs)).append("）\n")
        sb.append("最近高德状态: ").append(if (lastState < 0) "(未收到)" else lastState.toString()).append('\n')
        val counts = synchronized(keyTypeCounts) { keyTypeCounts.entries.joinToString(", ") { "${it.key}×${it.value}" } }
        sb.append("KEY_TYPE 统计: ").append(counts.ifEmpty { "(无)" }).append('\n')
        if (cruiseFrames == 0) {
            sb.append("判断: 高德没有发 NAVITOOL_CRUISE_TRAFFIC_LIGHTS —— 不是解析问题，是发包方/版本/设置问题。\n")
        }
        return sb.toString()
    }
}
