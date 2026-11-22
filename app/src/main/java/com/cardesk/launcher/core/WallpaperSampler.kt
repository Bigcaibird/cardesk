package com.cardesk.launcher.core

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 壁纸亮度采样（第222轮）。
 *
 * ## 采的是什么
 *
 * 只采**浮层真正压着的那两块**：
 *
 * | 采样区 | 覆盖的画面 | 谁在用 |
 * |---|---|---|
 * | [TOP_REGION] | 状态栏右侧（时钟 / 油量 / 连接图标）+ 右上天气块 | 状态栏、场景页天气 |
 * | [CENTER_REGION] | 屏幕中部（中心歌词） | 场景页中心歌词 |
 *
 * 坐标是**相对位图的比例**（0..1），所以任何分辨率的壁纸都能用同一套口径。
 *
 * ## 为什么不是整屏平均
 *
 * 整屏平均会把「上亮下暗」的壁纸压成一个中间值 ——
 * 顶部亮、中部暗的壁纸，按整屏算出来是「亮底」，
 * 结果中心歌词被换成深色字，压在暗色背景上照样看不清。
 * 分成两块采样，各自按各自的底色决定墨色。
 *
 * ## 采样成本
 *
 * 一块区域最多取 **4 万个像素**（按步长抽稀）——
 * 静态壁纸那一路是 192×108 的**代理图**（见 `WallpaperSampler.proxyOf`），
 * 一次几十毫秒以内，而且**只在壁纸 / 日夜变化时算一次**，不是每帧。
 *
 * ## 第231轮：视频壁纸要「最坏情况」而不是平均值
 *
 * 用户反馈：**视频壁纸（湖畔静思）下天气 / 状态栏 / 歌词颜色跟背景太近、看不清**。
 *
 * 视频是**一直在动**的画面（而且带高光岩壁），只有平均值不够：
 * 平均值 88 的歌词带里，最亮那 10% 的像素有 122 ——
 * 浅字压在这撮像素上只有 3.3:1（纯白也只有 4.3:1）。平均值判定会说「够清楚」，
 * 可肉眼看到的恰恰是**局部发糊**。
 *
 * 所以视频这一路除了平均值，还额外采出**暗端 / 亮端两簇像素的代表色**
 * （见 [Tone]），由 `WallpaperContrast.inkForTone` 按「浅字对亮端、深字对暗端」
 * 的最坏情况去拉字色亮度。
 *
 * 口径有两条**刻意的限制**：
 * 1. **方向仍由平均值定**（不被局部高光带偏）—— 局部一团白高光不该把整块区域判成「亮底」；
 * 2. 暗端 / 亮端各取**最外侧 10%** 像素的平均色，用来「把字色拉够」，不用来选择方向。
 */
object WallpaperSampler {

    /**
     * 一块采样区（相对位图的比例，0..1）。
     *
     * @param left 左边界比例
     * @param top 上边界比例
     * @param right 右边界比例
     * @param bottom 下边界比例
     */
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * 顶部浮层区。
     *
     * 设计坐标（1920×1080）：x 1056~1920、y 0~216。
     * - 状态栏右侧的裸元素（时钟 / 油量 / 连接图标）都在 x 1450~1891；
     * - 右上天气块在 x 1488~1876、y 75~200。
     *
     * 左边（x < 1056）不采：那块只有**带底板的**胶囊（驾驶模式 / 位置 / 天气），
     * 底板自带底色，字压底板、不压壁纸 ⇒ 不受壁纸亮度影响。
     */
    val TOP_REGION = Region(left = 0.55f, top = 0f, right = 1f, bottom = 0.20f)

    /**
     * 屏幕中部（中心歌词）。
     *
     * 设计坐标：x 480~1440（歌词块居中、最大宽 960）、y 367~670
     * （块中心 y=500，三行 56/40/34，字号可放到 140% ⇒ 上下留够余量）。
     */
    val CENTER_REGION = Region(left = 0.25f, top = 0.34f, right = 0.75f, bottom = 0.62f)

