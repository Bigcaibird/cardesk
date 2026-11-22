package com.cardesk.launcher.core

import android.content.Context
import android.net.Uri
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * **视频壁纸**（第207轮）。
 *
 * ## 与「自定义壁纸」的关系
 *
 * [CustomWallpaper] 导入的是**一张照片**（解码 → 裁到 1920×1080 → 存 JPEG）；
 * 视频壁纸导入的是**一段视频**（原文件直接存下来，播放时再解流）。
 *
 * ## 文件
 *
 * `filesDir/video_wallpaper/wallpaper.mp4`
 *
 * ## 为什么原样存盘、不转码
 *
 * 转码要引 ffmpeg（车机上没有），而且 1080p 视频转一次要几十秒。
 * 原文件直存 → 播放时交给系统的 `MediaPlayer` 解，
 * 车机的编解码器本来就是硬件加速的。
 *
 * ## 体积上限
 *
 * 单个视频最大 [MAX_BYTES]（64 MB）—— 再大就没必要了，
 * 而且车机 `/data` 分区也放不下太多。
 */
object VideoWallpaper {

    /** 视频的最大字节数（64 MB）。 */
    const val MAX_BYTES: Long = 64L * 1024 * 1024

    private const val DIR = "video_wallpaper"
    private const val FILE = "wallpaper.mp4"

    /**
     * 视频文件所在目录。
     *
     * ## 为什么放在「外部应用目录」而不是 `filesDir`
     *
     * 真正解码视频的是**媒体服务**（`mediaserver` / `media.extractor`），
     * 它要**自己按路径去打开这个文件** ——
     * 哪怕我们把 fd 递给 `MediaPlayer`，AOSP 也会把 fd 换算回真实路径再交给媒体服务
     * （实测：传 fd 之后 SELinux 拒绝日志里出现的仍然是**路径**）。
     *
     * 而平台签名包（`com.ecarx.cardesk`，uid 1000 / `system_app` 域）的私有目录
     * 被标成 `system_app_data_file`，AOSP 的 SELinux 策略**不允许媒体服务读它**：
     * ```
     * avc: denied { read } ... scontext=u:r:mediaserver:s0
     *   path="/data/data/com.ecarx.cardesk/files/video_wallpaper/wallpaper.mp4"
     *   tcontext=u:object_r:system_app_data_file:s0
     * ```
     * 表现就是 `prepareAsync()` 抛
     * `java.io.IOException: Prepare Async failed.: status=0x80000000`
     * （读不到文件 → 解封装失败 → prepare 直接失败）。
     *
     * 外部应用目录（`/sdcard/Android/data/<包名>/files/`）标签是 `sdcardfs`，
     * 媒体服务读得到 —— 所以视频壁纸放这儿。
     * 外部存储不可用时（比如车机没挂 SD 卡）回退到 `filesDir`。
     */
    fun dir(context: Context): File {
        val ext = context.getExternalFilesDir(null)
        val base = if (ext != null) File(ext, DIR) else File(context.filesDir, DIR)
        return base
    }

    /** 落盘的视频文件。 */
    fun file(context: Context): File = File(dir(context), FILE)

    /**
     * 旧版本把视频存在 `filesDir`（媒体服务读不到），这里做一次**迁移**。
     *
     * 迁移过就不用再管旧文件；失败也不影响新路径。
     */
    fun migrateLegacyFile(context: Context) {
        val legacy = File(File(context.filesDir, DIR), FILE)
        if (!legacy.isFile || legacy.length() <= 0L) return
        val target = file(context)
        if (target.isFile && target.length() > 0L) return
        runCatching {
            target.parentFile?.mkdirs()
            legacy.copyTo(target, overwrite = true)
            legacy.delete()
            Logx.i(LogTags.SETTING, "视频壁纸：旧目录文件已迁移", "to" to target.absolutePath)
        }
    }

    /** 是否已经导入过视频壁纸。 */
    fun has(context: Context): Boolean {
        val f = file(context)
        return f.isFile && f.length() > 0
    }

    /** 已导入视频的字节数（没有就 0）。 */
    fun sizeBytes(context: Context): Long {
        val f = file(context)
        return if (f.isFile) f.length() else 0L
    }

