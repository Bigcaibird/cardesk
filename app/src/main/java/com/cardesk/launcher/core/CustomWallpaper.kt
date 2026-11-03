package com.cardesk.launcher.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 自定义壁纸（第 105 轮）。
 *
 * ## 口径（与「资源占用性能对比」会话里的结论一致）
 *
 * 导入时**只解一次**，并且在后台线程里：
 * 1. `inJustDecodeBounds` 读尺寸，按 2 的幂算 `inSampleSize`，避免把 4K 原图整张解进内存；
 * 2. 解出来立刻**等比缩放 + 居中裁切**到 [TARGET_W]×[TARGET_H]（1920×1080）；
 * 3. 只把这张**预缩放副本**存成 JPEG 落盘（约 0.2–0.8 MB），原图不留在 App 里；
 * 4. 冷启动/切换时只解这张小图（约 80–150 ms），并且**只保留一张**全屏位图
 *    （[cachedBitmap] 单槽缓存），不进 [com.cardesk.launcher.ui.widget.WallpaperRenderer] 的 LRU。
 *
 * 夜间模式不额外存第二张图：绘制时盖一层 scrim（见 BackgroundView）。
 *
 * ## 文件
 *
 * `filesDir/custom_wallpaper/wallpaper.jpg`
 */
object CustomWallpaper {

    const val TARGET_W = 1920
    const val TARGET_H = 1080

    private const val DIR = "custom_wallpaper"
    private const val FILE = "wallpaper.jpg"
    private const val QUALITY = 88

