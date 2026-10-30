package com.cardesk.launcher.ui.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.WeatherGlyphs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 顶部状态栏（布局 view_design_status：1920×1080 覆盖层）。
 *
 * **第五十二轮：整行元素统一缩到 2/3**（用户反馈「状态栏占的空间还是太大了」）。
 * 缩放口径 = 乘 2/3 后取整，行内所有间距 / 字号 / 图标尺寸同步缩，
 * 因此左右两段仍然对齐、内容基线整体上移。
 *
 * 左：36px 头像 + 29px 高"玻璃胶囊"（8px 状态点 + 13px 文案）。
 * 右：蓝牙/热点/WiFi/信号 17×17 图标，燃油图标 16px + 13px 百分比，25px 时间，
 *     **以及位置 + 天气两个胶囊**（第八十二轮从中置挪过来）。
 * 全部自绘，图标用 Path/圆角矩形近似（内部按 17×17 画，用 [drawIcon] 统一缩放）。
 *
 * **第八十二轮：状态栏正中空出来** —— 原先摆在中间的「位置 + 天气」挪到了右侧图标行，
 * 正中改放**翻页指示圆点**（见 `PageDotsView`）。
 *
 * 第四十三轮曾整体缩小一档、第四十四轮按用户要求**恢复成原值**
 * 第五十二轮按用户要求**再缩到 2/3**（车机屏幕小，状态栏原来吃掉太多竖向空间）。
 * 顶部渐变遮罩在第四十九轮「浮屿」版式里已去掉。
 *
 * **第六十三轮：行内元素放大一档，上下空隙收窄**（用户反馈「字和图标放大一点，
 * 状态栏上下都有一段空间」）。口径 = **状态栏这一行的整体大小不动**
 * （[ROW_TOP] / [ROW_H] / [EDGE] 一律不改），只把行内的字与图标放大，
 * 让元素在原行高里占得更满 —— 行内元素是垂直居中的，字号图标一大，
 * 元素上下的空隙自然就小了。
 * 放大幅度取「第五十二轮 2/3」与「原值」之间（约为原值的 0.8~0.85）。
 */
class ShellStatusBar(context: Context) : View(context) {

    /**
     * 状态胶囊文案：
     * 形如「驾驶模式 · 待命」（缺省为「待命」）。
     */
    var assistText: String = "驾驶模式 · 待命"
        set(v) {
            val changed = field != v
            field = v
            // 排障场景：「驾驶模式胶囊不更新」——胶囊文案变化落盘。
            if (changed) {
                Logx.i(LogTags.DOCK, "状态栏驾驶模式胶囊变化", "assist" to v)
            }
            invalidate()
        }

    /**
     * 燃油百分比。**-1 = 车端没有数据**，显示布局的默认值 `--%`
     * （`android:text="--%"`）。
     */
    var fuelPercent: Int = FUEL_UNKNOWN_VALUE
        set(v) { field = v; invalidate() }

    /** 时钟文案（外部直接给字符串时用这个）。 */
    var clockText: String = "20:35"
        set(v) { field = v; invalidate() }

    var rangeText: String = ""
        set(v) { field = v; invalidate() }

    // -------------------------------------------------- 位置 + 天气（第四轮新增）

    /**
     * 状态栏右侧的**位置**文案（城市名，来自天气/定位链路）。
     *
     * 空串 = 还没拿到位置（画「位置获取中」）。
     */
    var locationText: String = ""

    /** 状态栏的**天气**描述（决定天气图标，取值同 `WeatherDesk.KEY_SKY`）。 */
    var weatherSky: String = ""

    /** 状态栏的**天气**温度（整数）；null = 没有天气数据（画 `--°`）。 */
    var weatherTemp: Int? = null

