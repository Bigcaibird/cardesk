package com.cardesk.launcher.core.log

import android.os.SystemClock

/**
 * **日志节流**（第220轮）。
 *
 * ## 为什么需要
 *
 * 实车诊断包实测的日志速率：
 *
 * | 来源 | 行/秒 |
 * | --- | --- |
 * | 氛围灯逐帧 | 20 |
 * | 车控属性变化回调 | 21 |
 * | 车辆信号降级链（「读不到」逐条记） | 14 |
 * | **合计** | **~60 行/秒** |
 *
 * 60 行/秒 × 150 字节 ≈ **9KB/秒** ⇒ 4MB 的日志预算 **6.5 分钟**就写满了。
 *
 * 后果很实际：诊断包只能装下最近 8 份文件，
 * 实车那份诊断包里**最老的 4 份直接被丢弃** ——
 * 也就是说「出问题之前发生了什么」全没了，只看得到出问题之后。
 *
 * ## 做法
 *
 * 高频的「每帧 / 每回调 / 每轮探测」类日志改成**按 key 节流**：
 *
 * ```kotlin
 * if (LogThrottle.allow("vehicle-cb-$func", 1000L)) {
 *     Logx.d(LogTags.VEHICLE, "属性变化回调", ...)
 * }
 * ```
 *
 * - 第一次一定记（现场第一条最重要）；
 * - 之后每 [intervalMs] 最多记一条；
 * - **Logcat 里仍然全量**（要查细节随时能查），
 *   节流的只是**落盘**这条路径 —— 落盘的量是有限的，得留给真正的异常。
 *
 * ## 为什么要「按 key」而不是全局节流
 *
 * 不同来源的问题要能同时看到：属性 A 变了要能看到，属性 B 变了也要能看到。
 * 按 key 节流才能各记各的。
 */
object LogThrottle {

    /** 每个 key 的上次「放行」时刻（`SystemClock.elapsedRealtime()`）。 */
    private val last = HashMap<String, Long>()

    /** key 数量上限（超了就整体清空 —— 宁可重新放行，也不要无限涨）。 */
    private const val MAX_KEYS = 2048

    /**
     * 问一句「这条现在能不能记」。
     *
     * @param key 区分来源的键（同一个 key 共享一个节流窗口）
     * @param intervalMs 最小间隔（毫秒）
     * @return true = 这次可以记
     */
    @JvmStatic
    @Synchronized
    fun allow(key: String, intervalMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val prev = last[key]
        if (prev == null || now - prev >= intervalMs) {
            if (last.size >= MAX_KEYS) last.clear()
            last[key] = now
            return true
        }
        return false
    }

    /** 清掉所有节流状态（换歌 / 重连 / 清日志后调用，避免旧窗口挡住新记录）。 */
    @JvmStatic
    @Synchronized
    fun reset() {
        last.clear()
    }
}
