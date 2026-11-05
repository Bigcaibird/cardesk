package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.MediaSourceIconKind
import com.cardesk.launcher.core.media.MediaSources
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * 媒体源**图标**（第 125 轮）。
 *
 * 音乐卡片来源行原来只有一个品牌色小圆点 —— 看得出是哪家，但认不出是哪个 App。
 * 这一轮换成真正的图标。
 *
 * ## 两级取法
 *
 * 1. **真实 App 图标**（[MediaSourceIconKind.APP]）：
 *    `PackageManager.getApplicationIcon()` 取 QQ音乐 / 网易云 / 酷狗 / 酷我 /
 *    汽水 / 多媒体 的启动图标 —— 用户一眼就认得。
 * 2. **自绘符号**（NOTE / BLUETOOTH / USB）：
 *    蓝牙音乐、USB 音乐、通用音乐**没有对应的 App**，硬凑别人的图标会认错，
 *    所以用 Canvas 画一个专属符号。
 *
 * > 用户选的是「真实 App 图标优先，取不到时用 Canvas 自绘符号」——
 * > 所以顺序是：**先试真图标，拿不到才画符号**。
 *
 * ## 性能
 *
 * - 真图标**按来源 + 尺寸缓存**（[cache]），同一次运行只取一次；
 * - 取图标要读 APK 资源，**放后台线程**调（调用方负责），
 *   主线程只读缓存 —— 桌面每秒刷新也不会卡。
 */
object MediaSourceIcons {

    /**
     * 缓存：`sourceId@sizePx` → 位图。
     *
     * 用 [ConcurrentHashMap] 而不是 [HashMap]：这张表是**后台线程写**
     * （[load]）、**主线程读**（[cached]，桌面每秒刷新都调一次）。
     * 普通 HashMap 一边 resize 一边读，理论上会走进死循环 / 丢更新，
     * 用并发 Map 就不用管了 —— 这里不需要更强的语义。
     */
    private val cache = ConcurrentHashMap<String, Bitmap>()

    /** 真图标的边长（像素）。按**屏幕像素**取，画的时候缩到设计坐标。 */
    const val APP_ICON_PX = 64

    /** 缓存命中（不碰 PackageManager，主线程可直接调）。 */
    fun cached(sourceId: String, sizePx: Int = APP_ICON_PX): Bitmap? = cache[key(sourceId, sizePx)]

    /**
     * 取该来源的真实 App 图标；没有对应 App 或没装 → 返回 null（由调用方画自绘符号）。
     *
     * **会读 PackageManager 与 APK 资源，别在主线程调**（除非先 [cached] 命中）。
     */
    fun load(context: Context, sourceId: String, sizePx: Int = APP_ICON_PX): Bitmap? {
        cached(sourceId, sizePx)?.let { return it }
        val candidates = MediaSources.iconPackageCandidates(sourceId)
        if (candidates.isEmpty()) return null
        val pkg = candidates.firstOrNull { installed(context, it) } ?: return null
        val bmp = loadAppIcon(context, pkg, sizePx)
        if (bmp == null) {
            Logx.d(
                LogTags.MEDIA, "媒体源图标取不到",
                "sourceId" to sourceId, "pkg" to pkg,
            )
            return null
        }
        cache[key(sourceId, sizePx)] = bmp
        Logx.d(LogTags.MEDIA, "媒体源图标已加载", "sourceId" to sourceId, "pkg" to pkg, "px" to sizePx)
        return bmp
    }

    /**
     * 画自绘符号（蓝牙 / USB / 音符）。
     *
     * @param cx 中心 x
     * @param cy 中心 y
     * @param r 半径（符号外接圆半径）
     * @param color 描边 / 填充色（一般用该来源的品牌色）
     */
    fun drawFallback(
        canvas: Canvas,
        kind: MediaSourceIconKind,
        cx: Float,
        cy: Float,
        r: Float,
        color: Int,
    ) {
        when (kind) {
            MediaSourceIconKind.APP -> Unit // 没有真图标时也不画自绘 —— 调用方自己决定
            MediaSourceIconKind.NOTE -> drawNote(canvas, cx, cy, r, color)
            MediaSourceIconKind.BLUETOOTH -> drawBluetooth(canvas, cx, cy, r, color)
            MediaSourceIconKind.USB -> drawUsb(canvas, cx, cy, r, color)
        }
    }

