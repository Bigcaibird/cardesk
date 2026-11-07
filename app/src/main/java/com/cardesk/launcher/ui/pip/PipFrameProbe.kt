package com.cardesk.launcher.ui.pip

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.embed.ActivityEmbed

/**
 * 画中画**画面探针**：采一帧下来，判断承载面是不是「黑屏」。
 *
 * ## 它是什么（第67轮加的，第130轮重做）
 *
 * 用户报的现象：画中画**黑屏**，要「全屏一次再还原画中画」才看得到。
 * 全屏 → 还原那条路上我们会给承载应用**重新下发一次启动**，
 * 所以它能恢复 —— 说明：**承载应用的进程还在，但它的 task 已经不在
 * VirtualDisplay 上了**（Activity 被系统回收 / 应用自己退出了），
 * ActivityView 里就只剩一块黑。
 *
 * 探针的用途就是**发现这件事**。
 *
 * ## 第130轮实测：**窗口采样根本看不见 ActivityView 的画面**
 *
 * 实测（模拟器 + 高德，屏幕上明明在渲染地图）：
 *
 * ```
 * 画中画画面采样：状态变化 black=true maxLuma=0 avgLuma=0 brightPct=0
 * ```
 *
 * 而同一时刻 `screencap` 截到的画中画区域是高德地图（maxLuma=255）。
 *
 * 原因：`ActivityView` 内部是 `SurfaceView`，它的内容画在**独立的图层**上，
 * 窗口自己的缓冲在那个位置是**透明的洞** ——
 * `PixelCopy.request(window, …)` 拷的是**窗口缓冲**，拷不到那个图层。
 *
 * 于是「应用画面是黑的」和「我们看不见应用画面」在数据上**完全一样**。
 * 当成判据 = **把正常的高德判成黑屏**（实测确实误判并重启了高德）。
 *
 * ## 所以判黑不能靠像素（第130轮换了权威判据）
 *
 * 权威判据是 **task 的 displayId**：
 * 承载应用的 Activity 还挂在我们的 VirtualDisplay 上吗？
 * （跟 AOSP 官方 `VirtualDisplay Launcher` 示例判活的方式一致，
 *  实现在 [PipSurface.isTaskOnOurDisplay]。）
 *
 * 像素判黑**降级成辅助**：只有先证明采样能看到应用画面
 * （[sawContent]）才认 —— 否则采样永远落在「洞」上，
 * 会把正常画面判成黑屏。
 *
 * ## 探针剩下的价值
 *
 * - **诊断**：采样成功/失败、最大亮度、亮像素占比都进日志，
 *   真车诊断包一眼能看出「承载面到底有没有画面」；
 * - **辅助判黑**：在能看见应用画面的设备上补一层判据。
 *
 * ## 采样怎么做的
 *
 * 1. **优先从窗口采**（`PixelCopy.request(window, srcRect, …)`）。
 *    直接采 ActivityView 内部 SurfaceView 的 surface 会返回
 *    `ERROR_SOURCE_NO_DATA`（实测模拟器 `result=3`）—— **一帧都采不到**。
 * 2. **采样矩形必须限定在承载面那块**（见 [hostRect]）。
 *    不限定的话，采到的是**整个桌面**（状态栏 / Dock / 音乐卡）——
 *    **永远有亮像素 → 永远判不出黑**。
 * 3. 采样矩形还要**往里缩**（见 [SAMPLE_INSET_RATIO]），
 *    避开我们**自己**叠在承载面上的控件：
 *    - 左侧 36×110 的「分屏/全屏」玻璃按钮（第129轮改成悬浮叠在画面上的）；
 *    - 顶部那三个换位圆点。
 *
 * ## 怎么算黑
 *
 * 三条判据（任一成立即判黑，见 [isBlackFrame]）：
 *
 * 1. **整帧几乎全黑** —— 最大亮度 < [BLACK_LUMA]；
 * 2. **几乎没有亮像素** —— 亮像素占比 < [BRIGHT_RATIO_MIN]；
 * 3. **整体很暗** —— 最大亮度 < [DARK_MAX_LUMA] **且**平均亮度 < [DARK_AVG_LUMA]。
 *
 * ## 防误判
 *
 * 1. **必须先见过非黑画面**（[sawContent]）才允许像素判黑；
 * 2. **连续 3 次**全黑才动手 —— 避开地图加载中的短暂黑帧；
 * 3. 自愈动作有冷却、且**分级**（见 [PipBlackHeal]），不会连着重启。
 *
 * ## 采样是异步的
 *
 * `PixelCopy.request` 是回调式的，所以这里的状态都是**上一次采样的结果**，
 * 调用方（看门狗）只要读属性即可，不用管异步。
 *
 * ## 第130轮修的五处
 *
 * 1. **回调不来 → pending 永久卡死** → 探针彻底死掉 → 诊断时完全看不出探针没在工作。
 *    现在给 pending 加了**超时复位**（见 [SAMPLE_TIMEOUT_MS]）。
 * 2. **取不到 surface 时是安静跳过的** → 一个字都没留。
 *    现在记 [consecutiveUnsampleable] 并在**变化时**打一条 WARN。
 * 3. **判黑只看最大亮度** → `#101010` 这种深色底判不出来。
 *    现在补了「整体很暗」的第二判据（见 [evaluate]）。
 * 4. **从窗口采却不限定范围** → 采到整个桌面 → 永远判不出黑。
 *    现在限定采样矩形到承载面（见 [hostRect]）。
 * 5. **判黑只看「有没有亮像素」** → 我们自己叠在画面上的控件会洗掉黑屏。
 *    现在改用「亮像素**占比**」（见 [BRIGHT_RATIO_MIN]）。
 */
