package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.Skin

/**
 * 频谱条视图（第七十轮）。
 *
 * 把 [com.cardesk.launcher.core.audio.SpectrumBands] 算出来的频段能量画成一排竖条：
 * 左边是低频（鼓点），右边是高频（镲片）。
 *
 * 用在「设置 → 氛围灯」页 —— 让用户**看得见**频谱确实在动，
 * 而不是只看到一个「不可用」。
 *
 * ## 画法
 *
 * - 每段一根竖条，条高 = 能量 × 可用高度；
 * - 能量 0 时画一条**基线**（不是完全不画），否则整条看着像坏了；
 * - 条与条之间留一点缝，底色用 `Skin.surfaceSoft`。
 */
class SpectrumView(context: Context) : View(context) {

    /** 频段能量（0..1）。 */
    var bands: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    /** 整体电平（0..1）—— 画一条底部高亮线。 */
    var level: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** 条的颜色；0 = 用主题强调色。 */
    var barColor: Int = 0

    /** 条之间的间距比例（相对一根条的宽度）。 */
    var gapRatio: Float = 0.35f

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 底板
        paint.color = Skin.surfaceSoft
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, paint)

        val data = bands
        val n = data.size
        if (n <= 0) {
            paint.color = 0x33FFFFFF
            canvas.drawRect(12f, h - 3f, w - 12f, h - 1f, paint)
            return
        }

        val pad = 12f
        val usableW = w - pad * 2
        val usableH = h - pad * 2
        val slot = usableW / n
        val barW = (slot / (1f + gapRatio)).coerceAtLeast(2f)
        val color = if (barColor != 0) barColor else Skin.ACCENT

        for (i in 0 until n) {
            val v = data[i].coerceIn(0f, 1f)
            // 最少 3px：不画的话整条看着像坏了
            val barH = (usableH * v).coerceAtLeast(3f)
            val left = pad + i * slot
            rect.set(left, h - pad - barH, left + barW, h - pad)
            paint.color = color
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }

        // 电平线：整体有多响
        paint.color = color
        paint.alpha = 90
        val lv = (usableH * level.coerceIn(0f, 1f)).coerceAtLeast(2f)
        canvas.drawRect(pad, h - pad - lv, w - pad, h - pad - lv + 2f, paint)
        paint.alpha = 255
    }
}
