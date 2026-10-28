package com.cardesk.launcher.core

/**
 * 俯视车模的**几何**（纯 Kotlin，不依赖 Android，可 JVM 单测）。
 *
 * ## 为什么单独拆一层
 *
 * 第三十三轮的车模是「在 `ScenePage` 里现算现画」的：轮廓靠一串半宽站点 +
 * 二次贝塞尔中点插值。结果是
 *
 * - 车身变成一个**胶囊形**，看不出车头 / 车尾 / 发动机舱；
 * - 三段玻璃（前风挡 / 车顶 / 后风挡）和车身对不齐，中间出现空隙；
 * - 四个轮子画在车身**上层**，变成贴在车身上的白色方块。
 *
 * 第四轮改成「**分段构造**」：和一样，车身由四段构成 ——
 *
 * `
 *   鼻尖 ──三次贝塞尔──▶ 前翼子板肩线 ──直线──▶ 车身最宽处 ──直线──▶ 后翼子板 ──三次贝塞尔──▶ 尾端
 * `
 *
 * 直线侧腰和贝塞尔车头之间的**肩线是硬角**，这正是「像车」的关键：
 * 用样条把整圈轮廓都抹圆的话，车头会变成鹅卵石。
 *
 * ## 参照基准
 *
 * 设计空间是
 * `120 × 240`（`canvas.scale(width / 120f, height / 240f)`），车头朝上、中心线 `x = 60`：
 *
 * | 元素 |坐标 |
 * | --- | --- |
 * | 车身 | 鼻尖 `(60,8)` → 尾端 `(60,232)`，最大半宽 48（`x=12..108`） |
 * | 前轮 | `x=6..22` / `x=98..114`，`y=46..82`（**探出车身 6px**） |
 * | 后轮 | `x=6..22` / `x=98..114`，`y=162..198` |
 * | 前风挡 | 上沿宽（`x=28..92`）下沿窄（`x=34..86`），`y=60..100` |
 * | 车顶 | 圆角矩形 `x=36..84`，`y=108..170` |
 * | 后风挡 | 上沿窄（`x=34..86`）下沿宽（`x=28..92`），`y=178..210` |
 *
 * 车身路径就是
 *
 * `
 * moveTo(60,8)
 * cubicTo(86,8, 102,18, 104,42) // 车头
 * lineTo(108,112) // 上侧腰
 * lineTo(106,202) // 下侧腰
 * cubicTo(104,224, 88,232, 60,232) // 车尾
 * `
 *
 * 本文件把这四个关键点参数化（`nose` / `shoulder` / `midTop` / `midBottom` / `tail`），
 * 控制点比例值，轿车用原比例，SUV 用更方的比例。
 */

/** 设计空间中心线（车头朝上，车身左右对称轴 `x = 60`）。 */
const val CAR_CENTER_X = 60f

/** 设计空间里的一个点（车头朝上，中心线 `x = 60`）。 */
data class GeoPoint(val x: Float, val y: Float)

/** 设计空间里的一个矩形。 */
data class GeoRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** 矩形宽度。 */
    val width: Float get() = right - left

    /** 矩形高度。 */
    val height: Float get() = bottom - top
}

/**
 * 车身右半轮廓的一个站点。
 *
 * @param y 该站点在设计空间里的纵向坐标（车头朝上，y 越小越靠车头）
 * @param half 该处的**半宽**（到中心线 `x = 60` 的距离）
 */
data class HalfStation(val y: Float, val half: Float)

/**
 * 一块玻璃（前风挡 / 后风挡）。
 *
 * 两块玻璃都是**上沿带弧度**的四边形（前风挡上沿鼓起、后风挡下沿鼓起）
 * 这里用 [topBulge] / [bottomBulge] 描述鼓出的高度（设计空间 px，0 = 直边）。
 */
data class GlassQuad(
    /** 上沿左端。 */
    val topLeft: GeoPoint,
    /** 上沿右端。 */
    val topRight: GeoPoint,
    /** 下沿右端。 */
    val bottomRight: GeoPoint,
    /** 下沿左端。 */
    val bottomLeft: GeoPoint,
    /** 上沿鼓出的高度（向上为正）。 */
    val topBulge: Float = 0f,
    /** 下沿鼓出的高度（向下为正）。 */
    val bottomBulge: Float = 0f,
)

