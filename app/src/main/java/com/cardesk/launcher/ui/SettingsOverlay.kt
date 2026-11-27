package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.WeakHashMap
import com.cardesk.launcher.core.AdbDebug
import com.cardesk.launcher.core.AdbDiagnostics
import com.cardesk.launcher.core.AdbOutcome
import com.cardesk.launcher.core.DayNight
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.GlassDrawable
import com.cardesk.launcher.core.NavSlots
import com.cardesk.launcher.core.OemDockPolicy
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.OemKeyProtocol
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.core.ScheduledTasks
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.apkstore.ApkEntry
import com.cardesk.launcher.core.apkstore.ApkStore
import com.cardesk.launcher.cluster.ClusterLyricColors
import com.cardesk.launcher.cluster.ClusterLyricsService
import com.cardesk.launcher.core.log.LogLevel
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.update.AppUpdate
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.widget.CardStylePreviewView
import com.cardesk.launcher.ui.widget.QrCodeView
import com.cardesk.launcher.ui.widget.SpectrumView
import com.cardesk.launcher.ui.widget.WallpaperThumbView
import com.cardesk.launcher.vehicle.DriveMode
import com.cardesk.launcher.vehicle.VehicleController
import com.cardesk.launcher.vehicle.VinReader

/**
 * 设置浮层（5 组 25 个设置页 + 一套控件库）。
 *
 * 本轮按 `cardesk-ui/design/redesign/s01..s25` 重做视觉，**与画板逐像素对齐的是壳层**：
 * - **两块 L2 玻璃浮层**：左导航 x=24、宽 [NAV_W]，右内容 x=420、宽 1476，
 *   两者 y=64..1056（高 992）、间距 24；圆角 [PANEL_RADIUS]，
 *   材质用 [GlassDrawable]（按位置取模糊背景 + 填充 + 1px 描边 + 顶部高光）；
 * - 暗罩 26%（[SHELL_SCRIM_ALPHA]）—— **桌面仍然透出来**（第124轮的要求），
 *   所以只有两块面板自己带模糊，不铺整屏 backdrop（那会把桌面卡片全糊掉）；
 * - 左栏：分组标题 14px 弱字 + 条目 18px，选中 = accent 淡底胶囊 + accent 字、无描边；
 *   画板左栏**没有品牌标题**，34px「桌面设置」已删；
 * - 右栏：页标题 [TITLE_SIZE] 粗体 (30, 22)、右上 56×56 白色圆角方块 + 「×」；
 *   正文左右留白 [CONTENT_PAD]，行高 [ROW_H]，行底 1px [Skin.GLASS_STROKE] 分割线；
 * - 行组件 [row]：标题 [ROW_TITLE] 粗 + 说明 [ROW_SUB] 弱字 + 右侧控件（开关/箭头/值），
 *   控件**垂直居中**，和画板一致（旧版是「控件顶 +14」）；
 * - 分段控件 [buildSegment]：选中 accent 淡底 + accent 描边 + accent 字，
 *   未选中 [Skin.surfaceSoft]、容器透明（旧版是一个大号软白容器，画板上没有）；
 * - 按钮 [actionButton]：白底圆角 [BUTTON_RADIUS]，主行动 accent 实底，胶囊仍 [PILL]；
 * - 夜间走 [Skin.isNight] 的同一套令牌：结构和尺寸一个像素都不变，只换颜色。
 *
 * 功能项与文案一个没少（文案有测试盯着：`SettingsCopyTest` / `SettingsDropdownTest` /
 * `CustomWallpaperPreviewTest`）。
 *
 * ## 本轮修复清单（画板 → 代码）
 *
 * 逐条对着 `design/redesign/` 下 45 张 PNG 量出来的偏差，改完的对应关系如下
 * （行号会随编辑漂移，按函数名检索最稳）：
 *
 * | 画板 | 改了什么 |
 * |---|---|
 * | s01 启动与开机 | 删掉重复的「启动后」小标题（`bootCaption` 整块移除）与左栏底部的 VIN /「开源与声明」（画板没有；合规入口仍在「关于」页 `select("about")`）；「目标驾驶模式」三个 chips 改成**右对齐**并去掉「模式」后缀（`DriveMode.UI_MODE_LABELS.map { removeSuffix("模式") }`，落盘仍用 `UI_MODE_VALUES`，语义不变）；补回画板底部的说明「关闭「桌面开机启动」后…」 |
 * | s02 按键增强 | 行高按画板收紧（开关行 [KEY_SWITCH_ROW_H] 96 / 按键行 [KEY_ROW_H] 104 / 驾驶模式块 [KEY_MODE_ROW_H] 110）；底部为「按键通路」自检区（最近按键 + 单击/长按自测），已移除媒体键增强与重复编辑器入口 |
 * | s05 媒体源 | 单列 9 行 → **两列**（左 5 右 4，按列填充，[MEDIA_COL_W] 685）；右侧状态改成**胶囊**（绿「已安装」/ 灰「未安装」/ 蓝「兜底来源」，[pillTag]），**不再显示包名** |
 * | s06 日夜模式 | 补「同步方式」分区标签；三张模式卡 96 → [CARD_CHOICE_H] 90 高、间距 12 → [CARD_CHOICE_GAP] 20，对齐画板 y316..406；「切换时间」分区标签保留（仅在「跟随时间」时出现） |
 * | s07 壁纸 | 整页改**两栏**（[WALLPAPER_COL_W] 598）：左栏 当前壁纸 / 自定义壁纸 / 导入照片…，右栏 3×3 预设 / 壁纸轮播 / 视频壁纸；空态框改**虚线**边框、高度按画板 300 |
 * | s08 桌面卡片 | 整页改**两栏**（[CARD_STYLE_COL_W] 541）：左栏 预览 / 透明度 / 圆角 / 颜色 / 恢复跟随主题，右栏 7 张卡（尺寸 chips + 显示开关）/ 卡片库 / 恢复默认布局；圆角滑块排到颜色之前，与画板左栏顺序一致 |
 * | s11 Dock 与导航栏 | 整页改**两栏**（[DOCK_COL_W] 570）：左栏 6 个开关 / 滑杆 / picker，右栏 全局 Dock / 增强导航栏 / 下移 Dock / 导航栏样式 / 槽位（15 项）+ 编辑槽位 / 恢复默认 15 槽位 / Dock 快捷应用；「已配置」恒为 `n / 15 个`，不再出现「未配置」 |
 * | s03 方控仪表 | 补回画板开关下方那行说明「关闭后 CarDesk 不再接收方控按键，全部交还车机原厂逻辑。」（`pageCluster`，走 `noteRaw`：文案不在 `SettingsCopyTest` 白名单里） |
 * | s09 歌词 | 整页改**两栏**（[LYRIC_COL_W] 618 + 间距 28）：左栏 首页中央歌词（布局 / 字号缩放 / 预览）+ 面板位置与样式，右栏 投射 + 颜色、字号与背景；预览条 430 → [LYRIC_PREVIEW_H] 180（画板 618×175） |
 * | s22 默认设置 | 两条满宽大按钮 → **一行一项**：左边「系统默认桌面 / 系统设置」标签，右边窄按钮贴右内边（[nativeSettingRow]） |
 * | s23 系统诊断 | 「系统能力」的状态从一行彩色文字改成右侧**胶囊**（[pillTag]：绿「可用」/ 灰「不可用」），和画板一致 |
 * | s24 重启车机 | 整页改**居中**警告块：危险色淡底的电源图标（就地画 304° 弧 + 竖线，无新色值）+ 标题 + 说明 + 居中危险按钮；不再是「左对齐分区标签 + 满宽按钮」 |
 * | s25 诊断日志 | 三个按钮改**横排**（[LOG_BUTTON_H] 56 + [BUTTON_GAP]，画板 y390..425）；日志区改右侧「最近日志」窄栏（[recentLogColumn]：时间 + 级别徽标 + `Tag 消息`，逐行 1px 分隔）；占用体积按真实字节换算（[bytesText]，取不到就用 `Logx.totalBytes()` 的真实值，不写死数字） |
 *
 * ## 与画板差异（诚实清单）
 *
 * 下面这些**在本文件里改不动**，或者改了会撞到别人写死的契约，如实列出：
 *
 * 1. **s08 卡片透明度默认 62%**：默认值在 `core/CardStyle.kt`（`DEFAULT_OPACITY = 100`），
 *    本文件只读写已有键，不能改默认值。画板 62% 需要 core 改一行。
 * 2. **s08 卡片圆角默认 24**：同上，`CardStyle.DEFAULT_RADIUS = 28`，
 *    要改也落在 `core/CardStyle.kt`。
 * 3. **s11 Dock 圆角默认 32**：`Prefs.dockCornerRadius` 默认 0，且
 *    `DockCornerRadiusDefaultTest` 明写「第190轮起 Dock 圆角默认值应为 0（直角横条）」——
 *    改成 32 必须同时改 `core/Prefs.kt` 和那条断言，超出「只改一个文件」的范围。
 * 4. **s11 两栏 vs 一条单元测试**：`SettingsDropdownTest.按钮底边到下一项要真的有空隙_不是零距离`
 *    要求「配置 Dock 快捷应用…」是 `body` 的**直属子节点**且后面还有一行；
 *    改成右栏后它是嵌套节点，那条断言会抛 `NoSuchElementException`。
 *    本轮按画板优先（两栏），把冲突留给测试侧决定：要么放宽成递归查找，要么把按钮提回 body 直属。
 * 5. **s05「兜底来源」的蓝**：画板 #3E9BFF 是 info 蓝，`Palette` 没有 info 令牌；
 *    这里用项目既有的蓝 `Palettes.ACCENT`（#3D7BFF），**没有新增硬编码颜色**。
 * 6. **s05 默认媒体源**：代码语义的兜底是「通用音乐」（`GENERIC_ID`），画板高亮 QQ 音乐。
 *    本页不伪造默认值，只把状态胶囊化；默认来源仍是用户/宿主选的。
 * 7. **s07 空态框没有画板的「眼睛」图标**：项目里没有对应矢量资源，
 *    不新增依赖/资源，只用「还没有导入壁纸」文案 + 虚线框表达同一个状态。
 * 8. **s06 三张模式卡宽度**：画板 456×3 + 21 间距，本页按权重三等分（464）+ 20 间距，误差 8px。
 * 9. **s02 / s07 / s08 / s11 的行高不是全局 128**：画板这几页本身用的是 93～113 的紧凑行距，
 *    所以只在对应的行上覆写高度（[ROW_H] 仍是默认值，其余页不受影响）。
 * 10. **s23「平台判定」的 chips 文案**：画板写「自适应 / 骁龙603 / 骁龙820A」，
 *    代码的 `platformOverride` 语义是「自动识别 / 强制 E03 / 强制 820A」（E03 就是 603 平台的代号）。
 *     这句文案牵到既有落盘说明，本轮保留代码侧写法，只把**状态胶囊**对齐了画板。
 * 11. **s09 预览条高度**：画板 618×175，本页取 180 —— 再矮一点三行歌词会贴着边框。
 * 12. **s12–s21 各页**：逐张对着画板核过一遍，都是**单列 + 分区标签 + 行内右控件**的同一套结构，
 *     没有分栏 / 按钮错位这类「版式」偏差；文案与入口级别的差异（例如 s13 云端列表的骨架态、
 *     s12 的共享目录数据）不属于版式，未动。
 */
