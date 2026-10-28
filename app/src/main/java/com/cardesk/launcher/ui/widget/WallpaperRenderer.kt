package com.cardesk.launcher.ui.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.cardesk.launcher.core.Wallpapers
import com.cardesk.launcher.core.Wallpapers.WallpaperSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 壁纸**程序化渲染器**（第五十七轮）。
 *
 * ## 目标
 *
 * 用户要求「默认壁纸不要用纯色的，用风景、拟物类的」。
 * 所以 9 张壁纸全部改成**用 Canvas 画出来的风景**：
 * 雪峰 / 雪湖 / 蓝调湖岸 / 极光 / 沙丘 / 城市夜色 / 云海松林。
 *
 * 我们**不复制图片资源**： 9 张里有 4 张是照片素材
 * 这里全部改成同名同顺序的**程序化绘制**，视觉上同为风景，体积上不占包体。
 *
 * ## 分层结构（每一张都是这几层叠出来的）
 *
 * `
 * 天空渐变 → 日/月 + 光晕 → 云 / 星 → 远山（含雪顶）→ 中山 → 近山 → 水面 + 倒影 → 前景
 * `
 *
 * ## 性能
 *
 * 壁纸是**静态画面**，没必要每帧重画。
 * 这里把每个 `(壁纸, 日夜, 尺寸)` 的成品**缓存成一张 Bitmap**（LRU，最多 10 张），
 * 之后每帧只是 `drawBitmap`。切换壁纸时才会重新生成一次。
 */
object WallpaperRenderer {

    /** 成品缓存条目数上限（1 张全屏 + 若干缩略图）。 */
    private const val MAX_CACHE = 10