class PipFrameProbe(private val view: View) {

    /** 最近一次采样是否「整帧几乎全黑」。null = 还没采到。 */
    @Volatile
    var black: Boolean? = null
        private set

    /** 连续全黑的次数（连续 3 次才允许自愈）。 */
    @Volatile
    var consecutiveBlack: Int = 0
        private set

    /** 有没有见过**非黑**画面 —— 防误判的第一道闸。 */
    @Volatile
    var sawContent: Boolean = false
        private set

    /** 采样次数（诊断用）。 */
    @Volatile
    var samples: Int = 0
        private set

    /**
     * 连续**采不到画面**的次数（第130轮新增）。
     *
     * 「采不到画面」和「画面全黑」对用户来说是同一个现象：
     * 画中画里什么都没有。以前只有「采到黑帧」这一条判据，
     * 于是**取不到 surface 的环境下黑屏永远不会被发现**。
     *
     * 采到一帧就复位。
     */
    @Volatile
    var consecutiveUnsampleable: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null

    /** 有一帧正在采（避免堆积）。 */
    private var pending = false

    /** 这一帧是什么时候开始采的（超时复位用）。 */
    private var pendingAt = 0L

    /** 上一次打「采不到画面」日志时的计数（变化时才记，避免刷屏）。 */
    private var lastLoggedUnsampleable = 0

    /** 上一次采样用的矩形（诊断用：`l,t,r,b`）。 */
    @Volatile
    var lastSampleRect: String = ""
        private set

    /** 承载面**至少布局过一次**没有（区分「还没布局」与「布局过却算不出位置」）。 */
    private var laidOut = false

