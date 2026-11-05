package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.cardesk.launcher.core.CardStyle
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.ui.OverlaySpec

/**
 * 模态浮层的**背景板**：设计板 `.bgwash` + `.scrim` 两层，**不透明**。
 *
 * ## 为什么浮层要自带背景板
 *
 * 设计板 ~（空调 / 设置 / 计划任务 / 应用选择 / 强调色 / 按键 / 槽位）
 * 每一屏的构成都是同一个公式：
 *
 * `
 * <div class="bgwash"></div> ← 纯色底 + 顶部椭圆径向渐变
 * <div class="abs" style="...background:var(--scrim)"></div> ← 全屏遮罩
 * <div class="abs card"...>…</div> ← 面板
 * `
 *
 * 也就是说**浮层是独立的模态屏**：遮罩下面是干净的背景，
 * 不是被压暗的桌面。之前实现只用一层半透明遮罩盖在桌面上，
 * 于是状态栏 / Dock / 桌面卡片会从遮罩后面透出来 —— 与设计板完全不是一回事。
 *
 * 这里把「`.bgwash` + `.scrim`」合成一层**不透明**背景板浮在桌面之上：
 * 桌面内容被完全遮住，露出来的颜色与设计板逐像素一致
 * （`.bgwash` `BackgroundView` 的径向渐变，`.scrim` 用 `palette.scrim`）。
 */
class OverlayBackdrop(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 是否再叠一层 `.scrim` 遮罩。
     *
     * - `true`（默认）：模态浮层（~）—— `.bgwash` + `.scrim`；
     * - `false`：整页浮层（全部应用）—— **只有 `.bgwash`**，设计板那一屏没有遮罩。
     */
    var withScrim: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 是否**透出桌面**（第 124 轮）。
     *
     * ## 背景
     *
     * 第五十轮起，浮层背景板是 `.bgwash` + `.scrim` 两层**不透明**色块 ——
     * 照设计板 `s05-settings` 做的「独立模态屏」：遮罩下面是干净的背景，
     * 不是被压暗的桌面。
     *
     * 用户后来要求（第 124 轮）：
     *
     * > 设置页主体外的留白不会透到桌面吗？把它改成透到桌面
     *
     * 也就是说**面板主体之外的那圈留白**应该能看见底下的桌面，
     * 而不是一整块死色。
     *
     * ## 怎么做到的
     *
     * 把 `.bgwash` 与 `.scrim` 两层都改成半透明
     * （`.bgwash` 用 [OverlaySpec.THROUGH_BG_ALPHA]，
     * `.scrim` 用 [OverlaySpec.THROUGH_SCRIM_SCALE] 缩一半）：
     *
     * - **面板主体**那张卡是 `Skin.flat(Shape.CARD)` → `palette.surface`，
     *   **不透明** —— 主体里的字照样清楚；
     * - **主体外的留白** → 桌面透出来。
     *
     * 不用 drawable、不改布局，只改背景板这两层的 alpha。
     */
    var seeThrough: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // —— `.bgwash`：纯色底 + 顶部中线偏心的椭圆径向渐变 ——
        // 透出桌面（第124轮）时底色改成半透明；
        // 两个端点用**同一个** alpha，渐变过程中 alpha 恒定，不会出现脏边。
        val bgAlpha = if (seeThrough) OverlaySpec.THROUGH_BG_ALPHA else 0xFF
        paint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                palette.withAlpha(palette.bgGlow, bgAlpha),
                palette.withAlpha(palette.bgTop, bgAlpha),
            ),
            floatArrayOf(0f, BackgroundView.GLOW_STOP),
            Shader.TileMode.CLAMP,
        ).apply {
            val m = Matrix()
            m.setScale(w * 1.2f, h * 0.9f)
            m.postTranslate(w * 0.5f, 0f)
            setLocalMatrix(m)
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // —— `.scrim`：全屏遮罩 ——
        if (withScrim) {
            paint.color = if (seeThrough) {
                // 背景板透明之后，`.scrim` 就变成「压暗桌面」了 —— 必须一起减弱。
                CardStyle.scaleAlpha(palette.scrim, OverlaySpec.THROUGH_SCRIM_SCALE)
            } else {
                palette.scrim
            }
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}
