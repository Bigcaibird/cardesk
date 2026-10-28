package com.cardesk.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.ui.pages.Cards

/**
 * 设置浮层：日夜模式、壁纸、车辆数据源。
 * 所有设置都立即生效并写入 SharedPreferences。
 */
@SuppressLint("ViewConstructor")
class SettingsPanelView(
    context: Context,
    private val pal: Palette,
    nightMode: Int,
    wallpaper: Int,
    vehicleSource: Int,
    source: String,
) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    var onNightMode: ((Int) -> Unit)? = null
    var onWallpaper: ((Int) -> Unit)? = null
    var onVehicleSource: ((Int) -> Unit)? = null
    var onReset: (() -> Unit)? = null

    private val nightChips = ArrayList<ChipButton>()
    private val wallpaperChips = ArrayList<ChipButton>()
    private val sourceChips = ArrayList<ChipButton>()

    init {
        // 第五十一轮：遮罩跟随主题（夜 0xA808090C / 日 0x61181A20），不再写死 60% 黑。
        setBackgroundColor(Skin.SCRIM)
        isClickable = true
        setOnClickListener { close() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(pal.surfaceStrong, 34f, 2f, pal.stroke)
            setPadding(48, 32, 48, 32)
            isClickable = true
        }
        addView(card, Ui.frame(1240, 840, Gravity.CENTER))

        val header = Cards.row(context)
        header.addView(Ui.text(context, "设置", 44f, pal.text, bold = true))
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val close = ChipButton.make(context, pal, "关闭").apply { setOnClickListener { close() } }
        header.addView(close, LinearLayout.LayoutParams(130, 62))
        card.addView(header, Ui.linear(-1, 62))

        card.addView(Cards.gap(context, 28))

        card.addView(Ui.text(context, "外观模式", 24f, pal.textDim))
        card.addView(Cards.gap(context, 12))
        card.addView(
            chipRow(
                context,
                listOf("跟随时间", "日间", "夜间"),
                nightMode,
                nightChips,
            ) { index -> onNightMode?.invoke(index) },
            Ui.linear(-1, 68),
        )

        card.addView(Cards.gap(context, 24))
        card.addView(Ui.text(context, "壁纸", 24f, pal.textDim))
        card.addView(Cards.gap(context, 12))
        card.addView(
            chipRow(
                context,
                listOf("极光", "流光", "远山"),
                wallpaper,
                wallpaperChips,
            ) { index -> onWallpaper?.invoke(index) },
            Ui.linear(-1, 68),
        )

        card.addView(Cards.gap(context, 24))
        card.addView(Ui.text(context, "车辆数据源", 24f, pal.textDim))
        card.addView(Cards.gap(context, 12))
        card.addView(
            chipRow(
                context,
                listOf("自动探测", "模拟车辆", "真车通道"),
                vehicleSource,
                sourceChips,
            ) { index -> onVehicleSource?.invoke(index) },
            Ui.linear(-1, 68),
        )
        card.addView(Cards.gap(context, 12))
        card.addView(Ui.text(context, "当前通道：$source", 20f, pal.textFaint))

        card.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        card.addView(Ui.text(context, "CarDesk 车控桌面 1.0", 20f, pal.textFaint))
        card.addView(Cards.gap(context, 14))
        val reset = ChipButton.make(context, pal, "恢复默认设置").apply { setOnClickListener { onReset?.invoke() } }
        card.addView(reset, Ui.linear(-1, 68))
    }

    private fun chipRow(
        context: Context,
        labels: List<String>,
        selected: Int,
        out: MutableList<ChipButton>,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in labels.indices) {
            val chip = ChipButton.make(context, pal, labels[i], i == selected)
            chip.setOnClickListener {
                out.forEachIndexed { index, c -> c.setActive(index == i) }
                onPick(i)
            }
            out.add(chip)
            row.addView(chip, Ui.linear(0, 68, 1f))
            if (i != labels.lastIndex) row.addView(View(context), LinearLayout.LayoutParams(16, 1))
        }
        return row
    }

    fun close() {
        visibility = View.GONE
        onClose?.invoke()
    }
}
