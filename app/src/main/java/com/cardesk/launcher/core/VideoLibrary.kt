package com.cardesk.launcher.core

import android.content.Context
import android.os.Environment
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File

/**
 * 第219轮：**应用内视频库**（不依赖系统选择器）。
 *
 * ## 为什么要有这个
 *
 * 第218轮在实车（领克 AAOS 820A）诊断包里看到的是：
 *
 * ```
 * 12:32:25.532  CarDesk/App    : onPause
 * 12:32:25.533  CarDesk/Setting : 视频壁纸：收到选视频结果 resultCode=0 uri=null
 * ```
 *
 * ⇒ 系统选择器**确实被拉起过**（没抛异常），但它**秒退**且返回的 Intent 里
 * `data` 为空 ⇒ 旧代码只认 `data?.data` ⇒ uri 永远是 null ⇒ 导入静默失败。
 * 加上车机系统里**没有 `documentsui`**（诊断包 uid=1000 包清单里查不到），
 * 说明「靠系统选择器」这条路在车机上本身就不稳。
 *
 * ## 换条路：我们自己列
 *
 * 车机上 `com.ecarx.cardesk` 跑在 **uid 1000（系统应用）**（诊断包可证），
 * 局域网传输功能也早就在直接读写车机的 Download 目录
 * ⇒ 我们**可以自己扫共享存储**，列出视频文件让用户点。
 *
 * ## ⚠️ 只扫**我们自己那一个目录**
 *
 * 扫的是 `Download/CarDesk` —— 就是**局域网传输**的上传落点。
 *
 * 不扫整个共享存储，原因是**卡顿**：
 * 车机共享存储里可能有几千个文件、上百个目录，
 * 递归扫一遍要几秒到几十秒，界面会明显发顿。
 *
 * ⇒ 视频怎么进这个目录？**局域网传输**（设置 → 局域网传输 → 手机扫码上传）
 * —— 这本来就是这个 App 自己的文件通道，闭环在 App 内。
 *
 * ## 为什么核心扫描逻辑是纯函数
 *
 * [scanFiles] 只碰 `java.io.File` ⇒ 可以在 JVM 单测里用临时目录跑，
 * 不需要设备、不需要权限、不依赖 Android ⇒ 断言是实的。
 */
object VideoLibrary {

    /**
     * 一条视频。
     *
     * ⚠️ 这里**不放 uri** —— 早期版本放过 `Uri.fromFile()` 的结果，
     * 结果扫描逻辑就不再是纯 Kotlin 了（纯 JVM 单测里 `Uri.fromFile` 返回 null ⇒ NPE）。
     * 导入只需要**路径**，所以只留路径。
     *
     * @param path 绝对路径（导入时直接按这个路径读字节）。
     */
    data class Item(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
    )

    /**
     * 我们自己的目录名（在 `Download` 下面）。
     *
     * ⚠️ 必须和 `ui/LanTransferServer` 的 `SHARE_DIR_NAME` 一致
     * —— 有单测钉死这条（`VideoLibraryShareDirTest`）。
     */
    const val SHARE_DIR_NAME = "CarDesk"

    /** 视频扩展名白名单（小写，含点）。 */
    val EXTENSIONS: List<String> = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".3gp", ".m4v", ".webm", ".ts", ".flv", ".wmv", ".m2ts",
    )

    /** 扫描的目录深度上限（我们的目录基本是平的，留 2 层给子目录）。 */
    const val MAX_DEPTH = 2

    /** 最多列多少条。 */
    const val LIMIT = 100

    // ------------------------------------------------------------------ 纯逻辑

    /** 取扩展名（小写，含点；没有则空串）。 */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        return name.substring(dot).lowercase()
    }

    /** 这个路径是不是视频（按扩展名）。 */
    fun isVideoPath(path: String): Boolean = EXTENSIONS.contains(extensionOf(path))

    /**
     * 扫目录找视频（**纯逻辑** ⇒ JVM 单测直接跑）。
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
                if (name.startsWith(".")) continue
                walk(child, depth + 1, maxDepth, limit, out, seen)
            } else {
                if (!child.isFile) continue
                if (!isVideoPath(name)) continue
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

    /** 文件大小文案（`58.1 MB`）。 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ Android

    /**
     * 我们自己的目录：`Download/CarDesk`。
     *
     * 不存在就建一个（和局域网传输一致）⇒ 第一次进选择器不会「目录都没有」。
     */
    fun libraryDir(context: Context): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(base, SHARE_DIR_NAME)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    /**
     * 扫我们自己的目录（**只扫这一个**，避免卡顿）。
     *
     * 后台线程调用。
     */
    fun scanLibrary(context: Context): List<Item> {
        val dir = libraryDir(context)
        val items = scanFiles(listOf(dir))
        Logx.i(
            LogTags.SETTING,
            "视频库：扫描完成",
            "目录" to dir.absolutePath,
            "视频数" to items.size,
        )
        return items
    }
}
