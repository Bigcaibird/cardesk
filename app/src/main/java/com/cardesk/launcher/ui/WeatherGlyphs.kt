package com.cardesk.launcher.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 天气图标（自绘）。
 *
 * 第四轮起**场景页天气卡**与**桌面状态栏**共用这一份实现：
 * 之前只有 `ScenePage.drawWeatherIcon` 一份私有方法，
 * 状态栏要显示天气就得再画一遍，容易两边对不上。
 *
 * 图形语义（都是 24×24 视觉框内）：
 *
 * | 天气文案包含 | 图标 |
 * | --- | --- |
 * | `雷` | 闪电 |
 * | `雪` | 三点雪花 |
 * | `雨` | 云 + 两道雨线 |
 * | `雾` / `霾` / `沙` | 三条横线 |
 * | `晴` | 太阳（圆 + 8 道光芒） |
 * | 其它 | 云（弧线） |
 */
object WeatherGlyphs {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = RectF()

    /**
     * 画一个天气图标。
     *
     * @param cx 图标中心 x
     * @param cy 图标中心 y
     * @param sky 天气文案（`WeatherDesk.KEY_SKY`）
     * @param tint 图标颜色（一般跟文字同色）
     * @param strokeWidth 描边宽度（设计 px）
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, sky: String, tint: Int, strokeWidth: Float = 2f) {
        stroke.color = tint
        stroke.strokeWidth = strokeWidth
        fill.color = tint
        when {
            sky.contains("雷") -> {
                val p = Path().apply {
                    moveTo(cx + 2f, cy - 9f)
                    lineTo(cx - 5f, cy + 1f)
                    lineTo(cx + 1f, cy + 1f)
                    lineTo(cx - 2f, cy + 9f)
                    lineTo(cx + 6f, cy - 2f)
                    lineTo(cx, cy - 2f)
                    close()
                }
                canvas.drawPath(p, fill)
            }
            sky.contains("雪") -> {
                canvas.drawCircle(cx - 5f, cy - 4f, 1.8f, fill)
                canvas.drawCircle(cx + 5f, cy - 4f, 1.8f, fill)
                canvas.drawCircle(cx, cy + 5f, 1.8f, fill)
            }
            sky.contains("雨") -> {
                canvas.drawArc(RectF(cx - 9f, cy - 9f, cx + 9f, cy + 5f), 200f, 140f, false, stroke)
                canvas.drawLine(cx - 4f, cy + 6f, cx - 6f, cy + 10f, stroke)
                canvas.drawLine(cx + 2f, cy + 6f, cx, cy + 10f, stroke)
            }
            sky.contains("雾") || sky.contains("霾") || sky.contains("沙") -> {
                canvas.drawLine(cx - 9f, cy - 4f, cx + 9f, cy - 4f, stroke)
                canvas.drawLine(cx - 7f, cy + 1f, cx + 9f, cy + 1f, stroke)
                canvas.drawLine(cx - 9f, cy + 6f, cx + 7f, cy + 6f, stroke)
            }
            sky.contains("晴") -> {
                canvas.drawCircle(cx, cy, 5.5f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    canvas.drawLine(
                        cx + (Math.cos(a) * 8f).toFloat(), cy + (Math.sin(a) * 8f).toFloat(),
                        cx + (Math.cos(a) * 11f).toFloat(), cy + (Math.sin(a) * 11f).toFloat(),
                        stroke,
                    )
                }
            }
            else -> {
                tmp.set(cx - 9f, cy - 8f, cx + 9f, cy + 6f)
                canvas.drawArc(tmp, 200f, 140f, false, stroke)
            }
        }
    }
}
