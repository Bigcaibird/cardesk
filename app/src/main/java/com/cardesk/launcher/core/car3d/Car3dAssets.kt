package com.cardesk.launcher.core.car3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import kotlin.math.abs
import org.json.JSONObject

/**
 * 写实车模的**运行时开关**。
 *
 * 偏好键 `car3d_enabled`（走 [Prefs.flag] / [Prefs.setFlag]，不新增 Prefs 字段）。
 * 默认**开**；关掉后 `Car3dPainter.paint` 立刻返回 false，所有槽位回到
 * `CarTopPainter` 的自绘俯视车模（不重建页面）。
 *
 * 进程内的 [enabled] 是**一次读取**的副本（每秒重绘时读一个 volatile boolean，
 * 不去碰 SharedPreferences），由 `LauncherView` 启动时 [load] 一次、
 * 设置页切换时 [set] 同步写入。
 */
object Car3dSettings {

    /** 偏好键。 */
    const val KEY = "car3d_enabled"

    /** 车漆颜色偏好键（值见 [PAINT_DARK] / [PAINT_WHITE] / [PAINT_BLACK]）。 */
    const val KEY_PAINT = "car3d_paint"

    /** 车况卡视角偏好键（值见 [CARD_VIEW_HERO] / [CARD_VIEW_CARDTOP]）。 */
    const val KEY_CARD_VIEW = "car3d_card_view"

    /** 车况卡视角：3/4 侧视（默认，`hero/<color>/`）。 */
    const val CARD_VIEW_HERO = Car3dAssets.VIEW_HERO

    /** 车况卡视角：俯视（`cardtop/<color>/`，缺资产时自动回退 3/4）。 */
    const val CARD_VIEW_CARDTOP = Car3dAssets.VIEW_CARDTOP

    /** 车况卡视角：正前 3/4（低机位 `card/<color>/`，接近参考图观感）。 */
    const val CARD_VIEW_FRONT = Car3dAssets.VIEW_CARD

    /** 车况卡视角白名单（顺序 = 设置页顺序）。 */
    val CARD_VIEWS = listOf(CARD_VIEW_HERO, CARD_VIEW_CARDTOP, CARD_VIEW_FRONT)

    /** 车漆：深灰（默认）。 */
    const val PAINT_DARK = "dark"

    /** 车漆：白。 */
    const val PAINT_WHITE = "white"

    /** 车漆：黑。 */
    const val PAINT_BLACK = "black"

    /** 车漆白名单（顺序 = 设置页顺序）。 */
    val PAINTS = listOf(PAINT_DARK, PAINT_WHITE, PAINT_BLACK)

    /**
     * 当前车漆（进程内副本，绘制线程直接读）。
     *
     * 资产路径为 `hero/<color>/<state>.webp`；asset 没有这一色时
     * [Car3dAssets.effectiveColor] 会回退 `model.json` 的 `defaultColor`，
     * 再不行走老路径 `hero/<state>.webp` —— 换色永远不会画成空白。
     */
    @Volatile
    var paint: String = PAINT_DARK

    /**
     * 车况卡用哪个视角（进程内副本，绘制线程直接读）。
     *
     * 默认 [CARD_VIEW_HERO]（3/4 侧视）；用户可在设置页切到 [CARD_VIEW_CARDTOP]（俯视）。
     * 俯视资产缺失时绘制层自动回退 3/4（见 [Car3dAssets.viewChain]），不会空白。
     */
    @Volatile
    var cardView: String = CARD_VIEW_HERO

    /** 进程内的开关副本（默认开：资产缺失会自己静默回退，不存在「打不开」问题）。 */
    @Volatile
    var enabled: Boolean = true

    /** 从偏好读一次（`LauncherView` 构造时调；无 Context 版本，默认色只能退回深灰）。 */
    fun load(prefs: Prefs) {
        apply(prefs, paintDefault = PAINT_DARK)
        debugInstalled = false
    }

    /**
     * 从偏好读一次，**默认车漆按资产声明**（`model.json` 的 `defaultColor`）。
     *
     * 第166轮用户反馈「默认车漆选黑色」：颜色默认值不在代码里写死，而是读
     * `assetDir(prefs.carModel)` 那份资产的 `defaultColor`（烘焙侧改成 black，
     * 换车型/换资产时默认色跟着走）。用户手动选过的 prefs 值仍然优先、不覆盖。
     */
    fun load(context: android.content.Context, prefs: Prefs) {
        val dir = CarModels.assetDir(prefs.carModel)
        val assetDefault = dir
            ?.let { Car3dAssets.model(context, it)?.defaultColor }
            ?.takeIf { it.isNotEmpty() }
            ?: PAINT_DARK
        apply(prefs, paintDefault = assetDefault)
        Car3dDebugPose.install(context)
        debugInstalled = true
    }

    private fun apply(prefs: Prefs, paintDefault: String) {
        enabled = prefs.flag(KEY, true)
        val raw = prefs.stringValue(KEY_PAINT, "")
        paint = if (raw.isEmpty()) normalizePaint(paintDefault) else normalizePaint(raw)
        cardView = normalizeCardView(prefs.stringValue(KEY_CARD_VIEW, ""))
    }

