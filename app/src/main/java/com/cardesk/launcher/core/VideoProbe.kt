package com.cardesk.launcher.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File

/**
 * 读视频元数据（第三百一十八轮）。
 *
 * 用 `MediaMetadataRetriever` 只读**元数据**，不解码帧 —— 所以哪怕这台机器的
 * 软解出黑帧（第231轮踩过：`getFrameAtTime` 在 AOSP 软解下解出来全黑），
 * 元数据仍然是可靠的。
 *
 * 帧率的来路有两个：
 * 1. `METADATA_KEY_CAPTURE_FRAMERATE`（拍摄帧率，最准，但很多容器没有）；
 * 2. 用 `METADATA_KEY_VIDEO_FRAME_COUNT / 时长` 反推（容器里没有帧率时用）。
 */
object VideoProbe {

    /** 读元数据；失败返回 null（调用方按「读不出」处理）。 */
    fun probe(file: File): VideoSpec.Info? {
        if (!file.isFile || file.length() <= 0L) return null
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durationMs =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val captureFps =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val frames =
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            val fps = captureFps ?: if (frames > 0L && durationMs > 0L) frames * 1000f / durationMs else null
            VideoSpec.Info(
                width = width,
                height = height,
                fps = fps,
                durationMs = durationMs,
                bytes = file.length(),
            )
        } catch (e: Throwable) {
            Logx.w(LogTags.SETTING, "视频壁纸：读元数据失败", e, "path" to file.absolutePath)
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 探测 + 判定（导入时用）。 */
    fun validate(file: File): VideoSpec.Verdict {
        val info = probe(file) ?: return VideoSpec.Verdict.Reject("读不出视频信息（文件可能已损坏）")
        val meta = VideoSpec.check(info)
        if (meta !is VideoSpec.Verdict.Reject && !decodeCheck(file)) {
            // 第三百一十八轮：元数据合法但**这台机器解不出画面**的素材以前能过关 ——
            // 表现是起播后一帧都不来（黑背景），首帧超时兜底再把它显示出来，
            // 用户看到的就是"视频壁纸是黑的"。这里在导入时先真解一帧拦掉。
            return VideoSpec.Verdict.Reject("这个视频车机解不出画面（编码参数不支持），换一个试试")
        }
        return meta
    }

    /**
     * 真解一帧（第三百一十八轮）。
     *
     * 为什么要单独做：`MediaMetadataRetriever` 只读容器元数据，容器写得很规矩、
     * 但编码参数（例如宽高不是 16 的倍数、level 超出本机解码器能力）会让解码器
     * 一个输出帧都不给 —— 元数据判定发现不了，播放器侧也只会一直黑着。
     *
     * 做法：`MediaExtractor` 取视频轨 → `MediaCodec` 不绑 surface（解到 ByteBuffer）
     * → 最多等 [timeoutMs] 拿到第一个**有数据**的输出缓冲。解不出返回 false。
     *
     * 注意：这里是**同步阻塞**的探测，只在导入线程 / 后台线程调用。
     */
    fun decodeCheck(file: File, timeoutMs: Long = 2_500L): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Logx.w(LogTags.SETTING, "视频解码探测：没有视频轨", "path" to file.name)
                return false
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val deadline = SystemClock.uptimeMillis() + timeoutMs
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var gotFrame = false
            while (!gotFrame && SystemClock.uptimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)
                        val size = if (input == null) -1 else extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) gotFrame = true
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            Logx.i(
                LogTags.SETTING,
                "视频解码探测结束",
                "ok" to gotFrame,
                "path" to file.name,
                "timeoutMs" to timeoutMs,
            )
            gotFrame
        } catch (e: Throwable) {
            Logx.w(LogTags.SETTING, "视频解码探测失败", e, "path" to file.absolutePath)
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}
