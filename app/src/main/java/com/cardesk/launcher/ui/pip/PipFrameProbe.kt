package com.cardesk.launcher.ui.pip

import android.graphics.Bitmap
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
 * ## 为什么需要它（第六十七轮）
 *
 * 真机现象：画中画**黑屏**，要「全屏一次再还原画中画」才看得到。
 *
 * 全屏 → 还原这条路上我们会给承载应用**重新下发一次启动**
 * （`PipSurface.relaunchAfterResize`），所以它能恢复 ——
 * 这说明：**承载应用的进程还在，但它的 task 已经不在 VirtualDisplay 上了**
 * （Activity 被系统回收 / 应用自己退出了），ActivityView 里就只剩一块黑。
 *
 * 现有的看门狗查的是**进程还在不在**：进程活着 → 什么都不做 → 一直黑。
 *
 * ## 怎么判「黑了」
 *
 * 用 `PixelCopy` 从承载面的 Surface 采一帧（缩到很小一张），
 * 看整帧的**最大亮度**是不是接近 0。
 *
 * 用「最大亮度」而不是「平均亮度」：地图画面里只要有一个亮像素（路网、文字），
 * 就说明**不是**黑屏 —— 夜里深色地图也不会被误判。
 *
 * ## 防误判（三道闸，缺一不可）
 *
 * 1. **必须先见过非黑画面**（[sawContent]）才允许判黑。
 *    有些设备上 `PixelCopy` 采 SurfaceView **恒为黑**（硬件 overlay 直通），
 *    那就不能让它一直以为「黑了」→ 否则会不停重启承载应用。
 * 2. **连续 3 次**全黑才动手 —— 避开地图加载中的短暂黑帧。
 * 3. 自愈动作有冷却（见 `PipSurface`），不会连着重启。
 *
 * ## 采样是异步的
 *
 * `PixelCopy.request` 是回调式的，所以这里的状态都是**上一次采样的结果**，
 * 调用方（看门狗）只要读属性即可，不用管异步。
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

    private val handler = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null

    /** 有一帧正在采（避免堆积）。 */
    private var pending = false

    /**
     * 请求采一帧。
     *
     * 采不到（承载面还没建 / Surface 无效 / PixelCopy 不可用）就**安静跳过** ——
     * 探针只是辅助手段，采不到不能影响画中画本身。
     */
    fun request() {
        if (pending) return
        val surface = ActivityEmbed.surfaceOf(view) ?: return
        val bmp = bitmap ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
            .also { bitmap = it }
        pending = true
        runCatching {
            PixelCopy.request(
                surface,
                bmp,
                callback@{ result ->
                    pending = false
                    if (result != PixelCopy.SUCCESS) return@callback
                    evaluate(bmp)
                },
                handler,
            )
        }.onFailure {
            pending = false
            Logx.d(LogTags.NAV_UI, "画中画画面采样失败", "reason" to "${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 换承载应用时复位（见过非黑画面的记录不能带到下一个应用上）。 */
    fun reset() {
        black = null
        consecutiveBlack = 0
        sawContent = false
        samples = 0
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
    }

    // ------------------------------------------------------------ 内部

    /** 判断这一帧是不是全黑，并更新计数。 */
    private fun evaluate(bmp: Bitmap) {
        samples++
        val pixels = IntArray(SAMPLE_W * SAMPLE_H)
        runCatching { bmp.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H) }
            .onFailure { return }
        var maxLuma = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma > maxLuma) maxLuma = luma
        }
        val isBlack = maxLuma < BLACK_LUMA
        black = isBlack
        if (isBlack) {
            consecutiveBlack++
        } else {
            consecutiveBlack = 0
            sawContent = true
        }
        Logx.d(
            LogTags.NAV_UI,
            "画中画画面采样",
            "maxLuma" to maxLuma,
            "black" to isBlack,
            "streak" to consecutiveBlack,
        )
    }

    companion object {
        /** 采样图尺寸（小一点就够，只要能看出「有没有亮点」）。 */
        const val SAMPLE_W = 64
        const val SAMPLE_H = 36

        /** 判黑的亮度阈值（0..255）。 */
        const val BLACK_LUMA = 10

        /**
         * 连续几帧全黑才判定「黑屏」。
         *
         * 看门狗 3s 一轮 → 约 9s。避开地图加载中的短暂黑帧。
         */
        const val BLACK_STREAK_LIMIT = 3

        /**
         * 黑屏自愈的冷却（毫秒）。
         *
         * 一次黑屏只自愈一次（[markHealed] 会「忘掉见过非黑画面」），
         * 这里是第二道保险：万一探针误判，也不要连着重启承载应用。
         */
        const val BLACK_HEAL_COOLDOWN_MS = 60_000L

        /**
         * 「要不要自愈」的判定（**纯函数**，可单测）。
         *
         * 三道闸都要过：
         * 1. **见过非黑画面** —— 否则可能一直是 PixelCopy 采不到内容（硬件 overlay）；
         * 2. **连续黑帧够多** —— 避开加载中的短暂黑帧；
         * 3. **冷却过了** —— 不连着重启承载应用。
         *
         * @param sinceLastHealMs 距上次自愈过了多久（`Long.MAX_VALUE` = 从没自愈过）
         */
        fun shouldHeal(
            sawContent: Boolean,
            consecutiveBlack: Int,
            streakLimit: Int = BLACK_STREAK_LIMIT,
            cooldownMs: Long = BLACK_HEAL_COOLDOWN_MS,
            sinceLastHealMs: Long = Long.MAX_VALUE,
        ): Boolean {
            if (!sawContent) return false
            if (consecutiveBlack < streakLimit) return false
            return sinceLastHealMs >= cooldownMs
        }
    }
}
