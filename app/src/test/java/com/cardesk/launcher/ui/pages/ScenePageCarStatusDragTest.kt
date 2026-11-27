package com.cardesk.launcher.ui.pages

import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleInfo
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
class ScenePageCarStatusDragTest {

    @Test
    fun `dragging vehicle card into bottom row succeeds even with slight upward jitter`() {
        val ctx = RuntimeEnvironment.getApplication()
        var inBottom = false
        val page = ScenePage(ctx).apply {
            showCarStatusCard = true
            carStatusCardCompact = true
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
            onCarStatusRowChanged = { bottom, _ -> inBottom = bottom }
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
            draw(Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)))
        }

        val hitsField = ScenePage::class.java.getDeclaredField("hits").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val hits = hitsField.get(page) as Map<String, android.graphics.RectF>
        val card = hits["carStatusCard"]!!
        val startX = card.centerX()
        val startY = card.top + 20f

        val now = SystemClock.uptimeMillis()
        page.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0))
        shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)

        // 拖到底部行附近（cardTop≈790），即使手指还在 cardTop - 55 处，卡片主体已跨入底部行
        page.dispatchTouchEvent(MotionEvent.obtain(now, now + 400, MotionEvent.ACTION_MOVE, 1200f, 740f, 0))
        assertTrue("拖入底部行应立即进入底部排布", page.carStatusInBottomRow)

        // 模拟松手前手指轻微上抖 30px（y=710），不应弹回上方
        page.dispatchTouchEvent(MotionEvent.obtain(now, now + 430, MotionEvent.ACTION_MOVE, 1200f, 710f, 0))
        assertTrue("轻微上抖不应退出底部行", page.carStatusInBottomRow)

        page.dispatchTouchEvent(MotionEvent.obtain(now, now + 460, MotionEvent.ACTION_UP, 1200f, 710f, 0))
        assertTrue("松手后应保持在底部行并回调落盘", page.carStatusInBottomRow && inBottom)

        // 若明确拖回上方很远（y=400 < cardTop - 130），应退出底部行
        page.draw(Canvas(android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)))
        val cardRectsField = ScenePage::class.java.getDeclaredField("cardRects").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cardRects = cardRectsField.get(page) as Map<String, android.graphics.RectF>
        val bottomCard = cardRects[com.cardesk.launcher.core.CardLayout.CAR_STATUS]!!
        val t2 = SystemClock.uptimeMillis()
        page.dispatchTouchEvent(MotionEvent.obtain(t2, t2, MotionEvent.ACTION_DOWN, bottomCard.centerX(), bottomCard.centerY(), 0))
        shadowOf(Looper.getMainLooper()).idleFor(350, TimeUnit.MILLISECONDS)
        page.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 400, MotionEvent.ACTION_MOVE, 1400f, 400f, 0))
        assertFalse("明显拖回上方应退出底部行", page.carStatusInBottomRow)
        page.dispatchTouchEvent(MotionEvent.obtain(t2, t2 + 450, MotionEvent.ACTION_UP, 1400f, 400f, 0))
        assertFalse(inBottom)
    }
}