    /**
     * 一次性刷新「位置 + 天气」。
     *
     * 三个字段合成一个入口，是为了**只在数据真的变了时打一条日志**：
     * 天气链路一次刷新会带回城市 / 天气 / 温度三个字段，
     * 分开打会变成三条噪音日志，而且看不出是不是同一次刷新。
     *
     * @param location 城市名（空 = 还没定位到）
     * @param sky 天气描述
     * @param temp 温度（null = 无数据）
     */
    fun setLocationWeather(location: String, sky: String, temp: Int?) {
        val loc = location.trim()
        if (loc == locationText && sky == weatherSky && temp == weatherTemp) return
        locationText = loc
        weatherSky = sky
        weatherTemp = temp
        // 排障场景：「状态栏上的位置/天气不显示或一直不更新」——
        // 城市、天气、温度一起落盘，能看出到底是哪一环没数据。
        Logx.i(
            LogTags.SYS,
            "状态栏位置天气刷新",
            "location" to loc,
            "sky" to sky,
            "temp" to (temp ?: TEMP_UNKNOWN_VALUE),
        )
        invalidate()
    }

    /**
     * 用格式刷新时钟：`SimpleDateFormat("H:mm", Locale.CHINA)`
     * （**单小时不补零**）。
     */
    fun setClock(date: Date) {
        clockText = CLOCK.format(date)
        // 排障场景：「状态栏时钟不刷新」——时钟每秒刷新一次，必须用 DEBUG（不落盘）。
        Logx.d(LogTags.DOCK, "状态栏时钟刷新", "clock" to clockText)
    }

