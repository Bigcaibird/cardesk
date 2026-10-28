package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Wallpapers
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 桌面背景：**壁纸层**。
 *
 * ## 第五十七轮的变化
 *
 * 第四十九轮「浮屿」把壁纸改成了「纯色底 + 一层极淡的柔和渐变」，
 * 默认壁纸等于**没有壁纸**（用户反馈：「默认壁纸不要用纯色的，用风景、拟物类的」）。
 *
 * 现在壁纸恢复成**程序化绘制的风景**（雪峰 / 雪湖 / 极光 / 沙丘 / 城市 / 云海松林）：
 * - 名字与顺序（前 8 张连名字都一样，第 9 张把
 *   「液态渐变」换成风景类的「云海松林」
 * - **不复制图片资源**，全部是本项目自绘的 Canvas 画面
 * - 落盘键 `design_wallpaper_index`（见 `Prefs.wallpaper`）。
 *
 * 具体怎么画见 [WallpaperRenderer]。
 */
class BackgroundView(context: Context) : View(context) {

    companion object {
        /**
         * 壁纸名（顺序 =）。
         *
         * 数据源在 [Wallpapers]，场景页的提示条圆点用的是同一份（`ScenePage.WALLPAPER_NAMES`）。
         */
        val NAMES: Array<String> = Wallpapers.NAMES.toTypedArray()

        /** 换壁纸的触发阈值（设计坐标 px， `m1183df(160f)`）。 */
        const val DRAG_THRESHOLD = 160f

        /** 渐变在 62% 处收敛回纯底色（设计板 `.bgwash` 的 radial-gradient 停靠点）。 */
        const val GLOW_STOP = 0.62f
    }

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var wallpaper: Int = Wallpapers.DEFAULT_INDEX
        set(value) {
            val next = Wallpapers.normalize(value)
            val changed = next != field
            field = next
            // 排障场景：「壁纸切了但还是上一张」——壁纸下标变化落盘。
            if (changed) {
                Logx.event(
                    LogTags.SCENE,
                    "壁纸切换",
                    "index" to next,
                    "name" to Wallpapers.name(next),
                )
            }
            invalidate()
        }

    /**
     * 拖拽偏移（**设计坐标** px）。
     *
     *
     * 横向拖动时壁纸整体平移，并露出相邻的上一张 / 下一张。
     */
    var dragOffset: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** 壁纸切换回调（参数是新的壁纸下标）。 */
    var onWallpaperChanged: ((Int) -> Unit)? = null

    private var settleAnim: android.animation.ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val off = dragOffset * (w / com.cardesk.launcher.core.Design.W)
        if (off == 0f) {
            drawWallpaper(canvas, wallpaper, w, h)
            return
        }
        // 拖拽中：并排画「上一张 / 当前 / 下一张」
        // （blit 三连）
        val n = Wallpapers.COUNT
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        drawWallpaperAt(canvas, (wallpaper - 1 + n) % n, w, h, off - w)
        drawWallpaperAt(canvas, wallpaper, w, h, off)
        drawWallpaperAt(canvas, (wallpaper + 1) % n, w, h, off + w)
        canvas.restore()
    }

    private fun drawWallpaperAt(canvas: Canvas, index: Int, w: Float, h: Float, offsetX: Float) {
        canvas.save()
        canvas.translate(offsetX, 0f)
        drawWallpaper(canvas, index, w, h)
        canvas.restore()
    }

    private fun drawWallpaper(canvas: Canvas, index: Int, w: Float, h: Float) {
        // 底色兜底：拖拽时两张壁纸之间会露出缝隙，先铺一层桌面底色。
        canvas.drawColor(palette.bgTop)
        WallpaperRenderer.draw(canvas, Wallpapers.spec(index), w, h, palette.night)
    }

    /**
     * 结束壁纸拖拽。
     *
     * - 横向位移 ≤ -160px：切到**下一张**（壁纸向左滑出）；
     * - 横向位移 ≥ +160px：切到**上一张**；
     * - 不足阈值：回弹，不换壁纸。
     *
     * 收尾动画 450ms，插值器 (0.2, 0.8, 0.2, 1)，一致。
     *
     * @return 切换步进（+1 下一张 / -1 上一张 / 0 未切换）
     */
    fun endDrag(delta: Float): Int {
        settleAnim?.cancel()
        settleAnim = null
        val step = when {
            delta <= -DRAG_THRESHOLD -> 1
            delta >= DRAG_THRESHOLD -> -1
            else -> 0
        }
        // 排障场景：「壁纸拖了没换 / 换错了方向」——收尾位移与步进落盘。
        Logx.event(
            LogTags.SCENE,
            "壁纸拖拽收尾",
            "delta" to delta,
            "step" to step,
            "from" to wallpaper,
        )
        // 收尾目标也是设计坐标：下一张 → -1920；上一张 → +1920（endDrag:343-357）
        val target = when (step) {
            1 -> -com.cardesk.launcher.core.Design.W
            -1 -> com.cardesk.launcher.core.Design.W
            else -> 0f
        }
        val anim = android.animation.ValueAnimator.ofFloat(delta, target)
        anim.duration = 450L
        anim.interpolator = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        anim.addUpdateListener { dragOffset = it.animatedValue as Float }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                settleAnim = null
                dragOffset = 0f
                if (step != 0) {
                    wallpaper = Wallpapers.next(wallpaper)
                    // 排障场景：「壁纸切了但界面没变」——最终落定的壁纸落盘。
                    Logx.event(
                        LogTags.SCENE,
                        "壁纸拖拽切到目标",
                        "index" to wallpaper,
                        "name" to Wallpapers.name(wallpaper),
                        "step" to step,
                    )
                    onWallpaperChanged?.invoke(wallpaper)
                }
            }
        })
        settleAnim = anim
        anim.start()
        return step
    }

    /** 取消收尾动画（新的拖拽开始时调用）。 */
    fun cancelSettle() {
        settleAnim?.cancel()
        settleAnim = null
        dragOffset = 0f
    }
}
