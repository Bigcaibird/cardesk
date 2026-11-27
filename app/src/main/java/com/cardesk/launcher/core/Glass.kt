package com.cardesk.launcher.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「液态玻璃」材质（第 242 轮，对齐 `cardesk-ui/design` 的设计语言）。
 *
 * ## 为什么不是简单的半透明白
 *
 * 设计稿的卡片是 `rgba(255,255,255,.62)` + **背景模糊 20px**。
 * 只画半透明白，卡片下面的照片会**原样透出来**（细节全在），
 * 看起来像「贴了一张灰色塑料片」，而不是玻璃。
 *
 * API 28 没有 `RenderEffect`（API 31 才有），所以这里用
 * **预模糊背景图 + 半透明色 + 内高光 + 柔阴影** 的方式实现等价效果：
 *
 * 1. [backdrop]：`BackgroundView` 在壁纸/日夜变化时把**整屏背景**
 *    （底色 + 柔光 + 壁纸）画进一张 `1/4` 缩略图，再在后台线程模糊
 *    （RS 真高斯，不可用时回退 3 遍盒式），模糊半径按 1/4 缩放（20px → 5px）。
 * 2. 每张卡片按自己的矩形**从这张图里取对应区域**，
 *    所以卡片透出来的是「它正下方那块背景的模糊版」——
 *    位置、颜色都对得上，不是一张糊死的整屏图。
 * 3. 再叠设计稿的填充色、1px 白描边（rim）、顶部 10% 的内高光，
 *    以及 `0 8 24 / 10%` 的柔阴影。
 *
 * ## 线程（第三百零四轮改）
 *
 * 模糊本身**在后台线程**算（[blurAsync]），算完 post 回主线程 [publish]；
 * 所以 [backdrop] 仍是「主线程写、主线程读」，`@Volatile` 只是防御性写法。
 *
 * 这条改造的起因：实测这段「画 1/4 缩略图 + 3 遍盒式模糊」在主线程要
 * **68ms**（车机 820A 上估计 150~400ms），而它只在壁纸/日夜变化时跑，
 * 冷方法永远不会被 JIT 编译（APK 是 `quicken` 编译级别）⇒ 每次切换都卡一帧以上。
 * 现在模糊挪到 `cardesk-glass-blur` 线程，并优先走 RenderScript 的
 * `ScriptIntrinsicBlur`（native 多线程，同一张图实测 **1ms**，快 68 倍）。
 */
object Glass {

    /** 模糊背影的缩放比（1/4：480×270，够用且模糊便宜）。 */
    const val SCALE = 0.25f

    /** 设计稿的背景模糊半径（px），落到缩略图上要乘 [SCALE]。 */
    const val BLUR_DESIGN = 20f

    /** 当前背景的模糊缩略图（null = 还没有，退化成纯半透明）。 */
    @Volatile
    var backdrop: Bitmap? = null
        private set

    private val shaderMatrix = Matrix()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 缓存与当前 [backdrop] 绑定的 [BitmapShader]，避免每帧每卡重复创建上传纹理。 */
    private var cachedShaderBmp: android.graphics.Bitmap? = null
    private var cachedShader: android.graphics.BitmapShader? = null

    /**
     * 发布一张**已经模糊过**的背景缩略图。
     *
     * @param blurred 尺寸应为 `Design.W*SCALE × Design.H*SCALE`
     */
    fun publish(blurred: Bitmap?) {
        backdrop = blurred
        // 实车闪屏诊断（第三百零三轮）：玻璃底图被重发布的次数。
        // 正常只在壁纸 / 日夜变化时涨一次；高频增长说明玻璃卡片底在闪。
        publishCount++
    }

    /**
     * [publish] 被调用的次数（实车闪屏诊断观测口，第三百零三轮）。
     */
    var publishCount: Long = 0L
        private set

    /**
     * 画一张玻璃卡片（阴影 + 模糊背景 + 填充 + 描边 + 内高光）。
     *
     * 调用方只需在返回后画内容；卡片矩形就是内容区。
     *
     * @param rect 卡片矩形（设计坐标）
     * @param radius 圆角
     * @param fill 填充色（默认 L1 卡面；L2 浮层传更实的那个）
     * @param shadow 是否画柔阴影（贴在别的卡上的小卡可以不画）
     * @param sheen 是否画顶部内高光
     */
    fun card(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        fill: Int = if (Skin.isNight) 0xA8161B22.toInt() else 0x9EFFFFFF.toInt(),
        shadow: Boolean = true,
        sheen: Boolean = true,
        stroke: Int = if (Skin.isNight) 0x1FFFFFFF else 0x59FFFFFF,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        if (shadow) drawShadow(canvas, rect, radius)

        val bmp = backdrop
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            if (bmp !== cachedShaderBmp || cachedShader == null) {
                cachedShaderBmp = bmp
                cachedShader = android.graphics.BitmapShader(
                    bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP,
                )
            }
            shaderMatrix.setScale(Design.W / bmp.width.toFloat(), Design.H / bmp.height.toFloat())
            if (offsetX != 0f || offsetY != 0f) shaderMatrix.postTranslate(-offsetX, -offsetY)
            cachedShader?.let { s ->
                s.setLocalMatrix(shaderMatrix)
                bitmapPaint.shader = s
                canvas.drawRoundRect(rect, radius, radius, bitmapPaint)
                bitmapPaint.shader = null
            }
        }

