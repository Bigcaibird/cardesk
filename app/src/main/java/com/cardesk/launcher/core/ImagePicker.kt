package com.cardesk.launcher.core

import android.content.Intent
import android.net.Uri

/**
 * 第230轮：**静态壁纸的图片选择**（纯逻辑，可 JVM 单测）。
 *
 * ## 背景（用户反馈 + 实车同源问题）
 *
 * 用户：「静态壁纸导入功能也有问题，提示没有图片选择器」。
 *
 * 报错来自 `MainActivity.startWallpaperImport()` —— 它只发**一次**
 * `ACTION_OPEN_DOCUMENT` + 图片通配类型，抛异常就弹「没有可用的图片选择器」。
 * 车机（领克 AAOS 820A）没有相册 / DocumentsUI 接管这个 action
 * ⇒ 用户完全没法导入图片壁纸。这与视频壁纸第218轮遇到的是同一堵墙。
 *
 * ## 修法（三层，与视频壁纸那套对齐）
 *
 * 1. **选择器降级链**：[strategies] 从精确到宽泛逐个试，用 `resolveActivity`
 *    预检（别等抛异常）；
 * 2. **全都不行**：打开应用内图片列表（[ImageLibrary] + `ImagePickerOverlay`），
 *    车机里只要有图片文件就能选；
 * 3. **取 uri 多级兜底**：[extractUri]（`data` → `clipData[0]` → `EXTRA_STREAM`），
 *    车机文件管理器返回的 Intent 常常没有 `data`。
 */
object ImagePicker {

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
     * | 1 | `OPEN_DOCUMENT` + 图片通配类型 | 最标准，能精确筛图片 |
     * | 2 | `OPEN_DOCUMENT` + 不限类型 | 车机的 DocumentsUI 只认「不限类型」时用（它本身是文件浏览器，安全） |
     * | 3 | `GET_CONTENT` + 图片通配类型 | 有些车机只注册了 GET_CONTENT |
     *
     * 顺序不能随便换：先精确后宽泛。
     *
     * ## 为什么**没有** `GET_CONTENT` + 不限类型
     *
     * API 28 模拟器实测：把相册和 DocumentsUI 都停用后，
     * `GET_CONTENT` + 不限类型 会被**任意 app** 接管
     * （实测 handler 是 `com.android.music`）——
     * 用户点「导入壁纸」直接跳进一个音乐播放器，比「没有选择器」更糟。
     * ⇒ 这一档删掉，宁可落到应用内列表。
     */
    fun strategies(): List<Strategy> = listOf(
        Strategy(Intent.ACTION_OPEN_DOCUMENT, "image/*"),
        Strategy(Intent.ACTION_OPEN_DOCUMENT, "*/*"),
        Strategy(Intent.ACTION_GET_CONTENT, "image/*"),
    )

    /** 按策略构造 intent。 */
    fun buildIntent(strategy: Strategy): Intent = Intent(strategy.action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        if (strategy.mime != null) type = strategy.mime
    }

    /**
     * 从返回的 Intent 里**取图片的 uri**（多级兜底）。
     *
     * 顺序与 [VideoPicker.extractUri] 一致：
     * 1. `intent.data`；
     * 2. `intent.clipData[i]`；
     * 3. `intent.getParcelableExtra(EXTRA_STREAM)`。
     */
    fun extractUri(data: Intent?): Uri? {
        if (data == null) return null
        data.data?.let { return it }
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = runCatching { clip.getItemAt(i).uri }.getOrNull()
                if (uri != null) return uri
            }
        }
        val extra = runCatching {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }.getOrNull()
        return extra
    }

    /**
     * 判断 uri 是不是「像图片」（按扩展名粗筛）。
     *
     * 降级链第 2/4 级是「不限类型」⇒ 可能选到视频/文档，
     * 这里挡一道，给用户明确提示而不是导入失败。
     */
    fun looksLikeImage(uri: Uri): Boolean {
        val path = uri.lastPathSegment ?: uri.path ?: return false
        val lower = path.lowercase()
        return ImageLibrary.EXTENSIONS.any { lower.endsWith(it) }
    }
}