/** 一段三次贝塞尔（设计空间坐标）。 */
data class CubicSegment(val p0: GeoPoint, val c1: GeoPoint, val c2: GeoPoint, val p1: GeoPoint)

/**
 * 贝塞尔段在 `t` 处的点（`t ∈ [0,1]`）。
 */
fun CubicSegment.pointAt(t: Float): GeoPoint {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return GeoPoint(
        a * p0.x + b * c1.x + c * c2.x + d * p1.x,
        a * p0.y + b * c1.y + c * c2.y + d * p1.y,
    )
}

/**
 * 一辆车的俯视几何（设计空间 120 × 240）。
 *
 * 全部字段都是坐标，绘制代码只负责把它们翻译成 `Path` / `RectF`。
 */
data class CarTopGeometry(
    /** 鼻尖（中心线上）。 */
    val nose: GeoPoint,
    /** 前翼子板肩线（车身从圆头变直的地方）。 */
    val shoulder: GeoPoint,
    /** 车身最宽处（上半段）。 */
    val midTop: GeoPoint,
    /** 车身最宽处（下半段）。 */
    val midBottom: GeoPoint,
    /** 尾端（中心线上）。 */
    val tail: GeoPoint,
    /** 车头贝塞尔控制点 1 的伸出比例（相对肩线半宽，越大车头越方）。 */
    val noseReach: Float,
    /** 车头贝塞尔控制点 2 的下沉比例（相对车头长度，越小车头越方）。 */
    val noseDrop: Float,
    /** 尾巴贝塞尔控制点 1 的内收比例。 */
    val tailInset: Float,
    /** 尾巴贝塞尔控制点 1 的下沉比例。 */
    val tailDrop: Float,
    /** 尾巴贝塞尔控制点 2 的收口比例。 */
    val tailReach: Float,
    /** 前风挡。 */
    val windshield: GlassQuad,
    /** 车顶（圆角矩形）。 */
    val roof: GeoRect,
    /** 车顶圆角半径。 */
    val roofRadius: Float,
    /** 后风挡。 */
    val rearGlass: GlassQuad,
    /** 四个轮子（左前 / 右前 / 左后 / 右后）。 */
    val wheels: List<GeoRect>,
    /** 前灯（左右各一）。 */
    val headLights: List<GeoRect>,
    /** 尾灯（左右各一）。 */
    val tailLights: List<GeoRect>,
) {
    /** 鼻尖 y。 */
    val noseY: Float get() = nose.y

    /** 尾端 y。 */
    val tailY: Float get() = tail.y

    /** 车身长度。 */
    val length: Float get() = tail.y - nose.y

    /** 车身最大半宽。 */
    val maxHalf: Float get() = maxOf(shoulder.x, midTop.x, midBottom.x) - CAR_CENTER_X

    /** 肩线处的半宽。 */
    val shoulderHalf: Float get() = shoulder.x - CAR_CENTER_X

    /** 车头贝塞尔（右半，鼻尖 → 肩线）。 */
    val noseCubic: CubicSegment
        get() = CubicSegment(
            p0 = nose,
            c1 = GeoPoint(nose.x + shoulderHalf * noseReach, nose.y),
            c2 = GeoPoint(
                shoulder.x - shoulderHalf * 0.045f,
                nose.y + (shoulder.y - nose.y) * noseDrop,
            ),
            p1 = shoulder,
        )

    /** 尾巴贝塞尔（右半，后翼子板 → 尾端）。 */
    val tailCubic: CubicSegment
        get() = CubicSegment(
            p0 = midBottom,
            c1 = GeoPoint(
                midBottom.x - (midBottom.x - CAR_CENTER_X) * tailInset,
                midBottom.y + (tail.y - midBottom.y) * tailDrop,
            ),
            c2 = GeoPoint(
                tail.x + (midBottom.x - CAR_CENTER_X) * tailReach,
                tail.y,
            ),
            p1 = tail,
        )

    /**
     * 右半轮廓的**采样折线**（贝塞尔按步长采样 + 直线端点）。
     *
     * 用途：查某个 `y` 处车身有多宽（灯组贴着车身轮廓画，要用这个）。
     */
    fun profile(samples: Int = 12): List<HalfStation> {
        val out = ArrayList<HalfStation>(samples * 2 + 4)
        val noseC = noseCubic
        for (i in 0..samples) {
            val p = noseC.pointAt(i.toFloat() / samples)
            out.add(HalfStation(p.y, p.x - CAR_CENTER_X))
        }
        out.add(HalfStation(midTop.y, midTop.x - CAR_CENTER_X))
        out.add(HalfStation(midBottom.y, midBottom.x - CAR_CENTER_X))
        val tailC = tailCubic
        for (i in 1..samples) {
            val p = tailC.pointAt(i.toFloat() / samples)
            out.add(HalfStation(p.y, p.x - CAR_CENTER_X))
        }
        return out
    }

    /**
     * 某个 `y` 处的车身半宽（在采样折线上线性插值）。
     */
    fun halfAt(y: Float, samples: Int = 12): Float = halfAt(profile(samples), y)
}