    /** 设置页切换车漆：同时更新进程内副本与偏好（调用方负责触发重绘）。 */
    fun setPaint(prefs: Prefs, color: String) {
        val c = normalizePaint(color)
        paint = c
        prefs.setStringValue(KEY_PAINT, c)
    }

    /** 设置页切换车况卡视角：同时更新进程内副本与偏好。 */
    fun setCardView(prefs: Prefs, view: String) {
        val v = normalizeCardView(view)
        cardView = v
        prefs.setStringValue(KEY_CARD_VIEW, v)
    }

    /** 白名单校验（未知值一律回 default）。 */
    fun normalizePaint(color: String?): String =
        if (color != null && PAINTS.contains(color)) color else PAINT_DARK

    /** 白名单校验（未知值一律回 3/4 侧视）。 */
    fun normalizeCardView(view: String?): String =
        if (view != null && CARD_VIEWS.contains(view)) view else CARD_VIEW_HERO

    /** 设置页切换：同时更新进程内副本与偏好。 */
    fun set(prefs: Prefs, on: Boolean) {
        enabled = on
        prefs.setFlag(KEY, on)
    }

    /**
     * **卡片**（车况卡 88×176 / 胎压卡 72×144）是否也用写实烘焙图。
     *
     * 第164轮实测判定：**默认 `false`**。88×176 上真车只占 41×85 px（宽 47%、高 48%），
     * 全关与开窗两态肉眼不可分、单门开只剩一条细线 —— 装不下写实俯视。
     * 卡片继续用 [com.cardesk.launcher.core.CarTopPainter] 的橙色实心面板，状态一眼可读；
     * 写实图只用在这个尺寸够大的「车门未关」弹窗里（`hero` 3/4 视角）。
     *
     * `card` 视角的资产与绘制路径**保留**（烘焙侧已出图、测试也覆盖了），
     * 以后槽位变大或做「车况详情页」时可以把它打开。
     */
    @Volatile
    var cardEnabled: Boolean = false

    /** 调试姿态广播是否已装（只用于日志/测试断言）。 */
    @Volatile
    internal var debugInstalled: Boolean = false
}

/**
 * `car3d` 资产仓库：`assets/car3d/<dir>/model.json` + 位图（懒加载 + 小 LRU）。
 *
 * ## 目录结构（规范 §2）
 *
 * ```
 * assets/car3d/lynk01/
 *   model.json
 *   top/closed.webp  top/lf.webp  top/all.webp  top/win.webp  top/trunk.webp ...
 *   top/anim_lf_0.webp ... anim_win_2.webp ... anim_trunk_5.webp
 *   hero/closed.webp ...            # 可选（本版运行时不用 hero）
 * ```
 *
 * 图片扩展名先试 `webp`（最终烘焙产物），失败再试 `png`（本仓库的占位图）。
 *
 * ## 三条硬规则
 *
 * 1. **不预载**：进桌面时不读任何资产，第一次真的要画（车况卡可见）才解码；
 * 2. **只解一次**：解码结果按「目录/视角/图名/目标尺寸」进 LRU，最多 [MAX_BITMAPS] 张、
 *    最多 [MAX_BYTES] 字节（卡片 79×158≈50KB、弹窗 251×448≈450KB，够两边各留几帧）；
 * 3. **失败永久**：[model] 解析失败 → 该车型目录**永久**返回 null；
 *    某张图缺失 → 记进 [missing]（不再重试 IO）；
 *    连 `top/closed` 都找不到 → [broken] 记死，调用方永久回退 `CarTopPainter`。
 *
 * 全部方法都应只在主线程（绘制线程）调用；内部仍用 `synchronized` 兜住测试/预览的多线程。
 */
object Car3dAssets {

    /** 俯视视角（车头朝上）。 */
    const val VIEW_TOP = "top"

    /** 左前 3/4 视角（「车门未关」弹窗用：横向大图，能看见车窗）。 */
    const val VIEW_HERO = "hero"

    /**
     * 卡片增强视角（88×176）：同一张俯视图的**深底提亮 + 轮廓光**版本。
     *
     * 卡片槽位只有 88×176，直接用 280×500 的俯视图缩下去会发灰、边缘糊；
     * 烘焙侧单独出这一档，缺图时回退 [VIEW_TOP]（画质差一点，但不会空白）。
     */
    const val VIEW_CARD = "card"

    /**
     * 车辆状态弹窗专用小图（340×300 左右，烘焙侧**直接出小图**）。
     *
     * 把 680×380 的 hero 缩到 300 高会糊（气泡/轮毂细节全丢），所以弹窗单独一档；
     * 缺图时回退 hero → top。
     */
    const val VIEW_DIALOG = "dialog"

    /**
     * 车况卡的**俯视**视角（256×188 横置俯视）：`cardtop/<color>/<state>.webp`。
     *
     * 与 [VIEW_HERO] 二选一，由 `Car3dSettings.cardView` 决定（设置页「车况卡视角」）。
     * 缺这套资产时 [viewChain] 会依次回退 hero → top，画面不会空白。
     */
    const val VIEW_CARDTOP = "cardtop"

