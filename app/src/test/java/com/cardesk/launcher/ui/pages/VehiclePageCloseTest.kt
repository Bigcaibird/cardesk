package com.cardesk.launcher.ui.pages

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.cardesk.launcher.core.Palettes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class VehiclePageCloseTest {

    private fun newPage(): Pair<VehiclePage, () -> Int> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var closes = 0
        val page = VehiclePage(activity, Palettes.night).apply {
            onClose = { closes++ }
        }
        activity.setContentView(page)
        page.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        page.layout(0, 0, 1920, 1080)
        shadowOf(Looper.getMainLooper()).idle()
        return page to { closes }
    }

    private fun dispatchSwipe(target: ViewGroup, x0: Float, y0: Float, x1: Float, y1: Float) {
        val now = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x0, y0, 0))
        target.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, (x0 + x1) / 2f, (y0 + y1) / 2f, 0))
        target.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, x1, y1, 0))
        target.dispatchTouchEvent(MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x1, y1, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun tapOutsideClosesButTapInsidePanelDoesNot() {
        val (page, closes) = newPage()
        // 点面板内空白处不关闭
        dispatchSwipe(page, 960f, 500f, 960f, 500f)
        assertEquals(0, closes())

        // 点面板外遮罩区域（y=20 < 67）关闭
        dispatchSwipe(page, 960f, 20f, 960f, 20f)
        assertEquals(1, closes())
    }

    @Test
    fun swipeDownLeftRightClosesDetailPage() {
        val (page, closes) = newPage()
        dispatchSwipe(page, 960f, 300f, 960f, 460f)
        assertEquals(1, closes())

        dispatchSwipe(page, 960f, 400f, 760f, 400f)
        assertEquals(2, closes())

        dispatchSwipe(page, 760f, 400f, 960f, 400f)
        assertEquals(3, closes())
    }
}