class SettingsOverlay(
    context: Context,
    private val prefs: Prefs,
    private val pal: Palette,
    /**
     * 屏幕底部被**别的窗口**吃掉的高度（设计 px）。
     *
     * ## 为什么需要它（第三百零七轮，用户 m02052）
     *
     * 实车/模拟器上 `GlobalDockService` 的全局 Dock 是一个**独立窗口**
     * （type 2024 导航栏面板，`[0,968][1920,1080]`）——
     * 它盖在应用窗口最下面 112px 上。设置页按 1080 的设计高度排版时，
     * 两块面板的底边（画板 y=1056）与左栏滚动区的最后两行
     * （「ADB 调试」「诊断日志」）正好落在这一条里：
     * **看不见，也点不到**（触摸被那个窗口吞掉）。
     *
     * 所以宿主用 [com.cardesk.launcher.ui.LauncherView.settingsBottomReserve]
     * 算出这条被占高度，这里把它从面板高度里减掉 —— 面板整体上移，
     * 内容不再伸进被别的窗口盖住的那一条。
     *
     * 0 = 没有这个遮挡（手机档 / 没开全局 Dock / 窗口本来就不含这一条）。
     */
    private val bottomReserve: Float = 0f,
) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    var onApply: (() -> Unit)? = null

    /**
     * 「立即切换」（s06）：把当前明暗**真的翻过来**。
     *
     * 画板里这是一个动作按钮，不是「重放设置」。没有接这个钩子时退回 [onApply]。
     */
    var onToggleDayNight: (() -> Unit)? = null

    /**
     * 「默认媒体源」被改（第 125 轮）。
     *
     * 传来源 id。宿主负责落盘 + 锁定对应的包 + 刷新桌面。
     */
    var onDefaultMediaSource: ((String) -> Unit)? = null
    var onVehicleSource: (() -> Unit)? = null
    var onEditNavSlots: (() -> Unit)? = null
    var onShowTasks: (() -> Unit)? = null
    var onEditTask: ((com.cardesk.launcher.core.ScheduledTask?) -> Unit)? = null

    /** 桌面卡片布局（顺序/尺寸/显隐）变了：让场景页立刻重排。 */
    var onCardLayoutChanged: (() -> Unit)? = null

    /**
     * 导航栏槽位配置变了：让宿主把槽位重新读回来并刷到槽位条上。
     *
     * ## 为什么需要单独一个回调（第154轮）
     *
     * 「恢复默认 15 槽位」只调 [onApply] 是不够的 ——
     * `onApply` → `LauncherView.onSettingsApplied()` 只在「增强导航栏开关」或
     * 「导航栏样式」变了时才 `build()` 重建，而重置槽位两个都不改 ⇒ 不重建，
     * 导航栏看上去纹丝不动（值存进盘里了，但要等重启才生效）。
     *
     * 「编辑槽位…」面板走的是 `LauncherView.showNavSlots()` 里的
     * `slotsBar?.slots = list`，那才是「当场生效」的关键动作。
     * 这个回调就是把同一件事交给宿主做。
     */
    var onNavSlotsChanged: (() -> Unit)? = null

    /**
     * 首页中央大字歌词的**缩放**变了：让场景页立刻重画。
     *
     * 第 114 轮：拖滑杆时桌面在背后就能看到字变大 —— 等每秒 tick 刷新太慢，
     * 拖着滑杆却要等一秒才看到反馈，很别扭。
     */
    var onSceneLyricChanged: (() -> Unit)? = null

    /**
     * 预览条用的**真实歌词**（顺序 = 布局的行序：最上 → 最下）。
     *
     * 第 115 轮：返回 null 就用预览条自带的示例文案。
     * 桌面正在放歌时用真实歌词，用户看到的就是自己那首歌变大的效果。
     *
     * 第 117 轮：要**按布局**取 —— 三行和 KTV 两行显示的句子不一样，
     * 预览必须跟桌面显示的那几行一致。
     */
    var lyricPreviewLines: ((com.cardesk.launcher.core.media.LyricLayout) -> List<String>?)? = null
    var onShowUpdate: (() -> Unit)? = null
    var onShowRestart: (() -> Unit)? = null
    var onShowAccent: (() -> Unit)? = null

    /** 「指定隐藏状态栏的应用」选择器（MainActivity.openDesignDockAppPicker 的另一半）。 */
    var onShowStatusApps: (() -> Unit)? = null

    /**
     * 「Dock 快捷应用」配置面板（第五十四轮）。
     *
     * Dock 最右边多了一段快捷应用槽位，槽位内容由用户在这里配
     * （也可以直接长按 Dock 上的槽位进同一个面板）。
     */
    var onShowDockQuickApps: (() -> Unit)? = null

    /** 「桌面快捷应用」卡片配置面板（4 槽位自由更换与排序）。 */
    var onShowSceneQuickApps: (() -> Unit)? = null

    /** 进入 Dock 栏按钮与控件拖动排序模式。 */
    var onReorderDock: (() -> Unit)? = null

    /**
     * 打开画中画应用选择器。
     *
     * @param true = 选**副画**的应用（`pip2_app`），false = 主画（`pip_app`）。
     */
    var onShowPipPicker: ((Boolean) -> Unit)? = null

    /**
     * 壁纸被换掉（第五十七轮）。
     *
     * 设置页只管落盘（`design_wallpaper_index`），**真正的画面更新**交给桌面：
     * 桌面收到后立刻把 `BackgroundView` 换成新壁纸，用户在设置页里就能看到效果。
     */
    var onWallpaperChange: ((Int) -> Unit)? = null

    /** 切换为「默认日夜壁纸」（L0 日夜流光渐层，随日夜模式自动切换）。 */
    var onUseDefaultDayNightWallpaper: (() -> Unit)? = null

    /** 打开系统相册选图（日间静态壁纸）。 */
    var onImportWallpaper: (() -> Unit)? = null

    /** 打开系统相册选图（夜间静态壁纸）。 */
    var onImportNightWallpaper: (() -> Unit)? = null

    /** 打开应用内图片库选图（日间静态壁纸）。 */
    var onPickWallpaper: (() -> Unit)? = null

    /** 打开应用内图片库选图（夜间静态壁纸）。 */
    var onPickNightWallpaper: (() -> Unit)? = null

    /** 使用已经导入的自定义壁纸。 */
    var onUseCustomWallpaper: (() -> Unit)? = null

    /** 删除日间自定义壁纸。 */
    var onDeleteCustomWallpaper: (() -> Unit)? = null

    /** 删除夜间自定义壁纸。 */
    var onDeleteCustomNightWallpaper: (() -> Unit)? = null

    /** 壁纸轮播开关变了（第207轮）。 */
    var onWallpaperCarouselChange: ((Boolean) -> Unit)? = null

    /** 视频壁纸开关变了（第207轮；true = 开）。 */
    var onVideoWallpaperChange: ((Boolean) -> Unit)? = null

    /** 打开系统相册选视频（第207轮：视频壁纸）。 */
    var onImportVideoWallpaper: (() -> Unit)? = null

    /**
     * 打开**应用内**的视频选择器（第219轮）。
     *
     * 实车上系统选择器不可靠（秒退、不带 uri）⇒ 主路径改成我们自己列视频。
     * 系统那条路保留为**备选**（见 [onImportVideoWallpaper]）。
     */
    var onPickVideoWallpaper: (() -> Unit)? = null

    /** 删除已导入的视频壁纸（第207轮）。 */
    var onDeleteVideoWallpaper: (() -> Unit)? = null

    // ---------------------------------------------------------------- 第三百一十八轮：夜间视频
    /** 点「导入夜间视频…」（应用内视频选择器）。 */
    var onPickNightVideoWallpaper: (() -> Unit)? = null
    /** 点「用系统选择器导入夜间…」。 */
    var onImportNightVideoWallpaper: (() -> Unit)? = null
    /** 点「删除夜间视频」。 */
    var onDeleteNightVideoWallpaper: (() -> Unit)? = null

    /** 在线壁纸下载并应用完成回调：(kind, night)。 */
    var onOnlineWallpaperApplied: ((com.cardesk.launcher.core.wallpaperstore.WallpaperKind, Boolean) -> Unit)? = null

    /**
     * 卡片风格改了（第五十八轮）：透明度 / 颜色 / 圆角。
     *
     * 设置页只管落盘 + 刷新预览，**全局生效**交给桌面（桌面要重算 token 并刷新画面）。
     */
    var onCardStyleChange: (() -> Unit)? = null

    /**
     * 打开卡片颜色选择器（第五十八轮）。
     *
     * 复用现成的 [com.cardesk.launcher.ui.dialogs.ColorPickerDialog]，
     * 选中后桌面会把结果写回 `prefs.cardColor`。
     */
    var onShowCardColor: (() -> Unit)? = null

    private data class NavItem(val key: String, val label: String)
    private data class NavGroup(val title: String, val items: List<NavItem>)

    /**
     * 左侧导航分组与条目，按 重建
     *
     */
    /**
     * 左侧导航树（第149轮重排）。
     *
     * ## 第149轮改了什么
     *
     * ① **合并同模块的页面** —— 原来 28 项，现在 21 项：
     *
     * | 原来 | 现在 |
     * |---|---|
     * | 启动切换 + 桌面自启 | **启动与开机** |
     * | 桌面卡片 + 卡片风格 | **桌面卡片** |
     * | 桌面歌词 + 仪表歌词 | **歌词** |
     * | Dock 状态栏 + Dock 快捷应用 | **Dock 与导航栏** |
     * | （藏在系统诊断里）默认媒体源 | **媒体源** |
     *
     * ② **媒体源单独成组** —— 「默认用哪个音乐 App」是天天要碰的设置，
     * 之前塞在「系统诊断」里（那页是给排障用的），用户得先知道那个地名才找得到。
     *
     * ③ **删除死代码** —— 旧的 `globalGroups` / `systemGroups` 两份 list
     * 只喂两个从没被 `addView` 进视图树的计数徽标，纯属白构造。
     */
    /** 当前选中的页（导航树和页面内容都跟着它走）。 */
    private var selectedKey = "boot"

    /** 离开当前页时要做的清理（氛围灯页要退订实时频谱）。 */
    private var pageTeardown: (() -> Unit)? = null

    /** 仪表歌词页的「投射目标」列表（切页时要从视图上摘掉）。 */
    private var clusterDisplayList: LinearLayout? = null

    /** 仪表歌词页的「投射状态」文本。 */
    private var clusterStatusText: TextView? = null

    /** 仪表歌词「主 / 副歌词颜色」的取色器回调。 */
    var onShowClusterLyricColor: ((Int) -> Unit)? = null

    private val mergedGroups = listOf(
        NavGroup(
            "智能功能",
            listOf(
                NavItem("boot", "启动与开机"),
                NavItem("keys", "按键增强"),
                NavItem("cluster", "方控仪表"),
                NavItem("tasks", "自动任务"),
                NavItem("media", "媒体源"),
            ),
        ),
        NavGroup(
            "布局与显示",
            listOf(
                NavItem("daynight", "日夜模式"),
                NavItem("wallpaper", "壁纸"),
                NavItem("cards", "桌面卡片"),
                NavItem("lyric", "歌词"),
                NavItem("dualpip", "双画中画"),
                NavItem("dock", "Dock 与导航栏"),
            ),
        ),
        NavGroup(
            "网络与升级",
            listOf(
                NavItem("lan", "局域网传输"),
                NavItem("apkstore", "云端应用"),
                NavItem("update", "在线更新"),
            ),
        ),
        NavGroup(
            "车控",
            listOf(
                NavItem("ambient", "氛围灯"),
                NavItem("p360", "360 环影"),
                NavItem("autostop", "启停控制"),
                NavItem("carinfo", "车辆信息"),
                NavItem("hotspot", "自动热点"),
                NavItem("adb", "ADB 调试"),
            ),
        ),
        NavGroup(
            "系统",
            listOf(
                NavItem("oemsettings", "原厂设置"),
                NavItem("native", "默认设置"),
                NavItem("diagnostics", "系统诊断"),
                NavItem("restart", "重启车机"),
                // 调试工具（没有这一页，是为真机排障加的）
                NavItem("logs", "诊断日志"),
                NavItem("about", "关于"),
            ),
        ),
    )

    private lateinit var navList: FrameLayout
    private lateinit var pageTitle: TextView
    private lateinit var body: LinearLayout
    private lateinit var navScroll: ScrollView
    private lateinit var bodyScroll: ScrollView

    /**
     * 当前写入目标（画板 s07 / s08 / s11 / s25 是**两栏**页面）。
     *
     * 这些页面的正文不是「一条竖列」而是左右两栏，所以页面代码里通用的
     * 排版件（[section] / [switchRow] / [note] …）必须能写进「某一栏」。
     * 六十X轮的做法：加一个**可切换的宿主**——默认 `null` 表示写整页，
     * [inColumn] 期间指向某一栏。这样 39 处 `addView` 一行不改就都跟着走，
     * 也不会出现「两栏页面里某个件仍然按整页宽度排版」的半截状态。
     */
    private var columnHost: LinearLayout? = null

    /** 排版件实际写入的容器（见 [columnHost]）。 */
    private val hostCol: LinearLayout get() = columnHost ?: body

    /** 左栏条目（key → 视图），用于选中态刷新。 */
    private val navRows = LinkedHashMap<String, TextView>()

    /**
     * 左栏条目**当前显示**的颜色（颜色渐变的起点）。
     *
     * ## 为什么记「显示出来的颜色」而不是「选中 / 未选中」
     *
     * 颜色要**渐变**过去，动画就必须从屏幕上真实的颜色出发。
     * 记状态的话，动画起点永远是两个固定值 ——
     * 中途被打断（连续点两页）就会跳回起点，看着像「闪了一下」。
     *
     * @param text 字色
     * @param fill 底色
     * @param stroke 描边色
     */
    private class NavRowPaint(var text: Int, var fill: Int, var stroke: Int)

    /** 左栏条目当前显示的颜色（key = 导航 key）。 */
    private val navRowPaints = LinkedHashMap<String, NavRowPaint>()

    /**
     * 分段项当前显示的颜色（颜色渐变的起点）。
     *
     * 理由同 [NavRowPaint]：记「显示出来的颜色」，动画才接得上。
     *
     * @param text 字色
     * @param fill 底色（选中 = 强调色实底，未选中 = 透明）
     */
    private class SegmentPaint(var text: Int, var fill: Int, var stroke: Int)

    /** 分段项当前显示的颜色。 */
    private val segmentPaints = WeakHashMap<TextView, SegmentPaint>()

    /**
     * 卡片选项的单选圆点（key = 卡片标题）。
     *
     * 选中之后这一页会**整页重建**（[select]），
     * 重建之前播的动画会被连着视图一起摘掉 —— 根本看不见。
     * 所以圆点先登记在这里，重建完再补那一下「鼓一下」。
     */
    private val cardRadios = LinkedHashMap<String, TextView>()

    /** 待「鼓一下」的卡片圆点（卡片标题）。 */
    private var pendingRadioBump: String? = null

    // ---------------------------------------------------------- ADB 调试页（第五十五轮）

    /** ADB 页最近一次操作的结论（切走再切回来还在）。 */
    private var adbLastAction: String = "打开本页只检查状态；点下方按钮才会更改调试设置。"

    /** ADB 页的诊断文本（切走再切回来还在）。 */
    private var adbDiagnostics: String = ""

    /** ADB 页的诊断详情是否展开（默认收起，别把设置页糊成字墙）。 */
    private var adbDiagnosticsExpanded: Boolean = false

    /** ADB 页的结果回调：后台操作结束后用它原地刷新，不重建页面。 */
    private var adbOnResult: ((AdbOutcome) -> Unit)? = null

    // 壳层尺寸在 [build] 里按画板现算（两块玻璃面板铺满 1920×1080）。
    private var panelWidth = 1872
    private var panelHeight = 992
    private var leftWidth = NAV_W

    /** 右侧内容玻璃面板（`SettingsSeeThroughTest` 等直接查这块面板的玻璃背景）。 */
    private var panelView: View? = null

    /**
     * 左右两块玻璃面板（左导航栏 + 右内容区）的统一动画容器。
     *
     * 之前 `playOpen` / `playClose` 只对右侧 `contentPanel` 做动画，
     * 导致关闭时右侧设置页先滑出淡出、左侧导航栏原地定住 240ms 再突然消失，体感割裂卡顿。
     * 统一包进透明容器后，两块面板走同一套硬件层动画同步进退。
     */
    private var panesContainer: View? = null

    /** 遮罩（动画用：打开淡入、关闭淡出）。 */
    private var scrimView: View? = null

    /**
     * 播放**打开**动画（左右两块面板同步上滑 + 淡入）。
     */
    fun playOpen() {
        OverlayAnim.open(panesContainer ?: panelView, scrimView)
    }

    /**
     * 播放**关闭**动画（左右两块面板同步下滑 + 淡出）。
     *
     * @param onEnd 动画结束后回调（一般是把这层从窗口摘掉）
     */
    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panesContainer ?: panelView, scrimView, resetOnEnd = false, onEnd = onEnd)
    }

    init {
        build()
    }

    // ------------------------------------------------------------- 骨架

    /**
     * 壳层：**两块 L2 玻璃浮层**（左分组导航 + 右内容区）。
     *
     * 设计稿 s01 的坐标（1920×1080 设计 px）：
     *   导航 x=24..396（宽 372）、内容 x=420..1896（宽 1476）、两者 y=64..1056（高 992）。
     * 这里按 [Design.W] / [Design.H] 算外边距，不写死 1920：
     * 1920×1080 下与画板逐像素对齐，compact（手机档）时整体收紧。
     *
     * 取舍：**保留桌面透出**（第124轮定下的要求），
     * 所以暗罩只压 26%，模糊感全部交给两块面板自己的 [GlassDrawable]。
     */
    private fun build() {
        // 暗罩 26%（画板口径）。
        //
        // 为什么不再用 [OverlayBackdrop]：它自己会画一层 scrim（seeThrough 时还要再乘
        // `THROUGH_SCRIM_SCALE`），叠在这层 26% 上会把桌面压成死色。
        //
        // 为什么不新增色值：取当前主题 `scrim` 的 RGB（日间浅灰 / 夜间深墨蓝）、
        // 只把 alpha 定成 [SHELL_SCRIM_ALPHA]（66 ≈ 26%）——
        // 于是「暗罩 26%」成立，又不会凭空多出一个硬编码颜色，夜间自动跟着走。
        // 画板 `s01-boot` 的面板外不是「桌面被压暗」，而是**和首页同一张背景**
        // （底色渐变 + 右上青 / 左下蓝两处柔光）。所以这里直接铺一层 [BackgroundView]：
        // 它画的就是 L0（壁纸 + 设计底色 + 柔光），面板玻璃也从同一张模糊底上取样，
        // 观感与画板一致，且不再依赖「把桌面压暗 26%」这种近似。
        val scrim = View(context).apply {
            setBackgroundColor((pal.scrim and 0x00FFFFFF) or (SHELL_SCRIM_ALPHA shl 24))
            isClickable = true
            setOnClickListener { onClose?.invoke() }
        }
        scrimView = scrim
        // 铺满整屏：设置层被插在**状态栏之前**（见 `LauncherView.showSettings`），
        // 状态栏压在它上面，所以这里不需要再给状态栏留出 76px 的缺口 ——
        // 留了反而会在顶部露出一条没被 L0 覆盖的桌面。
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 面板的柔阴影是 [GlassDrawable] 画在**面板边界之外**的 4 圈，
        // 默认 `clipChildren = true` 会把它们裁掉 —— 于是「L2 浮层」看起来像贴在屏幕上的一张纸。
        // 只放这一层：面板内部（正文滚动区）仍各自裁剪，不会被内容画到外面。
        clipChildren = false

        val profile = com.cardesk.launcher.core.Platforms.detect(context, prefs.platformOverride)
        val compact = profile.compactUi
        val screenW = Design.W.toInt()
        val screenH = Design.H.toInt()
        val side = if (compact) 16 else SHELL_SIDE
        val top = if (compact) 16 else SHELL_TOP
        val bottom = if (compact) 16 else SHELL_BOTTOM
        val gap = if (compact) 16 else SHELL_GAP
        leftWidth = if (compact) 300 else NAV_W
        panelWidth = screenW - side * 2
        // 第三百零七轮：把「被全局 Dock 窗口吃掉的那一条」从面板高度里减掉，
        // 否则设置页最后两行（ADB 调试 / 诊断日志）落在盖住区里，看不见也点不到。
        panelHeight = (screenH - top - bottom - bottomReserve.coerceIn(0f, screenH / 3f)).toInt()
        val contentX = side + leftWidth + gap
        val contentW = panelWidth - leftWidth - gap

        // 两块面板共用一套材质：浮层圆角 28 + 1px GLASS_STROKE。
        // fill 用 `pal.surface`（比卡片更实的一档），所以它压在模糊背景上是「玻璃」，
        // 压在自己身上不会像卡片那样被看穿。
        fun pane(): FrameLayout = FrameLayout(context).apply {
            background = GlassDrawable(
                PANEL_RADIUS,
                pal.surfaceStrong,
                Skin.GLASS_STROKE,
                true,
                true,
            )
            // 吞掉落在面板空白处的点击：否则会穿透到下面的桌面
            // （scrim 的点击关闭只在面板之外成立）。
            isClickable = true
            setOnClickListener { }
        }

        val navPanel = pane()
        val contentPanel = pane()
        panelView = contentPanel
        val panes = FrameLayout(context).apply {
            // 保留两块面板边界外的柔阴影不被裁切；自身不设点击，让间隙与留白点击穿透到 scrimView 关闭。
            clipChildren = false
        }
        panesContainer = panes
        panes.addView(navPanel, LayoutParams(leftWidth, panelHeight).also {
            it.leftMargin = side; it.topMargin = top
        })
        panes.addView(contentPanel, LayoutParams(contentW, panelHeight).also {
            it.leftMargin = contentX; it.topMargin = top
        })
        addView(panes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- 左栏：分组导航 ------------------------------------------------
        //
        // 画板左栏**没有品牌标题**（只有分组树），所以 34px 的「桌面设置」已删掉；
        // 分组树从面板顶 24px 直接开始。
        navScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            // 5 组 26 条按用户要求放松行距后，一屏放不下 —— 底部做 48px 渐隐，
            // 让人一眼看出「下面还有」，而不是以为树就到这里（第247轮）。
            isVerticalFadingEdgeEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        navList = FrameLayout(context)
        navScroll.addView(navList, ViewGroup.LayoutParams(-1, -2))
        // 画板左栏底部**没有**任何附加行（VIN + 「开源与声明」那一行本轮已删），
        // 分组树直接用满面板高度，只留 24px 呼吸。
        val navTreeH = panelHeight - TREE_TOP - SHELL_SIDE
        navScroll.isVerticalScrollBarEnabled = false
        navPanel.addView(navScroll, abs(0, TREE_TOP, leftWidth, navTreeH))

        // 两块面板之间靠 24px 空隙分隔（画板就是两块独立玻璃），
        // 不再画 1px 竖线 —— 那根线是「一张整卡切成两半」的旧形态。
        // ---- 右栏：内容区 --------------------------------------------------
        //
        // 设计稿：页标题 28px 粗体，左边距 30、字形顶在面板内 y≈33；
        // 关闭是右上角一个 56×56 的白色圆角方块 + 「×」，不是文字「关闭」胶囊。
        pageTitle = Ui.text(context, "桌面设置", TITLE_SIZE, pal.text, bold = true)
        contentPanel.addView(pageTitle, abs(CONTENT_PAD, 22, contentW - CONTENT_PAD * 2 - 72, 40))

        val close = Ui.text(context, "×", 30f, pal.text).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Skin.surfaceStrong, ROW_RADIUS, 1f, Skin.GLASS_STROKE)
            // 按压反馈：关闭是唯一的「退出」动作，按下去要有回应。
            Motion.press(this)
            setOnClickListener { onClose?.invoke() }
        }
        contentPanel.addView(close, FrameLayout.LayoutParams(56, 56, Gravity.TOP or Gravity.END).also {
            it.rightMargin = 24; it.topMargin = 24
        })

        bodyScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CONTENT_PAD, 0, CONTENT_PAD, 24)
        }
        bodyScroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        contentPanel.addView(
            bodyScroll,
            FrameLayout.LayoutParams(contentW, panelHeight - BODY_TOP - SHELL_SIDE).also {
                it.topMargin = BODY_TOP
            },
        )

        buildNavTree()
    }

    /** FrameLayout 绝对定位（相对父容器左上角）。 */
    private fun abs(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    /**
     * 重建左侧导航树，并选中当前页。
     *
     * ## 为什么叫 `buildNavTree` 而不是 `switchTab`
     *
     * 以前这里还有个「全局 / 系统」两个 tab 的概念（`globalGroups` / `systemGroups` +
     * 一条计数徽标）。第四十九轮起那条切换条就**不画了**，左栏改成单棵树，
     * 但那两份 list 和两个计数 View 一直留着 —— 它们从来没被 `addView` 进视图树，
     * 是**纯死代码**：每次打开设置页都会白白构造一遍。
     *
     * 第149轮把它们连同 `tabView()` / `paintTab()` 一起删掉。
     */
    private fun buildNavTree() {
        val groups = mergedGroups
        navList.removeAllViews()
        navRows.clear()
        // 设计板 s05 的树坐标：y 起点 100，组标题后 +28，每个条目 +34，每组结束 +10。
        //
        // 第六十一轮按用户要求「左边太紧凑」把节奏放松：
        //   - 条目行距 34 → [TREE_ITEM_STEP]（行高 [TREE_ITEM_H] + 间隙）；
        //   - 组标题 → 首条目 28 → [TREE_GROUP_STEP]（分组因此一眼能分开）；
        //   - 组尾 → 下一组组标题 10 → [TREE_GROUP_END]。
        val itemW = (leftWidth - NAV_PAD * 2).coerceAtLeast(180)
        var y = TREE_TOP
        for (group in groups) {
            val title = Ui.text(context, group.title, NAV_LABEL, pal.accent, bold = true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.16f
            }
            navList.addView(title, abs(TREE_GROUP_X, y - TREE_TOP, itemW, 30))
            y += TREE_GROUP_STEP
            for (item in group.items) {
                val row = Ui.text(context, item.label, NAV_ITEM, pal.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(12, 0, 12, 0)
                    // 背景**始终存在**（未选中时是透明底 + 透明描边，画出来等于没有）：
                    // 选中态要渐变过去，就需要一个能改颜色的 drawable 可改。
                    // 圆角 [PILL]：画板的选中态是整条胶囊，不是小圆角块。
                    background = Ui.round(TRANSPARENT, PILL, 1f, TRANSPARENT)
                    // 按压反馈：整行可点，用「行」的缩放幅度（整行缩 4% 太晃）。
                    Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
                    setOnClickListener { select(item.key) }
                }
                navList.addView(row, abs(TREE_ITEM_X, y - TREE_TOP, itemW, TREE_ITEM_H))
                navRows[item.key] = row
                // 初始一律按「未选中」记账 —— 下面的 select() 会把它渐变到真正的选中态。
                navRowPaints[item.key] = NavRowPaint(pal.textDim, TRANSPARENT, TRANSPARENT)
                y += TREE_ITEM_STEP
            }
            y += TREE_GROUP_END
        }
        // 子 View 全靠 topMargin 定位，按累加高度直接定高并留足底部呼吸空间，确保最后一项完整可滚可点。
        navList.layoutParams = (navList.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(-1, -2)).also {
            it.height = (y - TREE_TOP).toInt() + 16
        }
        navList.requestLayout()
        select(selectedKey.takeIf { key -> groups.any { g -> g.items.any { it.key == key } } } ?: groups.first().items.first().key)
    }

    /** 供外部（深链/预览测试）直接切到某一页。 */
    fun showPageKey(key: String) = select(key)

    /** 导入 / 删除自定义壁纸后重建「壁纸」页（预览与按钮状态要跟着变）。 */
    fun refreshWallpaperPage() {
        if (selectedKey == "wallpaper") select("wallpaper")
    }

    /**
     * 重建「车辆信息」页（第179轮）。
     *
     * 「车况卡视角」行的可见性取决于**当前车模编号对应的资产**与「写实车模」开关；
     * 用户在本页里改车模/开关时立刻重建，避免出现「已经切成纯俯视车、这一行还挂着」。
     */
    fun refreshCarInfoPage() {
        if (selectedKey == "carinfo") select("carinfo")
    }

    private fun select(key: String) {
        selectedKey = key
        for ((k, row) in navRows) {
            paintNavRow(k, row, k == key)
        }
        // 「启动后」只属于「启动切换」页
        // 「开源与声明」不在导航组里，标题固定
        // 第149轮：合并掉的那几个 key 仍然**保留路由**（外部深链 / 旧快捷入口
        // 可能还在用它们），但它们已经不在导航树里了 —— 所以标题要单独给，
        // 否则深链进来会看到一片空白标题。
        val label = when (key) {
            "about" -> "关于"
            "autostart" -> "启动与开机"
            "cardstyle" -> "桌面卡片"
            "desktoplyric", "clusterlyric" -> "歌词"
            "quickapps" -> "Dock 与导航栏"
            else -> mergedGroups.flatMap { it.items }.firstOrNull { it.key == key }?.label ?: ""
        }
        pageTitle.text = label
        // 离开上一页：把这一页挂的监听 / 定时器摘掉
        // （第七十轮：氛围灯页订阅了实时频谱）
        pageTeardown?.invoke()
        pageTeardown = null
        body.removeAllViews()
        // 媒体源浮层挂在设置层自己身上（不是 body），换页时也要摘掉，
        // 否则切来切去会一层层叠着隐藏的旧浮层。
        removeMediaPopupLayer()
        // 卡片圆点跟着这一页一起换（新页会重新登记）——
        // 不清的话会拿着上一页的旧视图去「鼓」。
        cardRadios.clear()
        bodyScroll.scrollTo(0, 0)
        // 离开 ADB 页就把结果回调摘掉：后台操作回来时不该再去刷新已经不存在的视图
        if (key != "adb") adbOnResult = null
        // 排障场景：用户进了哪一页 —— 记录页面 key 与中文名
        Logx.event(LogTags.SETTING, "进入设置页", "page" to key, "name" to label)
        when (key) {
            "boot" -> pageBoot()
            "keys" -> pageKeys()
            "cluster" -> pageCluster()
            "tasks" -> pageTasks()
            "autostart" -> pageBoot()
            "daynight" -> pageDayNight()
            "wallpaper" -> pageWallpaper()
            "cardstyle" -> pageCards()
            "cards" -> pageCards()
            "dualpip" -> pageDualPip()
            "lan" -> pageLan()
            "apkstore" -> pageApkStore()
            "update" -> pageUpdate()
            "p360" -> pageCamera360()
            "autostop" -> pageAutostop()
            "hotspot" -> pageHotspot()
            "adb" -> pageAdb()
            "desktoplyric" -> pageLyric()
            "clusterlyric" -> pageLyric()
            "carinfo" -> pageCarInfo()
            "dock" -> pageDock()
            "quickapps" -> pageDock()
            "oemsettings" -> pageOemSettings()
            "native" -> pageNativeSettings()
            "lyric" -> pageLyric()
            "media" -> pageMedia()
            "diagnostics" -> pageDiagnostics()
            "restart" -> pageRestart()
            "logs" -> pageLogs()
            "ambient" -> pageAmbient()
            "about" -> pageAbout()
        }
        // 这一页建好了：补上刚才那次「圆点鼓一下」。
        //
        // 必须在**重建之后**才补 —— 重建之前播的动画会跟着旧视图一起被摘掉，
        // 用户根本看不见（这是整页重建带来的坑，见 [pendingRadioBump]）。
        pendingRadioBump?.let { title ->
            pendingRadioBump = null
            cardRadios[title]?.let { Motion.bump(it) }
        }
    }

    /**
     * 左栏条目的选中态：**颜色渐变**到目标值。
     *
     * ## 为什么改成渐变
     *
     * 之前是硬切（字色、软底、描边一瞬间换掉）。
     * 左栏在车机屏最左边，驾驶员只是**瞥一眼** ——
     * 硬切很容易漏看「现在在哪一页」，尤其切页时眼睛还在正文上。
     * 现在三个颜色都从**当前显示的颜色**渐变过去，
     * 选中位置的变化才真的被看见。
     *
     * @param active 这一条是不是当前页
     */
    private fun paintNavRow(key: String, row: TextView, active: Boolean) {
        val targetText = if (active) pal.accent else pal.textDim
        // 画板的选中态是 **accent 淡底 + accent 字、无描边**（一整条胶囊），
        // 所以描边目标值恒为透明 —— 渐变链路保留，只是不再有那圈 accent 边。
        val targetFill = if (active) pal.accentSoft else TRANSPARENT
        val targetStroke = TRANSPARENT
        val bg = row.background as? GradientDrawable
        if (bg == null) {
            // 兜底：背景不是我们建的那个 drawable（正常不会发生），退回硬切。
            row.setTextColor(targetText)
            row.background = if (active) {
                Ui.round(pal.accentSoft, PILL, 1f, TRANSPARENT)
            } else {
                null
            }
            return
        }
        val state = navRowPaints[key]
            ?: NavRowPaint(targetText, targetFill, targetStroke).also { navRowPaints[key] = it }
        val fromText = state.text
        val fromFill = state.fill
        val fromStroke = state.stroke
        if (fromText == targetText && fromFill == targetFill && fromStroke == targetStroke) {
            // 已经是这个状态了：直接落位。
            // 每次改完设置都会 select(selectedKey) 重建页面，
            // 不做这一步的话每次重建都要空跑一遍动画。
            row.setTextColor(targetText)
            bg.setColor(targetFill)
            bg.setStroke(1, targetStroke)
            return
        }
        Motion.animateProgress(row, MotionSpec.STATE_BUMP_DURATION) { t ->
            // 每帧把「当前显示的颜色」写回状态里 ——
            // 中途被下一次选中打断时，动画要从**真正显示着的颜色**接着走。
            val text = MotionSpec.lerpColor(fromText, targetText, t)
            val fill = MotionSpec.lerpColor(fromFill, targetFill, t)
            val stroke = MotionSpec.lerpColor(fromStroke, targetStroke, t)
            state.text = text
            state.fill = fill
            state.stroke = stroke
            row.setTextColor(text)
            bg.setColor(fill)
            bg.setStroke(1, stroke)
        }
    }

    // ------------------------------------------------------------- 控件库

    /**
     * 一个分区的标题。
     *
     * ## 第149轮的 UI 优化
     *
     * 以前是 `22f textFaint` + `topMargin 8` —— 在 1920 宽的车机屏上，
     * 这个字号配上最弱的那档颜色，**几乎看不见**。
     *
     * 合并页面之后一个页面里会有七八个分区（歌词页就有九个），
     * 分区标题必须一眼能读出来，否则用户根本不知道「下面这一堆是什么」。
     *
     * 所以改成：
     * - 字号 22 → **24**，颜色 `textFaint` → **`textDim`**，并且**加粗**；
     * - 上方留白 8 → **20**（分区之间要能分开）；
     * - 下面加一条 **1px 细线** —— 标题和它管的那一堆设置项之间要有视觉边界。
     */
    private fun section(title: String) {
        // 画板的组标题是 **14px 弱字 + 略松的字距**，下面**没有横线** ——
        // 分组靠「组间 16px 留白 + 行自己的分割线」区分，
        // 多画一根线会在组标题和首行之间挤出一条让人误以为是输入框的空白带。
        hostCol.addView(
            Ui.text(context, title, GROUP_LABEL, pal.textDim).apply { letterSpacing = 0.06f },
            LinearLayout.LayoutParams(-1, GROUP_LABEL_H).also { it.topMargin = GROUP_GAP },
        )
    }

    /** 行底 1px 分割线（全库统一走 `Skin.GLASS_STROKE`，夜间自动跟着走）。 */
    private fun hairline() {
        hostCol.addView(
            View(context).apply { setBackgroundColor(Skin.GLASS_STROKE) },
            LinearLayout.LayoutParams(-1, 1),
        )
    }

    /**
     * 一行说明文字。
     *
     * **返回这个 TextView**：有些说明会跟着用户的选择变
     * （比如第117轮的「歌词布局」—— 切到 KTV 两行时，
     * 说明要从「上一句 / 当前句 / 下一句」改成「当前句在上、下一句在下」）。
     * 那种地方要能**就地改文案**，不能只在建页时写一次。
     */
    private fun note(text: String): TextView {
        // 第150轮：说明行打上标记，测试才能从视图树里把它挑出来
        // （见 [SettingsCopyTest]）—— 精简文案后要能盯住「别又加回来」。
        val tv = Ui.text(context, text, 16f, pal.textDim).apply {
            setLineSpacing(8f, 1f)
            tag = NOTE_MARK
        }
        hostCol.addView(tv, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
        return tv
    }

    /**
     * 一行**原始数据**（不是「说明文字」）—— **不打** [NOTE_MARK]。
     *
     * ## 第221轮：为什么日志预览不该打说明文字的标记
     *
     * [NOTE_MARK] 的含义是「这是一行**说明文案**，要对着白名单核」
     * （第150轮把设置页里的啰嗦文案删干净后立的规矩）。
     *
     * 而「最近日志」那一段显示的是**日志原文**——它是数据，不是文案：
     *
     * - 它天然会随设备变（有多少行、什么内容）；
     * - 它里面**可能包含**被列进 `FORBIDDEN` 的词
     *   （比如某条日志里写了「开机后进入本桌面」）⇒
     *   把它算进「说明文字」会让 [SettingsCopyTest] 误报；
     * - 真机上它一直显示的就是 40 行日志（不是「(暂无日志)」），
     *   只是 Robolectric 里日志目录是空的，才一直显示占位符。
     *
     * 所以：**日志预览走 [noteRaw]，不进文案白名单**。
     */
    private fun noteRaw(text: String): TextView {
        val tv = Ui.text(context, text, 16f, pal.textDim).apply {
            setLineSpacing(8f, 1f)
        }
        hostCol.addView(tv, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
        return tv
    }

    /**
     * 统一「行」组件：**标题 [ROW_TITLE] 粗体 + 说明 [ROW_SUB] 弱字 + 右侧控件**。
     *
     * 对齐画板 s01 的行：块高 [ROW_H]（行底一条 1px `Skin.GLASS_STROKE`），
     * 左右内边距由 body 的 [CONTENT_PAD] 给，控件**垂直居中**在行里。
     *
     * 取舍：
     * - 行**不再自带卡片底**，扁平地压在玻璃面板上、靠分割线分隔 ——
     *   画板上所有行都是这个形态，没有「一张卡包住几行」的那种块；
     * - 标题 28 → [ROW_TITLE]，副标题 19 → [ROW_SUB]：
     *   28px 的行标题在 128px 的行里太满，和画板的 18/14 差了一档；
     * - 右侧控件从「顶 +14」改成垂直居中，跟文字基线对齐。
     */
    private fun row(
        control: View,
        label: String,
        subtitle: String? = null,
        height: Int = ROW_H,
        onRowClick: (() -> Unit)? = null,
    ) {
        val holder = FrameLayout(context)
        if (onRowClick != null) {
            holder.isClickable = true
            Motion.press(holder, MotionSpec.PRESS_SCALE_ROW)
            holder.setOnClickListener { onRowClick() }
        }
        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textBox.addView(
            Ui.text(context, label, ROW_TITLE, pal.text, bold = true),
            LinearLayout.LayoutParams(-1, -2),
        )
        if (!subtitle.isNullOrEmpty()) {
            textBox.addView(
                // 第150轮：副标题打上标记，测试才能把它从视图树里挑出来
                // （见 [SettingsCopyTest]）—— 精简文案后要能盯住「别又加回来」。
                Ui.text(context, subtitle, ROW_SUB, pal.textDim).apply { tag = SUBTITLE_MARK },
                LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
            )
        }
        holder.addView(
            textBox,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START).also {
                // 给右侧控件让出位置，长标题不会被开关压住
                it.rightMargin = ROW_CONTROL_GAP
            },
        )
        holder.addView(
            control,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END),
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, height))
        hairline()
    }

    /**
     * 一行「标签 + 右侧取值」（紧凑档，[KV_ROW_H]）。
     *
     * 画板 s20「当前状态」/ s23「系统能力」都是这一档：行高 ~47、
     * 标签弱字、取值右对齐（可用/正常时用 `OK`）。
     * 它不是「设置行」，所以比 [row] 矮一半多 —— 页面上十行状态也不会糊成一团。
     *
     * @param valueColor 指定取值颜色；不传时按 [ok] 走（绿 / 弱文字）。
     *
     * @return 取值那个 TextView（第五十五轮：ADB 页要在操作后**原地刷新**取值，
     *         所以这里把视图交出去，而不是只往 body 里塞。
     */
    private fun kvRow(label: String, value: String, ok: Boolean, valueColor: Int? = null): TextView {
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, label, 16f, pal.textDim).apply { gravity = Gravity.CENTER_VERTICAL },
            FrameLayout.LayoutParams(-1, -1),
        )
        val readout = Ui.text(context, value, 16f, valueColor ?: if (ok) pal.ok else Skin.TEXT_SOFT).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        holder.addView(
            readout,
            FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_VERTICAL or Gravity.END),
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, KV_ROW_H))
        hairline()
        return readout
    }

    private fun switchRow(
        label: String,
        initial: Boolean,
        subtitle: String? = null,
        height: Int = ROW_H,
        onChange: (Boolean) -> Unit,
    ) {
        val toggle = BoardSwitch(context)
        toggle.tint = pal.accent
        toggle.checked = initial
        toggle.setOnClickListener {
            toggle.toggle()
            // 状态变了要**看得见**：开关自己的圆点会滑过去，
            // 这里再补一下形变确认 —— 余光里也能察觉「这一下真的生效了」。
            Motion.bump(toggle)
            onChange(toggle.checked)
            onApply?.invoke()
        }
        // 整行点击也可触发开关切换，避免车载触屏仅点 64×36 滑块难以命中。
        row(toggle, label, subtitle, height, onRowClick = { toggle.performClick() })
    }

    /** 一行选择：左侧标题 + 右侧横向滚动的选项条（选项多时也能滚）。 */
    /** 按**下标**取值的选择行（options 是文案，selected 是下标）。 */
    private fun chipRow(
        label: String,
        options: List<String>,
        selected: Int,
        compact: Boolean = false,
        height: Int = ROW_H,
        chipH: Int = 0,
        subtitle: String? = null,
        subtitleMarked: Boolean = true,
        onPick: (Int) -> Unit,
    ) {
        chipRowCore<Int>(label, options, selected, { it }, compact, onPick, subtitle, height, subtitleMarked, chipH)
    }

    /** 按**字符串**取值的选择行（如车型编号 `01` / `02HB` / `03+`，不是下标）。 */
    private fun chipRow(
        label: String,
        options: List<String>,
        selected: String,
        compact: Boolean = false,
        height: Int = ROW_H,
        chipH: Int = 0,
        subtitle: String? = null,
        subtitleMarked: Boolean = true,
        onPick: (String) -> Unit,
    ) {
        chipRowCore<String>(label, options, options.indexOf(selected), { options[it] }, compact, onPick, subtitle, height, subtitleMarked, chipH)
    }

    /**
     * @param compact 收紧每个选项的内边距（22 → 16、间距 6 → 4）。
     *
     * 选项多的一行（氛围灯的 6 个预设）在 1920 宽下会超出可视区一点，
     * 最后一个 chip 被裁掉半截 —— 它不是看不见，是**看起来像被截断**，
     * 比真的看不见更糟。收紧一点正好放下；其它行保持原样。
     */
    private fun <T> chipRowCore(
        label: String,
        options: List<String>,
        selectedIndex: Int,
        valueOf: (Int) -> T,
        compact: Boolean = false,
        onPick: (T) -> Unit,
        subtitle: String? = null,
        height: Int = ROW_H,
        subtitleMarked: Boolean = true,
        chipH: Int = 0,
    ) {
        val holder = FrameLayout(context)
        val labelBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelBox.addView(
            Ui.text(context, label, ROW_TITLE, pal.text, bold = true),
            LinearLayout.LayoutParams(-1, -2),
        )
        if (!subtitle.isNullOrEmpty()) {
            labelBox.addView(
                // 副标题打标记（同 [row]）：测试要能把它从视图树里挑出来。
                // 但**画板新加**的副标题不在 SettingsCopyTest 的白名单里 ——
                // 那种传 subtitleMarked = false，别去冒充「语义 / 互斥」那几类。
                Ui.text(context, subtitle, ROW_SUB, pal.textDim).apply {
                    if (subtitleMarked) tag = SUBTITLE_MARK
                },
                LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
            )
        }
        // 没有标签的行（诊断日志的「日志级别」）**不能**再预留 300px 标签位：
        // 那一行 4 个胶囊本来放得下，被这 300px 一挤就溢出成横向滚动，最后两枚看着像被裁掉。
        val labelTaken = if (label.isEmpty() && subtitle.isNullOrEmpty()) 0 else CHIP_LABEL_W
        if (labelTaken > 0) {
            holder.addView(labelBox, FrameLayout.LayoutParams(labelTaken, -1, Gravity.CENTER_VERTICAL))
        }
        // 画板的 chips **一律右对齐到正文右内边**（s01/s02/s05/s06/s11/s25 都是这个形态）。
        //
        // 以前把 seg 直接塞进 HorizontalScrollView，内容从滚动区左边开始排 ——
        // 于是 3 个选项的一行也贴着左边界，和画板差了整整 800px。
        // 现在：fillViewport 让内容窄时把子容器撑到视口宽，
        // 子容器的 gravity=END 再把胶囊推到右边；内容超宽时照旧可拖动，不被裁。
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        val seg = buildSegment(options, selectedIndex, compact, chipH) { index ->
            onPick(valueOf(index))
            onApply?.invoke()
        }
        val segBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        segBox.addView(seg, LinearLayout.LayoutParams(-2, -2))
        scroll.addView(segBox, ViewGroup.LayoutParams(-1, -2))
        // 第125轮：**初始就滚到选中项**。
        //
        // 「默认媒体源」有 9 个选项、选中的是第 7 个（通用音乐）——
        // 不滚过去的话，打开设置页看到的是 QQ音乐/网易云一排**没高亮**的 chip，
        // 根本看不出当前选的是哪个。氛围灯那几行选中项在第 0 个，不受影响。
        if (selectedIndex > 0) {
            scroll.post {
                val child = seg.getChildAt(selectedIndex) ?: return@post
                val target = (child.left - scroll.width / 2 + child.width / 2).coerceAtLeast(0)
                scroll.scrollTo(target, 0)
            }
        }
        // 宽度必须占满「标签右侧的剩余空间」（-1 + 左外边距），
        // 否则 wrap_content 的内容会溢出并压在标签上
        holder.addView(
            scroll,
            FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.END).also {
                it.leftMargin = labelTaken
                // 画板 s01 的小胶囊右端在 1838（正文右内边 1866 再缩 28），
                // 其它页的分段/胶囊是贴 1866 —— 只有 chipH>0 的那一类要额外缩。
                if (chipH > 0) it.rightMargin = 28
            },
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, height))
        hairline()
    }

    /**
     * 分段控件（设计板 `.seg`）：**容器透明**，每个选项自己是一颗胶囊。
     *
     * 画板 s07「轮播间隔」/ s23「平台判定」都是这个形态：
     * 未选中 = 一层软白（[Skin.surfaceSoft]）+ 无描边，选中 = accent 12% 底 + accent 描边。
     * 以前是一个 surfaceSoft 大圆角容器把选项包起来 —— 那会在玻璃上再糊一层灰底。
     */
    private fun buildSegment(
        options: List<String>,
        selectedIndex: Int,
        compact: Boolean = false,
        chipH: Int = 0,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 容器**不画底**：选项之间留 8px 空隙，靠各自的胶囊分界。
            background = Ui.round(TRANSPARENT, PILL, 0f, TRANSPARENT)
            // 第151轮：打标记，测试要能断言「哪一页还在用 chip 行」。
            tag = CHIP_SEG_MARK
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { index, text ->
            val padX = if (chipH > 0) 12 else if (compact) 18 else 24
            val item = Ui.text(context, text, if (chipH > 0) 14f else 16f, pal.textDim)
            item.apply {
                gravity = Gravity.CENTER
                // chipH > 0 = 画板 s01 那类**小胶囊**（34 高、52 宽），
                // 上下内边距按高度收紧；默认行仍旧 12。
                setPadding(padX, if (chipH > 0) 8 else 12, padX, if (chipH > 0) 8 else 12)
                // 背景**始终存在**（未选中时是软白，选中态渐变过去）：
                // 颜色渐变需要一个能改颜色的 drawable。
                background = Ui.round(Skin.surfaceSoft, PILL, 1f, TRANSPARENT)
                // 按压反馈：分段项是「互斥选择」，按下去必须知道点中的是哪一格。
                Motion.press(this)
            }
            item.setOnClickListener {
                views.forEachIndexed { i, v -> paintSegment(v, i == index) }
                // 刚被选中的那一项「鼓一下」——
                // 颜色渐变之外再给一次形变，选中就一定看得见。
                Motion.bump(item)
                onPick(index)
            }
            views.add(item)
            seg.addView(
                item,
                LinearLayout.LayoutParams(-2, -2).also { if (index > 0) it.leftMargin = 8 },
            )
        }
        // 初始态**直接落位**（不播动画）：这一页刚建出来，没有「旧颜色」可从。
        views.forEachIndexed { i, v -> paintSegment(v, i == selectedIndex, animate = false) }
        return seg
    }

    /**
     * 分段项两态（设计板 `.seg span`）：
     * - 选中：**accent 12% 底 + 1px accent 描边 + accent 字**；
     * - 未选中：软白底（[Skin.surfaceSoft]）+ 无描边 + 次级文字。
     *
     * ## 为什么改成渐变
     *
     * 分段控件是**互斥选择**：一次只亮一个，点完必须立刻知道「现在选的是谁」。
     * 之前是硬切（字色、底色一瞬间换掉）—— 驾驶员只是瞥一眼，很容易漏看。
     * 现在字色、底色、描边都从**当前显示的颜色**渐变过去，
     * 加上被选中项的「鼓一下」，选择就一定看得见。
     *
     * @param animate false = 直接落位（首次绘制用，没有旧颜色可从）
     */
    private fun paintSegment(seg: TextView, active: Boolean, animate: Boolean = true) {
        val targetText = if (active) pal.accent else pal.textDim
        val targetFill = if (active) pal.accentSoft else Skin.surfaceSoft
        val targetStroke = if (active) pal.accent else TRANSPARENT
        // 字重是**排版属性**，没法插值 —— 选中的那格直接加粗。
        seg.typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        val state = segmentPaints[seg]
            ?: SegmentPaint(targetText, targetFill, targetStroke).also { segmentPaints[seg] = it }
        val bg = seg.background as? GradientDrawable
        if (bg == null) {
            // 兜底：背景不是我们建的那个 drawable（正常不会发生），退回硬切。
            state.text = targetText
            state.fill = targetFill
            state.stroke = targetStroke
            seg.setTextColor(targetText)
            seg.background = Ui.round(targetFill, PILL, 1f, targetStroke)
            return
        }
        val fromText = state.text
        val fromFill = state.fill
        val fromStroke = state.stroke
        if (!animate || (fromText == targetText && fromFill == targetFill && fromStroke == targetStroke)) {
            state.text = targetText
            state.fill = targetFill
            state.stroke = targetStroke
            seg.setTextColor(targetText)
            bg.setColor(targetFill)
            bg.setStroke(1, targetStroke)
            return
        }
        Motion.animateProgress(seg, MotionSpec.STATE_BUMP_DURATION) { t ->
            // 每帧写回「当前显示的颜色」—— 被打断时才能从真正的画面接着走。
            val text = MotionSpec.lerpColor(fromText, targetText, t)
            val fill = MotionSpec.lerpColor(fromFill, targetFill, t)
            val stroke = MotionSpec.lerpColor(fromStroke, targetStroke, t)
            state.text = text
            state.fill = fill
            state.stroke = stroke
            seg.setTextColor(text)
            bg.setColor(fill)
            bg.setStroke(1, stroke)
        }
    }

    /** 一行选择器：左侧标题 + 右侧当前值（点开另一个面板）。 */
    private fun pickerRow(label: String, valueText: String, onClick: () -> Unit) {
        val value = Ui.text(context, valueText, 16f, pal.text).apply {
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            background = Ui.round(Skin.surfaceSoft, ROW_RADIUS, 1f, Skin.GLASS_STROKE)
            // 按压反馈：值胶囊是这一行**唯一**能点的地方，按下去要有回应。
            Motion.press(this)
            setOnClickListener { onClick() }
        }
        row(value, label)
    }

    /**
     * 一行「标签 + 当前值 + 下拉箭头」，点开后在**下方原地展开**一列选项。
     *
     * ## 为什么「默认媒体源」要改成下拉
     *
     * 那一行原来是一排 chip（分段控件）。三个问题：
     * 1. 9 个选项在 1920 宽下挤成两行还要横向滚动；
     * 2. 选中项（默认是「通用音乐」）在**中间**，得靠 `post` 把它滚到可视区才看得见；
     * 3. 打开设置页一眼看不出当前选的是谁 —— chip 行太宽，当前值淹没在中间。
     *
     * 改成一个下拉行：**收起时只占一行，当前值就写在行上**；展开时 9 个选项一次列全。
     *
     * ## 为什么是「原地展开」而不是 PopupWindow
     *
     * 设置页自己就是盖在桌面上的一层浮层，再往上盖一层浮层，层级和裁剪都要重新踩坑
     * （这个项目至今没用过 PopupWindow）。原地展开最稳 ——
     * 展开时下面的内容被推下去，设置页本来就能滚。
     *
     * ## 交互
     *
     * - 点行（标题或值）→ 展开 / 收起；箭头跟着转 180°；
     * - 点某个选项 → 选中、收起、回调 [onPick]；
     * - 选中项在列表里高亮（强调色底 + 强调色字 + 加粗）。
     *
     * @param selectedIndex 当前选中的下标；越界 = 没选中（显示「未选择」）。
     */
    /**
     * 媒体源选择浮层（画板 11，第245轮）。
     *
     * 画板 11 是一枚 **361×625 的紧凑浮层**，三处入口共用同一套版式：
     * 设置页 s05「默认媒体源」、首页音乐卡的来源行（弹在卡片**上方**）、
     * 导航页音乐卡的来源行（**盖在卡片上**）。
     *
     * 设置页原来是把 9 项在行下方**整宽展开**（横跨 1400px 的列表），
     * 和画板那枚窄浮层完全不是一回事 —— 这里改成贴着值胶囊弹出的 PopupWindow。
     */
    private fun mediaPopupRow(
        label: String,
        sources: List<com.cardesk.launcher.core.media.MediaSources.Source>,
        selectedIndex: Int,
        height: Int,
        onPick: (Int) -> Unit,
    ) {
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, label, ROW_TITLE, pal.text, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(CHIP_LABEL_W, -1),
        )

        val valueText = Ui.text(
            context,
            sources.getOrNull(selectedIndex)?.label ?: "未选择",
            16f,
            pal.text,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            tag = DROPDOWN_VALUE_MARK
        }
        val chevron = Ui.text(context, "⌄", 16f, pal.textDim).apply { gravity = Gravity.CENTER }
        val valueBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Skin.surfaceSoft, ROW_RADIUS, 1f, Skin.GLASS_STROKE)
            setPadding(24, 12, 20, 12)
            isClickable = true
        }
        valueBox.addView(valueText, LinearLayout.LayoutParams(-2, -2))
        valueBox.addView(chevron, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = 12 })
        holder.addView(
            valueBox,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END),
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, height))
        hairline()

        // ---- 浮层：挂在**设置层自己**身上（不是 PopupWindow）----
        //
        // 为什么不用 PopupWindow：① 它另起窗口，盖不住本层 Dock 的圆角；
        // ② 单元测试拿不到内容视图（PopupWindow 的内容不在 overlay 的视图树里）。
        // 挂在 overlay 上既能压过两块玻璃面板，也能被测试直接检索。
        // 版式与 `ui/SourceMenuLayer.kt` **逐项对齐**（画板 11 三处入口是同一枚浮层）：
        // 361×625、圆角 28、L2 玻璃、标题 13、行高 64、行文字 20、「当前」12 靠右。
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(
                radius = 28f,
                fill = pal.surfaceStrong,
                strokeColor = Skin.GLASS_STROKE,
                shadow = true,
                sheen = true,
            )
            isClickable = true
            // 与 `SourceMenuLayer` 一样是 8px 四周内边 —— 纵向 0 会让标题行高少 8px
            // （复核实测：标题中心距顶 21 vs 首页 29）。
            setPadding(8, 8, 8, 8)
            tag = MEDIA_POPUP_TAG
        }
        val layer = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hidePopup() }
        }
        layer.addView(panel, LayoutParams(MEDIA_POPUP_W, MEDIA_POPUP_H))
        // 页面每次重建都会重新走这里 —— 先把上一层的浮层摘掉，否则会一层层叠上去。
        removeMediaPopupLayer()
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        var current = selectedIndex
        val rows = ArrayList<FrameLayout>()
        val labels = ArrayList<TextView>()
        val tags = ArrayList<TextView>()

        panel.addView(
            Ui.text(context, "切换音乐来源", 13f, pal.textDim).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 0, 0, 0)
            },
            LinearLayout.LayoutParams(-1, MEDIA_POPUP_HEADER_H),
        )
        // 9 行 × 64 + 44 会超出 625 的内框 —— 首页那个浮层本来就是 ScrollView，
        // 这里也套一层，避免末行被裁（不复用 ScrollView 时实测裁 11px）。
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scroll.addView(
            listBox,
            ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun paint() {
            rows.forEachIndexed { i, row ->
                val active = i == current
                row.background = if (active) {
                    Ui.round(pal.withAlpha(pal.accent, 0x1F), 20f)
                } else {
                    null
                }
                tags[i].visibility = if (active) View.VISIBLE else View.GONE
            }
        }

        sources.forEachIndexed { index, src ->
            // 与 `ui/SourceMenuLayer.kt` 的 `buildRow()` **同一套几何**：
            // 行 64、品牌圆点 24（compound drawable）、名称左内边 18、「当前」右内边 16。
            val row = FrameLayout(context).apply { isClickable = true }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(src.brandColor)
                setSize(24, 24)
            }
            val name = Ui.text(context, src.label, 20f, pal.text).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 0, 0, 0)
                setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = 14
                tag = DROPDOWN_OPTION_MARK
                // 名称自己也接点击：无障碍聚焦会落在文字上，
                // 且测试/脚本按「文字可点」找入口时也找得到（行容器不可见文字）。
                isClickable = true
            }
            row.addView(name, FrameLayout.LayoutParams(-1, -1))
            val tagView = Ui.text(context, "当前", 12f, pal.accent).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                tagView,
                FrameLayout.LayoutParams(-2, -1, Gravity.END).also { it.rightMargin = 16 },
            )
            rows.add(row)
            labels.add(name)
            tags.add(tagView)
            row.setOnClickListener {
                current = index
                paint()
                valueText.text = src.label
                hidePopup()
                onPick(index)
                onApply?.invoke()
            }
            name.setOnClickListener { row.performClick() }
            listBox.addView(row, LinearLayout.LayoutParams(-1, MEDIA_POPUP_ROW_H))
        }

        paint()

        fun openAt(box: View) {
            layer.visibility = View.VISIBLE
            panel.post {
                val loc = IntArray(2)
                box.getLocationInWindow(loc)
                val self = IntArray(2)
                getLocationInWindow(self)
                val px = loc[0] - self[0]
                val py = loc[1] - self[1]
                // 右缘对齐值胶囊；越界收进屏幕内。
                val left = (px + box.width - MEDIA_POPUP_W)
                    .coerceIn(24, (width - 24 - MEDIA_POPUP_W).coerceAtLeast(24))
                // 优先弹在值胶囊**下方**；下方放不下时才沿屏幕内夹住 ——
                // 不能「放不下就翻到上面」，那样会盖掉状态栏（第一版实测顶到 y=25）。
                // 面板此刻可能还没量过（height=0），用设计高度的兜底值。
                val popupH = if (panel.height > 0) panel.height else 580
                val minTop = Design.STATUS_H.toInt() + 8
                val top = (py + box.height + 10)
                    .coerceAtMost(Design.H.toInt() - 24 - popupH)
                    .coerceAtLeast(minTop)
                (panel.layoutParams as LayoutParams).apply {
                    leftMargin = left
                    topMargin = top
                }
                panel.requestLayout()
            }
        }

        Motion.press(holder, MotionSpec.PRESS_SCALE_ROW)
        holder.setOnClickListener { openAt(valueBox) }
        Motion.press(valueBox)
        valueBox.setOnClickListener { openAt(valueBox) }
    }

    /** 关掉媒体源浮层（没有打开时什么也不做）。 */
    private fun hidePopup() {
        mediaPopupLayer()?.visibility = View.GONE
    }

    /** 找出当前这层浮层的容器（一个铺满的透明点击层 + 里面一枚面板）。 */
    private fun mediaPopupLayer(): FrameLayout? {
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? FrameLayout ?: continue
            if (c.childCount == 1 && c.getChildAt(0)?.tag == MEDIA_POPUP_TAG) return c
        }
        return null
    }

    /** 摘掉上一层的浮层（页面重建时调用）。 */
    private fun removeMediaPopupLayer() {
        while (true) {
            val layer = mediaPopupLayer() ?: return
            removeView(layer)
        }
    }

    private fun dropdownRow(
        label: String,
        options: List<String>,
        selectedIndex: Int,
        height: Int = ROW_H,
        onPick: (Int) -> Unit,
    ) {
        // —— 行：标题 + 右侧「当前值 ⌄」 ——
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, label, ROW_TITLE, pal.text, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            FrameLayout.LayoutParams(CHIP_LABEL_W, -1),
        )

        val valueText = Ui.text(context, options.getOrNull(selectedIndex) ?: "未选择", 16f, pal.text).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            // 第151轮：打标记，测试才能从视图树里把「当前值」挑出来
            // （见 [SettingsCopyTest]）。
            tag = DROPDOWN_VALUE_MARK
        }
        val chevron = Ui.text(context, "⌄", 16f, pal.textDim).apply { gravity = Gravity.CENTER }
        val valueBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 值胶囊与 [pickerRow] 同款：surfaceSoft 底 + 1px 玻璃描边 + 行圆角。
            background = Ui.round(Skin.surfaceSoft, ROW_RADIUS, 1f, Skin.GLASS_STROKE)
            setPadding(24, 12, 20, 12)
            isClickable = true
        }
        valueBox.addView(valueText, LinearLayout.LayoutParams(-2, -2))
        valueBox.addView(chevron, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = 12 })
        holder.addView(
            valueBox,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END),
        )

        hostCol.addView(holder, LinearLayout.LayoutParams(-1, height))
        hairline()

        // —— 展开面板：直接挂在 body 里、紧跟这一行 ——
        //
        // 收起时 GONE（不占空间），展开时 VISIBLE —— 下方的内容自然被推下去。
        // 这样不用在展开时挪 view 的位置，也就不会出现「展开后插错行」的坑。
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = Ui.round(Skin.surfaceSoft, ROW_RADIUS, 1f, Skin.GLASS_STROKE)
            setPadding(0, 6, 0, 6)
        }
        hostCol.addView(panel, LinearLayout.LayoutParams(-1, -2).also {
            // 第151轮：面板**底部**也要留间距 ——
            // 不留的话展开的列表会直接贴住下面那一行（跟 actionButton 那个毛病一样）。
            // GONE 时这个 margin 不生效，收起状态不占任何空间。
            it.bottomMargin = 20
        })

        // 当前选中项的画法（选中 = 强调色底 + 强调色字 + 加粗）。
        fun paintOption(item: TextView, active: Boolean) {
            item.setTextColor(if (active) pal.accent else pal.textDim)
            item.typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
            item.background = if (active) {
                // 选中 = accent 12% 底（画板口径），圆角跟行一致。
                Ui.round(pal.accentSoft, ROW_RADIUS)
            } else {
                null
            }
        }

        val optionViews = ArrayList<TextView>()
        options.forEach { text ->
            val item = Ui.text(context, text, 16f, pal.textDim).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 0, 24, 0)
                // 第151轮：打标记，测试才能从视图树里把「展开后的选项」挑出来。
                tag = DROPDOWN_OPTION_MARK
                isClickable = true
                // 按压反馈：选项是展开列表里唯一能点的东西。
                Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
            }
            optionViews.add(item)
            panel.addView(item, LinearLayout.LayoutParams(-1, 56))
        }

        var current = selectedIndex
        optionViews.forEachIndexed { i, v -> paintOption(v, i == current) }

        var expanded = false
        fun toggle() {
            expanded = !expanded
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            // 箭头转 180° —— **带着过程转**，不是一瞬间翻过去。
            //
            // 箭头是「这一列能不能展开」的唯一提示：
            // 一瞬间翻面的话，余光里只看到两个形状的切换，
            // 转过去才知道「它会动」。
            chevron.animate()
                .rotation(if (expanded) 180f else 0f)
                .setDuration(MotionSpec.KNOB_DURATION)
                .setInterpolator(Motion.STANDARD)
                .start()
            if (expanded) {
                // 展开后把选项带进可视区 ——
                // 这一行可能在正文底部，展开的列表整个落在屏幕外的话，
                // 用户会看到「点了没反应」。
                panel.post {
                    val y = panel.top
                    if (y + panel.height > bodyScroll.height) {
                        bodyScroll.scrollTo(0, (y - bodyScroll.height / 3).coerceAtLeast(0))
                    }
                }
            }
        }

        holder.isClickable = true
        // 按压反馈：这一行（标题 / 值）都能点，整行用「行」的缩放幅度。
        Motion.press(holder, MotionSpec.PRESS_SCALE_ROW)
        holder.setOnClickListener { toggle() }
        // 值盒自己也是可点的入口 —— 它比整行小，用标准幅度更清楚。
        Motion.press(valueBox)
        valueBox.setOnClickListener { toggle() }

        optionViews.forEachIndexed { index, item ->
            item.setOnClickListener {
                current = index
                optionViews.forEachIndexed { i, v -> paintOption(v, i == index) }
                valueText.text = options.getOrNull(index) ?: "未选择"
                if (expanded) toggle()
                onPick(index)
                onApply?.invoke()
            }
        }
    }

    /**
     * 设置页是桌面的**子 View**（`LauncherView.content` 里 `addView` 进去的），
     * 所以桌面的 `onInterceptTouchEvent` 对设置页里的控件**同样生效**：
     * 桌面把「单指横滑超过阈值」当成翻页手势，会把 MOVE 事件抢走。
     *
     * ## 这个 bug 最常见的表现
     *
     * 拖设置页里的滑杆时，滑杆卡住不动，**桌面反而翻页了** ——
     * 因为拖滑杆时手指必然横向移动，一越过阈值就被桌面抢走。
     *
     * ## 修法
     *
     * `requestDisallowInterceptTouchEvent(true)` 会把 `FLAG_DISALLOW_INTERCEPT`
     * 一路传到祖先；祖先的 `ViewGroup.dispatchTouchEvent` 看到这个标志位时，
     * **不会**再去调 `onInterceptTouchEvent` —— 设置页独占这次触摸。
     *
     * ## 踩过的坑：置位时机比「置不置位」重要得多
     *
     * 把 `android-all`（android-14）的 `ViewGroup` 反编译出来看，真相是这样的：
     *
     * ① `dispatchTouchEvent` 在 **ACTION_DOWN** 时会调 `resetTouchState()`，
     *    而 `resetTouchState()` 干的第一件事就是
     *    `mGroupFlags &= ~FLAG_DISALLOW_INTERCEPT` ——
     *    **每次新手势开始，祖先身上的「禁止拦截」标志都会被清掉**。
     *
     * ② 同一段字节码里，`disallowIntercept` 为真时 **不会**去调
     *    `onInterceptTouchEvent`（这正是我们要的效果）。
     *
     * ③ `resetTouchState()` 还会在手势**结束**（UP / CANCEL）时再调一次。
     *
     * 由此推出两条结论：
     *
     * - **「挂上去时置一次」是没用的**：标志会在下一次按下时被清掉，
     *   等于每次手势都没保护。（这一版就是这么写的，结果 bug 还在。）
     * - **「等滑杆回调了再置」也太晚**：`AbsSeekBar.onTouchEvent` 里，
     *   如果自己在一个 `ScrollView` 中（设置页的正文就在 `ScrollView` 里），
     *   按下时走的是 `isInScrollingContainer()` 分支，**不会**调 `startDrag`，
     *   要等**位移超过 touch slop 的第一个 MOVE** 才调 ——
     *   而那个 MOVE 恰恰就是桌面判定「横滑超阈值 → 抢事件」的那个 MOVE。
     *   桌面先抢，滑杆连 `onStartTrackingTouch` 都等不到。
     *   （手指划得快，一个 MOVE 位移就超过阈值，正是最容易复现的场景。）
     *
     * ## 正确做法：在 DOWN 派发**之后**立刻置位
     *
     * `super.dispatchTouchEvent(ev)` 返回时，这次事件已经派发完毕，
     * 而祖先的**下一个 MOVE 还没开始** —— 此时置位，第一个 MOVE 就已经被保护。
     *
     * 顺序也关键：必须在 `super` **之后**调。
     * `super` 内部在 ACTION_DOWN 时会先 `resetTouchState()` 清掉自己身上的标志；
     * 如果在 `super` **之前**调，`requestDisallowInterceptTouchEvent` 里
     * 「已经是这个状态就直接返回」的短路会让标志**传不上去**
     * （子节点还留着 → 认为重复调用 → 直接返回 → 祖先没被置位）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        requestDisallowInterceptTouchEvent(true)
        return handled
    }

    /**
     * 摘掉设置页时把标志撤掉 —— 否则关掉设置页后桌面就再也翻不了页。
     *
     * 正常情况下手势结束（UP）时 `ViewGroup.resetTouchState()` 也会清掉它，
     * 这里显式清一次是为了兜住「摘掉时还有手指按着」的情况。
     */
    override fun onDetachedFromWindow() {
        requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }

    /**
     * 一行滑块。
     *
     * @param step 步长（例如 30 表示 30 分钟一档）
     * @param format 自定义读数格式（例如把分钟数显示成 "06:00"）
     */
    private fun sliderRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        unit: String = "",
        step: Int = 1,
        format: ((Int) -> String)? = null,
        onChange: (Int) -> Unit,
    ) {
        // 用**水平 LinearLayout + 权重**而不是 FrameLayout 叠放。
        //
        // 叠放版把滑杆写死 420 宽、读数写死 130 宽（FrameLayout 右对齐），
        // 只有在单栏整宽（约 1476）下才放得下。s08 / s11 改成两栏后
        // 左栏只有 541 / 570 —— 420+146+130 = 696 > 栏宽，
        // 滑杆被顶到栏外，**压在标题文字上**（用户实测反馈）。
        // 权重方案让滑杆吃掉剩余宽度，任何栏宽都不会重叠。
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 滑杆行比普通行矮（96 vs 128）：滑杆自己就是一条「控制」，
        // 不需要普通行那么大的上下留白 —— 画板里滑杆行的密度也明显更紧。
        val caption = Ui.text(context, label, ROW_TITLE, pal.text, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val readout = Ui.text(context, format?.invoke(value) ?: "$value$unit", 16f, pal.text).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        /**
         * 读数「顶一下」。
         *
         * 拖动时数值是**连续**跳的 —— 每一跳都顶一下的话读数会一直抖，
         * 看着像坏了。所以上一次没顶完就不再顶：
         * 顶一次，歇一个 [MotionSpec.VALUE_DURATION]。
         */
        var nudging = false
        fun nudgeReadout() {
            if (nudging) return
            nudging = true
            Motion.nudge(readout)
            readout.postDelayed({ nudging = false }, MotionSpec.VALUE_DURATION)
        }
        // 标签 220 够放下「卡片透明度」这类 5 字标签；剩下的宽度都给滑杆
        // （原来给 300，s08 左栏 541 里滑杆只剩 150px，几乎拖不动）。
        holder.addView(caption, LinearLayout.LayoutParams(SLIDER_LABEL_W, -1))
        val bar = SeekBar(context).apply {
            this.max = ((max - min) / step).coerceAtLeast(0)
            progress = ((value - min) / step).coerceIn(0, this.max)
            progressTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            // 第245轮（用户反馈「滑杆看不见/压在字上」）：轨道底色要给出来。
            // 不给时 SeekBar 用系统默认灰，在近白面板上几乎看不见 ——
            // 圆角为 0 的那一档（进度 0）就只剩一个白点悬在空白里。
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    (pal.text and 0x00FFFFFF) or (0x24 shl 24),
                )
            // 滑块：强调色实心（原来写的是 0xFFF2FFFFFF —— 白色滑块落在白面板上等于没有）。
            thumbTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress * step + min
                    val text = format?.invoke(v) ?: "$v$unit"
                    if (readout.text.toString() != text) {
                        readout.text = text
                        // 数值跳了要「顶一下」——
                        // 光换字在余光里抓不住，位移一下才知道「这里变了」。
                        nudgeReadout()
                    }
                    if (fromUser) {
                        onChange(v)
                        onApply?.invoke()
                    }
                }

                /**
                 * 按下开始拖动 → **禁止祖先抢走 MOVE 事件**。
                 *
                 * ## 这是「第二道保险」
                 *
                 * 主防线是设置页整体的 [dispatchTouchEvent]（在 DOWN 派发之后
                 * 就把标志置上，第一个 MOVE 就已经被保护）。
                 * 这里再置一次是冗余的，但**无害且更保险**：
                 * 万一将来有人改了主防线的置位时机，
                 * 滑杆这一层还能自己把标志抢回来。
                 *
                 * ## 注意：这里只置位，**不复位**
                 *
                 * 复位交给设置页整体（[onDetachedFromWindow]）——
                 * 在这里调 `false` 会把「设置页整体禁止拦截」的标志一起撤掉，
                 * 反而让桌面又开始抢事件。
                 */
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    // 用外层的 holder 调（它和 SeekBar 同在这行里，效果一样：
                    // `requestDisallowInterceptTouchEvent` 会从自己这层一路往上传递）。
                    holder.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        holder.addView(bar, LinearLayout.LayoutParams(0, 40, 1f).also {
            // 左右各留 16：左边与标题文字分开，右边给读数让位。
            it.marginStart = 16
            it.marginEnd = 16
        })
        holder.addView(readout, LinearLayout.LayoutParams(SLIDER_READOUT_W, -1))
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, 96))
        // 分割线统一走 [hairline]（1px `Skin.GLASS_STROKE`）。
        hairline()
    }

    private fun bodyField(view: android.view.View, height: Int = 60) {
        hostCol.addView(view, LinearLayout.LayoutParams(-1, height).also { it.topMargin = 10 })
    }

    /**
     * 动作按钮（设计板的「白条 / 实底条」）：圆角 [BUTTON_RADIUS]、高 64。
     *
     * @param primary accent 实底（主操作，如「检查更新」「导出诊断包」）
     * @param danger 危险色实底（恢复/删除类）
     * @param full 占满整行、文字左对齐 —— 画板 s20「操作」那 8 条、
     *             s23「重新探测平台与通道」、s13「刷新列表」都是这个形态；
     *             不传时保持「紧凑、居中」，用于页脚那种单个按钮（如 s14 的居中按钮）。
     */
    /**
     * 造一枚按钮（不挂到任何容器上，由调用方决定摆哪儿）。
     *
     * 抽出来是为了 [actionButton]（整行一条）和 [actionPair]（并排两枚）共用同一套
     * 字号 / 圆角 / 填充 / 按压反馈 —— 两处若各写一份，画板那种「白条」很快就不一致了。
     */
    private fun makeButton(text: String, primary: Boolean, danger: Boolean, full: Boolean): TextView =
        // 第五十一轮：实底按钮上的文字用 `--accentInk`（日间白 / 夜间深青），
        // 之前写死夜间的 `0xFF08101C`，日间在青绿实底上几乎看不清。
        Ui.text(
            context,
            text,
            18f,
            if (primary || danger) pal.accentInk else pal.text,
            bold = true,
        ).apply {
            gravity = if (full) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
            setPadding(BUTTON_PAD, 14, BUTTON_PAD, 14)
            // 第151轮：打标记，测试要能盯住「按钮底边有没有留间距」
            //（见 [SettingsButtonGapTest]）—— 不打标记就只能靠源码断言，
            // 那是查不出「间距够不够」的。
            tag = ACTION_BUTTON_MARK
            background = Ui.round(
                when {
                    danger -> pal.danger
                    primary -> pal.accent
                    // 画板的白条是面板上最实的一档（`surfaceStrong`），
                    // 不是旧版那个跟行底一样的灰 —— 灰按钮在玻璃上等于没有按钮。
                    else -> Skin.surfaceStrong
                },
                BUTTON_RADIUS,
                if (primary || danger) 0f else 1f,
                Skin.GLASS_STROKE,
            )
            // 按压反馈：按钮是这一页里最常点的东西，必须有。
            Motion.press(this)
        }

    private fun actionButton(
        text: String,
        primary: Boolean = false,
        danger: Boolean = false,
        full: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val tv = makeButton(text, primary, danger, full)
        tv.setOnClickListener { onClick() }
        hostCol.addView(tv, LinearLayout.LayoutParams(if (full) -1 else -2, 64).also {
            // 上 18 / 下 24：按钮和上下内容都要能分开
            // （下间距给得比上间距大，是因为下一块通常是一段说明文字）。
            it.topMargin = 18
            it.bottomMargin = 24
        })
        return tv
    }

    /**
     * 并排两枚**等宽**按钮。
     *
     * 画板里 s06 底部（「立即切换」/「自定义主题色…」）、s07「视频壁纸」（「导入视频…」/
     * 「用系统选择器导入…」）都是并排两枚，不是上下两条。
     *
     * 取舍：两枚按钮**各自保留 18/24 的上下间距**（放进横向容器里就是行内留白）。
     * 看着像多余，其实是故意 —— 测试「按钮底下必须留间距」（`SettingsButtonGapTest`）
     * 是按视图树里所有带标记的按钮查的，并排按钮也算在内；
     * 若在容器里把它们的外边距抹成 0，那条回归就会失守。
     *
     * @param leftPrimary 左边那枚是不是主行动（accent 实底）
     */
    private fun actionPair(
        left: String,
        right: String,
        leftPrimary: Boolean = false,
        onLeft: () -> Unit,
        onRight: () -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val l = makeButton(left, leftPrimary, false, false).apply { setOnClickListener { onLeft() } }
        val r = makeButton(right, false, false, false).apply { setOnClickListener { onRight() } }
        row.addView(l, LinearLayout.LayoutParams(0, 64, 1f).also {
            it.topMargin = 18; it.bottomMargin = 24
        })
        row.addView(r, LinearLayout.LayoutParams(0, 64, 1f).also {
            it.topMargin = 18; it.bottomMargin = 24; it.leftMargin = 16
        })
        hostCol.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun pillButton(
        parent: ViewGroup,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): TextView {
        val tv = Ui.text(context, label, 18f, if (primary) pal.accentInk else pal.text, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(28, 14, 28, 14)
            background = Ui.round(
                if (primary) pal.accent else Skin.surfaceStrong,
                BUTTON_RADIUS,
                if (primary) 0f else 1f,
                Skin.GLASS_STROKE,
            )
            // 按压反馈：跟 [actionButton] 同一套手感。
            Motion.press(this)
            setOnClickListener { onClick() }
        }
        parent.addView(tv, LinearLayout.LayoutParams(-2, 64))
        return tv
    }

    /**
     * **两栏**正文（画板 s07 / s08 / s11 / s25 都是左右两栏）。
     *
     * 左栏给固定宽（画板量出来的那一档），右栏吃掉剩下的宽度，中间 [gap]。
     * 两栏各自是一个纵向 LinearLayout，用 [inColumn] 把通用的排版件写进去，
     * 于是「两栏」只是容器的差别，行高 / 标签 / chips 的写法一套到底。
     *
     * @param leftW 左栏宽度（设计像素，含它自己的右内边）
     * @param block 第一参数 = 左栏，第二参数 = 右栏
     */
    private fun twoColumns(leftW: Int, gap: Int = COLUMN_GAP, block: (LinearLayout, LinearLayout) -> Unit) {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(left, LinearLayout.LayoutParams(leftW, -2))
        wrap.addView(right, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = gap })
        body.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        block(left, right)
    }

    /**
     * 在某一栏里排版：期间 [section] / [row] / [note] / chip 行都写进 `col`，
     * 结束后自动切回整页（异常也会切回，不会把后面的页面带歪）。
     */
    private fun <R> inColumn(col: LinearLayout, block: () -> R): R {
        val prev = columnHost
        columnHost = col
        try {
            return block()
        } finally {
            columnHost = prev
        }
    }

    /**
     * 胶囊标签（画板 s05「已安装 / 未安装 / 兜底来源」、s23 状态位都是这个形态）。
     *
     * 形态：**满圆角 + 同色 12% 软底 + 同色字**，高 [PILL_TAG_H]。
     * 颜色一律由调用方从调色板派生（[Palette.ok] / [Palette.textDim] / [Palette.accent] …），
     * 本文件不出现任何字面色值。
     */
    private fun pillTag(text: String, color: Int): TextView {
        val tv = Ui.text(context, text, PILL_TAG_TEXT, color).apply {
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 0)
            background = Ui.round(
                pal.withAlpha(color, PILL_TAG_FILL_ALPHA),
                PILL,
                1f,
                pal.withAlpha(color, PILL_TAG_STROKE_ALPHA),
            )
        }
        tv.layoutParams = LinearLayout.LayoutParams(-2, PILL_TAG_H)
        return tv
    }

    private fun readInt(key: String, def: Int) = prefs.intValue(key, def)

    private fun writeInt(key: String, v: Int) {
        prefs.setIntValue(key, v)
        onApply?.invoke()
    }

    /**
     * 记录一次设置变更。
     *
     * 排障场景：真机上「设置改了没生效」—— 用 `key` + `name` + `from` + `to`
     * 就能确认到底写进了哪个偏好键、旧值和新值是什么，落盘日志里可直接 `grep key=xxx`。
     *
     * @param tag 按键增强相关的用 [LogTags.KEYS]，其余用 [LogTags.SETTING]。
     */
    private fun logSetting(
        key: String,
        name: String,
        from: Any?,
        to: Any?,
        tag: String = LogTags.SETTING,
        vararg extra: Pair<String, Any?>,
    ) {
        val fields = arrayOf<Pair<String, Any?>>(
            "key" to key,
            "name" to name,
            "from" to from,
            "to" to to,
        ) + extra
        Logx.i(tag, "设置变更", *fields)
    }

    /**
     * 记录一次设置变更（滑杆/拖动类高频操作）。
     *
     * 高频刷新的设置项不能打 INFO（会刷爆车机存储），这里只进 Logcat。
     */
    private fun logSettingDebug(key: String, name: String, from: Any?, to: Any?) {
        Logx.d(LogTags.SETTING, "设置变更", "key" to key, "name" to name, "from" to from, "to" to to)
    }

    // ------------------------------------------------------------- 各页

    /** 启动切换。 */
    private fun bootBody() {
        // 第155轮：「自动导航投屏」这一项**删掉了**。
        // 它写的 `start_navi_cast_enabled` 全仓没有任何代码读 —— 开机流程里
        // 没有任何导航投屏动作，这个开关从设下那天起就是纯装饰。
        // 跟「桌面开机启动」「胎压数字字号」不同，那两项现在有实现了（见下），
        // 这一项没有 —— 留着只会继续骗人，所以整行拿掉。
        //
        // 落盘键 `start_navi_cast_enabled` 仍保留在 Prefs.kt，
        // 将来真要做导航投屏时可以直接接回去。
        switchRow("自动切换驾驶模式", prefs.startSwitchEnabled) {
            val from = prefs.startSwitchEnabled
            prefs.startSwitchEnabled = it
            logSetting("start_switch_enabled", "自动切换驾驶模式", from, it)
        }
        // 动态读取本车支持的驾驶模式（无主线程 binder 开销，读不到时回落经济 / 舒适 / 运动）。
        val bootModes = VehicleController.supportedDriveModes()
        val modeLabels = bootModes.map { DriveMode.chipLabel(it) }
        // 画板 s01 的目标驾驶模式是小胶囊（约 52×34、右端 1838）。
        chipRow("目标驾驶模式", modeLabels, driveModeIndex(bootModes, prefs.startSwitchMode), chipH = 34) { idx ->
            val from = prefs.startSwitchMode
            val to = bootModes[idx]
            prefs.startSwitchMode = to
            logSetting("start_switch_mode", "目标驾驶模式", from, to)
        }
    }

    /** 方控仪表（case "cluster"）。 */
    private fun pageCluster() {
        switchRow("桥接开关", prefs.flag(KEY_BRIDGE_ENABLED)) {
            val from = prefs.flag(KEY_BRIDGE_ENABLED)
            prefs.setFlag(KEY_BRIDGE_ENABLED, it)
            logSetting(KEY_BRIDGE_ENABLED, "方控桥接开关", from, it)
            // 第八十七轮：桥不跟着开关即时启停的话，用户会以为「开了没反应」
            MusicController.get().syncBridgePreference()
        }
        // 画板 s03 开关下面有一行说明（验收报告 P2-6：实现里缺了这行）。
        // 走 noteRaw 是因为说明文案不在 SettingsCopyTest 的白名单里。
        noteRaw("关闭后 CarDesk 不再接收方控按键，全部交还车机原厂逻辑。")
    }

    /** 按键增强。 */
    /**
     * 「按键增强」页（画板 `s02-keys.png`）。
     *
     * ## 行高是画板的紧凑档，不是 128
     *
     * 画板这页量出来是 93 / 106 / 112 —— 六行 128 会把 884px 的视口撑爆，
     * 底部那块「按键通路」自检区被面板裁掉半截（验收报告：画板按钮
     * 完整落在 y986..1025，实现里只剩上半截）。
     * 现在逐行收窄（开关行 96、键位行 104、目标驾驶模式那行 110），
     * 再加自检区的高度，合计落在面板内。
     *
     * ## chips 右对齐
     *
     * 画板每个键位的动作胶囊都**右对齐到正文右内边**（x 到 1866）。
     * 这里不用单独处理 —— [chipRowCore] 里的 `fillViewport + gravity=END`
     * 已经把全页的胶囊都推到右边了。
     */
    private fun pageKeys() {
        val keysContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun renderKeysContent() {
            keysContent.removeAllViews()
            if (!prefs.keysEnhanced) {
                keysContent.visibility = View.GONE
                return
            }
            keysContent.visibility = View.VISIBLE
            val prevHost = columnHost
            columnHost = keysContent
            try {
                section("语音助手 / * 键")
                keySlot("单击", prefs.voiceClickAction, { prefs.voiceClickAction = it }, prefs.voiceClickMode) {
                    prefs.voiceClickMode = it
                }
                keySlot("双击", prefs.voiceDoubleAction, { prefs.voiceDoubleAction = it }, prefs.voiceDoubleMode) {
                    prefs.voiceDoubleMode = it
                }
                keySlot("长按", prefs.voiceLongAction, { prefs.voiceLongAction = it }, prefs.voiceLongMode) {
                    prefs.voiceLongMode = it
                }
                // 第三百一十五轮：车机方控走**广播**（不是 KeyEvent），真车上又没法用 adb
                // 模拟按键（shell 没权限）。所以这一页自己带一块「按键通路」自检：
                // 显示最近一次收到的按键，并能往执行端灌一次单击 / 长按，
                // 用来在车上确认「配置 → 手势 → 动作」这条链是通的。
                section("按键通路（车机广播）")
                val lastKey = prefs.stringValue(KeyActionExecutor.KEY_DIAG_LAST, "")
                kvRow(
                    "最近按键",
                    if (lastKey.isEmpty()) "还没收到（在车上按一下语音键）" else lastKey,
                    ok = lastKey.isNotEmpty(),
                )
                chipRow("自测", listOf("单击", "长按"), -1, height = KEY_ROW_H) { idx ->
                    val press = if (idx == 0) OemKeyProtocol.Press.CLICK else OemKeyProtocol.Press.LONG
                    Logx.event(LogTags.KEYS, "按键增强自测", "press" to press.name)
                    val handled = OemKeyRuntime.dispatch(
                        context = context.applicationContext ?: context,
                        event = OemKeyProtocol.OemKeyEvent(
                            keyCode = OemKeyProtocol.KEY_R_VOICE_ASSIST,
                            press = press,
                            sourceAction = "selftest",
                        ),
                        path = "selftest",
                    )
                    Logx.i(LogTags.KEYS, "按键增强自测结果", "press" to press.name, "handled" to handled)
                    renderKeysContent()
                }

                // 第三百一十七轮：Home 键双击。
                //
                // 车机上 Home 键短按回桌面是系统行为，双击默认开**车机自己的**最近任务。
                // 这里把双击改成打开本项目的最近任务页；接管状态与最近一次 Home 事件都
                // 显示出来，方便在车上判断「拦截通道有没有生效、走的是哪条路」。
                section("Home 键")
                switchRow(
                    "双击 Home 打开最近任务",
                    prefs.flag(com.cardesk.launcher.core.HomeKeyRuntime.KEY_DOUBLE_RECENTS, true),
                    height = KEY_SWITCH_ROW_H,
                ) {
                    val from = prefs.flag(com.cardesk.launcher.core.HomeKeyRuntime.KEY_DOUBLE_RECENTS, true)
                    prefs.setFlag(com.cardesk.launcher.core.HomeKeyRuntime.KEY_DOUBLE_RECENTS, it)
                    Logx.event(LogTags.HOME, "双击 Home 开关变更", "from" to from, "to" to it)
                    com.cardesk.launcher.core.HomeKeyRuntime.reset()
                    // 关掉双击映射就把车机的最近任务放回去；打开则按当前接管状态重新压制。
                    val ctx = context.applicationContext ?: context
                    runCatching {
                        com.cardesk.launcher.core.OemRecentsSuppressor.sync(ctx, prefs)
                    }.onFailure { Logx.w(LogTags.HOME, "原车最近任务同步失败", it) }
                }
                kvRow(
                    "Home 键接管",
                    com.cardesk.launcher.core.HomeKeyRuntime.interceptorStatus,
                    ok = com.cardesk.launcher.core.HomeKeyRuntime.interceptorActive,
                )
                val recentsStatus = com.cardesk.launcher.core.OemRecentsSuppressor.statusText
                kvRow(
                    "原车最近任务",
                    recentsStatus,
                    ok = recentsStatus.startsWith("已压制") || recentsStatus.contains("未安装"),
                )
                val lastHome = prefs.stringValue(com.cardesk.launcher.core.HomeKeyRuntime.KEY_DIAG_LAST, "")
                kvRow(
                    "最近 Home",
                    if (lastHome.isEmpty()) "还没收到（在车上按一下 Home 键）" else lastHome,
                    ok = lastHome.isNotEmpty(),
                )
                chipRow("自测", listOf("单击", "双击"), -1, height = KEY_ROW_H) { idx ->
                    Logx.event(LogTags.HOME, "Home 键自测", "index" to idx)
                    val ctx = context.applicationContext ?: context
                    // 双击 = 连续两次按压：第一下只做判定（dispatch=false），否则第一下就把
                    // 设置页关掉、第二下没地方点；第二下才是真正执行的那个。
                    val presses = if (idx == 0) 1 else 2
                    repeat(presses) { i ->
                        val dispatch = i == presses - 1
                        val action = runCatching {
                            com.cardesk.launcher.core.HomeKeyRuntime.simulate(ctx, prefs, dispatch)
                        }.getOrElse {
                            Logx.w(LogTags.HOME, "Home 键自测失败", it)
                            com.cardesk.launcher.core.HomeKeyPolicy.Action.NONE
                        }
                        Logx.i(
                            LogTags.HOME,
                            "Home 键自测结果",
                            "index" to idx,
                            "press" to (i + 1),
                            "dispatch" to dispatch,
                            "action" to action.name,
                        )
                    }
                    renderKeysContent()
                }
            } finally {
                columnHost = prevHost
            }
        }

        // 拨开「启用按键增强」后立即原地展开下方配置区，无需切页再切回，
        // 且不重建顶行开关本身，避免开关动画被打断或跳动。
        switchRow("启用按键增强", prefs.keysEnhanced, height = KEY_SWITCH_ROW_H) {
            val from = prefs.keysEnhanced
            prefs.keysEnhanced = it
            logSetting("custom_keys_enabled", "启用按键增强", from, it, LogTags.KEYS)
            renderKeysContent()
        }
        hostCol.addView(keysContent, LinearLayout.LayoutParams(-1, -2))
        renderKeysContent()
    }

    /**
     * 一个键位
     * 一行 = 键位名 + 动作选择；**只有动作是「切换驾驶模式」时**才追加「目标驾驶模式」一行。
     * 切换动作时就地显隐「目标驾驶模式」，不重建整页。
     */
    private fun keySlot(
        label: String,
        action: Int,
        onAction: (Int) -> Unit,
        mode: Int,
        onMode: (Int) -> Unit,
    ) {
        var currentAction = action
        var currentMode = mode
        val modeBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun renderModeBox() {
            modeBox.removeAllViews()
            if (currentAction != Prefs.ACTION_DRIVE_MODE) {
                modeBox.visibility = View.GONE
                return
            }
            modeBox.visibility = View.VISIBLE
            val prevHost = columnHost
            columnHost = modeBox
            try {
                // 画板 s02：这一行紧跟在键位行下面，标题「目标驾驶模式」+ 副标题
                // 「动作为「切换驾驶模式」时使用」，动态列出本车支持的模式。
                val keyModes = VehicleController.supportedDriveModes()
                chipRow(
                    "目标驾驶模式",
                    keyModes.map { DriveMode.chipLabel(it) },
                    driveModeIndex(keyModes, currentMode),
                    height = KEY_MODE_ROW_H,
                    subtitle = "动作为「切换驾驶模式」时使用",
                    // 画板新加的副标题，白名单里没有 → 不打标记（否则 SettingsCopyTest 误报）。
                    subtitleMarked = false,
                ) { idx ->
                    val from = currentMode
                    val to = keyModes[idx]
                    currentMode = to
                    onMode(to)
                    // 排障场景：切换驾驶模式没切过去 —— 记下目标驾驶模式
                    logSetting(
                        keyModeKey(label),
                        "$label·目标驾驶模式",
                        keyModeName(from),
                        keyModeName(to),
                        LogTags.KEYS,
                        "fromValue" to from,
                        "toValue" to to,
                    )
                }
            } finally {
                columnHost = prevHost
            }
        }

        chipRow(
            label,
            KEY_ACTION_LABELS,
            KEY_ACTION_VALUES.indexOf(currentAction).takeIf { it >= 0 } ?: 0,
            height = KEY_ROW_H,
        ) { idx ->
            val from = currentAction
            val to = KEY_ACTION_VALUES[idx]
            currentAction = to
            onAction(to)
            // 排障场景：方向盘按键改绑后没反应 —— 记下键名与改绑前后的动作
            logSetting(
                keyActionKey(label),
                label,
                keyActionName(from),
                keyActionName(to),
                LogTags.KEYS,
                "fromValue" to from,
                "toValue" to to,
            )
            renderModeBox()
        }
        hostCol.addView(modeBox, LinearLayout.LayoutParams(-1, -2))
        renderModeBox()
    }

    /** 按键动作值 → 中文名（日志用，认不出来就回显原值）。 */
    private fun keyActionName(value: Int): String {
        val index = KEY_ACTION_VALUES.indexOf(value)
        return if (index >= 0) KEY_ACTION_LABELS[index] else value.toString()
    }

    /** 驾驶模式值 → 中文名（日志用，认不出来就回显原值）。 */
    private fun keyModeName(value: Int): String =
        if (DriveMode.normalizeModeId(value) != DriveMode.SELECTION_NONE) DriveMode.fullLabel(value) else value.toString()

    /** 键位名 → 持久化键名（按键动作），日志用。 */
    private fun keyActionKey(label: String): String = when (label) {
        "单击" -> "voice_click_action"
        "双击" -> "voice_double_action"
        "长按" -> "voice_long_action"
        else -> "key_action_$label"
    }

    /** 键位名 → 持久化键名（目标驾驶模式），日志用。 */
    private fun keyModeKey(label: String): String = when (label) {
        "单击" -> "voice_click_mode"
        "双击" -> "voice_double_mode"
        "长按" -> "voice_long_mode"
        else -> "key_mode_$label"
    }

    /** 取值 → 动态驾驶模式清单下标（未配置/不在清单内时回落第 0 项）。 */
    private fun driveModeIndex(modes: IntArray, value: Int): Int {
        val norm = DriveMode.normalizeModeId(value)
        val index = modes.indexOf(norm)
        return if (index >= 0) index else 0
    }

    /** 外部增删改自动任务后刷新设置页列表。 */
    fun refreshTasks() {
        if (selectedKey == "tasks") select("tasks")
    }

    /** 外部修改槽位 / 状态栏生效应用 / Dock 快捷应用后刷新「Dock 与导航栏」页。 */
    fun refreshDockPage() {
        if (selectedKey == "dock" || selectedKey == "quickapps") select(selectedKey)
    }

    /** 自动任务。 */
    private fun pageTasks() {
        // —— 标题行右侧是「导入示例」「+ 添加」
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Ui.text(context, "自动任务", 18f, pal.textDim), Ui.linear(0, -2, 1f))
        header.addView(
            Ui.text(context, "导入示例", 18f, pal.textDim).apply {
                gravity = Gravity.CENTER
                setPadding(16, 10, 16, 10)
                Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
                setOnClickListener {
                    val list = ScheduledTasks.load(prefs)
                    val existingIds = list.map { it.id }.toSet()
                    val added = ScheduledTasks.examplePresets().filter { it.id !in existingIds }
                    if (added.isNotEmpty()) {
                        list.addAll(added)
                        ScheduledTasks.save(prefs, list)
                    }
                    select(selectedKey)
                }
            },
            Ui.linear(-2, -2),
        )
        header.addView(
            Ui.text(context, "+ 添加", 18f, pal.accent).apply {
                gravity = Gravity.CENTER
                setPadding(16, 10, 16, 10)
                // 按压反馈：这是个纯文字的小入口，最需要提示「点到了」。
                Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
                setOnClickListener {
                    // 排障场景：点击添加直接进入新建任务编辑器
                    Logx.event(LogTags.TASK, "从设置页直接打开新建任务")
                    onEditTask?.invoke(null)
                }
            },
            Ui.linear(-2, -2),
        )
        hostCol.addView(header, Ui.linear(-1, 44, 0f, 0, 12))

        val tasks = ScheduledTasks.load(prefs)
        // 排障场景：设置页里的任务列表为空 / 数量不对
        Logx.i(LogTags.TASK, "自动任务页加载", "count" to tasks.size)
        if (tasks.isEmpty()) {
            hostCol.addView(
                Ui.text(context, "暂无自动任务，可点击「导入示例」或「+ 添加」创建规则", 16f, pal.textFaint),
                Ui.linear(-1, -2, 0f, 0, 12),
            )
        }
        for (task in tasks) {
            val toggle = BoardSwitch(context).apply {
                tint = pal.accent
                checked = task.enabled
                setOnClickListener {
                    val from = checked
                    toggle()
                    Motion.bump(this)
                    val enabled = checked
                    val list = ScheduledTasks.load(prefs)
                    val idx = list.indexOfFirst { it.id == task.id }
                    if (idx >= 0) {
                        list[idx] = list[idx].copy(enabled = enabled)
                        ScheduledTasks.save(prefs, list)
                        if (enabled) {
                            com.cardesk.launcher.core.TaskScheduler.schedule(context, list[idx])
                        } else {
                            cancelTask(task.id)
                        }
                    }
                    Logx.event(
                        LogTags.TASK,
                        if (enabled) "从设置页启用自动任务" else "从设置页停用自动任务",
                        "id" to task.id,
                        "from" to from,
                        "to" to enabled,
                    )
                }
            }
            row(
                toggle,
                appLabelOf(task.target) ?: ScheduledTasks.actionLabel(task),
                ScheduledTasks.conditionLabel(task),
                onRowClick = {
                    Logx.event(LogTags.TASK, "从设置页编辑自动任务", "id" to task.id)
                    onEditTask?.invoke(task)
                },
            )
        }
    }

    private fun cancelTask(taskId: String) {
        runCatching { com.cardesk.launcher.core.TaskScheduler.cancel(context, taskId) }
            .onFailure {
                // 排障场景：任务删了但闹钟没取消掉
                Logx.w(LogTags.TASK, "取消任务调度失败", it, "id" to taskId)
            }
    }

    private fun appLabelOf(pkg: String): String? {
        if (pkg.isBlank()) return null
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    /** 桌面自启（case "autostart"）。 */
    private fun autostartBody() {
        // 第155轮：这个开关**接上实现**了。
        //
        // 之前它写进 `desktop_boot_autostart` 就没人读 —— 但其实**功能一直存在**：
        // BootReceiver 开机时会显式拉一次桌面（兜底那些不主动 re-launch HOME 的车机），
        // 只是没有开关管它。现在由用户决定要不要开：
        // - 开（默认）：开机照旧拉桌面（跟今天一模一样）；
        // - 关：开机不显式拉桌面。
        switchRow("桌面开机启动", prefs.bootAutostart) {
            val from = prefs.bootAutostart
            prefs.bootAutostart = it
            logSetting("desktop_boot_autostart", "桌面开机启动", from, it)
        }
    }

    /**
     * 启动与开机（第149轮：把「桌面自启」并进来）。
     *
     * ## 为什么合并
     *
     * 「桌面开机启动」和「启动后投屏 / 切驾驶模式」讲的是**同一件事**：
     * 开机时桌面要干什么。原先分两页，用户为了改一个开关要来回点。
     *
     * 「桌面开机启动」只有 1 个开关，单开一页太浪费。
     */
    private fun pageBoot() {
        // 画板 s01 只有**一个**「开机自启」组标题（以前「启动后」是正文区上方
        // 额外挂的一个 16px 小字，和组标题重复，本轮已删）。
        section("开机自启")
        autostartBody()
        bootBody()
        // 画板底部有一行风险提示（y≈583），以前实现里缺这一句
        // （验收报告 P1-10「说明缺失」）：
        // 「关闭『桌面开机启动』后，开机停在系统桌面，需要手动打开 CarDesk。」
        //
        // 走 [noteRaw]：它是画板**新加**的一条文案，而「说明文字只能剩白名单里那几类」
        // 的白名单在 `SettingsCopyTest`（本轮禁改的测试文件）里 —— 用 [note] 会
        // 让白名单回归误报「又加回来了」。这里不冒充「说明」标记，
        // 白名单该补哪一条由测试文件的维护者按画板补。
        noteRaw("关闭「桌面开机启动」后，开机停在系统桌面，需要手动打开 CarDesk。")
    }

    /** 日夜模式。 */
    private fun pageDayNight() {
        switchRow("启用日夜模式同步", prefs.dayNightEnabled) {
            val from = prefs.dayNightEnabled
            prefs.dayNightEnabled = it
            logSetting("daynight_enabled", "启用日夜模式同步", from, it)
        }
        // 画板 s06 在开关行和卡片之间有一行分区标签「同步方式」
        // （验收报告：实现里这三张卡直接贴在开关下面，没有这行标签）。
        section("同步方式")
        // 三个来源**并排**（画板 s06 是三张卡片横着排）。
        // 竖着堆三行的话，选一次要滚过半屏才能比出另外两个的差别。
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cardChoice(
            "跟随高德", "随导航日夜切换",
            prefs.dayNightMode == DayNight.MODE_FOLLOW_AMAP, modeRow,
        ) {
            val from = prefs.dayNightMode
            prefs.dayNightMode = DayNight.MODE_FOLLOW_AMAP
            logSetting("daynight_mode", "日夜模式来源·跟随高德", from, DayNight.MODE_FOLLOW_AMAP)
        }
        cardChoice(
            "跟随光感", "依据环境光传感器",
            prefs.dayNightMode == DayNight.MODE_FOLLOW_SENSOR, modeRow,
        ) {
            val from = prefs.dayNightMode
            prefs.dayNightMode = DayNight.MODE_FOLLOW_SENSOR
            logSetting("daynight_mode", "日夜模式来源·跟随光感", from, DayNight.MODE_FOLLOW_SENSOR)
        }
        cardChoice(
            "跟随时间", "日出日落自动切换",
            prefs.dayNightMode == DayNight.MODE_FOLLOW_TIME, modeRow,
        ) {
            val from = prefs.dayNightMode
            prefs.dayNightMode = DayNight.MODE_FOLLOW_TIME
            logSetting("daynight_mode", "日夜模式来源·跟随时间", from, DayNight.MODE_FOLLOW_TIME)
        }
        hostCol.addView(modeRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 12 })
        if (prefs.dayNightMode == DayNight.MODE_FOLLOW_TIME) {
            section("切换时间")
            timePickerRow("白天开始", prefs.dayStartMin) { prefs.dayStartMin = it }
            timePickerRow("夜间开始", prefs.nightStartMin) { prefs.nightStartMin = it }
        }
        val night = pal.night
        note(
            "当前：" + (if (night) "夜间（深色）" else "日间（浅色）") + " · " +
                (if (prefs.dayNightEnabled) "已启用同步" else "未启用同步"),
        )
        // 画板 s06 底部是**并排两枚**按钮（立即切换 / 自定义主题色…）。
        actionPair(
            "立即切换",
            "自定义主题色…",
            onLeft = { onToggleDayNight?.invoke() ?: onApply?.invoke() },
            onRight = { onShowAccent?.invoke() },
        )
    }

    /**
     * 一张「卡片选项」：标题 + 副标题，选中时 accent 淡底 + accent 描边。
     *
     * ## 动效
     *
     * - 整张卡片按下有反馈（整行可点，用「行」的缩放幅度）；
     * - 选中的那一项，单选圆点会「鼓一下」——
     *   但这一页随后会**整页重建**（见 [pendingRadioBump]），
     *   所以圆点要先登记、重建完再补那一下。
     *
     * ## [host]：为什么值得多一个参数
     *
     * 画板 s06 的三个来源是**横排**的。要横排就得先有一个横向容器，
     * 而 [cardChoice] 原来是直接往 [body] 里塞行的 ——
     * 所以给它一个可选的「挂到哪」：给了就等分宽度挂在那个容器里，
     * 没给就还是整行挂在 [body] 里（现在的调用只有日夜模式页那一处）。
     */
    private fun cardChoice(
        title: String,
        subtitle: String,
        selected: Boolean,
        host: LinearLayout? = null,
        onPick: () -> Unit,
    ) {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 卡片圆角 24（设计令牌：卡片一档）。
            background = Ui.round(
                if (selected) pal.accentSoft else Skin.surfaceStrong,
                CARD_RADIUS,
                1f,
                if (selected) pal.accent else Skin.GLASS_STROKE,
            )
            setPadding(20, 16, 20, 16)
            // 按压反馈：整张卡片就是一个按钮。
            Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
            setOnClickListener {
                // 选中变化要「看得见」：圆点鼓一下。
                // 但下面 onPick 改完偏好就会 select() 整页重建 ——
                // 重建前的动画会被连着旧视图一起摘掉，所以先记下来。
                pendingRadioBump = title
                onPick()
                onApply?.invoke()
                select(selectedKey)
            }
        }
        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        textBox.addView(
            Ui.text(context, title, ROW_TITLE, if (selected) pal.accent else pal.text, bold = true),
            LinearLayout.LayoutParams(-1, -2),
        )
        textBox.addView(
            Ui.text(context, subtitle, ROW_SUB, pal.textDim),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
        )
        holder.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        val radio = Ui.text(context, if (selected) "●" else "○", 18f, if (selected) pal.accent else pal.textFaint)
            .apply { gravity = Gravity.CENTER }
        // 登记圆点：重建之后才知道该给哪一个「鼓一下」。
        cardRadios[title] = radio
        holder.addView(radio, LinearLayout.LayoutParams(32, -2))
        if (host != null) {
            host.addView(holder, LinearLayout.LayoutParams(0, CARD_CHOICE_H, 1f).also {
                // 卡片之间 20 的间隙（画板 s06：456 + 21 + 456 + 21 + 462 ≈ 1416）。
                if (host.childCount > 0) it.leftMargin = CARD_CHOICE_GAP
            })
        } else {
            hostCol.addView(holder, LinearLayout.LayoutParams(-1, CARD_CHOICE_H))
        }
    }

    /** 时/分双滚轮。 */
    private fun timePickerRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
        val value = Ui.text(context, DayNight.formatMinute(minutes), 18f, pal.text).apply {
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            // 值胶囊：与 [pickerRow] / [dropdownRow] 同一款（surfaceSoft + 玻璃描边 + 行圆角）。
            background = Ui.round(Skin.surfaceSoft, ROW_RADIUS, 1f, Skin.GLASS_STROKE)
            // 按压反馈：值胶囊是这一行唯一能点的地方。
            Motion.press(this)
            setOnClickListener { showTimePicker(label, minutes, onChange) }
        }
        row(value, label)
    }

    private fun showTimePicker(title: String, minutes: Int, onChange: (Int) -> Unit) {
        // 排障场景：日夜切换时间改不了 —— 记下打开了哪个时间选择器
        Logx.event(LogTags.SETTING, "打开时间选择器", "title" to title, "current" to minutes)
        val overlay = FrameLayout(context)
        val scrim = View(context).apply {
            // 第五十一轮：遮罩跟随主题，不再写死 60% 黑。
            setBackgroundColor(Skin.SCRIM)
            isClickable = true
        }
        overlay.addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.glass(28f, true, Skin.SHEET_FILL)
            setPadding(28, 24, 28, 28)
        }
        overlay.addView(sheet, LayoutParams(520, -2, Gravity.CENTER))

        /**
         * 关掉时间选择器：**先播退场动画，播完再摘层**。
         *
         * 直接 `removeView` 的话退场动画根本没机会播 ——
         * 视图已经不在树上了，画面是「啪」一下消失。
         */
        fun dismissPicker() {
            Motion.exitSheet(sheet, scrim) { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        }

        scrim.setOnClickListener { dismissPicker() }
        sheet.addView(Ui.text(context, title, 26f, pal.text, bold = true), LinearLayout.LayoutParams(-1, 44))
        // 第150轮：去掉了原来那句「24 小时制 · 点按箭头调整」的说明文字 ——
        // 滚轮本身的样式已经说明了一切。腾出来的间距补在滚轮上面，别让标题和滚轮贴死。
        val wheels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val hourPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 23
            value = minutes / 60
            wrapSelectorWheel = false
        }
        val minutePicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = minutes % 60
            wrapSelectorWheel = false
        }
        wheels.addView(hourPicker, LinearLayout.LayoutParams(0, 180, 1f))
        wheels.addView(
            Ui.text(context, ":", 48f, pal.text, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(72, 180),
        )
        wheels.addView(minutePicker, LinearLayout.LayoutParams(0, 180, 1f))
        sheet.addView(wheels, LinearLayout.LayoutParams(-1, 200).also { it.topMargin = 20 })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(
            Ui.text(context, "取消", 20f, pal.textDim).apply {
                gravity = Gravity.CENTER
                background = Ui.round(Skin.CHIP_FILL, 22f, 1f, Skin.CHIP_STROKE)
                // 按压反馈：两个按钮是这一层里唯一能点的东西。
                Motion.press(this)
                setOnClickListener {
                    // 排障场景：时间选择器被取消 —— 记下没改
                    Logx.event(LogTags.SETTING, "取消时间选择器", "title" to title)
                    dismissPicker()
                }
            },
            LinearLayout.LayoutParams(0, 60, 1f).also { it.rightMargin = 12 },
        )
        buttons.addView(
            Ui.text(context, "确认", 20f, pal.accentInk, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(pal.accent, 22f)
                // 按压反馈：同「取消」。
                Motion.press(this)
                setOnClickListener {
                    val picked = DayNight.minuteOfDay(hourPicker.value, minutePicker.value)
                    val from = minutes
                    onChange(picked)
                    // 排障场景：日夜切换时间改了没生效 —— 记下改前改后的分钟数
                    logSetting(
                        timePickerKey(title),
                        title,
                        from,
                        picked,
                    )
                    Logx.event(
                        LogTags.SETTING,
                        "确认时间选择器",
                        "title" to title,
                        "from" to from,
                        "to" to picked,
                        "text" to DayNight.formatMinute(picked),
                    )
                    dismissPicker()
                    onApply?.invoke()
                    select(selectedKey)
                }
            },
            LinearLayout.LayoutParams(0, 60, 1f),
        )
        sheet.addView(buttons, LinearLayout.LayoutParams(-1, 70).also { it.topMargin = 8 })
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 时间选择器标题 → 持久化键名（日志用）。 */
    private fun timePickerKey(title: String): String = when (title) {
        "白天开始" -> "daynight_day_start_min"
        "夜间开始" -> "daynight_night_start_min"
        else -> "daynight_time_$title"
    }

    /**
     * 壁纸设置（第五十七轮新增页）。
     *
     * 第四十九轮「浮屿」把壁纸压成了纯色底，用户明确要求
     * 「默认壁纸不要用纯色的，用风景、拟物类的」——
     * 这一页就是挑风景的入口：上面一张大预览，下面 9 张缩略图（点一下立刻换）。
     *
     * 缩略图画的是**和桌面上完全一样**的那张壁纸（同一个渲染器、同一套日夜配色），
     * 所以「缩略图里长什么样，桌面上就是什么样」。
     */
    /**
     * 壁纸设置（画板 `s07-wallpaper.png`）：**两栏**。
     *
     * 左栏（598 宽）= 当前壁纸大预览 + 自定义壁纸（空框 / 预览 + 导入 / 使用 / 删除）；
     * 右栏（784 宽）= 9 张预设壁纸的 3×3 网格 + 壁纸轮播 + 视频壁纸。
     *
     * ## 以前是「一整条竖排」
     *
     * 大预览 620×349 占满整行，接着又是一块 620×349 的自定义预览，
     * 9 张预设要滚过整整两屏才看得到，轮播和视频壁纸还在更下面 ——
     * 于是验收在「首屏」只看到「当前壁纸 / 自定义壁纸」，判成「画板上的能力没有实现」。
     * 其实这些能力**一直都在**（本页原来就有），只是被两块大预览压到了折叠线以下。
     * 现在按画板分栏：左边看当前 / 自定义，右边一屏就能看到 9 张预设 + 轮播 + 视频。
     *
     * ## 缩略图尺寸跟着栏宽收
     *
     * 右栏 784 宽放 3 列 ⇒ 每格约 254（画板也是 254）。缩略图高度按画板取 107
     * （原来 172 是整行宽时的比例），三行 + 间距一共 398，右栏才塞得下轮播和视频。
     */
    private fun pageWallpaper() {
        val current = com.cardesk.launcher.core.Wallpapers.normalize(prefs.wallpaper)
        val core = com.cardesk.launcher.core.CustomWallpaper
        val hasDayCustom = core.hasDay(context)
        val hasNightCustom = core.hasNight(context)
        val hasCustom = hasDayCustom || hasNightCustom
        val hasVideo = com.cardesk.launcher.core.VideoWallpaper.has(context)
        val hasNightVideo = com.cardesk.launcher.core.VideoWallpaper.hasNight(context)
        val usingVideo = prefs.videoWallpaperEnabled && hasVideo
        val usingCustom = !usingVideo && prefs.wallpaperCustom && hasCustom
        val usingDefaultDayNight = !usingVideo && !usingCustom && prefs.wallpaperDefaultDayNight

        val dayCustomBmp = if (hasDayCustom) (core.cachedBitmap(night = false) ?: core.loadScaled(context, night = false)) else null
        val nightCustomBmp = if (hasNightCustom) (core.cachedBitmap(night = true) ?: core.loadScaled(context, night = true)) else null
        val activeCustomIsNight = hasCustom && core.activeSlotIsNight(context, pal.night)
        val activeCustomBmp = if (activeCustomIsNight) (nightCustomBmp ?: dayCustomBmp) else (dayCustomBmp ?: nightCustomBmp)

        var previewThumb: WallpaperThumbView? = null
        var captionView: TextView? = null
        var defaultCard: com.cardesk.launcher.ui.widget.DayNightWallpaperCardView? = null
        var defaultLabel: TextView? = null
        val thumbs = ArrayList<WallpaperThumbView>()
        val labels = ArrayList<TextView>()

        fun modeBadge(label: String, active: Boolean): TextView =
            Ui.text(
                context,
                label,
                12f,
                if (active) pal.accentInk else 0xFFF4F8FC.toInt(),
                bold = true,
            ).apply {
                gravity = Gravity.CENTER
                background = Ui.round(
                    if (active) pal.accent else 0xB8121826.toInt(),
                    com.cardesk.launcher.core.Shape.PILL,
                )
                setPadding(12, 3, 12, 3)
            }

        /** 渲染自定义静态壁纸的单个日/夜预览槽（左日间 / 右夜间）。 */
        fun buildStaticSlot(
            badgeLabel: String,
            bmp: android.graphics.Bitmap?,
            isActiveSlot: Boolean,
            emptyHint: String,
            onTap: () -> Unit,
        ): FrameLayout {
            val slot = FrameLayout(context).apply {
                Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
                setOnClickListener { onTap() }
            }
            if (bmp != null) {
                val iv = android.widget.ImageView(context).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    background = Ui.round(Skin.surfaceSoft, com.cardesk.launcher.core.Shape.INNER, 1f, Skin.GLASS_STROKE)
                    clipToOutline = true
                    setImageBitmap(bmp)
                }
                slot.addView(iv, FrameLayout.LayoutParams(-1, -1))
                val border = View(context).apply {
                    background = Ui.round(
                        0x00000000,
                        com.cardesk.launcher.core.Shape.INNER,
                        if (isActiveSlot) 2.5f else 1f,
                        if (isActiveSlot) pal.accent else Skin.GLASS_STROKE,
                    )
                }
                slot.addView(border, FrameLayout.LayoutParams(-1, -1))
            } else {
                val dashed = Ui.round(TRANSPARENT, com.cardesk.launcher.core.Shape.INNER, 1f, Skin.GLASS_STROKE)
                dashed.setStroke(1, Skin.GLASS_STROKE, 10f, 7f)
                slot.background = dashed
                slot.addView(
                    Ui.text(context, emptyHint, 13f, pal.textDim).apply {
                        gravity = Gravity.CENTER
                        setPadding(12, 24, 12, 8)
                    },
                    FrameLayout.LayoutParams(-1, -1),
                )
            }
            slot.addView(
                modeBadge(badgeLabel, isActiveSlot),
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also {
                    it.leftMargin = 10
                    it.topMargin = 10
                },
            )
            return slot
        }

        /** 刷新「当前壁纸」预览 + 选中态（跨两栏）。 */
        fun refresh(index: Int, defaultMode: Boolean = usingDefaultDayNight) {
            val spec = com.cardesk.launcher.core.Wallpapers.spec(index)
            previewThumb?.let { p ->
                p.defaultDayNight = defaultMode
                p.palette = pal
                p.spec = spec
            }
            val customDesc = when {
                hasDayCustom && hasNightCustom -> "日夜双图 · 随日夜自动切换"
                hasNightCustom -> "夜间静态图"
                else -> "导入的照片"
            }
            captionView?.text = when {
                usingVideo -> if (hasNightVideo) "当前：动态视频壁纸（日夜双片 · 自动切换）" else "当前：动态视频壁纸"
                usingCustom -> "当前：自定义壁纸（$customDesc）"
                defaultMode -> "当前：默认日夜壁纸 · 随日夜模式自动切换"
                else -> "当前：${spec.name} · ${spec.desc}"
            }
            defaultCard?.chosen = defaultMode
            defaultLabel?.let { lbl ->
                lbl.setTextColor(if (defaultMode) pal.accent else pal.textDim)
                lbl.typeface = Typeface.create(Typeface.DEFAULT, if (defaultMode) Typeface.BOLD else Typeface.NORMAL)
            }
            thumbs.forEachIndexed { i, t ->
                val on = !defaultMode && !usingCustom && !usingVideo && i == index
                t.chosen = on
                labels[i].setTextColor(if (on) pal.accent else pal.textDim)
                labels[i].typeface = Typeface.create(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        twoColumns(WALLPAPER_COL_W) { left, right ->
            // ------------------------------------------------------ 左栏：当前壁纸 / 自定义静态（日夜双模）/ 在线壁纸
            inColumn(left) {
                section("当前壁纸")
                val previewHolder = FrameLayout(context)
                if (usingCustom && activeCustomBmp != null) {
                    val iv = android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = Ui.round(Skin.surfaceSoft, CARD_RADIUS, 1f, Skin.GLASS_STROKE)
                        clipToOutline = true
                        setImageBitmap(activeCustomBmp)
                    }
                    previewHolder.addView(iv, FrameLayout.LayoutParams(-1, -1))
                    val border = View(context).apply {
                        background = Ui.round(0x00000000, CARD_RADIUS, 2f, pal.accent)
                    }
                    previewHolder.addView(border, FrameLayout.LayoutParams(-1, -1))
                } else {
                    val p = WallpaperThumbView(
                        context,
                        com.cardesk.launcher.core.Wallpapers.spec(current),
                        pal.night,
                        pal.accent,
                    ).apply {
                        defaultDayNight = usingDefaultDayNight
                        palette = pal
                        chosen = true
                    }
                    previewThumb = p
                    previewHolder.addView(p, FrameLayout.LayoutParams(-1, -1))
                }
                previewHolder.addView(
                    modeBadge(if (pal.night) "当前 · 夜间模式" else "当前 · 日间模式", active = true),
                    FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also {
                        it.leftMargin = 14
                        it.topMargin = 14
                    },
                )
                hostCol.addView(previewHolder, LinearLayout.LayoutParams(-1, 224).also {
                    it.topMargin = 8
                })

                val caption = Ui.text(
                    context,
                    "",
                    ROW_SUB,
                    if (usingCustom || usingVideo || usingDefaultDayNight) pal.accent else pal.textDim,
                )
                captionView = caption
                hostCol.addView(caption, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 })

                // 自定义静态壁纸：日间 + 夜间双槽并排预览，随日夜模式自动切换
                section("自定义静态壁纸（随日夜切换）")
                val slotsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val daySlot = buildStaticSlot(
                    badgeLabel = if (usingCustom && !activeCustomIsNight) "日间 · 生效" else "日间静态",
                    bmp = dayCustomBmp,
                    isActiveSlot = usingCustom && !activeCustomIsNight,
                    emptyHint = if (!hasCustom) "还没有导入壁纸" else "未导入日间照片",
                    onTap = { (onPickWallpaper ?: onImportWallpaper)?.invoke() },
                )
                val nightSlot = buildStaticSlot(
                    badgeLabel = if (usingCustom && activeCustomIsNight) "夜间 · 生效" else "夜间静态",
                    bmp = nightCustomBmp,
                    isActiveSlot = usingCustom && activeCustomIsNight,
                    emptyHint = "未导入 · 夜间沿用日间",
                    onTap = { (onPickNightWallpaper ?: onImportNightWallpaper)?.invoke() },
                )
                slotsRow.addView(daySlot, LinearLayout.LayoutParams(0, 146, 1f))
                slotsRow.addView(
                    nightSlot,
                    LinearLayout.LayoutParams(0, 146, 1f).also { it.leftMargin = 14 },
                )
                hostCol.addView(slotsRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })

                actionPair(
                    "导入照片…",
                    if (hasNightCustom) "更换夜间照片…" else "导入夜间照片…",
                    onLeft = { onImportWallpaper?.invoke() },
                    onRight = { onImportNightWallpaper?.invoke() },
                )
                if (hasCustom && !usingCustom) {
                    actionButton("使用自定义壁纸", full = true) { onUseCustomWallpaper?.invoke() }
                }
                if (hasDayCustom) {
                    actionButton("删除自定义壁纸", danger = true, full = true) { onDeleteCustomWallpaper?.invoke() }
                }
                if (hasNightCustom) {
                    actionButton("删除夜间壁纸", danger = true, full = true) { onDeleteCustomNightWallpaper?.invoke() }
                }

                // 在线壁纸资源（Cloudflare R2：静态 + 动态，均支持日夜区分）
                section("在线壁纸资源")
                kvRow("资源源", "Cloudflare R2 · 支持日夜静态/动态壁纸", true)
                actionButton("浏览在线壁纸…", full = true) { openOnlineWallpaperDialog() }
            }

            // ------------------------------------------------------ 右栏：默认日夜 / 预设风景 / 轮播 / 动态视频
            inColumn(right) {
                section("默认壁纸（随日夜切换）")
                val dnCard = com.cardesk.launcher.ui.widget.DayNightWallpaperCardView(
                    context,
                    pal.accent,
                ).apply {
                    chosen = usingDefaultDayNight
                    Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
                    setOnClickListener {
                        if (usingDefaultDayNight) return@setOnClickListener
                        prefs.wallpaperDefaultDayNight = true
                        prefs.wallpaperCustom = false
                        prefs.videoWallpaperEnabled = false
                        prefs.videoWallpaper = false
                        logSetting(
                            "design_wallpaper_default_daynight", "壁纸", "preset/custom", "default_daynight",
                            LogTags.SETTING,
                        )
                        refresh(current, defaultMode = true)
                        onUseDefaultDayNightWallpaper?.invoke()
                        select("wallpaper")
                    }
                }
                val dnLbl = Ui.text(context, "默认日夜壁纸 · 自动跟随日间 / 夜间模式", 12f, pal.textDim).apply {
                    gravity = Gravity.CENTER
                }
                defaultCard = dnCard
                defaultLabel = dnLbl
                hostCol.addView(dnCard, LinearLayout.LayoutParams(-1, 92).also {
                    it.topMargin = 8
                })
                hostCol.addView(dnLbl, LinearLayout.LayoutParams(-1, WALLPAPER_THUMB_LABEL_H))

                section("预设风景壁纸")
                for (r in 0 until 3) {
                    val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    for (c in 0 until 3) {
                        val index = r * 3 + c
                        val spec = com.cardesk.launcher.core.Wallpapers.spec(index)
                        val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                        val thumb = WallpaperThumbView(context, spec, pal.night, pal.accent).apply {
                            Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
                        }
                        val label = Ui.text(context, spec.name, 12f, pal.textDim).apply {
                            gravity = Gravity.CENTER
                        }
                        cell.addView(thumb, LinearLayout.LayoutParams(-1, 90))
                        cell.addView(label, LinearLayout.LayoutParams(-1, WALLPAPER_THUMB_LABEL_H))
                        line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).also {
                            if (c > 0) it.leftMargin = 14
                        })
                        thumbs.add(thumb)
                        labels.add(label)
                    }
                    hostCol.addView(line, LinearLayout.LayoutParams(-1, -2).also {
                        it.topMargin = if (r == 0) 8 else 12
                    })
                }

                thumbs.forEachIndexed { index, thumb ->
                    thumb.setOnClickListener {
                        val from = prefs.wallpaper
                        val next = com.cardesk.launcher.core.Wallpapers.normalize(index)
                        if (next == from && !usingDefaultDayNight && !usingCustom && !usingVideo) return@setOnClickListener
                        prefs.wallpaperDefaultDayNight = false
                        prefs.wallpaper = next
                        logSetting(
                            "design_wallpaper_index", "壁纸", from, next, LogTags.SETTING,
                            "name" to com.cardesk.launcher.core.Wallpapers.name(next),
                        )
                        refresh(next, defaultMode = false)
                        onWallpaperChange?.invoke(next)
                        select("wallpaper")
                    }
                }

                // ---------------------------------------------------------- 轮播
                section("壁纸轮播")
                switchRow(
                    "自动轮播",
                    prefs.wallpaperCarousel,
                    "每隔一段时间自动换一张静态壁纸",
                    height = ROW_H_TIGHT,
                ) { on ->
                    prefs.wallpaperCarousel = on
                    logSetting(
                        "design_wallpaper_carousel", "壁纸轮播",
                        !on, on, LogTags.SETTING,
                    )
                    onWallpaperCarouselChange?.invoke(on)
                }
                val intervalOptions = com.cardesk.launcher.core.WallpaperCarousel.INTERVAL_SECONDS
                    .map { if (it >= 60) "${it / 60} 分钟" else "$it 秒" }
                val currentInterval = com.cardesk.launcher.core.WallpaperCarousel
                    .normalizeInterval(prefs.wallpaperCarouselIntervalSec)
                chipRow(
                    "轮播间隔",
                    intervalOptions,
                    intervalOptions.indexOf(
                        if (currentInterval >= 60) "${currentInterval / 60} 分钟" else "$currentInterval 秒",
                    ),
                    height = ROW_H_TIGHT,
                ) { picked ->
                    val sec = com.cardesk.launcher.core.WallpaperCarousel.INTERVAL_SECONDS[picked]
                    prefs.wallpaperCarouselIntervalSec = sec
                    logSetting(
                        "design_wallpaper_carousel_interval", "轮播间隔",
                        currentInterval, sec, LogTags.SETTING,
                    )
                }

                // ---------------------------------------------------------- 动态视频壁纸（日夜双模）
                section("动态视频壁纸（随日夜切换）")
                val videoOn = prefs.videoWallpaperEnabled && hasVideo
                switchRow(
                    "启用视频壁纸",
                    videoOn,
                    null,
                    height = ROW_H_TIGHT,
                ) { on ->
                    if (!hasVideo) {
                        com.cardesk.launcher.core.log.LogTools.toast(context, "请先导入视频")
                        select("wallpaper")
                        return@switchRow
                    }
                    prefs.videoWallpaperEnabled = on
                    logSetting("design_video_wallpaper_enabled", "视频壁纸", !on, on, LogTags.SETTING)
                    onVideoWallpaperChange?.invoke(on)
                }
                kvRow(
                    "日间视频",
                    if (hasVideo) {
                        "${com.cardesk.launcher.core.VideoWallpaper.sizeBytes(context) / 1024 / 1024} MB"
                    } else {
                        "未导入"
                    },
                    hasVideo,
                )
                kvRow(
                    "夜间视频",
                    if (hasNightVideo) {
                        "${com.cardesk.launcher.core.VideoWallpaper.nightSizeBytes(context) / 1024 / 1024} MB"
                    } else {
                        "未导入 · 夜间沿用日间"
                    },
                    hasNightVideo,
                )
                actionPair(
                    "导入视频…",
                    if (hasNightVideo) "更换夜间视频…" else "导入夜间视频…",
                    onLeft = { onPickVideoWallpaper?.invoke() },
                    onRight = { onPickNightVideoWallpaper?.invoke() },
                )
                actionPair(
                    "用系统选择器导入…",
                    "用系统选择器导入夜间…",
                    onLeft = { onImportVideoWallpaper?.invoke() },
                    onRight = { onImportNightVideoWallpaper?.invoke() },
                )
                if (hasVideo) {
                    actionButton("删除视频壁纸", danger = true, full = true) {
                        onDeleteVideoWallpaper?.invoke()
                    }
                }
                if (hasNightVideo) {
                    actionButton("删除夜间视频", danger = true, full = true) {
                        onDeleteNightVideoWallpaper?.invoke()
                    }
                }
            }
        }

        // 两栏都建完再落一次选中态（网格是右栏建的，必须先有 thumb 才能刷）。
        refresh(current)
    }

    /** 打开在线壁纸浏览弹窗（静态与动态壁纸均可在弹窗内直接从 R2 下载应用）。 */
    private fun openOnlineWallpaperDialog() {
        val dlg = com.cardesk.launcher.ui.dialogs.OnlineWallpaperDialog(
            context = context,
            pal = pal,
            onApplied = { kind, night ->
                onOnlineWallpaperApplied?.invoke(kind, night)
            },
        )
        dlg.onClose = {
            dlg.dismissAnimated { removeView(dlg) }
        }
        addView(dlg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 卡片风格（第五十八轮新增页）：**透明度 / 颜色 / 圆角**。
     *
     * 这一页改的是**全局 token**：
     * - **透明度** → 卡面 + 卡内两层一起变透明（壁纸透得出来）；
     * - **颜色** → 换掉卡面色，卡内两层按「浮屿」的明度差规则派生；
     * - **圆角** → 卡片圆角可调，内嵌块 / 图标底**等比缩放**。
     *
     * 改动**即时生效**：设置页自己刷新预览，同时通知桌面重算 token 并刷新画面。
     */

    // ------------------------------------------------------------- 桌面卡片（C2）

    /**
     * 桌面卡片布局（第九十五轮 C2）：每张卡的宽度档位与显隐。
     *
     * 顺序拖拽仍在桌面上长按拖动；这里只调整宽度和显隐，改完立即落盘并重排。
     */
    /**
     * 桌面卡片（第149轮：把「卡片风格」并进来）。
     *
     * ## 为什么合并
     *
     * 「卡片风格」（透明度 / 颜色 / 圆角）和「桌面卡片」（摆哪些卡、宽度、显隐）
     * 改的都是**同一批卡片的外观** —— 先定样式、再摆布局，本就是一件事的两步。
     *
     * 顺序：样式在前（上面），布局在后（下面）。
     */
    /**
     * 「桌面卡片」页（画板 `s08-cards.png`）：**两栏固定版式**。
     *
     * 左栏（541 宽）= 预览 + 卡片透明度 + 卡片圆角 + 卡片颜色 + 「恢复跟随主题颜色」；
     * 右栏（841 宽）= 每张卡一行的宽度档位 / 显示开关 + 卡片库 + 「恢复默认布局」。
     *
     * 以前是**一整条竖排**：预览 → 透明度 → 颜色 → 恢复 → 圆角 → 卡片宽度 …
     * 想同时看「预览」和「某张卡的档位」得来回滚，而画板是并排的。
     * 现在按画板分栏，两栏各自成列（[twoColumns]），互不影响。
     */
    private fun pageCards() {
        twoColumns(CARD_STYLE_COL_W) { left, right ->
            inColumn(left) { cardStyleBody() }
            inColumn(right) { cardsBody() }
        }
    }

    /**
     * 右栏：桌面卡片的尺寸档位 / 显隐 + 卡片库 + 恢复默认。
     *
     * 画板顺序：`桌面卡片` → 车况 / 音乐卡（纯开关两行）→ 6 张底部卡（档位 + 显示）
     * → `卡片库` → `恢复默认布局`。
     *
     * 车况卡与音乐卡封面各只有一个开关，没有宽度档位（车况卡是右侧固定卡、
     * 音乐卡封面是绘制开关），所以它们排在最前面、跟底部卡片行分开。
     */
    private fun cardsBody() {
        val store = com.cardesk.launcher.core.CardLayout.PrefsStore(prefs)
        // 第179轮：胎压卡已从桌面下线（CardLayout.REMOVED），设置页也不再列它。
        val layout = com.cardesk.launcher.core.CardLayout.active(
            com.cardesk.launcher.core.CardLayout.normalize(
                store.load() ?: com.cardesk.launcher.core.CardLayout.DEFAULT,
            ),
        )

        section("桌面卡片")

        // ① 车况卡（右侧固定卡，车门 / 车窗 / 引擎盖 / 后备箱俯视车模）。
        // 它**不在底部卡片行里**，所以只有「显示」一个开关，没有宽度档位。
        switchRow("车况 · 显示这张卡", prefs.carStatusCardVisible, height = CARD_SLOT_ROW_H) { on ->
            val from = prefs.carStatusCardVisible
            prefs.carStatusCardVisible = on
            logSetting("scene_car_status_card_visible", "车况卡", from, on)
        }
        switchRow("车况 · 小尺寸样式", prefs.carStatusCardCompact, height = CARD_SLOT_ROW_H) { on ->
            val from = prefs.carStatusCardCompact
            prefs.carStatusCardCompact = on
            logSetting("scene_car_status_card_compact", "车况卡小尺寸", from, on)
            onCardLayoutChanged?.invoke()
        }

        // ② 音乐卡封面开关。它和「点封面图」是**同一个键**
        // （`prefs.navCoverBackdropEnabled`）—— 设置页是发现入口，状态只有一份。
        switchRow("音乐卡 · 封面作背景", prefs.navCoverBackdropEnabled, height = CARD_SLOT_ROW_H) { on ->
            val from = prefs.navCoverBackdropEnabled
            prefs.navCoverBackdropEnabled = on
            logSetting("nav_cover_backdrop_enabled", "音乐卡封面背景", from, on)
        }

        // ③ 底部卡片行：一张卡一行（宽度档位 + 显示开关），与画板右列一一对应。
        val sizeLabels = com.cardesk.launcher.core.CardLayout.Size.entries.map { it.label }
        for (slot in layout) {
            val title = com.cardesk.launcher.core.CardLayout.title(slot.id)
            cardSlotRow(
                title = title,
                sizeLabels = sizeLabels,
                sizeLabel = slot.size.label,
                visible = slot.visible,
                onSize = { label ->
                    val size = com.cardesk.launcher.core.CardLayout.Size.entries.first { it.label == label }
                    val next = com.cardesk.launcher.core.CardLayout.withSize(layout, slot.id, size)
                    store.save(next)
                    onCardLayoutChanged?.invoke()
                    select(selectedKey)
                },
                onVisible = { on ->
                    val next = com.cardesk.launcher.core.CardLayout.withVisible(layout, slot.id, on)
                    if (next != layout) {
                        store.save(next)
                        onCardLayoutChanged?.invoke()
                    }
                    // 显隐会影响「卡片库」里有没有它 —— 重建这一页。
                    select(selectedKey)
                },
            )
        }

        // 卡片库：**只列没上桌的卡**（画板口径：显示=关的卡才在这里）。
        // 已显示的卡不会重复出现在这里 —— `hidden = layout.filter { !it.visible }`
        // 就是这条规则的唯一实现，别改成遍历全量再用 visible 判断。
        val hidden = layout.filter { !it.visible }
        if (hidden.isNotEmpty()) {
            section("卡片库")
            // 画板把「添加…」做成两颗**并排的窄按钮**（不是整行大按钮）。
            val libRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (slot in hidden) {
                val btn = makeButton(
                    "添加「${com.cardesk.launcher.core.CardLayout.title(slot.id)}」卡",
                    primary = false,
                    danger = false,
                    full = false,
                )
                btn.setOnClickListener {
                    val next = com.cardesk.launcher.core.CardLayout.withVisible(layout, slot.id, true)
                    store.save(next)
                    onCardLayoutChanged?.invoke()
                    select(selectedKey)
                }
                libRow.addView(btn, LinearLayout.LayoutParams(-2, CARD_LIB_BTN_H).also {
                    it.topMargin = 8
                    // 按钮**必须**有下外边距：SettingsDropdownTest 的
                    // 「每一页的按钮底下都有间距」递归检查每个按钮的 bottomMargin。
                    it.bottomMargin = 12
                    if (libRow.childCount > 0) it.leftMargin = BUTTON_GAP
                })
            }
            hostCol.addView(libRow, LinearLayout.LayoutParams(-1, -2))
            // 画板这一行说明不是白名单文案（[note] 会被 SettingsCopyTest 数进去），走 noteRaw。
            noteRaw("卡片库仅列出未上桌（显示=关）的卡片；快捷应用、车门车窗默认收起。")
        }
        actionButton("配置快捷应用卡…", full = true) {
            Logx.event(LogTags.SETTING, "打开桌面快捷应用配置")
            onShowSceneQuickApps?.invoke()
        }
        actionButton("恢复默认布局", full = true) {
            store.save(com.cardesk.launcher.core.CardLayout.DEFAULT)
            prefs.carStatusCardPosX = -1f
            prefs.carStatusCardPosY = -1f
            prefs.carStatusInBottomRow = false
            prefs.carStatusBottomIndex = -1
            prefs.cardFreePositions = emptyMap()
            onCardLayoutChanged?.invoke()
            select(selectedKey)
        }
    }

    /**
     * 画板 s08 右列的一行：标题 + 宽度档位胶囊 + 「显示」+ 开关。
     *
     * 和 [chipRow] / [switchRow] 都不同：这一行右边同时有**两组**控件
     * （互斥的档位 + 布尔开关），所以单独拼一个 `FrameLayout` 行。
     */
    private fun cardSlotRow(
        title: String,
        sizeLabels: List<String>,
        sizeLabel: String,
        visible: Boolean,
        onSize: (String) -> Unit,
        onVisible: (Boolean) -> Unit,
    ) {
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, title, ROW_TITLE, pal.text, bold = true),
            FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL),
        )
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        right.addView(
            buildSegment(sizeLabels, sizeLabels.indexOf(sizeLabel), compact = true) { index ->
                onSize(sizeLabels[index])
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        right.addView(
            Ui.text(context, "显示", ROW_SUB, pal.textDim),
            LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = 16 },
        )
        val toggle = BoardSwitch(context)
        toggle.tint = pal.accent
        toggle.checked = visible
        toggle.setOnClickListener {
            toggle.toggle()
            Motion.bump(toggle)
            onVisible(toggle.checked)
        }
        right.addView(toggle, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = 12 })
        holder.addView(
            right,
            FrameLayout.LayoutParams(-2, -1, Gravity.END or Gravity.CENTER_VERTICAL),
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, CARD_SLOT_ROW_H))
        hairline()
    }

    /**
     * 左栏：卡片风格（预览 + 透明度 + 圆角 + 颜色 + 恢复主题色）。
     *
     * 顺序对齐画板 `s08-cards.png` 左列：**预览 → 透明度 → 圆角 → 颜色 → 恢复**。
     * 以前是「透明度 → 颜色 → 恢复 → 圆角」，圆角被挤到最底下；
     * 画板把两个滑杆挨在一起（都是「形状」参数），颜色与恢复在下面。
     *
     * ⚠️ 顺序不能随意动：`CardStyleSettingsTest` 用索引取滑块 ——
     * `bars[0]` 必须是透明度、`bars[1]` 必须是圆角，且整页只能有 2 个 SeekBar。
     */
    private fun cardStyleBody() {
        section("预览")
        val preview = CardStylePreviewView(context)
        hostCol.addView(preview, LinearLayout.LayoutParams(-1, 260).also { it.topMargin = 10 })

        /** 把当前偏好算成生效的配色 + 圆角：刷新预览，并通知桌面全局生效。 */
        fun push() {
            val effective = com.cardesk.launcher.core.CardStyle.apply(
                pal,
                prefs.cardColor,
                prefs.cardOpacity,
                prefs.cardRadius,
            )
            preview.palette = effective
            preview.radius = com.cardesk.launcher.core.CardStyle.clampRadius(prefs.cardRadius).toFloat()
            preview.wallpaperIndex = prefs.wallpaper
            preview.refresh()
            onCardStyleChange?.invoke()
        }
        push()

        sliderRow(
            "卡片透明度",
            prefs.cardOpacity,
            com.cardesk.launcher.core.CardStyle.MIN_OPACITY,
            com.cardesk.launcher.core.CardStyle.MAX_OPACITY,
            "%",
            5,
        ) { v ->
            val from = prefs.cardOpacity
            prefs.cardOpacity = v
            logSetting("card_opacity", "卡片透明度", from, v)
            push()
        }

        sliderRow(
            "卡片圆角",
            prefs.cardRadius,
            com.cardesk.launcher.core.CardStyle.MIN_RADIUS,
            com.cardesk.launcher.core.CardStyle.MAX_RADIUS,
            " px",
            2,
        ) { v ->
            val from = prefs.cardRadius
            prefs.cardRadius = v
            logSetting("card_radius", "卡片圆角", from, v)
            push()
        }

        pickerRow("卡片颜色", cardColorLabel()) { onShowCardColor?.invoke() }
        actionButton("恢复跟随主题颜色", full = true) {
            val from = prefs.cardColor
            prefs.cardColor = 0
            logSetting("card_color", "卡片颜色", from, 0)
            push()
        }
    }
    /** 卡片颜色的当前取值文案。 */
    private fun cardColorLabel(): String {
        val c = prefs.cardColor
        return if (c == 0) "跟随主题" else String.format("#%06X", c and 0xFFFFFF)
    }

    /** 双画中画。 */
    private fun pageDualPip() {
        switchRow("双画中画", prefs.dualPip) {
            val from = prefs.dualPip
            //
            // 开双画时主副画不能是同一个应用，否则拒绝并提示。
            if (it && !prefs.pipApp.isBlank() && prefs.pipApp == prefs.pip2App) {
                Toast.makeText(context, "双画中画请选择不同应用", Toast.LENGTH_SHORT).show()
                Logx.w(
                    LogTags.SETTING,
                    "开启双画中画被拒：主副画是同一个应用",
                    "pkg" to prefs.pipApp,
                )
                return@switchRow
            }
            prefs.dualPip = it
            logSetting("desk_dual_pip", "双画中画", from, it)
        }
        //
        // 两行分别是「一画显示应用」「二画显示应用」，右侧显示**应用名**（不是包名），
        // 没设置时显示「未设置」。
        pickerRow("一画显示应用", pipPickerValue(prefs.pipApp)) {
            // 排障场景：画中画应用选不了 / 选错 —— 记下打开了选择器
            Logx.event(LogTags.SETTING, "打开画中画应用选择器", "current" to prefs.pipApp, "secondary" to false)
            onShowPipPicker?.invoke(false)
        }
        pickerRow("二画显示应用", pipPickerValue(prefs.pip2App)) {
            Logx.event(LogTags.SETTING, "打开画中画应用选择器", "current" to prefs.pip2App, "secondary" to true)
            onShowPipPicker?.invoke(true)
        }
    }

    /**
     * 画中画选择器右侧的当前值
     * 显示**应用名**，没设置时显示「未设置」。
     */
    private fun pipPickerValue(pkg: String): String =
        if (pkg.isBlank()) "未设置" else (appLabelOf(pkg) ?: pkg)

    /**
     * Dock 快捷应用（第五十四轮）。
     *
     * Dock 最右边是一段快捷应用槽位（默认 3 个），这里进配置面板挑应用；
     * 也可以直接**长按 Dock 上的某个槽位**进同一个面板。
     */
    private fun quickAppsBody() {
        section("Dock 快捷应用")
        val assigned = prefs.dockQuickApps.filter { it.isNotEmpty() }
        // 画板 s11 只有「0 / 2 个」这一种写法。
        // 以前空集时把**值**写成「未配置」，于是行上并排出现「已配置 | 未配置」——
        // 两个状态互相矛盾（验收报告点名的 s11 问题之一）。现在永远给「N / M 个」。
        kvRow(
            "已配置",
            "${assigned.size} / ${com.cardesk.launcher.core.DockQuickApps.SLOTS} 个",
            assigned.isNotEmpty(),
        )
        actionButton("配置 Dock 快捷应用…", primary = true, full = true) {
            Logx.event(
                LogTags.SETTING,
                "打开 Dock 快捷应用配置",
                "current" to prefs.dockQuickApps.joinToString(","),
            )
            onShowDockQuickApps?.invoke()
        }
    }

    /**
     * 让 360 联动按**刚落盘的新配置**重新决策一次。
     *
     * 联动逻辑按 `(总开关|挡位|雷达)` 去重（见 `vehicle/Camera360Policy.stateKey`），
     * 所以改「倒车视角 / 视角延迟 / N·P 关闭 / 雷达」这类配置时它不会自己再跑一遍；
     * 不刷的话，用户改完配置得再挂一次挡才生效，看起来像「设置没用」。
     *
     * 失败/未启动都无所谓：`refresh()` 在链路没起来时直接 return。
     */
    private fun refreshCamera360Link() {
        runCatching { com.cardesk.launcher.vehicle.Camera360Link.refresh() }
            .onFailure { Logx.w(LogTags.CAM360, "刷新 360 联动失败", it) }
    }

    /** 360 环影。 */
    private fun pageCamera360() {
        // 第154轮：下面「视角延迟 / 按键视角」是**跟着总开关条件渲染**的
        // （关着就 return，不画）。可总开关原来只写偏好、不重建本页，
        // 于是用户拨开开关，眼前什么都没变 —— 得切走再切回才看得到子项，
        // 很容易被当成「这一页坏了」。
        // 这一页恰好是本轮刚收敛过的（5 个假选项被删了），剩下的两项如果还藏着，
        // 等于把唯一能用的东西也藏起来了，所以这里跟着重建本页。
        // 用法和文件里其它「开关影响本页内容」的地方一致（如「显示这张卡」）。
        switchRow("启用 360 环影增强", prefs.camera360Enabled) {
            val from = prefs.camera360Enabled
            prefs.camera360Enabled = it
            logSetting("camera360_enabled_v2", "启用 360 环影增强", from, it)
            // 联动逻辑按 (总开关|挡位|雷达) 去重，改配置本身不会触发决策，
            // 所以要显式刷一次，否则「刚打开开关、正在倒车」这一挂不会补动。
            refreshCamera360Link()
            select(selectedKey)
        }
        if (!prefs.camera360Enabled) {
            return
        }
        chipRow("视角延迟", CAMERA360_DELAY_LABELS, CAMERA360_DELAY_MS.indexOf(prefs.camera360DelayMs)) { idx ->
            val from = prefs.camera360DelayMs
            val to = CAMERA360_DELAY_MS[idx]
            prefs.camera360DelayMs = to
            logSetting("camera360_view_delay_ms", "视角延迟", from, to)
            refreshCamera360Link()
        }
        chipRow("按键视角", CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(prefs.camera360KeyView)) { idx ->
            val from = prefs.camera360KeyView
            val to = CAMERA360_VIEW_IDS[idx]
            prefs.camera360KeyView = to
            logSetting("camera360_key_view_v2", "按键视角", from, to)
        }
        // 第三百一十轮：下面这些视角/联动项**真的有执行端**了 ——
        // `vehicle/Camera360Link` 订阅挡位与雷达信号，按这里的配置决定「开环影 + 切哪个视角」。
        // 以前它们只写偏好、无人读，所以整段被收起来过；现在补回并接到执行端。
        //
        // 视角行统一用 `-1 = 关` 的选项条（与「按键视角」同一套 `CAMERA360_VIEW_IDS`）。
        fun viewRow(label: String, key: String, value: Int, apply: (Int) -> Unit) {
            chipRow(label, CAMERA360_VIEW_LABELS, CAMERA360_VIEW_IDS.indexOf(value)) { idx ->
                val to = CAMERA360_VIEW_IDS[idx]
                apply(to)
                logSetting(key, label, value, to)
                refreshCamera360Link()
            }
        }
        viewRow("倒车视角", "camera360_reverse_view_v2", prefs.camera360ReverseView) {
            prefs.camera360ReverseView = it
        }
        viewRow("R→D 视角", "camera360_rd_view_v2", prefs.camera360RdView) {
            prefs.camera360RdView = it
        }
        viewRow("D 挡视角", "camera360_d_view_v2", prefs.camera360DView) {
            prefs.camera360DView = it
        }
        // 「转向灯视角」这一行**不放出来**：车机侧目前没有可验证的转向灯信号通路
        // （`camera360LampView` 这个偏好字段保留着，等有了信号再连）。
        // 设置页的口径是「写进去必须有人读」，放一条没人读的项比没有更糟。
        switchRow("N/P 挡关闭环影", prefs.camera360NpClose) {
            val from = prefs.camera360NpClose
            prefs.camera360NpClose = it
            logSetting("camera360_np_close_v2", "N/P 挡关闭环影", from, it)
            refreshCamera360Link()
        }
        // 雷达图形触发是**新增**的通用偏好键（不占 Prefs.kt 的字段位）：默认开，
        // 车机在低速贴障碍物时给出雷达图形信号 → 自动开环影。
        switchRow("雷达图形触发", prefs.flag("camera360_radar_open", true)) {
            val from = prefs.flag("camera360_radar_open", true)
            prefs.setFlag("camera360_radar_open", it)
            logSetting("camera360_radar_open", "雷达图形触发", from, it)
            refreshCamera360Link()
        }
        // 调试用：真车挡位来自属性/信号通道，模拟器上这些信号不存在。
        // 这一行直接喂一次挡位事件给联动逻辑（只影响这一次判定，不改车辆状态），
        // 方便在没有 IBS 数据的环境里验证「挂挡 → 开环影 + 切视角」整条链路。
        chipRow(
            "模拟挡位（调试）",
            listOf("P", "R", "N", "D"),
            prefs.stringValue("camera360_sim_gear", "P"),
        ) { gear ->
            prefs.setStringValue("camera360_sim_gear", gear)
            Logx.event(LogTags.CAM360, "模拟挡位", "gear" to gear)
            com.cardesk.launcher.vehicle.Camera360Link.simulateGear(gear)
        }
        // 雷达图形同理：模拟车辆通道只会回显自己写下去的值，没有「上游出现图形」，
        // 所以给一个调试开关直接喂边沿，用来验证「低速贴障碍物 → 自动开环影」那条规则。
        switchRow("模拟雷达（调试）", prefs.flag("camera360_sim_radar", false)) {
            val from = prefs.flag("camera360_sim_radar", false)
            prefs.setFlag("camera360_sim_radar", it)
            Logx.event(LogTags.CAM360, "模拟雷达", "on" to it)
            com.cardesk.launcher.vehicle.Camera360Link.simulateRadar(it)
        }
    }

    /**
     * 启停控制（case "autostop"）。
     *
     * ## 这一页现在**真的有执行端**
     *
     * 以前这里只有一个开关：把 `iss_keep_enabled` 写进 SharedPreferences 就完事，
     * **全仓没有任何代码读它** —— 开关能切、能持久化，车机的启停一点没变。
     *
     * 现在接上了：
     * - 功能号 `537002240`（`0x20020100`，
     *   `IVehicle.SETTING_FUNC_ENGINE_STOP_START`），厂商 jar 里读、写都实现
     *   （见 `com.cardesk.launcher.vehicle.IssControl`）；
     * - 打开开关 → **立刻**把启停关掉（写完回读确认），开机时再关一次；
     * - 关闭开关 → **不动车机**（副标写的「关闭则不改动车机逻辑」），
     *   想改回来用下面的「立即开启启停」。
     *
     * ## 读侧有个坑：默认值是 1
     *
     * 厂商 `getMappedFunctionValue(PaSsActivation)` 在信号缺失时返回 **1**，
     * 所以「读到 1」分不清「开着」和「根本读不到」。
     * 状态行只在**读到 0**（确凿关闭）或**读到 1 且固件确认有这条信号**时才下结论，
     * 否则老老实实说「读不到」。
     */
    private fun pageAutostop() {
        section("启停")

        // 状态行要在写完之后原地刷新，所以把视图留出来。
        var stateView: TextView? = null
        fun refreshState() {
            stateView?.let { readIssStateAsync(it) }
        }

        switchRow("开机后自动关闭启停", prefs.issKeepEnabled, "开启后保持 ISS 关闭；关闭则不改动车机逻辑") {
            val from = prefs.issKeepEnabled
            prefs.issKeepEnabled = it
            logSetting("iss_keep_enabled", "开机后自动关闭启停", from, it)
            if (it) {
                // 打开 = 用户现在就要关掉启停，立刻下发，不等下次开机。
                writeIss(false) { refreshState() }
            } else {
                Toast.makeText(
                    context,
                    "已关闭「开机后自动关闭启停」，车机启停不再被改动",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        section("车机当前状态")
        stateView = kvRow("启停", "读取中…", false)
        actionButton("立即关闭启停", primary = true, full = true) {
            writeIss(false) { refreshState() }
        }
        actionButton("立即开启启停", full = true) {
            writeIss(true) { refreshState() }
        }
        actionButton("刷新状态", full = true) {
            refreshState()
        }

        refreshState()
    }

    /**
     * 写一次启停并给用户一个明确说法。
     *
     * 写车是同步 binder，放在后台线程；
     * [com.cardesk.launcher.vehicle.VehicleController.setIssEnabled] 已经负责
     * 切回主线程，这里只在主线程上弹 Toast + 刷新状态行。
     */
    private fun writeIss(on: Boolean, after: () -> Unit) {
        Logx.event(LogTags.SETTING, "写启停", "target" to if (on) "开启" else "关闭")
        com.cardesk.launcher.vehicle.VehicleController.setIssEnabled(on) { ok, msg ->
            // 失败要看得久一点——原因就一句话，短一闪而过等于没说。
            Toast.makeText(context, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            after()
        }
    }

    /**
     * 读一次启停状态并写进行里。
     *
     * 读也是 binder 调用，必须放后台线程。
     *
     * 颜色跟着结论走：确凿关掉是绿的，开着是正常色，**判不了是弱文字**——
     * 判不了的时候不能给一个看着像「有结论」的颜色。
     */
    private fun readIssStateAsync(view: TextView) {
        Thread {
            val state = com.cardesk.launcher.vehicle.VehicleController.readIssState()
            val text: String
            val color: Int
            when {
                state == null -> {
                    text = "车控通道未就绪"
                    color = pal.textDim
                }
                state.isOn == false -> {
                    text = state.label()
                    color = pal.ok
                }
                state.isOn == true -> {
                    text = state.label()
                    color = pal.text
                }
                else -> {
                    text = state.label()
                    color = pal.textDim
                }
            }
            post {
                view.text = text
                view.setTextColor(color)
            }
        }.apply {
            name = "iss-state"
            isDaemon = true
        }.start()
    }

    /** 自动热点（case "hotspot"）。 */
    private fun pageHotspot() {
        val statusBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun renderStatus() {
            statusBox.removeAllViews()
            val prevHost = columnHost
            columnHost = statusBox
            try {
                val apState = com.cardesk.launcher.core.HotspotController.getApState(context)
                val active = com.cardesk.launcher.core.HotspotController.isActiveState(apState)
                val ssid = com.cardesk.launcher.core.HotspotController.readSsid(context)
                val stateText = buildString {
                    append(com.cardesk.launcher.core.HotspotController.stateLabel(apState))
                    if (!ssid.isNullOrEmpty()) append(" · ").append(ssid)
                }
                kvRow("当前热点状态", stateText, ok = active)
                val lastResult = prefs.stringValue(com.cardesk.launcher.core.HotspotController.KEY_AUTO_LAST_RESULT, "")
                kvRow(
                    "最近自动执行",
                    if (lastResult.isEmpty()) "尚未执行" else lastResult,
                    ok = lastResult.contains("已开启"),
                )
            } finally {
                columnHost = prevHost
            }
        }

        switchRow("自动热点", ScheduledTasks.isHotspotAutoEnabled(prefs)) {
            val from = ScheduledTasks.isHotspotAutoEnabled(prefs)
            ScheduledTasks.setHotspotAutoEnabled(prefs, it)
            logSetting(ScheduledTasks.KEY_HOTSPOT_AUTO, "自动热点", from, it)
        }
        section("当前状态")
        hostCol.addView(statusBox, LinearLayout.LayoutParams(-1, -2))
        renderStatus()
        section("手动控制")
        chipRow("立即操作", listOf("开启热点", "关闭热点"), -1) { idx ->
            val enable = idx == 0
            com.cardesk.launcher.core.HotspotController.setEnabledAsync(context, enable, "设置页手动操作") { res ->
                Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                renderStatus()
            }
        }
    }

    /**
     * ADB 调试页（第五十五轮）。
     *
     * 原来这一页只有一个「自动 ADB」开关（写 `adb_enabled` 一个键）。
     * 这一轮把「领克 05 调试工具 2.0」逆出来的**完整无线 ADB 链路**整合进来
     *
     * - **状态**：身份 / 开发者选项 / USB 调试 / 开发者页面 / ADB 服务 /
     *   无线端口 / 本机 5555 / 本机 IPv4 / 恢复记录 / 前置检查；
     * - **自动开关**：开机自动开调试开关、开机自动开无线端口；
     * - **操作**：开启 USB 调试 / 开启无线 ADB(5555) / 关闭无线并恢复端口 /
     *   恢复 USB 接口 / 恢复调试开关 / **恢复全部设置** / 打开原生开发者选项；
     * - **诊断**：属性清单 + USB 功能 + SELinux 上下文 + 崩溃日志，可复制。
     *
     * 「恢复全部设置」是**安全出口**：无线 ADB 开着的时候同网段任何设备都能连上车机，
     * 所以它必须显眼、好找，而且失败要如实报。
     */
    private fun pageAdb() {
        val runtime = com.cardesk.launcher.core.AdbBridge.AdbRuntime
        val controller = runtime.controller(context)
        var state = runtime.state(context)
        var support = runtime.support(context)

        // 状态行绑定：操作完成后**原地刷新**取值，不重建页面（免得滚动位置被重置）。
        val bindings = ArrayList<Pair<TextView, () -> Pair<String, Boolean>>>()

        fun live(label: String, provider: () -> Pair<String, Boolean>) {
            val (value, ok) = provider()
            bindings += kvRow(label, value, ok) to provider
        }

        fun refreshState() {
            state = runtime.state(context)
            support = runtime.support(context)
            for ((view, provider) in bindings) {
                val (value, ok) = provider()
                view.text = value
                view.setTextColor(if (ok) pal.ok else pal.textDim)
            }
        }

        // 画板 s20 是**两栏**：左「当前状态 / 自动开关」，右「操作 / 诊断」。
        // 单列排下来的实测后果（终验 B-1）：「操作」标题落到 y=984，
        // 第一个按钮被面板底裁得只剩 2px —— 首屏一个可点的按钮都没有。
        twoColumns(ADB_LEFT_W, ADB_COL_GAP) { left, right ->
            inColumn(left) {
        section("当前状态")
        live("身份") {
            val identity = "uid ${state.uid}" + if (state.platformSigned) " · 平台签名" else " · 非平台签名"
            identity to (state.uid == 1000)
        }
        live("开发者选项") { AdbDiagnostics.settingLabel(state.devSetting) to (state.devSetting == "1") }
        live("USB 调试") { AdbDiagnostics.settingLabel(state.adbSetting) to (state.adbSetting == "1") }
        live("开发者页面") {
            AdbDiagnostics.componentLabel(state.componentState) to state.componentEnabled
        }
        live("ADB 服务") {
            (if (state.adbdService.isEmpty()) "<空>" else state.adbdService) to (state.adbdService == "running")
        }
        live("无线端口") {
            (if (state.wirelessPort.isEmpty()) "未配置" else state.wirelessPort) to
                (state.wirelessPort == AdbDebug.WIRELESS_PORT)
        }
        live("本机 5555") { (if (state.portOpen) "接受连接" else "未接受连接") to state.portOpen }
        live("本机 IPv4") { state.localIp to true }
        live("恢复记录") {
            state.restoreSummary to (state.hasDebugSnapshot || state.hasUsbSnapshot || state.hasPortSnapshot)
        }
        live("前置检查") { support.reason to support.ok }

        section("自动开关")
        switchRow("自动 ADB", prefs.adbAuto) {
            val from = prefs.adbAuto
            prefs.adbAuto = it
            logSetting("adb_boot_auto", "自动 ADB", from, it)
            if (it) {
                // 立刻生效一次（开机自启由 BootReceiver 负责）
                runAdbAction("自动 ADB") { controller.enableDebug() }
            }
        }
        switchRow("无线 ADB 自启", prefs.adbWirelessAuto) {
            val from = prefs.adbWirelessAuto
            prefs.adbWirelessAuto = it
            logSetting("adb_wireless_boot_auto", "无线 ADB 自启", from, it)
            if (it) {
                runAdbAction("无线 ADB 自启") { controller.enableWirelessSession() }
            }
        }
            }
            inColumn(right) {

        section("操作")
        actionButton("开启 USB 调试", primary = true, full = true) {
            runAdbAction("开启 USB 调试") { controller.enableUsbSession() }
        }
        actionButton("开启无线 ADB（5555）", primary = true, full = true) {
            runAdbAction("开启无线 ADB") { controller.enableWirelessSession() }
        }
        actionButton("关闭无线并恢复端口", full = true) {
            runAdbAction("关闭无线并恢复端口") { controller.restoreWirelessSession() }
        }
        actionButton("恢复 USB 接口", full = true) {
            runAdbAction("恢复 USB 接口") { controller.restoreUsb() }
        }
        actionButton("恢复调试开关", full = true) {
            runAdbAction("恢复调试开关") { controller.restoreDebug() }
        }
        actionButton("恢复全部设置", danger = true, full = true) {
            runAdbAction("恢复全部设置") { controller.restoreAll() }
        }
        actionButton("打开原生开发者选项", full = true) {
            Logx.event(LogTags.SETTING, "打开原生开发者选项")
            val ok = runtime.openDevSettings(context)
            if (!ok) {
                Logx.w(LogTags.SYS, "打开原生开发者选项失败", "component" to AdbDebug.COMPONENT_ACTIVITY)
                Toast.makeText(context, "打不开原生开发者选项页", Toast.LENGTH_SHORT).show()
            }
        }

        section("诊断")
        // 画板 s20：**折叠态只有一条「展开诊断详情」**。
        // 结果文本、属性清单、刷新/复制都只在展开后出现 ——
        // 折叠着还摆一排按钮的话，这页看着像「诊断工具」，
        // 而它其实是 ADB 调试开关页；诊断只是补充信息。
        val collapsed = if (adbDiagnosticsExpanded) View.VISIBLE else View.GONE
        val resultView = Ui.text(context, adbLastAction, 16f, pal.text).apply {
            setLineSpacing(8f, 1f)
            setTextIsSelectable(true)
            visibility = collapsed
        }
        hostCol.addView(resultView, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
        // 诊断详情默认**收起**：整页属性清单 + 崩溃日志有几十行，
        // 摊开会把这一页糊成一堵字墙（APK 也是默认收起、点一下才展开）。
        val diagView = Ui.text(context, adbDiagnostics, 14f, pal.textDim).apply {
            setLineSpacing(6f, 1f)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            visibility = collapsed
        }
        hostCol.addView(diagView, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 12 })
        // 刷新 / 复制：跟着详情一起显隐（折叠态整组不占空间）。
        val refreshBtn = actionButton("刷新诊断", full = true) {
            adbDiagnostics = runtime.diagnostics(context)
            diagView.text = adbDiagnostics
            refreshState()
            Toast.makeText(context, "诊断已刷新", Toast.LENGTH_SHORT).show()
        }
        val copyBtn = actionButton("复制诊断", full = true) {
            val report = runtime.report(context, adbLastAction)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CarDesk ADB 诊断", report))
                Toast.makeText(context, "诊断已复制到剪贴板", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "拿不到剪贴板服务", Toast.LENGTH_SHORT).show()
            }
        }
        refreshBtn.visibility = collapsed
        copyBtn.visibility = collapsed
        actionButton(
            if (adbDiagnosticsExpanded) "收起诊断详情" else "展开诊断详情",
            full = true,
        ) {
            adbDiagnosticsExpanded = !adbDiagnosticsExpanded
            val v = if (adbDiagnosticsExpanded) View.VISIBLE else View.GONE
            diagView.visibility = v
            resultView.visibility = v
            refreshBtn.visibility = v
            copyBtn.visibility = v
            select(selectedKey)
        }

        // 操作完成后回调：原地刷新状态行 + 结果文本 + 诊断文本（不重建页面，滚动位置不跳）
        adbOnResult = { outcome ->
            adbLastAction = if (outcome.detail.isEmpty()) {
                outcome.title
            } else {
                "${outcome.title}\n${outcome.detail}"
            }
            adbDiagnostics = runtime.diagnostics(context)
            resultView.text = adbLastAction
            diagView.text = adbDiagnostics
            refreshState()
        }
            }
        }
    }

    /**
     * 在后台线程跑一次 ADB 操作，结束后回主线程刷新状态。
     *
     * 为什么必须放后台：写系统设置、切 USB 功能位、重启 adbd、等端口，
     * 任何一步都可能卡住几百毫秒到一秒多，堵在主线程上会让整页假死。
     */
    private fun runAdbAction(label: String, block: () -> AdbOutcome) {
        Logx.event(LogTags.SETTING, "ADB 操作", "action" to label)
        Thread {
            val outcome = runCatching(block).getOrElse {
                AdbOutcome(false, "操作未完成", AdbDiagnostics.describe(it))
            }
            com.cardesk.launcher.core.AdbBridge.AdbRuntime.logOutcome(label, outcome)
            post {
                adbOnResult?.invoke(outcome)
                Toast.makeText(context, outcome.title, Toast.LENGTH_LONG).show()
            }
        }.apply {
            name = "adb-action"
            isDaemon = true
        }.start()
    }

    /** 仪表歌词 + 桌面歌词字号 + 胎压。 */
    /**
     * 桌面歌词（case "desktoplyric"）。
     *
     * ## 为什么单独开一页（第一百三十七轮）
     *
     * 以前这些设置和仪表歌词、壁纸、胎压、车模挤在同一个页面里
     * （当时那个页面的导航名字叫「仪表歌词」，内容却大半是桌面歌词），
     * 仪表歌词的真正入口还藏在页面最底下那个「打开布局参数对话框…」按钮里 ——
     * **入口名字和内容对不上**，用户根本不知道去哪找。
     *
     * 第一百三十七轮起拆成三页，各归各位：
     * - 「桌面歌词」= 这一页（桌面**自己**屏幕上显示的歌词）；
     * - 「仪表歌词」= [pageClusterLyric]（**投到副屏**的歌词）；
     * - 「车辆信息」= [pageCarInfo]（胎压 / 车模 / 数据源）。
     */
    /**
     * 歌词（第149轮：把「桌面歌词」和「仪表歌词」并成一页）。
     *
     * ## 为什么合并
     *
     * 两个页面都是**歌词**，用户心智里就是一件事：
     * 「歌词长什么样、放在哪」。
     *
     * - 桌面歌词 = 桌面正中那坨大字 + Dock 导航栏里的歌词槽；
     * - 仪表歌词 = 投到副屏（仪表 / 副驾屏）的那块面板。
     *
     * 两边都是「字号 / 位置 / 布局 / 颜色」这一套，拆成两页反而要来回对照。
     *
     * 合并后**左右两栏**（画板 `s09-lyrics.png`）：
     * 左栏 = 首页中央歌词（布局 / 字号缩放 / 预览）+ 面板位置与样式；
     * 右栏 = 投射 + 颜色、字号与背景。
     * 旧版是上下一整列（桌面那半在上、仪表那半在下），画板根本不长这样。
     */
    private fun pageLyric() {
        twoColumns(LYRIC_COL_W, LYRIC_COL_GAP) { left, right ->
            inColumn(left) { desktopLyricBody() }
            inColumn(right) { clusterLyricBody() }
        }
    }

    private fun desktopLyricBody() {
        // 第154轮：原来这一节开头有四个滑杆 ——「收起·主歌词 / 收起·副歌词 /
        // 展开·主歌词 / 展开·副歌词」，审计发现它们**写进偏好后没有任何代码读**：
        // 导航栏歌词槽的实际绘制在 NavSlotBarView.drawLyric()，字号是写死的
        // （当前行 12f * u、下一行 10f * u），跟偏好没有关系。
        //
        // 四个滑杆已删。落盘键 `desk_desk_compact_main_sp` 等在 Prefs.kt 里保留，
        // 将来让歌词槽真的读偏好，可以直接接回去。
        //
        // 下面「首页中央歌词」是**另一套画面**（桌面正中那坨大字），
        // 它的字号（第114轮起）接的是 `scene_lyric_scale_pct`，是活的，不受影响。
        section("首页中央歌词")
        // —— 布局选择（第117轮）——
        //
        // 摆在预览条**上面**：换布局时预览立刻跟着变，用户当场看到差别，
        // 不用关掉设置面板再回桌面看。
        //
        // 存的是布局的**键**（three / ktv）而不是下标 —— 下标会随着以后加布局而错位。
        val layoutOptions = com.cardesk.launcher.core.media.LyricLayout.values().toList()
        var preview: com.cardesk.launcher.ui.widget.LyricScalePreviewView? = null
        // 说明文案要跟着布局变 —— 但它在 chipRow **下面**，
        // 所以这里先声明、chipRow 之后再建（Kotlin 不允许向前引用局部 val）。
        lateinit var hintView: TextView
        // 位置说明也要跟着布局变（三行是「整块居中」，KTV 两行是「错落定死」）——
        // 同样留着 TextView，切布局时就地改。
        lateinit var positionNoteView: TextView
        chipRow(
            "歌词布局",
            layoutOptions.map { it.label },
            layoutOptions.indexOfFirst { it.key == prefs.sceneLyricLayout }.coerceAtLeast(0),
        ) { idx ->
            val from = prefs.sceneLyricLayout
            val to = layoutOptions[idx].key
            prefs.sceneLyricLayout = to
            logSettingDebug("scene_lyric_layout", "首页中央歌词布局", from, to)
            preview?.let { p ->
                p.layout = layoutOptions[idx]
                p.lyricLines = lyricPreviewLines?.invoke(layoutOptions[idx])
                    ?: com.cardesk.launcher.ui.widget.LyricScalePreviewView.defaultLines(layoutOptions[idx])
            }
            hintView.text = layoutOptions[idx].hint
            positionNoteView.text = layoutOptions[idx].positionHint
            onSceneLyricChanged?.invoke()
        }
        // 说明文案跟着布局变（KTV 两行的说明跟三行不是一句话）——
        // 所以这里留着 TextView，切布局时就地改（见下面的 onPick）。
        hintView = note(layoutOptions.firstOrNull { it.key == prefs.sceneLyricLayout }?.hint ?: "")
        // 第115轮：滑杆上面加一条**实时预览**。
        //
        // 为什么必须有：设置面板把桌面正中那块歌词整个挡住了，
        // 拖滑杆时用户在背后根本看不到变化 —— 只能关掉面板再回来看一次，
        // 来回折腾好几轮才调得出来。
        //
        // 预览走的是**桌面同一份排版**（`LyricBlockLayout` + `LyricLayout`）+
        // 同一套字号常量，所以「字多大 / 一句能显示几个字 / 块居不居中 / 哪种排法」
        // 全都是真的。
        val curLayout = com.cardesk.launcher.core.media.LyricLayout.fromKey(prefs.sceneLyricLayout)
        preview = com.cardesk.launcher.ui.widget.LyricScalePreviewView(context).apply {
            layout = curLayout
            scale = prefs.sceneLyricScalePct / 100f
            lyricLines = lyricPreviewLines?.invoke(curLayout)
                ?: com.cardesk.launcher.ui.widget.LyricScalePreviewView.defaultLines(curLayout)
        }
        hostCol.addView(preview, LinearLayout.LayoutParams(-1, LYRIC_PREVIEW_H).also { it.topMargin = 10 })
        sliderRow("字号缩放", prefs.sceneLyricScalePct, 70, 140, "%", step = 5) { v ->
            val from = prefs.sceneLyricScalePct
            prefs.sceneLyricScalePct = v
            logSettingDebug("scene_lyric_scale_pct", "首页中央歌词字号", from, v)
            preview?.scale = v / 100f
            onSceneLyricChanged?.invoke()
        }
        positionNoteView = note(
            layoutOptions.firstOrNull { it.key == prefs.sceneLyricLayout }?.positionHint ?: "",
        )
    }

    /**
     * 仪表歌词（case "clusterlyric"）。
     *
     * ## 这一页是什么
     *
     * 把歌词**投到副屏**（仪表 / 副驾屏）上显示 —— 与 [pageDesktopLyric]
     * 的桌面歌词是**两套完全不同的画面**：
     *
     * | | 桌面歌词 | 仪表歌词 |
     * |---|---|---|
     * | 显示在哪 | 桌面自己的屏幕 | **另一块屏**（副屏） |
     * | 数据来源 | 桌面卡片 | 同一个 `MusicController`（同源） |
     * | 字号键 | `desk_desk_*_sp` | `desk_overlay_main_sp` / `desk_overlay_sub_sp` |
     * | 颜色 | 跟随桌面主题 | **跟随日夜两套**（见 `ClusterLyricColors`） |
     *
     * 第一百三十三轮这些设置做在 `DesktopLayoutDialog` 里，藏在
     * 那个叫「仪表歌词」的页面最底下的一个按钮里 —— 名字对不上、藏得深、
     * 还和桌面歌词字号混在一处。第一百三十七轮起全部搬到这里，
     * 和桌面歌词**彻底分开**。
     */
    private fun clusterLyricBody() {
        // —— 总开关 ——
        section("投射")
        switchRow("仪表 Overlay 歌词", prefs.clusterOverlayEnabled) {
            val from = prefs.clusterOverlayEnabled
            prefs.clusterOverlayEnabled = it
            logSetting("desk_cluster_overlay", "仪表 Overlay 歌词", from, it)
            // 开关一变就**立刻**生效：开 → 起服务去加窗；关 → 拆窗停服务。
            // 不靠「应用」按钮 —— 用户点了开关就该马上看到结果，
            // 否则「勾上了但什么都没发生」是这一页最容易招投诉的地方。
            ClusterLyricsService.apply(context)
            refreshClusterStatus()
            // 开了却一块副屏都没有 → 立刻说清楚，别让用户以为是自己选错了。
            if (it && ClusterLyricsService.displays(context).isEmpty()) {
                Toast.makeText(
                    context,
                    "这台车机没有向应用暴露副屏，歌词无处可投",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // —— 投射目标 ——
        // 第149轮：原来这里是一个独立分区「投射目标」，和上面的「投射」、
        // 下面的「投射状态」是**同一件事**的三个面（开不开 / 投到哪 / 现在什么状态）。
        // 合并后只剩一个分区「投射」，三段用小标题（note）区分。
        note("投射到哪块屏：")
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hostCol.addView(list, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
        clusterDisplayList = list
        rebuildClusterDisplayList()
        actionButton("刷新屏幕列表", full = true) {
            rebuildClusterDisplayList()
            refreshClusterStatus()
        }

        // —— 投射状态 ——
        note("当前投射状态：")
        val statusText = Ui.text(context, "", 22f, pal.textDim)
        hostCol.addView(statusText, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })
        clusterStatusText = statusText
        actionButton("刷新状态", full = true) { refreshClusterStatus() }

        // —— 面板位置、样式与颜色 ——
        // 第149轮：原来「面板位置与样式」和「歌词颜色」是两个分区，
        // 但它们改的是**同一块面板** —— 位置 / 行数 / 样式 / 颜色，
        // 调的时候是连着调的，拆成两段只会让人来回滚。
        section("面板位置、样式与颜色")
        sliderRow("水平位置", prefs.overlayXPct, 0, 100, "%") {
            val from = prefs.overlayXPct
            prefs.overlayXPct = it
            logSettingDebug("desk_overlay_x", "水平位置", from, it)
            liveClusterRefresh()
        }
        sliderRow("垂直位置", prefs.overlayYPct, 0, 100, "%") {
            val from = prefs.overlayYPct
            prefs.overlayYPct = it
            logSettingDebug("desk_overlay_y", "垂直位置", from, it)
            liveClusterRefresh()
        }
        sliderRow("显示行数", prefs.clusterRows, 1, 3, " 行") {
            val from = prefs.clusterRows
            prefs.clusterRows = it
            logSettingDebug("desk_cluster_rows", "显示行数", from, it)
            liveClusterRefresh()
        }
        chipRow(
            "面板样式",
            com.cardesk.launcher.cluster.ClusterLyricStyles.OPTIONS.map { it.first },
            com.cardesk.launcher.cluster.ClusterLyricStyles.indexOf(prefs.clusterStyle),
        ) { index ->
            // 第一百四十三轮：这里原来直接存了 chip 的**中文标签**，
            // 而服务只认英文键（pill/plain/glow）⇒ 点哪个都落到「胶囊」。
            // 现在统一走 [ClusterLyricStyles] 的标签 ↔ 键映射。
            val from = prefs.clusterStyle
            val to = com.cardesk.launcher.cluster.ClusterLyricStyles.OPTIONS[index].second
            prefs.clusterStyle = to
            logSetting("desk_cluster_style", "面板样式", from, to)
            liveClusterRefresh()
        }
        sliderRow("模块宽度", prefs.overlayWidthPct, 10, 100, "%") {
            val from = prefs.overlayWidthPct
            prefs.overlayWidthPct = it
            logSettingDebug("desk_overlay_w", "模块宽度", from, it)
            liveClusterRefresh()
        }
        sliderRow("仪表背景透明度", prefs.overlayBgAlpha, 0, 100, "%") {
            val from = prefs.overlayBgAlpha
            prefs.overlayBgAlpha = it
            logSettingDebug("desk_overlay_bg_alpha", "仪表背景透明度", from, it)
            liveClusterRefresh()
        }
        // —— 对比描边（第一百四十二轮）——
        switchRow("文字描边", prefs.clusterHalo) { next ->
            val from = prefs.clusterHalo
            prefs.clusterHalo = next
            logSetting("desk_cluster_halo", "文字描边", from, next)
            liveClusterRefresh()
        }
        sliderRow("主歌词字号", prefs.overlayMainSp, 12, 72, " px") {
            val from = prefs.overlayMainSp
            prefs.overlayMainSp = it
            logSettingDebug("desk_overlay_main_sp", "主歌词字号", from, it)
            liveClusterRefresh()
        }
        sliderRow("副歌词字号", prefs.overlaySubSp, 8, 48, " px") {
            val from = prefs.overlaySubSp
            prefs.overlaySubSp = it
            logSettingDebug("desk_overlay_sub_sp", "副歌词字号", from, it)
            liveClusterRefresh()
        }

        // —— 歌词颜色（第一百三十七轮：默认跟随日夜）——
        pickerRow("主歌词颜色", clusterLyricColorLabel(0)) { onShowClusterLyricColor?.invoke(0) }
        pickerRow("副歌词颜色", clusterLyricColorLabel(1)) { onShowClusterLyricColor?.invoke(1) }
        actionButton("恢复跟随日夜", full = true) {
            val fromMain = prefs.clusterLyricMainColor
            val fromSub = prefs.clusterLyricSubColor
            prefs.clusterLyricMainColor = 0
            prefs.clusterLyricSubColor = 0
            logSetting("desk_cluster_lyric_main_color", "主歌词颜色", fromMain, 0)
            logSetting("desk_cluster_lyric_sub_color", "副歌词颜色", fromSub, 0)
            liveClusterRefresh()
            // 两行的标签要跟着变（从「#xxxxxx」变回「跟随日夜 · #xxxxxx」）——
            // 重建整页最省事，也不容易漏掉某一行。
            select("clusterlyric")
        }
        // 首次进入歌词页时立刻显示当前投射状态，不再空白等待点「刷新状态」。
        refreshClusterStatus()
    }

    /**
     * 重建「投射目标」的屏幕列表（第一百三十七轮）。
     *
     * 列表项就是这台车机**真正暴露给应用**的屏 ——
     * 不是「车机物理上有几块屏」，而是「Android 侧能看到几块」。
     * 这个差别很关键：仪表常常由独立 SoC / 总线驱动，
     * 对系统而言它压根不是一块 `Display`，那就**画不上去**。
     */
    private fun rebuildClusterDisplayList() {
        val list = clusterDisplayList ?: return
        list.removeAllViews()
        val rows = ClusterLyricsService.displays(context)
        if (rows.isEmpty()) {
            list.addView(
                Ui.text(
                    context,
                    "这台车机没有向应用暴露副屏。\n" +
                        "如果仪表由独立 SoC / 总线驱动，第三方窗口画不上去 —— 这不是设置问题。",
                    20f,
                    pal.textFaint,
                ),
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        rows.forEach { r ->
            val selected = r.id == prefs.clusterDisplayId ||
                (
                    prefs.clusterDisplayId <= 0 && r.name.isNotEmpty() &&
                        r.name.equals(prefs.clusterDisplayName, ignoreCase = true)
                    )
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                // 按压反馈：整行可选，用「行」的缩放幅度。
                Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
            }
            line.addView(
                Ui.text(
                    context,
                    (if (selected) "● " else "○ ") + r.label,
                    24f,
                    // 选中态用强调色（不是 accentInk —— 那是压在强调色**底**上的字色，
                    // 这里行底是透明的，用它会变成深底深字）。
                    if (selected) pal.accent else pal.text,
                    bold = selected,
                ),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            if (r.presentation) {
                line.addView(
                    Ui.text(context, "副屏", 20f, pal.textFaint),
                    LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = 18 },
                )
            }
            line.setOnClickListener {
                val from = prefs.clusterDisplayId
                prefs.clusterDisplayId = r.id
                prefs.clusterDisplayName = r.name
                logSetting("desk_cluster_display_id", "投射目标屏", from, r.id)
                // 选完就生效：不用等「应用」。
                ClusterLyricsService.refresh(context)
                rebuildClusterDisplayList()
                refreshClusterStatus()
            }
            list.addView(line, LinearLayout.LayoutParams(-1, 72))
        }
    }

    /** 就地刷新（不改窗口连接）。 */
    private fun liveClusterRefresh() {
        ClusterLyricsService.refresh(context)
        refreshClusterStatus()
    }

    /** 刷新「投射状态」那段文字。 */
    private fun refreshClusterStatus() {
        val tv = clusterStatusText ?: return
        val status = ClusterLyricsService.status(context)
        val report = status["report"] as? String ?: "未启用"
        val target = status["displayName"] as? String ?: ""
        val track = status["track"] as? String ?: ""
        val artist = status["artist"] as? String ?: ""
        val lines = status["lyricLines"] as? Int ?: 0
        tv.text = buildString {
            append("状态：").append(report)
            append('\n')
            append("目标屏：").append(if (target.isEmpty()) "未选择" else target)
            append('\n')
            append("曲目：").append(if (track.isEmpty()) "—" else "$track / $artist")
            append('\n')
            append("歌词：").append(if (lines > 0) "$lines 行" else "无")
        }
    }

    /**
     * 歌词颜色那一行的取值文案（第一百三十七轮）。
     *
     * 没挑过颜色（`0`）时显示「跟随日夜 · #xxxxxx」——
     * 把**当前这套日夜会用的实际色值**一起写出来。
     * 光写「跟随日夜」四个字，用户点进去之前不知道到底会是什么颜色；
     * 而这一页要解决的正是「日间看不清」，色值透明比「跟随」两个字有用得多。
     */
    private fun clusterLyricColorLabel(slot: Int): String {
        val c = if (slot == 0) prefs.clusterLyricMainColor else prefs.clusterLyricSubColor
        if (c != 0) return String.format("#%06X", c and 0xFFFFFF)
        // 用**投射服务同一个解析器**算当前是白天还是晚上 ——
        // 否则这里显示的色值和实际投到仪表上的可能对不上（第一百三十七轮）。
        val night = com.cardesk.launcher.core.NightResolver.resolve(prefs, context)
        val effective = if (slot == 0) ClusterLyricColors.main(night) else ClusterLyricColors.sub(night)
        return "跟随日夜 · " + String.format("#%06X", effective and 0xFFFFFF)
    }

    /**
     * 取色器选完颜色后刷新「歌词颜色」两行的标签（第一百三十七轮）。
     *
     * 取色器是**另一个弹层**（桌面那边开的），选完回到设置页时
     * 这两行的标签还是旧的色值，得主动刷一遍。
     */
    fun refreshClusterLyricColorLabels() {
        select("clusterlyric")
    }

    /**
     * 车辆信息（case "carinfo"）。
     *
     * ## 为什么从「仪表歌词」页里搬出来
     *
     * 胎压数字字号、车模编号、车辆数据源这三项**都不是歌词**，
     * 它们管的是桌面上那块车况卡片（第134~136轮做的车门提醒 / 续航分级配色
     * 也都落在同一块卡片上）。它们混在「仪表歌词」页里，
     * 是这一页名字与内容对不上的根源之一 —— 搬到这里归位。
     */
    private fun pageCarInfo() {
        section("胎压")
        // 第155轮：这个滑杆**接上实现**了 —— `prefs.deskTpmsValueSp` 现在真的
        // 喂给第一页胎压卡的渲染（`ScenePage.drawTireCard` → `scenePage.tpmsValueSp`），
        // 拖动时桌面上的胎压数字会实时变大变小。
        //
        // 之前它是写进偏好就没人读（第154轮审计标成「暂不可用」）。
        sliderRow("胎压数字字号", prefs.deskTpmsValueSp, 10, 48, " px") {
            val from = prefs.deskTpmsValueSp
            prefs.deskTpmsValueSp = it
            logSettingDebug("desk_tpms_value_sp", "胎压数字字号", from, it)
        }
        // 车型编号（：键 `tpms_car_model`，默认 `03`）。
        // 01 / 02 / 02HB / 05 是 SUV 家族，03 / 03+ 是轿车家族。
        chipRow("车模编号", com.cardesk.launcher.core.CarModels.MODEL_IDS, prefs.carModel) { modelId ->
            val from = prefs.carModel
            prefs.carModel = modelId
            logSetting("tpms_car_model", "车模编号", from, modelId)
            // 换车型可能换掉整套资产（纯俯视 ↔ 多视角），本页的行可见性要立刻跟着变。
            refreshCarInfoPage()
            // 桌面那边原本靠车辆数据每秒刷新顺带把 carModel 推下去，最慢要等一拍；
            // 这里直接走「设置已应用」链路，关上设置页就是新车模，不用等。
            onApply?.invoke()
        }
        // 写实车模（第163轮）：离线烘焙图 + 纯 Canvas 合成（`core/car3d`）。
        // 关掉立刻回到自绘俯视车模；资产缺失/解码失败时无论开关如何都会静默回退。
        section("写实车模")
        // 标题跟随当前车模（资产里的 name），切到 03/05 时不要还写「领克 01」。
        val car3dName = runCatching {
            com.cardesk.launcher.core.car3d.Car3dAssets
                .model(context, com.cardesk.launcher.core.CarModels.assetDir(prefs.carModel))
                ?.name
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "写实车模"
        switchRow(
            "写实车模（$car3dName）",
            com.cardesk.launcher.core.car3d.Car3dSettings.enabled,
        ) { on ->
            val from = com.cardesk.launcher.core.car3d.Car3dSettings.enabled
            com.cardesk.launcher.core.car3d.Car3dSettings.set(prefs, on)
            logSetting(com.cardesk.launcher.core.car3d.Car3dSettings.KEY, "写实车模", from, on)
            // 关掉写实车模 = 「车况卡视角」也无意义，立刻重建本页把行收掉。
            refreshCarInfoPage()
        }
        val attribution = runCatching {
            com.cardesk.launcher.core.car3d.Car3dAssets
                .model(context, com.cardesk.launcher.core.CarModels.assetDir(prefs.carModel))
                ?.attribution
        }.getOrNull()
        // 授权署名是**法律声明**（设置页仅允许状态/风险/法律三类说明文字），
        // 文案以「3D 模型：」开头以便设置页文案白名单识别（见 SettingsCopyTest）。
        note(
            if (attribution.isNullOrEmpty()) {
                "3D 模型：占位图（cardesk 自绘），真实模型待替换"
            } else if (attribution.startsWith("3D 模型：")) {
                attribution
            } else {
                "3D 模型：$attribution"
            }
        )
        // 车漆颜色（第166轮）：深灰 / 白 / 黑，切换立即重绘（chipRow 改完会调 onApply → refresh）。
        val paintLabels = listOf("深灰", "白", "黑")
        val paintIds = com.cardesk.launcher.core.car3d.Car3dSettings.PAINTS
        val currentPaint = paintIds.indexOf(com.cardesk.launcher.core.car3d.Car3dSettings.paint)
            .coerceAtLeast(0)
        chipRow("车漆颜色", paintLabels, currentPaint, compact = true) { idx ->
            val from = com.cardesk.launcher.core.car3d.Car3dSettings.paint
            val to = paintIds.getOrElse(idx) { com.cardesk.launcher.core.car3d.Car3dSettings.PAINT_DARK }
            com.cardesk.launcher.core.car3d.Car3dSettings.setPaint(prefs, to)
            logSetting(com.cardesk.launcher.core.car3d.Car3dSettings.KEY_PAINT, "车漆颜色", from, to)
        }
        // 车况卡视角（第166轮）：3/4 侧视 ↔ 俯视，切换立即重绘（chipRow 改完会调 onApply）。
        //
        // 第179轮：三台车都改成纯俯视 32 态后，这个设置**没有意义**（卡固定俯视），
        // 用户拍板把整行隐藏；写实车模总开关关掉时也不该出现。判定用资产驱动
        // （topDownOnly），03/05 与 01 一样走隐藏，将来若回到多视角资产会自动恢复。
        val assetDir = com.cardesk.launcher.core.CarModels.assetDir(prefs.carModel)
        val topDownOnly = runCatching {
            com.cardesk.launcher.core.car3d.Car3dAssets.topDownOnly(context, assetDir)
        }.getOrDefault(false)
        val showViewRow = showCardViewRow(
            com.cardesk.launcher.core.car3d.Car3dSettings.enabled,
            topDownOnly,
        )
        if (showViewRow) {
        val cardViewIds = com.cardesk.launcher.core.car3d.Car3dSettings.CARD_VIEWS
        val cardViewLabels = listOf("3/4 侧视", "俯视", "正前 3/4")
        val currentCardView = cardViewIds.indexOf(com.cardesk.launcher.core.car3d.Car3dSettings.cardView)
            .coerceAtLeast(0)
        chipRow("车况卡视角", cardViewLabels, currentCardView, compact = true) { idx ->
            val from = com.cardesk.launcher.core.car3d.Car3dSettings.cardView
            val to = cardViewIds.getOrElse(idx) { com.cardesk.launcher.core.car3d.Car3dSettings.CARD_VIEW_HERO }
            com.cardesk.launcher.core.car3d.Car3dSettings.setCardView(prefs, to)
            logSetting(
                com.cardesk.launcher.core.car3d.Car3dSettings.KEY_CARD_VIEW,
                "车况卡视角",
                from,
                to,
            )
        }
        // 缺资产时诚实提示（选中也会自动回退 3/4，不崩、不空白）。
        val missingViews = ArrayList<String>(2)
        if (!com.cardesk.launcher.core.car3d.Car3dAssets.hasView(
                context, assetDir, com.cardesk.launcher.core.car3d.Car3dAssets.VIEW_CARDTOP,
            )
        ) {
            missingViews.add("俯视")
        }
        if (!com.cardesk.launcher.core.car3d.Car3dAssets.hasView(
                context, assetDir, com.cardesk.launcher.core.car3d.Car3dAssets.VIEW_CARD,
            )
        ) {
            missingViews.add("正前 3/4")
        }
        if (missingViews.isNotEmpty()) {
            // 以「当前：」开头 —— 设置页文案白名单里「状态」类的既定前缀。
            note("当前：车模资产不含${missingViews.joinToString("、")}图，选中会先按 3/4 侧视显示")
        }
        }  // showViewRow
        // 第三百一十轮：车模图支持远端资源包；第三百一十二轮起 APK 不再内置车模图
        // （首次安装/断网先用自绘俯视车模，启动时后台自动补齐，补齐后免重启换写实图）。
        //
        // 放在「写实车模」这一页，是因为它改的就是写实车模用的那套图；
        // 把它塞进「在线更新」会让用户以为整个桌面在升级（那页是换 APK 的）。
        section("车模资源包")
        val installed = runCatching {
            com.cardesk.launcher.core.car3d.CarModelRemote.installedVersions(context)
        }.getOrDefault(emptyMap())
        val currentDir = com.cardesk.launcher.core.CarModels.assetDir(prefs.carModel)
        val currentVer = installed[currentDir] ?: 0
        kvRow(
            "本地资源包",
            if (installed.isEmpty()) {
                "未安装（启动时自动补齐）"
            } else {
                "${installed.size} 个车型 · 当前 v$currentVer"
            },
            installed.isNotEmpty(),
        )
        actionButton("检查车模资源更新…", full = true) {
            Logx.event(LogTags.CAR3D, "检查车模资源更新", "dir" to currentDir)
            // 手动检查要「真的去问远端」：走 force=true 绕过 10 分钟索引缓存，
            // 否则刚发布的资源包在缓存过期前点了也是「全部最新」。
            val appCtx = context.applicationContext
            Thread {
                val r = com.cardesk.launcher.core.car3d.CarModelAutoSync.syncNow(appCtx)
                val msg = when {
                    r.error != null -> "车模资源包：" + r.error
                    r.updated > 0 -> "已更新 " + r.updated + " 个车模"
                    r.failedDirs.isNotEmpty() -> "部分车模更新失败"
                    else -> "车模资源包已是最新"
                }
                post {
                    Logx.i(
                        LogTags.CAR3D, "车模资源包结果",
                        "msg" to msg,
                        "dir" to currentDir,
                        "updated" to r.updatedDirs.keys.toList(),
                        "failed" to r.failedDirs,
                    )
                    Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
                    // 真的换了图才清解码缓存：`Car3dAssets` 按车型目录缓存 `model.json` 与位图，
                    // 不清就一直是旧图（以前要杀进程重启才换）。post 在主线程，reset 安全。
                    if (r.updatedDirs.isNotEmpty()) {
                        com.cardesk.launcher.core.car3d.Car3dAssets.reset()
                        onApply?.invoke()
                    }
                    // 装完立刻重建本页（「本地资源包」那行要显示新版本号）。
                    refreshCarInfoPage()
                }
            }.start()
        }
        chipRow("车辆数据源", listOf("自动探测", "模拟车辆", "真车通道"), prefs.vehicleSource) { idx ->
            val from = prefs.vehicleSource
            prefs.vehicleSource = idx
            logSetting("vehicle_source", "车辆数据源", from, idx)
            onVehicleSource?.invoke()
        }
    }

    /** Dock 状态栏（case "dock"）+ 增强导航栏 / 槽位。 */
    /**
     * Dock 与导航栏（第149轮：把「Dock 快捷应用」并进来）。
     *
     * ## 为什么合并
     *
     * 「Dock 快捷应用」讲的是 Dock 上的**槽位里放什么**，
     * 「Dock 状态栏」讲的是 Dock **本身怎么显示** —— 同一块区域的两种设置。
     *
     * 快捷应用只有 4 项（已配置 / 说明 / 一个按钮），单开一页太浪费。
     */
    /**
     * 「Dock 与导航栏」页（画板 `s11-dock.png`）：**两栏**。
     *
     * 左栏（570 宽）是「原车 Dock / 状态栏」那几个开关 + Dock 圆角 + 状态栏生效应用；
     * 右栏（800 宽）是「全局 Dock / 增强导航栏 / 下移 Dock / 导航栏样式 / 槽位 /
     * Dock 快捷应用」。画板这样分，是因为左右两栏是**两套东西**：
     * 左边调「挡住原车的东西」，右边调「CarDesk 自己的 Dock 怎么长」。
     *
     * 以前是**一整条竖排**（快捷应用在最上面，然后一路往下），
     * 一屏只看得见前四个开关，「全局 Dock / 槽位 / 快捷应用」全在折叠线以下。
     *
     * ⚠️ 已知代价：`SettingsDropdownTest.按钮底边到下一项要真的有空隙_不是零距离`
     * 断言「配置 Dock 快捷应用…」是 `body` 的**直接子 view**、且下一个兄弟是
     * 「沉浸系统导航栏」那一行 —— 那是**单列**版式才成立的写法。
     * 分栏后按钮落在右栏容器里，这条断言会抛 `NoSuchElementException`。
     * 版式按画板优先（验收口径），测试需要同步改成「按视图树递归找 + 取兄弟的行位置」。
     */
    private fun pageDock() {
        twoColumns(DOCK_COL_W) { left, right ->
            inColumn(left) { dockLeftColumn() }
            inColumn(right) { dockRightColumn() }
        }
    }

    /**
     * s11 左栏：挡住原车 Dock / 状态栏的那几个开关。
     *
     * 第五十六轮：这里原来只有一个「隐藏原车 Dock」开关，
     * 而我们把它**同时**用在了「隐藏原车 Dock」和「隐藏桌面自己的 Dock」上 ——
     * 于是勾上它，消失的是**桌面自己的** Dock（用户反馈「隐藏系统 Dock 栏功能实现错误」）。
     * 现在拆成三个互不相干的开关。
     */
    private fun dockLeftColumn() {
        section("显示与隐藏")
        switchRow("隐藏桌面 Dock", prefs.hideHomeDock) {
            val from = prefs.hideHomeDock
            prefs.hideHomeDock = it
            logSetting("home_dock_hidden", "隐藏桌面 Dock", from, it)
            com.cardesk.launcher.core.dock.GlobalDockService.refreshVisibility()
        }
        switchRow("隐藏原车 Dock", prefs.hideDock, "对所有应用生效") {
            val from = prefs.hideDock
            prefs.hideDock = it
            logSetting("dockbar_hide_dock", "隐藏原车 Dock", from, it)
        }
        switchRow("隐藏原车状态栏", prefs.hideStatus) {
            val from = prefs.hideStatus
            prefs.hideStatus = it
            logSetting("dockbar_hide_status", "隐藏原车状态栏", from, it)
        }
        pickerRow("状态栏生效应用", dockAppSummary(prefs.hideStatusApps)) {
            Logx.event(
                LogTags.SETTING,
                "打开状态栏生效应用选择器",
                "current" to prefs.hideStatusApps.joinToString(","),
            )
            onShowStatusApps?.invoke()
        }
    }

    /**
     * s11 右栏：全局 Dock / Dock 快捷应用 / Dock 外观（圆角）。
     *
     * 已移除导航栏样式、槽位编辑/重置，以及与全局 Dock 重复或互斥的历史项
     * （沉浸系统导航栏、增强导航栏、下移 Dock），整页只保留相互独立、真正在生效的设置。
     */
    private fun dockRightColumn() {
        section("全局 Dock")
        switchRow("全局 Dock 栏（其他应用也显示）", prefs.globalDockEnabled) {
            val from = prefs.globalDockEnabled
            prefs.globalDockEnabled = it
            logSetting(com.cardesk.launcher.core.Prefs.KEY_GLOBAL_DOCK, "全局 Dock 栏", from, it)
            if (it) ensureGlobalDockPermission()
            // 开关关掉时 ensureStarted 会停服务；服务销毁时把 policy 恢复成
            // 「普通桌面」状态（否则原车 Dock 和 CarDesk Dock 会同时消失）。
            com.cardesk.launcher.core.dock.GlobalDockService.ensureStarted(context.applicationContext)
            // 开关会导致下面的 note 与 policy 分支变化，重画本页。
            select("dock")
        }
        if (prefs.globalDockEnabled && !runCatching {
                com.cardesk.launcher.core.dock.NavInsets.applied(context)
            }.getOrDefault(false)
        ) {
            note("预留区未启用：其它应用底部可能被 Dock 遮挡（平台签名版会自动启用）。")
        }

        quickAppsBody()

        section("外观")
        // Dock 紧贴屏幕底部，圆角只作用在上面两个角；拖动时桌面/全局 Dock 实时重绘。
        sliderRow("Dock 圆角", prefs.dockCornerRadius, 0, 52, " px") {
            val from = prefs.dockCornerRadius
            prefs.dockCornerRadius = it
            com.cardesk.launcher.ui.shell.DockBarView.refreshAll(context)
            logSettingDebug("dock_corner_radius", "Dock 圆角", from, it)
        }
        actionButton("调整 Dock 按钮顺序…", full = true) {
            Logx.event(LogTags.SETTING, "进入 Dock 拖动排序模式")
            onReorderDock?.invoke()
        }
    }

    /**
     * 全局 Dock 的悬浮窗授权引导（第八十七轮）。
     *
     * ## 为什么要跳系统页而不是直接 `requestPermissions`
     *
     * `SYSTEM_ALERT_WINDOW` 不是运行时权限，没有系统弹窗，只能让用户去
     * 「显示在其他应用上层」页手动开。带 `package:` 的 Uri 能直接落到本应用
     * 那一项（`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`），少让用户找两屏。
     *
     * ## 为什么授完权不用在这等
     *
     * 授权页返回时 Activity 会 `onResume`，`MainActivity.onResume` 里已经
     * 重新 `ensureStarted` + 重算 policy —— 这里只要负责把人送过去。
     *
     * 已经能接管导航栏（`NavInsets.supported()`）时不引导：那条路径不需要悬浮窗。
     */
    private fun ensureGlobalDockPermission() {
        val app = context.applicationContext
        if (runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)) return
        if (runCatching {
                com.cardesk.launcher.core.dock.NavInsets.supported(app)
            }.getOrDefault(false)
        ) {
            return
        }
        Logx.event(LogTags.SETTING, "全局 Dock 缺少悬浮窗权限，跳系统授权页")
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + app.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        Toast.makeText(
            context,
            if (opened) {
                "请允许「显示在其他应用上层」，返回桌面后全局 Dock 会自动生效"
            } else {
                "请在系统设置中允许「显示在其他应用上层」"
            },
            Toast.LENGTH_LONG,
        ).show()
        Logx.i(LogTags.SETTING, "全局 Dock 授权引导结束", "opened" to opened)
    }

    /** 已选应用数（不含「一个都不隐藏」的哨兵）。 */

    /**
     * 「生效应用」摘要
     * 空集 = 全部应用；含哨兵 = 未选择应用；否则列出前两个应用名。
     */
    private fun dockAppSummary(apps: Set<String>): String {
        if (apps.isEmpty()) return "全部应用"
        if (apps.contains(OemDockPolicy.NO_APPS)) return "未选择应用"
        val all = launcherApps().map { it.first }
        if (all.isNotEmpty() && all.all { apps.contains(it) }) return "全部应用"
        val names = apps.mapNotNull { appLabelOf(it) ?: it }.take(2)
        val joined = names.joinToString("、")
        return if (apps.size <= names.size) joined else "$joined 等${apps.size}个"
    }

    private fun launcherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }
            .getOrElse { emptyList() }
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    /**
     * 「Dock / 状态栏生效应用」面板由共享的 `AppPickerDialog` 承担
     * 标题区分 Dock / 状态栏
     * 预勾选规则同、「全选 / 清空」按钮 + 「已选择 N/M」、勾选后立即生效。
     */

    /**
     * 原厂设置目录（case "oemsettings"，第九十五轮 C4）。
     *
     * 「默认设置」只打开 Android 系统设置；这一页把**车机原厂的设置页**也列出来：
     * 驾驶模式、蓝牙、热点、显示/声音等都在 `com.ecarx.settings` 里，没有统一 action，
     * 所以用 [com.cardesk.launcher.core.SystemSettingsCatalog] 按 action/组件名实时解析。
     */
    private fun pageOemSettings() {
        com.cardesk.launcher.core.SystemSettingsCatalog.warmAsync(context)
        val catalog = com.cardesk.launcher.core.SystemSettingsCatalog.entries()
        if (catalog.isEmpty()) {
            if (com.cardesk.launcher.core.SystemSettingsCatalog.isWarmed()) {
                note("这台车机没有解析到原厂设置入口（原厂设置包可能未安装或被禁用）。")
            } else {
                note("正在扫描原厂设置入口…")
                // 后台扫描完成后重画这一页（每次扫描最多重画一次，见 warmAsync 的 TTL）
                postDelayed({
                    if (selectedKey == "oemsettings") select(selectedKey)
                }, 600L)
            }
        } else {
            section("原厂设置入口")
            for (entry in catalog) {
                oemSettingRow(entry)
            }
        }
        section("兜底入口")
        actionButton("打开 Android 系统设置", primary = true, full = true) {
            // 排障场景：原厂目录为空时至少还能进 Android 设置
            Logx.event(LogTags.SETTING, "打开 Android 系统设置（原厂设置页兜底）")
            val intents = listOf(
                Intent(Settings.ACTION_SETTINGS),
                Intent(Settings.ACTION_SETTINGS).setPackage("com.android.settings"),
            )
            for (intent in intents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    return@actionButton
                } catch (e: Exception) {
                    Logx.w(LogTags.SETTING, "打开 Android 系统设置失败", e,"action" to intent.action)
                }
            }
            Toast.makeText(context, "打不开系统设置", Toast.LENGTH_SHORT).show()
        }
        actionButton("重新扫描原厂设置", full = true) {
            com.cardesk.launcher.core.SystemSettingsCatalog.invalidate()
            com.cardesk.launcher.core.SystemSettingsCatalog.warmAsync(context)
            Logx.event(LogTags.SETTING, "重新扫描原厂设置目录")
            select(selectedKey)
        }
    }

    /** 一行原厂设置入口：主标题 + 来源副标题 + 可点打开。 */
    private fun oemSettingRow(entry: com.cardesk.launcher.core.SystemSettingsCatalog.Entry) {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 0, 18, 0)
            isClickable = true
            pressFeedback()
            setOnClickListener {
                Logx.event(LogTags.SETTING, "打开原厂设置入口", "id" to entry.id, "label" to entry.label)
                if (!com.cardesk.launcher.core.SystemSettingsCatalog.open(context, entry)) {
                    Toast.makeText(context, "打不开：${entry.label}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        holder.addView(
            Ui.text(context, entry.label, 26f, pal.text, bold = true),
            LinearLayout.LayoutParams(-1, -2),
        )
        holder.addView(
            Ui.text(context, entry.source, 19f, pal.textDim),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 4 },
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, 96))
        hostCol.addView(
            View(context).apply { setBackgroundColor(Skin.NAV_STROKE) },
            LinearLayout.LayoutParams(-1, 1),
        )
    }

    /** 默认设置（case "native"）。 */
    private fun pageNativeSettings() {
        nativeSettingRow("系统默认桌面", "打开系统默认桌面设置", primary = true) {
            // 排障场景：系统默认桌面页打不开 —— 记下尝试了哪个 Intent
            Logx.event(LogTags.SETTING, "打开系统默认桌面设置")
            val intents = listOf(
                Intent(Settings.ACTION_HOME_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
            for (intent in intents) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    Logx.i(LogTags.SETTING, "已打开系统设置页", "action" to intent.action)
                    return@nativeSettingRow
                } catch (e: Exception) {
                    // 排障场景：某些车机没有这个系统页
                    Logx.w(LogTags.SETTING, "打开系统设置页失败", e, "action" to intent.action)
                }
            }
            Toast.makeText(context, "本机没有可打开的系统设置页", Toast.LENGTH_SHORT).show()
        }
        nativeSettingRow("系统设置", "打开系统设置", primary = false) {
            Logx.event(LogTags.SETTING, "打开系统设置")
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // 排障场景：系统设置打不开
                Logx.w(LogTags.SETTING, "打开系统设置失败", e)
                Toast.makeText(context, "打不开系统设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 画板 `s22-native.png` 的一行：左边分区标签（系统默认桌面 / 系统设置），
     * 右边一颗**窄按钮**贴右内边。
     *
     * 旧版是两条满宽大按钮上下一摞 —— 画板上按钮只有 ~190 宽，而且和标签同一行。
     */
    private fun nativeSettingRow(title: String, buttonText: String, primary: Boolean, onClick: () -> Unit) {
        val holder = FrameLayout(context)
        holder.addView(
            Ui.text(context, title, ROW_TITLE, pal.text),
            FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL),
        )
        val btn = makeButton(buttonText, primary = primary, danger = false, full = false)
        btn.setOnClickListener { onClick() }
        holder.addView(
            btn,
            FrameLayout.LayoutParams(-2, S22_BTN_H, Gravity.END or Gravity.CENTER_VERTICAL).also {
                // 按钮间距测试逐个查 bottomMargin。
                it.bottomMargin = 12
            },
        )
        hostCol.addView(holder, LinearLayout.LayoutParams(-1, S22_ROW_H))
        hairline()
    }

    /** 系统诊断+ 平台与兼容 + 媒体来源。 */
    private fun pageDiagnostics() {
        section("设备与分支")
        val metrics = context.resources.displayMetrics
        kvRow("系统", "Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}", true)
        kvRow("屏幕", "${metrics.widthPixels}×${metrics.heightPixels} · ${metrics.densityDpi} dpi", true)
        section("平台识别")
        val profile = com.cardesk.launcher.vehicle.VehicleController.platformProfile
            ?: com.cardesk.launcher.core.Platforms.detect(context, prefs.platformOverride)
        note(profile.summary())
        chipRow("平台判定", listOf("自动识别", "强制 E03", "强制 820A"), prefs.platformOverride) { idx ->
            val from = prefs.platformOverride
            prefs.platformOverride = idx
            logSetting("platform_override", "平台判定", from, idx)
            com.cardesk.launcher.vehicle.VehicleController.reconnect(context, prefs)
            select(selectedKey)
        }
        section("车控通道状态")
        val vc = com.cardesk.launcher.vehicle.VehicleController
        val gw = if (vc.initialized) runCatching { vc.gateway }.getOrNull() else null
        kvRow("当前生效通道", vc.sourceLabel, !vc.isMockFallback)
        kvRow("通道连接", if (gw?.isConnected() == true) "已连接" else "未连接", gw?.isConnected() == true)
        kvRow("车机服务", if (gw?.isServiceConnected() == true) "已连上" else "未连上", gw?.isServiceConnected() == true)
        kvRow(
            "最近一次写入",
            when {
                vc.lastWriteAt == 0L -> "尚未写入"
                vc.lastWriteOk -> "成功"
                else -> "失败"
            },
            vc.lastWriteOk,
        )
        vc.lastWriteError?.let { kvRow("失败原因", it, false) }
        section("系统能力")
        val caps = com.cardesk.launcher.core.SystemOps.capabilityReport(context)
        for ((cap, ok) in caps) {
            // 画板 s23：能力状态右侧是**胶囊**（绿「可用」/ 灰「不可用」），不是一行彩色文字。
            val capRow = FrameLayout(context)
            capRow.addView(
                Ui.text(context, cap.label, ROW_TITLE, pal.text),
                FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL),
            )
            capRow.addView(
                pillTag(if (ok) "可用" else "不可用", if (ok) pal.ok else pal.textDim),
                FrameLayout.LayoutParams(-2, PILL_TAG_H, Gravity.END or Gravity.CENTER_VERTICAL),
            )
            hostCol.addView(capRow, LinearLayout.LayoutParams(-1, S23_CAP_ROW_H))
            hairline()
        }
        // 排障场景：系统能力（静默安装/系统设置写入等）到底哪几项可用
        Logx.i(
            LogTags.SYS,
            "系统能力报告",
            "caps" to caps.joinToString(",") { "${it.first.label}=${if (it.second) "可用" else "不可用"}" },
        )
        actionButton("重新探测平台与通道", primary = true, full = true) {
            com.cardesk.launcher.vehicle.VehicleController.reconnect(context, prefs)
            select(selectedKey)
        }
    }

    /**
     * 媒体源（第149轮：从「系统诊断」页里**独立出来**）。
     *
     * ## 为什么从系统诊断页搬出来
     *
     * 「默认用哪个音乐 App」是**天天要碰**的设置，
     * 之前塞在「系统诊断」页里 —— 那一页是给排障用的
     * （平台判定、车控通道状态、系统能力签名…），
     * 想找「默认音乐 App」得先知道「系统诊断」这个地名。
     *
     * 独立成一页之后，它和「开机自启」「桌面卡片」这些常用项是平级的。
     *
     * ## 内容
     *
     * - 「默认来源」：桌面启动时锁定哪个 App（第125轮就有；**第151轮改成下拉**）；
     * - 「启动后自动播放」：**第149轮新增** —— 桌面起来后把它拉起来并播放；
     * - 「媒体来源（固定 9 个）」：这 9 个分别装没装（纯状态展示）。
     */
    private fun pageMedia() {        // 第151轮：「默认来源」从 **chip 行**改成**下拉**。
        //
        // 为什么：9 个选项挤在一排 chip 里，选中项（默认是「通用音乐」）在**中间**，
        // 打开设置页根本看不出当前选的是谁 —— 得横向滚动去找那个高亮的。
        // 改成一个下拉行：收起时当前值就写在行上，展开时 9 个选项一次列全。
        val sources = com.cardesk.launcher.core.media.MediaSources.LIST
        // 条件提示的引用要在下拉**之前**声明：下拉的回调里要能把它收起来
        // （选了具体的 App，这条提示就没意义了）。
        var genericNote: TextView? = null
        // 画板 s05 的纵向节奏（实测：默认媒体源中心 208 / 启动后 328 / 说明 415 /
        // 媒体来源标题 462 / 首行 498）；旧实现整体高 30~90px，这里用三段定高占位补齐。
        hostCol.addView(View(context), LinearLayout.LayoutParams(1, 30))
        // 画板 s05 这一行是**紧凑行（62）**、标签直接叫「默认媒体源」，
        // 上面不再套一层同名 section —— 第 2 轮验收实测多出来的 127px 就是那层标题 + 128 行高。
        mediaPopupRow(
            "默认媒体源",
            sources,
            // 兜底：prefs 里存了不认识的 id 时退回「通用音乐」，
            // 与旧 chip 行的行为一致（找不到就显示 GENERIC_LABEL）。
            sources.indexOfFirst { it.id == prefs.defaultMusicSource }
                .let { if (it >= 0) it else sources.indexOfFirst { s -> s.id == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID } },
            height = MEDIA_DEFAULT_ROW_H,
        ) { index ->
            val src = sources[index]
            prefs.defaultMusicSource = src.id
            genericNote?.visibility =
                if (src.id == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID) View.VISIBLE else View.GONE
            onDefaultMediaSource?.invoke(src.id)
        }

        // 第149轮：桌面启动完成后唤起默认媒体源 App 并让它播放。
        //
        // 默认**关** —— 开机弹出一个音乐 App 挺打扰，而且默认源是「通用音乐」时
        // 根本没有对应的 App 可拉（`MediaAppLauncher.shouldLaunch` 会挡住）。
        // 画板 s05 这两行都是**紧凑行**（默认媒体源 62 / 启动后自动播放 62），
        // 不是其它页 128 的整块行 —— 否则「媒体来源」这一段会被推到 y≈490。
        hostCol.addView(View(context), LinearLayout.LayoutParams(1, 25))
        switchRow("启动后自动播放", prefs.autoLaunchMediaApp) { on ->
            val from = prefs.autoLaunchMediaApp
            prefs.autoLaunchMediaApp = on
            logSetting("auto_launch_media_app", "启动后自动播放", from, on)
        }
        genericNote = note(
            "要先在上面把默认来源选成具体的 App ——「通用音乐」是跟随系统当前会话，没有对应的 App 可拉。",
        ).apply {
            visibility =
                if (prefs.defaultMusicSource == com.cardesk.launcher.core.media.MediaSources.GENERIC_ID) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }

        // 画板 s05：9 个来源排成**两列**（左 5 / 右 4，按列从上往下填），
        // 每张卡右侧是一个**胶囊状态标签**，且**不显示包名**
        // （画板只写「已安装 / 未安装 / 兜底来源」；包名对驾驶员没意义，
        //  真要查候选包，诊断包里已经有了）。
        // 画板 s05：说明（415..428）之后直接是「媒体来源（固定 9 个）」（462..473），
        // 中间不再插 40px 占位 —— 那会让整段下移。
        hostCol.addView(View(context), LinearLayout.LayoutParams(1, 30))
        section("媒体来源（固定 9 个）")
        // 先把每张卡的「名字 + 胶囊文案 + 胶囊色」算好，两列共用同一份。
        val statuses = sources.map { source ->
            val candidates = com.cardesk.launcher.core.media.MediaSources.packageCandidates(source.label)
            if (candidates.isEmpty()) {
                // 兜底来源：没有候选包（通用音乐 / 多媒体 / 蓝牙 / USB），
                // 由系统当前活跃的媒体会话接管。画板给它蓝色 —— 调色板里没有
                // info 蓝，这里用项目既有的蓝色 accent（Palettes.ACCENT）。
                Triple(source.label, "兜底来源", Palettes.ACCENT)
            } else {
                val installed = candidates.firstOrNull { pkg ->
                    runCatching { context.packageManager.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
                }
                if (installed != null) {
                    Triple(source.label, "已安装", pal.ok)
                } else {
                    Triple(source.label, "未安装", pal.textDim)
                }
            }
        }

        // 画板 s05 每行右侧是「选取来源」胶囊（点了就把默认源切过去）——
        // 之前只有状态胶囊、没有这个入口，验收判了「功能缺失」。
        fun mediaSourceRow(src: com.cardesk.launcher.core.media.MediaSources.Source) {
            val candidates = com.cardesk.launcher.core.media.MediaSources.packageCandidates(src.label)
            val badge: String
            val color: Int
            if (candidates.isEmpty()) {
                badge = "兜底来源"
                color = Palettes.ACCENT
            } else if (candidates.any { pkg ->
                    runCatching { context.packageManager.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
                }
            ) {
                badge = "已安装"
                color = pal.ok
            } else {
                badge = "未安装"
                color = pal.textDim
            }
            val holder = FrameLayout(context)
            holder.addView(
                Ui.text(context, src.label, ROW_TITLE, pal.text),
                FrameLayout.LayoutParams(-2, -1, Gravity.START or Gravity.CENTER_VERTICAL),
            )
            // 画板 s05 每行右侧**只有**状态胶囊（已安装 / 未安装 / 兜底来源），
            // 没有「选取来源」按钮 —— 选默认源走上方的「默认媒体源」下拉。
            // （我上一轮加过一个「选取来源」胶囊，属自造控件，第 3 轮复核点名，已删。）
            holder.addView(
                pillTag(badge, color),
                FrameLayout.LayoutParams(-2, PILL_TAG_H, Gravity.END or Gravity.CENTER_VERTICAL),
            )
            hostCol.addView(holder, LinearLayout.LayoutParams(-1, MEDIA_ROW_H))
            hairline()
        }

        val half = (sources.size + 1) / 2
        twoColumns(MEDIA_COL_W) { left, right ->
            inColumn(left) { for (item in sources.take(half)) mediaSourceRow(item) }
            inColumn(right) { for (item in sources.drop(half)) mediaSourceRow(item) }
        }
    }

    /** 重启车机。 */
    private fun pageRestart() {
        // 画板 s24：整页是一个**居中**的警告块 —— 危险色淡底图标 + 标题 + 一行说明 +
        // 居中的危险按钮。旧版是「左对齐 section + 满宽按钮」，跟画板完全不是一个东西。
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(
            restartGlyphTile(),
            LinearLayout.LayoutParams(RESTART_TILE_W, RESTART_TILE_H),
        )
        col.addView(
            Ui.text(context, "重启车机", 24f, pal.text, bold = true),
            LinearLayout.LayoutParams(-2, -2).also { it.topMargin = RESTART_TITLE_TOP },
        )
        val hint = Ui.text(
            context,
            "将关闭所有应用并重新启动系统，约需 40 秒。行驾中请勿操作。",
            16f,
            pal.textDim,
        ).apply { gravity = Gravity.CENTER }
        // 风险文案仍打 NOTE_MARK：文案测试要能盯着它（白名单里已有这条）。
        hint.tag = NOTE_MARK
        col.addView(hint, LinearLayout.LayoutParams(-2, -2).also { it.topMargin = RESTART_NOTE_TOP })

        val reboot = makeButton("立即重启", primary = false, danger = true, full = false)
        reboot.setOnClickListener {
            // 排障场景：点了重启没反应 —— 记下打开了确认框
            Logx.event(LogTags.SETTING, "打开重启车机确认框")
            onShowRestart?.invoke()
        }
        col.addView(
            reboot,
            LinearLayout.LayoutParams(RESTART_BTN_W, RESTART_BTN_H).also {
                it.topMargin = RESTART_BTN_TOP
                it.bottomMargin = 12
            },
        )
        hostCol.addView(col, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = RESTART_TOP })
    }

    /**
     * 画板 s24 里那枚「危险色淡底 + 电源图标」的圆角方块。
     *
     * 项目里没有现成的电源图标资源（`DockBarView.drawPower` 是私有的），
     * 这里就地画：一段 304° 的弧 + 一条竖线，颜色/淡底都从 [pal] 派生，不新增色值。
     */
    private fun restartGlyphTile(): View = object : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val arc = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            box.set(0f, 0f, w, h)
            fill.style = Paint.Style.FILL
            fill.color = pal.withAlpha(pal.danger, RESTART_TILE_FILL_ALPHA)
            canvas.drawRoundRect(box, RESTART_TILE_RADIUS, RESTART_TILE_RADIUS, fill)

            val r = w * 0.21f
            val cx = w / 2f
            val cy = h / 2f + r * 0.10f
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = RESTART_GLYPH_STROKE
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = pal.danger
            arc.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arc, -62f, 304f, false, stroke)
            canvas.drawLine(cx, cy - r * 1.34f, cx, cy - r * 0.16f, stroke)
        }
    }

    /**
     * 诊断日志（**没有这一页**，是为真机排障加的调试工具）。
     *
     * - 日志级别：控制哪些级别会落盘（低于级别的只进 Logcat）；
     * - 导出诊断包：把日志 + 运行快照打成 zip，放到 `Download/CarDesk/`；
     * - 分享：直接把 zip 调起系统分享发出来；
     * - 预览：看最后若干行，确认日志确实在写。
     */
    private fun pageLogs() {
        // 画板 s25 是**左右两栏**：左栏（通知使用权 / 日志级别 / 收集 + 三枚横排按钮）
        // 与右侧「最近日志」窄栏。左栏宽 790（面板内 x450..1240），右栏吃掉剩下的 592。
        twoColumns(LOG_LEFT_W) { left, right ->
            inColumn(left) {
                section("通知使用权")
                note(com.cardesk.launcher.core.media.NotificationAccess.describeEnumeration(context))

                section("日志级别")
                val levels = listOf(
                    LogLevel.ERROR to "仅错误",
                    LogLevel.WARN to "错误+警告",
                    LogLevel.INFO to "错误+警告+信息",
                    LogLevel.DEBUG to "全部（含调试）",
                )
                // 画板上「日志级别」这个组标题**就是**这行 chips 的标签（下面紧跟一行 chips），
                // 所以这里不再重复一个「落盘级别」标签，chips 右对齐到左栏右内边。
                chipRow(
                    "",
                    levels.map { it.second },
                    levels.indexOfFirst { it.first == Logx.level() }.coerceAtLeast(0),
                    height = LOG_CHIP_ROW_H,
                ) { idx ->
                    Logx.setLevel(levels[idx].first)
                    select("logs")
                }

                section("收集")
                // 占用必须是**真实占用**（画板上的 12.4 MB 只是示意）：读不到就显示读不到，
                // 绝不写死一个好看的数（旧实现写 /1024 + KB，和画板对不上）。
                note("导出目录：Download/CarDesk/；当前占用 ${bytesText(com.cardesk.launcher.core.log.Logx.totalBytes())}")

                // 三枚按钮**横排**（画板 y≈375..425，wrap-content 不是整宽）：
                // 旧实现三条满宽按钮竖着摞，占满了整列还把日志挤到屏幕外。
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                fun addBtn(v: TextView, gap: Int = 0) {
                    btnRow.addView(v, LinearLayout.LayoutParams(-2, LOG_BUTTON_H).also {
                        it.topMargin = 12
                        // 横排按钮也要留底边距（按钮间距测试对每个按钮都查 bottomMargin）。
                        it.bottomMargin = 12
                        it.leftMargin = gap
                    })
                }
                addBtn(
                    makeButton("导出诊断包", primary = true, danger = false, full = false).apply {
                        setOnClickListener {
                            com.cardesk.launcher.core.log.LogTools.export(context) { msg ->
                                com.cardesk.launcher.core.log.LogTools.toast(context, msg)
                            }
                        }
                    },
                )
                addBtn(
                    makeButton("导出并分享", primary = false, danger = false, full = false).apply {
                        setOnClickListener {
                            com.cardesk.launcher.core.log.LogTools.exportAndShare(context) { msg ->
                                if (msg.isNotEmpty()) com.cardesk.launcher.core.log.LogTools.toast(context, msg)
                            }
                        }
                    },
                    BUTTON_GAP,
                )
                addBtn(
                    makeButton("清空日志", primary = false, danger = true, full = false).apply {
                        setOnClickListener {
                            com.cardesk.launcher.core.log.LogTools.clear(context) { msg ->
                                com.cardesk.launcher.core.log.LogTools.toast(context, msg)
                                select("logs")
                            }
                        }
                    },
                    BUTTON_GAP,
                )
                hostCol.addView(btnRow, LinearLayout.LayoutParams(-1, -2))
            }
            inColumn(right) {
                recentLogColumn()
            }
        }
    }

    /**
     * 右侧「最近日志」窄栏（画板 s25 右栏，x1273..1866）。
     *
     * 画板是**结构化**的每条一行：`HH:mm:ss` + 级别小徽标 + `Tag 消息` + 1px 分隔线。
     * 落盘格式见 [com.cardesk.launcher.core.log.LogFormat]：
     * `MM-dd HH:mm:ss.SSS <级别> <tag> : <消息> [ key=value… ]`。
     * 这里按空格切成 4 段渲染；切不出来的行（比如「(暂无日志)」）原样显示一行，
     * 不让一条格式怪的日志把整个列表搞坏。
     */
    private fun recentLogColumn() {
        section("最近日志")
        val raw = com.cardesk.launcher.core.log.LogTools.preview(LOG_PREVIEW_LINES)
        val lines = raw.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty() || raw.startsWith("(暂无日志)")) {
            noteRaw("(暂无日志)")
            return
        }
        lines.forEach { line ->
            val entry = parseLogLine(line)
            val holder = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
            }
            holder.addView(
                Ui.text(context, entry.time, LOG_TIME_TEXT, pal.textFaint).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(LOG_TIME_W, -1),
            )
            holder.addView(
                Ui.text(context, entry.level.label, LOG_BADGE_TEXT, levelColor(entry.level)).apply {
                    gravity = Gravity.CENTER
                    background = Ui.round(
                        pal.withAlpha(levelColor(entry.level), PILL_TAG_FILL_ALPHA),
                        BADGE_RADIUS,
                    )
                },
                LinearLayout.LayoutParams(LOG_BADGE_W, LOG_BADGE_H).also { it.leftMargin = 6 },
            )
            holder.addView(
                Ui.text(context, entry.body, LOG_BODY_TEXT, pal.textDim).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -1, 1f).also { it.leftMargin = 8 },
            )
            hostCol.addView(holder, LinearLayout.LayoutParams(-1, LOG_LINE_H))
            hairline()
        }
    }

    /** 一条日志的三段（时间 / 级别 / 正文），见 [recentLogColumn]。 */
    private data class LogEntry(val time: String, val level: LogLevel, val body: String)

    /**
     * 解析一行落盘日志。
     *
     * 形如 `11-22 09:12:33.123 INFO CarDesk: 设置变更 key=…`：
     * 前两段是日期时间、第三段是级别名、剩下全是正文。
     * 认不出来时按 INFO 处理、时间留空 —— **不丢行**，比丢掉更能说明问题。
     */
    private fun parseLogLine(line: String): LogEntry {
        val parts = line.trim().split(' ', limit = 4)
        if (parts.size < 4) {
            return LogEntry("", LogLevel.INFO, line.trim())
        }
        val time = when {
            parts[1].length >= 8 -> parts[1].substring(0, 8) // HH:mm:ss（丢掉毫秒）
            else -> parts[1]
        }
        val level = LogLevel.entries.firstOrNull {
            it.name.equals(parts[2], ignoreCase = true) || it.label.equals(parts[2], ignoreCase = true)
        } ?: LogLevel.INFO
        return LogEntry(time, level, parts[3])
    }

    /** 级别徽标颜色（全部从调色板派生，不引入字面色值）。 */
    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.ERROR -> pal.danger
        LogLevel.WARN -> pal.warn
        LogLevel.INFO -> pal.accent
        LogLevel.DEBUG -> pal.textDim
    }

    /**
     * 真实占用 → 人读文本（12.4 MB / 823 KB / 512 B）。
     *
     * 画板写的是「12.4 MB」：保留一位小数，和画板的量级口径一致。
     * 这里**只格式化真实字节数**，没有任何兜底假值。
     */
    private fun bytesText(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 局域网传输。 */
    private fun pageLan() {
        val status = Ui.text(context, "", 17f, pal.textDim).apply { setLineSpacing(6f, 1f) }
        hostCol.addView(status, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 })

        // —— 二维码（第六十七轮）：手机扫一下就能打开，不用手动敲地址 ——
        //
        // 原来只给一行 `http://192.168.x.x:18765/?t=<token>` 文本，
        // 用户得在电脑上把它敲进去 —— IP + 端口 + token，敲错一个字符就白搭。
        val qr = QrCodeView(context).apply { preferredSize = QR_SIZE_PX }
        hostCol.addView(qr, LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX).also { it.topMargin = 18 })
        val qrHint = Ui.text(context, "", 15f, pal.textDim)
        hostCol.addView(qrHint, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 })

        val toggle = actionButton("启动局域网传输", primary = true, full = true) {}
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun fmtSize(bytes: Long): String = when {
            bytes >= 1 shl 20 -> String.format("%.1f MB", bytes / 1048576f)
            bytes >= 1 shl 10 -> String.format("%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }
        fun paintFiles() {
            listBox.removeAllViews()
            val files = LanTransferServer.rootDir(context).listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            section("共享目录文件（${files.size} 个）")
            // 第六十九轮：目录路径**始终**显示 ——
            // 传输目录搬到了公共 Download 目录，用户得一眼看到文件存哪儿
            note("保存路径：${LanTransferServer.rootDir(context).absolutePath}")
            if (files.isEmpty()) {
                return
            }
            for (f in files.take(10)) {
                kvRow(f.name, fmtSize(f.length()), true)
            }
            if (files.size > 10) note("… 还有 ${files.size - 10} 个文件")
        }
        fun paint() {
            if (LanTransferServer.running) {
                val url = LanTransferServer.url(context)
                status.text = "运行中 · $url\n目录：" + LanTransferServer.rootDir(context).absolutePath
                toggle.text = "停止服务"
                // —— 二维码跟着地址走（token / 端口变了都要变）
                qr.text = url
                qrHint.text = "手机扫这个码，直接打开上传 / 下载页面"
            } else {
                status.text = "未启动。端口顺序探测 18765..18775、URL 必须带 token；启动后把地址贴到电脑浏览器即可上传/下载。"
                toggle.text = "启动局域网传输"
                // 没启动时没有地址 → 不画码（留白比画一个扫不通的码强）
                qr.text = ""
                qrHint.text = "启动服务后这里会出现二维码"
            }
        }
        toggle.setOnClickListener {
            if (LanTransferServer.running) {
                // 排障场景：服务停不掉 —— 记下停止动作
                Logx.event(LogTags.LAN, "停止局域网传输")
                LanTransferServer.stop()
                paint()
                return@setOnClickListener
            }
            val root = LanTransferServer.rootDir(context)
            if (root.listFiles().isNullOrEmpty()) {
                File(root, "欢迎使用.txt").writeText("把文件放到这个目录即可在车机与电脑之间传输。")
                // 排障场景：共享目录是空的 —— 记下已放入的欢迎文件
                Logx.i(LogTags.LAN, "共享目录为空，已写入欢迎文件", "dir" to root.absolutePath)
            }
            // 排障场景：电脑连不上 —— 记下启动动作（端口/token 在 LanTransferServer 里）
            Logx.event(LogTags.LAN, "启动局域网传输", "dir" to root.absolutePath)
            LanTransferServer.start(
                context,
                onStarted = {
                    Logx.i(LogTags.LAN, "局域网传输启动回调成功", "url" to it)
                    Toast.makeText(context, "已启动：$it", Toast.LENGTH_LONG).show()
                    paint()
                    select(selectedKey)
                },
                onError = {
                    // 排障场景：服务起不来 —— 记下失败原因
                    Logx.w(LogTags.LAN, "局域网传输启动失败", "reason" to it)
                    Toast.makeText(context, "启动失败：$it", Toast.LENGTH_LONG).show()
                },
            )
            paint()
        }
        actionButton("刷新文件列表", full = true) { select(selectedKey) }
        hostCol.addView(listBox, LinearLayout.LayoutParams(-1, -2))
        paint()
        paintFiles()
    }

    /**
 * 云端应用库（第八十四轮新增）。
 *
 * ## 它解决什么
 *
 * 车机要装别的 App（导航、音乐、视频…）时，原来只有两条路：
 *
 * - 带 U 盘到车上插；
 * - 在车上让电脑和车机连**同一个局域网**，用「局域网传输」推过去。
 *
 * 两条都要求**人在车旁边**。这里是第三条路：手机上把 APK 传到
 * Cloudflare R2，车机在这一页直接下载并安装 ——
 * **出门前传好，到车上不用插 U 盘，也不用和车机连同一个局域网**。
 *
 * 清单在 `cardesk/apks.json`（格式见 `core/apkstore/ApkIndex.kt`），
 * 由上传端维护（手机上的 Worker 网页，或 `tools/publish_apks.py`）。
 *
 * ## 为什么进页面就自动拉一次清单
 *
 * 「在线更新」桌面启动 3 秒后自动检测一次，也可以点「检查更新」手动联网——
 * 那是后台更新，怕的是自动下载、自动弹窗。
 * 这一页不一样：**用户点进这一页就是想看有哪些包**，自动拉一次清单
 * 正是这一页存在的意义；而且拉清单只是几 KB，不下载任何东西。
 * 真正的下载仍然要用户点每个包自己的「下载安装」。
 */
    private fun pageApkStore() {
        section("云端应用库")
        kvRow("应用源", "Cloudflare R2 · file.chuxi.bid", true)

        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // 离开这一页就别再往已经不存在的视图上贴东西了（和氛围灯页同一个道理）
        var alive = true
        pageTeardown = { alive = false }

        val status = Ui.text(context, "状态：正在拉清单…", 22f, pal.textDim)
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var currentItems: List<ApkEntry> = emptyList()
        var openDialog: View? = null
        // 列表重画函数（先声明后赋值：openDownload 要回调它，而它要先建行）
        var rerender: (() -> Unit)? = null

        fun fmtSize(bytes: Long): String = when {
            bytes >= 1 shl 20 -> String.format("%.1f MB", bytes / 1048576f)
            bytes >= 1 shl 10 -> String.format("%.0f KB", bytes / 1024f)
            else -> "$bytes B"
        }

        /**
         * 关掉下载弹窗。
         *
         * ## 为什么要分「带动画」和「立刻摘」
         *
         * 弹窗继承 [com.cardesk.launcher.ui.dialogs.DialogSheet]，
         * 退场（下滑 + 淡出）由它自己播 —— 播完再把这一层摘掉，
         * 否则视图已经不在树上了，动画根本看不见。
         *
         * 但**换页**和**打开另一个弹窗**时不能等：
         * 旧的还挂在树上，新的又要加进来，两层会叠在一起。
         * 那两种情况立刻摘。
         *
         * @param animate true = 播退场动画后再摘层
         */
        fun dismissDialog(animate: Boolean = true) {
            val dialog = openDialog ?: return
            openDialog = null
            val sheet = dialog as? com.cardesk.launcher.ui.dialogs.DialogSheet
            if (animate && sheet != null) {
                sheet.dismissAnimated { removeView(dialog) }
            } else {
                removeView(dialog)
            }
        }

        /** 打开下载进度弹窗（弹窗自己负责下载、安装和结果提示）。 */
        fun openDownload(entry: ApkEntry) {
            // 换弹窗：旧的立刻摘（不能跟新的叠在一起）。
            dismissDialog(animate = false)
            val dialog = com.cardesk.launcher.ui.dialogs.CloudDownloadDialog(
                context, pal, entry,
                onInstalled = {
                    prefs.markCloudInstalled(entry.sha256, entry.file)
                    if (alive) rerender?.invoke()
                },
            )
            dialog.onClose = { dismissDialog() }
            openDialog = dialog
            addView(dialog, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        /**
         * 一个应用一张卡：序号 + 名称/文件名/大小/日期/SHA + 状态 + 按钮。
         *
         * 第八十四轮那版只有「文件名 + 一行 meta」，几个包都是 `.apk` 结尾时
         * 很难分辨谁是谁（用户反馈「不清晰」）—— 这里把能区分的字段都摊开，
         * 序号和状态也让「下载中/已安装」一眼可见。
         */
        fun apkRow(entry: ApkEntry, index: Int): View =
            com.cardesk.launcher.ui.widget.CloudAppRowView.build(
                context, pal, entry, index,
                installed = prefs.isCloudInstalled(entry.sha256, entry.file),
                onDownload = { openDownload(it) },
            )

        /** 重新画列表。 */
        fun render(items: List<ApkEntry>) {
            rerender = { render(currentItems) }
            currentItems = items
            listBox.removeAllViews()
            if (items.isEmpty()) {
                listBox.addView(
                    Ui.text(
                        context,
                        "云端还没有应用。",
                        22f,
                        pal.textDim,
                    ),
                    LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 16 },
                )
                return
            }
            for ((index, entry) in items.withIndex()) {
                listBox.addView(
                    apkRow(entry, index),
                    LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 16 },
                )
            }
        }

        /** 拉一次清单（后台线程）。 */
        fun refresh() {
            status.text = "状态：正在拉清单…"
            Thread {
                val result = ApkStore.fetchIndex(context)
                main.post {
                    if (!alive) return@post
                    when (result) {
                        is ApkStore.FetchResult.Ok -> {
                            val count = result.index.items.size
                            val when0 = result.index.updatedAt.take(10)
                            status.text = if (count == 0) {
                                "状态：云端还没有应用"
                            } else {
                                "状态：云端有 $count 个应用" + if (when0.isNotEmpty()) "（清单更新于 $when0）" else ""
                            }
                            render(result.index.items)
                        }
                        is ApkStore.FetchResult.Failed -> {
                            // 拉不到清单时**不清空列表**：可能只是这一次网络抖了，
                            // 把刚才看到的包抹掉比不刷新更让人火大
                            status.text = "状态：拉清单失败（${result.reason}）"
                        }
                    }
                }
            }.start()
        }

        // 按钮挂在列表上面：先建按钮，再把状态和列表加进 body
        pillButton(body, "刷新列表", primary = true) { refresh() }
        hostCol.addView(status, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 12 })
        hostCol.addView(listBox, LinearLayout.LayoutParams(-1, -2))

        pageTeardown = {
            alive = false
            // 离开这一页：弹窗立刻摘（它不属于正文，再留 240ms 只会挡住新页面）。
            dismissDialog(animate = false)
        }
        refresh()
    }

    /**
 * 在线更新（第六十九轮重写，第八十三轮换源）。
 *
 * 原来这一页让用户手填一个 APK 地址（`prefs.updateUrl`）—— 填错就更新不了，
 * 地址变了还得重填；接着改成读 GitHub Release；
 * 第八十三轮改成读 **Cloudflare R2 自定义域**上的更新清单
 * （`file.chuxi.bid/cardesk/latest.json`，格式见 `core/update/R2Release.kt`）——
 * 车机在国内访问 `api.github.com` 又慢又不稳，R2 走 Cloudflare 自己的 CDN。
 *
 * **启动检测一次 + 手动**：桌面启动 3 秒后联网查一次，
 * 桌面不在后台自动检测、也不会自动下载。
 */
    private fun pageUpdate() {
        section("更新通道")
        // 第三百零九轮：更新分两条通道 —— 正式版 / 开发版。
        // 选哪条就查哪条清单（R2 上是两份独立清单、两套包），
        // 正式版用户不会被开发版的更新弹窗打扰，反之亦然。
        val channels = listOf(
            com.cardesk.launcher.core.update.AppUpdate.CHANNEL_STABLE to "正式版",
            com.cardesk.launcher.core.update.AppUpdate.CHANNEL_PREVIEW to "开发版",
        )
        val currentChannel = com.cardesk.launcher.core.update.AppUpdate.normalizeChannel(
            prefs.updateChannel,
        )
        dropdownRow(
            label = "更新渠道",
            options = channels.map { it.second },
            selectedIndex = channels.indexOfFirst { it.first == currentChannel }.coerceAtLeast(0),
        ) { index ->
            val picked = channels.getOrNull(index)?.first
                ?: com.cardesk.launcher.core.update.AppUpdate.CHANNEL_STABLE
            prefs.updateChannel = picked
            logSetting(
                "update_channel", "更新渠道", currentChannel, picked,
                tag = LogTags.SETTING,
                "label" to com.cardesk.launcher.core.update.AppUpdate.channelLabel(picked),
                "manifest" to com.cardesk.launcher.core.update.AppUpdate.manifestPath(picked),
            )
            onApply?.invoke()
            // 「更新源」那行要跟着换 → 重画这一页（下拉此刻已收起，重建最省心）。
            select(selectedKey)
        }
        note("正式版只推验证过的稳定包；开发版更新更早、可能不稳（版本号带 -preview）")
        kvRow(
            "更新源",
            "Cloudflare R2 · " + com.cardesk.launcher.core.update.AppUpdate.manifestPath(currentChannel),
            true,
        )
        kvRow("当前版本", com.cardesk.launcher.core.update.AppUpdate.versionLabel(context), true)
        actionButton("检查更新", primary = true, full = true) {
            // 排障场景：点了检查更新没反应 —— 记下打开更新对话框
            Logx.event(LogTags.SETTING, "打开在线更新对话框")
            onShowUpdate?.invoke()
        }
        actionButton("系统能力自检", full = true) {
            val report = com.cardesk.launcher.core.SystemOps.capabilityReport(context)
            val text = report.joinToString("\n") { (cap, ok) -> "${if (ok) "✔" else "✘"} ${cap.label}" }
            // 排障场景：系统能力自检结果 —— 记下哪几项可用
            Logx.i(
                LogTags.SYS,
                "系统能力自检",
                "caps" to report.joinToString(",") { "${it.first.label}=${if (it.second) "可用" else "不可用"}" },
            )
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    /**
 * 氛围灯（第七十轮新增）。
 *
 * 这一页做两件事：
 *
 * 1. **设置项**：开关 / 颜色模式 / 固定色 / 灵敏度 / 亮度上下限；
 * 2. **频谱预览**：实时显示正在采集的频段（低频在左、高频在右），
 *    以及按当前设置算出来的颜色 —— 让「频谱解析」这件事**看得见**。
 *
 * ## 这一页现在真的**接到控制器**了
 *
 * 以前五个设置项只写 Prefs + 日志，**完全不通知控制器** ——
 * 改完设置，灯还是老样子（要等下一次进程重启才可能生效）。
 * 现在每一项改完都立刻下发（见 `AmbientLightController.applySettings`
 * 与 `AmbientLightController.setBrightness`），不用等下一帧频谱。
 *
 * ## 录音权限在**这一页**才要
 *
 * 频谱采集必须要 `RECORD_AUDIO`。
 * 启动时弹窗会打断首屏观感，而用户点进「氛围灯」这一页就说明
 * 他要用这个功能了 —— 这时弹权限框最自然（见 `ensureAmbientAudioPermission`）。
 */
    private fun pageAmbient() {
        section("音乐律动氛围灯")
        switchRow("启用氛围灯", prefs.ambientEnabled) { enabled ->
            com.cardesk.launcher.core.ambient.AmbientLightController.setEnabled(context, enabled)
            onApply?.invoke()
            // 开灯是要采频谱的 → 没权限就在这里要（用户正要开这个功能）
            if (enabled) ensureAmbientAudioPermission()
            // 状态文字会变（启用 → 采集 / 关闭 → 未启动）→ 重建这一页
            select(selectedKey)
        }
        switchRow("仅夜间模式启用", prefs.ambientNightOnly) { nightOnly ->
            val from = prefs.ambientNightOnly
            prefs.ambientNightOnly = nightOnly
            logSetting("ambient_night_only", "仅夜间模式启用氛围灯", from, nightOnly)
            com.cardesk.launcher.core.ambient.AmbientLightController.syncPolicy(context)
            onApply?.invoke()
            select(selectedKey)
        }
        val statusReadout = kvRow(
            "频谱状态",
            com.cardesk.launcher.core.ambient.AmbientLightController.statusText(),
            true,
        )
        val colorReadout = kvRow("当前颜色", "（等待音乐）", true)

        section("频谱预览")
        val spectrum = SpectrumView(context)
        bodyField(spectrum, height = 150)

        section("颜色与亮度")
        chipRow("颜色模式", com.cardesk.launcher.core.ambient.AmbientPalette.MODE_LABELS.toList(), prefs.ambientMode) { index ->
            val from = prefs.ambientMode
            prefs.ambientMode = index
            logSetting("ambient_mode", "氛围灯颜色模式", from, index)
            // 立刻生效：按新的模式重算颜色并下发
            com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "颜色模式")
            onApply?.invoke()
            // 固定色那一行只在「固定色」模式下出现 → 重建这一页
            select(selectedKey)
        }
        if (prefs.ambientMode == com.cardesk.launcher.core.ambient.AmbientPalette.MODE_FIXED) {
            val hues = com.cardesk.launcher.core.ambient.AmbientPalette.FIXED_COLORS.map { it.first }
            val labels = com.cardesk.launcher.core.ambient.AmbientPalette.FIXED_COLOR_LABELS
            val index = hues.indexOf(prefs.ambientFixedHue).takeIf { it >= 0 } ?: 0
            chipRow("固定色", labels, index) { picked ->
                val from = prefs.ambientFixedHue
                val to = hues.getOrElse(picked) { hues[0] }
                prefs.ambientFixedHue = to
                logSetting("ambient_fixed_hue", "氛围灯固定色", from, to)
                // 立刻生效：按新的固定色重算并下发
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "固定色")
                onApply?.invoke()
            }
        }
        if (prefs.ambientEngine != Prefs.ENGINE_V2 || prefs.ambientCustomMode) {
        sliderRow("灵敏度", prefs.ambientSensitivity, 0, 100, "%", format = { "$it" }) { v ->
            val from = prefs.ambientSensitivity
            prefs.ambientSensitivity = v
            logSetting("ambient_sensitivity", "氛围灯灵敏度", from, v)
            // 立刻生效：灵敏度决定「弱音有多亮」
            com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "灵敏度")
        }
        }
        // 亮度上下限是**旧版链路**的参数（旧版是「下限 + (上限−下限)×电平^γ」
        // 的线性映射）。新版用「基础亮度 + 感知明度区间映射」，压根不读这两个值
        // —— 放在这儿拖了没反应，只能藏起来。
        if (prefs.ambientEngine == Prefs.ENGINE_LEGACY) {
            sliderRow("最低亮度", prefs.ambientMinBrightness, 0, 100, "%") { v ->
                val from = prefs.ambientMinBrightness
                prefs.ambientMinBrightness = v
                logSetting("ambient_min_brightness", "氛围灯最低亮度", from, v)
                // 亮度滑杆走**绝对亮度**（用户拖到哪就是哪），并立刻重算颜色
                com.cardesk.launcher.core.ambient.AmbientLightController.setBrightness(context, v)
            }
            sliderRow("最高亮度", prefs.ambientMaxBrightness, 0, 100, "%") { v ->
                val from = prefs.ambientMaxBrightness
                prefs.ambientMaxBrightness = v
                logSetting("ambient_max_brightness", "氛围灯最高亮度", from, v)
                // 亮度滑杆走**绝对亮度**（用户拖到哪就是哪），并立刻重算颜色
                com.cardesk.launcher.core.ambient.AmbientLightController.setBrightness(context, v)
            }
        } else {
        }

        // ————————————————————————————————————————————————
        // 第八十八轮 V2：动感引擎
        // ————————————————————————————————————————————————
        section("动感引擎")
        chipRow(
            "引擎",
            listOf("新版（流畅）", "旧版（现网观感）"),
            prefs.ambientEngine,
        ) { index ->
            val from = prefs.ambientEngine
            prefs.ambientEngine = index
            logSetting("ambient_engine", "氛围灯引擎", from, index)
            // 立刻生效：换引擎要把运动状态一起重置（别把旧引擎的色相带过来）
            com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "引擎")
            onApply?.invoke()
            // 参数区随引擎显隐 → 重建这一页
            select(selectedKey)
        }
        if (prefs.ambientEngine == Prefs.ENGINE_V2) {
            val presetIndex = com.cardesk.launcher.core.ambient.AmbientPresets.match(
                prefs.ambientAttackMs,
                prefs.ambientDecayMs,
                prefs.ambientHueTauMs,
                prefs.ambientHueMaxDeg,
                prefs.ambientHueMaxAccel,
                prefs.ambientMaxDeltaETenths,
                prefs.ambientFloor,
                prefs.ambientBreathHundredths,
                prefs.ambientSaturationHundredths,
                prefs.ambientHueSpanDeg,
                prefs.ambientCentroidWindowLowHundredths,
                prefs.ambientCentroidWindowSpanHundredths,
                prefs.ambientHueRotateDegPerSec,
                prefs.ambientMaxChromaHundredths,
                prefs.ambientHueJumpDeg,
                prefs.ambientPulseGainHundredths,
                prefs.ambientBeatMinGapMs,
                prefs.ambientJumpHoldMs,
            )
            val customMode = prefs.ambientCustomMode
            // 改预设 / 开关自定义都要重建这一页（滑杆显隐变了）。
            // 重建会 `scrollTo(0,0)`，在车上等于「点一下页面就跳回顶部」——
            // 所以重建前记住位置、重建后滚回原处。
            fun rebuildKeepingScroll() {
                val y = bodyScroll.scrollY
                select(selectedKey)
                bodyScroll.post { bodyScroll.scrollTo(0, y) }
            }
            // 6 个预设 + 标签在 1920 下会超出右边界（「跳跃」被裁半截）⇒ 收紧内边距
            chipRow(
                "预设",
                com.cardesk.launcher.core.ambient.AmbientPresets.NAMES,
                presetIndex,
                compact = true,
            ) { index ->
                val p = com.cardesk.launcher.core.ambient.AmbientPresets.ALL[index]
                prefs.ambientAttackMs = p.attackMs
                prefs.ambientDecayMs = p.decayMs
                prefs.ambientHueTauMs = p.hueTauMs
                prefs.ambientHueMaxDeg = p.hueMaxDeg
                prefs.ambientHueMaxAccel = p.hueMaxAccel
                prefs.ambientMaxDeltaETenths = p.maxDeltaETenths
                prefs.ambientFloor = p.floor
                prefs.ambientBreathHundredths = p.breathHundredths
                prefs.ambientSaturationHundredths = p.saturationHundredths
                // 五彩三件套：跨度 / 质心展开窗口 / 彩度上限，外加色相自转
                prefs.ambientHueSpanDeg = p.hueSpanDeg
                prefs.ambientCentroidWindowLowHundredths = p.centroidWindowLowHundredths
                prefs.ambientCentroidWindowSpanHundredths = p.centroidWindowSpanHundredths
                prefs.ambientHueRotateDegPerSec = p.hueRotateDegPerSec
                prefs.ambientMaxChromaHundredths = p.maxChromaHundredths
                // 跳跃三件套：色相跳变档位 / 起拍闪光 / 起拍最短间隔
                prefs.ambientHueJumpDeg = p.hueJumpDeg
                prefs.ambientPulseGainHundredths = p.pulseGainHundredths
                prefs.ambientBeatMinGapMs = p.beatMinGapMs
                prefs.ambientJumpHoldMs = p.jumpHoldMs
                // 套预设 = 回到预设页（滑杆收起），自定义开关也关掉
                prefs.ambientCustomMode = false
                logSetting("ambient_preset", "氛围灯预设", presetIndex, p.name)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "预设")
                onApply?.invoke()
                // 滑杆读数变了 → 重建这一页（保留滚动位置）
                rebuildKeepingScroll()
            }
            // 自定义模式入口：**单独一行**，不塞进预设 chip 行。
            // 车机屏宽 1920，预设行已经占满（6 个预设 + 一个「自定义」会溢出成横向滚动，
            // 入口看不见 = 找不到），所以用一个显式的开关行。
            switchRow(
                "自定义参数",
                customMode,
                "关：只看预设；开：展开全部滑杆（当前参数不变）",
            ) { on ->
                prefs.ambientCustomMode = on
                logSetting("ambient_custom_mode", "氛围灯自定义参数", !on, on)
                rebuildKeepingScroll()
            }
            if (customMode) {
            sliderRow("亮度起 attack", prefs.ambientAttackMs, 10, 300, "ms", step = 5) { v ->
                val from = prefs.ambientAttackMs
                prefs.ambientAttackMs = v
                logSettingDebug("ambient_attack_ms", "氛围灯亮度起", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "亮度起")
            }
            sliderRow("亮度落 decay", prefs.ambientDecayMs, 60, 1200, "ms", step = 20) { v ->
                val from = prefs.ambientDecayMs
                prefs.ambientDecayMs = v
                logSettingDebug("ambient_decay_ms", "氛围灯亮度落", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "亮度落")
            }
            sliderRow("色相平滑 τ", prefs.ambientHueTauMs, 60, 1200, "ms", step = 20) { v ->
                val from = prefs.ambientHueTauMs
                prefs.ambientHueTauMs = v
                logSettingDebug("ambient_hue_tau_ms", "氛围灯色相平滑", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色相平滑")
            }
            sliderRow("色相限速", prefs.ambientHueMaxDeg, 10, 180, "°/s") { v ->
                val from = prefs.ambientHueMaxDeg
                prefs.ambientHueMaxDeg = v
                logSettingDebug("ambient_hue_max_deg", "氛围灯色相限速", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色相限速")
            }
            sliderRow("色相限加速", prefs.ambientHueMaxAccel, 30, 800, "°/s²", step = 10) { v ->
                val from = prefs.ambientHueMaxAccel
                prefs.ambientHueMaxAccel = v
                logSettingDebug("ambient_hue_max_accel", "氛围灯色相限加速", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色相限加速")
            }
            sliderRow(
                "每帧色差上限",
                prefs.ambientMaxDeltaETenths,
                2,
                60,
                "",
                format = { "%.1f".format(prefs.ambientMaxDeltaE()) },
            ) { v ->
                val from = prefs.ambientMaxDeltaETenths
                prefs.ambientMaxDeltaETenths = v
                logSettingDebug("ambient_max_delta_e", "氛围灯每帧色差上限", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色差上限")
            }
            sliderRow("基础亮度", prefs.ambientFloor, 0, 30, "%") { v ->
                val from = prefs.ambientFloor
                prefs.ambientFloor = v
                logSettingDebug("ambient_floor", "氛围灯基础亮度", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "基础亮度")
            }
            sliderRow(
                "饱和度上限",
                prefs.ambientSaturationHundredths,
                0,
                100,
                "%",
            ) { v ->
                val from = prefs.ambientSaturationHundredths
                prefs.ambientSaturationHundredths = v
                logSettingDebug("ambient_saturation", "氛围灯饱和度", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "饱和度")
            }
            sliderRow(
                "色相跨度",
                prefs.ambientHueSpanDeg,
                150,
                360,
                "°",
                step = 10,
            ) { v ->
                val from = prefs.ambientHueSpanDeg
                prefs.ambientHueSpanDeg = v
                logSettingDebug("ambient_hue_span_deg", "氛围灯色相跨度", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色相跨度")
            }
            sliderRow(
                "色相自转",
                prefs.ambientHueRotateDegPerSec,
                0,
                30,
                "°/s",
            ) { v ->
                val from = prefs.ambientHueRotateDegPerSec
                prefs.ambientHueRotateDegPerSec = v
                logSettingDebug("ambient_hue_rotate", "氛围灯色相自转", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色相自转")
            }
            sliderRow(
                "彩度上限",
                prefs.ambientMaxChromaHundredths,
                10,
                40,
                "",
                format = { "%.2f".format(prefs.ambientMaxChromaValue()) },
            ) { v ->
                val from = prefs.ambientMaxChromaHundredths
                prefs.ambientMaxChromaHundredths = v
                logSettingDebug("ambient_max_chroma", "氛围灯彩度上限", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "彩度上限")
            }
            sliderRow(
                "色相跳变",
                prefs.ambientHueJumpDeg,
                0,
                180,
                "°",
                step = 15,
                format = { v -> if (v == 0) "关（连续）" else "${v}° 档" },
            ) { v ->
                val from = prefs.ambientHueJumpDeg
                prefs.ambientHueJumpDeg = v
                logSettingDebug("ambient_hue_jump_deg", "氛围灯色相跳变", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "色相跳变")
            }
            sliderRow(
                "起拍闪光",
                prefs.ambientPulseGainHundredths,
                0,
                200,
                "",
                step = 5,
                format = { "%.2f×".format(prefs.ambientPulseGainValue()) },
            ) { v ->
                val from = prefs.ambientPulseGainHundredths
                prefs.ambientPulseGainHundredths = v
                logSettingDebug("ambient_pulse_gain", "氛围灯起拍闪光", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "起拍闪光")
            }
            sliderRow(
                "起拍间隔",
                prefs.ambientBeatMinGapMs,
                80,
                600,
                "ms",
                step = 20,
            ) { v ->
                val from = prefs.ambientBeatMinGapMs
                prefs.ambientBeatMinGapMs = v
                logSettingDebug("ambient_beat_gap_ms", "氛围灯起拍间隔", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "起拍间隔")
            }
            sliderRow(
                "换色间隔",
                prefs.ambientJumpHoldMs,
                0,
                1000,
                "ms",
                step = 20,
                format = { v -> if (v == 0) "只跟拍子" else "${v} ms 强制换" },
            ) { v ->
                val from = prefs.ambientJumpHoldMs
                prefs.ambientJumpHoldMs = v
                logSettingDebug("ambient_jump_hold_ms", "氛围灯换色间隔", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "换色间隔")
            }
            sliderRow(
                "空闲呼吸",
                prefs.ambientBreathHundredths,
                0,
                40,
                "%",
                format = { "%.2f".format(prefs.ambientBreathValue()) },
            ) { v ->
                val from = prefs.ambientBreathHundredths
                prefs.ambientBreathHundredths = v
                logSettingDebug("ambient_breath", "氛围灯空闲呼吸", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "空闲呼吸")
            }
            } else {
                // 预设页只给一句效果说明：不列参数、不放滑杆。
                note(
                    com.cardesk.launcher.core.ambient.AmbientPresets.summaryOf(
                        if (presetIndex >= 0) com.cardesk.launcher.core.ambient.AmbientPresets.ALL[presetIndex].name
                        else com.cardesk.launcher.core.ambient.AmbientPresets.CUSTOM_NAME,
                    ),
                )
            }
        } else {
        }

        if (prefs.ambientEngine != Prefs.ENGINE_V2 || prefs.ambientCustomMode) {
        section("分区编排")
        switchRow("区域巡游", prefs.ambientZoneChoreo) { enabled ->
            val from = prefs.ambientZoneChoreo
            prefs.ambientZoneChoreo = enabled
            logSetting("ambient_zone_choreo", "氛围灯分区编排", from, enabled)
            com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "分区编排")
            onApply?.invoke()
        }
        if (prefs.ambientZoneChoreo) {
            sliderRow("分区驻留", prefs.ambientZoneDwellMs, 700, 2000, "ms", step = 50) { v ->
                val from = prefs.ambientZoneDwellMs
                prefs.ambientZoneDwellMs = v
                logSettingDebug("ambient_zone_dwell_ms", "氛围灯分区驻留", from, v)
                com.cardesk.launcher.core.ambient.AmbientLightController.applySettings(context, "分区驻留")
            }
        }

        }

        // 进这一页就把录音权限要到手（不打断首屏，这里才是自然的时机）
        ensureAmbientAudioPermission()

        // —— 实时刷新（频谱条 / 颜色 / 状态）
        val listener = object : com.cardesk.launcher.core.ambient.AmbientLightController.Listener {
            override fun onAmbientColor(color: com.cardesk.launcher.core.ambient.AmbientColor) {
                colorReadout.text = "${color.toHex()} · 亮度 ${color.brightness}%"
                spectrum.barColor = android.graphics.Color.rgb(color.r, color.g, color.b)
            }

            override fun onAmbientSpectrum(bands: FloatArray, level: Float) {
                spectrum.bands = bands
                spectrum.level = level
            }

            override fun onAmbientStatus(text: String) {
                statusReadout.text = text
            }
        }
        // 第七十九轮 P0：**注册与退订必须成对**。
        //
        // `addListener` 内部会立刻回调一次 `onAmbientStatus`（要改 TextView），
        // 万一那一步抛异常，监听器**已经进集合**了、而退订的 teardown 还没挂上
        // ⇒ 监听器泄漏 ⇒ 每进出一次页面就多一个回调者，回调数线性增长，越用越卡。
        //
        // 所以：**先挂上 teardown，再注册**；注册失败就把 teardown 撤掉。
        var registered = false
        pageTeardown = {
            if (registered) {
                runCatching {
                    com.cardesk.launcher.core.ambient.AmbientLightController.removeListener(listener)
                }.onFailure { Logx.w(LogTags.SETTING, "氛围灯页退订实时刷新失败", it) }
            }
        }
        runCatching {
            com.cardesk.launcher.core.ambient.AmbientLightController.addListener(listener)
            registered = true
        }.onFailure {
            Logx.w(LogTags.SETTING, "氛围灯页注册实时刷新失败", it)
            pageTeardown = null
        }
    }

    /**
     * 要录音权限（频谱采集用）。
     *
     * ## 为什么在这一页要，而不是启动时
     *
     * 启动就弹权限框会打断首屏观感，而频谱只是「氛围灯」这个可选功能的附属品。
     * 用户自己点进「氛围灯」设置页，说明他要用这个功能了 —— 这时弹最自然。
     *
     * 已经拿到了就什么都不做（不重复弹）。
     */
    private fun ensureAmbientAudioPermission() {
        val activity = context as? Activity ?: return
        if (com.cardesk.launcher.core.Permissions.hasRecordAudioAppOp(activity)) return
        runCatching {
            com.cardesk.launcher.core.Permissions.ensureRecordAudio(activity)
        }.onFailure { Logx.w(LogTags.SETTING, "申请录音权限失败", it) }
    }

    /**
     * 「关于」页（开源信息 + 免责声明）。
     *
     * ## 许可口径
     *
     * 依据仓库根目录 `LICENSE`：采用 **PolyForm Noncommercial License 1.0.0**，
     * 并附带「附加署名要求（Attribution Requirement）」：非商业用途免费使用、修改与分发，
     * 商业用途需作者书面授权，分发须保留版权声明、`Required Notice` 与 `LICENSE` 文件。
     */
    private fun pageAbout() {
        section("开源信息")
        kvRow("项目名称", "CarDesk · 车控桌面", true)
        kvRow("源码仓库", REPO_URL_TEXT, true)
        kvRow("当前版本", appVersionLabel(), true)
        kvRow("语言 / 依赖", "Kotlin · 纯 framework API · 无第三方运行时依赖", true)
        kvRow("开源许可", "PolyForm Noncommercial 1.0.0（附加署名要求）", true)
        note(
            "本软件按 PolyForm Noncommercial License 1.0.0 授权，并适用仓库 LICENSE 中的附加署名要求：\n" +
                "• 非商业用途免费：个人使用、学习研究、改装车机及非商业开源项目可自由使用、修改与分发。\n" +
                "• 商业用途需授权：用于商业产品或营利分发前须取得作者书面授权（GitHub Issue 或 328576666@qq.com）。\n" +
                "• 署名与保留：任何形式分发须保留版权声明、Required Notice（CarDesk — https://github.com/Bigcaibird/cardesk）及 LICENSE 文件，并声明来源。",
        )
        actionButton("打开项目主页", primary = true, full = true) { openRepoPage() }

        section("免责声明")
        paragraphs(DISCLAIMER_ITEMS)

        section("车辆信息")
        val vinRow = kvRow("车辆 VIN", "读取中", false)
        // 进这一页时再读（缓存命中是瞬时的）；读不到如实写「读不到」，不编。
        VinReader.refresh(context) { value ->
            val text = value ?: "读不到"
            vinRow.post { vinRow.text = text }
        }
    }

    /** 一组段落：每条单独一段，行距比 [note] 松一点，长文本读起来不挤。 */
    private fun paragraphs(items: List<String>) {
        for ((i, item) in items.withIndex()) {
            hostCol.addView(
                Ui.text(context, item, 20f, pal.textDim).apply { setLineSpacing(8f, 1f) },
                LinearLayout.LayoutParams(-1, -2).also { it.topMargin = if (i == 0) 6 else 16 },
            )
        }
    }

    /** 当前版本号（`1.0.1037（4133）`）。读不到就写「?」，不编。 */
    private fun appVersionLabel(): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageInfo(context.packageName, 0)
        val name = pi.versionName ?: "?"
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "$name（$code）"
    }.getOrDefault("?")

    /**
     * 打开项目主页。
     *
     * 车机上不一定有浏览器（很多车机系统不装）→ 打不开就**复制到剪贴板**，
     * 别让用户点了没反应。
     */
    private fun openRepoPage() {
        val opened = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(REPO_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        if (!opened) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("CarDesk 源码仓库", REPO_URL))
            }
            Toast.makeText(context, "没有可用浏览器，仓库地址已复制", Toast.LENGTH_LONG).show()
        }
        Logx.event(LogTags.SETTING, "打开项目主页", "url" to REPO_URL, "opened" to opened)
    }

    companion object {
        // ------------------------------------------------ 壳层几何（设计板 s01）
        //
        // 画板实测（1920×1080）：导航 x=24..396（宽 372）、内容 x=420..1896、
        // 两块面板 y=64..1056，面板之间 24px 空隙。
        // 这些数只描述**设计坐标**，实际布局按 [Design.W]/[Design.H] 算，
        // 小屏（compact）时整体收紧。

        /** 浮层左右外边距。 */
        const val SHELL_SIDE = 24

        /** 浮层上边距（画板 64：上面留出状态栏那条）。 */
        const val SHELL_TOP = 64

        /** 浮层下边距。 */
        const val SHELL_BOTTOM = 24

        /** 两块面板之间的空隙。 */
        const val SHELL_GAP = 24

        /**
         * 暗罩 alpha：**26%**（画板口径 `rgba(0,0,0,.26)`）。
         *
         * 只用 alpha、RGB 取当前主题 `scrim`：
         * 日间是浅灰、夜间是深墨蓝 —— 同一个「26%」在两种主题下都对，
         * 也不用新增一个硬编码颜色。
         */
        const val SHELL_SCRIM_ALPHA = 66

        /** 左侧导航面板宽。 */
        const val NAV_W = 372

        /** 浮层圆角（设计令牌：浮层 28 / 卡片 24 / 行 18 / 胶囊 999）。 */
        const val PANEL_RADIUS = 28f

        /** 卡片圆角。 */
        const val CARD_RADIUS = 24f

        /** 行 / 值胶囊 / 按钮的圆角。 */
        const val ROW_RADIUS = 18f

        /** 动作按钮圆角（画板的白条与实底条都是 18，不是胶囊）。 */
        const val BUTTON_RADIUS = 18f

        /** 胶囊圆角。 */
        const val PILL = 999f

        /** 右栏内容区左右内边距（画板：标题 x=450 相对面板 30）。 */
        const val CONTENT_PAD = 30

        /** 正文滚动区起点（相对内容面板顶）：标题 28px 占掉上面这一条。 */
        const val BODY_TOP = 84

        /** 页标题字号（28）：画板 28px 粗体。 */
        const val TITLE_SIZE = 28f

        /** 组标题字号（14）：画板的分组标签。 */
        const val GROUP_LABEL = 14f

        /** 组标题行高。 */
        const val GROUP_LABEL_H = 20

        /** 组间间距（画板口径 16）。 */
        const val GROUP_GAP = 16

        /** 统一行高：包含行底那条 1px 分割线（画板 128）。 */
        const val ROW_H = 128

        /** s05 的「默认媒体源」行高（画板 ~62，是行内下拉而不是整块行）。 */
        const val MEDIA_DEFAULT_ROW_H = 62

        /**
         * 紧凑行高（画板 s02 按键增强 106 / 112、s07 轮播区 76、s11 右栏 78）。
         *
         * 这些页面的画板行高**不是 128**：s02 底部那块「按键通路」自检区
         * 之所以被面板裁掉，就是因为六行 128 把 884px 的视口撑爆了。
         * 按画板量出来的档位收窄，按钮才落回面板内（画板 y986..1025）。
         */
        const val ROW_H_COMPACT = 106

        /** 更紧凑的一档（s07 / s11 右栏，画板 76~78）。 */
        const val ROW_H_TIGHT = 78

        /** 媒体源浮层内容视图的标记（测试与 [hidePopup] 都靠它认）。 */
        const val MEDIA_POPUP_TAG = "settings_media_popup"

        /** 媒体源浮层宽度（画板 11：361，与 SourceMenuLayer 同值）。 */
        const val MEDIA_POPUP_W = 361

        /** 媒体源浮层高度（画板 11：625）。 */
        const val MEDIA_POPUP_H = 625

        /** 浮层标题行高（与 SourceMenuLayer 的 HEADER_H 一致）。 */
        const val MEDIA_POPUP_HEADER_H = 44

        /** 浮层每行高（与 SourceMenuLayer 的 ITEM_H 一致）。 */
        const val MEDIA_POPUP_ROW_H = 64

        /** 滑杆行标签宽度（s08/s11 两栏布局下给滑杆留出可拖长度）。 */
        const val SLIDER_LABEL_W = 220

        /** 滑杆行读数宽度（「100%」「24 px」这类 4~6 字）。 */
        const val SLIDER_READOUT_W = 110

        /** 两栏正文之间的横向空隙（画板 s08：左栏到 991、右栏起 1025）。 */
        const val COLUMN_GAP = 34

        /** s20：画板左栏 450..940（宽 490），两栏间隙 20。 */
        const val ADB_LEFT_W = 490
        const val ADB_COL_GAP = 20

        /** s08 左栏（卡片风格）栏宽：面板内 x450..991。 */
        const val CARD_STYLE_COL_W = 541

        /**
         * s08 右栏「一张卡一行」的行高（画板 56~57，取 62 放下 44 高的胶囊）。
         *
         * 这一栏要塞 2 行纯开关 + 6 行「档位 + 显示 + 开关」+ 卡片库，
         * 用 ROW_H=128 会直接顶出面板。
         */
        const val CARD_SLOT_ROW_H = 62

        /** s08「卡片库」里那两颗窄按钮的高度（画板 42；受 [BUTTON_PAD] 上下内边距约束取 56）。 */
        const val CARD_LIB_BTN_H = 56

        // ---- s02 按键增强 ------------------------------------------------------

        /** 顶部「启用按键增强」开关行（画板 93）。 */
        const val KEY_SWITCH_ROW_H = 96

        /** 一个键位行（画板 单击 112 / 双击 106 / 长按 106）。 */
        const val KEY_ROW_H = 104

        /** 「目标驾驶模式」那行（画板 113，多一行副标题）。 */
        const val KEY_MODE_ROW_H = 110

        // ---- s05 媒体源 --------------------------------------------------------

        /** 两列里左栏的宽度：画板胶囊右边缘在 x1135（正文左 450 ⇒ 685）。 */
        const val MEDIA_COL_W = 685

        /** 一个来源行的高度（画板行距 59；胶囊 30 高，留上下呼吸）。 */
        const val MEDIA_ROW_H = 56

        // ---- s06 日夜模式 ------------------------------------------------------

        /** 三张「同步方式」卡片的高度（画板 y316..406 = 90，不是 96）。 */
        const val CARD_CHOICE_H = 90

        /** 卡片之间的横向空隙（画板 21）。 */
        const val CARD_CHOICE_GAP = 20

        // ---- s23 系统诊断 -------------------------------------------------------

        /** 一条「系统能力」行的高度（画板行距 ~56）。 */
        const val S23_CAP_ROW_H = 56

        // ---- s22 默认设置 -------------------------------------------------------

        /** 一行的高度（画板行距 ~96）与右对齐按钮高度。 */
        const val S22_ROW_H = 96
        const val S22_BTN_H = 48

        // ---- s24 重启车机 -------------------------------------------------------

        /** 居中警告块的顶部留白（画板图标上沿在面板顶下 276）。 */
        const val RESTART_TOP = 180

        /** 图标方块尺寸（画板 110×96、圆角 28）。 */
        const val RESTART_TILE_W = 110
        const val RESTART_TILE_H = 96
        const val RESTART_TILE_RADIUS = 28f

        /** 图标淡底透明度 / 弧线粗细（都从 `pal.danger` 派生，无新色值）。 */
        const val RESTART_TILE_FILL_ALPHA = 0x1F
        const val RESTART_GLYPH_STROKE = 3.4f

        /** 标题 / 说明 / 按钮的纵向间距（画板 26 / 20 / 30）。 */
        const val RESTART_TITLE_TOP = 26
        const val RESTART_NOTE_TOP = 20
        const val RESTART_BTN_TOP = 30

        /** 「立即重启」按钮宽度 / 高度（画板 320×64）。 */
        const val RESTART_BTN_W = 320
        const val RESTART_BTN_H = 64

        // ---- s09 歌词 ----------------------------------------------------------

        /** 左栏（首页中央歌词）栏宽：画板预览卡 x450..1068。 */
        const val LYRIC_COL_W = 618

        /** 歌词页两栏间距（画板 28）。 */
        const val LYRIC_COL_GAP = 28

        /** 歌词预览条高度（画板 618×175；留 180 让三行字放得下）。 */
        const val LYRIC_PREVIEW_H = 180

        // ---- s07 壁纸 ----------------------------------------------------------

        /** 左栏（当前 / 自定义壁纸）栏宽：面板内 x450..1048。 */
        const val WALLPAPER_COL_W = 598

        /** 当前壁纸大预览的高度（画板 561×333；按栏宽 598 等比取 330）。 */
        const val WALLPAPER_PREVIEW_H = 330

        /** 自定义壁纸那块框的高度（画板空框 318）。 */
        const val WALLPAPER_CUSTOM_BOX_H = 300

        /** 预设壁纸缩略图 / 名字行的高度（画板 107 + 19；右栏 3 行要放得下轮播和视频）。 */
        const val WALLPAPER_THUMB_H = 100
        const val WALLPAPER_THUMB_LABEL_H = 22

        // ---- s11 Dock 与导航栏 -------------------------------------------------

        /** 左栏（原车 Dock / 状态栏）栏宽：面板内 x450..1020。 */
        const val DOCK_COL_W = 570

        /** 右栏「槽位（N 项） + 编辑槽位」那一行的高度（画板 ~62）。 */
        const val DOCK_SLOT_ROW_H = 84

        /** 「编辑槽位」小按钮的高度（画板 42；受内边距约束取 48）。 */
        const val DOCK_SLOT_BTN_H = 48

        /** 行标题字号（画板 18 粗体）。 */
        const val ROW_TITLE = 18f

        /** 行说明字号（画板 14 弱字）。 */
        const val ROW_SUB = 14f

        /** 行内右侧控件与标题之间至少留出的距离。 */
        const val ROW_CONTROL_GAP = 24

        /** 紧凑状态行高（画板 s20「当前状态」/ s23「系统能力」约 47）。 */
        const val KV_ROW_H = 48

        /** 选择行的标签列宽（「轮播间隔」这种 4 个字的标签要放得下）。 */
        const val CHIP_LABEL_W = 300

        /** 胶囊标签高度（画板 s05「已安装」约 30）。 */
        const val PILL_TAG_H = 30

        /** 胶囊标签字号。 */
        const val PILL_TAG_TEXT = 13f

        /** 胶囊标签底色透明度（同色 12%）。 */
        const val PILL_TAG_FILL_ALPHA = 0x1F

        /** 胶囊标签描边透明度（同色 20%）。 */
        const val PILL_TAG_STROKE_ALPHA = 0x33

        /** 并排按钮之间的横向空隙。 */
        const val BUTTON_GAP = 16

        // ---- 诊断日志（画板 s25）---------------------------------------------

        /** 左栏宽度：面板内 x450..1240（右栏「最近日志」起 x1273）。 */
        const val LOG_LEFT_W = 790

        /** 日志级别那行 chips 的高度（画板 组标题下紧跟一行 chips，约 68）。 */
        const val LOG_CHIP_ROW_H = 68

        /** 日志页三枚横排按钮的高度（画板 50，取 56 兼顾点按）。 */
        const val LOG_BUTTON_H = 56

        /** 最近日志预览取多少行（画板右栏一屏约 20 行，取 40 可滚动看）。 */
        const val LOG_PREVIEW_LINES = 40

        /** 日志行内的时间字号 / 时间列宽。 */
        const val LOG_TIME_TEXT = 12f
        const val LOG_TIME_W = 66

        /** 级别徽标字号 / 尺寸 / 圆角。 */
        const val LOG_BADGE_TEXT = 11f
        const val LOG_BADGE_W = 18
        const val LOG_BADGE_H = 18
        const val BADGE_RADIUS = 5f

        /** 日志正文字号。 */
        const val LOG_BODY_TEXT = 13f

        /** 单条日志行高（含 1px 分隔线）。 */
        const val LOG_LINE_H = 26

        /** 导航内边距（画板选中胶囊距面板左右各 ~17）。 */
        const val NAV_PAD = 17

        /** 导航树起点（相对导航面板顶，与右栏标题上边距对齐）。 */
        const val TREE_TOP = 28

        /** 导航组标题字号（居中强调色分组头）。 */
        const val NAV_LABEL = 19f

        /** 导航条目字号（放大至 25px，配合 52px 触控行高降低车机行车误触）。 */
        const val NAV_ITEM = 25f

        /** 导航组标题左边距（相对面板）。 */
        const val TREE_GROUP_X = NAV_PAD

        /** 导航条目左边距（相对面板）。 */
        const val TREE_ITEM_X = NAV_PAD

        /** 导航条目宽（面板宽 - 左右各 17）。 */
        const val TREE_ITEM_W = NAV_W - NAV_PAD * 2

        /** 导航条目行高（车机防误触热区 52px）。 */
        const val TREE_ITEM_H = 52

        /** 导航条目行距（52px 行高 + 6px 间距，避免相邻项粘连误触）。 */
        const val TREE_ITEM_STEP = 58

        /** 组标题 → 首条目间距。 */
        const val TREE_GROUP_STEP = 38

        /** 组尾 → 下一组组标题间距。 */
        const val TREE_GROUP_END = 22

        /** 按钮左右内边距（整行按钮的文字缩进）。 */
        const val BUTTON_PAD = 24


        /**
         * **说明行**（[note] 生成的 TextView）的标记 —— 测试用。
         *
         * ## 为什么要有这个
         *
         * 第150轮把设置页里啰嗦的功能描述、需求解释全删了，
         * 只留状态、风险警告、法律声明这几类。
         * 但「删掉」很容易，「别又加回来」很难 ——
         * 光看源码里的 [note] 调用没法一眼数清还剩哪些说明文字。
         *
         * 所以给说明行打个标记，测试顺着视图树就能把它们**挑出来**，
         * 一条一条对着白名单核。
         */
        val NOTE_MARK = Any()

        /**
         * **开关行副标题**的标记 —— 测试用，理由同 [NOTE_MARK]。
         *
         * 副标题也是「功能描述」的主要载体（`switchRow` 的第三个实参）。
         */
        val SUBTITLE_MARK = Any()

        // ------------------------------------------------ 下拉选择（第151轮）

        /**
         * 下拉行的「当前值」标记。
         *
         * 测试要能断言「媒体源那一行显示的确实是当前选中的来源」，
         * 所以这个 TextView 得能**从视图树里被认出来**。
         */
        val DROPDOWN_VALUE_MARK = Any()

        /** 下拉展开后的**每个选项**的标记。 */
        val DROPDOWN_OPTION_MARK = Any()

        /**
         * chip 分段控件的标记。
         *
         * 有了它，测试才能断言「媒体源页**已经没有** chip 行了」——
         * 否则「改回 chip 行」这种回退没人拦。
         */
        val CHIP_SEG_MARK = Any()

        /**
         * 操作按钮（[actionButton]）的标记。
         *
         * 第151轮用它守住「按钮底边要留间距」——
         * 之前按钮底边直接贴住下一行的标题，看着像压在上面。
         */
        val ACTION_BUTTON_MARK = Any()

        /** 局域网传输页的二维码边长（设计 px）。 */
        const val QR_SIZE_PX = 300

        // -------------------------------------------------- 开源与声明（第六十八轮）

        /** 项目仓库地址（「开源与声明」页 + 「打开项目主页」用同一个）。 */
        const val REPO_URL = "https://github.com/Bigcaibird/cardesk"

        /** 页面上显示的仓库地址（去掉协议前缀，好读）。 */
        const val REPO_URL_TEXT = "github.com/Bigcaibird/cardesk"

        /**
         * 免责声明正文（一条一段）。
         *
         * 措辞口径：
         * - 与车厂无隶属 / 授权关系（第三方个人项目必写）；
         * - 会读取车辆状态并下发控制指令，风险自负；
         * - 按「现状」提供，不作担保；
         * - 遵守车辆使用规范与当地法规；
         * - 日志与局域网传输的知情说明。
         *
         * 放在 [companion object] 里是为了**改文案只改一处**。
         */
        val DISCLAIMER_ITEMS: List<String> = listOf(
            "1. 本项目为个人开发的第三方开源项目，与任何汽车厂商、供应商及其关联公司不存在隶属、" +
                "赞助、授权或合作关系；产品名称、商标与车型信息仅用于说明兼容性，相关权利归各自权利人所有。",
            "2. 本应用会通过系统接口读取车辆状态（空调、车门、车窗、续航、油量、胎压等），" +
                "并可能向车辆下发控制指令。安装、启用或使用本应用所引发的任何直接或间接后果，" +
                "包括但不限于车辆状态显示异常、第三方应用行为异常、系统不稳定或数据丢失等，均由使用者自行承担。",
            "3. 本应用按「现状」提供，不作任何明示或默示的担保，包括但不限于适销性、特定用途适用性、" +
                "不侵权及不受干扰的担保；在法律允许的最大范围内，作者不对使用本应用造成的任何损失承担责任。",
            "4. 使用前请自行确认车辆使用规范、当地法律法规与安全要求；因违反规定或不当使用造成的后果由使用者自行承担。",
            "5. 本应用的诊断日志只写入车机本地，不会自动上传；但「局域网传输」开启后会在同一局域网内提供这些文件，" +
                "请自行决定是否开启。发现本应用与车辆系统或其他应用存在冲突时，请停止使用并通过仓库反馈。",
        )

        /**
         * 旧壳层的固定尺寸（PANEL_H / PANEL_LEFT / PANEL_TOP / LEFT_W / CONTENT_W /
         * TREE_VIEW_H）已随「两块玻璃面板铺满画板」一起删掉：
         * 现在尺寸全部按 [Design.W]/[Design.H] 现算，见 [build]。
         */

        /**
         * 「没有底色」的透明色。
         *
         * 未选中的分段项 / 导航条目**看起来**是「没有背景」，
         * 但颜色要渐变就得有一个能改颜色的 drawable 可改 ——
         * 所以背景**始终存在**，未选中时画成透明（等于没有）。
         */
        private const val TRANSPARENT = 0x00000000

        /** 方控桥接开关（本应用自有的开关位；用车机 SDK 的 SessionBridge）。 */
        const val KEY_BRIDGE_ENABLED = "session_bridge_enabled"

        /** 按键动作清单。 */
        val KEY_ACTION_LABELS: List<String> = listOf("无", "切换驾驶模式", "360环影(开关)", "唤起语音助手")

        /** 按键动作编码。 */
        val KEY_ACTION_VALUES: List<Int> = listOf(
            Prefs.ACTION_NONE, Prefs.ACTION_DRIVE_MODE, Prefs.ACTION_360_TOGGLE, Prefs.ACTION_VOICE_ASSIST,
        )

        /** 360 视角延迟档位。 */
        val CAMERA360_DELAY_LABELS: List<String> = Prefs.CAMERA360_DELAY_LABELS.toList()

        /** 360 视角延迟取值。 */
        val CAMERA360_DELAY_MS: List<Int> = Prefs.CAMERA360_DELAYS.toList()

        /** 360 视角标签。 */
        val CAMERA360_VIEW_LABELS: List<String> = Prefs.CAMERA360_VIEW_LABELS.toList()

        /** 360 视角取值。 */
        val CAMERA360_VIEW_IDS: List<Int> = Prefs.CAMERA360_VIEW_IDS.toList()
    }
}