    /**
     * 「正前 3/4」低机位家族（712×500）的最小可接受槽位。
     *
     * 低于这个尺寸说明是老版 88×176 的 `card/`（旧车况卡的窄槽位），不能铺大图。
     */
    const val CARD_VIEW_MIN_SLOT_W = 400
    const val CARD_VIEW_MIN_SLOT_H = 280

    /** `model.json` 的 `format` 固定值。 */
    const val FORMAT = "cardesk.car3d/1"

    /** 连续失败多少次才把车型记死（单次弹窗失败不该污染卡片）。 */
    private const val FAILS_BEFORE_BROKEN = 3

    /** 全关状态名（动画自检要与它首帧对齐）。 */
    private const val STATE_CLOSED = "closed"

    /**
     * 动画自检容差：首帧与 `closed` 的平均单通道差超过它，就判「这段动画不可用」。
     *
     * 实测：同机位只差压缩噪声 < 6；不同机位（v4 的 anim 家族 1.22× 放大 + 底片）≈ 32。
     */
    private const val ANIM_MATCH_TOLERANCE = 12.0

    private const val MAX_BITMAPS = 8

    /** 4MB 位图预算（规范 §6：PSS ≤ +4MB）。 */
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val EXTENSIONS = arrayOf("webp", "png")

    /** 视角描述（尺寸只用于日志/诊断，实际解码按调用方目标尺寸）。 */
    data class ViewSpec(
        val masterW: Int,
        val masterH: Int,
        val slotW: Int,
        val slotH: Int,
    )

    /** 一组动画：总时长 [ms]、帧数 [frames]。 */
    data class AnimSpec(val frames: Int, val ms: Int)

    /** 解析好的 `model.json`。 */
    class Model(
        val dir: String,
        val model: String,
        val name: String,
        val views: Map<String, ViewSpec>,
        val states: Set<String>,
        val anims: Map<String, AnimSpec>,
        val attribution: String,
        val colors: List<String> = emptyList(),
        val defaultColor: String = "",
        /**
         * 归一化锚点：视角 → 键（`lf/rf/lb/rb/trunk` 与 `tires.fl/fr/rl/rr`）→ [CardSnapshot.NormPoint]。
         *
         * 第167轮快照卡的胶囊位置；资产没写就是空表（绘制侧走「沿车模框均匀排布」兜底）。
         */
        val anchors: Map<String, Map<String, CardSnapshot.NormPoint>> = emptyMap(),
        /**
         * 车窗黄色贴片：视角 → 窗 id（`lf/rf/lb/rb` 或按侧 `l/r`）→ 归一化矩形。
         *
         * 第170轮资产契约 `views.<view>.winpatches.<id> = [nx,ny,nw,nh]`；缺失 = 该窗被挡死，
         * 绘制侧在锚点处画小黄点兜底。
         */
        val winpatches: Map<String, Map<String, NormRect>> = emptyMap(),
    )

