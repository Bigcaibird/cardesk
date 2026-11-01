package com.cardesk.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.RecentTasks
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 最近任务页（第九十五轮 C3）的 UI 回归 + 效果图。
 *
 * 1. 有数据时列出应用（名称/包名）；
 * 2. 点「锁定」会写入 `recent_locked` 并立刻重画；
 * 3. 渲染 `preview/y04-recent-tasks.png` 供目视检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecentTasksPageTest {

    private val dir = File("../preview").apply { mkdirs() }
    private lateinit var context: Context

    private class FakeSystem(private val tasks: List<RecentTasks.Raw>) : RecentTasks.System {
        override fun list(max: Int) = tasks
        override fun moveToFront(taskId: Int) = true
        override fun remove(taskId: Int) = true
    }

    private fun <T> findAll(root: View, type: Class<T>): List<T> {
        val out = ArrayList<T>()
        if (type.isInstance(root)) @Suppress("UNCHECKED_CAST") out.add(root as T)
        if (root is ViewGroup) for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i), type))
        return out
    }

    private fun page(): RecentTasksPage {
        context = RuntimeEnvironment.getApplication()
        Prefs(context).recentLocked = emptySet()
        val fake = FakeSystem(
            listOf(
                RecentTasks.Raw(11, "com.ecarx.music", "com.ecarx.music.Main"),
                RecentTasks.Raw(12, "com.autonavi.amapautp.yh", "com.autonavi.amapautp.Main"),
                RecentTasks.Raw(13, "com.tencent.wecar", "com.tencent.wecar.Main"),
            ),
        )
        val view = RecentTasksPage(context, Palettes.night, RecentTasks(context, fake))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    private fun shot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun 最近任务页列出应用并可锁定() {
        val view = page()
        assertNotNull("应显示应用名", findAll(view, TextView::class.java).firstOrNull { it.text.toString() == "com.ecarx.music" })
        assertTrue("应显示标题", findAll(view, TextView::class.java).any { it.text == "最近任务" })
        shot(view, "y04-recent-tasks.png")

        findAll(view, TextView::class.java).first { it.text == "锁定" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("锁定应持久化", Prefs(context).recentLocked.contains("com.ecarx.music"))
        assertNotNull("锁定后按钮应显示已锁定", findAll(view, TextView::class.java).firstOrNull { it.text == "已锁定" })
    }
}
