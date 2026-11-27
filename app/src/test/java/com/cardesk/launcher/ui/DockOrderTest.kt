package com.cardesk.launcher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.DockOrder
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.shell.DockBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Dock 栏自定义拖动调整按钮与控件顺序单元测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DockOrderTest {

    @Test
    fun 默认顺序排布在1920宽下对称贴齐且无重叠() {
        val slots = DockOrder.layout(DockOrder.DEFAULT, 1920f)
        assertEquals(17, slots.size)
        assertEquals(DockOrder.ITEM_BACK, slots.first().id)
        assertEquals(28f, slots.first().left, 0.5f)
        assertEquals(1920f - 28f, slots.last().right, 0.5f)
        for (i in 0 until slots.lastIndex) {
            assertTrue(
                "相邻控件不应重叠：${slots[i]} vs ${slots[i + 1]}",
                slots[i].right <= slots[i + 1].left + 0.1f,
            )
        }
    }

    @Test
    fun 归一化自动去重过滤非法ID并补齐缺失项() {
        val norm = DockOrder.normalize(listOf("media", "home", "media", "unknown_id"))
        assertEquals(17, norm.size)
        assertEquals(DockOrder.ITEM_MEDIA, norm[0])
        assertEquals(DockOrder.ITEM_HOME, norm[1])
        assertEquals(DockOrder.DEFAULT.toSet(), norm.toSet())

        // 老版本保存的 16 项（不含 back）解析时自动把返回键补在最左侧
        val legacyCsv = DockOrder.DEFAULT.filter { it != DockOrder.ITEM_BACK }.joinToString(",")
        val migrated = DockOrder.parse(legacyCsv)
        assertEquals(17, migrated.size)
        assertEquals(DockOrder.ITEM_BACK, migrated.first())
    }

    @Test
    fun 拖动媒体胶囊到最右侧后顺序更新并落盘且无振荡() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).dockOrder = DockOrder.DEFAULT

        val dock = DockBarView(ctx)
        dock.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(DockBarView.DOCK_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        dock.layout(0, 0, 1920, DockBarView.DOCK_HEIGHT)
        dock.draw(Canvas(Bitmap.createBitmap(1920, DockBarView.DOCK_HEIGHT, Bitmap.Config.ARGB_8888)))

        var mediaOpened = false
        dock.onMediaOpenApp = { mediaOpened = true }

        DockBarView.setReorderMode(ctx, true)
        try {
            val mediaSlot = DockOrder.layout(DockOrder.DEFAULT, 1920f).first { it.id == DockOrder.ITEM_MEDIA }
            val startX = mediaSlot.centerX
            val cy = DockBarView.DOCK_HEIGHT / 2f
            val now = System.currentTimeMillis()

            dock.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, cy, 0))
            dock.onTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, 1750f, cy, 0))
            dock.onTouchEvent(MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, 1750f, cy, 0))
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("排序模式下拖动媒体胶囊不应误触发打开音乐 App", mediaOpened)
            assertEquals("媒体胶囊应移动到最右侧末尾", DockOrder.ITEM_MEDIA, dock.currentOrder().last())
            assertEquals("新顺序必须持久化到 Prefs", dock.currentOrder(), Prefs(ctx).dockOrder)

            // 恢复默认顺序
            DockBarView.resetOrder(ctx)
            assertEquals(DockOrder.DEFAULT, Prefs(ctx).dockOrder)
        } finally {
            DockBarView.setReorderMode(ctx, false)
        }
    }

    @Test
    fun 拖动调换后点击新位置按钮仍然精确命中原功能() {
        val ctx = RuntimeEnvironment.getApplication()
        // 把「首页」移到最右边，「返回」仍在最左边
        val homeIdx = DockOrder.DEFAULT.indexOf(DockOrder.ITEM_HOME)
        val custom = DockOrder.move(DockOrder.DEFAULT, homeIdx, DockOrder.DEFAULT.lastIndex)
        Prefs(ctx).dockOrder = custom

        val dock = DockBarView(ctx)
        dock.applyOrder(custom)
        dock.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(DockBarView.DOCK_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        dock.layout(0, 0, 1920, DockBarView.DOCK_HEIGHT)
        dock.draw(Canvas(Bitmap.createBitmap(1920, DockBarView.DOCK_HEIGHT, Bitmap.Config.ARGB_8888)))

        var homeClicked = false
        var backClicked = false
        dock.onHome = { homeClicked = true }
        dock.onBack = { backClicked = true }

        val layout = DockOrder.layout(custom, 1920f)
        val homeSlot = layout.first { it.id == DockOrder.ITEM_HOME }
        val backSlot = layout.first { it.id == DockOrder.ITEM_BACK }
        val now = System.currentTimeMillis()
        dock.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, homeSlot.centerX, 56f, 0))
        dock.onTouchEvent(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, homeSlot.centerX, 56f, 0))
        dock.onTouchEvent(MotionEvent.obtain(now + 100, now + 100, MotionEvent.ACTION_DOWN, backSlot.centerX, 56f, 0))
        dock.onTouchEvent(MotionEvent.obtain(now + 100, now + 130, MotionEvent.ACTION_UP, backSlot.centerX, 56f, 0))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("首页键挪到最右后点击新坐标应触发 onHome", homeClicked)
        assertTrue("最左侧返回键点击应触发 onBack", backClicked)
    }
}
