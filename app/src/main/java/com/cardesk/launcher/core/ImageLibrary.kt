package com.cardesk.launcher.core

import android.content.Context
import android.os.Environment
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File

/**
 * 第230轮：**图片库**（应用内壁纸选择器用，纯逻辑可 JVM 单测）。
 *
 * ## 为什么要有这个
 *
 * 用户反馈（真车）：静态壁纸「导入」点了只弹一句「没有可用的图片选择器」。
 * 根因和视频壁纸第218轮一模一样 —— 车机上**没有系统相册 / DocumentsUI**接管
 * `ACTION_OPEN_DOCUMENT`，`startActivityForResult` 直接抛
 * `ActivityNotFoundException`。
 *
 * 视频那条路第219轮已经用「应用内文件列表」兜住了（[VideoLibrary] +
 * `VideoPickerOverlay`），图片这里照同一套做。
 *
 * ## 扫哪些目录
 *
 * 车机没有相册索引，只能按目录扫；**深度和条数都要设上限**（车机存储里
 * 可能几千个文件，扫整个共享存储会卡）：
 *
 * | # | 目录 | 说明 |
 * |---|---|---|
 * | 1 | `Pictures` | 最常见的放图位置 |
 * | 2 | `DCIM` | 相机 / 手机同步过来的 |
 * | 3 | `Download` | 浏览器/局域网传输常见落点 |
 * | 4 | `Download/CarDesk` | 我们自己的局域网传输目录 |
 *
 * ## 纯逻辑
 *
 * [scanFiles] / [sort] / [isImagePath] / [formatSize] 都不碰 Android API，
 * 单测直接喂临时目录。
 */
object ImageLibrary {

    /**
     * 一条图片。
     *
     * 与 [VideoLibrary.Item] 一样**只留路径**（`Uri.fromFile` 在纯 JVM 单测里是 null）。
     */
    data class Item(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    /** 我们自己的目录名（在 `Download` 下面，和视频共用）。 */
    const val SHARE_DIR_NAME = VideoLibrary.SHARE_DIR_NAME

    /**
     * 图片扩展名白名单（小写，含点）。
     *
     * 刻意不含 `.gif`：静态壁纸只取一帧，动图放进来会让人误以为「会动」。
     * `.heic/.heif` 保留 —— 车机 API 28 解不了时由导入流程报「解不出来」，
     * 比在选择器里直接看不见更可解释。
     */
    val EXTENSIONS: List<String> = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif",
    )

    /** 扫描深度上限（这几类目录基本都是平的，留 2 层给子目录）。 */
    const val MAX_DEPTH = 2

    /** 最多列多少条。 */
    const val LIMIT = 200

    // ------------------------------------------------------------------ 纯逻辑

    /** 取扩展名（小写，含点；没有则空串）。 */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        return name.substring(dot).lowercase()
    }

    /** 这个路径是不是图片（按扩展名）。 */
    fun isImagePath(path: String): Boolean = EXTENSIONS.contains(extensionOf(path))

    /**
     * 扫目录找图片（**纯逻辑** ⇒ JVM 单测直接跑）。
     *
     * @param roots 扫描根目录（不存在的自动跳过）。
     * @param maxDepth 目录深度上限。
     * @param limit 最多返回多少条。
     */
    fun scanFiles(
        roots: List<File>,
        maxDepth: Int = MAX_DEPTH,
        limit: Int = LIMIT,
    ): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (out.size >= limit) break
            walk(root, 0, maxDepth, limit, out, seen)
        }
        return sort(out)
    }

    private fun walk(
        dir: File,
        depth: Int,
        maxDepth: Int,
        limit: Int,
        out: ArrayList<Item>,
        seen: HashSet<String>,
    ) {
        if (out.size >= limit) return
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= limit) return
            val name = child.name
            if (child.isDirectory) {
                // 跳过隐藏目录（`.thumbnails` 之类）
                if (name.startsWith(".")) continue
                walk(child, depth + 1, maxDepth, limit, out, seen)
            } else {
                if (!child.isFile) continue
                if (!isImagePath(name)) continue
                val path = child.absolutePath
                if (!seen.add(path)) continue
                out.add(
                    Item(
                        path = path,
                        name = name,
                        sizeBytes = child.length(),
                        modifiedAt = child.lastModified(),
                    ),
                )
            }
        }
    }

    /** 最新的排前面；同时间按名字排（结果稳定）。 */
    fun sort(items: List<Item>): List<Item> = items.sortedWith(
        compareByDescending<Item> { it.modifiedAt }.thenBy { it.name },
    )

    /** 文件大小文案（`2.4 MB`）。 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ Android

    /** 我们自己的目录：`Download/CarDesk`（不存在就建）。 */
    fun shareDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, SHARE_DIR_NAME)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    /**
     * 扫描候选目录（后台线程调用）。
     *
     * 目录不存在 / 没权限时 `listFiles()` 返回 null ⇒ 自动跳过，不抛。
     */
    fun scanLibrary(context: Context): List<Item> {
        val external = Environment.getExternalStorageDirectory()
        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            shareDir(),
        ).filter { it != null && it.exists() }
        // 保底：外部存储根下的 Pictures/DCIM 由上面覆盖；这里只兜底一次 null
        val items = scanFiles(roots.ifEmpty { listOf(external) })
        Logx.i(
            LogTags.SETTING,
            "图片库：扫描完成",
            "目录数" to roots.size,
            "图片数" to items.size,
        )
        return items
    }
}
