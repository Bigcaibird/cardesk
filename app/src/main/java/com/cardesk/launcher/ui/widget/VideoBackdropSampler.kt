package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import com.cardesk.launcher.core.VideoWallpaper
import com.cardesk.launcher.core.WallpaperSampler
import java.io.File

/**
 * **视频壁纸**的亮度采样（第222轮；第231轮改成「先读文件再拷图层」）。
 *
 * ## 为什么视频要单独一套
 *
 * 视频画面在**独立的 SurfaceFlinger 图层**上（`SurfaceView` 后端），
 * 窗口 buffer 在那块区域是**透明的洞** ——
 * ⇒ 采窗口缓冲根本采不到视频（`ui/pip/PipFrameProbe` 里记着这条实测）。
 * 只能**直接拷视频自己的 Surface**（`PixelCopy`）。
 *
 * ## 第231轮：`PixelCopy` 不再是唯一一条路
 *
 * 用户反馈「视频壁纸下天气 / 状态栏 / 歌词颜色太近、看不清」——
 * 查下来是**亮度采样拿不到值**：拿不到就回落主题墨色，
 * 日间主题（深字）压在暗色视频上 = 深底深字，正好就是用户看到的样子。
 *
 * `PixelCopy` 有个**先天限制**：它读的是「这块 Surface 的当前缓冲」。
 * 真车（820A）上视频是**硬件解码直通 HWC 图层**的，
 * 这块 Surface 的缓冲对应用侧不可读（模拟器上走 GPU 合成所以能读、看不出来）
 * ⇒ 车机上这条**一直是失败的**，只有模拟器上「看起来能用」。
 *
 * 所以这一轮换成两条路，**先文件、后图层**：
 *
 * | 顺序 | 方式 | 为什么 |
 * |---|---|---|
 * | ① | [MediaMetadataRetriever.getScaledFrameAtTime] 直接**解视频文件**当前帧 | 跟合成方式无关，车机上照样能读；小尺寸解码几十毫秒 |
 * | ② | `PixelCopy` 拷视频 Surface / `TextureView.getBitmap()` | 文件读不到时（换源瞬间 / 极端情况）兜底 |
 *
 * 两条都失败才返回 null（这时上层保持上一次的值 / 跟着主题走）。
 *
 * ## 采什么
 *
 * 不再是「平均色」，而是 [WallpaperSampler.tone] 的**亮度分布**
 * （平均值 + 暗端 10% + 亮端 10%）—— 视频有局部高光，
 * 只按平均值选墨色会「平均值说够清楚、局部却发糊」（第231轮）。
 *
 * ## 线程
 *
 * 解文件那一趟在**独立后台线程**（[HandlerThread]）上跑，结果回到主线程
 * （`PixelCopy` 也要求一个带 Looper 的 handler）。
 */
