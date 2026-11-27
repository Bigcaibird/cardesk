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

    private class FakeSystem(initialTasks: List<RecentTasks.Raw>) : RecentTasks.System {
        val tasks = initialTasks.toMutableList()
        override fun list(max: Int) = tasks.toList()
        override fun moveToFront(taskId: Int) = true
        override fun remove(taskId: Int) = tasks.removeAll { it.taskId == taskId }
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

    @Test
    fun 关闭单个任务与清空按钮生效() {
        val view = page()
        findAll(view, TextView::class.java).first { it.text == "关闭任务" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "关闭任务后第一条应从列表移除",
            findAll(view, TextView::class.java).none { it.text.toString() == "com.ecarx.music" },
        )
        assertNotNull(
            "其余任务仍应保留",
            findAll(view, TextView::class.java).firstOrNull { it.text.toString() == "com.autonavi.amapautp.yh" },
        )

        findAll(view, TextView::class.java).first { it.text == "清空" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "清空后剩余未锁定任务应全部移除",
            findAll(view, TextView::class.java).none { it.text.toString() == "com.autonavi.amapautp.yh" },
        )
        assertNotNull(
            "清空后应显示空态提示",
            findAll(view, TextView::class.java).firstOrNull { it.text == "暂无最近任务" && it.visibility == View.VISIBLE },
        )
    }

    @Test
    fun 清空按钮位于底部正中且运行内存字体放大并支持点击外区关闭() {
        val view = page()
        var closed = 0
        view.onClose = { closed++ }

        val clearBtn = findAll(view, TextView::class.java).first { it.text == "清空" }
        val panel = clearBtn.parent as ViewGroup
        val panelCenterX = panel.width / 2f
        val clearCenterX = (clearBtn.left + clearBtn.right) / 2f
        assertTrue(
            "清空按钮应水平居中：panelCenterX=$panelCenterX clearCenterX=$clearCenterX",
            kotlin.math.abs(panelCenterX - clearCenterX) <= 4f,
        )
        assertTrue(
            "清空按钮应位于面板下方区域：clearTop=${clearBtn.top} panelH=${panel.height}",
            clearBtn.top > panel.height * 0.75f,
        )

        val memLabel = findAll(view, TextView::class.java).first { it.text == "运行内存" }
        val cardMem = findAll(view, TextView::class.java).first { it.text.toString().startsWith("占用 ") }
        assertTrue("运行内存标签字号应放大（>=20px）", memLabel.textSize >= 20f)
        assertTrue("单卡占用内存字号应放大（>=16px）", cardMem.textSize >= 16f)

        // 面板左右应留出外部可点遮罩区
        assertTrue("面板左边距应留出外部区域", panel.left >= 80)
        assertTrue("面板右边距应留出外部区域", view.width - panel.right >= 80)

        // 点击遮罩层应触发 onClose
        val scrim = view.getChildAt(0)
        scrim.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("点击面板外遮罩应关闭最近任务", closed == 1)
    }

    @Test
    fun 按Home键可关闭最近任务页() {
        val activity = org.robolectric.Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val launcher = findAll(activity.window.decorView, LauncherView::class.java).first()
        val showRecents = LauncherView::class.java.getDeclaredMethod("showRecents").apply { isAccessible = true }
        val recentsField = LauncherView::class.java.getDeclaredField("recentTasksPage").apply { isAccessible = true }

        showRecents.invoke(launcher)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("打开后 recentTasksPage 应非空", recentsField.get(launcher))

        launcher.handleExternalHome()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertTrue("按外部/Dock HOME 键应关闭最近任务页", recentsField.get(launcher) == null)
    }
}
