package com.cardesk.launcher

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.cardesk.launcher.core.media.MediaActions
import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.ui.pages.NavPage
import com.cardesk.launcher.ui.pages.ScenePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第207轮：音乐卡**收藏 / 播放模式**两个按钮的版式与首页收藏按钮。
 *
 * ## 锁住什么
 *
 * ① **导航页两个按钮一样大** ——
 *    用户反馈播放顺序按钮太小、和收藏按钮不一样大。
 *    这里直接量两个 `ImageView` 的**内容区**（bounds 减去 padding），
 *    断言相等 —— 光看常量不够，要看**真实控件**。
 *
 * ② **首页音乐卡也有收藏按钮** ——
 *    首页音乐卡是 Canvas 自绘的，收藏按钮是**自绘心形**，
 *    所以断言的是「命中区存在」+「点击真的会触发回调」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class MediaActionButtonsLayoutTest {

    private fun launcher(): LauncherView {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return findLauncher(root)!!
    }

    private fun findLauncher(view: View): LauncherView? {
        if (view is LauncherView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findLauncher(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** 递归找出所有 [ImageView]。 */
    private fun collectImageViews(root: View): List<ImageView> {
        val out = ArrayList<ImageView>()
        fun walk(v: View) {
            if (v is ImageView) out.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    /** 一个 `ImageView` 的**内容区**尺寸（bounds 去掉 padding）。 */
    private fun contentSize(v: ImageView): Pair<Int, Int> =
        (v.width - v.paddingLeft - v.paddingRight) to (v.height - v.paddingTop - v.paddingBottom)

    @Test
    fun `导航页收藏与播放模式按钮一样大`() {
        val l = launcher()
        val navPage = findNavPage(l)
        assertNotNull("应该能找到 NavPage", navPage)

        val fav = navPage!!.favoriteBtnForTest()
        val mode = navPage.playModeBtnForTest()
        assertNotNull("应该有收藏按钮", fav)
        assertNotNull("应该有播放模式按钮", mode)

        // 内容区（真正的图标大小）必须一致
        assertEquals(
            "收藏与播放模式的图标内容区必须一样大",
            contentSize(fav!!),
            contentSize(mode!!),
        )
        // 外框也一样大（点击热区一致）
        assertEquals(fav.width, mode.width)
        assertEquals(fav.height, mode.height)
        // 常量口径：内容区 40、外框 48×56
        assertEquals(40, NavPage.ACTION_ICON_CONTENT)
        assertEquals(48, NavPage.ACTION_ICON_FRAME_W)
        assertEquals(56, NavPage.ACTION_ICON_FRAME_H)
    }

    @Test
    fun `首页音乐卡也有收藏按钮且可点`() {
        val l = launcher()
        val scenePage = findScenePage(l)
        assertNotNull("应该能找到 ScenePage", scenePage)

        // 收藏按钮是自绘的 → 断言命中区存在
        scenePage!!.favoriteState = MediaActions.FavoriteState.FAVORITED
        // 用**能力表里真实存在的包名**（网易云车机版），否则查不到能力 → 按钮不画
        scenePage.musicPackage = com.cardesk.launcher.core.media.MediaButtonTransport.NETEASE_PACKAGE
        drawNow(scenePage)

        assertTrue(
            "首页音乐卡应该登记了收藏命中区",
            scenePage.hasFavoriteHitForTest(),
        )

        // 点击命中区 → 回调被触发
        var clicked = 0
        scenePage.onFavoriteToggle = { clicked++ }
        val r = scenePage.favoriteHitForTest()
        assertNotNull("应该有收藏命中区", r)
        scenePage.dispatchTapForTest(r!!.centerX(), r.centerY())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("点收藏命中区应该触发回调", 1, clicked)
    }

    @Test
    fun `首页音乐卡收藏按钮在播放键左侧且放大一倍`() {
        val l = launcher()
        val scenePage = findScenePage(l)!!
        scenePage.favoriteState = MediaActions.FavoriteState.FAVORITED
        scenePage.musicPackage = com.cardesk.launcher.core.media.MediaButtonTransport.NETEASE_PACKAGE
        drawNow(scenePage)

        val fav = scenePage.favoriteHitForTest()
        val play = scenePage.playHitForTest()
        assertNotNull("应该有收藏命中区", fav)
        assertNotNull("应该有播放键命中区", play)

        // ① 收藏心形**整个落在播放键左边**
        assertTrue(
            "收藏心形必须整个在播放键左侧（fav.right=${fav!!.right} play.left=${play!!.left}）",
            fav.right <= play.left + 0.5f,
        )
        // ② 与播放键**同一条水平中线**
        assertEquals(
            "收藏心形应与播放键垂直居中对齐",
            play.centerY(),
            fav.centerY(),
            1f,
        )
        // ③ 边长 = 52 设计单位（第207轮是 26 → 放大一倍）
        assertEquals(52f, ScenePage.MUSIC_FAV_ICON_SIZE)
        // 播放键命中区直径 = 28 × 2 = 56 设计单位 → 比例 52/56
        assertEquals(
            "收藏心形边长应是播放键命中区直径的 52/56",
            52f / 56f,
            fav.width() / play.width(),
            0.01f,
        )
        // ④ 是个正方形
        assertEquals(fav.width(), fav.height(), 0.5f)
    }

    @Test
    fun `首页音乐卡收藏按钮摆在歌名和播放键中间`() {
        val l = launcher()
        val scenePage = findScenePage(l)!!
        scenePage.favoriteState = MediaActions.FavoriteState.FAVORITED
        scenePage.musicPackage = com.cardesk.launcher.core.media.MediaButtonTransport.NETEASE_PACKAGE
        // 固定歌名/歌手 —— 心形的位置要跟着**文字实际占位**走，测的时候得可控。
        scenePage.mediaTitle = "九万字"
        scenePage.mediaArtist = "黄诗扶"
        drawNow(scenePage)

        val fav = scenePage.favoriteHitForTest()!!
        val play = scenePage.playHitForTest()!!
        val textRight = scenePage.musicTextRightForTest()

        // ① 心形在「歌名右缘」和「播放键左缘」**之间**
        assertTrue(
            "心形应该在歌名右侧（textRight=$textRight fav.left=${fav.left}）",
            fav.left >= textRight - 0.5f,
        )
        assertTrue(
            "心形应该在播放键左侧（fav.right=${fav.right} play.left=${play.left}）",
            fav.right <= play.left + 0.5f,
        )
        // ② 不是贴着播放键 —— 中间要留出**实打实**的空档
        assertTrue(
            "心形不能贴着播放键（间距=${play.left - fav.right}）",
            play.left - fav.right > fav.width() * 0.5f,
        )
        // ③ 大致在两者**中间**（允许 ±15% 的余量）
        val mid = (textRight + play.left) / 2f
        val span = (play.left - textRight).coerceAtLeast(1f)
        assertTrue(
            "心形应大致居中在歌名与播放键之间（center=${fav.centerX()} mid=$mid span=$span）",
            kotlin.math.abs(fav.centerX() - mid) <= span * 0.15f,
        )
    }

    @Test
    fun `歌名极长时收藏按钮不压到歌名上`() {
        val l = launcher()
        val scenePage = findScenePage(l)!!
        scenePage.favoriteState = MediaActions.FavoriteState.FAVORITED
        scenePage.musicPackage = com.cardesk.launcher.core.media.MediaButtonTransport.NETEASE_PACKAGE
        // Robolectric 的 `measureText` **每个字只算 1px**（真机是真实字宽），
        // 所以这里要用**超长**标题才能把文本列撑到撑满、逼出截断分支。
        scenePage.mediaTitle = "测试".repeat(200)
        scenePage.mediaArtist = "artist".repeat(60)
        drawNow(scenePage)

        val fav = scenePage.favoriteHitForTest()!!
        val play = scenePage.playHitForTest()!!
        val textRight = scenePage.musicTextRightForTest()

        // 心形仍然整个在播放键左侧
        assertTrue(
            "心形必须整个在播放键左侧（fav.right=${fav.right} play.left=${play.left}）",
            fav.right <= play.left + 0.5f,
        )
        // 文字右缘不能压到心形上（不够宽时截断，而不是叠在一起）
        assertTrue(
            "歌名不能压到心形上（textRight=$textRight fav.left=${fav.left}）",
            textRight <= fav.left + 0.5f,
        )
    }

    @Test
    fun `播放器不支持收藏时不画收藏按钮`() {
        val l = launcher()
        val scenePage = findScenePage(l)!!
        // 未知包 + 没有探测信息 → 不支持 → 不该有命中区
        scenePage.musicPackage = "com.unknown.player"
        scenePage.actionProbe = null
        drawNow(scenePage)
        assertTrue(
            "不支持收藏的播放器不该画收藏按钮",
            !scenePage.hasFavoriteHitForTest(),
        )
    }

    /**
     * 真正画一帧。
     *
     * 收藏命中区是在 [com.cardesk.launcher.ui.pages.ScenePage.drawMusicCard] 里
     * **画的时候**登记的（`hits` 每帧开头清空）——
     * 只调 `invalidate()` 不会让 Robolectric 真的走一遍 `onDraw`，
     * 所以这里手动 draw 一次。
     */
    private fun drawNow(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1920, 1080)
        val bmp = android.graphics.Bitmap.createBitmap(
            1920, 1080, android.graphics.Bitmap.Config.ARGB_8888,
        )
        view.draw(android.graphics.Canvas(bmp))
        bmp.recycle()
    }

    private fun findNavPage(root: View): NavPage? {
        if (root is NavPage) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findNavPage(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findScenePage(root: View): ScenePage? {
        if (root is ScenePage) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findScenePage(root.getChildAt(i))?.let { return it }
        }
        return null
    }
}