    /**
     * 请求采一帧。
     *
     * 采不到（承载面还没建 / Surface 无效 / PixelCopy 不可用）就**安静跳过** ——
     * 探针只是辅助手段，采不到不能影响画中画本身。
     */
    fun request() {
        if (pending) {
            // —— 第130轮：**超时复位** ——
            //
            // `PixelCopy.request` 的回调可能永远不来（surface 失效 /
            // 某些设备实现不回调），那 pending 就永远为 true →
            // 之后每次 request 都直接 return → **探针彻底死掉** →
            // 黑屏永远不会被发现，也永远不自愈。
            //
            // 实测（模拟器 + 纯黑承载应用）：「画中画画面采样」一条日志都没有，
            // 连「采样失败」也没有 —— 就是卡在这里。
            if (System.currentTimeMillis() - pendingAt < SAMPLE_TIMEOUT_MS) return
            pending = false
            Logx.w(
                LogTags.NAV_UI,
                "画中画画面采样超时（回调没来），复位探针",
                "waitedMs" to (System.currentTimeMillis() - pendingAt),
            )
        }
        // —— 第130轮：**优先从窗口采** ——
        //
        // 直接采 ActivityView 内部 SurfaceView 的 surface，在很多环境上会返回
        // ERROR_SOURCE_NO_DATA（实测模拟器上 result=3）—— **一帧都采不到**。
        // 从窗口采，SurfaceView 的内容会先合成进窗口，成功率高得多。
        val window = ActivityEmbed.windowOf(view)
        val surface = if (window == null) ActivityEmbed.surfaceOf(view) else null
        // 采样矩形（窗口坐标系）：**限定在承载面那块**，再往里缩避开我们自己的控件。
        val rect = if (window != null) hostRect() else null
        if (window == null && surface == null) {
            // —— 第130轮：**不再安静跳过** ——
            //
            // 「取不到 surface」本身就是一种黑屏（承载面没内容），
            // 以前这里直接 return，一个字都不留 → 排障时完全看不出探针没在工作。
            noteUnsampleable("取不到承载面 surface")
            return
        }
        if (window != null && rect == null) {
            // 有窗口、但算不出承载面的位置：
            // - **还没布局好** → 布局前的瞬间，跳过（不能记成「采不到」，
            //   否则刚嵌入的那几帧会被误判成黑屏）；
            // - **布局过却算不出** → 算成「采不到画面」。
            //
            // 另外：这时如果退回去「采整个窗口」，采到的是**整个桌面** → 永远有亮像素 →
            // 探针会误以为「有画面」→ 黑屏永远发现不了。所以不能那样退。
            if (laidOut) {
                noteUnsampleable("算不出承载面在窗口里的位置")
            } else {
                Logx.d(LogTags.NAV_UI, "画中画画面采样：承载面还没布局好，跳过本轮")
            }
            return
        }
        val bmp = bitmap ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
            .also { bitmap = it }
        pending = true
        pendingAt = System.currentTimeMillis()
        lastSampleRect = rect?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: ""
        runCatching {
            val onCopied = PixelCopy.OnPixelCopyFinishedListener { result ->
                pending = false
                if (result != PixelCopy.SUCCESS) {
                    // 采到了但结果不对 —— 也要留痕（以前这里也是静默 return）
                    Logx.w(
                        LogTags.NAV_UI,
                        "画中画画面采样未成功",
                        "result" to result,
                        "from" to if (window != null) "window" else "surface",
                        "rect" to lastSampleRect,
                    )
                    return@OnPixelCopyFinishedListener
                }
                evaluate(bmp)
            }
            if (window != null && rect != null) {
                PixelCopy.request(window, rect, bmp, onCopied, handler)
            } else {
                PixelCopy.request(surface!!, bmp, onCopied, handler)
            }
        }.onFailure {
            pending = false
            Logx.w(
                LogTags.NAV_UI,
                "画中画画面采样失败",
                "reason" to "${it.javaClass.simpleName}: ${it.message}",
                "from" to if (window != null) "window" else "surface",
                "rect" to lastSampleRect,
            )
        }
    }

