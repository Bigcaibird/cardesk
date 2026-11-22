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
    fun frame(file: File, positionMs: Int, targetW: Int, targetH: Int): Bitmap? {
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

            // 跳到目标位置附近（关键帧）—— 亮度只需要「这一刻差不多」，
            // 不需要精确到帧，`SEEK_TO_PREVIOUS_SYNC` 比 CLOSEST 便宜。
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
                        // 这一帧已经渲染到 ImageReader 的 Surface 上 ⇒ 把位图读回来。
                        codec.releaseOutputBuffer(outIdx, true)
                        val bmp = readImage(reader, targetW, targetH)
                        if (bmp != null) return bmp
                        // 渲染到 Surface 之后 Image 不会同步立刻可用，继续下一轮。
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
}
