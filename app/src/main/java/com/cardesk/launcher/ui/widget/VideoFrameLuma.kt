package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File

/**
 * 从**视频文件**里取一帧的亮度图（第231轮）。
 *
 * ## 为什么不用 `MediaMetadataRetriever`
 *
 * 第231轮第一版用的是 `MediaMetadataRetriever.getFrameAtTime` —— 逻辑最简单，
 * 但实测（cardesk28 / API 28 / AOSP 软件解码器）**解出来的帧是全黑的**
 * （`getScaledFrameAtTime` 和 `getFrameAtTime` 都一样）：
 *
 * ```
 * CarDesk/Scene: 视频壁纸解码诊断 w=256 h=144 px=-16777216   ← 0xFF000000
 * CarDesk/Scene: [EVT] 视频壁纸亮度采样 src=file pos=0 top=0 topBright=0 …
 * ```
 *
 * 原因是它走的是「解码器 → 内存 buffer」那条路，模拟器的软解只对
 * **带 Surface 的输出**真的出图（同一个文件用 `MediaPlayer` 播是正常的）。
 *
 * ## 这里走的路：解码到 `ImageReader` 的 Surface，读 Y 平面
 *
 * ```
 * MediaExtractor  →  MediaCodec（输出到 ImageReader 的 Surface）  →  YUV_420_888
 * ```
 *
 * - 这是**标准播放路径**（解码到 Surface），模拟器 / 真车都成立；
 * - 只要 **Y 平面**（亮度），不用转 RGB —— 本来要的就是亮度；
 * - 解一张小图：先按解码器原尺寸出帧，再把 Y 平面**等距抽样**成 256×144 灰度图。
 *
 * ## 代价
 *
 * 一次采样 = 建/拆一次 `MediaCodec` + 解 1~2 帧（720p 软解约几十毫秒），
 * 每 3 秒一次 —— 比 `PixelCopy` 重，但 `PixelCopy` 在真车上**读不到**那块缓冲
 * （见 [VideoBackdropSampler] 的注释），只能这样。
 *
 * 必须在**后台线程**上调（本类是阻塞的）。
 */
object VideoFrameLuma {

    /** 等一次解码输出的上限（毫秒）。超了就当这次没采到。 */
    private const val TIMEOUT_MS = 1_200L

    /**
     * 取 [positionMs] 附近的一帧，返回灰度的 `targetW × targetH` 位图。
     *
     * @return 位图（ARGB_8888，r=g=b=亮度）；取不到返回 null
     */
    fun frame(file: File, positionMs: Int, targetW: Int, targetH: Int): Bitmap? =
        decodeFrame(file, positionMs, targetW, targetH, color = false)

    /**
     * 取 [positionMs] 附近的一帧彩色封面略缩图（用于壁纸设置页预览）。
     *
     * 走与播放器相同的 `MediaCodec -> ImageReader Surface` 路径，
     * 规避 Android 9 / API 28 下 `MediaMetadataRetriever.getFrameAtTime` 返回全黑帧的问题。
     */
    fun colorFrame(file: File, positionMs: Int, maxW: Int, maxH: Int): Bitmap? =
        decodeFrame(file, positionMs, maxW, maxH, color = true)

    private fun decodeFrame(
        file: File,
        positionMs: Int,
        targetW: Int,
        targetH: Int,
        color: Boolean,
    ): Bitmap? {
        if (targetW <= 0 || targetH <= 0) return null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val track = firstVideoTrack(extractor) ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcW = format.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (srcW <= 0 || srcH <= 0) return null

            reader = ImageReader.newInstance(srcW, srcH, ImageFormat.YUV_420_888, 2)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, reader.surface, null, 0)
            codec.start()

