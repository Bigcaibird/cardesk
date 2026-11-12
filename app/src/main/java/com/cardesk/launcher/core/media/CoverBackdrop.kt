package com.cardesk.launcher.core.media

/**
 * 「专辑封面当第二页音乐卡背景」的视觉规格 —— **纯计算**（不碰任何 Android 图形对象，可直接 JVM 单测）。
 *
 * ## 这份规格要解决什么
 *
 * 第二页音乐卡上压着三层文字，颜色一档比一档弱：
 *
 * | 内容 | 字号 | 颜色 |
 * |---|---|---|
 * | 歌名 / 当前句歌词 | 38px 粗体 | `Skin.TEXT` |
 * | 歌手 / 次要歌词 | 22 / 27px | `Skin.TEXT_SOFT` |
 * | 播放状态 / 时间行 | 20 / 18px | `Skin.TEXT_DIM` |
 *
 * 原来它们压在一块**纯色卡面**上（夜间 `#1F232D`），对比度是定死的。
 * 换成封面当底之后底色就不受控了 —— 一张纯白封面 + 白字 = 什么都看不见。
 *
 * 所以这里定死一条规矩：
 *
 * > **封面可以当底，但必须先被压到「不喧宾夺主」的程度，压到什么程度由对比度反推。**
 *
 * ## 压到什么程度（对比度口径）
 *
 * 以**最弱那档文字**（`Skin.TEXT_DIM`）为准，要求它与背景的对比度 ≥ **3:1**
 * （WCAG 对「大字号」文字的底线；这些字在 1080p 车机屏上是 18~20px、
 * 驾驶位远距离看，3:1 是对次要信息通行的档位）。
 *
 * - 夜间（字亮底暗）：背景亮度**上限** `L_bg ≤ (L_text + 0.05) / 3.05 − 0.05`
 * - 日间（字暗底亮）：背景亮度**下限** `L_bg ≥ 3.05 × (L_text + 0.05) − 0.05`
 *
 * 换成实际数值：
 *
 * - 夜间 `Skin.TEXT_DIM` = `#757E8E` → `L_text = 0.2065` → **`L_bg ≤ 0.0341`**
 *   （卡面本身是 0.0168；压到 0.0341 时最弱那档文字 3.05:1、
 *   次要文字 5.34:1、主文字 10.8:1）
 * - 日间 `Skin.TEXT_DIM` = `#8B92A0` → `L_text = 0.2859` → **`L_bg ≥ 0.9745`**
 *
 * 日间这条几乎等于「卡面保持纯白」—— **白底深字与深底白字是对称的**，
 * 只要底上有一点可见的深色内容，深色文字就读不出来。
 *
 * ## 第153轮：日间怎么才能既统一又看得见封面
 *
 * 上面那条「日间几乎等于纯白」**不是没实现，是物理限制**：
 * 白底深字时，底上只要有一点可见的深色内容，深色文字就读不出来。
 * 于是日间模式下「开封面背景」和「关封面背景」画出来**像素级几乎一样** ——
 * 用户点封面图只看到一句提示、看不到任何变化（真机实测，见 REPORT-y153）。
 *
 * 实测数字（日间罩子区域）：
 *
 * | 文字档位 | 最弱那档 | 背景亮度**下限** | 罩完之后比纯白暗 8 以上的像素 |
 * |---|---|---|---|
 * | 三档（`#8B92A0`） | L=0.2859 | **0.9743** | **0.9%**（= 没画） |
 * | **两档（`#565D6B`）** | L=0.1087 | **0.4340** | **99.9%**（认得出是张封面） |
 *
 * 也就是说：**能不能让封面在日间看得见，取决于卡上最弱那档文字有多深**。
 * 砍掉最弱那档（把它并进 `textDim`）之后，白罩可以淡 4 倍，
 * 封面就留得住一层灰调影子，同时卡片还是亮的 —— 跟日间其它卡**风格统一**。
 *
 * 夜间不用改：深底浅字 → 区间是**上限** `[0, 0.0341]` → 封面被压暗，清晰可见。
 *
 * 组合卡两套配色的具体接线见 [com.cardesk.launcher.ui.pages.NavPage]。
 *
 * ## 怎么压：亮度映射（tone map），不是「叠一层半透明遮罩」
 *
 * 最初的实现是「把封面整体混向卡面色」：
 *
 * `
 * out = cover × (1 − a) + surface × a
 * `
 *
 * 为了让最亮的像素（纯白封面）落到上限，`a` 得取到 **0.92** ——
 * 也就是说**整张封面只剩下 8% 的份量**，所有像素都被拉到同一个亮度：
 * 实测整张卡是一片几乎均匀的深蓝灰，**封面等于没画**（只剩极淡的斜纹痕迹）。
 *
 * 现在改成**亮度映射**：先算出封面里最亮的那个像素有多亮，再把整张图的亮度
 * 按一条曲线**压进允许的区间**（`gamma < 1` 的幂曲线会把暗部提起来），
 * 缩放是在**线性光**空间做的，所以**色相不变**。
 *
 * 效果对比（同一张封面、同一张卡）：
 *
 * - 混向卡面：灰度范围 36~53 —— 一片糊掉的深蓝灰，看不出是张封面；
 * - 亮度映射：灰度范围 19~52 —— 渐变、亮斑、斜纹都还在，**一眼认得出是那张封面**。
 *
 * 这也是行业里「氛围背景」的标准做法：**压的是亮度上限，不是整张图的份量**。
 */
