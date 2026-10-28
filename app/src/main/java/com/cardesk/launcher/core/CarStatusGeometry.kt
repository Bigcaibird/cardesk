package com.cardesk.launcher.core

/**
 * 车况面板的**几何**（纯 Kotlin，不依赖 Android，可 JVM 单测）。
 *
 * ## 用途
 *
 * 第三十六轮新增的「车况卡」要在**俯视车模**上标出每一个车门、车窗、后备箱、引擎盖的
 * 开闭状态。这里只负责算「这些面板在设计空间（120 × 240）里的矩形」，
 * 画法交给 `ScenePage`。
 *
 * ## 面板的摆法
 *
 * 俯视图里，门是**车身两侧的门皮**（车身外沿 ↔ 侧窗外沿），
 * 窗是**紧贴车顶玻璃外侧的一条玻璃带**，后备箱在**车尾**，引擎盖在**车头**：
 *
 * `
 *        引擎盖（hood）
 *   ┌──────────────────────┐
 *   │ 前风挡 │
 *  ╔╡ 门皮 │侧窗│ 前风挡 ╞╗ ← 门皮 = 车身外沿 ↔ 侧窗外沿
 *  ║ 门皮 │侧窗│ ║
 *   │ 后风挡 │
 *   │ 后备箱 │
 *   └──────────────────────┘
 * `
 *
 * 纵向位置全部**从 [CarTopGeometry] 自己的地标推**（风挡下沿 / 车顶上下沿 / 后风挡上沿 /
 * 前后轮位置，所以换车型时面板自动跟着变，不需要再手调一套坐标。
 */
data class CarStatusGeometry(
    /** 前左门。 */
    val frontLeftDoor: GeoRect,
    /** 前右门。 */
    val frontRightDoor: GeoRect,
    /** 后左门。 */
    val rearLeftDoor: GeoRect,
    /** 后右门。 */
    val rearRightDoor: GeoRect,
    /** 前左窗。 */
    val frontLeftWindow: GeoRect,
    /** 前右窗。 */
    val frontRightWindow: GeoRect,
    /** 后左窗。 */
    val rearLeftWindow: GeoRect,
    /** 后右窗。 */
    val rearRightWindow: GeoRect,
    /** 后备箱盖。 */
    val trunk: GeoRect,
    /** 引擎盖。 */
    val hood: GeoRect,
) {
    /**
     * 面板矩形列表，**顺序与 [CarStatus.items] 的下标一一对应**：
     *
     * `
     * 0 左前门 1 右前门 2 左后门 3 右后门 4 后备箱
     * 5 左前窗 6 右前窗 7 左后窗 8 右后窗 9 引擎盖
     * `
     *
     * 这样画车模叠加层时，可以直接用下标取矩形、用同一下标取状态，
     * 两边不会错位（单测 会校验这个对应关系）。
     */
    fun rects(): List<GeoRect> = listOf(
        frontLeftDoor,
        frontRightDoor,
        rearLeftDoor,
        rearRightDoor,
        trunk,
        frontLeftWindow,
        frontRightWindow,
        rearLeftWindow,
        rearRightWindow,
        hood,
    )
}

/**
 * 车况面板几何工厂：从 [CarTopGeometry] 推出各面板矩形。
 */
object CarStatusGeometryFactory {

    /** 窗带宽度（沿车宽方向，设计空间 px）。 */
    private const val WINDOW_BAND = 11f

    /** 窗带距车顶玻璃的留缝。 */
    private const val WINDOW_INSET = 1f

    /** 后备箱盖的半宽（设计空间 px）。 */
    private const val TRUNK_HALF = 20f

    /** 引擎盖的半宽（设计空间 px）。 */
    private const val HOOD_HALF = 18f

    /**
     * 面板几何。
     *
     * @param geo 车型几何（`CarTopGeometryFactory.of(modelId)`）
     */
    fun of(geo: CarTopGeometry): CarStatusGeometry = build(geo)