    /**
     * 记一次「采不到画面」（并按变化限频打日志）。
     *
     * 「采不到」在用户看来就是黑屏，所以它必须被记下来 ——
     * 以前这里是安静跳过，黑屏就永远不会被发现。
     */
    private fun noteUnsampleable(why: String) {
        consecutiveUnsampleable++
        // 只在计数**变化**时记一条 —— 否则 3s 一条会把车机磁盘刷爆。
        if (consecutiveUnsampleable != lastLoggedUnsampleable) {
            lastLoggedUnsampleable = consecutiveUnsampleable
            Logx.w(
                LogTags.NAV_UI,
                "画中画画面采样：取不到承载面画面",
                "unsampleable" to consecutiveUnsampleable,
                "why" to why,
            )
        }
    }

    /** 换承载应用时复位（见过非黑画面的记录不能带到下一个应用上）。 */
    fun reset() {
        black = null
        consecutiveBlack = 0
        sawContent = false
        samples = 0
        consecutiveUnsampleable = 0
        lastLoggedUnsampleable = 0
        pending = false
    }

    /**
     * 自愈动作之后调用：清掉连续计数，并**忘掉「见过非黑画面」**。
     *
     * 忘掉是有意的：一次黑屏只自愈一次。
     * 万一重新拉起之后还是黑，就不再反复重启承载应用了
     * （反复重启比一直黑更烦人）。
     */
    fun markHealed() {
        consecutiveBlack = 0
        sawContent = false
        consecutiveUnsampleable = 0
        lastLoggedUnsampleable = 0
    }

    // ------------------------------------------------------------ 内部

    /**
     * 承载面在**窗口**里的采样矩形（已内缩）。
     *
     * ## 为什么要限定范围（第130轮）
     *
     * 从窗口采如果不带 `srcRect`，采到的是**整个桌面**：
     * 状态栏、Dock、音乐卡、我们自己的玻璃按钮…… **永远有亮像素** →
     * 判黑永远不成立 → 探针等于没有。
     *
     * 所以必须把采样矩形限定到**承载面那块**，
     * 而且要**往里缩**——我们自己叠在画面上的控件
     * （左侧 36×110 的分屏按钮、顶部三个点）是亮的，
     * 不缩进去就会把「黑屏」洗成「有画面」。
     *
     * @return null = 承载面还没布局好（算不出位置）
     */
    private fun hostRect(): Rect? {
        if (!view.isAttachedToWindow) return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        laidOut = true
        val pos = IntArray(2)
        view.getLocationInWindow(pos)
        val r = sampleRectOf(pos[0], pos[1], pos[0] + w, pos[1] + h)
        val left = r[0]
        val top = r[1]
        val right = r[2]
        val bottom = r[3]
        if (right - left <= 0 || bottom - top <= 0) return null
        return Rect(left, top, right, bottom)
    }

