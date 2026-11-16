package com.cardesk.launcher.core.car3d

/** `car3d` 动画的种类（也是资产文件名 `anim_<kind>_<i>` 里的 `<kind>`）。 */
enum class CarAnimKind(val key: String) {
    /** 左前门。 */
    LF("lf"),

    /** 右前门。 */
    RF("rf"),

    /** 左后门。 */
    LB("lb"),

    /** 右后门。 */
    RB("rb"),

    /** 车窗（一整套「窗升起/落下」的帧，不区分是哪一扇）。 */
    WINDOW("win"),

    /** 尾门。 */
    TRUNK("trunk"),
}

/**
 * 一次姿态变化对应的动画。
 *
 * @param reverse `true` = 倒放（关回去），`false` = 正放（打开）。
 */
data class CarAnim(val kind: CarAnimKind, val reverse: Boolean)

/**
 * [CarPoseResolver] 的结果。
 *
 * @param stateKey 资产目录下要画的图片名（`closed` / `lf` / `all` / `win` / `trunk` ...）
 * @param anim 需要播的动画；`null` = 直接切到目标状态图
 * @param unknown 有部件读不到（画角标）
 */
data class CarResolution(
    val stateKey: String,
    val anim: CarAnim?,
    val unknown: Boolean,
) {
    companion object {
        /** 一张图都找不到时的兜底（调用方会回退到 `CarTopPainter`）。 */
        val CLOSED: CarResolution = CarResolution(CarPoseResolver.STATE_CLOSED, null, unknown = true)
    }
}

/**
 * 姿态 → 策展状态 key 的解析器（纯函数，可单测）。
 *
 * ## 为什么不做 2^9 组合
 *
 * 四门 + 四窗 + 尾门 + 引擎盖 = 512 种组合，每张图 300KB 级，装不进 APK。
 * 所以资产只出一小撮「策展状态」（~8 张/视角）+ 3 组动画帧：
 *
 * | key | 含义 |
 * | --- | --- |
 * | `closed` | 全关 |
 * | `lf` / `rf` / `lb` / `rb` | 只有某一扇门开 |
 * | `all` | 两扇及以上门开，或（弹窗大图里）门 + 窗同时开 |
 * | `win` | 只有窗开 |
 * | `trunk` | 只有尾门开 |
 *
 * 找不到精确组合时**就近降级**（详下），永远不返回空图。
 *
 * ## 解析顺序（与规范 §3.4 一致）
 *
 * 1. 门 ≥ 3 扇开 → `all`；门恰好 2 扇开 → 双门状态（`lf_rf` 等，缺图时由绘制层回退 `all`）；
 * 2. 门 1 扇开 + 窗也开 → 弹窗大图（[RICH_MIN_WIDTH_PX] 以上）用 `all`，卡片上用该门状态
 *    （79×158 的卡片上「窗开没开」看不出来，门开着更显眼）；
 * 3. 门 1 扇开、窗全关 → 该门的单门状态；
 * 4. 门全关：尾门开 → `trunk`；窗开 → `win`；都关 → `closed`；
 * 5. 尾门 + 窗同时开（数据上少见）→ 尾门优先（尾门整块掀起来更显眼）。
 *
 * ## 动画选择
 *
 * 多部件同时变化时只播**一个**（规范：变化最大的那个）：门 > 尾门 > 窗。
 * 关回去是倒放；反向（播到一半又要求反着来）由 `Car3dPainter` 从当前帧接上。
 */
object CarPoseResolver {

    const val STATE_CLOSED = "closed"
    const val STATE_ALL = "all"
    const val STATE_WINDOW = "win"
    const val STATE_TRUNK = "trunk"

    /** 四门各自的单门状态 key（下标同 [CarPose.doors]）。 */
    val DOOR_STATES: List<String> = listOf("lf", "rf", "lb", "rb")