object CoverBackdrop {

    // ------------------------------------------------------------------ 规格常量

    /**
     * 模糊后小图的短边（px）。
     *
     * 组合卡大约 572×867，短边取 64 → 放大回全卡时是 **9 倍以上**，
     * 细节早就糊成色块了，正是我们要的「氛围底」而不是「贴一张小图」。
     */
    const val BLUR_SHORT_SIDE = 64

    /**
     * 最弱那档文字（`Skin.TEXT_DIM`）与背景的对比度底线。
     *
     * 3:1 = WCAG 对「大字号」文字的 AA 档。
     */
    const val MIN_CONTRAST = 3.0

    /**
     * 实际用的目标 —— 比 [MIN_CONTRAST] 严一点点。
     *
     * ## 为什么不是直接用 3.0
     *
     * 「正好卡在 3.0 边界上」时，浮点误差会让实际对比度变成 2.9999…，
     * 断言就得写成 `>= 2.99` 那种将就写法。
     * 这里留 0.05 的余量，落地后稳稳地在 3.05 以上。
     */
    private const val SOLVE_CONTRAST = MIN_CONTRAST + 0.05

    /**
     * 亮度映射的曲线指数（`t = (L / L_max) ^ gamma`）。
     *
     * 小于 1 → **暗部被提起来**：封面里那些本来很暗的区域不会糊成一团黑，
     * 整张图的结构（渐变、亮斑）才看得出来。
     */
    const val TONE_GAMMA = 0.45

    // ------------------------------------------------------------------ 颜色工具