    /**
     * 判断这一帧是不是「黑」，并更新计数。
     *
     * ## 三条判据（见 [isBlackFrame]）
     *
     * 1. **整帧几乎全黑** —— 最大亮度 < [BLACK_LUMA]。
     * 2. **几乎没有亮像素** —— 亮像素占比 < [BRIGHT_RATIO_MIN]。
     * 3. **整体很暗** —— 最大亮度 < [DARK_MAX_LUMA] **且**平均亮度 < [DARK_AVG_LUMA]。
     *
     * 第 2 条是为了兜住「画面整体很暗、但不是纯黑」，
     * 第 3 条是为了覆盖「承载应用画的是 `#101010` 这种深色底」。
     */
    private fun evaluate(bmp: Bitmap) {
        samples++
        // 采到一帧 → 「采不到画面」的连续计数复位
        consecutiveUnsampleable = 0
        lastLoggedUnsampleable = 0
        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
        runCatching { bmp.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H) }
            .onFailure { return }
        var maxLuma = 0
        var sumLuma = 0L
        var bright = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma > maxLuma) maxLuma = luma
            sumLuma += luma
            if (luma >= BRIGHT_LUMA) bright++
        }
        val avgLuma = (sumLuma / pixels.size).toInt()
        val brightRatio = bright.toDouble() / pixels.size
        val isBlack = isBlackFrame(maxLuma, avgLuma, brightRatio)
        val wasBlack = black
        black = isBlack
        if (isBlack) {
            consecutiveBlack++
        } else {
            consecutiveBlack = 0
            sawContent = true
        }
        // —— 限频：只在「黑白状态翻转」或每 [LOG_EVERY_N_FRAMES] 帧记一条 ——
        // 看门狗 3s 一帧，每帧都记会把车机磁盘刷爆（第 N 轮踩过的坑）。
        val flipped = wasBlack != isBlack
        if (flipped) {
            // 状态翻转是**关键诊断信息** → 提到 WARN，真车诊断包里能看到。
            //
            // 注意：实测（模拟器 API 29）窗口拷贝在 ActivityView 那个位置是
            // **透明的洞**，所以这里**永远**是 black=true ——
            // 这不代表画面是黑的，只代表「我们看不见 ActivityView 的画面」。
            // 判黑请用 [PipBlackHeal] 的 task display 判据。
            Logx.w(
                LogTags.NAV_UI,
                "画中画画面采样：状态变化",
                "black" to isBlack,
                "maxLuma" to maxLuma,
                "avgLuma" to avgLuma,
                "brightPct" to (brightRatio * 100).toInt(),
                "streak" to consecutiveBlack,
                "rect" to lastSampleRect,
                "note" to "窗口拷贝看不到 ActivityView 画面（SurfaceView 独立图层）",
            )
        } else if (samples % LOG_EVERY_N_FRAMES == 0) {
            Logx.d(
                LogTags.NAV_UI,
                "画中画画面采样",
                "maxLuma" to maxLuma,
                "avgLuma" to avgLuma,
                "brightPct" to (brightRatio * 100).toInt(),
                "black" to isBlack,
                "streak" to consecutiveBlack,
                "rect" to lastSampleRect,
            )
        }
    }

    companion object {
        /** 采样图尺寸（小一点就够，只要能看出「有没有亮点」）。 */
        const val SAMPLE_W = 64
        const val SAMPLE_H = 36

        /** 判黑的亮度阈值（0..255）。 */
        const val BLACK_LUMA = 10

        /**
         * 「整体很暗」这条判据的**最大亮度**上限（0..255）。
         *
         * 比 [BLACK_LUMA] 宽一些，用来覆盖 `#101010` 这类深色底。
         * 同时要求平均亮度也很小 —— 所以夜里深色地图（有路网、文字）不会误判。
         */
        const val DARK_MAX_LUMA = 24

        /**
         * 「整体很暗」这条判据的**平均亮度**上限（0..255）。
         *
         * 深色地图的平均亮度远大于这个值（路网和文字是亮的）。
         */
        const val DARK_AVG_LUMA = 8

        /**
         * 算「亮像素」的亮度阈值（0..255）。
         *
         * 低于这个值一律当「暗」—— 深色底、路网底色都落在暗区，
         * 判黑看的就是「有没有明显亮的东西」。
         */
        const val BRIGHT_LUMA = 48

        /**
         * 亮像素**占比**低于这个值就判黑（0.02 = 2%）。
         *
         * ## 为什么要有这条（第130轮）
         *
         * 承载面黑屏时，画面里可能**一点亮的东西都没有**，
         * 但也可能是「整体很暗 + 零星亮点」（例如应用刚起来时的深色启动页）。
         * 只看最大亮度的话，一颗小亮点就能把「黑」洗成「有画面」。
         *
         * 而**我们自己**叠在画面上的控件（左侧 36×110 的分屏按钮、顶部三个点）
         * 恰好就是这种「零星亮点」—— 采样矩形没缩干净时，
         * 它们会让黑屏**永远判不出来**。这条就是兜底。
         *
         * 阈值定 2%：采样图 64×36 = 2304 像素 → 约 46 个像素。
         * 深色地图的路网/文字缩放后远多于这个数（实测见第130轮报告），
         * 所以夜里导航**不会误判**。
         */
        const val BRIGHT_RATIO_MIN = 0.02

        /**
         * 采样矩形**内缩**比例（0.15 = 四边各缩 15%）。
         *
         * ## 为什么必须缩（第130轮）
         *
         * 画中画承载面上叠着**我们自己的**控件：
         * - 左侧 36×110 的「分屏/全屏」玻璃按钮（第129轮改成悬浮在画面上）；
         * - 顶部三个换位圆点。
         *
         * 它们是**亮的**。不缩进去的话，黑屏会被这几个亮控件洗成「有画面」→
         * 探针永远判不出黑 → 黑屏永远不自愈。
         *
         * 15% 的依据：分屏按钮宽 36px、三个点高约 60px，
         * 相对承载面（最窄的双画 ~960px、最矮 ~700px）都在 10% 以内 → 15% 足够避开。
         */
        const val SAMPLE_INSET_RATIO = 0.15f

        /**
         * 采样日志的限频：每 N 帧记一条（黑白状态翻转时**总是**记）。
         *
         * 看门狗 3s 一帧 —— 每帧都记，一天就是 2.8 万条，车机磁盘扛不住。
         */
        const val LOG_EVERY_N_FRAMES = 30

        /**
         * 采样回调的超时（毫秒）。
         *
         * 超过这么久还没回调 → 认为这次采样废了，**复位 pending**。
         *
         * 没有这个超时，`PixelCopy.request` 的回调一旦不来，
         * `pending` 就永远为 true → 探针彻底死掉 → 黑屏永远不被发现。
         */
        const val SAMPLE_TIMEOUT_MS = 1_500L

        /**
         * 连续几帧全黑才判定「黑屏」。
         *
         * 看门狗 3s 一轮 → 约 9s。避开地图加载中的短暂黑帧。
         */
        const val BLACK_STREAK_LIMIT = 3

        /**
         * 把承载面的矩形**往里缩**（**纯函数**，可单测）。
         *
         * 返回 `[left, top, right, bottom]`。
         *
         * @param ratio 内缩比例（0 = 不缩，0.15 = 四边各缩 15%）
         */
        fun sampleRectOf(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            ratio: Float = SAMPLE_INSET_RATIO,
        ): IntArray {
            val w = right - left
            val h = bottom - top
            val dx = (w * ratio).toInt()
            val dy = (h * ratio).toInt()
            return intArrayOf(left + dx, top + dy, right - dx, bottom - dy)
        }

        /**
         * 这一帧算不算「黑」（**纯函数**，可单测）。
         *
         * 三条判据（见 [evaluate]）：
         * 1. 整帧几乎全黑 —— 最大亮度 < [BLACK_LUMA]；
         * 2. 几乎没有亮像素 —— 亮像素占比 < [BRIGHT_RATIO_MIN]；
         * 3. 整体很暗 —— 最大亮度 < [DARK_MAX_LUMA] **且**平均亮度 < [DARK_AVG_LUMA]。
         *
         * 第 2 条覆盖「画面整体很暗但不是纯黑」，也是「我们自己的控件
         * 洗掉黑屏」的兜底；第 3 条覆盖 `#101010` 这类深色底。
         *
         * @param maxLuma 这一帧的最大亮度
         * @param avgLuma 这一帧的平均亮度
         * @param brightRatio 亮像素占比（0..1）；默认 1.0 = 不触发第 2 条
         */
        fun isBlackFrame(maxLuma: Int, avgLuma: Int, brightRatio: Double = 1.0): Boolean =
            maxLuma < BLACK_LUMA ||
                brightRatio < BRIGHT_RATIO_MIN ||
                (maxLuma < DARK_MAX_LUMA && avgLuma < DARK_AVG_LUMA)
    }
}
