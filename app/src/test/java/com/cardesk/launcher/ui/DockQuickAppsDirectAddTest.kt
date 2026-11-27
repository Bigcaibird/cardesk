package com.cardesk.launcher.ui

import android.os.Looper
import android.view.View
import com.cardesk.launcher.MainActivity
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.ui.dialogs.AppPickerDialog
import com.cardesk.launcher.ui.dialogs.DockQuickAppsDialog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class DockQuickAppsDirectAddTest {

    private fun findAll(root: View): List<View> {
        val out = ArrayList<View>()
        out.add(root)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) out.addAll(findAll(root.getChildAt(i)))
        }
        return out
    }

    @Test
    fun `global dock quick app pick and config actions open picker and config dialogs directly`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val prefs = Prefs(activity)
        prefs.dockQuickApps = listOf("", "")
        val launcher = LauncherView(activity)

        launcher.handleGlobalDockAction("${MainActivity.ACTION_QUICK_APP_PICK}:0")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "空槽位点击应直接弹出 AppPickerDialog",
            findAll(launcher).any { it is AppPickerDialog },
        )

        launcher.handleGlobalDockAction("${MainActivity.ACTION_QUICK_APP_CONFIG}:1")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "长按槽位应弹出 DockQuickAppsDialog",
            findAll(launcher).any { it is DockQuickAppsDialog },
        )
    }
}