    private val DOOR_KINDS: List<CarAnimKind> =
        listOf(CarAnimKind.LF, CarAnimKind.RF, CarAnimKind.LB, CarAnimKind.RB)

    /**
     * 「大图」门槛（设计像素宽）。
     *
     * 弹窗里车模是 280×500（≈251×448 实际绘制），卡片里是 88×176 / 72×144。
     * 180 这个分界只用来决定「门 + 窗同时开时用 all 还是单门图」。
     */
    const val RICH_MIN_WIDTH_PX = 180f

    /**
     * 解析目标姿态。
     *
     * @param previous 上一帧的姿态；`null` = 首次绘制（不播动画，直接出目标图）
     * @param next 这一帧的姿态
     * @param rich 是否大图槽位（弹窗 = true）
     */
    fun resolve(previous: CarPose?, next: CarPose, rich: Boolean): CarResolution =
        CarResolution(stateKey(next, rich), animOf(previous, next), next.unknown)

    /**
     * 姿态 → 状态 key（第175轮：任意门组合）。
     *
     * 规则（用户 m02705）：
     * - 按固定顺序 `lf,rf,lb,rb` 收集打开的门，`join("_")`；
     * - 0 扇 → `closed`；4 扇 → `all`；
     * - 尾门开时追加 `_trunk`（`lf_rf_trunk` / `trunk` / `all_trunk`）。
     *
     * 车窗**不参与**状态 key：俯视卡里窗的开合靠卡面文字表达（资产不再有 win 贴片）。
     * 唯一的例外是「门与尾门全关、只有窗开」→ `win`，保持对老资产（03/05）的兼容；
     * 俯视家族没有 `win` 图时 [com.cardesk.launcher.core.car3d.Car3dAssets.stateChain]
     * 会把它降级到 `closed`，不会空白。
     */
    fun stateKey(pose: CarPose, rich: Boolean): String {
        val openDoors = (0 until CarPose.DOOR_COUNT).filter { pose.doors[it] }
        if (openDoors.isEmpty()) {
            return when {
                // 32 态资产的命名：门全关 + 尾门开 = `closed_trunk`（老资产是 `trunk`）。
                pose.trunk -> "closed_trunk"
                pose.anyWindowOpen -> STATE_WINDOW
                else -> STATE_CLOSED
            }
        }
        val base = if (openDoors.size == CarPose.DOOR_COUNT) {
            STATE_ALL
        } else {
            openDoors.joinToString("_") { DOOR_STATES[it] }
        }
        return if (pose.trunk) "${base}_trunk" else base
    }

    /**
     * 一个状态 key 拆成「门组合 + 是否带尾门」。
     *
     * 纯函数，给 `stateChain` 的降级与单测用。
     */
    fun splitState(state: String): Pair<List<String>, Boolean> {
        val trunk = state.endsWith("_trunk") || state == "trunk"
        var base = if (state.endsWith("_trunk")) state.removeSuffix("_trunk") else state
        if (base == "trunk") base = STATE_CLOSED
        val doors = when (base) {
            STATE_CLOSED, STATE_ALL, STATE_WINDOW -> emptyList()
            else -> base.split("_").filter { it in DOOR_STATES }
        }
        return doors to trunk
    }

    /** 与上一帧相比该播哪个动画；`null` = 不播。 */
    fun animOf(previous: CarPose?, next: CarPose): CarAnim? {
        if (previous == null) return null
        for (i in 0 until CarPose.DOOR_COUNT) {
            if (previous.doors[i] != next.doors[i]) {
                return CarAnim(DOOR_KINDS[i], reverse = !next.doors[i])
            }
        }
        if (previous.trunk != next.trunk) {
            return CarAnim(CarAnimKind.TRUNK, reverse = !next.trunk)
        }
        for (i in 0 until CarPose.DOOR_COUNT) {
            if (previous.windows[i] != next.windows[i]) {
                return CarAnim(CarAnimKind.WINDOW, reverse = !next.windows[i])
            }
        }
        return null
    }
}