    private fun red(c: Int): Int = (c shr 16) and 0xFF
    private fun green(c: Int): Int = (c shr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF

    /**
     * sRGB 通道 → 线性光查表（256 项）。
     *
     * 用查表而不是每次 `pow()`：
     * [toneMap] 要逐像素算几千次，查表能把这一轮压到毫秒级。
     */
    private val LINEAR = DoubleArray(256) { i ->
        val s = i / 255.0
        if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** 单通道的线性光值（0..1）。 */
    fun linear(channel: Int): Double = LINEAR[channel.coerceIn(0, 255)]

    /** 线性光 → sRGB 的 8bit 通道。 */
    private fun toSrgb(l: Double): Int {
        val s = if (l <= 0.0031308) l * 12.92 else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    /** 颜色的相对亮度（WCAG 定义，0..1）。 */
    fun relativeLuminance(color: Int): Double =
        0.2126 * linear(red(color)) +
            0.7152 * linear(green(color)) +
            0.0722 * linear(blue(color))

    /** 两个颜色的对比度（WCAG 定义，1..21）。 */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * 两个通道按 alpha 混合（Android `SRC_OVER` 的 8bit 算法）。
     *
     * @param alpha 0..255，不透明度
     */
    fun mixChannel(src: Int, dst: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return (src * (255 - a) + dst * a + 127) / 255
    }

    /** 两个颜色按 alpha 混合（alpha 作用于 `src`）。 */
    fun mixColor(src: Int, dst: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        val r = mixChannel(red(src), red(dst), a)
        val g = mixChannel(green(src), green(dst), a)
        val b = mixChannel(blue(src), blue(dst), a)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 灰度 → 不透明颜色（亮度分析用）。 */
    fun gray(value: Int): Int {
        val v = value.coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    // ------------------------------------------------------------------ 亮度区间

    /**
     * 背景亮度应该落在哪个区间（相对亮度）。
     *
     * @param surface 卡片面颜色（只用来判断「字亮底暗」还是「字暗底亮」）
     * @param textColor 卡片上**最弱**那档文字的颜色（约束由它决定）
     * @return `[下限, 上限]`
     */
    fun targetRange(surface: Int, textColor: Int): DoubleArray {
        val lText = relativeLuminance(textColor)
        val lSurface = relativeLuminance(surface)
        return if (lText >= lSurface) {
            // 文字亮 → 底必须够暗 → 区间是 [0, 上限]
            doubleArrayOf(0.0, (lText + 0.05) / SOLVE_CONTRAST - 0.05)
        } else {
            // 文字暗 → 底必须够亮 → 区间是 [下限, 1]
            doubleArrayOf(SOLVE_CONTRAST * (lText + 0.05) - 0.05, 1.0)
        }
    }

    /**
     * **浅色**文字压在背景上时，背景亮度的**上限**（相对亮度）。
     *
     * 由「该文字与背景的对比度 ≥ [SOLVE_CONTRAST]」反推：
     * `L_bg ≤ (L_text + 0.05) / SOLVE_CONTRAST − 0.05`
     *
     * ## 用途（第153轮）
     *
     * 组合卡改成「深色氛围卡」后，**卡面必须暗到能压住盖在上面的浅色字** ——
     * 用户自选的卡片色如果比这个上限还亮（比如日间默认的纯白），
     * 就得换成标准深色底，否则白字压不住。
     *
     * 传夜间最弱那档文字（`#757E8E`，`L_text = 0.2065`）算出来是 **0.0341**。
     */
    fun maxBackgroundLuminance(textColor: Int): Double =
        (relativeLuminance(textColor) + 0.05) / SOLVE_CONTRAST - 0.05

    /**
     * **深色**文字压在背景上时，背景亮度的**下限**（相对亮度）。
     *
     * 由「该文字与背景的对比度 ≥ [SOLVE_CONTRAST]」反推：
     * `L_bg ≥ SOLVE_CONTRAST × (L_text + 0.05) − 0.05`
     *
     * ## 用途（第153轮，日间模式）
     *
     * 日间是**白底深字**，所以封面要往「够亮」的方向罩，
     * 罩多浓由**最弱那档文字**决定：
     *
     * - 三档文字（最弱 `#8B92A0`，L=0.2859）→ 下限 **0.9743** → 封面被冲成纯白（等于没画）；
     * - **两档**文字（最弱 `#565D6B`，L=0.1087）→ 下限 **0.4340** → 白罩可以淡很多，
     *   封面还留得住一层很淡的灰调影子。
     *
     * 这就是日间能不能「风格统一又看得见封面」的分水岭。
     */
    fun minBackgroundLuminance(textColor: Int): Double =
        SOLVE_CONTRAST * (relativeLuminance(textColor) + 0.05) - 0.05

    // ------------------------------------------------------------------ 亮度映射

    /**
     * 亮度映射：把一组像素压进 `[lo, hi]` 的亮度区间，**色相保持不变**。
     *
     * 做法：
     * ① 找出这组像素里最亮的那个（`L_max`）；
     * ② 每个像素按 `t = (L / L_max) ^ gamma` 映射到目标亮度；
     * ③ 按「变暗 / 变亮」分别用两种**不会削顶**的方式落到目标亮度
     *    （见 [mapToLuminance]）；
     * ④ 8bit 取整可能让个别像素越界，最后逐个夹回去。
     *
     * @param pixels 待映射的像素（ARGB）
     * @param lo 目标亮度下限（相对亮度）
     * @param hi 目标亮度上限（相对亮度）
     * @param gamma 曲线指数（见 [TONE_GAMMA]）
     */
    fun toneMap(pixels: IntArray, lo: Double, hi: Double, gamma: Double = TONE_GAMMA): IntArray {
        if (pixels.isEmpty()) return pixels

        var lMax = 0.0
        for (p in pixels) {
            val l = relativeLuminance(p)
            if (l > lMax) lMax = l
        }
        if (lMax <= 0.0) return pixels

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val lIn = relativeLuminance(p)
            if (lIn <= 0.0) {
                out[i] = p
                continue
            }
            val t = Math.pow(lIn / lMax, gamma)
            val lOut = lo + (hi - lo) * t
            out[i] = mapToLuminance(p, lIn, lOut)
        }

        // 取整误差可能让个别像素越界 —— 逐个夹回去，保证不越界。
        //
        // ## 为什么不能「一次性统一缩放」
        //
        // 统一缩放的系数太小（差 0.6%），8bit 取整后**原封不动**：
        // 灰度 52 缩放后还是 52，来回多少次都压不下去。
        // 所以这里改成**逐像素往下踩**，每次缩 3% —— 一次就能掉 1~2 个灰阶。
        //
        // ## 踩过的坑：变亮方向用「固定 5% 混白」会**卡死**
        //
        // 日间模式的规格是**亮度下限**（`L_bg ≥ 0.9743`，白底深字）：
        // 任何比下限暗的像素都会让最弱那档文字掉到 3:1 以下。
        //
        // 而 `mixWhite(p, 0.05)` 在线性光空间混白：灰度 248 算出来是
        // `linear(248) + (1 − linear(248)) × 0.05` → 反算回 sRGB 仍是 **248**
        // —— **8bit 的量化台阶比步长还大，固定比例的步子永远跨不过去**，
        // 循环转 40 圈像素一动不动，亮度就停在规格线以下。
        //
        // 实测（真实 QQ 音乐封面）：日间模式下有一小撮像素卡在灰度 240~253，
        // 换成灰度 240 那个算，对比度只有 2.89:1 —— 低于 3.05 底线。
        //
        // 修法：变亮方向改成「**每通道至少 +1 个灰阶**」，
        // 一次就跨过一个量化台阶，必定能爬到下限之上。
        for (i in out.indices) {
            var p = out[i]
            var guard = 0
            while (guard < 260) {
                val l = relativeLuminance(p)
                if (l <= hi && l >= lo) break
                p = if (l > hi) scaleColor(p, 0.97) else brightenOneStep(p)
                guard++
            }
            out[i] = p
        }
        return out
    }

    /**
     * 变亮方向的「踩一步」：**每个通道 +1**（封顶 255）。
     *
     * ## 为什么不用固定比例
     *
     * 固定比例（哪怕 5%）在通道值已经很高时会被 8bit 取整吃掉 ——
     * 灰度 253 混 5% 白还是 253，像素永远出不来（见 [toneMap] 里的说明）。
     * 而「+1 个灰阶」是**离散**的，一定跨得过去。
     *
     * 日间目标本来就接近纯白，多抬几个灰阶不影响观感；
     * 色调也基本不变（各通道等量 +1 只会轻微降饱和）。
     */
    private fun brightenOneStep(color: Int): Int {
        val r = (red(color) + 1).coerceAtMost(255)
        val g = (green(color) + 1).coerceAtMost(255)
        val b = (blue(color) + 1).coerceAtMost(255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * 把一个颜色落到指定亮度，**色相不变、且不削顶**。
     *
     * ## 为什么要分「变暗 / 变亮」两套算法
     *
     * - **变暗**（夜间：把亮封面压下去）：在线性光空间整体乘一个系数。
     *   系数 ≤ 1，**不会削顶** ✓
     * - **变亮**（日间：白底深字，底要提亮）：如果也用「乘系数」，
     *   系数会远大于 1 —— 深蓝封面的蓝通道一乘就**冲到 255 削顶**，
     *   实际亮度掉回目标以下，「文字读得清」这条保证就破了。
     *   所以变亮改用「**向白色混合**」：`out = in + (white − in) × s`，
     *   系数 s ≤ 1，天然不削顶 ✓
     */
    private fun mapToLuminance(color: Int, lIn: Double, lOut: Double): Int {
        if (lOut <= lIn) {
            return scaleColor(color, lOut / lIn)
        }
        val headroom = 1.0 - lIn
        val s = if (headroom <= 1e-9) 1.0 else ((lOut - lIn) / headroom).coerceIn(0.0, 1.0)
        return mixWhite(color, s)
    }

    /** 在**线性光**空间整体缩放一个颜色（保持色相，亮度按比例走）。 */
    private fun scaleColor(color: Int, k: Double): Int {
        val r = toSrgb(linear(red(color)) * k)
        val g = toSrgb(linear(green(color)) * k)
        val b = toSrgb(linear(blue(color)) * k)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 在**线性光**空间向白色混合（`s` = 0 不变、1 = 全白）。 */
    private fun mixWhite(color: Int, s: Double): Int {
        val lr = linear(red(color))
        val lg = linear(green(color))
        val lb = linear(blue(color))
        val r = toSrgb(lr + (1.0 - lr) * s)
        val g = toSrgb(lg + (1.0 - lg) * s)
        val b = toSrgb(lb + (1.0 - lb) * s)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
