package com.cardesk.launcher.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * 把自己拿到的**封面图片**暴露成仪表能读的 `content://` Uri（第134轮）。
 *
 * ## 为什么需要它
 *
 * 原车的仪表要封面时，问的是 `IMediaInfo#getArtwork()`，返回 **`android.net.Uri`**。
 * 我们能拿到两种「图」：
 *
 * 1. **会话 metadata 里给的 URI 字符串**（`ALBUM_ART_URI` 之类）——
 *    原样透传即可（见 [BridgeArtwork]）；
 * 2. **我们自己拿到的 Bitmap** —— 会话只给位图时（`METADATA_KEY_ALBUM_ART`），
 *    或者**根本没有会话**时（QQ 音乐车机版不发布会话、广播里也没有图，
 *    只能靠我们自己的 [CoverLoader] 去搜）。
 *
 * 第 2 种是 **Bitmap**，不是 Uri —— 必须变成仪表读得到的 Uri 才行。
 *
 * ## 为什么用 ContentProvider 而不是 `file://`
 *
 * 原车自己也会用 `Uri.fromFile(file)`（当 `ART_URI` 指本地文件时），
 * 但那是**原车音源自己的文件**。我们的文件在 `/data/user/0/com.ecarx.cardesk/` 里，
 * 别的进程能不能读取决于 uid —— 车机上的仪表进程未必跟我们同 uid。
 *
 * `content://` 由**我们的进程**替别人读，权限只取决于 provider 的导出与权限声明，
 * 与对方 uid 无关 —— 这是唯一能保证「仪表一定读得到」的做法。
 *
 * 原车桌面自己加载这个 Uri 用的是 **Glide**（`analysis/inner_jadx` 里
 * `MusicManager.loadCoverIntoViews(Uri, String)`），Glide 对 `content://` 原生支持。
 *
 * ## 声明
 *
 * `AndroidManifest.xml` 里：`android:exported="true"`，**不设任何读写权限** ——
 * 仪表是系统应用，必须读得到；这里暴露的只是「一张封面图」，
 * 与其它私有数据无关（路径只认我们生成的 key，其它一律 404）。
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        /**
         * provider 的 authority **后缀**（完整 authority = `<applicationId>.artwork`）。
         *
         ## 为什么不能写死整个 authority
         *
         * 调试包的 applicationId 与系统包不同（`com.cardesk.launcher` vs `com.ecarx.cardesk`），
         * 写死 `com.ecarx.cardesk.artwork` 的话两个包会**抢同一个 authority** ——
         * 装调试包时系统会把 authority 判给先装的那个，provider 就找错了地方，
         * 表现是「provider 存在但永远读不到文件」。
         *
         * 所以：清单里写 `${applicationId}.artwork`，代码里用
         * [ArtworkStore.authority]（`context.packageName + ".artwork"`）现算。
         */
        const val AUTHORITY_SUFFIX = ".artwork"

        /** Uri 形如 `content://<applicationId>.artwork/cover/<key>`。 */
        private const val PATH_PREFIX = "/cover/"

        private const val MIME_JPEG = "image/jpeg"

        /** 目录名（放在 files 下，别的 app 读不到也无所谓 —— 走的是 provider）。 */
        private const val DIR_NAME = "bridge-artwork"
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_JPEG

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("只读: $uri")
        val ctx = context ?: throw FileNotFoundException("没有上下文")
        val file = fileFor(ctx, keyOf(uri)) ?: throw FileNotFoundException("路径不对: $uri")
        if (!file.exists()) throw FileNotFoundException("封面还没写出来: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        val file = fileFor(ctx, keyOf(uri)) ?: return null
        if (!file.exists()) return null
        return runCatching {
            AssetFileDescriptor(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                file.length(),
            )
        }.getOrNull()
    }

    /** 文件还没写出来时的标准答复（调用方按「这张图暂时没有」处理）。 */
    private fun missing(uri: Uri): FileNotFoundException = FileNotFoundException("封面不存在: $uri")

    /** 有些图片加载器会先 `query` 一下尺寸；给它一个带 SIZE 的游标，省得对方报错。 */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val file = fileFor(ctx, keyOf(uri)) ?: return null
        if (!file.exists()) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }.toTypedArray()
        val cursor = MatrixCursor(cols, 1)
        cursor.addRow(values)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** 从 Uri 里取出 key（形如 `/cover/<key>`）。 */
    private fun keyOf(uri: Uri): String {
        val path = uri.path.orEmpty()
        if (!path.startsWith(PATH_PREFIX)) return ""
        return path.removePrefix(PATH_PREFIX).trim('/')
    }

    /** key → 文件（目录在 app 的 files 下）。 */
    private fun fileFor(ctx: Context, key: String): File? {
        if (key.isEmpty()) return null
        return File(File(ctx.filesDir, DIR_NAME), "$key.jpg")
    }
}

