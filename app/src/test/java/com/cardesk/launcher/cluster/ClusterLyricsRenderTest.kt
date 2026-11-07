package com.cardesk.launcher.cluster

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.dialogs.DesktopLayoutDialog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 把「仪表歌词面板」和「仪表歌词设置页」离屏渲染成 PNG，方便肉眼核对。
 *
 * ## 为什么要有这个
 *
 * 真机上这块屏是**看不见摸不着**的（它在副驾前面 / 仪表后面），
 * 截图只能截到主屏 —— 面板长什么样、位置对不对、擦除高亮有没有画出来，
 * 只能靠这里渲一张出来看。
 *
 * 产物写到 cardesk/preview/ 下：
 *
 * | 文件 | 内容 |
 * | --- | --- |
 * | `30-cluster-pill.png` | **默认**：胶囊样式 + 底板透明度 0 → 歌词直接落在仪表画面上（**没有灰底**） |
 * | `31-cluster-plain.png` | 纯文字样式 |
 * | `32-cluster-glow.png` | 发光样式 |
 * | `33-cluster-empty.png` | 空态（没在播） |
 * | `34-cluster-settings.png` | 仪表歌词设置页（含投射目标 / 投射状态 / 位置 / 行数 / 样式） |
 * | `35-cluster-plate.png` | 底板透明度拉到 70% 的样子（这个选项还在，仪表底色太亮时用） |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterLyricsRenderTest {

    /** 仪表屏尺寸（真车常见 1920×720）。 */
    private val clusterWidth = 1920

    /** 仪表屏高度。 */
    private val clusterHeight = 720

    /** 仪表屏密度（240dpi ⇒ 1.5 倍）。 */
    private val density = 1.5f

    /** 主歌词字号（sp）。 */
    private val mainSp = 28f

    /** 副歌词字号（sp）。 */
    private val subSp = 18f

    private val accent = 0xFF7FE3D4.toInt()

    private val muted = 0xFF9FB4BB.toInt()

    /** 仪表自己的底色（模拟仪表盘面）。 */
    private val clusterBg = 0xFF1A2430.toInt()

    @Test
    fun 三种样式各渲一张() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val snapshot = ClusterLyricSnapshot(
            title = "晴天",
            artist = "周杰伦",
            sourceLabel = "netease",
            playing = true,
            previousText = "故事的小黄花",
            currentText = "从出生那年就飘着",
            nextText = "童年的荡秋千",
            wipe = 0.45f,
            lyricLines = 64,
        )
        shotPanel(activity, ClusterLyricsView.STYLE_PILL, snapshot, 0, "30-cluster-pill.png")
        shotPanel(activity, ClusterLyricsView.STYLE_PLAIN, snapshot, 0, "31-cluster-plain.png")
        shotPanel(activity, ClusterLyricsView.STYLE_GLOW, snapshot, 0, "32-cluster-glow.png")
    }

    @Test
    fun 空态渲一张() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shotPanel(
            activity,
            ClusterLyricsView.STYLE_PILL,
            ClusterLyricSnapshot(title = "", playing = false),
            0,
            "33-cluster-empty.png",
        )
    }

    @Test
    fun 开了底板的样子也渲一张() {
        // 「仪表背景透明度」拉到 70% 时的样子 —— 说明这个选项还在，
        // 仪表底色太亮、字看不清时可以拉上去。
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shotPanel(
            activity,
            ClusterLyricsView.STYLE_PILL,
            ClusterLyricSnapshot(
                title = "晴天",
                playing = true,
                previousText = "故事的小黄花",
                currentText = "从出生那年就飘着",
                nextText = "童年的荡秋千",
                wipe = 0.45f,
                lyricLines = 64,
            ),
            70,
            "35-cluster-plate.png",
        )
    }

    @Test
    fun 设置页渲一张() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val prefs = Prefs(activity.applicationContext)
        val dialog = DesktopLayoutDialog(activity, Palettes.night, prefs)
        val host = FrameLayout(activity)
        host.addView(
            dialog,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        activity.setContentView(host)
        shot(host, 1920, 1080, "34-cluster-settings.png")
        assertTrue("设置页应该渲染出内容", host.width > 0)
    }

    /**
     * 渲染一块面板到「仪表屏」画布上。
     *
     * 位置用**真实的几何函数**算（[ClusterPanelGeometry]），
     * 所以这张图里的位置就等于真车上的位置 ——
     * 这也正是把几何抽成纯 Kotlin 类的意义。
     */
    private fun shotPanel(
        activity: Activity,
        style: Int,
        snapshot: ClusterLyricSnapshot,
        bgAlphaPercent: Int,
        name: String,
    ) {
        val view = ClusterLyricsView(activity)
        val mainPx = mainSp * density
        val subPx = subSp * density
        // 底板颜色跟服务里 `backgroundOf` 同一套算法：alpha = 百分比。
        val alpha = (255 * bgAlphaPercent / 100f).toInt().coerceIn(0, 255)
        view.configure(
            mainPx,
            subPx,
            ClusterPanelGeometry.DEFAULT_ROWS,
            accent,
            muted,
            (alpha shl 24) or 0x00101820,
            style,
        )
        view.snapshot = snapshot

        val width = ClusterPanelGeometry.width(clusterWidth, 0.94f)
        val height = ClusterPanelGeometry.height(mainPx, subPx, ClusterPanelGeometry.DEFAULT_ROWS)
        val origin = ClusterPanelGeometry.origin(clusterWidth, clusterHeight, width, height, 0.5f, 0.88f)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val bmp = Bitmap.createBitmap(clusterWidth, clusterHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(clusterBg)
        canvas.translate(origin[0].toFloat(), origin[1].toFloat())
        view.draw(canvas)
        save(bmp, name)
    }

    /** 渲染一整屏。 */
    private fun shot(view: View, w: Int, h: Int, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Palettes.night.bgTop)
        view.draw(canvas)
        save(bmp, name)
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File("../preview").apply { mkdirs() }
        val out = File(dir, name)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("PNG 没写出来：$name", out.exists() && out.length() > 0)
    }
}