    /** 单槽缓存：当前自定义壁纸的全屏位图（只有一张）。 */
    @Volatile
    private var cached: Bitmap? = null

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)

    fun has(context: Context): Boolean {
        val f = file(context)
        return f.isFile && f.length() > 0
    }

    /** 已解码的当前自定义壁纸（没有就返回 null）。 */
    fun cachedBitmap(): Bitmap? = cached

    /** 丢掉单槽缓存（删除 / 重新导入时调用）。 */
    fun invalidate() {
        val old = cached
        cached = null
        if (old != null && !old.isRecycled) old.recycle()
    }

    /**
     * 解码落盘的预缩放副本（后台线程调用，结果进单槽缓存）。
     *
     * @return 解码失败（文件损坏/被删）时返回 null。
     */
    fun loadScaled(context: Context): Bitmap? {
        cached?.let { if (!it.isRecycled) return it }
        val f = file(context)
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, TARGET_W, TARGET_H)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            val fitted = fitTo(decoded, TARGET_W, TARGET_H)
            if (fitted !== decoded) decoded.recycle()
            cached = fitted
            fitted
        } catch (e: Exception) {
            Logx.w(LogTags.SETTING, "自定义壁纸解码失败", e)
            null
        }
    }

    /**
     * 从相册 Uri 导入（后台线程调用）。
     *
     * 失败都返回 false 并写日志，调用方只需要提示用户。
     */
    fun importFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            // content:// 的流可能只能读一次（相册 provider 常见）：整个读进内存再解两遍。
            val bytes = resolver.openInputStream(uri)?.use { readAllBounded(it) }
                ?: run {
                    Logx.w(LogTags.SETTING, "自定义壁纸导入失败：打不开图片")
                    return false
                }
            if (bytes.isEmpty()) {
                Logx.w(LogTags.SETTING, "自定义壁纸导入失败：图片为空")
                return false
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logx.w(LogTags.SETTING, "自定义壁纸导入失败：读不到图片尺寸")
                return false
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, TARGET_W, TARGET_H)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: run {
                    Logx.w(LogTags.SETTING, "自定义壁纸导入失败：解码为空")
                    return false
                }
            val orientation = parseJpegOrientation(bytes)
            val rotated = if (orientation != 0) rotate(decoded, orientation) else decoded
            val fitted = fitTo(rotated, TARGET_W, TARGET_H)
            if (rotated !== decoded) decoded.recycle()
            if (fitted !== rotated) rotated.recycle()
            val out = file(context)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fitted.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            fitted.recycle()
            invalidate()
            Logx.i(
                LogTags.SETTING,
                "自定义壁纸已导入",
                "srcW" to bounds.outWidth,
                "srcH" to bounds.outHeight,
                "sample" to opts.inSampleSize,
                "bytes" to out.length(),
            )
            true
        } catch (e: Exception) {
            Logx.w(LogTags.SETTING, "自定义壁纸导入失败", e)
            false
        }
    }

    /** 删除自定义壁纸（文件 + 缓存）。 */
    fun reset(context: Context) {
        invalidate()
        runCatching { file(context).delete() }
    }

    // ------------------------------------------------------------------ 纯函数（可单测）

    /**
     * 按 2 的幂算 inSampleSize：解码后的宽高都不小于目标（避免先解 4K 再缩）。
     */
    fun computeSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }

    /** 等比缩放 + 居中裁切到目标尺寸（宽高比不同就以填满为准）。 */
    fun fitTo(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src
        val scale = max(targetW / src.width.toFloat(), targetH / src.height.toFloat())
        val nw = max(1, (src.width * scale).roundToInt())
        val nh = max(1, (src.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val x = ((nw - targetW) / 2).coerceAtLeast(0)
        val y = ((nh - targetH) / 2).coerceAtLeast(0)
        val out = Bitmap.createBitmap(scaled, x, y, targetW, targetH)
        if (out !== scaled) scaled.recycle()
        return out
    }

    // ------------------------------------------------------------------ EXIF 方向

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** 最多读 64 MB（一张照片足够；防止坏文件把内存吃光）。 */
    private fun readAllBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > 64 * 1024 * 1024) throw IllegalArgumentException("图片超过 64MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 读 JPEG 的 EXIF Orientation，返回需要顺时针旋转的角度（0/90/180/270）。
     *
     * 只处理最常见的 1/3/6/8；PNG 等非 JPEG 直接返回 0。
     */
    private fun parseJpegOrientation(bytes: ByteArray): Int {
        return try {
            var i = 2 // 跳过 SOI
            while (i + 4 <= bytes.size) {
                if ((bytes[i].toInt() and 0xFF) != 0xFF) {
                    i++
                    continue
                }
                val marker = bytes[i + 1].toInt() and 0xFF
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    i += 2
                    continue
                }
                if (marker == 0xDA) return 0 // SOS 之后没有元数据
                val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (len < 2) return 0
                if (marker == 0xE1 && i + 10 <= bytes.size) {
                    val tag = String(bytes, i + 4, 6, Charsets.US_ASCII)
                    if (tag == "Exif\u0000\u0000") return parseTiffOrientation(bytes, i + 10)
                }
                i += 2 + len
            }
            0
        } catch (e: Exception) {
            0
        }
    }

    private fun parseTiffOrientation(bytes: ByteArray, tiffStart: Int): Int {
        if (tiffStart + 8 > bytes.size) return 0
        val little = bytes[tiffStart] == 'I'.code.toByte() && bytes[tiffStart + 1] == 'I'.code.toByte()
        fun u16(off: Int): Int {
            val a = bytes[off].toInt() and 0xFF
            val b = bytes[off + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(off: Int): Int {
            var v = 0
            for (k in 0 until 4) {
                val b = bytes[off + if (little) 3 - k else k].toInt() and 0xFF
                v = (v shl 8) or b
            }
            return v
        }
        if (u16(tiffStart + 2) != 42) return 0
        val ifd0 = tiffStart + u32(tiffStart + 4)
        if (ifd0 + 2 > bytes.size) return 0
        val count = u16(ifd0)
        var p = ifd0 + 2
        repeat(count) {
            if (p + 12 > bytes.size) return 0
            val tag = u16(p)
            if (tag == 0x0112) {
                val value = u16(p + 8)
                return when (value) {
                    3 -> 180
                    6 -> 90
                    8 -> 270
                    else -> 0
                }
            }
            p += 12
        }
        return 0
    }

    /** 供「资源占用」口径的自检：当前缓存位图的字节数。 */
    fun cachedBytes(): Long = cached?.let { it.byteCount.toLong() } ?: 0L
}