        fillPaint.color = fill
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        if (sheen) {
            // 顶部 38% 高度的一道白，8% → 0：玻璃的「湿润感」全靠它。
            sheenPaint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + rect.height() * 0.38f,
                if (Skin.isNight) 0x14FFFFFF else 0x2EFFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, sheenPaint)
            sheenPaint.shader = null
        }

        rimPaint.color = stroke
        rimPaint.strokeWidth = 1f
        canvas.drawRoundRect(
            RectF(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f),
            radius - 0.5f, radius - 0.5f, rimPaint,
        )
    }

    /**
     * 只画柔阴影（`0 8 24 / 10%` 的近似）。
     *
     * `setShadowLayer` 在硬件加速画布上对**形状**不生效（只对文字生效），
     * 而给每张卡开软件图层 `saveLayer` 在车机上太贵，
     * 所以用「多层渐隐圆角矩形」逼近：外扩 2/5/8/12px，alpha 4%→1%。
     */
    fun drawShadow(canvas: Canvas, rect: RectF, radius: Float) {
        val base = if (Skin.isNight) 0xFF000000.toInt() else 0xFF1B2A3A.toInt()
        val rings = floatArrayOf(12f, 8f, 5f, 2f)
        val alphas = intArrayOf(4, 6, 8, 10)
        for (i in rings.indices) {
            shadowPaint.color = Color.argb(
                alphas[i],
                Color.red(base), Color.green(base), Color.blue(base),
            )
            val e = rings[i]
            canvas.drawRoundRect(
                RectF(rect.left - e, rect.top - e + 8f, rect.right + e, rect.bottom + e + 8f),
                radius + e, radius + e, shadowPaint,
            )
        }
    }

    /** 清掉（退出桌面 / 视频壁纸接管时）。 */
    fun clear() {
        backdrop = null
        cachedShaderBmp = null
        cachedShader = null
        // 第三百零四轮：让**在飞**的模糊结果作废。否则视频壁纸刚接管时，
        // 上一张静态壁纸的模糊图会晚到并被发布出去（盖在视频上 ⇒ 卡片后面
        // 透出一块静态糊图），得等下一次刷新才会消失。
        blurGen.incrementAndGet()
    }

    /**
     * 盒式模糊（原地，三遍 ⇒ 近似高斯）。
     *
     * @param src 源图（会被读，不会被改）
     * @param radius 缩略图上的半径（设计半径 × [SCALE]）
     */
    fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            blurPass(pixels, tmp, w, h, radius, true)
            blurPass(tmp, pixels, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val span = r * 2 + 1
        if (horizontal) {
            for (y in 0 until h) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                val row = y * w
                for (i in -r..r) {
                    val c = src[row + i.coerceIn(0, w - 1)]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((a / span) shl 24) or ((rr / span) shl 16) or ((gg / span) shl 8) or (bb / span)
                    val out = src[row + (x - r).coerceIn(0, w - 1)]
                    val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                    a += (inn ushr 24) - (out ushr 24)
                    rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    gg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        } else {
            for (x in 0 until w) {
                var a = 0; var rr = 0; var gg = 0; var bb = 0
                for (i in -r..r) {
                    val c = src[i.coerceIn(0, h - 1) * w + x]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((a / span) shl 24) or ((rr / span) shl 16) or ((gg / span) shl 8) or (bb / span)
                    val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                    val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    a += (inn ushr 24) - (out ushr 24)
                    rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    gg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    bb += (inn and 0xFF) - (out and 0xFF)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 第三百零四轮：模糊挪到后台线程 + RenderScript 真高斯快速路径
    // ------------------------------------------------------------------

    /**
     * RenderScript 是否已经成功跑过一次（true = 走真高斯，false = 还在盒式回退）。
     */
    var rsAvailable: Boolean = false
        private set

    /** RS 是否已被判定不可用（一次失败后不再重试，避免每次刷新都刷日志）。 */
    var rsFallback: Boolean = false
        private set

    private var rsLastError: String? = null

    /**
     * RS 的上下文与 `ScriptIntrinsicBlur` 都是**进程级单例**、且**只能在
     * 创建它的线程上使用** —— 所以它们只在 [blurHandler] 那个线程里碰。
     *
     * 创建成本实测 68~86ms（加载 `libRS.so` + 建上下文 + 编译内建 kernel），
     * 用完**不要 destroy**，留着复用（之后每次模糊只要 1ms）。
     */
    private var rs: RenderScript? = null
    private var rsBlurScript: ScriptIntrinsicBlur? = null

    /** 模糊线程（进程级单例，`cardesk-glass-blur`）。 */
    private var blurThread: HandlerThread? = null
    private var blurHandler: Handler? = null
    private var uiHandler: Handler? = null

    /** 请求代次：新请求/`clear()` 会让在飞的结果作废（旧壁纸的糊图不能盖新图）。 */
    private val blurGen = AtomicInteger(0)

    /** 模糊完成次数（诊断心跳用）。 */
    var blurCount: Long = 0L
        private set

    /** 结果因「已有更新请求 / 已被 clear」被丢弃的次数（诊断心跳用）。 */
    var blurDropCount: Long = 0L
        private set

    private fun ui(): Handler = uiHandler ?: Handler(Looper.getMainLooper()).also { uiHandler = it }

    private fun ensureBlurHandler(): Handler? {
        blurHandler?.let { return it }
        return runCatching {
            val t = HandlerThread("cardesk-glass-blur").apply { start() }
            blurThread = t
            Handler(t.looper).also { blurHandler = it }
        }.getOrNull()
    }

    /**
     * 在**后台线程**把 [src] 模糊掉，算完 post 回主线程 [publish]，再回调 [onDone]。
     *
     * 两条路：
     * 1. [rsBlurInternal]：RenderScript `ScriptIntrinsicBlur` 真高斯（native 多线程，
     *    实测 1ms / 张，车机镜像里 `libRS.so`、`libRSCpuRef.so` 齐全）；
     * 2. 回退 [boxBlur]：3 遍盒式（视觉上和真高斯平均差 1/255，肉眼无差），
     *    盘古 ROM 裁掉 RS 时仍然能出玻璃。
     *
     * **[src] 的所有权转交给本方法**：算完（成功或失败）都会回收，调用方不要再碰。
     *
     * @return `false` = 线程没起来 / 没排上队，调用方应就地同步兜底。
     */
    fun blurAsync(context: Context?, src: Bitmap, radius: Int, onDone: (Bitmap) -> Unit): Boolean {
        val gen = blurGen.incrementAndGet()
        val handler = ensureBlurHandler() ?: return false
        val posted = handler.post worker@{
            // 排队期间又有新请求 / 被 clear 了 ⇒ 这张已经过时，别白算。
            if (blurGen.get() != gen) {
                blurDropCount++
                src.recycle()
                return@worker
            }
            val t0 = SystemClock.uptimeMillis()
            val out = blurNow(context, src, radius)
            val ms = SystemClock.uptimeMillis() - t0
            if (out !== src) src.recycle()
            ui().post {
                if (blurGen.get() != gen) {
                    blurDropCount++
                    out.recycle()
                    return@post
                }
                blurCount++
                publish(out)
                Logx.i(
                    LogTags.SCENE, "玻璃模糊完成",
                    "ms" to ms,
                    "via" to if (rsAvailable && !rsFallback) "rs" else "box",
                    "size" to "${out.width}x${out.height}",
                    "radius" to radius,
                    "blurCount" to blurCount,
                    "drop" to blurDropCount,
                )
                onDone(out)
            }
        }
        if (!posted) {
            src.recycle()
            return false
        }
        return true
    }

    /** 后台线程里跑：先 RS，失败/不可用回退盒式。永远返回一张可用的图。 */
    private fun blurNow(context: Context?, src: Bitmap, radius: Int): Bitmap =
        rsBlurInternal(context, src, radius) ?: boxBlur(src, radius)

    /**
     * RenderScript 真高斯（**只能在 [blurHandler] 线程调用**）。
     *
     * 一次失败就把 [rsFallback] 置上、不再重试 —— 老 ROM 裁掉 RS 时
     * `RenderScript.create` 会抛 `UnsatisfiedLinkError` 之类，
     * 每次刷新都试一遍既慢又刷日志。
     */
    private fun rsBlurInternal(context: Context?, src: Bitmap, radius: Int): Bitmap? {
        if (rsFallback) return null
        val ctx = context?.applicationContext ?: return null
        return runCatching {
            if (rs == null) {
                val t0 = SystemClock.uptimeMillis()
                rs = RenderScript.create(ctx)
                Logx.i(
                    LogTags.SCENE, "玻璃模糊RS初始化",
                    "ms" to (SystemClock.uptimeMillis() - t0),
                )
            }
            val r = rs ?: return null
            val script = rsBlurScript ?: ScriptIntrinsicBlur.create(r, Element.U8_4(r))
                .also { rsBlurScript = it }
            script.setRadius(radius.toFloat().coerceIn(0.1f, 25f))
            val inAlloc = Allocation.createFromBitmap(r, src)
            val outAlloc = Allocation.createTyped(r, inAlloc.type)
            try {
                script.setInput(inAlloc)
                script.forEach(outAlloc)
                val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                outAlloc.copyTo(out)
                rsAvailable = true
                out
            } finally {
                runCatching {
                    inAlloc.destroy()
                    outAlloc.destroy()
                }
            }
        }.onFailure {
            rsFallback = true
            rsLastError = "${it.javaClass.simpleName}: ${it.message}"
            Logx.w(LogTags.SCENE, "玻璃模糊RS不可用，回退盒式", "err" to rsLastError)
        }.getOrNull()
    }

    /** RS 最近一次失败原因（日志/诊断用）。 */
    fun rsError(): String? = rsLastError

}
