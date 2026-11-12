package com.cardesk.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.media.CoverBackdrop
import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.ui.pages.NavPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 日间模式下音乐卡的封面背景（第153轮）。
 *
 * ## 用户反馈
 *
 * > 「我在日间模式下点击第二页音乐卡片的图片，有提示开关图片背景，但实际无变化」
 *
 * ## 根因
 *
 * 日间是**白底深字**。为了让压在封面上的最弱那档小字（18px 时间行）仍有 3:1 对比度，
 * [CoverBackdrop.targetRange] 反推出的背景亮度区间是 **[0.9743, 1.0]** ——
 * 整张封面会被罩成一片**均匀的白**，和纯色卡面几乎一模一样。
 *
 * 于是「开封面背景」和「关封面背景」画出来像素级相同：提示弹了，画面没动。
 *
 * ## 修法：日间砍到**两档**文字 + 淡白罩
 *
 * 日间只留两档文字（歌名 `#191C23`、其余 `#565D6B`），
 * 最弱那档从 `#8B92A0`（L=0.2859）降到 `#565D6B`（L=0.1087）
 * → 背景亮度**下限**从 0.9743 降到 **0.4340**
 * → 白罩可以淡很多 → 封面还留得住一层灰调影子，
 * 同时卡片跟日间其它卡一样白（**风格统一**）。
 *
 * 夜间一行不改（深底浅字、三档、压暗封面）→ **零退化**。
 *
 * ## 这条测试锁住五件事
 *
 * ① 日间模式下卡面是**亮**的（跟日间其它卡一样白）；
 * ② 日间模式下文字是**深**的，而且只有**两档**（最弱那档不能是 `textFaint`）；
 * ③ 亮度区间是**下限**且由 `minBackgroundLuminance` 反推（不是旧路线的 0.9743）；
 * ④ 罩完之后封面**还认得出来**（没被冲成一片白）；
 * ⑤ **夜间零变化**。
 *
 * ## 一条最容易踩的坑：组合卡是**构建时**建出来的
 *
 * 组合卡的文字（歌名 / 歌手 / 歌词 / 进度条）都在 [NavPage] 的**构造期**建好，
 * 运行时切主题**不会**重建它们。
 *
 * 所以想测「日间模式」，必须让桌面**在日间下冷启动** ——
 * 否则测到的其实是「夜间下建好的卡」，
 * 断言再严也拦不住「代码改回跟主题走」这种改坏方式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavCoverBackdropDayModeTest {

    /**
     * 起一个**指定日夜**的桌面，返回 [LauncherView]。
     *
     * ## 为什么要在起桌面**之前**写 pref
     *
     * 桌面冷启动时的配色来自「上次应用的夜间态」（`daynight_applied_night_ui`）——
     * 总开关 `daynight_enabled` 默认关 → 直接回落它，没这个键就按夜间。
     *
     * 所以要**先**把这个键写成目标状态，桌面才会在目标主题下起来，
     * 组合卡也才会**在那个主题下被构建出来**。
     */
    private fun setupLauncher(night: Boolean): LauncherView {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("cardesk", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("daynight_enabled", false)
            .putBoolean("daynight_applied_night_ui", night)
            .commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return findByType(root, LauncherView::class.java)!!
    }

    private fun <T : View> findByType(view: View, type: Class<T>): T? {
        if (type.isInstance(view)) return type.cast(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findByType(view.getChildAt(i), type)?.let { return it }
        }
        return null
    }

    /**
     * 起一个指定日夜的组合卡。
     *
     * 桌面**在目标主题下**起来 → 组合卡也在那个主题下被构建 →
     * 再补一次 [NavPage.applyCardStyle]（运行时切卡片风格走的同一条路）。
     */
    private fun navOf(night: Boolean): NavPage {
        val launcher = setupLauncher(night)
        assertEquals(
            "桌面没在目标主题下起来 —— 这条用例的前提不成立（组合卡是构建时建出来的）",
            night,
            Skin.isNight,
        )
        // 单例状态兜底：把主题重新刷成目标状态，
        // 免得上一条用例改过 [Skin]（见 @After 的说明）把这里带偏。
        Skin.apply(if (night) Palettes.night else Palettes.day)
        val nav = findByType(launcher, NavPage::class.java) ?: error("桌面上找不到导航页")
        nav.applyCardStyle()
        shadowOf(Looper.getMainLooper()).idle()
        return nav
    }

    /**
     * 给背景层一个**设计尺寸**。
     *
     * 组合卡在导航页里是**带权重**的，测试环境里它拿不到真实尺寸
     * （导航页不是当前页 → 整个页面不参与布局 → 组合卡 bounds 为 0），
     * 而背景层没有尺寸就生成不出模糊图、也算不出亮度区间。
     *
     * 尺寸取设计稿上的组合卡大小（见 `CoverBackdrop` 的类注释：约 572×867）。
     */
    private fun NavPage.giveCardSize() {
        coverBackdropLayer.setBounds(0, 0, CARD_W, CARD_H)
    }

    // ------------------------------------------------------------------ ① 日间卡面是亮的

    @Test
    fun 日间模式下组合卡的卡面是亮的() {
        val nav = navOf(night = false)

        val surface = nav.cardSurfaceColor
        val l = CoverBackdrop.relativeLuminance(surface and 0xFFFFFF)
        assertTrue(
            "日间模式下组合卡的卡面亮度是 $l —— 这么暗的话跟日间的其它卡不统一，" +
                "「风格统一」这个诉求就没了",
            l > 0.4,
        )
    }

    @Test
    fun 日间模式下组合卡的卡面跟日间其它卡一样白() {
        val nav = navOf(night = false)
        assertEquals(
            "前置条件变了：日间默认卡片色应该还是纯白，这条用例的前提不成立",
            0xFFFFFFFF.toInt(),
            Skin.surface,
        )
        assertEquals(
            "日间组合卡应该跟日间其它卡一样是白卡",
            0xFFFFFFFF.toInt(),
            nav.cardSurfaceColor,
        )
    }

    // ------------------------------------------------------------------ ② 日间文字是深的

    @Test
    fun 日间模式下组合卡的文字是深的() {
        val nav = navOf(night = false)

        val color = nav.cardTextColor
        assertTrue("歌名颜色是 $color，应该是深色", color != 0)
        val l = CoverBackdrop.relativeLuminance(color and 0xFFFFFF)
        assertTrue(
            "歌名亮度是 $l —— 白底上必须用深色字才读得清",
            l < 0.05,
        )
    }

    @Test
    fun 日间模式下组合卡的次要文字也是深的() {
        val nav = navOf(night = false)

        assertEquals(
            "歌手那一档也应该是日间的深色",
            Palettes.day.textDim,
            nav.cardArtistColor,
        )
        val l = CoverBackdrop.relativeLuminance(nav.cardArtistColor and 0xFFFFFF)
        assertTrue("次要文字亮度 $l 太高了", l < 0.2)
    }

    // ------------------------------------------------------------------ ③ 区间是下限且够淡

    @Test
    fun 日间模式下背景亮度区间是下限() {
        val nav = navOf(night = false)
        nav.giveCardSize()
        nav.artwork = 双色封面()

        val range = nav.coverBackdropLayer.currentToneRange
        assertEquals(
            "日间是白底深字 → 区间应该是 [下限, 1.0]，上限恒为 1",
            1.0,
            range[1],
            1e-9,
        )
        assertTrue(
            "下限是 ${range[0]} —— 旧路线（三档文字）会顶到 0.9743，" +
                "那样整张封面会被冲成纯白（用户反馈的那个 bug）；" +
                "砍到两档文字之后应该只有 0.43 左右",
            range[0] < 0.6,
        )
    }

    @Test
    fun 日间模式下亮度区间由两档文字色反推() {
        val nav = navOf(night = false)
        nav.giveCardSize()
        nav.artwork = 双色封面()

        // 下限必须由**真正压在封面上的最弱那档文字**（日间 textDim）反推。
        assertEquals(
            "亮度区间不是由实际渲染的最弱文字色反推的 —— " +
                "日间要是用了 textFaint，下限就会回到 0.9743，封面又没了",
            CoverBackdrop.minBackgroundLuminance(Palettes.day.textDim),
            nav.coverBackdropLayer.currentToneRange[0],
            1e-9,
        )
    }

    // ------------------------------------------------------------------ ④ 罩完还认得出来

    @Test
    fun 日间模式下罩完之后封面还认得出来() {
        // 纯逻辑层：日间的区间是 [0.4340, 1.0]，映射完之后
        // 封面**暗的那部分**必须还在 0.75 以下 —— 旧路线会把它顶到 0.97 以上（等于纯白）。
        val range = CoverBackdrop.targetRange(Palettes.day.surface, Palettes.day.textDim)
        val src = intArrayOf(0xFF1A3A6B.toInt(), 0xFFD2691E.toInt()) // 深蓝 + 橙
        val mapped = CoverBackdrop.toneMap(src, range[0], range[1])

        val l0 = CoverBackdrop.relativeLuminance(mapped[0] and 0xFFFFFF)
        val l1 = CoverBackdrop.relativeLuminance(mapped[1] and 0xFFFFFF)
        assertTrue(
            "罩完之后最暗那部分亮度是 $l0 —— 说明封面被冲成一片白了（等于没画）",
            l0 < 0.75,
        )
        assertTrue(
            "罩完之后封面暗部 $l0 / 亮部 $l1 几乎没有差别 —— 糊成一片了，认不出是张封面",
            l1 - l0 > 0.15,
        )
    }

    @Test
    fun 日间模式下强调色在白罩底上读得清() {
        val nav = navOf(night = false)
        // 日间组合卡的强调色（当前歌词 / 进度条）压在最暗处 L=0.4340 的白罩底上，
        // 必须还有 3:1 —— 日间主题那个强调色 `#0F9C8E` 只有 1.57:1，读不出来。
        val bg = CoverBackdrop.minBackgroundLuminance(Palettes.day.textDim)
        // 取**实际在用**的那个色（歌词控件的 activeColor），不是常量 ——
        // 常量对了但接线接错的话，查常量是查不出来的。
        val accent = nav.cardAccentColor
        assertEquals(
            "日间组合卡的强调色应该是加深的青（不是主题那个浅青）",
            NavPage.CARD_ACCENT_DAY,
            accent,
        )
        val lText = 亮度(accent)
        val contrast = (bg + 0.05) / (lText + 0.05)
        assertTrue(
            "强调色 $accent 在白罩底上的对比度只有 $contrast —— 当前歌词读不出来",
            contrast >= 3.0,
        )
    }

    // ------------------------------------------------------------------ ⑤ 夜间零变化

    @Test
    fun 夜间模式下组合卡的卡面没变() {
        val nav = navOf(night = true)
        assertEquals(
            "夜间本来就是深底浅字，这次改动**不许**动到它",
            Palettes.night.surface,
            nav.cardSurfaceColor,
        )
    }

    @Test
    fun 夜间模式下组合卡的文字没变() {
        val nav = navOf(night = true)
        assertEquals(
            "夜间文字色应该还是夜间那档",
            Palettes.night.text,
            nav.cardTextColor,
        )
        assertEquals(
            "夜间次要文字色应该还是夜间那档",
            Palettes.night.textDim,
            nav.cardArtistColor,
        )
    }

    @Test
    fun 夜间模式下背景亮度区间没变() {
        val night = navOf(night = true)
        val day = navOf(night = false)
        // 区间是重建背景层时算出来的 → 得先给尺寸和封面，否则读到的是初值。
        night.giveCardSize()
        night.artwork = 双色封面()
        day.giveCardSize()
        day.artwork = 双色封面()
        assertEquals(
            "夜间是「压暗」区间 [0, 0.0341]，不能被日间那套改掉",
            0.0,
            night.coverBackdropLayer.currentToneRange[0],
            1e-9,
        )
        assertEquals(
            "夜间区间上限（最弱那档 textFaint 反推）不能变",
            CoverBackdrop.maxBackgroundLuminance(Palettes.night.textFaint),
            night.coverBackdropLayer.currentToneRange[1],
            1e-9,
        )
        assertTrue(
            "日夜两套区间本来就不同（一个是上限、一个是下限），别拿它们直接比",
            day.coverBackdropLayer.currentToneRange[0] > 0.4,
        )
    }

    // ------------------------------------------------------------------ 开关真的有效果

    @Test
    fun 日间模式下开关封面背景_真的有封面纹理() {
        val nav = navOf(night = false)
        nav.giveCardSize()
        nav.artwork = 双色封面()

        // 开：背景层要真的画上封面（模糊图存在）
        nav.coverBackdropEnabled = true
        assertNotNull(
            "开着的时候背景层应该有模糊封面 —— 没有的话用户点开看不到任何变化",
            nav.coverBackdropLayer.blurredSize,
        )

        // 关：模糊图要丢掉（退回纯色卡面）
        nav.coverBackdropEnabled = false
        assertNull(
            "关掉之后不该再留着模糊图",
            nav.coverBackdropLayer.blurredSize,
        )
    }

    /**
     * **用户反馈的原话**直接翻译成断言：
     * 「点了有提示，但实际无变化」⇒ 开 / 关两张画面必须**不一样**。
     *
     * 日间是白罩，差异本来就比夜间小得多（夜间平均差 ~18/255，日间只有几个），
     * 所以这里不能拿夜间的阈值来卡 —— 卡的是「**必须真的变了**」这条底线。
     */
    @Test
    fun 日间模式下开关封面背景_画面确实变了() {
        val nav = navOf(night = false)
        nav.giveCardSize()
        nav.artwork = 双色封面()

        nav.coverBackdropEnabled = true
        val on = 渲染(nav.coverBackdropLayer)
        nav.coverBackdropEnabled = false
        val off = 渲染(nav.coverBackdropLayer)

        val diff = 平均差异(on, off)
        assertTrue(
            "开 / 关两张画面的平均差异只有 $diff —— 用户点封面图根本看不出变化，" +
                "这正是反馈的「有提示但实际无变化」",
            diff > 1.5,
        )
    }

    // ------------------------------------------------------------------ 用户设置

    @Test
    fun 用户自选的亮色卡片色会被组合卡沿用() {
        val launcher = setupLauncher(night = false)
        // 用户在设置里挑了一个够亮的卡片色。
        Skin.surface = 0xFFF0F3F8.toInt()
        val nav = findByType(launcher, NavPage::class.java) ?: error("桌面上找不到导航页")
        nav.applyCardStyle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "够亮的自选卡片色应该被原样沿用",
            0xFFF0F3F8.toInt(),
            nav.cardSurfaceColor,
        )
    }

    @Test
    fun 用户自选的暗色卡片色在日间会被换成白底() {
        val launcher = setupLauncher(night = false)
        // 用户挑了一个**太暗**的卡片色 —— 深色字压在上面读不出来，必须换掉。
        Skin.surface = 0xFF102030.toInt()
        val nav = findByType(launcher, NavPage::class.java) ?: error("桌面上找不到导航页")
        nav.applyCardStyle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "太暗的自选卡片色在日间必须换成白底（深色字压不住）",
            0xFFFFFFFF.toInt(),
            nav.cardSurfaceColor,
        )
    }

    @Test
    fun 卡片透明度设置仍然生效() {
        val launcher = setupLauncher(night = false)
        // 模拟用户把卡片透明度调到 60%。
        Skin.surface = (0x99 shl 24) or (Palettes.day.surface and 0xFFFFFF)
        val nav = findByType(launcher, NavPage::class.java) ?: error("桌面上找不到导航页")
        nav.applyCardStyle()
        shadowOf(Looper.getMainLooper()).idle()

        val alpha = nav.cardSurfaceColor ushr 24
        assertEquals(
            "卡片不透明度（alpha 段）必须跟着用户的设置走",
            0x99,
            alpha,
        )
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 还原主题单例。
     *
     * [Skin] 是**单例对象**（静态状态），而 Robolectric 在同一个 classloader 里
     * 跑同一个配置的所有用例 —— 静态状态会**跨用例泄漏**：
     * 「卡片透明度设置仍然生效」那条把 `Skin.surface` 改成了半透明，
     * 后面那条再断言「日间默认卡片色是纯白」就会读到脏值而误红。
     *
     * 所以每条用例跑完都把主题刷回夜间基准。
     */
    @After
    fun 还原主题单例() {
        Skin.apply(Palettes.night)
    }

    /** 把背景层画到一张位图上，取回全部像素。 */
    private fun 渲染(d: android.graphics.drawable.Drawable): IntArray {
        val bmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.bounds = Rect(0, 0, CARD_W, CARD_H)
        d.draw(canvas)
        val px = IntArray(CARD_W * CARD_H)
        bmp.getPixels(px, 0, CARD_W, 0, 0, CARD_W, CARD_H)
        bmp.recycle()
        return px
    }

    /** 两张图逐通道的平均绝对差（0 = 完全一样）。 */
    private fun 平均差异(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            sum += kotlin.math.abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)).toLong()
            sum += kotlin.math.abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)).toLong()
            sum += kotlin.math.abs((x and 0xFF) - (y and 0xFF)).toLong()
        }
        return sum.toDouble() / (a.size * 3)
    }

    /**
     * 一张**双色**封面（深蓝 + 橙）。
     *
     * 用双色而不是单色：单色封面映射完还是一块纯色，
     * 分不清「封面被压成了一片均匀的白」和「封面还在」。
     * 双色映射后必须**保留两种色相**才算数。
     */
    private fun 双色封面(): Bitmap =
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawColor(Color.parseColor("#1A3A6B"))
            c.drawRect(0f, 0f, 100f, 200f, android.graphics.Paint().apply {
                color = Color.parseColor("#D2691E")
            })
        }

    /** 颜色的相对亮度（纯逻辑层不碰 android.graphics.Color）。 */
    private fun 亮度(color: Int): Double = CoverBackdrop.relativeLuminance(color and 0xFFFFFF)

    private companion object {
        /** 组合卡的设计尺寸（见 `CoverBackdrop` 类注释）。 */
        const val CARD_W = 572
        const val CARD_H = 867
    }
}
