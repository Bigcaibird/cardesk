package com.cardesk.launcher.ui.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.cardesk.launcher.core.NavSlot
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.pressFeedback
import com.cardesk.launcher.vehicle.HvacIds
import com.cardesk.launcher.vehicle.HvacState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导航栏槽位条，沿用 App `item_nav_bar_*` 九种槽位
 *
 *
 * 布局规则：顺序即视觉顺序；空槽是 3dp 半透明细条且不计上限；只在「普通槽之后、第一个 HVAC 槽之前」
 * 插一根分隔线；媒体槽最小 200dp、歌词槽最小 150dp、温度槽最小 112dp，各槽宽度自适应。
 *
 * 交互规则（全部）
 * - 歌词槽**不可点**（的 `clickable=false`）；
 * - 媒体槽是**三块独立热区**：封面 → 切到音乐页、标题 → 打开音乐 App、播放键 → 播放/暂停
 *   ；
 * - 温度槽**整槽不可点**，只有 ± 两个箭头可点，且按 actionId 区分主驾 / 副驾
 *   ；
 * - 主页槽单击回桌面、双击 / 长按进最近任务；应用列表槽单击进应用列表、长按进最近任务
 *
 *
 * 画布坐标：宽高即控件尺寸（贴在增强导航栏 pill 里），内部按 1dp = 1.6px 的视觉比例绘制。
 */
class NavSlotBarView(context: Context) : View(context) {

    var palette: Palette = Palettes.night
        set(value) {
            field = value
            invalidate()
        }

    var slots: List<NavSlot> = NavSlots.defaults()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var state: HvacState? = null

    /**
     * 媒体槽标题 / 歌手。
     *
     * 标题为空时显示「暂无播放」
     * 歌手为空时是**空串**（不是占位文案）。
     */
    var mediaTitle: String = "暂无播放"
    var mediaArtist: String = ""
    var mediaPlaying: Boolean = false

    /** 歌词槽：当前行 / 下一行（无歌词时是空串）。 */
    var lyricCurrent: String = ""
    var lyricNext: String = ""

