package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Wallpapers
import kotlin.math.max

/**
 * 卡片风格**预览**（设置页「卡片风格」用）。
 *
 * 画的是一张**样例卡片**，用的就是当前生效的 token：
 * 卡面（`surface`）、卡内两层（`surfaceStrong` / `surfaceSoft`）、描边、圆角。
 *
 * 底下铺的是**当前壁纸**（16:9 居中裁切）——
 * 卡片半透明时壁纸从这里透出来，所见即所得。
 *
 * 配色与圆角都是**外部塞进来的**（不直接读全局 token），
 * 这样设置页里改参数时能立刻看到效果，而不用等全局生效。
 */
class CardStylePreviewView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 预览用的配色（卡面 / 两层内嵌都从这里取）。 */
    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    /** 预览用的卡片圆角（设计坐标 px）。 */
    var radius: Float = Shape.CARD
        set(value) {
            field = value
            invalidate()
        }

    /** 预览用的壁纸下标（铺在样例卡片后面）。 */
    var wallpaperIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
    }

    /** 刷新（改完透明度 / 颜色 / 圆角后调一下）。 */
    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawWallpaperBackdrop(canvas, w, h)

        // 样例卡片（偏右摆：左边留一条壁纸，卡片的半透明才看得出来）
        val card = RectF(w * 0.30f, 26f, w - 26f, h - 26f)
        paint.color = palette.surface
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = palette.stroke
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.style = Paint.Style.FILL

        // 卡内两层：选中槽（surfaceStrong）+ 内嵌块（surfaceSoft）
        val inner = RectF(card.left + 28f, card.top + 28f, card.left + 28f + 180f, card.bottom - 28f)
        paint.color = palette.surfaceStrong
        canvas.drawRoundRect(inner, radius * (18f / 28f), radius * (18f / 28f), paint)

        val chip = RectF(inner.right + 24f, inner.top, inner.right + 24f + 140f, inner.top + 72f)
        paint.color = palette.surfaceSoft
        canvas.drawRoundRect(chip, radius * (16f / 28f), radius * (16f / 28f), paint)

        // 强调色小块（强调色不受卡片风格影响，这里做对照）
        val dot = RectF(chip.right + 24f, inner.centerY() - 26f, chip.right + 24f + 52f, inner.centerY() + 26f)
        paint.color = palette.accent
        canvas.drawRoundRect(dot, radius * (16f / 28f), radius * (16f / 28f), paint)

        // 半透明卡片自己再补一道淡描边，轮廓更清楚
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(0x40, 0xFF, 0xFF, 0xFF)
        canvas.drawRoundRect(card, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /** 壁纸铺底：16:9 居中裁切（cover）。 */
    private fun drawWallpaperBackdrop(canvas: Canvas, w: Float, h: Float) {
        val spec = Wallpapers.spec(wallpaperIndex)
        val bw = 960f
        val bh = 540f
        val scale = max(w / bw, h / bh)
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        canvas.translate((w - bw * scale) / 2f, (h - bh * scale) / 2f)
        canvas.scale(scale, scale)
        WallpaperRenderer.draw(canvas, spec, bw, bh, palette.night)
        canvas.restore()
    }
}