/**
 * 某个 `y` 处的车身半宽（站点之间线性插值，超出范围取端点）。
 */
fun halfAt(stations: List<HalfStation>, y: Float): Float {
    if (stations.isEmpty()) return 0f
    if (y <= stations.first().y) return stations.first().half
    if (y >= stations.last().y) return stations.last().half
    for (i in 0 until stations.size - 1) {
        val a = stations[i]
        val b = stations[i + 1]
        if (y in a.y..b.y) {
            val span = (b.y - a.y).takeIf { it > 0f } ?: return a.half
            return a.half + (b.half - a.half) * (y - a.y) / span
        }
    }
    return stations.last().half
}

/**
 * 俯视车模几何工厂：车型 → 坐标。
 *
 * 车型族沿用 [CarModels]：轿车（`03` / `03+`）细长、SUV（`01` / `02` / `02HB` / `05`）方而宽。
 */
object CarTopGeometryFactory {

    /** 设计空间中心线。 */
    const val CENTER_X = CAR_CENTER_X

    /** 轮子宽度（轮子矩形宽 16）。 */
    const val WHEEL_W = 16f

    /** 轮子探出车身的距离（：轮子 `x=6..22`，车身 `x=12..108`）。 */
    const val WHEEL_OUT = 6f

    /**
     * 轿车轮廓（领克 03 / 03+）。
     *
     * 关键点**直接用坐标**
     * 鼻尖 `(60,8)`、肩线 `(104,42)`、最宽 `(108,112)` / `(106,202)`、尾端 `(60,232)`，
     * 贝塞尔控制点比例也，所以画出来和那张图是同一台车。
     */
    private val SEDAN = CarTopGeometry(
        nose = GeoPoint(60f, 8f),
        shoulder = GeoPoint(104f, 42f),
        midTop = GeoPoint(108f, 112f),
        midBottom = GeoPoint(106f, 202f),
        tail = GeoPoint(60f, 232f),
        // 比例：`cubicTo(86,8, 102,18, 104,42)` / `cubicTo(104,224, 88,232, 60,232)`
        noseReach = 26f / 44f,
        noseDrop = 10f / 34f,
        tailInset = 2f / 46f,
        tailDrop = 22f / 30f,
        tailReach = 28f / 46f,
        windshield = GlassQuad(
            topLeft = GeoPoint(60f - 32f, 60f),
            topRight = GeoPoint(60f + 32f, 60f),
            bottomRight = GeoPoint(60f + 26f, 100f),
            bottomLeft = GeoPoint(60f - 26f, 100f),
            topBulge = 9f,
        ),
        roof = GeoRect(60f - 26f, 108f, 60f + 26f, 170f),
        roofRadius = 8f,
        rearGlass = GlassQuad(
            topLeft = GeoPoint(60f - 26f, 178f),
            topRight = GeoPoint(60f + 26f, 178f),
            bottomRight = GeoPoint(60f + 32f, 202f),
            bottomLeft = GeoPoint(60f - 32f, 202f),
            bottomBulge = 8f,
        ),
        wheels = listOf(
            GeoRect(60f - 48f - WHEEL_OUT, 46f, 60f - 48f - WHEEL_OUT + WHEEL_W, 82f),
            GeoRect(60f + 48f + WHEEL_OUT - WHEEL_W, 46f, 60f + 48f + WHEEL_OUT, 82f),
            GeoRect(60f - 48f - WHEEL_OUT, 162f, 60f - 48f - WHEEL_OUT + WHEEL_W, 198f),
            GeoRect(60f + 48f + WHEEL_OUT - WHEEL_W, 162f, 60f + 48f + WHEEL_OUT, 198f),
        ),
        headLights = listOf(),
        tailLights = listOf(),
    )