    /**
     * 歌词槽当前行的**擦除比例** 0..1。
     *
     * `DockLyricBinder.setLyricWipe(float)` —— 当前行随播放进度做擦除高亮。
     */
    var lyricWipe: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            lyricWipeState.setTarget(field)
            invalidate()
        }

    /** 擦除动画状态。 */
    private val lyricWipeState = com.cardesk.launcher.core.media.LyricWipeState()

    /** 媒体槽封面；null = 画音符占位。 */
    var mediaArtwork: android.graphics.Bitmap? = null

    /**
     * 是否显示 Dock 里的歌词槽（两行小字）。
     *
     * 本轮首页加了「中央大字歌词」，Dock 这两行 12px/10px 小字就**整体隐藏**，
     * 免得同一首歌在屏上出现两处歌词。隐藏时该槽**不占宽度**（不留空位）。
     */
    var showLyric: Boolean = true

    var onSlot: ((NavSlot) -> Unit)? = null
    var onTemp: ((String, Int) -> Unit)? = null

    /** 最近任务（主页槽双击 / 长按、应用列表槽长按）。 */
    var onRecents: (() -> Unit)? = null

    /** 媒体槽封面热区 → 切到音乐页（case 15）。 */
    var onMediaCover: (() -> Unit)? = null

    /** 媒体槽标题热区 → 打开音乐 App。 */
    var onMediaTitle: (() -> Unit)? = null

    /** 媒体槽播放键热区 → 播放 / 暂停。 */
    var onMediaPlay: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hits = LinkedHashMap<String, RectF>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val hmsFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    // `yyyy年M月d日`
    private val dateFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private val weekFmt = SimpleDateFormat("E", Locale.CHINA)

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var tapCount = 0
    private var longPressFired = false
    private var downSlot: NavSlot? = null

    init {
        pressFeedback()
    }

    /** 1dp 约 1.6px（本控件只在 1920×1080 设计坐标系里使用）。 */
    private val u = 1.6f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, if (h > 0) h else (46 * u).toInt())
    }

    private fun slotWidth(slot: NavSlot): Float = when {
        slot.isEmpty -> 3f * u
        slot.type == NavSlot.TYPE_MEDIA -> 200f * u
        slot.type == NavSlot.TYPE_LYRIC -> 150f * u
        NavSlots.isTempSlot(slot) -> 112f * u
        slot.type == NavSlot.TYPE_DATETIME -> 96f * u
        NavSlots.isTextToggle(slot) -> 56f * u
        NavSlots.isHvacAction(slot.actionId) && slot.actionId == "hvac_fan" -> 46f * u
        else -> 44f * u
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        hits.clear()
        val contentH = h - 8f * u
        val top = (h - contentH) / 2f
        var x = 0f
        var hvacDividerDone = false
        var hasNonEmptyBefore = false

        for (slot in slots) {
            // 歌词槽被关掉时整个跳过：不画、也不占宽度（不留空位）。
            if (!showLyric && slot.type == NavSlot.TYPE_LYRIC) continue
            val w = slotWidth(slot)
            if (slot.isEmpty) {
                fill.color = palette.withAlpha(palette.stroke, 0x26)
                canvas.drawRoundRect(RectF(x, top + 6 * u, x + w, top + contentH - 6 * u), 2f, 2f, fill)
                x += w + 3 * u
                continue
            }
            if (!hvacDividerDone && hasNonEmptyBefore && NavSlots.isHvacAction(slot.actionId)) {
                val dx = x + 4 * u
                fill.color = palette.withAlpha(palette.stroke, 0x55)
                canvas.drawRoundRect(RectF(dx, top + 12 * u, dx + 1.2f * u, top + contentH - 12 * u), 1f, 1f, fill)
                x += 9 * u
                hvacDividerDone = true
            }
            hasNonEmptyBefore = true
            drawSlot(canvas, slot, x, top, w, contentH)
            x += w + margin(slot)
        }
    }

    private fun margin(slot: NavSlot): Float = when {
        slot.type == NavSlot.TYPE_MEDIA || slot.type == NavSlot.TYPE_LYRIC -> 8f * u
        NavSlots.isTempSlot(slot) -> 4f * u
        else -> 4f * u
    }

    private fun drawSlot(canvas: Canvas, slot: NavSlot, x: Float, top: Float, w: Float, h: Float) {
        when {
            slot.type == NavSlot.TYPE_MEDIA -> drawMedia(canvas, x, top, w, h, slot)
            slot.type == NavSlot.TYPE_LYRIC -> drawLyric(canvas, x, top, w, h, slot)
            slot.type == NavSlot.TYPE_DATETIME -> drawDatetime(canvas, x, top, w, h, slot)
            NavSlots.isTempSlot(slot) -> drawTemp(canvas, x, top, w, h, slot)
            NavSlots.isTextToggle(slot) -> drawTextToggle(canvas, x, top, w, h, slot)
            else -> drawIconAction(canvas, x, top, w, h, slot)
        }
    }

    private fun tile(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, active: Boolean = false) {
        fill.color = if (active) palette.withAlpha(palette.accent, 0x40) else palette.withAlpha(palette.stroke, 0x24)
        canvas.drawRoundRect(RectF(x, top, x + w, top + h), 12f * u, 12f * u, fill)
        if (active) {
            stroke.color = palette.withAlpha(palette.accent, 0xCC)
            canvas.drawRoundRect(RectF(x, top, x + w, top + h), 12f * u, 12f * u, stroke)
        }
    }

    private fun label(canvas: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, bold: Boolean = false) {
        text.color = color
        text.textSize = size
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2f, text)
    }

    // ------------------------------------------------------------------ 九种槽位

    private fun drawIconAction(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, slot: NavSlot) {
        val active = when (slot.actionId) {
            "hvac_power" -> state?.power == true
            "hvac_circ" -> (state?.circulation ?: 0) != 0
            "hvac_defrost_front" -> state?.defrostFront == true
            "hvac_defrost_rear" -> state?.defrostRear == true
            else -> false
        }
        tile(canvas, x, top, w, h, active)
        val cx = x + w / 2f
        val cy = top + h / 2f
        when (slot.actionId) {
            "back" -> {
                icon.color = palette.text
                val p = Path()
                p.moveTo(cx + 8 * u, cy - 8 * u)
                p.lineTo(cx - 8 * u, cy)
                p.lineTo(cx + 8 * u, cy + 8 * u)
                canvas.drawPath(p, icon)
            }
            "home" -> {
                icon.color = palette.text
                // 第七十七轮：与桌面 Dock 的 home 键**共用同一份房子形状**
                // （见 [HomeHouseIcon]）—— 同一个「主页」不能两处两个样。
                HomeHouseIcon.draw(canvas, cx, cy, 9f * u, icon)
            }
            "tasks" -> {
                icon.color = palette.text
                for (r in 0..2) canvas.drawCircle(cx - 6 * u, cy - 7 * u + r * 7 * u, 1.8f * u, icon)
                for (r in 0..2) {
                    canvas.drawLine(cx - 1 * u, cy - 7 * u + r * 7 * u, cx + 9 * u, cy - 7 * u + r * 7 * u, icon)
                }
            }
            "split" -> {
                icon.color = palette.text
                canvas.drawRoundRect(RectF(cx - 9 * u, cy - 8 * u, cx - 1 * u, cy + 8 * u), 2f, 2f, icon)
                canvas.drawRoundRect(RectF(cx + 1 * u, cy - 8 * u, cx + 9 * u, cy + 8 * u), 2f, 2f, icon)
            }
            "camera_360" -> label(canvas, "360", cx, cy, 15f * u, if (active) palette.accent else palette.text, true)
            "settings" -> {
                icon.color = palette.text
                canvas.drawCircle(cx, cy, 5.5f * u, icon)
                icon.color = palette.text
                canvas.drawCircle(cx, cy, 10f * u, icon)
            }
            "media" -> label(canvas, "♪", cx, cy, 17f * u, palette.text, true)
            "lyric" -> label(canvas, "词", cx, cy, 15f * u, palette.text, true)
            "hvac_power" -> {
                icon.color = if (active) palette.accent else palette.text
                canvas.drawRoundRect(RectF(cx - 2 * u, cy - 10 * u, cx + 2 * u, cy - 2 * u), 2f, 2f, icon)
                canvas.drawArc(RectF(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u), -60f, 300f, false, icon)
            }
            "hvac_fan" -> {
                icon.color = if (state?.fanAuto == true) palette.accent else palette.text
                for (k in 0..2) {
                    val ang = -Math.PI / 2 + k * 2 * Math.PI / 3
                    canvas.drawOval(
                        RectF(
                            cx + (Math.cos(ang) * 5 * u - 4 * u).toFloat(),
                            cy + (Math.sin(ang) * 5 * u - 4 * u).toFloat(),
                            cx + (Math.cos(ang) * 5 * u + 4 * u).toFloat(),
                            cy + (Math.sin(ang) * 5 * u + 4 * u).toFloat(),
                        ),
                        icon,
                    )
                }
                val level = state?.fanLevel ?: 0
                for (i in 0 until 7) {
                    val bx = cx - 15f * u + i * 4.6f * u
                    fill.color = if (i < level) palette.accent else palette.withAlpha(palette.stroke, 0x66)
                    canvas.drawRect(RectF(bx, top + h - 9 * u, bx + 3.2f * u, top + h - 4 * u), fill)
                }
            }
            "hvac_circ" -> {
                icon.color = if (active) palette.accent else palette.text
                canvas.drawArc(RectF(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u), 40f, 280f, false, icon)
                canvas.drawLine(cx + 6 * u, cy - 7 * u, cx + 10 * u, cy - 8 * u, icon)
            }
            "hvac_defrost_front" -> {
                icon.color = if (active) palette.accent else palette.text
                canvas.drawArc(RectF(cx - 10 * u, cy + 1 * u, cx + 10 * u, cy + 15 * u), 180f, 180f, false, icon)
                for (k in -1..1) canvas.drawLine(cx + k * 4 * u, cy - 5 * u, cx + k * 4 * u, cy + 2 * u, icon)
            }
            "hvac_defrost_rear" -> {
                icon.color = if (active) palette.accent else palette.text
                canvas.drawRect(RectF(cx - 10 * u, cy - 6 * u, cx + 10 * u, cy + 8 * u), icon)
                canvas.drawLine(cx - 12 * u, cy + 8 * u, cx + 12 * u, cy + 8 * u, icon)
                for (k in -1..1) canvas.drawLine(cx + k * 4 * u, cy - 2 * u, cx + k * 4 * u, cy + 4 * u, icon)
            }
            else -> label(canvas, slot.label.take(2), cx, cy, 13f * u, palette.text)
        }
        hits[slot.actionId.ifBlank { "slot${slot.hashCode()}" }] = RectF(x, top, x + w, top + h)
    }

    private fun drawTextToggle(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, slot: NavSlot) {
        val active = when (slot.actionId) {
            "hvac_ac" -> state?.ac == true
            "hvac_auto" -> state?.auto == true
            else -> false
        }
        tile(canvas, x, top, w, h, active)
        val labelText = when (slot.actionId) {
            "hvac_ac" -> "A/C"
            "hvac_auto" -> "AUTO"
            else -> slot.actionId
        }
        label(canvas, labelText, x + w / 2f, top + h / 2f, 14f * u, if (active) palette.accent else palette.text, true)
        hits[slot.actionId] = RectF(x, top, x + w, top + h)
    }

    /**
     * 温度槽：整槽不可点，只有 ± 两个箭头可点。
     * 命中区带上主驾 / 副驾，± 必须作用到对应分区。
     */
    private fun drawTemp(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, slot: NavSlot) {
        val passenger = slot.actionId == NavSlot.ACTION_HVAC_TEMP_PASSENGER
        val zone = if (passenger) HvacIds.ZONE_PASSENGER else HvacIds.ZONE_DRIVER
        val zoneKey = if (passenger) ZONE_PASSENGER_KEY else ZONE_DRIVER_KEY
        val value = state?.tempOf(zone) ?: 23f
        val arrow = 34f * u
        val cy = top + h / 2f
        icon.color = palette.textDim
        canvas.drawLine(x + 8 * u, cy, x + 18 * u, cy - 8 * u, icon)
        canvas.drawLine(x + 8 * u, cy, x + 18 * u, cy + 8 * u, icon)
        label(canvas, String.format(Locale.US, "%.1f°", value), x + w / 2f + 2 * u, cy, 15f * u, palette.text, true)
        val incX = x + w - 18 * u
        canvas.drawLine(incX, cy - 8 * u, incX + 10 * u, cy, icon)
        canvas.drawLine(incX, cy + 8 * u, incX + 10 * u, cy, icon)
        hits["temp_inc_$zoneKey"] = RectF(x + w - arrow, top, x + w, top + h)
        hits["temp_dec_$zoneKey"] = RectF(x, top, x + arrow, top + h)
    }

    private fun drawDatetime(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, slot: NavSlot) {
        val now = Date()
        //
        // - 主行 = 时间（时分 / 时分秒，星期跟在时间后面），日期类槽位只显示日期；
        // - 次行 = 日期 `yyyy年M月d日`。
        val dateOnly = slot.actionId == NavSlot.ACTION_DATE
        val withSec = slot.actionId.contains("hms")
        val withWeek = slot.actionId.contains("week")
        val time = if (withSec) hmsFmt.format(now) else timeFmt.format(now)
        val primary = if (withWeek) "$time ${weekFmt.format(now)}" else time

        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        text.color = palette.textDim
        text.textSize = 11f * u
        canvas.drawText(dateFmt.format(now), x + w / 2f, top + h / 2f + 13 * u, text)
        if (!dateOnly) {
            text.color = palette.text
            text.textSize = 13f * u
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(primary, x + w / 2f, top + h / 2f - 3 * u, text)
        }
        hits["datetime"] = RectF(x, top, x + w, top + h)
    }

    /**
     * 媒体槽：三块独立热区——
     * 封面 → 切音乐页；标题 → 打开音乐 App；播放键 → 播放 / 暂停。
     */
    private fun drawMedia(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, slot: NavSlot) {
        fill.color = palette.withAlpha(palette.surface, 0xE6)
        canvas.drawRoundRect(RectF(x, top, x + w, top + h), 12f * u, 12f * u, fill)
        val cover = h - 8 * u
        val cy = top + h / 2f
        val art = mediaArtwork
        if (art != null) {
            // 有专辑图 → 裁剪成圆角贴上去（圆角 16）
            val coverRect = RectF(x + 6 * u, cy - cover / 2f, x + 6 * u + cover, cy + cover / 2f)
            val clip = Path().apply { addRoundRect(coverRect, 7f * u, 7f * u, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(art, null, coverRect, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        } else {
            fill.color = palette.withAlpha(palette.accent, 0xCC)
            canvas.drawRoundRect(RectF(x + 6 * u, cy - cover / 2f, x + 6 * u + cover, cy + cover / 2f), 7f * u, 7f * u, fill)
            label(canvas, "♪", x + 6 * u + cover / 2f, cy, cover * 0.5f, palette.text, true)
        }
        val playR = 11f * u
        val px = x + w - playR - 8 * u
        val titleLeft = x + 14 * u + cover
        // 都是
        // `android:maxLines="1"` + `android:ellipsize="end"` —— 长歌名只能截断，
        // **不能画到播放键上**。
        val avail = (px - playR) - titleLeft - 4 * u
        text.textAlign = Paint.Align.LEFT
        text.color = palette.text
        text.textSize = 11f * u
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(clip(mediaTitle, text, avail), titleLeft, cy - 1 * u, text)
        text.color = palette.textDim
        text.textSize = 9f * u
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(clip(mediaArtist, text, avail), titleLeft, cy + 10 * u, text)
        fill.color = palette.withAlpha(palette.accent, 0x33)
        canvas.drawCircle(px, cy, playR, fill)
        icon.color = palette.accent
        if (mediaPlaying) {
            canvas.drawLine(px - 3 * u, cy - 4 * u, px - 3 * u, cy + 4 * u, icon)
            canvas.drawLine(px + 3 * u, cy - 4 * u, px + 3 * u, cy + 4 * u, icon)
        } else {
            val p = Path()
            p.moveTo(px - 3 * u, cy - 5 * u)
            p.lineTo(px + 5 * u, cy)
            p.lineTo(px - 3 * u, cy + 5 * u)
            p.close()
            icon.style = Paint.Style.FILL
            canvas.drawPath(p, icon)
            icon.style = Paint.Style.STROKE
        }
        hits["media_cover"] = RectF(x + 6 * u, cy - cover / 2f, x + 6 * u + cover, cy + cover / 2f)
        hits["media_title"] = RectF(titleLeft, top, px - playR, top + h)
        hits["media_play"] = RectF(px - playR, top, px + playR, top + h)
    }

    /**
     * 歌词槽：只显示，不可点（`clickable=false`）。
     *
     * 布局里两行都是 `android:maxLines="1"` + `android:ellipsize="end"` +
     * `android:singleLine="true"` —— **长句必须截断成省略号，绝不能画到相邻槽位上**。
     * 槽宽 `navbar_lyric_min_width = 150dp`，左右各留 4dp 内边距。
     */
    private fun drawLyric(canvas: Canvas, x: Float, top: Float, w: Float, h: Float, slot: NavSlot) {
        val avail = w - 8f * u
        text.textAlign = Paint.Align.LEFT
        text.textSize = 12f * u
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // 当前行带**擦除高亮**
        // 已唱到的部分用强调色，没唱到的部分用暗色。
        val moving = lyricWipeState.advance(System.currentTimeMillis())
        if (moving) postInvalidateOnAnimation()
        com.cardesk.launcher.ui.widget.LyricWipeText.draw(
            canvas = canvas,
            text = clip(lyricCurrent, text, avail),
            cx = x + 4f * u + avail / 2f,
            baseline = top + h * 0.44f,
            paint = text,
            activeColor = palette.accent,
            inactiveColor = palette.textDim,
            fraction = lyricWipeState.display,
            edgePx = com.cardesk.launcher.core.media.LyricWipe.edgeWidth(resources.displayMetrics.density),
            maxWidth = avail,
        )
        text.color = palette.textDim
        text.textSize = 10f * u
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText(clip(lyricNext, text, avail), x + 4 * u, top + h * 0.82f, text)
    }

    /**
     * 按可用宽度裁剪文本（超出加省略号）。
     *
     *
     * `android:ellipsize="end"`：`maxLines=1` + `ellipsize=end` 的 TextView
     * 在超宽时显示省略号，而不是换行、更不是画出去。
     *
     * canvas 自绘没有 TextView 的换行/裁剪能力，所以这里按测量宽度手工截断。
     */
    private fun clip(s: String, paint: Paint, max: Float): String =
        com.cardesk.launcher.core.TextClip.ellipsize(s, { paint.measureText(it) }, max)

    // ------------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                downSlot = slotAt(event.x, event.y)
                val slot = downSlot
                if (slot != null && supportsLongPress(slot)) {
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                    handler.removeCallbacks(longPressRunnable)
                    longPressFired = true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || longPressFired) {
                    downSlot = null
                    return true
                }
                handleUp(event.x, event.y)
                downSlot = null
                return true
            }
        }
        return true
    }

    /** 主页 / 应用列表槽支持长按。 */
    private fun supportsLongPress(slot: NavSlot): Boolean =
        slot.actionId == NavSlot.ACTION_HOME || slot.actionId == NavSlot.ACTION_TASKS

    private val longPressRunnable = Runnable {
        val slot = downSlot ?: return@Runnable
        if (!supportsLongPress(slot)) return@Runnable
        longPressFired = true
        handler.removeCallbacks(homeTapRunnable)
        tapCount = 0
        // 排障场景：「导航栏长按没进最近任务」——长按动作落盘。
        Logx.event(LogTags.DOCK, "导航栏槽位长按：打开最近任务", "actionId" to slot.actionId)
        onRecents?.invoke()
    }

    /** 主页槽单击 / 双击判定。 */
    private val homeTapRunnable = Runnable {
        if (tapCount == 1) {
            downSlot?.let { onSlot?.invoke(it) }
        } else {
            Logx.event(LogTags.DOCK, "导航栏主页槽双击：打开最近任务")
            onRecents?.invoke()
        }
        tapCount = 0
    }

    private fun handleUp(x: Float, y: Float) {
        val hit = hits.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return
        when {
            hit.startsWith("temp_inc_") -> {
                // 排障场景：「增强导航栏温度 + 点不动 / 加错了分区」。
                val zone = zoneOf(hit)
                Logx.event(
                    LogTags.DOCK,
                    "导航栏槽位温度调节",
                    "zone" to zone,
                    "delta" to 1,
                    "temp" to (state?.tempOf(hvacZoneOf(zone)) ?: -1f),
                )
                onTemp?.invoke(zone, 1)
                return
            }
            hit.startsWith("temp_dec_") -> {
                val zone = zoneOf(hit)
                Logx.event(
                    LogTags.DOCK,
                    "导航栏槽位温度调节",
                    "zone" to zone,
                    "delta" to -1,
                    "temp" to (state?.tempOf(hvacZoneOf(zone)) ?: -1f),
                )
                onTemp?.invoke(zone, -1)
                return
            }
            hit == "media_cover" -> {
                // 排障场景：「媒体槽封面点了没切音乐页」。
                Logx.event(LogTags.DOCK, "导航栏媒体槽封面点击", "title" to mediaTitle)
                onMediaCover?.invoke()
                return
            }
            hit == "media_title" -> {
                Logx.event(LogTags.DOCK, "导航栏媒体槽标题点击", "title" to mediaTitle, "artist" to mediaArtist)
                onMediaTitle?.invoke()
                return
            }
            hit == "media_play" -> {
                Logx.event(LogTags.DOCK, "导航栏媒体槽播放键点击", "playing" to mediaPlaying, "title" to mediaTitle)
                onMediaPlay?.invoke()
                return
            }
        }
        val slot = slotForHit(hit) ?: return
        // 排障场景：「导航栏槽位点了没反应」——槽位点击落盘（actionId + 文案）。
        Logx.event(
            LogTags.DOCK,
            "导航栏槽位点击",
            "actionId" to slot.actionId,
            "label" to slot.label,
            "type" to slot.type,
        )
        when (slot.actionId) {
            NavSlot.ACTION_HOME -> {
                tapCount++
                if (tapCount > 2) {
                    handler.removeCallbacks(homeTapRunnable)
                    tapCount = 0
                    Logx.event(LogTags.DOCK, "导航栏主页槽三击：打开最近任务")
                    onRecents?.invoke()
                    return
                }
                handler.removeCallbacks(homeTapRunnable)
                handler.postDelayed(homeTapRunnable, HOME_TAP_WINDOW_MS)
            }
            NavSlot.ACTION_TASKS -> onSlot?.invoke(slot)
            else -> onSlot?.invoke(slot)
        }
    }

    /** 命中区 → 槽位（actionId 命中优先，其次按类型命中）。 */
    private fun slotForHit(hit: String): NavSlot? = slots.firstOrNull {
        (it.actionId.ifBlank { "slot${it.hashCode()}" }) == hit ||
            (hit == "datetime" && NavSlots.isDatetime(it))
    }

    private fun slotAt(x: Float, y: Float): NavSlot? {
        val hit = hits.entries.firstOrNull { it.value.contains(x, y) }?.key ?: return null
        return slotForHit(hit)
    }

    private fun zoneOf(hit: String): String =
        if (hit.endsWith(ZONE_PASSENGER_KEY)) ZONE_PASSENGER_KEY else ZONE_DRIVER_KEY

    /** 槽位分区名 → 车控分区号（[HvacIds.ZONE_DRIVER] / [HvacIds.ZONE_PASSENGER]）。 */
    private fun hvacZoneOf(zoneKey: String): Int =
        if (zoneKey == ZONE_PASSENGER_KEY) HvacIds.ZONE_PASSENGER else HvacIds.ZONE_DRIVER

    /** 当前槽位条的命中区（供测试断言）。 */
    fun hitRects(): Map<String, RectF> = hits

    companion object {
        /** 宽屏阈值。 */
        const val WIDE_SCREEN_MIN_WIDTH = 1800

        /** 宽屏下增强导航栏整层固定 152px（同上行）。 */
        const val WIDE_HEIGHT_PX = 152

        /** 主页槽单击 / 双击的判定窗口（400ms）。 */
        const val HOME_TAP_WINDOW_MS = 400L

        /** 温度槽命中区里的主驾标记（[onTemp] 的第一个参数）。 */
        const val ZONE_DRIVER_KEY = "driver"

        /** 温度槽命中区里的副驾标记（[onTemp] 的第一个参数）。 */
        const val ZONE_PASSENGER_KEY = "passenger"

        /**
         * 增强导航栏整层高度：宽屏（≥1800px）固定 152px，其余按设计稿高度。
         */
        fun resolveHeightPx(screenWidth: Int, designedHeightPx: Int): Int =
            if (screenWidth < WIDE_SCREEN_MIN_WIDTH) maxOf(1, designedHeightPx) else WIDE_HEIGHT_PX
    }
}