    private fun build(geo: CarTopGeometry): CarStatusGeometry {
        val profile = geo.profile()

        // —— 纵向分段 ——
        // 前门从「前风挡下沿」到「车顶 42% 处」，后门从那之后到「车顶 92% 处」，
        // 但不能压到后轮（后门的尾边要在后轮前）。
        val frontTop = geo.windshield.bottomLeft.y
        val roofTop = geo.roof.top
        val roofBottom = geo.roof.bottom
        val frontBottom = roofTop + (roofBottom - roofTop) * 0.42f
        val rearTop = frontBottom + 2f
        val rearWheelTop = geo.wheels.getOrNull(2)?.top ?: (roofBottom + 20f)
        val rearBottom = minOf(roofTop + (roofBottom - roofTop) * 0.92f, rearWheelTop - 2f)

        // —— 横向：门皮 / 侧窗 / 车顶玻璃，三段并排 ——
        //
        //
        //   车身外沿 13 27 35 36 84 85 93 107 车身外沿
        //
        //            门皮 侧窗 │ 车顶玻璃 │ 侧窗 门皮
        //
        //
        // 侧窗紧贴玻璃外侧、门皮在更外侧，**两者不重叠**——
        // 重叠的话「门开着」的高亮会把窗户也盖住，看不出到底是门还是窗开了。
        val frontMidY = (frontTop + frontBottom) / 2f
        val halfFront = halfAt(profile, frontMidY)
        val halfRear = halfAt(profile, (rearTop + rearBottom) / 2f)

        val roofLeft = geo.roof.left
        val roofRight = geo.roof.right
        val winLeftOuter = roofLeft - WINDOW_BAND
        val winLeftInner = roofLeft - WINDOW_INSET
        val winRightInner = roofRight + WINDOW_INSET
        val winRightOuter = roofRight + WINDOW_BAND

        val flDoor = GeoRect(
            CAR_CENTER_X - halfFront + 1f, frontTop,
            winLeftOuter, frontBottom,
        )
        val frDoor = GeoRect(
            winRightOuter, frontTop,
            CAR_CENTER_X + halfFront - 1f, frontBottom,
        )
        val rlDoor = GeoRect(
            CAR_CENTER_X - halfRear + 1f, rearTop,
            winLeftOuter, rearBottom,
        )
        val rrDoor = GeoRect(
            winRightOuter, rearTop,
            CAR_CENTER_X + halfRear - 1f, rearBottom,
        )

        // —— 窗带：紧贴车顶玻璃外侧的一条玻璃 ——
        val winTopPad = 4f
        val winBottomPad = 4f

        return CarStatusGeometry(
            frontLeftDoor = flDoor,
            frontRightDoor = frDoor,
            rearLeftDoor = rlDoor,
            rearRightDoor = rrDoor,
            frontLeftWindow = GeoRect(winLeftOuter, frontTop + winTopPad, winLeftInner, frontBottom - winBottomPad),
            frontRightWindow = GeoRect(winRightInner, frontTop + winTopPad, winRightOuter, frontBottom - winBottomPad),
            rearLeftWindow = GeoRect(winLeftOuter, rearTop + winTopPad, winLeftInner, rearBottom - winBottomPad),
            rearRightWindow = GeoRect(winRightInner, rearTop + winTopPad, winRightOuter, rearBottom - winBottomPad),
            // 后备箱：车尾一块
            trunk = GeoRect(
                CAR_CENTER_X - TRUNK_HALF,
                geo.tail.y - 26f,
                CAR_CENTER_X + TRUNK_HALF,
                geo.tail.y - 4f,
            ),
            // 引擎盖：车头一块（避开前轮与前风挡）
            hood = GeoRect(
                CAR_CENTER_X - HOOD_HALF,
                geo.nose.y + 10f,
                CAR_CENTER_X + HOOD_HALF,
                geo.nose.y + 30f,
            ),
        )
    }
}

/**
 * 车况卡右侧的**状态条目**（纯逻辑，可单测）。
 *
 * @param label 条目名（门 / 窗 / 盖）
 * @param open true = 开，false = 关，null = 读不到
 */
data class CarStatusItem(val label: String, val open: Boolean?)

/**
 * 把「四门 + 四窗 + 后备箱 + 引擎盖」拼成车况卡右侧的条目列表。
 *
 * 顺序固定为两列：
 * - 左列：左前门 / 右前门 / 左后门 / 右后门 / 后备箱
 * - 右列：左前窗 / 右前窗 / 左后窗 / 右后窗 / 引擎盖
 *
 * 读不到的条目用 `null` 表示，UI 要显示成「--」而不是「关」——
 * 「读不到」和「关着」是两回事（这条口径与 `VehicleStats.interpretDoorClosed` 一致）。
 */
object CarStatus {

    /** 四个门的位置名（下标同 `AdaptapiBody.DOOR_ZONES`：主驾 / 副驾 / 左后 / 右后）。 */
    val DOOR_LABELS = arrayOf("左前门", "右前门", "左后门", "右后门")

    /** 四个窗的位置名（下标 FL / FR / RL / RR）。 */
    val WINDOW_LABELS = arrayOf("左前窗", "右前窗", "左后窗", "右后窗")

    /**
     * 生成条目列表（长度固定 10：前 5 个左列、后 5 个右列）。
     *
     * @param doors 四门是否关闭（true = 关），下标 FL / FR / RL / RR；
     *   **逐门独立**（第四十五轮）：某一扇读不到时那一项是 null，其余照常
     * @param windows 四窗是否打开（true = 开），下标 FL / FR / RL / RR；null = 读不到
     * @param hood 引擎盖是否关闭（true = 关）；null = 读不到
     * @param trunk 后备箱是否关闭（true = 关）；null = 读不到
     */
    fun items(
        doors: List<Boolean?>?,
        windows: BooleanArray?,
        hood: Boolean?,
        trunk: Boolean?,
    ): List<CarStatusItem> {
        val out = ArrayList<CarStatusItem>(10)
        for (i in 0 until 4) {
            out.add(CarStatusItem(DOOR_LABELS[i], doors?.getOrNull(i)?.let { !it }))
        }
        out.add(CarStatusItem("后备箱", trunk?.let { !it }))
        for (i in 0 until 4) {
            out.add(CarStatusItem(WINDOW_LABELS[i], windows?.getOrNull(i)))
        }
        out.add(CarStatusItem("引擎盖", hood?.let { !it }))
        return out
    }

    /** 状态文案：`开` / `关` / `--`（读不到）。 */
    fun stateText(open: Boolean?): String = when (open) {
        true -> "开"
        false -> "关"
        null -> "--"
    }
}