    /**
     * 从相册 Uri 导入视频（后台线程调用）。
     *
     * 失败都返回 false 并写日志，调用方只需要提示用户。
     */
    fun importFromUri(context: Context, uri: Uri): Boolean {
        return try {
            Logx.i(LogTags.SETTING, "视频壁纸：开始导入", "uri" to uri.toString())
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri)
            Logx.i(LogTags.SETTING, "视频壁纸：已打开输入流", "null" to (input == null))
            if (input == null) {
                Logx.w(LogTags.SETTING, "视频壁纸导入失败：打不开视频")
                return false
            }
            copyToWallpaper(context, input)
        } catch (e: Exception) {
            runCatching { file(context).delete() }
            Logx.w(LogTags.SETTING, "视频壁纸导入失败", e)
            false
        }
    }

    /**
     * 从**文件**导入视频（第219轮：应用内视频列表给的是路径）。
     *
     * 车机上系统选择器不可靠（见 [VideoLibrary] 的说明），
     * 我们自己列出存储里的视频文件，用户点哪个就导哪个。
     *
     * @param file 视频文件（车机上是共享存储里的绝对路径）。
     */
    fun importFromFile(context: Context, file: File): Boolean {
        return try {
            Logx.i(
                LogTags.SETTING,
                "视频壁纸：开始导入（应用内视频库）",
                "path" to file.absolutePath,
                "size" to file.length(),
            )
            if (!file.isFile || file.length() <= 0L) {
                Logx.w(LogTags.SETTING, "视频壁纸导入失败：文件不存在或为空")
                return false
            }
            val input = FileInputStream(file)
            copyToWallpaper(context, input)
        } catch (e: Exception) {
            runCatching { file(context).delete() }
            Logx.w(LogTags.SETTING, "视频壁纸导入失败", e)
            false
        }
    }

    /**
     * 把输入流抄进壁纸视频文件（**两条导入路径共用**）。
     *
     * 先写临时文件，成功了再改名 —— 避免中途失败留下一个半截文件
     * （下次启动会拿它去播，MediaPlayer 直接报错）。
     */
    private fun copyToWallpaper(context: Context, input: InputStream): Boolean {
        return try {
            val out = file(context)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, "$FILE.tmp")
            var total = 0L
            input.use { src ->
                FileOutputStream(tmp).use { sink ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            throw IllegalArgumentException("视频超过 ${MAX_BYTES / 1024 / 1024}MB")
                        }
                        sink.write(buf, 0, n)
                    }
                }
            }
            if (total <= 0L) {
                tmp.delete()
                Logx.w(LogTags.SETTING, "视频壁纸导入失败：视频为空")
                return false
            }
            // ⚠️ **不先删旧文件**：`File.renameTo` 用的是 POSIX `rename(2)`，
            // 本身就是**原子替换**（目标存在也会被换掉，实测确认）。
            // 先删反而有个坏处：万一这次改名失败，旧壁纸已经被删了 ⇒ 用户的壁纸没了。
            // 不删的话，失败时旧壁纸还在（顶多这次导入不生效）。
            if (!tmp.renameTo(out)) {
                tmp.delete()
                Logx.w(LogTags.SETTING, "视频壁纸导入失败：落盘改名失败")
                return false
            }
            Logx.i(
                LogTags.SETTING,
                "视频壁纸已导入",
                "bytes" to total,
                "path" to out.absolutePath,
            )
            true
        } catch (e: Exception) {
            runCatching { file(context).delete() }
            Logx.w(LogTags.SETTING, "视频壁纸导入失败", e)
            false
        }
    }

    /** 删除视频壁纸（文件）。 */
    fun delete(context: Context) {
        runCatching { file(context).delete() }
        runCatching { File(file(context).parentFile, "$FILE.tmp").delete() }
        // 旧目录（媒体服务读不到的那份）也一起清掉。
        val legacy = File(File(context.filesDir, DIR), FILE)
        runCatching { legacy.delete() }
        runCatching { File(legacy.parentFile, "$FILE.tmp").delete() }
    }

    // ------------------------------------------------------------------ 纯函数（可单测）

    /**
     * 这个 Uri 看起来是不是**视频**。
     *
     * 系统选图器（`ACTION_OPEN_DOCUMENT` + 图片类型）只给图片，
     * 视频要用视频类型单独选一次。这里给一个**兜底判断**：
     * MIME 说不是视频、但扩展名像视频（.mp4/.mkv/.webm/.3gp/.mov）
     * 时也放行 —— 有些车机相册返回的 MIME 是 `application/octet-stream`。
     *
     * @param mimeType 系统的 MIME（可空）
     * @param displayName 文件名（可空）
     * @return true = 像视频
     */
    fun looksLikeVideo(mimeType: String?, displayName: String?): Boolean {
        val mime = mimeType?.lowercase()?.trim().orEmpty()
        if (mime.startsWith("video/")) return true
        val name = displayName?.lowercase()?.trim().orEmpty()
        val ext = name.substringAfterLast('.', "")
        return ext in VIDEO_EXTENSIONS
    }

    /** 认得的视频扩展名。 */
    val VIDEO_EXTENSIONS: Set<String> = setOf("mp4", "mkv", "webm", "3gp", "mov", "m4v", "avi", "ts")

    // ------------------------------------------------------------------ 合成后端（第214轮）

    /**
     * 合成后端：**`SurfaceView`**（默认，第214轮起）。
     *
     * ## 为什么从 `TextureView` 换成 `SurfaceView`
     *
     * 第213轮在模拟器上实测出：**`TextureView` 路径解码是好的，画面就是不上屏**。
     *
     * | 证据 | 结论 |
     * |---|---|
     * | `dumpsys media.player`：`numFramesTotal` 稳定涨 **53.4 帧/s** | 解码正常，60fps 满帧在跑 |
     * | logcat「视频壁纸：收到第一帧」 | 帧确实送进了 `TextureView` 的 `SurfaceTexture` |
     * | 视频壁纸 vs 静态壁纸两张 1920×1080 截图全图像素对比 | **只有状态栏时钟那一小块（17×23 px）不同**，视频一个像素没上屏 |
     * | 同一个文件交给系统 Gallery3D（**`SurfaceView`** 路径） | **画面完整显示** |
     * | `dumpsys SurfaceFlinger --list` | 全设备只有 `SurfaceView - <pkg>` 层，**没有一条 TextureView 层** |
     *
     * ⇒ 这台设备上 **`TextureView` 这条合成路径不通**，而 **`SurfaceView` 通**。
     *
     * ## `SurfaceView` 怎么做到「夹在壁纸与桌面卡片之间」
     *
     * `SurfaceView` 默认 z 序是「**推模式**」：它有**自己独立的 SurfaceFlinger 层**，
     * 位置在**窗口 buffer 之下**。于是层级天然是：
     *
     * ```
     * 系统壁纸层
     *   └─ cardesk 窗口 buffer   ← 桌面卡片 / 状态栏 / Dock 画在这里
     *        └─ SurfaceView 层    ← 视频在这里（窗口 buffer 的同一块区域被“挖空”）
     * ```
     *
     * 代价是：**窗口 buffer 在视频那块区域必须是透明的**，视频才能透出来。
     * ⇒ 窗口要设成 `PixelFormat.TRANSLUCENT`，且那一块不能画任何不透明东西
     * （壁纸层让路、桌面底色透明）。
     */
    const val BACKEND_SURFACE_VIEW: Int = 0

    /**
     * 合成后端：**`TextureView`**（第208轮的老路径，保留备选）。
     *
     * 留着它有两个理由：
     * 1. 实车（领克 AAOS）上 `TextureView` 未必不通 —— 这台模拟器的结论不能直接套到实车；
     * 2. 实车复测时可以**不改代码**就切后端（改 `design_video_wallpaper_backend` 这个 pref 即可），
     *    一次实车跑就能对比两条路径。
     */
    const val BACKEND_TEXTURE_VIEW: Int = 1

    /** 归一化后端值：只认 [BACKEND_TEXTURE_VIEW]，其余一律当 [BACKEND_SURFACE_VIEW]。 */
    fun normalizeBackend(value: Int): Int =
        if (value == BACKEND_TEXTURE_VIEW) BACKEND_TEXTURE_VIEW else BACKEND_SURFACE_VIEW

    /**
     * 这个后端要不要把 Activity 窗口切成**半透明**。
     *
     * - [BACKEND_SURFACE_VIEW]：**要** —— surface 在窗口 buffer 之下，
     *   窗口那块区域必须透明，视频才能透上来；
     * - [BACKEND_TEXTURE_VIEW]：**不要** —— 帧被画进窗口 buffer 本身。
     *
     * 提成纯函数是为了能直接被单测覆盖（这条规则错一点点就是「画面全黑」）。
     */
    fun needsTranslucentWindow(backend: Int): Boolean =
        normalizeBackend(backend) == BACKEND_SURFACE_VIEW

    /**
     * 桌面窗口**当前**要不要半透明（第227轮）。
     *
     * = 视频壁纸开着 **且** 走 `SurfaceView` 后端。
     *
     * ## 为什么要单独提一个函数
     *
     * 这条判断要在**两个地方**用，而且必须**完全一致**：
     *
     * 1. **窗口创建之前**（`MainActivity.onCreate`）——
     *    决定窗口用 `TRANSLUCENT` 还是 `OPAQUE` 建；
     * 2. **承载面建起来之前**（`LauncherView.refreshPip`）——
     *    决定画中画走 `LAYER`（旧路线）还是 `MIRROR`（窗口内渲染）。
     *
     * 两处口径不一致的后果：窗口是半透明的、画中画却按不透明窗口那条路画
     * ⇒ 圆角失效 / 切页残留黑卡；反过来窗口不透明、画中画走镜像 ⇒ 画面全黑。
     *
     * 提成纯函数 → 单测直接把两条口径钉在一起。
     */
    fun needsTranslucentWindow(videoOn: Boolean, videoEnabled: Boolean, backend: Int): Boolean =
        videoOn && videoEnabled && needsTranslucentWindow(backend)

    /**
     * 第227轮：桌面窗口的**目标 format**。
     *
     * 只有一个地方能决定它（`MainActivity.applyWindowFormatBeforeCreate`，
     * 在**建窗之前**调用）—— 窗口建好之后再改 format 会重建窗口 surface，
     * 连带把 `SurfaceView` 的 surface 重建一次 ⇒ 视频壁纸**从第一帧重播**
     * （实测每次冷启动起播两次，间隔 652ms）。
     *
     * @return [android.graphics.PixelFormat.TRANSLUCENT] 或 [android.graphics.PixelFormat.OPAQUE]
     */
    fun targetWindowFormat(videoOn: Boolean, videoEnabled: Boolean, backend: Int): Int =
        if (needsTranslucentWindow(videoOn, videoEnabled, backend)) {
            android.graphics.PixelFormat.TRANSLUCENT
        } else {
            android.graphics.PixelFormat.OPAQUE
        }
}