    // ------------------------------------------------------------------ 自绘符号

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 音符（通用音乐）。
     *
     * 符头是一个**旋转 -20° 的实心椭圆**（左低右高，标准音符的样子），
     * 符干竖直立在右边。
     *
     * ## 形状是调出来的，不是拍出来的
     *
     * 第一版符头用两个三次贝塞尔画，画出来是个**向下凸的「碗」** ——
     * 在实机截图上根本认不出是音符。改成旋转椭圆后又太**扁**
     * （长轴 0.96r / 短轴 0.48r），16px 下还是像根「」。
     * 现在把短轴加到 **0.64r**（接近正圆），小尺寸下才认得出是音符。
     */
    private fun drawNote(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        // 符头：绕自身中心旋转 -20°（逆时针 → 右端上翘）
        fillPaint.color = color
        canvas.save()
        canvas.rotate(-20f, cx - 0.06f * r, cy + 0.42f * r)
        canvas.drawOval(
            RectF(cx - 0.50f * r, cy + 0.10f * r, cx + 0.38f * r, cy + 0.74f * r),
            fillPaint,
        )
        canvas.restore()
        // 符干：竖直，右端接着符头
        strokePaint.color = color
        strokePaint.strokeWidth = r * 0.20f
        canvas.drawLine(cx + 0.28f * r, cy - 0.88f * r, cx + 0.28f * r, cy + 0.44f * r, strokePaint)
    }

    /**
     * 蓝牙符号（蓝牙音乐）。
     *
     * 标准蓝牙 rune：一条竖线 + 上下两个折角。
     */
    private fun drawBluetooth(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = r * 0.20f
        val p = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + r * 0.62f, cy - r * 0.38f)
            lineTo(cx, cy + r * 0.24f)
            moveTo(cx, cy + r)
            lineTo(cx + r * 0.62f, cy + r * 0.38f)
            lineTo(cx, cy - r * 0.24f)
            moveTo(cx, cy - r)
            lineTo(cx, cy + r)
        }
        canvas.drawPath(p, strokePaint)
    }

    /**
     * USB 符号（USB 音乐）。
     *
     * 顶部方块 + 主干 + 左侧分支和圆点 —— USB trident 的简化版。
     *
     * 方块要**比主干粗**（不然整体看着「上细下粗」），主干也不能太细
     * —— 16px 下线宽低于 0.2r 就断了。
     */
    private fun drawUsb(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fillPaint.color = color
        strokePaint.color = color
        // 顶部方块
        val box = RectF(cx - r * 0.30f, cy - r * 0.90f, cx + r * 0.30f, cy - r * 0.44f)
        canvas.drawRoundRect(box, r * 0.10f, r * 0.10f, fillPaint)
        // 主干
        strokePaint.strokeWidth = r * 0.24f
        canvas.drawLine(cx, cy - r * 0.44f, cx, cy + r * 0.92f, strokePaint)
        // 左侧分支
        canvas.drawLine(cx - r * 0.56f, cy + r * 0.18f, cx, cy + r * 0.18f, strokePaint)
        canvas.drawLine(cx - r * 0.56f, cy + r * 0.18f, cx - r * 0.56f, cy + r * 0.46f, strokePaint)
        // 末端圆点
        canvas.drawCircle(cx - r * 0.56f, cy + r * 0.60f, r * 0.19f, fillPaint)
    }

    // ------------------------------------------------------------------ 真图标

    /** 该包是否装了。 */
    private fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0) != null
    }.getOrDefault(false)

    /** 取 App 图标并**居中裁剪**成正方形。 */
    private fun loadAppIcon(context: Context, pkg: String, sizePx: Int): Bitmap? {
        val drawable = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        val src = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, iw, ih)
        drawable.draw(Canvas(src))
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // cover：短边贴满，长边居中裁掉 —— 避免把图标拉变形。
        val scale = max(sizePx.toFloat() / iw, sizePx.toFloat() / ih)
        val dw = iw * scale
        val dh = ih * scale
        val left = (sizePx - dw) / 2f
        val top = (sizePx - dh) / 2f
        Canvas(out).drawBitmap(
            src, null, RectF(left, top, left + dw, top + dh),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private fun key(sourceId: String, sizePx: Int) = "$sourceId@$sizePx"
}