    /** 归一化矩形（0..1，y 向下），与锚点同口径。 */
    data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float)

    /** 颜色声明的查找顺序：先看分色出图的视角。 */
    private val COLOR_VIEW_ORDER = listOf("hero", "dialog", "card", "cardtop", "top")

    private val models = HashMap<String, Model?>()

    private val broken = HashSet<String>()

    private val missing = HashSet<String>()

    /** 每个车型连续取图失败的次数（见 [stateBitmapNamed] 的记死规则）。 */
    private val failures = HashMap<String, Int>()

    /** 「动画帧与状态图同机位」的自检结果（`dir/view/kind` → 可用）。 */
    private val animUsable = HashMap<String, Boolean>()

    private val bitmaps = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {}

    private var bytes = 0

    /** 最近一次失败原因（诊断/测试用，正常路径为 null）。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 读并缓存 `model.json`。
     *
     * @return 解析失败/目录不存在 → `null`（**永久**，不会每次重试刷 IO）
     */
    /** 缺资产时回退的车型目录（01 是内置主资产）。 */
    const val FALLBACK_DIR = "lynk01"

    /**
     * 实际可用的资产目录：`dir` 能解析就用它，否则回退 [FALLBACK_DIR]；都没有 → `null`。
     *
     * 目的：切到还没烘焙的车型（如 03）时不崩、不空白，而是先拿 01 的图顶着。
     */
    fun effectiveDir(context: Context, dir: String?): String? {
        if (dir.isNullOrEmpty()) return null
        if (model(context, dir) != null) return dir
        if (dir == FALLBACK_DIR) return null
        return if (model(context, FALLBACK_DIR) != null) FALLBACK_DIR else null
    }

    fun model(context: Context, dir: String?): Model? {
        if (dir.isNullOrEmpty()) return null
        synchronized(this) {
            if (broken.contains(dir)) return null
            models[dir]?.let { return it }
            if (models.containsKey(dir)) return models[dir] // 已解析失败
            val parsed = runCatching { parse(context, dir) }.getOrNull()
            if (parsed == null) {
                models[dir] = null
                markBroken(dir, "model.json 缺失或解析失败")
                return null
            }
            models[dir] = parsed
            return parsed
        }
    }

    /** 该车型目录是否已判定不可用（永久）。 */
    fun isBroken(dir: String?): Boolean = dir != null && synchronized(this) { broken.contains(dir) }

    /**
     * 取一张状态图（已缩放到 `w × h`）。
     *
     * 降级链：`<view>/<state>` → `<view>/closed` → `top/<state>` → `top/closed`；
     * [VIEW_CARD] 例外：**只认 card 目录**，缺图直接 `null`（调用方回退 2D，不拿 top 硬塞）。
     * top 也全找不到 → `null` 并把该车型记死（永久回退 2D）。
     */
    fun stateBitmap(
        context: Context,
        dir: String?,
        view: String,
        state: String,
        w: Int,
        h: Int,
        color: String? = null,
    ): Bitmap? = stateBitmapNamed(context, dir, view, state, w, h, color)?.first

    /**
     * 同 [stateBitmap]，但把**实际命中的状态名**一并返回。
     *
     * 门缝橙色高亮（`<state>__gap`）必须画在「真正画出来的那张底图」上：
     * 请求 `lf_rf_lb` 但资产只有 `lf_rf` 时，要叠的是 `lf_rf__gap` 而不是 `lf_rf_lb__gap`。
     */
    fun stateBitmapNamed(
        context: Context,
        dir: String?,
        view: String,
        state: String,
        w: Int,
        h: Int,
        color: String? = null,
    ): Pair<Bitmap, String>? {
        if (w <= 0 || h <= 0) return null
        val d = effectiveDir(context, dir) ?: return null
        val m = model(context, d) ?: return null
        val c = effectiveColor(m, color ?: Car3dSettings.paint)
        val views = viewChain(m, view)
        val states = stateChain(state)
        for (v in views) {
            for (s in states) {
                fetch(context, d, v, s, w, h, c)?.let {
                    synchronized(this) { failures.remove(d) }
                    return it to s
                }
            }
        }
        // 第179轮：不能因为**单次取图失败**（例如老弹窗请求 dialog 视角而资产是 top-only）
        // 就把整个车型记死 —— 那会连带卡片也回退 2D。规则：
        //  · 资产声明了 top 家族却连 top/closed 都解不出来 → 真坏，立刻记死；
        //  · 否则只有连续 [FAILS_BEFORE_BROKEN] 次失败才记死（避免刷 IO，也不会误伤）。
        val declaredTop = m.views?.containsKey(VIEW_TOP) == true
        val fails = synchronized(this) {
            val n = (failures[d] ?: 0) + 1
            failures[d] = n
            n
        }
        if (declaredTop || fails >= FAILS_BEFORE_BROKEN) {
            markBroken(d, "连续 $fails 次解不出任何状态（回退 2D）")
        }
        return null
    }

    /**
     * 门缝橙色高亮贴片（`<state>__gap`，俯视家族用）。
     *
     * 资产安装时会把「该状态相对 closed 变化的细边」染成橙色单独出一张；这里只按
     * **实际命中的状态**找，找不到就返回 null（调用方直接跳过，不画任何替代标记）。
     * 窗开（`win`）没有 overlay —— 窗的开关只由卡面文字表达。
     */
    fun gapBitmap(
        context: Context,
        dir: String?,
        view: String,
        state: String,
        w: Int,
        h: Int,
        color: String? = null,
    ): Bitmap? {
        if (w <= 0 || h <= 0) return null
        if (state == CarPoseResolver.STATE_WINDOW || state == CarPoseResolver.STATE_CLOSED) return null
        val d = effectiveDir(context, dir) ?: return null
        val m = model(context, d) ?: return null
        val c = effectiveColor(m, color ?: Car3dSettings.paint)
        for (v in viewChain(m, view)) {
            fetch(context, d, v, "${state}__gap", w, h, c)?.let { return it }
        }
        return null
    }

    /**
     * 取动画的第 [frame] 帧（已缩放到 `w × h`）。
     *
     * 缺帧 → `null`（调用方退回状态图，不会画成空白）。
     */
    fun animBitmap(
        context: Context,
        dir: String?,
        view: String,
        kind: String,
        frame: Int,
        w: Int,
        h: Int,
        color: String? = null,
    ): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val d = effectiveDir(context, dir) ?: return null
        val m = model(context, d) ?: return null
        val c = effectiveColor(m, color ?: Car3dSettings.paint)
        for (v in viewChain(m, view)) {
            fetch(context, d, v, "anim_${kind}_$frame", w, h, c)?.let { return it }
        }
        return null
    }

    /**
     * 动画帧能不能和状态图**无缝衔接**（自检，一次结果永久缓存）。
     *
     * 烘焙侧必须保证「`anim_<kind>_0` = `closed`」且动画帧与状态图**同一机位/同一缩放**
     * —— 否则 220ms 动画播完跳回状态图时会看到整车位移/缩放。这个自检就是防这种事：
     * 首帧与 `closed` 对不上 → 判这段动画不可用，**运行时直接不播**（仍然画目标状态），
     * 产品不会出现「滑一下再跳回来」，等资产修好后自动恢复，无需改代码。
     *
     * 比较在半分辨率（slotW/2 × slotH/2）上做，只算两边都不透明的像素。
     */
    fun canAnimate(
        context: Context,
        dir: String?,
        view: String,
        kind: String,
        slotW: Int,
        slotH: Int,
        color: String? = null,
    ): Boolean {
        val d0 = effectiveDir(context, dir) ?: return false
        val w = (slotW / 2).coerceAtLeast(1)
        val h = (slotH / 2).coerceAtLeast(1)
        val c = effectiveColor(model(context, d0) ?: return false, color ?: Car3dSettings.paint) ?: "-"
        val key = "$d0/$view/$kind/$c@${w}x$h"
        synchronized(this) { animUsable[key]?.let { return it } }
        val first = animBitmap(context, d0, view, kind, 0, w, h, color)
        val closed = stateBitmap(context, d0, view, STATE_CLOSED, w, h, color)
        val ok = first != null && closed != null && meanChannelDiff(first, closed) < ANIM_MATCH_TOLERANCE
        synchronized(this) { animUsable[key] = ok }
        return ok
    }

    /** 两张同尺寸位图在「两边都不透明」像素上的平均单通道差（0..255）。 */
    private fun meanChannelDiff(a: Bitmap, b: Bitmap): Double {
        val n = minOf(a.width, b.width) * minOf(a.height, b.height)
        if (n <= 0) return Double.MAX_VALUE
        val pa = IntArray(n)
        val pb = IntArray(n)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var sum = 0L
        var count = 0
        val step = 2 // 隔像素采样：自检不要求亚像素精度，省一半时间
        var i = 0
        while (i < n) {
            val x = pa[i]
            val y = pb[i]
            if ((x ushr 24) >= 128 && (y ushr 24) >= 128) {
                sum += abs((x ushr 16 and 0xFF) - (y ushr 16 and 0xFF))
                sum += abs((x ushr 8 and 0xFF) - (y ushr 8 and 0xFF))
                sum += abs((x and 0xFF) - (y and 0xFF))
                count++
            }
            i += step
        }
        return if (count == 0) Double.MAX_VALUE else sum.toDouble() / count
    }

    /** 清空所有缓存（Robolectric 测试之间互不影响）。 */
    fun reset() {
        synchronized(this) {
            for (b in bitmaps.values) b.recycle()
            bitmaps.clear()
            models.clear()
            broken.clear()
            missing.clear()
            failures.clear()
            animUsable.clear()
            bytes = 0
            lastError = null
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 视角降级链。
     *
     * - [VIEW_CARD]：**只认 card**。card 是 88×176 的专属构图（深底提亮 + 轮廓光），
     *   把 280×500 的 top 竖图塞进 88×176 会糊成一团 —— 缺图就整块回退 2D 自绘车模，
     *   「宁可没有 3D，也不要错的 3D」。
     * - 其它视角（[VIEW_HERO] / [VIEW_TOP]）：请求的视角优先，缺了回退 top。
     *
     * 视角目录是烘焙侧**后补**的，所以不要求 `model.json` 的 `views` 里先声明。
     */
    private fun viewChain(m: Model, view: String): List<String> {
        if (view == VIEW_CARD) {
            val out = ArrayList<String>(3)
            if (usableView(m, VIEW_CARD)) out.add(VIEW_CARD)
            out.add(VIEW_HERO)
            out.add(VIEW_TOP)
            return out
        }
        val out = ArrayList<String>(3)
        if (view.isNotEmpty()) out.add(view)
        // 卡片俯视 ↔ 3/4 侧视是一对互退的视角：任一份缺失都不该空白。
        when (view) {
            VIEW_CARDTOP, VIEW_DIALOG -> out.add(VIEW_HERO)
        }
        if (view != VIEW_TOP) out.add(VIEW_TOP)
        // 第179轮：top-only 新资产（只有 cardtop 家族）—— 弹窗/3/4 请求没有任何声明视角时，
        // 最后兜底到 cardtop（按目标矩形等比放大，允许轻微发虚），而不是直接判缺图。
        if (out.none { m.views?.containsKey(it) == true }) out.add(VIEW_CARDTOP)
        return out
    }

    /**
     * 这份资产里有没有某个视角的图（只看 `model.json` 的 views 声明）。
     *
     * 设置页用它决定「俯视」那一项是否可用：没有 `cardtop` 就提示会先按 3/4 画。
     */
    fun hasView(context: Context, dir: String?, view: String): Boolean {
        val d = effectiveDir(context, dir) ?: return false
        return usableView(model(context, d), view)
    }

    /**
     * 实际会用的视角：从 [view] 起按 `viewChain` 找第一个「资产里声明且尺寸可用」的视角。
     *
     * 降级链可能落到**不同比例**的视角（如 card 缺失 → hero），调用方要按返回值的
     * 槽位比例算绘制矩形，否则会把 3/4 图塞进竖槽（第167轮踩过）。
     */
    /**
     * 该车型是否「纯俯视」资产：只声明了 cardtop（没有 hero / card / top）。
     *
     * 用户拍板后 01 已迁到这套方案；03/05 烘焙完成后自动生效，
     * 运行时不再按车型 id 写死判断。
     */
    fun topDownOnly(context: Context, dir: String?): Boolean {
        val m = model(context, effectiveDir(context, dir)) ?: return false
        return isTopDownOnly(m)
    }

    /**
     * 资产是否「纯俯视」：只声明了 `cardtop`，没有 hero/card/top 这些可切换视角。
     *
     * 抽成纯函数是为了让设置页与单测都能判（[topDownOnly] 只是带 Context 的取图版本）。
     */
    fun isTopDownOnly(m: Model): Boolean {
        if (!m.views.containsKey(VIEW_CARDTOP)) return false
        return !m.views.containsKey(VIEW_HERO) &&
            !m.views.containsKey(VIEW_CARD) &&
            !m.views.containsKey(VIEW_TOP)
    }

    fun usedView(context: Context, dir: String?, view: String): String {
        val m = model(context, effectiveDir(context, dir)) ?: return view
        for (v in viewChain(m, view)) {
            if (m.views.containsKey(v) && usableView(m, v)) return v
        }
        return view
    }

    /**
     * 某个视角声明的槽位尺寸（宽, 高）；没声明 → `null`。
     *
     * 快照卡用它算车模的等比 `fit` 矩形（锚点也按实际绘制矩形换算）。
     */
    fun slotOf(context: Context, dir: String?, view: String): Pair<Int, Int>? {
        val v = model(context, effectiveDir(context, dir))?.views?.get(view) ?: return null
        if (v.slotW <= 0 || v.slotH <= 0) return null
        return v.slotW to v.slotH
    }

    /** 某视角的归一化锚点表（空 = 资产未提供 → 绘制侧兜底）。 */
    fun anchors(context: Context, dir: String?, view: String): Map<String, CardSnapshot.NormPoint>? {
        val m = model(context, effectiveDir(context, dir)) ?: return null
        return m.anchors[view]?.takeIf { it.isNotEmpty() }
    }

    /**
     * 车窗贴片的归一化矩形（第170轮）。
     *
     * id 查找顺序：`lf` → `l`、`rf` → `r`（按侧出图的老契约），都没有 → `null`。
     */
    fun winPatch(context: Context, dir: String?, view: String, id: String): NormRect? {
        val m = model(context, effectiveDir(context, dir)) ?: return null
        val map = m.winpatches[view] ?: return null
        return map[id] ?: map[id.take(1)]
    }

    /** 读某个窗的黄色贴片，缩放到 [w]×[h]；资产没这张图 → `null`。 */
    fun winPatchBitmap(
        context: Context,
        dir: String?,
        view: String,
        id: String,
        w: Int,
        h: Int,
        color: String? = null,
    ): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val d = effectiveDir(context, dir) ?: return null
        val m = model(context, d) ?: return null
        val c = effectiveColor(m, color ?: Car3dSettings.paint)
        // 文件也按「先 lf 再 l」兼容
        for (name in listOf("win_$id", "win_${id.take(1)}")) {
            val bmp = decodeScaled(context, d, view, c, name, w, h)
            if (bmp != null) return bmp
        }
        return null
    }

    /**
     * 视角是否**可用**（第167轮）：存在且槽位够大。
     *
     * `card` 家族历史上是 88×176 的小图（旧卡槽位），不能拿来铺 356×250 的大区域；
     * 烘焙侧新版 `card` 是 712×500。用「槽宽 ≥ [CARD_VIEW_MIN_SLOT_W]」区分，
     * 旧 small card 视为不可用 → 自动回退 hero（而不是拉糊）。
     */
    private fun usableView(m: Model?, view: String): Boolean {
        val v = m?.views?.get(view) ?: return false
        if (view == VIEW_CARD) {
            return v.slotW >= CARD_VIEW_MIN_SLOT_W && v.slotH >= CARD_VIEW_MIN_SLOT_H
        }
        return v.slotW > 0 && v.slotH > 0
    }

    /**
     * 当前请求色在**这份资产**里最终用哪个目录；`null` = 走老路径（无 `colors` 声明）。
     *
     * `model.json` 没有 `colors` 时（第165轮之前的资产）返回 null，
     * `decodeScaled` 会直接读 `hero/<state>.webp`，设置项换色不生效但不会空白。
     */
    fun effectiveColor(m: Model, requested: String?): String? {
        if (m.colors.isEmpty()) return null
        val req = requested?.takeIf { it.isNotEmpty() }
        if (req != null && m.colors.contains(req)) return req
        val def = m.defaultColor.takeIf { it.isNotEmpty() && m.colors.contains(it) }
        return def ?: m.colors.firstOrNull()
    }

    /**
     * 状态 key 的降级链（第175轮：任意门组合，按**门数递减**回退）。
     *
     * 例：`lf_rf_lb` → `lf_rf`/`lf_lb`/`rf_lb` → `lf`/`rf`/`lb` → `all` → `closed`。
     * 带尾门时同一级先试 `_trunk` 版本，再无尾门版本；最后 `trunk` → `closed`。
     * 保证任何组合都有图可画，永远不空白。
     */
    private fun stateChain(state: String): List<String> {
        val out = ArrayList<String>(8)
        fun add(s: String) {
            if (s.isNotEmpty() && s !in out) out.add(s)
        }
        val (doors, trunk) = CarPoseResolver.splitState(state)
        if (doors.isEmpty()) {
            when {
                state == CarPoseResolver.STATE_WINDOW -> {
                    add(CarPoseResolver.STATE_WINDOW)
                    add(CarPoseResolver.STATE_CLOSED)
                }
                state == CarPoseResolver.STATE_CLOSED -> add(CarPoseResolver.STATE_CLOSED)
                else -> {
                    // `all` / `closed_trunk` / `all_trunk`（新 32 态命名）
                    // 以及老资产的 `trunk`（单尾门）。
                    add(state)
                    if (state.endsWith("_trunk")) {
                        val base = state.removeSuffix("_trunk")
                        if (base == CarPoseResolver.STATE_ALL) {
                            add(CarPoseResolver.STATE_ALL)
                            add(CarPoseResolver.STATE_TRUNK)
                        } else {
                            // closed_trunk → 老资产的 trunk → closed
                            add(CarPoseResolver.STATE_TRUNK)
                            add(base)
                        }
                    }
                    if (state != CarPoseResolver.STATE_TRUNK) add(CarPoseResolver.STATE_CLOSED)
                }
            }
            return out
        }
        for (k in doors.size downTo 1) {
            for (subset in combinations(doors, k)) {
                val base = subset.joinToString("_")
                if (trunk) add("${base}_trunk")
                add(base)
            }
        }
        if (doors.size < CarPose.DOOR_COUNT) {
            add(if (trunk) "${CarPoseResolver.STATE_ALL}_trunk" else CarPoseResolver.STATE_ALL)
        }
        if (trunk) add(CarPoseResolver.STATE_TRUNK)
        add(CarPoseResolver.STATE_CLOSED)
        return out
    }

    /** 保序组合（用于门组合降级链）。 */
    private fun combinations(items: List<String>, k: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        val cur = ArrayList<String>(k)
        fun go(start: Int) {
            if (cur.size == k) {
                out.add(ArrayList(cur))
                return
            }
            for (i in start until items.size) {
                cur.add(items[i])
                go(i + 1)
                cur.removeAt(cur.size - 1)
            }
        }
        go(0)
        return out
    }

    /** 读文件 + 解码 + 缩放到目标尺寸；结果进 LRU。 */
    private fun fetch(
        context: Context,
        dir: String,
        view: String,
        name: String,
        w: Int,
        h: Int,
        color: String?,
    ): Bitmap? {
        val colorKey = color ?: "-"
        val key = "$dir/$view/$colorKey/$name@$w x $h"
        synchronized(this) {
            bitmaps[key]?.let { return it }
        }
        val missingKey = "$dir/$view/$colorKey/$name"
        if (missing.contains(missingKey)) return null
        val bmp = decodeScaled(context, dir, view, color, name, w, h)
        synchronized(this) {
            if (bmp == null) {
                missing.add(missingKey)
                if (missing.size <= 4) {
                    lastError = "缺图 $dir/$view/$name"
                    Logx.w(LogTags.SCENE, "car3d 缺图（回退到就近状态）", "path" to "$dir/$view/$name")
                }
                return null
            }
            bitmaps[key] = bmp
            bytes += bmp.byteCount
            trimLocked()
        }
        return bmp
    }

    private fun trimLocked() {
        val it = bitmaps.entries.iterator()
        while ((bitmaps.size > MAX_BITMAPS || bytes > MAX_BYTES) && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.byteCount
            e.value.recycle()
            it.remove()
        }
    }

    private fun decodeScaled(
        context: Context,
        dir: String,
        view: String,
        color: String?,
        name: String,
        w: Int,
        h: Int,
    ): Bitmap? {
        // 同一色先试带色目录；再退回老路径（无 colors 的资产、以及 top/ 下的动画帧）。
        val dirs = ArrayList<String>(2)
        if (!color.isNullOrEmpty()) dirs.add("car3d/$dir/$view/$color")
        dirs.add("car3d/$dir/$view")
        for (base in dirs) for (ext in EXTENSIONS) {
            val path = "$base/$name.$ext"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val ok = runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            }.isSuccess
            if (!ok || bounds.outWidth <= 0 || bounds.outHeight <= 0) continue

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull() ?: continue
            if (raw.width == w && raw.height == h) return raw
            val scaled = runCatching { Bitmap.createScaledBitmap(raw, w, h, true) }.getOrNull()
            if (scaled == null) {
                raw.recycle()
                continue
            }
            if (scaled !== raw) raw.recycle()
            return scaled
        }
        return null
    }

    private fun parse(context: Context, dir: String): Model? {
        val text = context.assets.open("car3d/$dir/model.json").bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT) {
            lastError = "format 不匹配：${o.optString("format")}"
            return null
        }
        val views = HashMap<String, ViewSpec>()
        val winpatches = HashMap<String, Map<String, NormRect>>()
        o.optJSONObject("views")?.let { vo ->
            val keys = vo.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val v = vo.optJSONObject(key) ?: continue
                v.optJSONObject("winpatches")?.let { po ->
                    val patches = HashMap<String, NormRect>()
                    val pk = po.keys()
                    while (pk.hasNext()) {
                        val id = pk.next()
                        val a = po.optJSONArray(id) ?: continue
                        if (a.length() < 4) continue
                        val r = NormRect(
                            a.optDouble(0, 0.0).toFloat(),
                            a.optDouble(1, 0.0).toFloat(),
                            a.optDouble(2, 0.0).toFloat(),
                            a.optDouble(3, 0.0).toFloat(),
                        )
                        if (r.w > 0f && r.h > 0f) patches[id] = r
                    }
                    if (patches.isNotEmpty()) winpatches[key] = patches
                }
                val master = v.optJSONArray("master")
                val slot = v.optJSONArray("slot")
                views[key] = ViewSpec(
                    master?.optInt(0, 0) ?: 0,
                    master?.optInt(1, 0) ?: 0,
                    slot?.optInt(0, 0) ?: 0,
                    slot?.optInt(1, 0) ?: 0,
                )
            }
        }
        if (views.isEmpty()) {
            lastError = "model.json 没有 views"
            return null
        }
        val states = HashSet<String>()
        o.optJSONObject("states")?.let { so ->
            val keys = so.keys()
            while (keys.hasNext()) states.add(keys.next())
        }
        val anims = HashMap<String, AnimSpec>()
        o.optJSONObject("anim")?.let { ao ->
            val groups = ao.keys()
            while (groups.hasNext()) {
                val group = groups.next()
                val g = ao.optJSONObject(group) ?: continue
                val frames = g.optInt("frames", 0)
                val ms = g.optInt("ms", 0)
                if (frames <= 0 || ms <= 0) continue
                val kinds = g.optJSONArray("kinds")
                if (kinds != null && kinds.length() > 0) {
                    for (i in 0 until kinds.length()) {
                        val k = kinds.optString(i, "")
                        if (k.isNotEmpty()) anims[k] = AnimSpec(frames, ms)
                    }
                } else {
                    anims[group] = AnimSpec(frames, ms)
                }
            }
        }
        // 车漆颜色：约定允许写在 model.json 顶层，也允许挂在某个视角下
        // （烘焙侧实际放在 `views.hero.colors`，因为只有 hero 分色出图）。
        val colors = ArrayList<String>()
        o.optJSONArray("colors")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { colors.add(it) }
            }
        }
        var defaultColor = o.optString("defaultColor", "")
        if (colors.isEmpty()) {
            for (viewName in COLOR_VIEW_ORDER) {
                val v = o.optJSONObject("views")?.optJSONObject(viewName) ?: continue
                val arr = v.optJSONArray("colors") ?: continue
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { colors.add(it) }
                }
                if (defaultColor.isEmpty()) defaultColor = v.optString("defaultColor", "")
                break
            }
        }
        val anchors = HashMap<String, Map<String, CardSnapshot.NormPoint>>()
        o.optJSONObject("anchors")?.let { ao ->
            val viewKeys = ao.keys()
            while (viewKeys.hasNext()) {
                val viewName = viewKeys.next()
                val vo = ao.optJSONObject(viewName) ?: continue
                val points = HashMap<String, CardSnapshot.NormPoint>()
                val keys = vo.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (key) {
                        "tires" -> {
                            val to = vo.optJSONObject("tires") ?: continue
                            val tk = to.keys()
                            while (tk.hasNext()) {
                                val t = tk.next()
                                val p = to.optJSONArray(t) ?: continue
                                points["tires.$t"] = CardSnapshot.NormPoint(
                                    p.optDouble(0, 0.0).toFloat(),
                                    p.optDouble(1, 0.0).toFloat(),
                                )
                            }
                        }
                        else -> {
                            val p = vo.optJSONArray(key) ?: continue
                            points[key] = CardSnapshot.NormPoint(
                                p.optDouble(0, 0.0).toFloat(),
                                p.optDouble(1, 0.0).toFloat(),
                            )
                        }
                    }
                }
                if (points.isNotEmpty()) anchors[viewName] = points
            }
        }
        return Model(
            dir = dir,
            model = o.optString("model", dir),
            name = o.optString("name", dir),
            views = views,
            states = states,
            anims = anims,
            attribution = o.optString("attribution", ""),
            colors = colors,
            defaultColor = defaultColor,
            anchors = anchors,
            winpatches = winpatches,
        )
    }

    private fun markBroken(dir: String, reason: String) {
        synchronized(this) {
            if (broken.add(dir)) {
                lastError = reason
                Logx.w(LogTags.SCENE, "car3d 该车型不可用，永久回退自绘车模", "dir" to dir, "reason" to reason)
            }
        }
    }
}