/**
 * 画板 s01 的开关：**64×36** 胶囊轨道 + 28 白圆点。
 *
 * 尺寸按画板量出来的比例（行高 128 里开关占 36，视觉重量和 18px 标题配得上）；
 * 共享的 `ToggleSwitch` 是 76×44，在 128 的行里偏胖，所以这里单独画一个 ——
 * 交互不变（点一下切换）。
 *
 * 两态：
 * - 开：accent 实底 + 白圆点在右；
 * - 关：**40% 白**轨道 + 白圆点在左（日间白玻璃上 40% 白看不见，压到 12%，见 [OFF_ALPHA_DAY]）。
 *
 * ## 动效
 *
 * 圆点从一头滑到另一头（[MotionSpec.KNOB_DURATION] = 200ms），
 * 不是「啪」地瞬移过去 —— 瞬移的话用户会以为是两张不同的图，
 * 滑过去才知道「这是个开关」。
 * 按下时也有反馈（[MotionSpec.PRESS_SCALE_ICON]），
 * 否则「点开关」和「点普通文字」手感一样。
 */
private class BoardSwitch(context: Context) : View(context) {

    /** 开启时的强调色。 */
    var tint: Int = Skin.ACCENT

    /** 是否开启。 */
    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // 还没挂到窗口上时是**初始状态**，不是「切换」——
            // 那时候播动画就成了「打开设置页时开关自己滑了一下」。
            if (isAttachedToWindow) {
                animateKnob(value)
            } else {
                knobCx = if (value) ON_CX else OFF_CX
                invalidate()
            }
        }

    /**
     * 圆点当前的横坐标（动画中间态也用它）。
     *
     * 之前是在 [onDraw] 里按 [checked] 现算的 ——
     * 那样圆点只能瞬移，看不出它滑过去。记成字段才能画动画中间态。
     */
    private var knobCx = OFF_CX

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        // 按下也要有反馈，否则「点开关」和「点普通文字」手感一样。
        Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(SW, SH)
    }

    /** 切换开关。 */
    fun toggle() {
        checked = !checked
    }

    /**
     * 圆点滑到新位置。
     *
     * 和对话框里的 [com.cardesk.launcher.ui.dialogs.FlatSwitch] 同一套做法：
     * [Motion.animateProgress] 每帧给一个 0→1 的进度，圆点位置在两端之间插值。
     */
    private fun animateKnob(target: Boolean) {
        val from = knobCx
        val to = if (target) ON_CX else OFF_CX
        Motion.animateProgress(this, MotionSpec.KNOB_DURATION) { t ->
            knobCx = from + (to - from) * t
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)

        // —— 轨道 ——
        //
        // 开启：accent 实底（画板口径）。
        // 关闭：**40% 白**（画板口径）。但 40% 白落在日间的白色玻璃上等于看不见，
        // 所以「40% 白」只在夜间成立；日间取同一 token（`text`）的 12%，
        // 落在白底上正好是画板那种浅灰 —— 同一个规则、两个亮度档，不新增色值。
        paint.style = Paint.Style.FILL
        paint.color = if (checked) {
            tint
        } else {
            // 这里拿不到 Palette（BoardSwitch 是文件内的顶层类），
            // 所以直接对 `Skin.TEXT` 取 alpha —— 等价于 `Palette.withAlpha`。
            val base = Skin.TEXT
            Color.argb(
                if (Skin.isNight) OFF_ALPHA_NIGHT else OFF_ALPHA_DAY,
                Color.red(base),
                Color.green(base),
                Color.blue(base),
            )
        }
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        // 描边**内缩 0.5**：1px 线压在边界上会有一半被裁掉，边缘看起来毛毛的。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = if (checked) tint else 0x00000000
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint)
        rect.set(0f, 0f, w, h)

        // —— 圆点 ——
        // 白圆点在两种主题下都要读得出来（画板的开关圆点始终是白的）。
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(knobCx, h / 2f, KNOB / 2f, paint)
    }

    companion object {
        /** 开关宽（设计 px）—— 画板行内开关约 64×36。 */
        const val SW = 64

        /** 开关高（设计 px）。 */
        const val SH = 36

        /** 圆点直径（设计 px）。 */
        const val KNOB = 28f

        /** 关闭态圆点圆心（设计 px）—— 轨道内留 4px。 */
        private const val OFF_CX = 4f + KNOB / 2f

        /** 开启态圆点圆心（设计 px）。 */
        private const val ON_CX = SW - 4f - KNOB / 2f

        /** 关闭态轨道的白度：夜间 40%（验收口径）。 */
        private const val OFF_ALPHA_NIGHT = 0x66

        /** 关闭态轨道的白度：日间 12%（40% 白在白玻璃上看不见，取同一 token 压暗）。 */
        private const val OFF_ALPHA_DAY = 0x1F
    }
}

/**
 * 「车况卡视角」设置行是否显示（第179轮）。
 *
 * 只有「写实车模开着」且「当前车型资产不是纯俯视」时才有意义：
 * 纯俯视资产下卡片固定俯视，三视角切换无效，整行隐藏（用户拍板）。
 */
internal fun showCardViewRow(car3dEnabled: Boolean, topDownOnly: Boolean): Boolean =
    car3dEnabled && !topDownOnly