/**
 * 封面 Bitmap ↔ Uri 的**存取**（Android 侧）。
 *
 * 写入是**同步**的（一张几百 KB 的 JPEG，几毫秒），调用点在后台线程；
 * 读是跨进程的（仪表进程）。
 */
object ArtworkStore {

    /** 写入锁：同一首歌可能被两个线程同时写（会话刷新 + 广播刷新）。 */
    private val lock = Any()

    /**
     * 把封面 Bitmap 存下来，返回给仪表用的 Uri 字符串。
     *
     * @param context 任意 Context（内部取 applicationContext）
     * @param key 歌的标识（[BridgeArtwork.songKey]）
     * @param bitmap 封面；空 → 返回空串
     * @return `content://com.ecarx.cardesk.artwork/cover/<key>`；失败返回空串
     */
    fun put(context: Context?, key: String, bitmap: Bitmap?): String {
        val ctx = context?.applicationContext ?: context ?: return ""
        if (bitmap == null || bitmap.isRecycled) return ""
        val safe = sanitize(key)
        if (safe.isEmpty()) return ""
        return synchronized(lock) {
            runCatching {
                val dir = File(ctx.filesDir, "bridge-artwork")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$safe.jpg")
                // 同一首歌不重复写（封面多半一样，白写一遍只是浪费 IO）
                if (file.exists() && file.length() > 0) {
                    return@synchronized uriFor(ctx, safe)
                }
                val bytes = compressJpeg(bitmap) ?: return@synchronized ""
                if (bytes.isEmpty()) return@synchronized ""
                FileOutputStream(file).use { it.write(bytes) }
                uriFor(ctx, safe)
            }.getOrDefault("")
        }
    }

    /**
     * 位图 → JPEG 字节。
     *
     * **硬件位图要先拷成软件位图** —— `Bitmap.compress` 对 `Config.HARDWARE`
     * 直接抛 `IllegalStateException`（"unable to compress hardware bitmaps"）。
     * 而会话给的封面（`MediaMetadata.getBitmap()`）在 API 26+ **就是硬件位图**，
     * 不处理的话这一路会静默失败（`runCatching` 吞掉 → 返回空串 → 仪表还是没图）。
     */
    private fun compressJpeg(bitmap: Bitmap): ByteArray? = runCatching {
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return null
        val out = ByteArrayOutputStream()
        if (!soft.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
        out.toByteArray()
    }.getOrNull()

    /**
     * provider 的完整 authority（**跟 applicationId 走**）。
     *
     * 调试包与系统包的 applicationId 不同，写死会让两个包抢同一个 authority。
     */
    fun authority(context: Context): String =
        "${context.packageName}${ArtworkProvider.AUTHORITY_SUFFIX}"

    /** 拼出 Uri 字符串（不碰文件，只做拼装 —— 便于单测）。 */
    fun uriFor(context: Context, safeKey: String): String =
        "content://${authority(context)}/cover/$safeKey"

    /**
     * 把歌的 key 变成能当**文件名**的字符串。
     *
     * 歌名/歌手里什么字符都有（`/`、`:`、空格、emoji…），
     * 直接当文件名会出问题、甚至**穿越目录**，所以：
     * - 保留字母数字（含中文）、`.`、`_`、`-`；
     * - 其余（空格、`|`、`/`、`:`…）一律换成 `_`；
     * - `..` **必须压掉**（能穿越目录）；
     * - 超过 64 字符就截断并补上 hashCode（避免长歌名把路径撑爆，也降低撞名概率）。
     */
    fun sanitize(key: String): String {
        val raw = key.trim()
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val ok = ch == '.' || ch == '_' || ch == '-' ||
                (ch.isLetterOrDigit() && !ch.isWhitespace())
            sb.append(if (ok) ch else '_')
        }
        var s = sb.toString()
        // ".." 能穿越目录 —— 一律压掉（压完可能又冒出新的 ".."，所以用 while）
        while (s.contains("..")) s = s.replace("..", "_")
        s = s.trim('_')
        if (s.isEmpty()) return ""
        return if (s.length <= 64) s else s.substring(0, 48) + "_" + raw.hashCode().toString(16)
    }
}
