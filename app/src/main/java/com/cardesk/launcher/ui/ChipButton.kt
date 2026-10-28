package com.cardesk.launcher.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.cardesk.launcher.core.Palette

/**
 * 面板里的圆角开关按钮：文字 + 可选副标题，激活态用主题色描边。
 */
class ChipButton(context: Context, private val palette: Palette, textPx: Float = 24f) : TextView(context) {

    var active: Boolean = false
        private set

    private var label: String = ""

    init {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        setPadding(20, 10, 20, 10)
    }

    fun bind(text: String, active: Boolean) {
        label = text
        this.text = text
        this.active = active
        refresh()
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        refresh()
    }

    fun label(): String = label

    private fun refresh() {
        // 第四十九轮「浮屿」：激活态 = 强调色软底 + 强调色描边 + 强调色文字；
        // 未激活 = 内嵌块底 + 1px 描边 + 弱化文字。
        setTextColor(if (active) palette.accent else palette.textDim)
        background = Ui.round(
            if (active) palette.accentSoft else palette.surfaceSoft,
            22f,
            1f,
            if (active) palette.accent else palette.stroke,
        )
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    companion object {
        fun make(context: Context, palette: Palette, text: String, active: Boolean = false): ChipButton =
            ChipButton(context, palette).apply { bind(text, active) }
    }
}

/** 给 View 挂一个点击时的缩放反馈，模拟车机的"按键回弹"手感。 */
fun View.pressFeedback() {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start()
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        }
        false
    }
}
