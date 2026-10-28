package com.cardesk.launcher.ui.shell

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 增强导航栏容器， （证据：06-nav-dialogs-hud.md F01/F02）。
 *
 * 是一个贴底的定高悬浮窗（`gravity=87`、`match_parent × resolveHeightPx`）
 * 窗口里再放一个 FitStrip 把 1864×124 的设计 Dock 整体缩放居中；这里用同一套「pill + 缩放」结构，
 * 但不建悬浮窗（本应用是 HOME 应用，Dock 就在自己窗口里）。
 *
 * - [mode] = [MODE_DOCK] 时承载设计版 Dock（FitStrip：scale = min(w/1864, h/124)，居中）
 * - [mode] = [MODE_SLOTS] 时承载槽位条 [NavSlotBarView]
 */
class EnhancedNavBarView(context: Context) : FrameLayout(context) {

    companion object {
        const val MODE_DOCK = 0
        const val MODE_SLOTS = 1
        const val DESIGN_W = 1864f
        const val DESIGN_H = 124f
    }

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            setBackgroundDrawable(Skin.glass(40f, strong = true))
            invalidate()
        }

    private var host: FrameLayout? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** 放入设计版 Dock，并模拟 FitStrip 的缩放居中。 */
    fun mountDock(dock: android.view.View) {
        val stable = dock.parent as? FrameLayout
        if (stable !== this) {
            stable?.removeView(dock)
            val wrap = FrameLayout(context).apply { clipChildren = false }
            wrap.addView(dock, LayoutParams(DESIGN_W.toInt(), DESIGN_H.toInt(), Gravity.TOP or Gravity.START))
            addView(wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            host = wrap
            // 排障场景：「增强导航栏里 Dock 不显示 / 尺寸不对」——挂载模式变化落盘。
            Logx.event(LogTags.DOCK, "增强导航栏挂载 Dock", "mode" to "dock")
        }
        post { fit() }
    }

    fun mountSlots(slots: NavSlotBarView) {
        removeAllViews()
        host = null
        addView(slots, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).also {
            //  navBarSlotContainer
            // paddingStart/End = navbar_padding_x（12dp，1920 画布下按设计 px）
            it.leftMargin = 12
            it.rightMargin = 12
        })
        // 排障场景：「增强导航栏切到槽位条后 Dock 不见了」——模式切换落盘。
        Logx.event(LogTags.DOCK, "增强导航栏挂载槽位条", "mode" to "slots")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    /** FitStrip.onLayout：子层永远按 1864×124 设计 px 布局，整体 scale + translation 居中。 */
    private fun fit() {
        val wrap = host ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val scale = minOf(w / DESIGN_W, h / DESIGN_H)
        wrap.pivotX = 0f
        wrap.pivotY = 0f
        wrap.scaleX = scale
        wrap.scaleY = scale
        wrap.translationX = (w - DESIGN_W * scale) / 2f
        wrap.translationY = (h - DESIGN_H * scale) / 2f
    }
}
