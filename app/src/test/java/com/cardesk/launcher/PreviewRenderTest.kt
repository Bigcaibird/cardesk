package com.cardesk.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.FileOutputStream
import java.time.Duration

/**
 * 把桌面、应用页、车辆页、空调浮层、360 浮层离屏渲染成 PNG，方便在没人肉设备时肉眼检查布局。
 * 产物写到 cardesk/preview/ 下。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewRenderTest {

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun call(launcher: LauncherView, name: String, vararg args: Any?) {
        val types = args.map { it!!::class.javaPrimitiveType ?: it.javaClass }.toTypedArray()
        launcher.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
            .invoke(launcher, *args)
    }

    /** 取导航页（给预览塞歌词用）。 */
    private fun navOf(launcher: LauncherView): com.cardesk.launcher.ui.pages.NavPage? {
        val f = LauncherView::class.java.getDeclaredField("navPage").apply { isAccessible = true }
        return f.get(launcher) as? com.cardesk.launcher.ui.pages.NavPage
    }

    private fun findSettings(view: View): com.cardesk.launcher.ui.SettingsOverlay? {
        if (view is com.cardesk.launcher.ui.SettingsOverlay) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findSettings(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun renderAllScreensToPng() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)

        val dir = File("../preview").apply { mkdirs() }
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        shot("01-home.png")
        // 第四十九轮：应用抽屉改成页面级卡片（设计板 s03-apps），状态栏 / Dock 仍可见。
        call(launcher!!, "showDrawer")
        shot("03-apps.png")
        call(launcher, "closeDrawer")
        // 第三十二轮：桌面只剩两页（第三页播放器页已去掉）。
        // 第二页 = 导航页，播放器就在它的右半屏。
        call(launcher, "showPage", 1)
        // 给导航页塞一句歌词（设计板 s02-nav 的歌词区是三行居中）。
        navOf(launcher)?.let { nav ->
            nav.lyricPrev = "我看见 夜空中 最亮的星"
            nav.lyricCurrent = "风雨里追赶 雾里分不清影踪"
            nav.lyricNext = "请指引我靠近你"
        }
        shot("02-nav.png")
        call(launcher, "showPage", 0)
        call(launcher, "showHvac")
        shot("04-hvac.png")
        call(launcher, "onBack")
        // 360 没有自有页面了（不画预览，画面是车机厂商的 AVM 窗口）
        // 这里不再截 360 那一屏。
        call(launcher, "showSettings")
        shot("06-settings.png")
        findSettings(decor)?.let { s ->
            s.showPageKey("tasks")
            shot("09-settings-tasks.png")
            s.showPageKey("lan")
            shot("10-settings-lan.png")
            s.showPageKey("platform")
            shot("22-settings-platform.png")
            s.showPageKey("sources")
            shot("23-settings-sources.png")
        }
        call(launcher, "closeSettings")
        call(launcher, "showClimate")
        shot("07-climate.png")

        // 增强导航栏两种承载（06-F01/F02/F03）：贴底 pill 内的设计 Dock / 槽位条
        val prefs = com.cardesk.launcher.core.Prefs(activity.applicationContext)
        prefs.enhancedNav = true
        prefs.navStyle = 0
        call(launcher, "build")
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        shot("11-nav-dock.png")
        prefs.navStyle = 1
        call(launcher, "build")
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        shot("12-nav-slots.png")
        call(launcher, "showNavSlots")
        shot("13-navslot-dialog.png")
        call(launcher, "onBack")

        // 计划任务 + 主题色 + 布局参数（dialog_task_list / dialog_task_editor / dialog_color_picker / dialog_desktop_layout）
        prefs.enhancedNav = false
        call(launcher, "build")
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        com.cardesk.launcher.core.ScheduledTasks.save(
            prefs,
            mutableListOf(
                com.cardesk.launcher.core.ScheduledTask(
                    id = "t-demo",
                    condition = com.cardesk.launcher.core.ScheduledTasks.COND_BOOT,
                    target = activity.packageName,
                    delaySeconds = 8,
                ),
                com.cardesk.launcher.core.ScheduledTask(
                    id = "t-demo2",
                    condition = com.cardesk.launcher.core.ScheduledTasks.COND_LAUNCH,
                    target = activity.packageName,
                    delaySeconds = 12,
                    enabled = false,
                ),
            ),
        )
        call(launcher, "showTasks")
        shot("14-tasks.png")
        call(launcher, "newTask")
        shot("15-task-editor.png")
        call(launcher, "onBack")
        call(launcher, "onBack")
        call(launcher, "showAccentPicker")
        shot("16-color.png")
        call(launcher, "onBack")
        call(launcher, "showDockApps")
        shot("17-app-picker.png")
        call(launcher, "onBack")
        call(launcher, "showKeys")
        shot("18-keys.png")
        call(launcher, "onBack")
        assertNotNull(launcher)
        launcher!!.showFloat(activity.packageName, "CarDesk 浮窗")
        shot("19-floats.png")
        launcher.showFloat(activity.packageName, "第二个浮窗")
        shot("20-floats-two.png")
        // 把设置切到「夜间」再重建，验证 Skin token 切换后的主页/设置可读性。
        // daynight_enabled 总开关 + daynight_mode（1=跟随时间）。
        prefs.dayNightEnabled = true
        prefs.dayNightMode = com.cardesk.launcher.core.Prefs.MODE_FOLLOW_TIME
        prefs.dayStartMin = 0
        prefs.nightStartMin = 1
        call(launcher, "applyTheme")
        activity.window.decorView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
        )
        activity.window.decorView.layout(0, 0, 1920, 1080)
        shot("24-home-night.png")
        call(launcher, "showSettings")
        shot("25-settings-night.png")
        call(launcher, "closeSettings")
        prefs.dayNightEnabled = false
        prefs.dayNightMode = com.cardesk.launcher.core.Prefs.MODE_FOLLOW_AMAP

        // 媒体页：用假会话 + LRC 歌词验证 bindTrack/bindLyric 的绘制（真机上数据来自）。
        // 播放器页：第三十二轮起第三页（MusicPage）已去掉，
        // 播放器搬到了**第二页组合卡的右半屏** —— 这里改成预览 NavPage 的播放器。
        val pal = com.cardesk.launcher.core.Palettes.night
        val music = com.cardesk.launcher.ui.pages.NavPage(activity, pal)
        music.sourceLabel = "QQ 音乐"
        music.musicState = "播放中"
        music.trackTitle = "夜航星"
        music.trackArtist = "不才"
        music.artwork = null
        music.playing = true
        music.positionText = "1:24"
        music.durationText = "4:05"
        music.progress = 0.34f
        val lrc = """
            [00:00.00]我看见 夜空中 最亮的星
            [00:12.00]请指引我靠近你
            [00:24.00]是否 星空的尽头 有你
            [00:36.00]我祈祷拥有一颗透明的心灵
            [00:48.00]和会流泪的眼睛
            [01:00.00]每颗心上某一个地方
            [01:12.00]总有记忆的芳华
        """.trimIndent()
        val timeline = com.cardesk.launcher.core.media.LrcParser.parse(lrc)
        // 第三十三轮：第二页播放器显示 5 句（上 2 + 当前 + 下 2），当前句在中间。
        music.setLyricWindow(timeline.lines.map { it.text }, 3)
        music.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        music.layout(0, 0, 1920, 1080)
        val mediaBmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val mediaCanvas = Canvas(mediaBmp)
        mediaCanvas.drawColor(0xFF06101E.toInt())
        music.draw(mediaCanvas)
        FileOutputStream(File(dir, "21-media.png")).use { out ->
            mediaBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * 首页导航引导卡 + 胎压顺序的单独预览。
     *
     * 单独一个测试方法，避免被别的浮层（设置 / 应用选择等）挡住。
     */
    @Test
    fun renderHomeNavGuideAndTireOrder() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val prefs = com.cardesk.launcher.core.Prefs(activity.applicationContext)
        val dir = File("../preview").apply { mkdirs() }
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val scene = findScenePage(launcher!!)
        assertNotNull("LauncherView 里应该有 scenePage", scene)
        // 胎压换成四个**互不相同**的值，直接肉眼核对四轮顺序：
        // 左列上=左前(2.1) 下=左后(2.3)，右列上=右前(2.2) 下=右后(2.4)。
        scene!!.bind(
            com.cardesk.launcher.vehicle.VehicleInfo(
                ambientTemp = 24f,
                rangeKm = 286,
                tires = floatArrayOf(2.1f, 2.2f, 2.3f, 2.4f),
                source = "预览",
            ),
            com.cardesk.launcher.vehicle.HvacState(),
            prefs.wallpaper,
        )
        scene.setGuide(
            com.cardesk.launcher.core.nav.NavGuideModel.build(
                icon = 2,
                segmentMeters = 1500,
                currentRoad = "文景路",
                nextRoad = "金峰北路",
                remainSeconds = 1080,
                remainMeters = 12400,
                lanesRaw = """{"drive_way_enabled":true,"drive_way_size":3,"drive_way_info":[
                    {"drive_way_number":0,"drive_way_lane_Front_icon":19,"drive_way_lane_Back_icon":4},
                    {"drive_way_number":1,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":2},
                    {"drive_way_number":2,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":1}]}""",
            ),
        )
        shot("27-home-guide.png")
        // 没有车道线时卡片要矮一截（visibility=gone）
        scene.setGuide(
            com.cardesk.launcher.core.nav.NavGuideModel.build(
                icon = 8,
                segmentMeters = 320,
                currentRoad = "文景路",
                nextRoad = "",
                remainSeconds = 60,
                remainMeters = 2400,
                lanesRaw = null,
            ),
        )
        shot("28-home-guide-nolanes.png")
        scene.setGuide(null)
    }

    /**
     * 引导卡的四种转向图标（直行 / 左转 / 右转 / 掉头）。
     *
     * 按 `ICON` 编码选
     *  / /，
     * 这里渲染 4 张，用来肉眼核对「图标确实会随编码变」。
     */
    @Test
    fun renderGuideIcons() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val dir = File("../preview").apply { mkdirs() }
        val scene = findScenePage(launcher!!)
        assertNotNull("LauncherView 里应该有 scenePage", scene)
        val scenePage = scene!!

        // (文件名, 图标编码, 期望的转向文案)
        val cases = listOf(
            Triple("29-guide-icon-straight.png", 1, "直行"),
            Triple("30-guide-icon-left.png", 2, "向左"),
            Triple("31-guide-icon-right.png", 3, "向右"),
            Triple("32-guide-icon-uturn.png", 8, "掉头"),
        )
        for ((name, icon, expectText) in cases) {
            scenePage.setGuide(
                com.cardesk.launcher.core.nav.NavGuideModel.build(
                    icon = icon,
                    segmentMeters = 320,
                    currentRoad = "文景路",
                    nextRoad = "",
                    remainSeconds = 600,
                    remainMeters = 8000,
                    lanesRaw = null,
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 图标编码 → 文案 的映射必须一致
            assertEquals(
                "图标编码 $icon 的转向文案",
                expectText,
                com.cardesk.launcher.core.nav.NavGuideText.directionText(icon).trim(),
            )
        }
        scenePage.setGuide(null)
    }

    /**
     * 首页「中央大字歌词」+ 音乐卡「进度环 / 封面光晕」的单独预览。
     *
     * 场景页**没有**歌词（里没有歌词控件），大字歌词是本轮
     * 超出增强；进度环和封面光晕
     * 是**补齐漏项**。
     */
    @Test
    fun renderHomeLyricAndMusicCard() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val dir = File("../preview").apply { mkdirs() }
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        val scene = findScenePage(launcher!!)
        assertNotNull("LauncherView 里应该有 scenePage", scene)
        val scenePage = scene!!

        // 一张暖色封面（用来验证封面光晕的主色采样）
        val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                art.setPixel(x, y, 0xFFE08A3C.toInt())
            }
        }
        scenePage.mediaTitle = "夜航星"
        scenePage.mediaArtist = "不才"
        scenePage.mediaSource = "QQ 音乐"
        scenePage.mediaPlaying = true
        scenePage.mediaArtwork = art
        scenePage.mediaPositionMs = 84_000L
        scenePage.mediaDurationMs = 245_000L

        // ① 有歌词：中央大字（当前句高亮 + 上下句淡出）
        scenePage.lyricPrev = "我看见 夜空中 最亮的星"
        scenePage.lyricCurrent = "请指引我靠近你"
        scenePage.lyricNext = "是否 星空的尽头 有你"
        shot("33-home-lyric.png")

        // ② 没歌词：中央整块不画（不留空位）
        scenePage.lyricPrev = ""
        scenePage.lyricCurrent = ""
        scenePage.lyricNext = ""
        shot("34-home-music-card.png")

        // ③ Dock 歌词槽被关掉后不该再占位置（槽位条样式）
        val prefs = com.cardesk.launcher.core.Prefs(activity.applicationContext)
        prefs.enhancedNav = true
        prefs.navStyle = 1
        // 故意塞一个「歌词」槽：关掉歌词显示后它应该整个消失（不画、不占宽度）
        val slots = com.cardesk.launcher.core.NavSlots.defaults()
        slots.add(2, com.cardesk.launcher.core.NavSlot.createLyric())
        com.cardesk.launcher.core.NavSlots.save(prefs, slots)
        call(launcher, "build")
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        shot("35-nav-slots-nolyric.png")
    }

    /**
     * 封面在各个位置的显示：场景页音乐卡 / Dock 媒体槽 / 导航页组合卡 / 音乐页黑胶。
     *
     * 封面出现在 4 处
     *  / （导航页组合卡）/ （音乐页黑胶）。
     */
    @Test
    fun renderCoverInDockAndPages() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val dir = File("../preview").apply { mkdirs() }
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // 一张有明确主色的封面（暖橙），用来肉眼核对强调色 / 光晕也跟着变
        val art = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until 240) {
            for (x in 0 until 240) {
                art.setPixel(x, y, 0xFFE08A3C.toInt())
            }
        }

        val scene = findScenePage(launcher!!)
        assertNotNull("LauncherView 里应该有 scenePage", scene)
        scene!!.mediaTitle = "海阔天空"
        scene.mediaArtist = "BEYOND"
        scene.mediaSource = "QQ 音乐"
        scene.mediaPlaying = true
        scene.mediaArtwork = art
        scene.mediaPositionMs = 84_000L
        scene.mediaDurationMs = 239_000L

        // Dock 媒体槽（60×60 圆角 16）
        findField<com.cardesk.launcher.ui.shell.DockBarView>(launcher, "dock")?.bindMedia(
            "海阔天空", "BEYOND", true, 0.35f, art,
        )
        // 导航页组合卡（80×80 圆角 26）
        findField<com.cardesk.launcher.ui.pages.NavPage>(launcher, "navPage")?.apply {
            trackTitle = "海阔天空"
            trackArtist = "BEYOND"
            artwork = art
        }
        shot("36-home-cover.png")

        // 导航页（组合卡里的专辑图）
        call(launcher, "showPage", 1)
        shot("37-nav-cover.png")
        // 第三十二轮：第三页（播放器页）已去掉，没有单独的播放器页预览了。
        call(launcher, "showPage", 0)
    }

    /**
     * 第三十九轮（4385）：导航页歌词**放大 + 跑马灯**。
     *
     * 喂一首带**超长句**的歌词，渲染两帧：
     * - 第一帧：超宽的当前句**左对齐**（不再被 `ellipsize` 截断）；
     * - 把时钟往前推 2.5 秒再渲染：句子已经**向左滚**了一段。
     *
     * 歌词控件用 `SystemClock.uptimeMillis()`（单调时钟），Robolectric 下可控。
     */
    @Test
    fun renderNavLyricMarquee() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)

        val dir = File("../preview").apply { mkdirs() }
        // 注意：歌词跑马灯会 `postInvalidateOnAnimation`（一直排队下一帧）。
        // 这时再 会变成「绘制 → 再排队 → 再绘制」的无限循环（OOM），
        // 所以这里只做**同步绘制**，不 idle。
        fun shot(name: String) {
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // 先切到第二页并把切页动画跑完。
        // 注意顺序：必须在灌歌词**之前** idle()——歌词一灌上，跑马灯在第一次绘制时
        // 就会 `postInvalidateOnAnimation` 一直排队下一帧，此时再 idle() 会变成
        // 「绘制 → 再排队 → 再绘制」的无限循环（OOM）。
        call(launcher!!, "showPage", 1)
        shadowOf(Looper.getMainLooper()).idle()

        findField<com.cardesk.launcher.ui.pages.NavPage>(launcher, "navPage")?.setLyricWindow(
            listOf(
                "今天我 寒夜里看雪飘过",
                "怀着冷却了的心窝飘远方",
                // 这一句故意很长：宽到一行放不下 → 触发跑马灯
                "风雨里追赶 雾里分不清影踪 天空海阔你与我 重新开启一段旅程 祝福你心中有花开满沿途",
                "再多的回忆 也不怕",
                "此刻与你相拥",
            ),
            2,
        )
        // 跑马灯的锚点是「换句的时刻」→ 起点帧：超宽句**左对齐、不截断**
        shot("38-nav-lyric-marquee-start.png")
        // 推时钟 2.5 秒（越过左端停留）→ 滚动帧
        ShadowSystemClock.advanceBy(Duration.ofMillis(2500))
        shot("39-nav-lyric-marquee-scrolling.png")
    }

    /**
     * 第四十五轮：车况卡上的**车门张开姿态**。
     *
     * 车门示意是一张俯视车模，
     * 每一扇门绕**铰链**转 ±45°。
     *
     * 这里渲两帧：一帧全关、一帧左前门 + 右后门开着。
     */
    @Test
    fun renderCarStatusDoorOpen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        val scene = findScenePage(launcher!!)
        assertNotNull("LauncherView 里应该有 scenePage", scene)
        val scenePage = scene!!
        val dir = File("../preview").apply { mkdirs() }

        fun shot(name: String) {
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        fun info(doors: List<Boolean?>, allClosed: Boolean) = com.cardesk.launcher.vehicle.VehicleInfo(
            rangeKm = 386,
            odometerKm = 12480,
            ambientTemp = 18f,
            speedKmh = 0,
            gear = "P",
            tires = floatArrayOf(240f, 240f, 238f, 235f),
            fuelPercent = 62,
            source = "模拟车辆",
            doorsClosed = doors,
            doorsAllClosed = allClosed,
            hoodClosed = true,
            trunkClosed = true,
            windowsOpen = booleanArrayOf(false, false, false, false),
            windowsAllClosed = true,
        )

        // ① 四门全关
        scenePage.bind(
            info(listOf(true, true, true, true), true),
            com.cardesk.launcher.vehicle.HvacState(),
            0,
        )
        shot("40-car-status-closed.png")

        // ② 左前门 + 右后门开着（前门 / 后门的旋转方向相反，肉眼可验）
        scenePage.bind(
            info(listOf(false, true, true, false), false),
            com.cardesk.launcher.vehicle.HvacState(),
            0,
        )
        shot("41-car-status-door-open.png")

        // ③ 右前门 + 左后门开着（另一侧）
        scenePage.bind(
            info(listOf(true, false, false, true), false),
            com.cardesk.launcher.vehicle.HvacState(),
            0,
        )
        shot("42-car-status-door-open2.png")
    }

    /** 从 LauncherView 里取场景页（字段是 private，用反射拿）。 */
    private fun findScenePage(launcher: LauncherView): com.cardesk.launcher.ui.pages.ScenePage? {
        val f = LauncherView::class.java.getDeclaredField("scenePage")
        f.isAccessible = true
        return f.get(launcher) as? com.cardesk.launcher.ui.pages.ScenePage
    }

    /** 从 LauncherView 里取任意私有字段（泛型）。 */
    private inline fun <reified T> findField(launcher: LauncherView, name: String): T? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher) as? T
    }
}