    /** 点头像 / 点状态胶囊。 */
    var onAvatar: (() -> Unit)? = null
    var onAssist: (() -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val fuelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 17f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        style = Paint.Style.FILL
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.CHIP_FILL }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.CHIP_STROKE
        style = Paint.Style.STROKE
        strokeWidth = 0.9f
    }
    private val locationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT_SOFT
        textSize = 17f
    }
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 22f
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
        strokeCap = Paint.Cap.ROUND
    }
    private val pinDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.ACCENT_LIGHT }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.TEXT_DIM }
    /** 顶部渐变遮罩（沉浸式）。 */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarRect = RectF()

    /**
     * 驾驶模式胶囊的矩形。
     *
     * **第八十二轮：必须独立出来。**
     * 以前位置/天气胶囊画在屏幕正中，和左侧的驾驶模式胶囊隔得很远，
     * 所以「用同一个 RectF 复用」没暴露问题；现在天气胶囊挪到**右侧图标行**，
     * 两者虽然还是分开的，但复用的那个 RectF 会被**最后画的那个胶囊覆盖**
     * —— 点驾驶模式胶囊时 `contains` 判的是天气胶囊的位置，就再也点不开了。
     * 命中判定和绘制用的矩形必须一一对应，所以拆成三个独立的。
     */
    private val assistRect = RectF()

    /** 位置胶囊的矩形。 */
    private val locRect = RectF()

    /** 天气胶囊的矩形。 */
    private val wxRect = RectF()

    private val tmp = RectF()

    private val avatarShader: Shader by lazy {
        LinearGradient(0f, 0f, AVATAR, AVATAR, intArrayOf(Skin.ACCENT_LIGHT, 0xFF2B4FD8.toInt()), null, Shader.TileMode.CLAMP)
    }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 第四十九轮「浮屿」：**去掉顶部渐变遮罩**。
        //
        // 第四十三轮那版是「沉浸式」：状态栏透明浮在任意背景上，所以要在顶部铺一条
        // 深色 → 透明的渐变把白字压住。现在背景已经换成**纯色底 + 极淡渐变**
        // （`BackgroundView` 不再有拟物山峦），内容也不会顶到屏幕最上沿，
        // 渐变遮罩反而会在顶部压出一条脏边 —— 直接去掉。
        //
        // 可读性改由**扁平分段胶囊**保证：每一段信息各自一个圆角底板，互不干扰。

        val top = ROW_TOP
        val rowH = ROW_H

        // 头像（第四十九轮：改成**纯色扁平方块**，不再是蓝色渐变）
        // 头像在状态行里**垂直居中**（设计板那行是 flex align-items:center）
        val avatarTop = top + (rowH - AVATAR) / 2f
        avatarRect.set(EDGE, avatarTop, EDGE + AVATAR, avatarTop + AVATAR)
        val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.ACCENT }
        canvas.drawRoundRect(avatarRect, AVATAR_RADIUS, AVATAR_RADIUS, avatarPaint)
        val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Skin.ACCENT_INK
            textSize = AVATAR_TEXT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("C", avatarRect.centerX(), avatarRect.centerY() + AVATAR_TEXT / 3f, initial)

        // 状态胶囊（第五十一轮按设计板 对齐：文字 20px `var(--text)`、
        // 间距 18、胶囊内边距 22/12/22、指示点 12px 强调色实心圆
        val chipX = EDGE + AVATAR + CHIP_GAP
        textPaint.let {
            it.textSize = CHIP_TEXT_SIZE
            it.color = Skin.TEXT
            val w = it.measureText(assistText)
            assistRect.set(
                chipX, top + (rowH - CHIP_H) / 2f,
                chipX + CHIP_PAD_L + CHIP_DOT + CHIP_PAD_M + w + CHIP_PAD_R,
                top + (rowH - CHIP_H) / 2f + CHIP_H,
            )
            canvas.drawRoundRect(assistRect, CHIP_RADIUS, CHIP_RADIUS, chipPaint)
            canvas.drawRoundRect(assistRect, CHIP_RADIUS, CHIP_RADIUS, chipStroke)
        }
        val dotCx = assistRect.left + CHIP_PAD_L + CHIP_DOT / 2f
        val dotCy = assistRect.centerY()
        // 设计板：直径 12 的强调色实心圆（「光晕 + 内芯」双圈是旧版视觉）。
        canvas.drawCircle(dotCx, dotCy, CHIP_DOT / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.ACCENT })
        canvas.drawText(
            assistText,
            dotCx + CHIP_DOT / 2f + CHIP_PAD_M,
            dotCy + CHIP_TEXT_SIZE / 3f,
            textPaint,
        )

        // 右侧图标行（从右往左排）
        val end = width - EDGE
        val cy = top + rowH / 2f
        val clockW = clockPaint.measureText(clockText)
        var x = end
        clockPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(clockText, x - clockW, cy + clockPaint.textSize / 3f, clockPaint)
        x -= clockW + CLOCK_GAP

        // 燃油百分比 + 图标（没有车端数据时显示默认的 `--%`）
        val pct = if (fuelPercent >= 0) "$fuelPercent%" else FUEL_UNKNOWN
        fuelPaint.color = Skin.TEXT
        val pw = fuelPaint.measureText(pct)
        canvas.drawText(pct, x - pw, cy + fuelPaint.textSize / 3f, fuelPaint)
        x -= pw + FUEL_GAP
        drawIcon(canvas, x - FUEL_ICON, cy - FUEL_ICON / 2f, FUEL_ICON) { drawFuelIcon(it, 0f, 0f) }
        x -= FUEL_ICON + ICON_GAP

        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawCellular(it, 0f, 0f) }
        x -= ICON + ICON_GAP
        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawWifi(it, 0f, 0f) }
        x -= ICON + ICON_GAP
        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawHotspot(it, 0f, 0f) }
        x -= ICON + ICON_GAP
        drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawBluetooth(it, 0f, 0f) }
        x -= ICON + ICON_GAP

        // 第八十二轮：**位置 + 天气并入右侧图标行**（原来是单独中置的一段）。
        //
        // 为什么挪：状态栏正中这一行要放**翻页指示圆点**（第八十二轮同时改），
        // 两段都摆正中会互相压住。挪到右侧后，中间的空出来正好给圆点，
        // 右边的信息密度也更高 —— 时间在最右，地区/天气在连接图标左边。
        x -= drawLocationWeather(canvas, cy, x)
    }

    /**
     * 按目标尺寸缩放绘制一个图标。
     *
     * 五个连接图标（蓝牙 / 热点 / WiFi / 信号 / 燃油）内部都是按 26×26 的坐标系
     * 硬编码的路径，这里统一用 `scale` 缩到目标尺寸，避免为了缩小而重画一遍路径。
     */
    private inline fun drawIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        draw: (Canvas) -> Unit,
    ) {
        val k = size / ICON_BASE
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(k, k)
        draw(canvas)
        canvas.restore()
    }

    /**
     * 「位置 + 天气」段（第四轮新增，**第八十二轮挪到右侧图标行**）。
     *
     * 状态栏左右两边本来就有内容（左边头像 + 驾驶模式胶囊，右边连接图标 +
     * 油量 + 时钟），中间这一段是我们自加的：
     * 位置取天气链路带回来的**城市名**，天气取同一个链路的**天气文案 + 温度**，
     * 所以状态栏和第一页天气卡显示的一定是同一份数据。
     *
     * **第八十二轮的位置口径**：这一段不再水平居中，而是**并入右侧图标行**，
     * 排在连接图标（蓝牙）的左边。原因是状态栏正中要留给**翻页指示圆点**。
     * 段内顺序（从右往左）：天气胶囊 → 位置胶囊，与右侧整行的「右→左」一致。
     *
     * @param cy 状态栏这一行的中心 y
     * @param rightX 这一段的**右边界**（左侧连接图标的左边）
     * @return 这一段占用的宽度（调用方据此继续往左排）
     */
    private fun drawLocationWeather(canvas: Canvas, cy: Float, rightX: Float): Float {
        val hasLocation = locationText.isNotEmpty()
        val city = if (hasLocation) locationText else LOCATION_PENDING
        val temp = weatherTemp
        val tempText = if (temp != null) "$temp°" else TEMP_UNKNOWN

        locationPaint.textSize = LOCATION_TEXT_SIZE
        locationPaint.color = if (hasLocation) Skin.TEXT_SOFT else Skin.TEXT_DIM
        tempPaint.textSize = TEMP_TEXT_SIZE
        tempPaint.color = if (temp != null) Skin.TEXT else Skin.TEXT_DIM
        locationPaint.textAlign = Paint.Align.LEFT
        tempPaint.textAlign = Paint.Align.LEFT

        val cityW = locationPaint.measureText(city)
        val tempW = tempPaint.measureText(tempText)

        // 第八十二轮：**城市名过长时截断**。
        //
        // 为什么：这一段是**从右往左**排的，城市名越长，位置胶囊的左边界越靠左 ——
        // 而正中（第八十二轮起）摆着翻页指示圆点。地名一长就会顶到圆点上。
        // 所以给位置胶囊一个宽度上限，超了就截断加省略号，
        // 保证**这一段的左边界永远在圆点右边**。
        val maxCityW = LOC_MAX_W - (PILL_PAD_X + PIN_W + PIN_GAP + PILL_PAD_X)
        val cityText = if (cityW > maxCityW) clipEllipsis(city, maxCityW) else city
        val cityWW = if (cityW > maxCityW) locationPaint.measureText(cityText) else cityW

        // 第四十九轮「浮屿」：**扁平分段胶囊行** ——
        // 位置和天气各自一个圆角底板，两段之间留 10px 空档。
        val locW = PILL_PAD_X + PIN_W + PIN_GAP + cityWW + PILL_PAD_X
        val wxW = PILL_PAD_X + WX_ICON_W + WX_ICON_GAP + tempW + PILL_PAD_X
        val total = locW + PILL_GAP + wxW
        var right = rightX
        val pillTop = cy - PILL_H / 2f

        // ① 天气胶囊（这一段的右半，紧挨连接图标）
        val wxLeft = right - wxW
        wxRect.set(wxLeft, pillTop, right, pillTop + PILL_H)
        canvas.drawRoundRect(wxRect, PILL_RADIUS, PILL_RADIUS, chipPaint)
        canvas.drawRoundRect(wxRect, PILL_RADIUS, PILL_RADIUS, chipStroke)
        // 天气图标的图形是按 24×24 视觉框硬编码的（[WeatherGlyphs]），
        // 直接画会比设计板大一圈 —— 这里按 [WX_ICON_SIZE] / 24 等比缩放，
        // 描边也一起缩（传进去的是缩放前的值，缩放后正好落在 [WX_ICON_STROKE]）。
        val wxCx = wxLeft + PILL_PAD_X + WX_ICON_W / 2f
        canvas.save()
        canvas.scale(WX_ICON_SCALE, WX_ICON_SCALE, wxCx, cy)
        WeatherGlyphs.draw(
            canvas,
            wxCx,
            cy,
            weatherSky,
            if (temp != null) Skin.TEXT else Skin.TEXT_DIM,
            WX_ICON_STROKE / WX_ICON_SCALE,
        )
        canvas.restore()
        canvas.drawText(tempText, wxLeft + PILL_PAD_X + WX_ICON_W + WX_ICON_GAP, cy + TEMP_TEXT_SIZE / 3f, tempPaint)
        right = wxLeft - PILL_GAP

        // ② 位置胶囊
        val locLeft = right - locW
        locRect.set(locLeft, pillTop, right, pillTop + PILL_H)
        canvas.drawRoundRect(locRect, PILL_RADIUS, PILL_RADIUS, chipPaint)
        canvas.drawRoundRect(locRect, PILL_RADIUS, PILL_RADIUS, chipStroke)
        drawLocationPin(canvas, locLeft + PILL_PAD_X + PIN_W / 2f, cy)
        canvas.drawText(cityText, locLeft + PILL_PAD_X + PIN_W + PIN_GAP, cy + LOCATION_TEXT_SIZE / 3f, locationPaint)

        return total
    }

    /**
     * 把文本截到不超过 [maxW]（超出部分用「…」收尾）。
     *
     * 与 `ScenePage.clipLabel` 同一套口径：**先按整串量，超了就一个字一个字地截**，
     * 保证截完的宽度真的塞得进上限里。
     */
    private fun clipEllipsis(s: String, maxW: Float): String {
        if (s.isEmpty()) return s
        if (locationPaint.measureText(s) <= maxW) return s
        var out = s
        while (out.isNotEmpty() && locationPaint.measureText("$out…") > maxW) out = out.dropLast(1)
        return "$out…"
    }

    /** 定位针（一个水滴形轮廓 + 中心点）。 */
    private fun drawLocationPin(canvas: Canvas, cx: Float, cy: Float) {
        // 第六十三轮：定位针跟着字与图标一起放大（[PIN_SCALE]），
        // 描边宽度由 canvas 缩放一起放大，比例不变。
        canvas.save()
        canvas.scale(PIN_SCALE, PIN_SCALE, cx, cy)
        val p = Path().apply {
            moveTo(cx, cy + 6.7f)
            cubicTo(cx - 5.3f, cy + 0.7f, cx - 4.7f, cy - 6.7f, cx, cy - 6.7f)
            cubicTo(cx + 4.7f, cy - 6.7f, cx + 5.3f, cy + 0.7f, cx, cy + 6.7f)
            close()
        }
        pinPaint.color = Skin.TEXT_SOFT
        canvas.drawPath(p, pinPaint)
        canvas.drawCircle(cx, cy - 2.7f, 2f, pinDotPaint)
        canvas.restore()
    }

    private fun drawBluetooth(canvas: Canvas, x: Float, y: Float) {
        val p = Path().apply {
            moveTo(x + 8f, y + 1f)
            lineTo(x + 18f, y + 9f)
            lineTo(x + 8f, y + 17f)
            lineTo(x + 8f, y + 25f)
            lineTo(x + 18f, y + 17f)
        }
        canvas.drawPath(p, iconPaint)
        canvas.drawLine(x + 4f, y + 8f, x + 14f, y + 18f, iconPaint)
        canvas.drawLine(x + 4f, y + 18f, x + 14f, y + 8f, iconPaint)
        canvas.drawLine(x + 8f, y + 1f, x + 8f, y + 25f, iconPaint)
    }

    private fun drawHotspot(canvas: Canvas, x: Float, y: Float) {
        val c = Paint(iconFill)
        c.color = Skin.ACCENT_LIGHT
        canvas.drawCircle(x + 13f, y + 13f, 4.5f, c)
        val arc = Paint(iconPaint)
        arc.strokeWidth = 2.4f
        tmp.set(x + 3f, y + 3f, x + 23f, y + 23f)
        canvas.drawArc(tmp, 200f, 140f, false, arc)
        tmp.set(x - 1f, y - 1f, x + 27f, y + 27f)
        canvas.drawArc(tmp, 205f, 130f, false, arc)
    }

    private fun drawWifi(canvas: Canvas, x: Float, y: Float) {
        val c = Paint(iconFill).apply { color = Skin.TEXT }
        canvas.drawCircle(x + 13f, y + 21f, 2.6f, c)
        for (i in 0..2) {
            val inset = 9f - i * 4.5f
            tmp.set(x + inset, y + (10f - i * 4.5f), x + 26f - inset, y + (26f - i * 4.5f))
            canvas.drawArc(tmp, 225f, 90f, false, iconPaint)
        }
    }

    private fun drawCellular(canvas: Canvas, x: Float, y: Float) {
        val c = Paint(iconFill).apply { color = Skin.TEXT }
        for (i in 0..3) {
            val h = 6f + i * 5f
            canvas.drawRoundRect(
                RectF(x + i * 6.5f, y + 22f - h, x + i * 6.5f + 4f, y + 22f),
                2f, 2f, c,
            )
        }
    }

    private fun drawFuelIcon(canvas: Canvas, x: Float, y: Float) {
        val c = iconPaint
        canvas.drawRoundRect(RectF(x + 2f, y + 2f, x + 15f, y + 22f), 2f, 2f, c)
        canvas.drawLine(x + 15f, y + 7f, x + 21f, y + 10f, c)
        canvas.drawLine(x + 21f, y + 10f, x + 21f, y + 19f, c)
        canvas.drawLine(x + 5f, y + 7f, x + 12f, y + 7f, c)
    }

    private var downHit = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有命中头像/胶囊才消费事件，其余位置放行给下层页面
                downHit = avatarRect.contains(event.x, event.y) || assistRect.contains(event.x, event.y)
                return downHit
            }
            MotionEvent.ACTION_UP -> {
                if (downHit) {
                    if (avatarRect.contains(event.x, event.y)) {
                        // 排障场景：「点头像没反应」。
                        Logx.event(LogTags.DOCK, "状态栏头像点击")
                        onAvatar?.invoke()
                    }
                    if (assistRect.contains(event.x, event.y)) {
                        Logx.event(LogTags.DOCK, "状态栏驾驶模式胶囊点击", "assist" to assistText)
                        onAssist?.invoke()
                    }
                }
                downHit = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downHit = false
                return false
            }
        }
        return downHit
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Design.W.toInt(), Design.H.toInt())
    }

    companion object {
        // ================================================================
        // 第五十二轮：状态栏整行元素**统一缩到 2/3**
        //
        // 第四十三轮缩小过一档 → 第四十四轮恢复成原值
        // 第五十二轮用户再要求「缩小到目前的 2/3」。
        //
        // 缩放口径：**每一项 × 2/3 后取整**，行内相对关系保持不变。
        //   左右边距 44 → 29
        //   顶边距 26 → 17，行高 64 → 43（行底 60）
        //   头像 54 → 36，圆角 18 → 12，字号 26 → 17
        //   胶囊 高 44 → 29、间距 18 → 12、状态点 12 → 8、
        //              内边距 22/12/22 → 15/8/15、文案 20 → 13
        //   连接图标 26 → 17（相邻间距 22 → 15）
        //   燃油图标 24 → 16、燃油百分比 20 → 13、间距 6 → 4
        //   时钟 38 → 25、与燃油百分比间距 18 → 12
        //   位置/天气胶囊 高 44 → 29、内边距 16 → 11、字号 20/26 → 13/17
        //
        // 内容基线随之由 **110 → 73**（= 110 × 2/3），
        // 页面内容整体上移，不留空白带。
        //
        // ----------------------------------------------------------------
        // 第六十三轮：**行内元素放大一档**（字 + 图标），**行本身大小不动**。
        //
        // 用户反馈：「状态栏的字和图标放大一点，状态栏上下都有一段空间」，
        // 要求「不要调整状态栏的整体大小」。
        //
        // 口径 = **行（band）一个字都不动**：[ROW_TOP] / [ROW_H] / [EDGE]
        // 全部保持第五十二轮的值；只把行内的字与图标放大。
        // 行内元素都是**垂直居中**的，字号图标一大，
        // 元素上下留白自然就收窄了 —— 这正是用户要的效果。
        //
        // 放大幅度取「2/3」与「原值」之间（约为原值的 0.8~0.85）：
        //   头像 36 → 40，圆角 12 → 13，字号 17 → 21
        //   胶囊 高 29 → 34、圆角 15 → 17、状态点 8 → 10、
        //            内边距 15/8/15 → 16/9/16、文案 13 → 17
        //   连接图标 17 → 22（相邻间距 17 → 18）
        //   燃油图标 16 → 21、燃油百分比 13 → 17、间距 13 → 14
        //   时钟 25 → 32、与燃油百分比间距 17 → 18
        //   位置/天气胶囊 高 29 → 34、内边距 15 → 16、字号 13/17 → 17/22
        //   定位针 描边形状整体 ×1.3
        // ================================================================

        /** 左右边距。 */
        const val EDGE = 29f

        /** 这一行元素整体的顶边距。 */
        const val ROW_TOP = 17f

        /** 这一行的高度（元素的垂直中心线 = ROW_TOP + ROW_H / 2）。 */
        const val ROW_H = 43f

        /** 头像边长。 */
        const val AVATAR = 40f

        /** 头像圆角。 */
        const val AVATAR_RADIUS = 13f

        /** 头像里的字母字号（设计板 26px bold）。 */
        const val AVATAR_TEXT = 21f

        /** 头像与状态胶囊之间的距离（第五十一轮按设计板 18 改；布局是 22）。 */
        const val CHIP_GAP = 12f

        /** 状态胶囊高度（44）。 */
        const val CHIP_H = 34f

        /** 状态胶囊圆角（= 高的一半 → 胶囊形）。 */
        const val CHIP_RADIUS = 17f

        /** 胶囊里状态点直径（设计板 12px 强调色实心圆）。 */
        const val CHIP_DOT = 10f

        /** 胶囊左内边距（设计板 22）。 */
        const val CHIP_PAD_L = 16f

        /** 胶囊里状态点与文案之间的距离（设计板 12）。 */
        const val CHIP_PAD_M = 9f

        /** 胶囊右内边距（设计板 22）。 */
        const val CHIP_PAD_R = 16f

        /** 胶囊文案字号（设计板 20px）。 */
        const val CHIP_TEXT_SIZE = 17f

        /** 连接图标的边长（蓝牙 / 热点 / WiFi / 信号）。 */
        const val ICON = 22f

        /**
         * 图标内部路径的基准尺寸（缩放用）。
         *
         * 五个图标的路径都按 **26×26** 的坐标系硬编码，这里只改显示尺寸（[ICON]），
         * 由 [drawIcon] 统一 `scale(size / ICON_BASE)` 缩放 ——
         * 基准值本身不能跟着缩，否则缩放会被抵消（图标反而变大）。
         */
        const val ICON_BASE = 26f

        /** 连接图标之间的距离（相邻图标的 `marginStart = 22`）。 */
        const val ICON_GAP = 18f

        /** 燃油图标的边长（24×24）。 */
        const val FUEL_ICON = 21f

        /** 燃油图标与燃油百分比之间的距离（6）。 */
        const val FUEL_GAP = 14f

        /** 燃油百分比与时钟之间的距离（18）。 */
        const val CLOCK_GAP = 18f

        /**
         * 顶部渐变遮罩的高度。
         *
         * = 状态栏那一行的高度（含顶边距）—— 遮罩**正好盖住状态栏那一行**，
         * 不会碰到下面的页面内容（第四十四轮起导航页内容从 90 开始）。
         */
        const val SCRIM_H = ROW_TOP + ROW_H

        /** 渐变遮罩顶部颜色（半透明深色）。 */
        const val SCRIM_TOP_COLOR = 0x7A000000.toInt()

        /** 渐变遮罩底部颜色（全透明）。 */
        const val SCRIM_BOTTOM_COLOR = 0x00000000.toInt()

        /** `H:mm`，单小时不补零。 */
        private val CLOCK = SimpleDateFormat("H:mm", Locale.CHINA)

        /** 没有油量数据时显示 `--%`。 */
        const val FUEL_UNKNOWN = "--%"

        /** [fuelPercent] 的「无数据」哨兵值。 */
        const val FUEL_UNKNOWN_VALUE = -1

        // -------------------------------------------------- 位置 + 天气（第四轮新增）

        /** 还没定位到时的占位文案（与场景页天气卡的「天气获取中」同一口径）。 */
        const val LOCATION_PENDING = "位置获取中"

        /** 没有天气数据时的温度占位（场景页用的是 `--`，这里带一度符号）。 */
        const val TEMP_UNKNOWN = "--°"

        /** [weatherTemp] 的「无数据」哨兵值（只用于日志）。 */
        const val TEMP_UNKNOWN_VALUE = -1

        /** 城市名字号。 */
        const val LOCATION_TEXT_SIZE = 17f

        /** 温度字号。 */
        const val TEMP_TEXT_SIZE = 22f

        // -------------------------------------------------- 扁平分段胶囊（第四十九轮「浮屿」）

        /** 分段胶囊的高度。 */
        const val PILL_H = 34f

        /** 分段胶囊的圆角（半高 → 视觉上完全圆）。 */
        const val PILL_RADIUS = 17f

        /** 分段胶囊的左右内边距。 */
        const val PILL_PAD_X = 16f

        /** 两段胶囊之间的间隙。 */
        const val PILL_GAP = 8f

        /** 定位针占位宽。 */
        const val PIN_W = 18f

        /** 定位针与城市名之间的间距。 */
        const val PIN_GAP = 8f

        /**
         * 定位针的放大倍数（第六十三轮）。
         *
         * 定位针的路径是按固定坐标画的水滴（高 13.4），字与图标放大后
         * 它显得偏小 —— 这里整体乘一个倍数，描边宽度随之一起放大。
         */
        const val PIN_SCALE = 1.3f

        /** 城市名与分隔点之间的间距。 */
        const val SEP_W = 17f

        /**
         * 位置胶囊的**最大宽度**（第八十二轮）。
         *
         * 位置 + 天气是**从右往左**排的，城市名越长，位置胶囊的左边界越靠左 ——
         * 而状态栏正中（第八十二轮起）摆着翻页指示圆点，地名一长就会顶上去。
         * 所以给一个宽度上限，超了就把地名截断加省略号。
         *
         * 取 300 的理由：按 [LOCATION_TEXT_SIZE] = 17 算，
         * 中文大约 14 个字 / 英文大约 28 个字符 ——
         * 正常城市名（「北京」「Shanghai」）远够不着这个上限，不会被截。
         */
        const val LOC_MAX_W = 300f

        /** 分隔点半径。 */
        const val SEP_DOT_R = 1.1f

        /** 天气图标占位宽。 */
        const val WX_ICON_W = 21f

        /** 天气图标与温度之间的间距。 */
        const val WX_ICON_GAP = 8f

        /** 天气图标的显示边长（设计板 16px → 第六十三轮放大到 21px）。 */
        const val WX_ICON_SIZE = 21f

        /** 天气图标缩放比（[WeatherGlyphs] 的图形按 24×24 视觉框硬编码）。 */
        const val WX_ICON_SCALE = WX_ICON_SIZE / 24f

        /** 天气图标描边（缩放后的实际宽度，设计 px）。 */
        const val WX_ICON_STROKE = 1.9f
    }
}