            val posUs = positionMs.coerceAtLeast(0).toLong() * 1000L
            extractor.seekTo(posUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            var inputDone = false
            while (SystemClock.uptimeMillis() < deadline) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, size,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx >= 0 -> {
                        codec.releaseOutputBuffer(outIdx, true)
                        val bmp = if (color) {
                            readColorImage(reader, targetW, targetH)
                        } else {
                            readImage(reader, targetW, targetH)
                        }
                        if (bmp != null) return bmp
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> Unit
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && inputDone) break
            }
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { reader?.close() }
            runCatching { extractor?.release() }
        }
    }

    /** 找到第一条视频轨。 */
    private fun firstVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    /**
     * 把 `ImageReader` 里最新一帧的 **Y 平面**抽成小图。
     *
     * 抽稀口径：源图上等距取点（`sx = x * srcW / targetW`），
     * 这样 256×144 的小图和整帧是**同一块画面**（区域比例坐标不用改）。
     */
    private fun readImage(reader: ImageReader, targetW: Int, targetH: Int): Bitmap? {
        // Surface 出帧和 `acquireLatestImage` 之间有一小段延迟 ⇒ 轮询几次。
        var image: android.media.Image? = null
        val deadline = SystemClock.uptimeMillis() + 200L
        while (SystemClock.uptimeMillis() < deadline) {
            image = reader.acquireLatestImage()
            if (image != null) break
            SystemClock.sleep(5L)
        }
        if (image == null) return null
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val srcW = image.width
            val srcH = image.height
            if (srcW <= 0 || srcH <= 0) return null
            val w = targetW
            val h = targetH.coerceAtMost(
                (srcH.toLong() * targetW / srcW).toInt().coerceAtLeast(1),
            )
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val sy = (y.toLong() * srcH / h).toInt().coerceIn(0, srcH - 1)
                val rowBase = sy * rowStride
                for (x in 0 until w) {
                    val sx = (x.toLong() * srcW / w).toInt().coerceIn(0, srcW - 1)
                    val yv = buf.get(rowBase + sx * pixelStride).toInt() and 0xFF
                    pixels[y * w + x] = (0xFF shl 24) or (yv shl 16) or (yv shl 8) or yv
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            runCatching { image.close() }
        }
    }

    /** 把 `ImageReader` 的 `YUV_420_888` 转为等比缩放后的彩色 `ARGB_8888` 略缩图（BT.601）。 */
    private fun readColorImage(reader: ImageReader, maxW: Int, maxH: Int): Bitmap? {
        var image: android.media.Image? = null
        val deadline = SystemClock.uptimeMillis() + 200L
        while (SystemClock.uptimeMillis() < deadline) {
            image = reader.acquireLatestImage()
            if (image != null) break
            SystemClock.sleep(5L)
        }
        if (image == null) return null
        try {
            val crop = image.cropRect
            val srcW = crop.width().takeIf { it > 0 } ?: image.width
            val srcH = crop.height().takeIf { it > 0 } ?: image.height
            if (srcW <= 0 || srcH <= 0 || image.planes.size < 3) return null
            val scale = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH, 1f)
            val w = (srcW * scale).toInt().coerceAtLeast(1)
            val h = (srcH * scale).toInt().coerceAtLeast(1)

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            val uRow = uPlane.rowStride
            val uPix = uPlane.pixelStride
            val vRow = vPlane.rowStride
            val vPix = vPlane.pixelStride
            val yLimit = yBuf.limit()
            val uLimit = uBuf.limit()
            val vLimit = vBuf.limit()
            val left = crop.left.coerceAtLeast(0)
            val top = crop.top.coerceAtLeast(0)
            val maxX = image.width - 1
            val maxY = image.height - 1

            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val sy = (top + (y.toLong() * srcH / h).toInt()).coerceIn(0, maxY)
                val cy = sy shr 1
                val yBase = sy * yRow
                val uBase = cy * uRow
                val vBase = cy * vRow
                val outRow = y * w
                for (x in 0 until w) {
                    val sx = (left + (x.toLong() * srcW / w).toInt()).coerceIn(0, maxX)
                    val cx = sx shr 1
                    val yIdx = (yBase + sx * yPix).coerceIn(0, yLimit - 1)
                    val uIdx = (uBase + cx * uPix).coerceIn(0, uLimit - 1)
                    val vIdx = (vBase + cx * vPix).coerceIn(0, vLimit - 1)
                    val yv = (yBuf.get(yIdx).toInt() and 0xFF) - 16
                    val c = if (yv < 0) 0 else yv
                    val uv = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                    val vv = (vBuf.get(vIdx).toInt() and 0xFF) - 128
                    val r = ((298 * c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                    val g = ((298 * c - 100 * uv - 208 * vv + 128) shr 8).coerceIn(0, 255)
                    val b = ((298 * c + 516 * uv + 128) shr 8).coerceIn(0, 255)
                    pixels[outRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            runCatching { image.close() }
        }
    }
}
