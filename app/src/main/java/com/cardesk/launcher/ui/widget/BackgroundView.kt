package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.cardesk.launcher.core.CustomWallpaper
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

        /**
         * 设计稿 L0 的**底色覆盖度**（第243轮）。
         *
         * 画板 `Main.png` 的底色实测是「左近白 `#F4F8FA` → 右上青 `#A8DDDD` →
         * 左下蓝 `#E1EDF7`」的柔和渐变，壁纸只在下面留一点纹理。
         * 用户壁纸（风景 / 视频 / 自选照片）直接铺满时，玻璃卡会读出灰调（深色照片上
         * 尤其明显），与画板的通透明亮完全不是一回事。
         *
         * 所以设计稿的 L0 被实现成**一层压在壁纸上的渐变**：
         * 白天 95% 的 `bgTop→bgBottom`（壁纸只留 5% 的纹理），夜间 89%；
         * 再叠两处柔光（青 `#00C2B2` 右上、蓝 `#0E8FD8` 左下）。
         *
         * 为什么这么重：上一版是 88% / 85%，实机上深色风景壁纸的山脊仍然清晰可辨，
         * 卡片透出来就是灰的（截图 `/tmp/v-home-final.png` 左下角），与画板
         * 「近白的柔和渐变」差得很远。画板里壁纸只是**纹理**，不是画面主体。
         */
        const val WASH_DAY = 0xFA
        const val WASH_NIGHT = 0xF7

        /** 设计稿的两处柔光色（`验收包.md` §1 L0）。 */
        const val GLOW_TEAL = 0xFF00C2B2.toInt()

        /** 左上近白柔光（画板 L0 的第一处光，纯白）。 */
        const val GLOW_WHITE = 0xFFFFFFFF.toInt()
        const val GLOW_BLUE = 0xFF0E8FD8.toInt()

        /**
         * 右上柔光：设计稿 `#00C2B2`。
         *
         * 值是**峰值 alpha**，到 [GLOW_STOP] 线性收敛到 0。第 244 轮按画板逐点反解：
         * 画板 (1700,100)=(128,217,209)、(1400,100)=(155,222,217)，
         * 说明峰值需要 ~98%（白天），否则整屏发白、青绿光晕只剩一层灰
         * （独立验收 P1-1：实测 (197,232,232) vs 画板 (128,217,209)）。
         */
        const val GLOW_TEAL_DAY = 0xA8
        const val GLOW_TEAL_NIGHT = 0x7A

        /** 左下柔光：设计稿 `#0E8FD8`，白天 26% / 夜间 17%（画板 (300,1000) 偏蓝）。 */
        const val GLOW_BLUE_DAY = 0x42

        /** 左上白光的峰值 alpha（日 / 夜）。 */
        const val GLOW_WHITE_DAY = 0xB0
        const val GLOW_WHITE_NIGHT = 0x1A
        const val GLOW_BLUE_NIGHT = 0x2B

        /** 亮度采样代理图的宽度（第222轮；壁纸几何全按比例算 ⇒ 小图构图一致）。 */
        const val PROXY_W = 192

        /** 亮度采样代理图的高度（第222轮）。 */
        const val PROXY_H = 108

        /**
         * 绘制**默认日夜模式壁纸**（L0 流光渐层：浅青白/深夜蓝底 + 右上青/左下蓝/左上微白三处柔光）。
         *
         * 桌面主背景、玻璃模糊底图、设置页「默认日夜壁纸」缩略图与大图预览共用这一套实现，
         * 保证所见即所得。
         */
        fun drawDefaultDayNight(
            canvas: Canvas,
            palette: Palette,
            w: Float,
            h: Float,
            washPaint: Paint = Paint(Paint.DITHER_FLAG),
            glowPaint: Paint = Paint(Paint.DITHER_FLAG),
            opaqueBase: Boolean = true,
        ) {
            if (w <= 0f || h <= 0f) return
            val night = palette.night
            val baseAlpha = if (opaqueBase) 0xFF else if (night) WASH_NIGHT else WASH_DAY
            washPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                palette.withAlpha(palette.bgTop, baseAlpha),
                palette.withAlpha(palette.bgBottom, baseAlpha),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, washPaint)

            // 左上近白柔光
            glowPaint.shader = RadialGradient(
                w * 0.00f, h * 0.06f, w * 0.52f,
                intArrayOf(
                    palette.withAlpha(GLOW_WHITE, if (night) GLOW_WHITE_NIGHT else 0x33),
                    palette.withAlpha(GLOW_WHITE, 0),
                ),
                floatArrayOf(0f, 0.72f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, glowPaint)

            // 右上青绿柔光
            val teal = if (night) GLOW_TEAL_NIGHT else 0x8C
            glowPaint.shader = RadialGradient(
                w * 0.89f, h * 0.01f, w * 0.42f,
                intArrayOf(
                    palette.withAlpha(GLOW_TEAL, teal),
                    palette.withAlpha(GLOW_TEAL, (teal * 0.55f).toInt()),
                    palette.withAlpha(GLOW_TEAL, 0),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, glowPaint)

            // 左下蓝柔光
            val blue = if (night) GLOW_BLUE_NIGHT else 0x3D
            glowPaint.shader = RadialGradient(
                w * 0.10f, h * 0.98f, w * 0.55f,
                intArrayOf(
                    palette.withAlpha(GLOW_BLUE, blue),
                    palette.withAlpha(GLOW_BLUE, (blue * 0.5f).toInt()),
                    palette.withAlpha(GLOW_BLUE, 0),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, glowPaint)
            glowPaint.shader = null
            washPaint.shader = null
        }
    }

    /** L0 的两支画笔（[drawDesignWash] 用；每帧复用，避免在 onDraw 里 new）。 */
    private val washPaint = Paint(Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.DITHER_FLAG)

    var palette: Palette = Palettes.night
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            // 日夜换了 → 静态壁纸的配色也换了（day / night 两套色调）→ 亮度要重算。
            refreshBackdrop()
        }

    var wallpaper: Int = Wallpapers.DEFAULT_INDEX
        set(value) {
            val next = Wallpapers.normalize(value)
            // 第九批（设置页闪屏）：值没变就**整段跳过**。
            //
            // 设置页里改任何开关都会把壁纸下标重新赋一遍（见
            // LauncherView.onSettingsApplied），而这个 setter 会触发
            // refreshBackdrop() → refreshGlass()：主线程画 192×108 代理图 +
            // 480×270 玻璃底图，车机上几十毫秒 —— 白掉一帧。
            //
            // 壁纸没换时画面本来就应该原封不动，跳过是安全的；
            // 日夜换色走 palette setter（它自己会重算），不受这里影响。
            if (next == field) return
            field = next
            // 排障场景：「壁纸切了但还是上一张」——壁纸下标变化落盘。
            Logx.event(
                LogTags.SCENE,
                "壁纸切换",
                "index" to next,
                "name" to Wallpapers.name(next),
            )
            invalidate()
            // 换纸 → 浮层墨色要跟着换（第222轮）。
            refreshBackdrop()
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

    /**
     * 是否使用**默认日夜壁纸**（L0 流光渐层，随日夜模式自动切换）。
     *
     * 为 true 且未使用自定义照片时，整屏绘制默认日夜流光底；
     * 选了 9 张风景预设或自定义照片时置 false，让所选壁纸清晰呈现。
     */
    var defaultDayNight: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            refreshBackdrop()
        }

    /**
     * 是否显示**自定义壁纸**（第 105 轮：用户导入的照片）。
     *
     * 为 true 时 [wallpaper] 只是回退值：删掉自定义壁纸 / 下次打开预设就用它。
     */
    var custom: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            // 换壁纸类型 → 亮度要重算（第222轮）。
            refreshBackdrop()
        }

    /** 自定义壁纸在夜间是否使用独立夜间照片（为 true 时不再叠加人工夜间压暗 scrim）。 */
    var customUsesNightAsset: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            refreshBackdrop()
        }

    /**
     * 自定义壁纸位图。
     *
     * 解码在后台线程（见 [CustomWallpaper.loadScaled]），完成后由 LauncherView 塞进来；
     * null 表示还没解好 / 没有，此时只铺桌面底色。
     */
    var customBitmap: Bitmap? = null
        set(value) {
            // 第九批（设置页闪屏）：同一张位图重复赋值不重算。
            // 设置页每次应用设置都会把 CustomWallpaper.cachedBitmap() 再塞一遍，
            // 而它返回的是缓存里的**同一个实例**，图没换就不该重算整屏底图。
            if (field === value) return
            field = value
            invalidate()
            // 图解好了 → 才能采它的亮度（第222轮）。
            refreshBackdrop()
        }

    /** 壁纸切换回调（参数是新的壁纸下标）。 */
    var onWallpaperChanged: ((Int) -> Unit)? = null

    // ------------------------------------------------------------------
    // 壁纸亮度（第222轮）：浮层墨色按壁纸明暗切换
    // ------------------------------------------------------------------

    /**
     * 壁纸在**顶部浮层区** / **屏幕中部**的平均颜色。
     *
     * null = 还不知道（跟着主题走）。
     * 由 [refreshBackdrop] 计算，算完通过 [onBackdropChanged] 抛给上层。
     */
    var backdropTop: Int? = null
        private set

    var backdropCenter: Int? = null
        private set

    /** 壁纸亮度变了（上层要重算浮层墨色并重绘）。 */
    var onBackdropChanged: (() -> Unit)? = null

    /**
     * 重算壁纸亮度（第222轮）。
     *
     * ## 什么时候调
     *
     * **只在壁纸 / 日夜变化时调**，不是每帧 ——
     * 采样要读位图像素（一次几万次 `getPixels`），每帧读会拖慢桌面。
     *
     * ## 两种壁纸各走各的采样源
     *
     * | 壁纸 | 采样源 |
     * |---|---|
     * | 静态预设 | 现画一张 **192×108 代理图**（几何等比 ⇒ 构图一致） |
     * | 自定义照片 | 直接采**照片本身**（夜间算上压暗系数） |
     *
     * 视频壁纸这一层什么都不画（画面在独立的 SurfaceView 上），
     * 由 `LauncherView` 采样视频帧后单独塞进来。
     */
    fun refreshBackdrop() {
        if (videoPassthrough) {
            // 视频壁纸：这一层让路了，亮度由上层（视频帧采样）负责。
            publishBackdrop(null, null)
            // 玻璃材质没有静态背景可取 ⇒ 退化成纯半透明（视频帧没法预模糊）。
            com.cardesk.launcher.core.Glass.clear()
            return
        }
        val w = width.toInt()
        val h = height.toInt()
        if (w <= 0 || h <= 0) {
            publishBackdrop(null, null)
            return
        }
        val night = palette.night
        when {
            custom -> {
                val bmp = customBitmap
                if (bmp == null || bmp.isRecycled) {
                    publishBackdrop(null, null)
                    return
                }
                // 自定义壁纸：按「等比填满 + 居中裁切」的实际映射换算源图区域。
                val topRect = com.cardesk.launcher.core.WallpaperSampler.sourceRectFor(
                    bmp.width, bmp.height, w.toFloat(), h.toFloat(),
                    com.cardesk.launcher.core.WallpaperSampler.TOP_REGION,
                )
                val centerRect = com.cardesk.launcher.core.WallpaperSampler.sourceRectFor(
                    bmp.width, bmp.height, w.toFloat(), h.toFloat(),
                    com.cardesk.launcher.core.WallpaperSampler.CENTER_REGION,
                )
                val top = com.cardesk.launcher.core.WallpaperSampler.averageColorInRect(
                    bmp, topRect, night, 0.10f,
                )
                val center = com.cardesk.launcher.core.WallpaperSampler.averageColorInRect(
                    bmp, centerRect, night, 0.48f,
                )
                publishBackdrop(top, center)
            }

            else -> {
                val proxy = proxyBitmap()
                if (proxy == null) {
                    publishBackdrop(null, null)
                    return
                }
                try {
                    val canvas = android.graphics.Canvas(proxy)
                    if (defaultDayNight) {
                        drawDefaultDayNight(canvas, palette, proxy.width.toFloat(), proxy.height.toFloat(), washPaint, glowPaint, opaqueBase = true)
                    } else {
                        WallpaperRenderer.drawDirect(
                            canvas, Wallpapers.spec(wallpaper), proxy.width.toFloat(), proxy.height.toFloat(), night,
                        )
                    }
                    publishBackdrop(
                        com.cardesk.launcher.core.WallpaperSampler.averageColor(
                            proxy, com.cardesk.launcher.core.WallpaperSampler.TOP_REGION, false,
                        ),
                        com.cardesk.launcher.core.WallpaperSampler.averageColor(
                            proxy, com.cardesk.launcher.core.WallpaperSampler.CENTER_REGION, false,
                        ),
                    )
                } finally {
                    proxy.recycle()
                }
            }
        }
        refreshGlass()
    }

    /**
     * 背景**最底部一条**的底色（**不含**任何蒙层，就是壁纸本身）。
     *
     * ## 第三百零五轮：给「全局 Dock 空白占位」取色
     *
     * 设置页 / 启动页会把屏幕最下面那条 112px 交给一条空白底窗
     * （见 [com.cardesk.launcher.core.dock.GlobalDockService] 的 `applyBlank`）——
     * 它盖在原车 `CarNavigationBar` 上面，颜色必须和它上面露出来的壁纸**接得上**，
     * 否则屏幕底部会留一道明显的色差接缝（实测差了 13/255 就看得出来）。
     *
     * 口径与 [refreshBackdrop] 一致：自定义壁纸走源图 + 「等比填满 + 居中裁切」
     * 的映射；预设壁纸画进 192×108 代理图再采样。只是区域换成
     * [com.cardesk.launcher.core.WallpaperSampler.BOTTOM_REGION]。
     *
     * @return ARGB；拿不到（图还没解好 / 空）返回 0
     */
    fun bottomWallpaperColor(): Int {
        val night = palette.night
        val region = com.cardesk.launcher.core.WallpaperSampler.BOTTOM_REGION
        if (custom) {
            val bmp = customBitmap
            if (bmp == null || bmp.isRecycled) return 0
            val rect = com.cardesk.launcher.core.WallpaperSampler.sourceRectFor(
                bmp.width, bmp.height,
                width.toFloat().coerceAtLeast(1f), height.toFloat().coerceAtLeast(1f),
                region,
            )
            return com.cardesk.launcher.core.WallpaperSampler.averageColorInRect(
                bmp, rect, night, 0.97f,
            ) ?: 0
        }
        val proxy = proxyBitmap() ?: return 0
        return try {
            val canvas = Canvas(proxy)
            if (defaultDayNight) {
                drawDefaultDayNight(canvas, palette, proxy.width.toFloat(), proxy.height.toFloat(), washPaint, glowPaint, opaqueBase = true)
            } else {
                WallpaperRenderer.drawDirect(
                    canvas, Wallpapers.spec(wallpaper), proxy.width.toFloat(), proxy.height.toFloat(), night,
                )
            }
            com.cardesk.launcher.core.WallpaperSampler.averageColor(proxy, region, false) ?: 0
        } catch (t: Throwable) {
            0
        } finally {
            proxy.recycle()
        }
    }

    /**
     * 把**整屏背景**（底色 + 壁纸）画进一张 1/4 缩略图、模糊后发布给
     * [com.cardesk.launcher.core.Glass]（第242轮「液态玻璃」）。
     *
     * 只在壁纸 / 日夜变化时算一次；卡片绘制时按自己的矩形从这张图里取区域，
     * 所以「卡片下面透出来的模糊背景」位置是对的。
     *
     * ## 第三百零四轮：模糊挪到后台线程 + RS 快速路径
     *
     * 实测这段在主线程要 **68ms**（车机 820A 上估计 150~400ms）：它只在壁纸/
     * 日夜变化时跑，冷方法永远拿不到 JIT（APK 是 `quicken` 编译级别），
     * 所以每次切换都卡一帧以上。现在分两种情况：
     *
     * | 时机 | 做法 | 原因 |
     * |---|---|---|
     * | **首帧**（还没有玻璃底图） | 就地**同步** 3 遍盒式 | 异步的话首帧卡片会先以纯半透明出现、随后"跳"成模糊玻璃 = 一眼可见的闪；且 RS 上下文初始化 86ms 不能放主线程，这里只走盒式 |
     * | 之后（壁纸/日夜变化） | [Glass.blurAsync] 后台线程（RS 真高斯 → 回退盒式），算完回调 [onGlassChanged] | 不占主线程；RS 实测 1ms/张 |
     */
    private var lastGlassKey: String = ""

    private fun refreshGlass() {
        val glass = com.cardesk.launcher.core.Glass
        val night = palette.night
        val key = "$night:$defaultDayNight:$wallpaper:$custom:$customUsesNightAsset:${customBitmap?.let { System.identityHashCode(it) } ?: 0}:${palette.bgTop}"
        if (glass.backdrop != null && key == lastGlassKey) return
        val glassStartMs = android.os.SystemClock.uptimeMillis()
        val bw = (com.cardesk.launcher.core.Design.W * com.cardesk.launcher.core.Glass.SCALE).toInt()
        val bh = (com.cardesk.launcher.core.Design.H * com.cardesk.launcher.core.Glass.SCALE).toInt()
        if (bw <= 0 || bh <= 0) return
        val raw = runCatching {
            android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return
        val radius = (com.cardesk.launcher.core.Glass.BLUR_DESIGN * com.cardesk.launcher.core.Glass.SCALE).toInt()
            .coerceAtLeast(1)
        try {
            val canvas = android.graphics.Canvas(raw)
            canvas.drawColor(palette.bgTop)
            when {
                custom -> WallpaperRenderer.drawCustom(canvas, customBitmap, bw.toFloat(), bh.toFloat(), night && !customUsesNightAsset)
                defaultDayNight -> drawDefaultDayNight(canvas, palette, bw.toFloat(), bh.toFloat(), washPaint, glowPaint, opaqueBase = true)
                else -> WallpaperRenderer.drawDirect(canvas, Wallpapers.spec(wallpaper), bw.toFloat(), bh.toFloat(), night)
            }
        } catch (t: Throwable) {
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SCENE, "玻璃底图绘制失败", "err" to t.toString(),
            )
            raw.recycle()
            return
        }

        // 首帧：同步盒式（保证第一帧就是模糊玻璃，见上面 kDoc 的表）。
        if (glass.backdrop == null) {
            val blurStartMs = android.os.SystemClock.uptimeMillis()
            val blurred = glass.boxBlur(raw, radius)
            val blurMs = android.os.SystemClock.uptimeMillis() - blurStartMs
            if (blurred !== raw) raw.recycle()
            glass.publish(blurred)
            lastGlassKey = key
            com.cardesk.launcher.core.log.Logx.i(
                com.cardesk.launcher.core.log.LogTags.SCENE,
                "玻璃底图耗时",
                "via" to "box-sync",
                "blur" to blurMs,
                "total" to (android.os.SystemClock.uptimeMillis() - glassStartMs),
                "size" to "${bw}x$bh",
                "radius" to radius,
            )
            return
        }

        // 之后：后台线程（raw 的所有权交给 Glass.blurAsync）。
        val queued = glass.blurAsync(context, raw, radius) {
            lastGlassKey = key
            onGlassChanged?.invoke()
        }
        if (!queued) {
            // 线程没起来（极少见）：就地同步兜底，别让玻璃底图一直停在上一次。
            val blurred = glass.boxBlur(raw, radius)
            if (blurred !== raw) raw.recycle()
            glass.publish(blurred)
            lastGlassKey = key
            onGlassChanged?.invoke()
            com.cardesk.launcher.core.log.Logx.w(
                com.cardesk.launcher.core.log.LogTags.SCENE, "玻璃模糊线程未启动，同步兜底",
            )
        }
    }

    /**
     * 玻璃底图**算完并发布**之后的回调（第三百零四轮）。
     *
     * 模糊挪到后台线程后，[com.cardesk.launcher.core.Glass.publish] 不再发生在
     * 主线程的绘制流程里，所以必须显式把画玻璃的层标脏 ——
     * 否则卡片会继续用旧底图，直到下一次别的原因触发重绘（看起来像"切换不生效"）。
     */
    var onGlassChanged: (() -> Unit)? = null

    /** 采样用的代理图（小图即可，构图等比）。 */
    private fun proxyBitmap(): android.graphics.Bitmap? = runCatching {
        android.graphics.Bitmap.createBitmap(
            PROXY_W, PROXY_H, android.graphics.Bitmap.Config.ARGB_8888,
        )
    }.getOrNull()

    /** 抛出新的壁纸亮度（没变就不抛，省得白白重绘）。 */
    private fun publishBackdrop(top: Int?, center: Int?) {
        if (top == backdropTop && center == backdropCenter) return
        backdropTop = top
        backdropCenter = center
        com.cardesk.launcher.core.log.Logx.event(
            com.cardesk.launcher.core.log.LogTags.SCENE,
            "壁纸亮度采样",
            "top" to (top?.let { com.cardesk.launcher.core.WallpaperContrast.luma(it) } ?: -1),
            "center" to (center?.let { com.cardesk.launcher.core.WallpaperContrast.luma(it) } ?: -1),
            "custom" to custom,
        )
        onBackdropChanged?.invoke()
    }

    private var settleAnim: android.animation.ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    /**
     * 第208轮：**视频壁纸播放时，这一层要让路**。
     *
     * 视频壁纸走的是独立于窗口 buffer 的 SurfaceFlinger 层，
     * 它在**主 surface 之下** —— 主 surface 上任何不透明像素都会盖住视频。
     * 所以播视频时这里**什么都不画**（连底色都不铺），把画面完全透给视频。
     *
     * ## 第214轮：改这个值**必须 invalidate**
     *
     * 原来它是个普通 `var`，改成 `true` 之后这一层**不会自己重绘**，
     * 屏幕上留着上一帧的静态壁纸 —— 看着就像「视频壁纸没生效」。
     * （第213轮实测：切到视频壁纸后截图里壁纸原封不动，就是这个原因。）
     */
    var videoPassthrough: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            // 视频壁纸接管 → 这一层不再有画面，亮度交给视频帧采样（第222轮）；
            // 视频退出 → 这一层恢复画壁纸，亮度也恢复成壁纸自己的。
            refreshBackdrop()
        }

    /**
     * 第214轮：**`invalidate()` 真的被调用过**几次（测试观察口）。
     *
     * ## 为什么不直接用 `ShadowView.wasInvalidated()`
     *
     * 那个标志观测的是「View 当前处于 invalidated 状态」，
     * 而这个状态会被 `layout` / `draw` / 换肤等**各种路径**置位 ——
     * 实测：把 setter 里的 `invalidate()` 删掉，它照样返回 `true`，
     * 断言等于没有断言（变异检查当场漏网）。
     *
     * 覆写 `invalidate()` 观测的是「**这个方法被调用了几次**」，
     * 才真正对应「改了 `videoPassthrough` 之后会不会重绘」这条要保证的行为。
     */
    var invalidateCountForTest: Int = 0
        private set

    override fun invalidate() {
        super.invalidate()
        invalidateCountForTest++
    }

    /**
     * 尺寸变了 → 亮度重算（第222轮）。
     *
     * 前面那些 setter 里的 [refreshBackdrop] 在**还没有尺寸**时算不了
     * （宽高是 0，采样区是空的）⇒ 第一次拿到尺寸时补算一次。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshBackdrop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoPassthrough) return
        // 首帧耗时打点（第191轮）：壁纸是 1920×1080 的整屏位图，
        // 冷启动时这一笔可能是几百毫秒到几秒 —— 要能一眼看出是不是它。
        com.cardesk.launcher.core.StartupTrace.markOnce("draw:bg")
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 自定义壁纸不参与「拖三张」的换纸动画：一张照片没有上一张/下一张。
        if (custom) {
            drawWallpaper(canvas, wallpaper, w, h)
            return
        }
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
        when {
            custom -> WallpaperRenderer.drawCustom(canvas, customBitmap, w, h, palette.night && !customUsesNightAsset)
            defaultDayNight -> drawDesignWash(canvas, w, h, palette.night)
            else -> WallpaperRenderer.draw(canvas, Wallpapers.spec(index), w, h, palette.night)
        }
    }

    /**
     * 设计稿 L0：默认日夜壁纸（底色渐变 + 三处柔光）。
     */
    private fun drawDesignWash(canvas: Canvas, w: Float, h: Float, night: Boolean) {
        drawDefaultDayNight(canvas, palette, w, h, washPaint, glowPaint, opaqueBase = true)
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
        if (custom) {
            // 自定义壁纸时拖动不切纸，回弹即可。
            Logx.event(LogTags.SCENE, "壁纸拖拽收尾", "delta" to delta, "step" to 0, "custom" to true)
            dragOffset = 0f
            return 0
        }
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
                    defaultDayNight = false
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