class VideoBackdropSampler(
    private val host: android.view.View,
    private val backend: Int,
    private val handler: Handler,
    /** 视频文件的绝对路径提供者（null = 文件那条路不可用，只能拷图层）。 */
    private val videoFile: (() -> File?)? = null,
    /** 当前播放位置（毫秒）提供者；null 或拿不到时按 0 处理。 */
    private val positionMs: (() -> Int)? = null,
) {

    companion object {
        /** 解出来的小图宽度（比例坐标 ⇒ 小图就够，采一次几毫秒）。 */
        private const val DECODE_W = 256

        /** 解出来的小图高度。 */
        private const val DECODE_H = 144
    }

    /** 采样中的标志（同一时刻只允许一次采样在飞）。 */
    private var pending = false

    /** 复用的帧缓冲（`PixelCopy` 用；每次新建 1920×1080 的位图太浪费）。 */
    private var frame: Bitmap? = null

    /** 解文件的后台线程（懒创建）。 */
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    /**
     * 最近一次成功的那条路（诊断用）：`file` / `layer` / `texture` / null。
     *
     * 会随日志打出去 —— 车机上如果一直是 `layer`，说明读的是图层（不可靠）；
     * 正常应该是 `file`。
     */
    var lastSource: String? = null
        private set

    /**
     * 采一帧。
     *
     * @param onResult 回调（顶部 / 中部的亮度分布；拿不到就是 null）
     */
    fun sample(onResult: (top: WallpaperSampler.Tone?, center: WallpaperSampler.Tone?) -> Unit) {
        if (pending) return
        if (host.width <= 0 || host.height <= 0) {
            onResult(null, null)
            return
        }
        pending = true
        // ① 先读文件（跟合成方式无关，车机上唯一可靠的一条）。
        // ② 文件读不到 ⇒ 再拷图层（模拟器 / GPU 合成时能读到）。
        sampleFromFile(onResult) {
            if (backend == VideoWallpaper.BACKEND_TEXTURE_VIEW) {
                sampleTextureView(onResult)
            } else {
                sampleSurfaceView(onResult)
            }
        }
    }

    // ------------------------------------------------------------------
    // ① 解视频文件（第231轮）
    // ------------------------------------------------------------------

    /** 解视频文件当前帧 → 采样；失败就交给 [onFail]（不结束这次采样）。 */
    private fun sampleFromFile(
        onResult: (WallpaperSampler.Tone?, WallpaperSampler.Tone?) -> Unit,
        onFail: () -> Unit,
    ) {
        val file = videoFile?.invoke()
        if (file == null || !file.isFile || file.length() <= 0L) {
            onFail()
            return
        }
        val dh = ensureDecodeHandler()
        if (dh == null) {
            onFail()
            return
        }
        val position = (positionMs?.invoke() ?: 0).coerceAtLeast(0)
        dh.post {
            val bmp = decodeFrame(file, position)
            handler.post {
                if (bmp == null) {
                    onFail()
                    return@post
                }
                val top = WallpaperSampler.tone(bmp, WallpaperSampler.VIDEO_TOP_REGION, false)
                val center = WallpaperSampler.tone(bmp, WallpaperSampler.VIDEO_CENTER_REGION, false)
                if (top == null && center == null) {
                    onFail()
                } else {
                    lastSource = "file"
                    finish(onResult, top, center)
                }
            }
        }
    }

    /**
     * 在后台线程上解出文件当前帧的**亮度小图**。
     *
     * 走 [VideoFrameLuma]（解码到 `ImageReader` 的 Surface 再读 Y 平面）——
     * `MediaMetadataRetriever` 在模拟器上解出来是全黑（见那个类的注释），不能用。
     */
    private fun decodeFrame(file: File, positionMs: Int): Bitmap? = runCatching {
        VideoFrameLuma.frame(file, positionMs, DECODE_W, DECODE_H)
    }.getOrNull()

    /** 懒建解文件的后台线程。 */
    private fun ensureDecodeHandler(): Handler? {
        decodeHandler?.let { return it }
        return runCatching {
            val t = HandlerThread("video-backdrop")
            t.start()
            decodeThread = t
            Handler(t.looper).also { decodeHandler = it }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // ② 兜底：拷图层（第222轮那两条）
    // ------------------------------------------------------------------

    /** `TextureView` 后端：直接拿当前帧。 */
    private fun sampleTextureView(onResult: (WallpaperSampler.Tone?, WallpaperSampler.Tone?) -> Unit) {
        val bmp = (host as? TextureView)?.getBitmap()
        if (bmp == null || bmp.isRecycled) {
            finish(onResult, null, null)
            return
        }
        val top = WallpaperSampler.tone(bmp, WallpaperSampler.VIDEO_TOP_REGION, false)
        val center = WallpaperSampler.tone(bmp, WallpaperSampler.VIDEO_CENTER_REGION, false)
        bmp.recycle()
        if (top == null && center == null) {
            finish(onResult, null, null)
        } else {
            lastSource = "texture"
            finish(onResult, top, center)
        }
    }

    /** `SurfaceView` 后端：拷视频自己的 Surface。 */
    private fun sampleSurfaceView(onResult: (WallpaperSampler.Tone?, WallpaperSampler.Tone?) -> Unit) {
        val w = host.width
        val h = host.height
        val surface = (host as? SurfaceView)?.holder?.surface
        if (surface == null || !surface.isValid || w <= 0 || h <= 0) {
            finish(onResult, null, null)
            return
        }
        val bmp = frame
        if (bmp == null || bmp.isRecycled || bmp.width < w || bmp.height < h) {
            frame?.let { if (!it.isRecycled) it.recycle() }
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val dest = frame ?: run {
            finish(onResult, null, null)
            return
        }
        PixelCopy.request(
            surface,
            Rect(0, 0, w, h),
            dest,
            { result ->
                if (result != PixelCopy.SUCCESS) {
                    finish(onResult, null, null)
                    return@request
                }
                val top = WallpaperSampler.tone(dest, WallpaperSampler.VIDEO_TOP_REGION, false)
                val center = WallpaperSampler.tone(dest, WallpaperSampler.VIDEO_CENTER_REGION, false)
                if (top != null || center != null) lastSource = "layer"
                finish(onResult, top, center)
            },
            handler,
        )
    }

    /** 收尾：清 pending 标记再回调（同步路径也要走这里，标志只在这里落）。 */
    private fun finish(
        onResult: (WallpaperSampler.Tone?, WallpaperSampler.Tone?) -> Unit,
        top: WallpaperSampler.Tone?,
        center: WallpaperSampler.Tone?,
    ) {
        pending = false
        onResult(top, center)
    }

    /** 丢掉缓存的位图 / retriever / 后台线程（视频停了 / 换源时调）。 */
    fun release() {
        frame?.let { if (!it.isRecycled) it.recycle() }
        frame = null
        runCatching { decodeThread?.quitSafely() }
        decodeThread = null
        decodeHandler = null
        pending = false
    }
}
