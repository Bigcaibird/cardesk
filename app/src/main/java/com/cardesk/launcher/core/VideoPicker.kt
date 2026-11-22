package com.cardesk.launcher.core

import android.content.Intent
import android.net.Uri

/**
 * 第218轮：**视频壁纸的文件选择**（纯逻辑，可 JVM 单测）。
 *
 * ## 背景（实车诊断包实测）
 *
 * 领克 AAOS 820A 车机上：
 *
 * ```
 * 12:32:25.533 视频壁纸：收到选视频结果 resultCode=0 uri=null
 * 12:32:25.533 视频壁纸：进入导入处理 uri=null
 * ```
 *
 * 两个问题叠在一起：
 *
 * 1. `ACTION_OPEN_DOCUMENT` + 视频通配类型 在车机上**没人接**
 *    ⇒ `startActivityForResult` 直接抛 `ActivityNotFoundException`
 *    ⇒ 弹「没有可用的视频选择器」。
 * 2. 就算选择器打开了，车机文件管理器返回的 Intent **可能没有 `data`**
 *    ⇒ `data?.data` 是 null（上面日志就是 `resultCode=0` 但 `uri=null`）。
 *
 * ⇒ 两处都要修。
 *
 * ## 修法
 *
 * - **选择器**：按 [strategies] 的顺序逐个试，第一个能解析的就用它；
 *   全都不行 ⇒ 明确告诉用户「车机没有可用的视频选择器」并给出替代路径。
 * - **取 uri**：按 [extractUri] 的顺序兜底
 *   （`data` → `clipData[0]` → `EXTRA_STREAM`）。
 */
object VideoPicker {

    /** 一种选择器策略。 */
    data class Strategy(
        /** 意图的 action。 */
        val action: String,
        /** MIME 类型；null = 不限（让选择器自己筛）。 */
        val mime: String?,
    ) {
        /** 给人看的说明。 */
        fun describe(): String = if (mime == null) action else "$action ($mime)"
    }

    /**
     * 选择器降级链（**按优先级**）。
     *
     * | # | 策略 | 用途 |
     * |---|---|---|
     * | 1 | `OPEN_DOCUMENT` + 「视频通配类型」 | 最标准，能精确筛视频 |
     * | 2 | `OPEN_DOCUMENT` + 「不限类型」 | 车机的 DocumentsUI 只认「不限类型」时用 |
     * | 3 | `GET_CONTENT` + 「视频通配类型」 | 有些车机只注册了 GET_CONTENT |
     *
     * 顺序不能随便换：先精确后宽泛，能筛就别让用户自己找。
     */
    fun strategies(): List<Strategy> = listOf(
        Strategy(Intent.ACTION_OPEN_DOCUMENT, "video/*"),
        Strategy(Intent.ACTION_OPEN_DOCUMENT, "*/*"),
        Strategy(Intent.ACTION_GET_CONTENT, "video/*"),
    )

    /** 按策略构造 intent。 */
    fun buildIntent(strategy: Strategy): Intent = Intent(strategy.action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        if (strategy.mime != null) type = strategy.mime
    }

    /**
     * 从返回的 Intent 里**取视频文件的 uri**（多级兜底）。
     *
     * 顺序：
     * 1. `intent.data` —— 最常见；
     * 2. `intent.clipData[0].uri` —— 多选/部分车机走这个；
     * 3. `intent.getParcelableExtra(EXTRA_STREAM)` —— 老式文件管理器走这个。
     *
     * @return 取到的 uri；都取不到时返回 null。
     */
    fun extractUri(data: Intent?): Uri? {
        if (data == null) return null
        // ① 标准字段
        data.data?.let { return it }
        // ② clipData
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = runCatching { clip.getItemAt(i).uri }.getOrNull()
                if (uri != null) return uri
            }
        }
        // ③ EXTRA_STREAM
        val extra = runCatching {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }.getOrNull()
        if (extra != null) return extra
        return null
    }

    /**
     * 判断 uri 是不是「像视频」（按扩展名粗筛）。
     *
     * 降级链第 2 级是「不限类型」⇒ 选出来的可能是图片/文档，
     * 这里挡一道，给用户一个明确的提示而不是导入失败。
     */
    fun looksLikeVideo(uri: Uri): Boolean {
        val path = uri.lastPathSegment ?: uri.path ?: return false
        val lower = path.lowercase()
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    /** 常见视频扩展名。 */
    val VIDEO_EXTENSIONS: List<String> = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".3gp", ".m4v", ".webm", ".ts", ".flv", ".wmv",
    )
}
