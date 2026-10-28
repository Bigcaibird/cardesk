package com.cardesk.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.ui.LauncherView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 第五十轮：**设计板同款数据**的 13 屏离屏渲染。
 *
 * ## 为什么要有这一组
 *
 * 之前那批预览（[PreviewRenderTest]）是**空态**渲染 —— 没有车况、没有天气、
 * 没有歌词、没有应用、没有任务。拿它跟设计板逐屏比，差的全是**数据差异**
 * （卡空着、歌词没画、网格只有 1 个应用），不是布局差异，看起来就像「没实现设计图」。
 *
 * 这一组把**设计板上的假数据原样灌进去**（12,480 km / 26° / 600 米 / 小镇姑娘 /
 * 18 个应用 / 4 条计划任务……，再逐屏渲染，产物是 `cardesk/preview/d01..d13-*.png`，
 * 与 `cardesk/design/png/s01..s13-*.png` **一一对应**，可以直接叠差异图。
 *
 * 假数据只活在**测试里**，不进产品代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignParityRenderTest {

    @Test
    fun renderDesignParityScreens() {
        // 夜间（默认）：设计板 s01-home-night.png … s13-float-night.png
        renderAll("d")
    }

    /**
     * **日间**模式同一组 13 屏。
     *
     * 日夜是**同一套 token、两套值**（`Palettes.night` / `Palettes.day`），
     * 所以日间必须单独出一组图去比对 —— 只验夜间等于没验一半。
     *
     * 强制进日间的办法（对齐 [com.cardesk.launcher.core.DayNight.resolveNightUi]）：
     * `daynight_enabled` 默认**关闭** → 走「上次应用的夜间态」；
     * 所以在 Activity 起来**之前**把 `daynight_applied_night_ui` 写成 `false` 即可。
     */
    @Test
    fun renderDesignParityDayScreens() {
        val prefs = com.cardesk.launcher.core.Prefs(RuntimeEnvironment.getApplication())
        prefs.setLastAppliedNight(false)
        renderAll("e")
    }

    private fun renderAll(prefix: String) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        layout(decor)
        val launcher = findLauncher(decor)
        assertNotNull("LauncherView 应该挂在窗口里", launcher)
        // 日夜是同一套 token 两套值：渲染前缀必须和实际拿到的调色板对得上，
        // 否则「日间那组图」其实是夜间渲的，比对毫无意义。
        assertEquals(
            "渲染前缀 $prefix 与实际日夜态对不上",
            prefix == "d",
            com.cardesk.launcher.core.Skin.isNight,
        )

        val dir = File("../preview").apply { mkdirs() }
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idle()
            // 状态栏时钟会被 refresh() 刷成真实时间，这里每次出图前都按设计板摆回 15:41。
            findField<com.cardesk.launcher.ui.shell.ShellStatusBar>(launcher!!, "statusBar")?.clockText = "15:41"
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bmp))
            FileOutputStream(File(dir, prefix + name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // —— ① 18 个假应用（设计板 s03 的 6×3 网格）+ 快捷入口 ——
        installDesignApps(activity)
        // 第五十四轮：Dock 快捷应用的 3 个假包（只给名字，不给图标）
        installDesignQuickApps(activity)
        // 第五十五轮：ADB 调试页的**确定状态**（真机上 uid / 签名 / IP 都不可控）
        installDesignAdbState()

        // —— ② 灌设计板同款数据 ——
        applyDesignFixture(launcher!!, activity)

        // —— ③ 逐屏 ——
        shot("01-home.png")

        call(launcher, "showPage", 1)
        shot("02-nav.png")
        call(launcher, "showPage", 0)

        call(launcher, "showDrawer")
        shot("03-apps.png")
        call(launcher, "closeDrawer")

        call(launcher, "showClimate")
        shot("04-hvac.png")
        call(launcher, "onBack")

        call(launcher, "showSettings")
        shot("05-settings.png")
        call(launcher, "closeSettings")

        call(launcher, "showTasks")
        shot("06-tasks.png")
        call(launcher, "newTask")
        shot("07-taskedit.png")
        call(launcher, "onBack")
        call(launcher, "onBack")

        call(launcher, "showDockApps")
        shot("08-picker.png")
        call(launcher, "onBack")

        call(launcher, "showAccentPicker")
        shot("09-color.png")
        call(launcher, "onBack")

        call(launcher, "showKeys")
        shot("10-keys.png")
        call(launcher, "onBack")

        call(launcher, "showNavSlots")
        shot("11-slots.png")
        call(launcher, "onBack")

        // s12 的底图还是首页：前面的弹层开关会触发 refresh()，
        // 把首页媒体卡刷回「空态」（音乐会话在测试里是空的），
        // 这里按设计板 s01 的数据把首页媒体状态重新灌回去。
        findScenePage(launcher)?.apply {
            mediaTitle = "小镇姑娘"
            mediaArtist = "陶喆"
            mediaSource = "通用音乐"
            mediaPositionMs = 84_000L
            mediaDurationMs = 245_000L
            lyricPrev = "我看见 夜空中 最亮的星"
            lyricCurrent = "风雨里追赶 雾里分不清影踪"
            lyricNext = "请指引我靠近你"
            setLyricWipeImmediate(0.38f)
        }
        // 设计板 s12：媒体浮窗（460×250 卡片，歌名 / 歌手 / 进度 / 关闭键）
        findField<com.cardesk.launcher.ui.floats.FloatWindowLayer>(launcher, "floatLayer")
            ?.openMediaFloat(
                track = "小镇姑娘",
                artist = "陶喆",
                positionMs = 84_000L,
                durationMs = 245_000L,
            )
        shot("12-float.png")
        call(launcher, "onBack")

        // 设计板 s13：Dock 快捷应用配置面板（3 个槽位 / 更换 / 清除 / 清空全部）
        call(launcher, "showDockQuickApps")
        shot("13-quickapps.png")
        call(launcher, "onBack")

        // 设计板 s14：设置页「车控 → ADB 调试」（当前状态 10 行）
        call(launcher, "showSettings")
        findField<com.cardesk.launcher.ui.SettingsOverlay>(launcher, "settings")
            ?.showPageKey("adb")
        shot("14-adb.png")
        call(launcher, "closeSettings")
    }

    /**
     * ADB 调试页的**确定状态**（第五十五轮）。
     *
     * 真机上这一页的读数全是设备相关的：uid、签名、IP、5555 通不通，
     * 每一台车都不一样 —— 不注入的话这一屏没法跟设计板逐像素比。
     *
     * 注入的值与设计板 的那 10 行**逐字一致**。
     */
    private fun installDesignAdbState() {
        val runtime = com.cardesk.launcher.core.AdbBridge.AdbRuntime
        runtime.stateProvider = {
            com.cardesk.launcher.core.AdbState(
                uid = 1000,
                platformSigned = true,
                settingsSigned = true,
                devSetting = "1",
                adbSetting = "1",
                componentState = com.cardesk.launcher.core.AdbDebug.COMPONENT_STATE_ENABLED,
                componentEnabled = true,
                adbdService = "running",
                servicePort = "5555",
                persistPort = "",
                usbFunctions = 4L,
                portOpen = true,
                localIp = "192.168.1.112",
                hasDebugSnapshot = true,
                hasUsbSnapshot = true,
                hasPortSnapshot = true,
            )
        }
        runtime.supportProvider = {
            com.cardesk.launcher.core.AdbSupport(true, "固件与权限符合")
        }
        runtime.diagnosticsProvider = { "" }
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 把设计板上的假数据灌进 Launcher。
     *
     * 数据全部照抄设计板（`design/build_design_board.py`）里的那一套：
     * 12,480 km / 6.8 L/100km / 128.4 km / 1 小时 23 分 / 26° / 晴 · 空气优 /
     * 600 米 · 文景路 / 小镇姑娘 · 陶喆 / 386 km / 62%。
     */
    private fun applyDesignFixture(launcher: LauncherView, activity: MainActivity) {
        val prefs = com.cardesk.launcher.core.Prefs(activity.applicationContext)

        // ① 车况（设计板 s01 右列三张卡的数据源）
        val info = com.cardesk.launcher.vehicle.VehicleInfo(
            ambientTemp = 26f,
            rangeKm = 386,
            odometerKm = 12480,
            speedKmh = 0,
            gear = "P",
            tires = floatArrayOf(240f, 240f, 238f, 235f),
            fuelPercent = 62,
            source = "设计板夹具",
            doorsClosed = listOf(true, true, true, true),
            doorsAllClosed = true,
            hoodClosed = true,
            trunkClosed = true,
            windowsOpen = booleanArrayOf(false, false, false, false),
            windowsAllClosed = true,
            avgFuelConsumption = 6.8,
            tripKm = 128.4,
            tripDurationMs = 83.0 * 60.0 * 1000.0,
        )
        // 设计板 s04：空调**已关机**，双温区 23.5°（HvacLogic.state 的 setter 是私有的，用反射灌）
        val hvac = com.cardesk.launcher.vehicle.HvacState(
            power = false,
            ac = false,
            auto = false,
            circulation = 1,
            fanLevel = 0,
            tempDriver = 23.5f,
            tempPassenger = 23.5f,
            blowing = 2,
        )
        runCatching {
            val logic = com.cardesk.launcher.vehicle.VehicleController.logic
            val f = logic.javaClass.getDeclaredField("state").apply { isAccessible = true }
            f.set(logic, hvac)
        }

        // ② 场景页：天气 / 媒体 / 歌词 / 导航引导
        val scene = findScenePage(launcher)
        assertNotNull("LauncherView 里应该有 scenePage", scene)
        scene!!.bind(info, hvac, prefs.wallpaper)
        scene.weatherSky = "晴 · 空气优"
        scene.weatherTemp = 26
        scene.fuelPercent = 62
        scene.mediaTitle = "小镇姑娘"
        scene.mediaArtist = "陶喆"
        scene.mediaSource = "通用音乐"
        scene.mediaPlaying = false
        scene.mediaPositionMs = 84_000L
        scene.mediaDurationMs = 245_000L
        scene.lyricPrev = "我看见 夜空中 最亮的星"
        scene.lyricCurrent = "风雨里追赶 雾里分不清影踪"
        scene.lyricNext = "请指引我靠近你"
        // 设计板 的当前句是
        // `linear-gradient(90deg, var(--accent) 38%, var(--text) 38%)`
        // —— 已经唱到的 38% 用强调色。这里直接落位（不走指数逼近动画）。
        scene.setLyricWipeImmediate(0.38f)
        // 快捷应用：CarDesk / 日历 / 电话（设计板 s01 左下第一张卡）
        scene.setQuickApps(
            listOf(
                com.cardesk.launcher.ui.pages.ScenePage.QuickApp(
                    "com.design.preview.app1", "CarDesk", resIcon(activity, android.R.drawable.ic_menu_agenda),
                ),
                com.cardesk.launcher.ui.pages.ScenePage.QuickApp(
                    "com.design.preview.app2", "日历", resIcon(activity, android.R.drawable.ic_dialog_info),
                ),
                com.cardesk.launcher.ui.pages.ScenePage.QuickApp(
                    "com.design.preview.app3", "电话", resIcon(activity, android.R.drawable.ic_dialog_dialer),
                ),
            ),
        )
        // 导航引导：600 米 · 文景路 · 剩余 3.2 公里 / 8 分 · 满 8 条车道线
        // 设计板 s01 的转向图标是**右转**箭头（车道线里第 3 条也是右转高亮），
        // 所以引导编码取 3（NavGuideText.ICON_RIGHT 的第一个）。
        // ETA 口径：设计板是「15:49 到达」，= 15:41 + 8 分钟。
        scene.setGuide(
            com.cardesk.launcher.core.nav.NavGuideModel.build(
                icon = 3,
                segmentMeters = 600,
                currentRoad = "文景路",
                nextRoad = "",
                remainSeconds = 480,
                remainMeters = 3200,
                lanesRaw = DESIGN_LANES_RAW,
                now = designClock(15, 41),
            ),
        )

        // ③ 状态栏：位置获取中 + 26° + 15:41
        findField<com.cardesk.launcher.ui.shell.ShellStatusBar>(launcher, "statusBar")?.apply {
            setLocationWeather("", "晴 · 空气优", 26)
            clockText = "15:41"
            fuelPercent = 62
            assistText = "驾驶模式 · 待命"
        }

        // ④ 导航页组合卡：小镇姑娘 / 陶喆 / 1:24 / 4:05 + 三行歌词
        findField<com.cardesk.launcher.ui.pages.NavPage>(launcher, "navPage")?.apply {
            sourceLabel = "通用音乐"
            musicState = "已暂停"
            trackTitle = "小镇姑娘"
            trackArtist = "陶喆"
            artwork = null
            playing = false
            positionText = "1:24"
            durationText = "4:05"
            progress = 0.34f
            setLyricWindow(
                listOf(
                    "我看见 夜空中 最亮的星",
                    "风雨里追赶 雾里分不清影踪",
                    "请指引我靠近你",
                    "是否 星空的尽头 有你",
                ),
                0,
            )
        }

        // ④' Dock 快捷应用槽位（第五十四轮）：3 个示意图标
        val quickPkgs = listOf("com.design.nav", "com.design.phone", "com.design.music")
        prefs.dockQuickApps = quickPkgs
        findField<com.cardesk.launcher.ui.shell.DockBarView>(launcher, "dock")?.bindQuickApps(
            listOf(
                com.cardesk.launcher.ui.shell.DockBarView.QuickApp(
                    quickPkgs[0], "导航", quickIcon(0xFF33C6B4.toInt(), "N"),
                ),
                com.cardesk.launcher.ui.shell.DockBarView.QuickApp(
                    quickPkgs[1], "电话", quickIcon(0xFFE0A340.toInt(), "T"),
                ),
                com.cardesk.launcher.ui.shell.DockBarView.QuickApp(
                    quickPkgs[2], "音乐", quickIcon(0xFF4CC38A.toInt(), "M"),
                ),
            )
        )

        // ⑤ Dock 媒体卡
        findField<com.cardesk.launcher.ui.shell.DockBarView>(launcher, "dock")?.bindMedia(
            "小镇姑娘", "陶喆", false, 0.34f, null,
        )

        // ⑥ 计划任务（设计板 s06 的 4 条）
        com.cardesk.launcher.core.ScheduledTasks.save(
            prefs,
            mutableListOf(
                com.cardesk.launcher.core.ScheduledTask(
                    id = "t-design-1",
                    condition = com.cardesk.launcher.core.ScheduledTasks.COND_BOOT,
                    target = "com.design.preview.app4",
                    delaySeconds = 8,
                ),
                com.cardesk.launcher.core.ScheduledTask(
                    id = "t-design-2",
                    condition = com.cardesk.launcher.core.ScheduledTasks.COND_BOOT,
                    target = "com.design.preview.app5",
                    delaySeconds = 12,
                    enabled = false,
                ),
                com.cardesk.launcher.core.ScheduledTask(
                    id = "t-design-3",
                    condition = com.cardesk.launcher.core.ScheduledTasks.COND_LAUNCH,
                    target = "com.design.preview.app6",
                    delaySeconds = 20,
                ),
                com.cardesk.launcher.core.ScheduledTask(
                    id = "t-design-4",
                    condition = com.cardesk.launcher.core.ScheduledTasks.COND_LAUNCH,
                    target = "com.design.preview.app7",
                    delaySeconds = 30,
                ),
            ),
        )

        // ⑦ 抽屉排序：按设计板 1..18 的顺序
        prefs.appOrder = (1..18).map { "com.design.preview.app$it" }

        // ⑧ 设置页「启动切换」：设计板两个开关都是**开启态**
        prefs.startNaviCastEnabled = true
        prefs.startSwitchEnabled = true
    }

    /**
     * 设计板用的「当天几点几分」时间戳。
     *
     * Robolectric 会冻结 `System.currentTimeMillis()`，而设计板的 ETA 是
     * 所以这里显式构造 15:41 的时间戳喂给
     * `NavGuideModel.build(now = …)`。
     */
    private fun designClock(hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * 设计板 s01 的 8 条车道线：
     * 直行 / 直行 / **右转（高亮）** / 直行 / 直行 / **左转（高亮）** / 直行 / 直行。
     *
     * 编码语义见 `core/nav/LaneShape.kt`：直行 0、左转 16、右转 18，
     * `drive_way_lane_Front_icon > 0` 即「当前推荐车道」。
     */
    private val DESIGN_LANES_RAW = """
        {"drive_way_enabled":true,"drive_way_size":8,"drive_way_info":[
          {"drive_way_number":0,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":0},
          {"drive_way_number":1,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":0},
          {"drive_way_number":2,"drive_way_lane_Front_icon":18,"drive_way_lane_Back_icon":0},
          {"drive_way_number":3,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":0},
          {"drive_way_number":4,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":0},
          {"drive_way_number":5,"drive_way_lane_Front_icon":16,"drive_way_lane_Back_icon":0},
          {"drive_way_number":6,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":0},
          {"drive_way_number":7,"drive_way_lane_Front_icon":0,"drive_way_lane_Back_icon":0}]}
    """.trimIndent()

    /** 18 个假应用的图标（用系统图标，避免复制任何资源）。 */
    private val APP_ICON_RES = intArrayOf(
        android.R.drawable.ic_menu_agenda,
        android.R.drawable.ic_dialog_dialer,
        android.R.drawable.ic_menu_compass,
        android.R.drawable.ic_menu_edit,
        android.R.drawable.ic_dialog_info,
        android.R.drawable.ic_menu_month,
        android.R.drawable.ic_menu_search,
        android.R.drawable.ic_menu_share,
        android.R.drawable.ic_menu_slideshow,
        android.R.drawable.ic_menu_upload,
        android.R.drawable.ic_menu_view,
        android.R.drawable.ic_dialog_email,
        android.R.drawable.ic_menu_gallery,
        android.R.drawable.ic_menu_camera,
        android.R.drawable.ic_menu_myplaces,
        android.R.drawable.ic_menu_manage,
        android.R.drawable.ic_dialog_alert,
        android.R.drawable.ic_dialog_map,
    )

    /**
     * 装 18 个假应用（设计板 s03 的 6×3 网格 + s08 的 4 列网格）。
     *
     * 名字一律「应用 1..18」，与设计板逐格对应；图标用系统 drawable（不复制资源）。
     *
     * 注意：**PackageInfo 里不预先放 activities** —— Robolectric 的
     *  遇到同名 Activity 会直接返回、**不会**把它登记进
     * IntentFilter 表，于是 `queryIntentActivities(MAIN/LAUNCHER)` 一个也查不到。
     * 所以这里让 去创建那个 Activity：
     * 名字与图标都挂在 上，`ActivityInfo.loadLabel/loadIcon` 会回落到它。
     */
    private fun installDesignApps(activity: MainActivity) {
        val pm = shadowOf(activity.packageManager)
        for (i in 1..18) {
            val pkg = "com.design.preview.app$i"
            val cls = "$pkg.MainActivity"
            val label = "应用 $i"
            val app = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
                icon = APP_ICON_RES[(i - 1) % APP_ICON_RES.size]
                sourceDir = "/data/app/$pkg"
                publicSourceDir = sourceDir
            }
            val pi = PackageInfo().apply {
                packageName = pkg
                applicationInfo = app
                versionName = "1.0"
            }
            pm.installPackage(pi)
            val component = ComponentName(pkg, cls)
            pm.addActivityIfNotPresent(component)
            pm.addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }

    /**
     * 安装 3 个「快捷应用」假包（第五十四轮）。
     *
     * 只给名字、**不给图标资源** —— 这样配置面板里会走「首字占位」，
     * 与设计板 s13 的画法一致（设计板用色块 + 字母示意）。
     */
    private fun installDesignQuickApps(activity: MainActivity) {
        val pm = shadowOf(activity.packageManager)
        for ((pkg, label) in listOf(
            "com.design.nav" to "导航",
            "com.design.phone" to "电话",
            "com.design.music" to "音乐",
        )) {
            val cls = "$pkg.MainActivity"
            val app = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
                icon = 0
                sourceDir = "/data/app/$pkg"
                publicSourceDir = sourceDir
            }
            val pi = PackageInfo().apply {
                packageName = pkg
                applicationInfo = app
                versionName = "1.0"
            }
            pm.installPackage(pi)
            val component = ComponentName(pkg, cls)
            pm.addActivityIfNotPresent(component)
            // 故意**不**加 LAUNCHER intent filter：
            // 这 3 个是 Dock 快捷应用，不是桌面应用 —— 加了 filter 会混进
            // 应用抽屉的网格（设计板 s03 是 18 个应用，多 3 个就错位了）。
        }
    }

    /**
     * 快捷应用槽位的示意图标（第五十四轮）。
     *
     * 50×50 圆角 14 色块 + 白色字母 —— 与设计板 **同色同形**，
     * 这样 Dock 最右边那一段在离屏渲染里能和设计板对上。
     */
    private fun quickIcon(color: Int, letter: String): android.graphics.drawable.Drawable {
        val size = 50
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 14f, 14f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 26f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        c.drawText(letter, size / 2f, 34f, p)
        return android.graphics.drawable.BitmapDrawable(bmp)
    }

    /** 取系统图标（失败返回 null，界面会画默认圆点）。 */
    private fun resIcon(activity: MainActivity, res: Int): android.graphics.drawable.Drawable? =
        try {
            activity.resources.getDrawable(res, null)
        } catch (t: Throwable) {
            null
        }

    // ---------------------------------------------------------------- 小工具

    private fun layout(decor: View) {
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
    }

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

    private fun findScenePage(launcher: LauncherView): com.cardesk.launcher.ui.pages.ScenePage? =
        findField(launcher, "scenePage")

    private inline fun <reified T> findField(launcher: LauncherView, name: String): T? {
        val f = LauncherView::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(launcher) as? T
    }
}
