package com.cardesk.launcher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.apkstore.ApkEntry
import com.cardesk.launcher.core.update.AppUpdate
import com.cardesk.launcher.core.update.R2Apk
import com.cardesk.launcher.core.update.R2Release
import com.cardesk.launcher.ui.dialogs.CloudDownloadDialog
import com.cardesk.launcher.ui.dialogs.UpdateAvailableDialog
import android.widget.LinearLayout
import com.cardesk.launcher.ui.widget.CloudAppRowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 第九十八轮：在线更新询问框 + 云端应用下载进度框。
 *
 * 下载/安装通过构造参数注入假实现，不联网；这里验证文案、进度/速度显示、
 * 安装结果提示和按钮回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudDialogsTest {

    private val pal: Palette = Palettes.night

    private val release = R2Release(
        version = "1.0.1089",
        versionCode = 4185,
        tag = "v1.0.1089",
        name = "CarDesk 4185 / 1.0.1089-system",
        notes = "修云端应用列表区分度与下载进度显示。",
        publishedAt = "2026-11-02T10:00:00Z",
        apk = R2Apk(
            name = "CarDesk-platform-ecarx-cardesk-4185.apk",
            url = "cardesk/CarDesk-platform-ecarx-cardesk-4185.apk",
            size = 2_150_000,
            sha256 = "abc123",
        ),
    )

    private val entry = ApkEntry(
        name = "酷狗音乐",
        file = "kugou-8.9.1.apk",
        url = "https://file.chuxi.bid/cardesk/apks/kugou-8.9.1.apk",
        size = 12_345_678,
        sha256 = "0123456789abcdef0123",
        addedAt = "2026-11-01T20:00:00Z",
    )

    @Test
    fun updateAvailableDialog_rendersAndCallsBack() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = UpdateAvailableDialog(activity, pal, release)
        var updated = false
        var later = false
        dialog.onUpdate = { updated = true }
        dialog.onLater = { later = true }
        layout(dialog)
        val text = texts(dialog)
        assertTrue(text.any { it.contains("发现新版本") })
        assertTrue(text.any { it.contains("1.0.1089") })
        assertTrue(text.any { it.contains("2.1 MB") })
        render(dialog, "y20-update-available.png")

        find(dialog, "立即更新")!!.performClick()
        assertTrue(updated)
        find(dialog, "稍后")!!.performClick()
        assertTrue(later)
    }

    @Test
    fun cloudDownloadDialog_showsPercentSpeedAndInstallResult() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var installedCallback = false
        val dialog = CloudDownloadDialog(
            activity, pal, entry,
            downloader = { _, onPercent, onDetail ->
                onPercent(30)
                onDetail(1_000_000L, 12_345_678L, 500L)
                onPercent(100)
                onDetail(12_345_678L, 12_345_678L, 1_500L)
                File(activity.cacheDir, "fake.apk").apply { writeText("fake") }
            },
            installer = { AppUpdate.InstallResult.Silent },
            onInstalled = { installedCallback = true },
        )
        layout(dialog)
        // 等下载/安装线程把结果 post 回主线程
        pumpUntilIdle(dialog)
        val text = texts(dialog)
        assertTrue(text.any { it == "100%" })
        assertTrue(text.any { it.contains("安装完成") })
        assertTrue(text.any { it.contains("已提交静默安装") })
        assertTrue(installedCallback)
        assertEquals(false, dialog.isRunning)
        render(dialog, "y20-cloud-download.png")
    }

    @Test
    fun cloudDownloadDialog_failureShowsRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dialog = CloudDownloadDialog(
            activity, pal, entry,
            downloader = { _, _, _ -> null },
            installer = { AppUpdate.InstallResult.Silent },
        )
        layout(dialog)
        pumpUntilIdle(dialog)
        val text = texts(dialog)
        assertTrue(text.any { it.contains("下载失败") })
        assertTrue(text.any { it == "重试" })
    }

    @Test
    fun cloudAppRows_areDistinguishable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val entries = listOf(
            entry,
            entry.copy(name = "高德地图", file = "amapauto-9.1.0.apk", size = 88_000_000, sha256 = "aaaa1111bbbb2222"),
            entry.copy(name = "爱趣听", file = "qqt-1.2.3.apk", size = 3_400_000, sha256 = "cccc3333dddd4444"),
        )
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 120, 72, 0)
            setBackgroundColor(pal.bgTop)
        }
        entries.forEachIndexed { index, e ->
            column.addView(
                CloudAppRowView.build(activity, pal, e, index, installed = index == 2) {},
                LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 18 },
            )
        }
        layout(column)
        val text = texts(column)
        assertTrue(text.any { it.contains("高德地图") })
        assertTrue(text.any { it.contains("83.9 MB") })
        assertTrue(text.any { it.contains("已安装") })
        renderPanel(column, "y20-cloud-list.png")
    }

    // ---------------------------------------------------------------- helpers

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1920, 1080)
    }

    private fun pumpUntilIdle(dialog: CloudDownloadDialog, timeoutMs: Long = 4_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!dialog.isRunning && System.currentTimeMillis() + 100 > end * 0) {
                // 回调线程可能还在 post，再 idle 一次
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(20)
        }
    }

    private fun texts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView && v.text?.isNotBlank() == true) out.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun find(root: View, label: String): View? {
        if (root is TextView && root.text?.toString() == label) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) find(root.getChildAt(i), label)?.let { return it }
        }
        return null
    }

    private fun renderPanel(view: View, name: String) {
        val screen = android.widget.FrameLayout(view.context).apply {
            setBackgroundColor(pal.bgTop)
            addView(view, ViewGroup.LayoutParams(-1, -2))
        }
        layout(screen)
        render(screen, name)
    }

    private fun render(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("../preview").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
