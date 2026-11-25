package com.cardesk.launcher.core.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 零依赖 APK `content://` 提供者（供 API 24–28 拉起系统安装器时替代 `file://`）。
 *
 * `targetSdk = 29` 下直接把 `Uri.fromFile()` 放进 `ACTION_VIEW` Intent 会触发
 * `FileUriExposedException`；本 Provider 非导出 + `grantUriPermissions="true"`，
 * 仅允许通过 `FLAG_GRANT_READ_URI_PERMISSION` 临时授权系统安装器读取 `.apk` 文件。
 */
class ApkFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (resolveFile(uri) != null) MIME_APK else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only provider")
        val file = resolveFile(uri) ?: throw FileNotFoundException("APK not found: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolveFile(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val values = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }.toTypedArray()
        return MatrixCursor(cols, 1).apply { addRow(values) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolveFile(uri: Uri): File? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        val scope = segments[0]
        val name = segments[1]
        if (name.isBlank() || !name.endsWith(".apk", ignoreCase = true)) return null
        val baseDir = when (scope) {
            SCOPE_PUBLIC -> publicDir()
            SCOPE_CACHE -> ctx.cacheDir
            else -> return null
        }
        val candidate = File(baseDir, name)
        val canonicalBase = runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
        val canonicalFile = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (canonicalFile.parentFile != canonicalBase) return null
        if (!canonicalFile.isFile) return null
        return canonicalFile
    }

    companion object {
        private const val MIME_APK = "application/vnd.android.package-archive"
        private const val AUTHORITY_SUFFIX = ".apkprovider"
        private const val SCOPE_PUBLIC = "public"
        private const val SCOPE_CACHE = "cache"

        @Suppress("DEPRECATION")
        private fun publicDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CarDesk",
        )

        /** 为 [apk] 生成可跨进程临时授权的 `content://` Uri。 */
        fun uriFor(context: Context, apk: File): Uri {
            val authority = "${context.packageName}$AUTHORITY_SUFFIX"
            val canonicalApk = runCatching { apk.canonicalFile }.getOrNull() ?: apk
            val canonicalPublic = runCatching { publicDir().canonicalFile }.getOrNull()
            val canonicalCache = runCatching { context.cacheDir.canonicalFile }.getOrNull()
            val scope = when (canonicalApk.parentFile) {
                canonicalPublic -> SCOPE_PUBLIC
                canonicalCache -> SCOPE_CACHE
                else -> null
            }
            if (scope != null && apk.name.endsWith(".apk", ignoreCase = true)) {
                return Uri.Builder()
                    .scheme("content")
                    .authority(authority)
                    .appendPath(scope)
                    .appendPath(apk.name)
                    .build()
            }
            return Uri.fromFile(apk)
        }
    }
}
