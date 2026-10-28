package com.cardesk.launcher.ui.pages

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.ui.Ui

/**
 * 页面里反复出现的卡片 / 文本构件。
 * 全部按 1920×1080 设计坐标书写，由 LauncherView 统一缩放。
 */
object Cards {

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 一张玻璃质感卡片。 */
    fun card(ctx: Context, pal: Palette, radius: Float = 30f): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(pal.surface, radius, 1.5f, pal.stroke)
            setPadding(30, 26, 30, 26)
        }

    fun caption(ctx: Context, pal: Palette, s: CharSequence, size: Float = 22f): TextView =
        Ui.text(ctx, s, size, pal.textDim)

    fun value(ctx: Context, pal: Palette, s: CharSequence, size: Float = 52f, color: Int = pal.text): TextView =
        Ui.text(ctx, s, size, color, bold = true)

    fun unit(ctx: Context, pal: Palette, s: CharSequence): TextView =
        Ui.text(ctx, s, 22f, pal.textFaint)

    fun gap(ctx: Context, h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    fun vgap(ctx: Context, w: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    /** 键值对一行。 */
    fun kv(ctx: Context, pal: Palette, key: String, value: String): LinearLayout =
        row(ctx).apply {
            addView(caption(ctx, pal, key, 24f))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Ui.text(ctx, value, 26f, pal.text, bold = true))
        }
}
