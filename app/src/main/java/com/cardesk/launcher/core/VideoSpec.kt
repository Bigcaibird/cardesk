package com.cardesk.launcher.core

import java.io.File

/**
 * 视频壁纸的**规格判定**与**文件选择**（第三百一十八轮）。
 *
 * ## 为什么要判定规格
 *
 * 车机的解码器是**硬件**的，能力有上限。导入 4K / 60fps 的视频
 * 在电脑上没事，车机上可能直接解不动（黑屏）或者掉帧到个位数。
 *
 * 所以导入时就分三档：
 * - [Verdict.Reject]：明确不支持的（4K 以上 / 60fps 以上 / 读不出尺寸）⇒ 拒绝导入；
 * - [Verdict.Warn]：能播但可能卡的（超过 1080p30）⇒ 允许，但提示；
 * - [Verdict.Ok]：1080p30 及以下 ⇒ 直接收。
 *
 * ## 为什么是纯函数 + 单独的探测类
 *
 * 判定规则（这个文件）不碰 Android API ⇒ 可以直接 JVM 单测；
 * 读视频元数据要 `MediaMetadataRetriever`（见 [VideoProbe]）⇒ 分开。
 */
object VideoSpec {

    /** 硬上限：超过这个分辨率／帧率一律拒绝。 */
    const val HARD_MAX_WIDTH: Int = 3840
    const val HARD_MAX_HEIGHT: Int = 2160
    const val HARD_MAX_FPS: Float = 60f

    /** 软上限：超过会提示「可能卡顿」，但允许导入。 */
    const val SOFT_MAX_WIDTH: Int = 1920
    const val SOFT_MAX_HEIGHT: Int = 1080
    const val SOFT_MAX_FPS: Float = 30f

    /**
     * 探测到的视频信息。
     *
     * [fps] / [durationMs] 读不到时为 null / 0 —— 有些容器（比如部分 mkv）
     * 没有帧率元数据，这时候退化成「不判帧率」而不是当成 0fps。
     */
    data class Info(
        val width: Int,
        val height: Int,
        val fps: Float?,
        val durationMs: Long,
        val bytes: Long,
    )

    /** 判定结果。 */
    sealed interface Verdict {
        /** 完全没问题。 */
        object Ok : Verdict

        /** 能播，但可能卡顿（提示用户，仍然导入）。 */
        data class Warn(val reason: String) : Verdict

        /** 不支持（拒绝导入）。 */
        data class Reject(val reason: String) : Verdict
    }

    /** 按规格判定。 */
    fun check(info: Info): Verdict {
        if (info.width <= 0 || info.height <= 0) {
            return Verdict.Reject("读不出视频尺寸（文件可能已损坏）")
        }
        if (info.width > HARD_MAX_WIDTH || info.height > HARD_MAX_HEIGHT) {
            return Verdict.Reject(
                "分辨率 ${info.width}×${info.height} 超过 $HARD_MAX_WIDTH×$HARD_MAX_HEIGHT，车机解不动",
            )
        }
        val fps = info.fps
        if (fps != null && fps > HARD_MAX_FPS) {
            return Verdict.Reject("帧率 ${formatFps(fps)} 超过 ${HARD_MAX_FPS.toInt()}，车机解不动")
        }
        if (info.width > SOFT_MAX_WIDTH || info.height > SOFT_MAX_HEIGHT) {
            return Verdict.Warn(
                "分辨率 ${info.width}×${info.height} 高于 $SOFT_MAX_WIDTH×$SOFT_MAX_HEIGHT，播放可能卡顿",
            )
        }
        if (fps != null && fps > SOFT_MAX_FPS) {
            return Verdict.Warn("帧率 ${formatFps(fps)} 高于 ${SOFT_MAX_FPS.toInt()}，播放可能卡顿")
        }
        return Verdict.Ok
    }

    /**
     * 当前该播哪一个文件（第三百一十八轮：日/夜双视频）。
     *
     * 规则：
     * 1. 现在是夜间 **且** 夜间版存在且非空 ⇒ 播夜间版；
     * 2. 其余情况一律播日间版（夜间版没导入时，夜间就用日间那段，不黑屏）。
     *
     * 日间版都没有 ⇒ null（调用方回退静态壁纸）。
     */
    fun chooseFile(day: File?, night: File?, nightNow: Boolean): File? {
        if (nightNow && isUsable(night)) return night
        return if (isUsable(day)) day else null
    }

    private fun isUsable(file: File?): Boolean = file != null && file.isFile && file.length() > 0L

    /** 人类可读的规格摘要，例如 `1920×1080 · 30fps · 4.2MB`。 */
    fun summary(info: Info): String {
        val fps = info.fps
        val fpsText = if (fps == null) "帧率未知" else "${formatFps(fps)}fps"
        return "${info.width}×${info.height} · $fpsText · ${formatBytes(info.bytes)}"
    }

    /** 人类可读的字节数。 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0MB"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 100.0) "${mb.toInt()}MB" else String.format(java.util.Locale.US, "%.1fMB", mb)
    }

    /** 帧率格式化：整数不带小数点，其余保留一位。 */
    fun formatFps(fps: Float): String {
        val rounded = Math.round(fps * 10f) / 10f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(java.util.Locale.US, "%.1f", rounded)
    }
}
