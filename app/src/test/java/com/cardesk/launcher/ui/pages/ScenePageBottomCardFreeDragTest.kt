package com.cardesk.launcher.ui.pages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.CardLayout
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class ScenePageBottomCardFreeDragTest {

    @Test
    fun `bottom card can be dragged freely anywhere on homepage and persisted`() {
        val ctx = RuntimeEnvironment.getApplication()
        var savedFree: Map<String, Pair<Float, Float>> = emptyMap()
        val page = ScenePage(ctx).apply {
            bind(
                VehicleInfo(
                    rangeKm = 386,
                    fuelPercent = 62,
                    doorsClosed = listOf(true, true, true, true),
                    doorsAllClosed = true,
                    hoodClosed = true,
                    trunkClosed = true,
                    windowsOpen = booleanArrayOf(false, false, false, false),
                    windowsAllClosed = true,
                ),
                HvacState(),
                0,
            )
            onCardFreePosChanged = { savedFree = it }
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
            draw(Canvas(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)))
        }

        val cardRectsField = ScenePage::class.java.getDeclaredField("cardRects").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cardRects = cardRectsField.get(page) as Map<String, android.graphics.RectF>
        val rangeRect = cardRects[CardLayout.RANGE]!!

        // 长按续航卡并拖到上半屏 (600, 300)
        val now = SystemClock.uptimeMillis()
        page.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, rangeRect.centerX(), rangeRect.centerY(), 0))
        shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        page.dispatchTouchEvent(MotionEvent.obtain(now, now + 400, MotionEvent.ACTION_MOVE, 600f, 300f, 0))
        page.dispatchTouchEvent(MotionEvent.obtain(now, now + 450, MotionEvent.ACTION_UP, 600f, 300f, 0))

        assertTrue("续航卡应记录自由摆放坐标", savedFree.containsKey(CardLayout.RANGE))
        assertTrue("纵坐标应停在上方区域", savedFree[CardLayout.RANGE]!!.second < 400f)

        // 重绘后 cardRects[RANGE] 应位于新坐标
        page.draw(Canvas(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)))
        val movedRect = cardRects[CardLayout.RANGE]!!
        assertEquals(savedFree[CardLayout.RANGE]!!.first, movedRect.left, 2f)
        assertEquals(savedFree[CardLayout.RANGE]!!.second, movedRect.top, 2f)

        // 拖回底部行后应自动归位到底部行并移除自由坐标
        val t2 = SystemClock.uptimeMillis()
        page.dispatchTouchEvent(MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, movedRect.centerX(), movedRect.centerY(), 0))
        shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        page.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 400, MotionEvent.ACTION_MOVE, 300f, 840f, 0))
        page.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 450, MotionEvent.ACTION_UP, 300f, 840f, 0))
        assertFalse("拖回底部行后应清除自由摆放坐标", savedFree.containsKey(CardLayout.RANGE))
    }
}