    /**
     * **视频壁纸**专用的顶部区（第231轮）：只覆盖文字真正会压到的地方。
     *
     * 为什么不能沿用 [TOP_REGION]（哪怕它的注释就是按这个意图写的）：
     * 它的左边界 `0.55` 是「x < 1056 不采」的姿态，但状态栏裸元素实际从
     * x≈1450（0.755）才开始；`0.55..0.74` 这段在「湖畔静思」上是**亮的暖色岩壁**
     * （实测最亮 luma 191）—— 把它算进平均值，区域就从「暗」（79）
     * 被拉成「中间调」（114），选出来的字色只按平均值算「够 4.5:1」，
     * 压在最亮那撮岩壁上其实不够。
     *
     * 但**静态壁纸那条路不能动**：第222轮在 cardesk28 上逐项验收过
     * （时钟 14.69 / 天气 14.38 / 明细 6.85），换区域会让那批数字全部失效
     * ⇒ 视频壁纸单独一套。
     *
     * 设计坐标：x 1420~1920、y 10~205（状态栏文字 y≈17~60 + 天气块 y 75~200）。
     */
    val VIDEO_TOP_REGION = Region(left = 0.74f, top = 0.01f, right = 1f, bottom = 0.19f)

    /**
     * **视频壁纸**专用的中心区（第231轮）：歌词带的实际落点，比 [CENTER_REGION] 窄一点。
     *
     * 歌词是居中一行（实测占 x 0.30~0.70、y 0.36~0.60），
     * `CENTER_REGION` 左右各多 5% 的亮边会被算进去（p90 从 122 抬到 143），
     * 把字色不必要地推向纯白。
     */
    val VIDEO_CENTER_REGION = Region(left = 0.28f, top = 0.36f, right = 0.72f, bottom = 0.60f)

    /** 单块区域最多取多少像素（抽稀后的上限）。 */
    private const val MAX_SAMPLES = 40_000

    /**
     * 自定义壁纸在**夜间**的压暗系数（按 y 比例）。
     *
     * 与 `ui/widget/WallpaperRenderer.drawCustom` 同一口径：
     * 整幅盖一层 `0x66000000`（40% 黑 ⇒ ×0.6），
     * 顶部再盖一层 `0x40` → `0x00` 的纵向渐变（25% 黑，55% 高度处淡出）。
     *
     * 不算这个系数的话，夜间采样到的亮度会比**屏幕上真实看到的**亮一截 ——
     * 判定就会偏。
     */
    fun customNightScrim(yFraction: Float): Float {
        val base = 1f - 0.40f
        val top = if (yFraction >= 0.55f) 0f else 0.25f * (1f - yFraction / 0.55f)
        return base * (1f - top)
    }

    /**
     * 一块区域的**亮度分布**（第231轮）。
     *
     * @param average 整块区域的平均颜色
     * @param dark **最暗的 10% 像素**的平均色（深字要压在这上面看）
     * @param bright **最亮的 10% 像素**的平均色（浅字要压在这上面看）
     */
    data class Tone(
        val average: Int,
        val dark: Int,
        val bright: Int,
    )

