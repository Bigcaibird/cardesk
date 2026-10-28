package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 无 XML 的界面搭建小工具。所有尺寸都按 1920×1080 设计稿像素写。
 */
object Ui {

    fun text(ctx: Context, s: CharSequence, px: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    fun round(color: Int, radius: Float, strokeWidth: Float = 0f, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0f) setStroke(strokeWidth.toInt().coerceAtLeast(1), strokeColor)
        }

    /** FrameLayout 里的绝对定位参数。 */
    fun frame(
        w: Int,
        h: Int,
        gravity: Int = Gravity.TOP or Gravity.START,
        left: Int = 0,
        top: Int = 0,
    ): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h, gravity).apply {
        leftMargin = left
        topMargin = top
    }

    /** LinearLayout 的子项参数。 */
    fun linear(w: Int, h: Int, weight: Float = 0f, marginRight: Int = 0, marginTop: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            rightMargin = marginRight
            topMargin = marginTop
        }

    fun margin(v: android.view.View, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): android.view.View {
        val lp = v.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.setMargins(l, t, r, b)
            v.layoutParams = lp
        }
        return v
    }
}