    /** 山脊 / 沙脊的采样点数。 */
    private const val SAMPLES = 200

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** 成品缓存（access-order LRU）。 */
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size <= MAX_CACHE) return false
            eldest?.value?.recycle()
            return true
        }
    }

    /**
     * 把一张壁纸画到 [canvas]。
     *
     * 尺寸任意（缩略图 / 全屏都走这里），构图按比例算，任意长宽比都不会变形。
     *
     * @param spec 壁纸定义（[Wallpapers.SPECS] 里的一项）
     * @param night true = 夜间那套配色
     */
    fun draw(canvas: Canvas, spec: WallpaperSpec, w: Float, h: Float, night: Boolean) {
        if (w < 2f || h < 2f) return
        val iw = w.toInt()
        val ih = h.toInt()
        val key = "${spec.index}|$night|$iw x $ih"
        val cached = cache[key]
        val bmp: Bitmap = if (cached == null || cached.isRecycled) {
            val created = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            drawScene(Canvas(created), spec, iw.toFloat(), ih.toFloat(), night)
            cache[key] = created
            created
        } else {
            cached
        }
        paint.isFilterBitmap = true
        canvas.drawBitmap(bmp, 0f, 0f, paint)
    }

    /** 丢掉缓存（内存吃紧时可以调）。 */
    fun clearCache() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
    }

    // ------------------------------------------------------------------ 场景

    private fun drawScene(canvas: Canvas, spec: WallpaperSpec, w: Float, h: Float, night: Boolean) {
        // 画笔是共用的：上一张壁纸里 `paint.color = withAlpha(...)` 会把
        // `paint.alpha` 一起改掉（半透明），不复位的话下一张的**渐变层**
        // 会跟着变半透明 → 天空没铺满、整张发灰。
        resetPaint()
        val tone = if (night) spec.night else spec.day
        when (spec.style) {
            Wallpapers.Style.PEAK -> peaks(canvas, w, h, tone)
            Wallpapers.Style.LAKE -> lake(canvas, w, h, tone, stars = true)
            Wallpapers.Style.LAKE_BLUE -> lake(canvas, w, h, tone, stars = false)
            Wallpapers.Style.AURORA -> aurora(canvas, w, h, tone)
            Wallpapers.Style.DUNE -> dunes(canvas, w, h, tone)
            Wallpapers.Style.CITY -> city(canvas, w, h, tone)
            Wallpapers.Style.CLOUD_FOREST -> cloudForest(canvas, w, h, tone)
        }
        vignette(canvas, w, h)
    }

    /** 复位画笔（清 shader、alpha 回到不透明）。 */
    private fun resetPaint() {
        paint.shader = null
        paint.alpha = 255
        paint.color = Color.WHITE
    }

    /** 天空渐变（三段：天顶 / 中段 / 地平线）。 */
    private fun sky(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone, horizon: Float) {
        paint.alpha = 255
        paint.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(tone.skyTop, tone.skyMid, tone.skyLow),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** 光晕（径向渐变到透明）。 */
    private fun glow(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        paint.alpha = 255
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(color, alpha), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    /** 日 / 月本体。 */
    private fun disc(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.color = color
        canvas.drawCircle(cx, cy, r, paint)
    }

    /** 星点（随机位置 + 随机亮度）。 */
    private fun starField(canvas: Canvas, w: Float, limit: Float, color: Int, count: Int, seed: Int) {
        val rnd = Random(seed)
        repeat(count) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * limit * 0.94f
            val r = max(0.6f, w * (0.0008f + rnd.nextFloat() * 0.0018f))
            paint.color = withAlpha(color, 0x40 + rnd.nextInt(0x90))
            canvas.drawCircle(x, y, r, paint)
        }
    }

    /** 薄云带（几团扁椭圆叠出来的横向云）。 */
    private fun cloudBand(canvas: Canvas, w: Float, h: Float, y: Float, color: Int, alpha: Int, seed: Int) {
        val rnd = Random(seed)
        repeat(6) {
            val cx = rnd.nextFloat() * w
            val cy = y * (0.6f + rnd.nextFloat() * 0.8f)
            val rx = w * (0.07f + rnd.nextFloat() * 0.14f)
            val ry = h * (0.008f + rnd.nextFloat() * 0.016f)
            paint.color = withAlpha(color, alpha)
            canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint)
        }
    }

    // ------------------------------------------------------------------ 山脊

    /**
     * 山脊高度采样：三个不同波长的三角波叠加，越往下越平缓。
     *
     * @param baseY 山谷基准线
     * @param amp 最高峰相对基准线抬起的幅度
     * @param seed 随机种子（同种子永远同一条山脊，保证渲染可复现）
     */
    private fun ridgeSamples(w: Float, baseY: Float, amp: Float, seed: Int, cycles: Float): FloatArray {
        val rnd = Random(seed)
        val phases = FloatArray(3) { rnd.nextFloat() * 6.2831855f }
        val out = FloatArray(SAMPLES)
        for (i in 0 until SAMPLES) {
            val x = i / (SAMPLES - 1f) * w
            var y = baseY
            var a = amp
            var wl = w / cycles
            for (o in 0 until 3) {
                val tri = 1f - abs(sin(x / wl * 3.14159265f + phases[o]))
                y -= a * tri
                a *= 0.5f
                wl *= 0.5f
            }
            out[i] = y
        }
        return out
    }

    /** 由采样点拼出填充到 [bottom] 的山体路径。 */
    private fun ridgePath(ys: FloatArray, w: Float, bottom: Float): Path {
        path.reset()
        path.moveTo(0f, ys[0])
        for (i in 1 until ys.size) {
            path.lineTo(i / (ys.size - 1f) * w, ys[i])
        }
        path.lineTo(w, bottom)
        path.lineTo(0f, bottom)
        path.close()
        return path
    }

    private fun fillPath(canvas: Canvas, p: Path, color: Int) {
        paint.color = color
        canvas.drawPath(p, paint)
    }

    /**
     * 雪顶：把山脊线**高于阈值**的那几段填成亮色。
     *
     * 做法是顺着采样点找出连续的一段「低于阈值」的区间，
     * 用山脊线 + 一条水平阈值线围成一块闭合区域。
     */
    private fun fillCaps(canvas: Canvas, ys: FloatArray, w: Float, threshold: Float, color: Int) {
        val step = w / (ys.size - 1f)
        var i = 0
        while (i < ys.size) {
            if (ys[i] >= threshold) {
                i++
                continue
            }
            val start = i
            while (i < ys.size && ys[i] < threshold) i++
            val end = i - 1
            val p = Path()
            p.moveTo(start * step, ys[start])
            for (k in start + 1..end) p.lineTo(k * step, ys[k])
            p.lineTo(end * step, threshold)
            p.lineTo(start * step, threshold)
            p.close()
            paint.color = color
            canvas.drawPath(p, paint)
        }
    }

    // ------------------------------------------------------------------ 场景：雪峰 / 雪湖

    /** 雪峰（无水面）：紫晖雪山 / 日照金山。 */
    private fun peaks(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone) {
        val horizon = h * 0.70f
        sky(canvas, w, h, tone, horizon)
        glow(canvas, w * 0.70f, horizon - h * 0.30f, h * 0.24f, tone.sunGlow, 0x66)
        disc(canvas, w * 0.70f, horizon - h * 0.30f, h * 0.040f, tone.sun)
        cloudBand(canvas, w, h, h * 0.26f, tone.haze, 0x30, 5)
        // 远山（掺一点地平线雾 → 空气透视）
        val far = ridgeSamples(w, horizon - h * 0.02f, h * 0.22f, 11, 1.6f)
        fillPath(canvas, ridgePath(far, w, h), Wallpapers.mix(tone.ridgeFar, tone.haze, 0.38f))
        fillCaps(canvas, far, w, horizon - h * 0.13f, tone.detail)
        // 中山 / 近山 / 前景
        val mid = ridgeSamples(w, horizon + h * 0.03f, h * 0.17f, 23, 1.3f)
        fillPath(canvas, ridgePath(mid, w, h), tone.ridgeMid)
        val near = ridgeSamples(w, horizon + h * 0.13f, h * 0.12f, 37, 1.1f)
        fillPath(canvas, ridgePath(near, w, h), tone.ridgeNear)
        val front = ridgeSamples(w, h * 0.93f, h * 0.025f, 53, 0.9f)
        fillPath(canvas, ridgePath(front, w, h), tone.ridgeFront)
    }

    /** 雪湖 / 蓝调湖岸：山 + 水 + 倒影。 */
    private fun lake(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone, stars: Boolean) {
        val horizon = h * 0.58f
        sky(canvas, w, h, tone, horizon)
        if (stars) starField(canvas, w, horizon, tone.detail, 110, 20240607)
        glow(canvas, w * 0.32f, horizon - h * 0.22f, h * 0.20f, tone.sunGlow, 0x5C)
        disc(canvas, w * 0.32f, horizon - h * 0.22f, h * 0.036f, tone.sun)
        val far = ridgeSamples(w, horizon - h * 0.01f, h * 0.17f, 11, 1.5f)
        fillPath(canvas, ridgePath(far, w, horizon), Wallpapers.mix(tone.ridgeFar, tone.haze, 0.42f))
        fillCaps(canvas, far, w, horizon - h * 0.10f, tone.detail)
        val mid = ridgeSamples(w, horizon + h * 0.015f, h * 0.12f, 23, 1.2f)
        fillPath(canvas, ridgePath(mid, w, horizon), tone.ridgeMid)
        // 水面 + 倒影 + 波纹
        water(canvas, w, h, horizon, tone)
        reflect(canvas, mid, w, horizon, tone)
        streaks(canvas, w, h, horizon, tone)
    }

    /** 水面（近地平线更亮，远处更暗）。 */
    private fun water(canvas: Canvas, w: Float, h: Float, horizon: Float, tone: Wallpapers.Tone) {
        paint.alpha = 255
        paint.shader = LinearGradient(
            0f, horizon, 0f, h,
            intArrayOf(Wallpapers.mix(tone.water, tone.skyLow, 0.45f), tone.water),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, horizon, w, h, paint)
        paint.shader = null
    }

    /** 山体在水面的倒影（沿地平线翻折）。 */
    private fun reflect(canvas: Canvas, ys: FloatArray, w: Float, horizon: Float, tone: Wallpapers.Tone) {
        canvas.save()
        canvas.scale(1f, -1f, 0f, horizon)
        paint.color = withAlpha(Wallpapers.mix(tone.ridgeMid, tone.water, 0.55f), 0x5A)
        canvas.drawPath(ridgePath(ys, w, horizon), paint)
        canvas.restore()
    }

    /** 水面波纹（一堆横向短线）。 */
    private fun streaks(canvas: Canvas, w: Float, h: Float, horizon: Float, tone: Wallpapers.Tone) {
        val rnd = Random(97)
        var y = horizon + h * 0.015f
        while (y < h) {
            repeat(3 + rnd.nextInt(4)) {
                val lw = w * (0.04f + rnd.nextFloat() * 0.16f)
                val lx = rnd.nextFloat() * w
                paint.color = withAlpha(tone.haze, 0x2E)
                canvas.drawRoundRect(
                    RectF(lx, y, lx + lw, y + max(1f, h * 0.005f)),
                    h * 0.003f, h * 0.003f, paint,
                )
            }
            y += h * (0.03f + rnd.nextFloat() * 0.05f)
        }
    }

    // ------------------------------------------------------------------ 场景：极光 / 沙丘 / 城市 / 云海

    /** 极光：夜空 + 三条极光带 + 前景原野。 */
    private fun aurora(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone) {
        val horizon = h * 0.74f
        sky(canvas, w, h, tone, horizon)
        starField(canvas, w, horizon * 0.92f, tone.detail, 130, 424242)
        for (i in 0 until 3) {
            val baseY = h * (0.14f + i * 0.10f)
            val amp = h * (0.09f + i * 0.035f)
            val p = ribbonPath(w, baseY, amp, 1.1f + i * 0.5f, i * 2.3f, h * (0.14f + i * 0.045f))
            paint.alpha = 255
            paint.shader = LinearGradient(
                0f, baseY - amp, 0f, baseY + h * 0.14f,
                intArrayOf(withAlpha(tone.detail, 0x00), withAlpha(tone.detail, 0x62), withAlpha(tone.detail, 0x00)),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(p, paint)
            paint.shader = null
        }
        val plain = ridgeSamples(w, horizon + h * 0.01f, h * 0.03f, 61, 0.8f)
        fillPath(canvas, ridgePath(plain, w, h), tone.ridgeFront)
    }

    /** 极光带：上沿正弦、下沿带厚度，围成一条带子。 */
    private fun ribbonPath(w: Float, baseY: Float, amp: Float, cycles: Float, phase: Float, thickness: Float): Path {
        val n = 80
        val top = FloatArray(n)
        val bottom = FloatArray(n)
        for (i in 0 until n) {
            val x = i / (n - 1f) * w
            val y = baseY - amp * sin(x / w * 6.2831855f * cycles + phase)
            top[i] = y
            bottom[i] = y + thickness * (0.55f + 0.45f * sin(x / w * 6.2831855f * (cycles + 1.7f) + phase))
        }
        val p = Path()
        p.moveTo(0f, top[0])
        for (i in 1 until n) p.lineTo(i / (n - 1f) * w, top[i])
        for (i in n - 1 downTo 0) p.lineTo(i / (n - 1f) * w, bottom[i])
        p.close()
        return p
    }

    /** 沙丘：四层暖色沙脊（平滑正弦曲线）。 */
    private fun dunes(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone) {
        val horizon = h * 0.44f
        sky(canvas, w, h, tone, horizon)
        glow(canvas, w * 0.62f, horizon - h * 0.16f, h * 0.20f, tone.sunGlow, 0x62)
        disc(canvas, w * 0.62f, horizon - h * 0.16f, h * 0.038f, tone.sun)
        cloudBand(canvas, w, h, h * 0.18f, tone.haze, 0x28, 15)
        val colors = intArrayOf(tone.ridgeFar, tone.ridgeMid, tone.ridgeNear, tone.ridgeFront)
        for (i in 0 until 4) {
            val baseY = horizon + h * (0.05f + i * 0.135f)
            val amp = h * (0.040f + i * 0.016f)
            fillPath(canvas, dunePath(w, baseY, amp, 1.15f + i * 0.42f, i * 2.1f), colors[i])
        }
    }

    /** 沙脊路径：两个不同波长的正弦叠加。 */
    private fun dunePath(w: Float, baseY: Float, amp: Float, cycles: Float, phase: Float): Path {
        val n = 120
        val p = Path()
        for (i in 0 until n) {
            val x = i / (n - 1f) * w
            val y = baseY - amp * sin(x / w * 6.2831855f * cycles + phase) -
                amp * 0.35f * sin(x / w * 6.2831855f * (cycles * 2.6f) + phase * 1.7f)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.lineTo(w, baseY + amp * 4f)
        p.lineTo(0f, baseY + amp * 4f)
        p.close()
        return p
    }

    /** 城市夜色：天际线 + 窗灯 + 水面倒影。 */
    private fun city(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone) {
        val horizon = h * 0.72f
        sky(canvas, w, h, tone, horizon)
        starField(canvas, w, horizon * 0.82f, tone.detail, 70, 77777)
        glow(canvas, w * 0.78f, h * 0.17f, h * 0.15f, tone.sunGlow, 0x4A)
        disc(canvas, w * 0.78f, h * 0.17f, h * 0.028f, tone.sun)
        val far = skyline(canvas, w, h, horizon, tone.ridgeFar, 0.10f, 0.26f, 13, 0)
        val near = skyline(canvas, w, h, horizon, tone.ridgeNear, 0.15f, 0.40f, 29, tone.detail)
        // 水面 + 窗灯倒影
        water(canvas, w, h, horizon, tone)
        canvas.save()
        canvas.scale(1f, -1f, 0f, horizon)
        paint.color = withAlpha(tone.ridgeNear, 0x40)
        far.forEach { canvas.drawRect(it, paint) }
        near.forEach { canvas.drawRect(it, paint) }
        canvas.restore()
        // 倒影里的窗灯（暖黄的一条条）
        val rnd = Random(31337)
        paint.color = withAlpha(tone.detail, 0x66)
        repeat(60) {
            val r = near[rnd.nextInt(near.size)]
            val cy = horizon + rnd.nextFloat() * h * 0.20f
            canvas.drawRect(r.left + rnd.nextFloat() * r.width(), cy, r.left + rnd.nextFloat() * r.width() + w * 0.006f, cy + h * 0.004f, paint)
        }
    }

    /**
     * 一排楼。
     *
     * @return 每栋楼的矩形（倒影时要用）
     */
    private fun skyline(
        canvas: Canvas,
        w: Float,
        h: Float,
        horizon: Float,
        color: Int,
        minH: Float,
        maxH: Float,
        seed: Int,
        windowColor: Int,
    ): List<RectF> {
        val rnd = Random(seed)
        val out = ArrayList<RectF>()
        var x = -w * 0.02f
        while (x < w) {
            val bw = w * (0.018f + rnd.nextFloat() * 0.035f)
            val bh = h * (minH + rnd.nextFloat() * (maxH - minH))
            val r = RectF(x, horizon - bh, x + bw, horizon)
            out.add(r)
            paint.color = color
            canvas.drawRect(r, paint)
            // 楼顶天线
            if (rnd.nextFloat() < 0.25f) {
                canvas.drawRect(
                    RectF(x + bw * 0.45f, horizon - bh - h * 0.035f, x + bw * 0.55f, horizon - bh),
                    paint,
                )
            }
            // 窗灯
            if (windowColor != 0) {
                var wy = horizon - bh + h * 0.012f
                while (wy < horizon - h * 0.012f) {
                    var wx = r.left + w * 0.004f
                    while (wx < r.right - w * 0.006f) {
                        if (rnd.nextFloat() < 0.42f) {
                            paint.color = withAlpha(windowColor, 0x30 + rnd.nextInt(0x90))
                            canvas.drawRect(
                                RectF(wx, wy, wx + w * 0.0035f, wy + h * 0.006f),
                                paint,
                            )
                        }
                        wx += w * 0.009f
                    }
                    wy += h * 0.016f
                }
            }
            x += bw + w * (0.004f + rnd.nextFloat() * 0.010f)
        }
        return out
    }

    /** 云海松林：晨空 + 云海 + 前景松树。 */
    private fun cloudForest(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone) {
        val horizon = h * 0.50f
        sky(canvas, w, h, tone, horizon)
        glow(canvas, w * 0.34f, horizon - h * 0.24f, h * 0.22f, tone.sunGlow, 0x5C)
        disc(canvas, w * 0.34f, horizon - h * 0.24f, h * 0.038f, tone.sun)
        cloudBand(canvas, w, h, h * 0.22f, tone.haze, 0x30, 21)
        // 云海：四条由椭圆叠出来的云带
        for (i in 0 until 4) {
            cloudSeaBand(canvas, w, h, horizon + h * (0.015f + i * 0.065f), tone.detail, 0x5C - i * 8, 31 + i * 7)
        }
        // 前景松林
        pines(canvas, w, h, tone)
    }

    /** 一条云带：许多扁椭圆叠出来的效果。 */
    private fun cloudSeaBand(canvas: Canvas, w: Float, h: Float, y: Float, color: Int, alpha: Int, seed: Int) {
        val rnd = Random(seed)
        repeat(26) {
            val cx = rnd.nextFloat() * w
            val cy = y + (rnd.nextFloat() - 0.5f) * h * 0.035f
            val rx = w * (0.03f + rnd.nextFloat() * 0.075f)
            val ry = h * (0.008f + rnd.nextFloat() * 0.018f)
            paint.color = withAlpha(color, alpha)
            canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint)
        }
    }

    /** 前景松树：两排三角形的树冠。 */
    private fun pines(canvas: Canvas, w: Float, h: Float, tone: Wallpapers.Tone) {
        val rnd = Random(404)
        // 远排
        var x = -w * 0.02f
        while (x < w) {
            val tw = w * (0.016f + rnd.nextFloat() * 0.016f)
            val th = h * (0.07f + rnd.nextFloat() * 0.07f)
            val base = h * (0.86f + rnd.nextFloat() * 0.03f)
            val p = Path()
            p.moveTo(x + tw / 2f, base - th)
            p.lineTo(x + tw, base)
            p.lineTo(x, base)
            p.close()
            fillPath(canvas, p, Wallpapers.mix(tone.ridgeNear, tone.haze, 0.30f))
            x += tw * (0.7f + rnd.nextFloat() * 0.8f)
        }
        // 近排（更暗更高）
        x = -w * 0.02f
        while (x < w) {
            val tw = w * (0.022f + rnd.nextFloat() * 0.024f)
            val th = h * (0.12f + rnd.nextFloat() * 0.10f)
            val base = h * (1.02f + rnd.nextFloat() * 0.04f)
            val p = Path()
            p.moveTo(x + tw / 2f, base - th)
            p.lineTo(x + tw, base)
            p.lineTo(x, base)
            p.close()
            fillPath(canvas, p, tone.ridgeFront)
            x += tw * (0.55f + rnd.nextFloat() * 0.7f)
        }
    }

    /** 四角压暗一点，视线往中间收。 */
    private fun vignette(canvas: Canvas, w: Float, h: Float) {
        paint.alpha = 255
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.46f, max(w, h) * 0.72f,
            intArrayOf(0x00000000, 0x14000000),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** 给颜色套一个 alpha（不改动 RGB）。 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