    /**
     * 采样一块区域的**亮度分布**（第231轮；视频壁纸用）。
     *
     * @return 平均值 + 暗端 10% / 亮端 10% 的代表色；位图不可用时返回 null
     */
    fun tone(bitmap: Bitmap?, region: Region, scrim: Boolean): Tone? {
        if (bitmap == null || bitmap.isRecycled) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val x0 = (region.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (region.right * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (region.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (region.bottom * h).toInt().coerceIn(y0 + 1, h)
        val factor = if (scrim) customNightScrim((region.top + region.bottom) / 2f) else 1f
        return toneInRect(bitmap, x0, y0, x1, y1, factor)
    }

    /**
     * 采样一块区域的**平均颜色**（ARGB，alpha 恒 255）。
     *
     * @param bitmap 壁纸位图（静态壁纸的成品图 / 自定义照片 / 视频帧）
     * @param region 采样区（比例）
     * @param scrim true = 叠加夜间自定义壁纸的压暗（见 [customNightScrim]）
     * @return 平均颜色；位图不可用时返回 null
     */
    fun averageColor(bitmap: Bitmap?, region: Region, scrim: Boolean): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val x0 = (region.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (region.right * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (region.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (region.bottom * h).toInt().coerceIn(y0 + 1, h)
        val factor = if (scrim) customNightScrim((region.top + region.bottom) / 2f) else 1f
        return averageInRect(bitmap, x0, y0, x1, y1, factor)
    }

    /**
     * 按「等比填满 + 居中裁切」的实际映射，把**画面上的比例区域**换算成**源图上的像素区域**。
     *
     * 自定义壁纸是这么画的（见 `WallpaperRenderer.drawCustom`）：
     * 源图按 `scale = max(viewW/srcW, viewH/srcH)` 放大后，**居中**铺到
     * `RectF(left, top, left+dw, top+dh)`（可能比视口大 ⇒ 上下或左右被裁掉）。
     *
     * 反推：画面上的 `(fx·viewW, fy·viewH)` 对应源图上的
     * `((fx·viewW − left) / scale, (fy·viewH − top) / scale)`。
     *
     * ⚠️ 这里用的是**视口**尺寸（`viewW` / `viewH`），不是放大后的 `dw` / `dh` ——
     * 用 `dw` / `dh` 算的话，竖拍照片（被裁掉一半）会取到画面**之外**的源图区域。
     *
     * @return 源图上的像素矩形 [x0, y0, x1, y1]
     */
    fun sourceRectFor(
        srcW: Int,
        srcH: Int,
        viewW: Float,
        viewH: Float,
        region: Region,
    ): IntArray {
        if (srcW <= 0 || srcH <= 0 || viewW <= 0f || viewH <= 0f) return intArrayOf(0, 0, 1, 1)
        val scale = max(viewW / srcW, viewH / srcH)
        val dw = srcW * scale
        val dh = srcH * scale
        val left = (viewW - dw) / 2f
        val top = (viewH - dh) / 2f
        val sx0 = ((region.left * viewW - left) / scale).toInt().coerceIn(0, srcW - 1)
        val sy0 = ((region.top * viewH - top) / scale).toInt().coerceIn(0, srcH - 1)
        val sx1 = ((region.right * viewW - left) / scale).toInt().coerceIn(sx0 + 1, srcW)
        val sy1 = ((region.bottom * viewH - top) / scale).toInt().coerceIn(sy0 + 1, srcH)
        return intArrayOf(sx0, sy0, sx1, sy1)
    }

    /**
     * 在源图的像素矩形里采样平均颜色（供 [sourceRectFor] 的结果直接用）。
     *
     * @param rect 源图像素矩形 [x0, y0, x1, y1]
     * @param scrim true = 叠加夜间自定义壁纸的压暗
     * @param yFraction 该矩形在**画面上的**高度比例中值（压暗系数按它算）
     */
    fun averageColorInRect(
        bitmap: Bitmap?,
        rect: IntArray,
        scrim: Boolean,
        yFraction: Float,
    ): Int? {
        if (bitmap == null || bitmap.isRecycled) return null
        val x0 = rect[0].coerceIn(0, bitmap.width - 1)
        val y0 = rect[1].coerceIn(0, bitmap.height - 1)
        val x1 = rect[2].coerceIn(x0 + 1, bitmap.width)
        val y1 = rect[3].coerceIn(y0 + 1, bitmap.height)
        val factor = if (scrim) customNightScrim(yFraction) else 1f
        return averageInRect(bitmap, x0, y0, x1, y1, factor)
    }

    /** 真正的取样实现：矩形内按步长抽稀求平均。 */
    private fun averageInRect(
        bitmap: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        factor: Float,
    ): Int? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null
        // 抽稀步长：保证取样点不超过 MAX_SAMPLES。
        val step = max(1, ceil(sqrt((rw.toLong() * rh) / MAX_SAMPLES.toDouble())).toInt())
        val row = IntArray(rw)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        var y = y0
        while (y < y1) {
            bitmap.getPixels(row, 0, rw, x0, y, rw, 1)
            var x = 0
            while (x < rw) {
                val p = row[x]
                sumR += (((p shr 16) and 0xFF) * factor).toLong()
                sumG += (((p shr 8) and 0xFF) * factor).toLong()
                sumB += ((p and 0xFF) * factor).toLong()
                count++
                x += step
            }
            y += step
        }
        if (count == 0L) return null
        val r = (sumR / count).toInt().coerceIn(0, 255)
        val g = (sumG / count).toInt().coerceIn(0, 255)
        val b = (sumB / count).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 直方图的桶数（luma 0..255 均分）。16 桶 ⇒ 每桶 16 级，够分「亮端 / 暗端」了。 */
    private const val BUCKETS = 16

    /** 暗端 / 亮端各取多少比例的像素。 */
    private const val TAIL_FRACTION = 0.10

    /**
     * 真正的「亮度分布」取样：一趟扫描同时算平均值 + 16 桶 luma 直方图，
     * 再从直方图两端各取 [TAIL_FRACTION] 的像素算代表色。
     *
     * 只扫一趟（每桶累计 RGB 和），比「先求分位数再扫第二趟」便宜一半。
     */
    private fun toneInRect(
        bitmap: Bitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        factor: Float,
    ): Tone? {
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return null
        val step = max(1, ceil(sqrt((rw.toLong() * rh) / MAX_SAMPLES.toDouble())).toInt())
        val row = IntArray(rw)
        val cnt = IntArray(BUCKETS)
        val sumR = LongArray(BUCKETS)
        val sumG = LongArray(BUCKETS)
        val sumB = LongArray(BUCKETS)
        var totR = 0L
        var totG = 0L
        var totB = 0L
        var total = 0L
        var y = y0
        while (y < y1) {
            bitmap.getPixels(row, 0, rw, x0, y, rw, 1)
            var x = 0
            while (x < rw) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                val bi = (luma * BUCKETS / 256).coerceIn(0, BUCKETS - 1)
                cnt[bi]++
                sumR[bi] += r.toLong()
                sumG[bi] += g.toLong()
                sumB[bi] += b.toLong()
                totR += r.toLong()
                totG += g.toLong()
                totB += b.toLong()
                total++
                x += step
            }
            y += step
        }
        if (total == 0L) return null
        val average = colorOf(totR, totG, totB, total, factor)
        val tail = (total * TAIL_FRACTION).toLong().coerceAtLeast(1L)

        // 暗端：从最暗的桶往上累，凑够 tail 个像素
        var cum = 0L
        var dr = 0L
        var dg = 0L
        var db = 0L
        var dn = 0L
        var i = 0
        while (i < BUCKETS && cum < tail) {
            cum += cnt[i]
            dr += sumR[i]
            dg += sumG[i]
            db += sumB[i]
            dn += cnt[i]
            i++
        }
        // 亮端：从最亮的桶往下累
        cum = 0L
        var br = 0L
        var bg = 0L
        var bb = 0L
        var bn = 0L
        i = BUCKETS - 1
        while (i >= 0 && cum < tail) {
            cum += cnt[i]
            br += sumR[i]
            bg += sumG[i]
            bb += sumB[i]
            bn += cnt[i]
            i--
        }
        val dark = if (dn > 0L) colorOf(dr, dg, db, dn, factor) else average
        val bright = if (bn > 0L) colorOf(br, bg, bb, bn, factor) else average
        return Tone(average = average, dark = dark, bright = bright)
    }

    /** 累加值 → ARGB（乘压暗系数，alpha 恒 255）。 */
    private fun colorOf(r: Long, g: Long, b: Long, n: Long, factor: Float): Int {
        if (n <= 0L) return 0xFF000000.toInt()
        val rr = ((r.toDouble() / n) * factor).toInt().coerceIn(0, 255)
        val gg = ((g.toDouble() / n) * factor).toInt().coerceIn(0, 255)
        val bb = ((b.toDouble() / n) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