    /**
     * SUV 轮廓（领克 01 / 02 / 02HB / 05）。
     *
     * 比轿车**短**（208 vs 224）、**宽**（半宽 48）、**方**：
     * 车头的贝塞尔控制点伸得更远（`noseReach` 0.59 → 0.68）、
     * 下沉更晚（`noseDrop` 0.29 → 0.20），画出来车头更平、更有方盒子感。
     */
    private val SUV = CarTopGeometry(
        nose = GeoPoint(60f, 16f),
        shoulder = GeoPoint(106f, 52f),
        midTop = GeoPoint(108f, 110f),
        midBottom = GeoPoint(106f, 192f),
        tail = GeoPoint(60f, 224f),
        noseReach = 0.68f,
        noseDrop = 0.20f,
        tailInset = 3f / 46f,
        tailDrop = 0.68f,
        tailReach = 30f / 46f,
        windshield = GlassQuad(
            topLeft = GeoPoint(60f - 35f, 56f),
            topRight = GeoPoint(60f + 35f, 56f),
            bottomRight = GeoPoint(60f + 33f, 96f),
            bottomLeft = GeoPoint(60f - 33f, 96f),
            topBulge = 8f,
        ),
        roof = GeoRect(60f - 33f, 104f, 60f + 33f, 166f),
        roofRadius = 8f,
        rearGlass = GlassQuad(
            topLeft = GeoPoint(60f - 33f, 174f),
            topRight = GeoPoint(60f + 33f, 174f),
            bottomRight = GeoPoint(60f + 35f, 200f),
            bottomLeft = GeoPoint(60f - 35f, 200f),
            bottomBulge = 7f,
        ),
        wheels = listOf(
            GeoRect(60f - 48f - WHEEL_OUT, 42f, 60f - 48f - WHEEL_OUT + WHEEL_W, 78f),
            GeoRect(60f + 48f + WHEEL_OUT - WHEEL_W, 42f, 60f + 48f + WHEEL_OUT, 78f),
            GeoRect(60f - 48f - WHEEL_OUT, 152f, 60f - 48f - WHEEL_OUT + WHEEL_W, 188f),
            GeoRect(60f + 48f + WHEEL_OUT - WHEEL_W, 152f, 60f + 48f + WHEEL_OUT, 188f),
        ),
        headLights = listOf(),
        tailLights = listOf(),
    )

    /**
     * 车型 → 几何。
     *
     * 灯组在这里**按车身轮廓算**：外沿跟着该处车身半宽走，
     * 这样不管车型怎么改，灯都贴在车身上、不会跑到轮廓外面去。
     */
    fun of(modelId: String): CarTopGeometry {
        val base = when (CarModels.bodyOf(modelId)) {
            CarModels.Body.SUV -> SUV
            CarModels.Body.SEDAN -> SEDAN
        }
        val profile = base.profile()
        return base.copy(
            headLights = lights(profile, base.nose.y + 14f),
            tailLights = lights(profile, base.tail.y - 14f),
        )
    }

    /**
     * 沿车身轮廓内侧生成一对灯组（左右各一）。
     *
     * @param profile 右半轮廓采样
     * @param y 灯组中心的 y
     */
    private fun lights(profile: List<HalfStation>, y: Float): List<GeoRect> {
        val half = halfAt(profile, y) - LIGHT_INSET
        if (half <= LIGHT_W) return emptyList()
        return listOf(
            GeoRect(CAR_CENTER_X - half, y - LIGHT_THICK / 2f, CAR_CENTER_X - half + LIGHT_W, y + LIGHT_THICK / 2f),
            GeoRect(CAR_CENTER_X + half - LIGHT_W, y - LIGHT_THICK / 2f, CAR_CENTER_X + half, y + LIGHT_THICK / 2f),
        )
    }

    /** 灯组外沿离车身轮廓的距离。 */
    private const val LIGHT_INSET = 4f

    /** 灯组长度（沿车宽方向）。 */
    private const val LIGHT_W = 11f

    /** 灯组厚度（沿车长方向）。 */
    private const val LIGHT_THICK = 7f
}
