package com.cardesk.launcher.cluster

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import androidx.test.runner.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 「歌词投到第二块屏上」的**真机式**验证。
 *
 * ## 为什么要 instrumented 而不是单测
 *
 * 这个功能的**全部要害**都在 Android 框架这一侧：
 *
 * - `createDisplayContext(display)` 能不能拿到**那块屏自己的** `WindowManager`；
 * - `addView` 能不能在**不是主屏**的 display 上成功；
 * - `TYPE_APPLICATION_OVERLAY` 在目标屏上会不会被系统拒。
 *
 * 这三件事 Robolectric 全部模拟不出来（它没有真的窗口管理器，也没有真的多屏）。
 * 所以这里**自己造一块副屏**再真投一次。
 *
 * ## 怎么造第二块屏
 *
 * `DisplayManager.createVirtualDisplay` —— 这是**应用自己拥有**的虚拟屏：
 *
 * - 它会出现在 `getDisplays()` 里，id > 0，所以 [DisplayPicker] 能选中它
 *   （跟真车上「副屏也是一个 Display」的情形完全一致）；
 * - 因为是自己创建的，我们对它有完整权限，窗口能挂上去
 *   （真车上副屏是别人的私有屏时会被系统拒 —— 那是**权限**问题，不是代码问题）；
 * - 背后接一个 [ImageReader]，于是这块屏**画出来的东西可以被抓回来**：
 *   抓到有内容 ⇒ 歌词真的画上去了，不是「加了个空窗口」。
 *
 * ## 抓图
 *
 * 抓到的 PNG 写到应用的外置目录，测试结束后用
 * `adb pull /sdcard/Android/data/com.cardesk.launcher/files/…` 取回，肉眼可查。
 */
@RunWith(AndroidJUnit4::class)
class ClusterLyricsCastTest {

    /** 副屏尺寸（真车仪表常见 1920×720）。 */
    private val clusterWidth = 1920

    /** 副屏高度。 */
    private val clusterHeight = 720

    /** 副屏密度（真车仪表常见 240dpi）。 */
    private val clusterDpi = 240

    /** 等待加窗成功的上限（毫秒）。 */
    private val attachTimeoutMs = 12_000L

    @Test
    fun 歌词窗口能挂到第二块屏上并且真的画出东西() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(DisplayManager::class.java)
        assertNotNull("拿不到 DisplayManager", manager)

        // 悬浮窗权限（SYSTEM_ALERT_WINDOW）。
        //
        // `TYPE_APPLICATION_OVERLAY` 需要这个权限，而它是一个 **appop**，
        // 光在清单里声明权限不够 —— 用户还得在系统里授「显示在其他应用上层」。
        // gradle 每次跑 connectedAndroidTest 都会重装 APK、重置 appop，
        // 所以这里在测试内部自己开一次（UiAutomation 以 shell 身份执行）。
        grantOverlayPermission(context.packageName)

        // —— 1. 造一块自己拥有的副屏 ——
        //
        // 注意 `DisplayManager.createVirtualDisplay` 的**公开**签名是
        // `(name, width, height, densityDpi, surface, flags)` —— surface 在 flags **前面**。
        // （AOSP 内部那版是 flags 在前，公开 SDK 这版反过来；照抄网上常见的写法会类型不匹配。）
        // 另外它返回的是 `android.hardware.display.VirtualDisplay`，
        // 那**不是** `android.view.Display` 的子类，真正那块屏要从 `getDisplay()` 拿。
        val reader = ImageReader.newInstance(clusterWidth, clusterHeight, PixelFormat.RGBA_8888, 3)
        val virtual = manager.createVirtualDisplay(
            "CarDesk 测试仪表",
            clusterWidth,
            clusterHeight,
            clusterDpi,
            reader.surface,
            // flags 用 0（**私有**虚拟屏），不要用 VIRTUAL_DISPLAY_FLAG_PUBLIC：
            // PUBLIC 会被系统当成「屏幕共享/投屏」，要求 CAPTURE_VIDEO_OUTPUT 权限
            // —— 普通应用拿不到，于是直接抛 SecurityException。
            // 私有屏一样会出现在自己进程的 getDisplays() 里（id > 0），
            // 也一样能挂自己的窗口，投射链路要的正是这两点。
            0,
        )
        val display = virtual.display
        val clusterName = display.name
        val clusterId = display.displayId
        assertTrue("副屏 id 必须是正数（id<=0 会被当成主屏跳过）", clusterId > 0)

        val prefs = Prefs(context)
        val beforeEnabled = prefs.clusterOverlayEnabled
        val beforeId = prefs.clusterDisplayId
        val beforeName = prefs.clusterDisplayName

        try {
            // —— 2. 指向它 ——
            prefs.clusterDisplayId = clusterId
            prefs.clusterDisplayName = clusterName
            prefs.clusterOverlayEnabled = true

            // —— 3. 拉起投射服务 ——
            ClusterLyricsService.apply(context)

            // —— 4. 等它把窗口挂上去 ——
            val deadline = System.currentTimeMillis() + attachTimeoutMs
            var report = ClusterLyricsService.lastReport
            while (System.currentTimeMillis() < deadline && !report.startsWith("已投射")) {
                Thread.sleep(300)
                report = ClusterLyricsService.lastReport
            }
            assertEquals(
                "投射没有成功。最后状态：$report",
                "已投射 · $clusterName · ID $clusterId",
                report,
            )

            // —— 5. 抓那块屏的画面，证明**真的画上去了** ——
            val bitmap = grab(reader)
            assertNotNull("副屏上没有抓到任何画面", bitmap)
            assertNotNull("副屏上没有抓到任何画面", bitmap!!)

            // 先把图存下来（断言失败也能拉回去看），再断言。
            val out = File(context.getExternalFilesDir(null), "cluster-cast.png")
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            // 非全透明 ⇒ 面板确实画在了这块屏上。
            var opaque = 0
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (p in pixels) {
                if ((p ushr 24) != 0) opaque++
            }
            assertTrue(
                "副屏画面全透明 —— 窗口挂上去了但什么都没画（opaque=$opaque，图：${out.absolutePath}）",
                opaque > 0,
            )
            assertTrue("PNG 没写出来", out.exists() && out.length() > 0)
        } finally {
            prefs.clusterOverlayEnabled = beforeEnabled
            prefs.clusterDisplayId = beforeId
            prefs.clusterDisplayName = beforeName
            context.stopService(android.content.Intent(context, ClusterLyricsService::class.java))
            virtual.release()
            reader.close()        }
    }

    /**
     * 给目标包开「显示在其他应用上层」的 appop。
     *
     * 用 shell 身份执行 `appops set`，等效于用户在系统设置里手动授权。
     */
    private fun grantOverlayPermission(packageName: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        fd.close()
    }

    /**
     * 从 [ImageReader] 取一帧**有内容**的画面。
     *
     * ## 为什么不能只取一次
     *
     * 刚加窗的头几帧可能是空的（合成器还没把这一块画上去），
     * 所以这里循环着取，**保留 alpha 最大的一帧**，直到真的有内容为止。
     *
     * ## 为什么按 alpha 判「有内容」
     *
     * 面板底板本身是半透明的（alpha ≈ 0xB3），字是全不透明的；
     * 所以「alpha > 0 的像素数」就是「面板画上去的面积」，
     * 比「有没有非黑像素」更准确（底板是深色，非黑判定会把底板也算进去，
     * 但纯文字样式下底板是全透明的，只有字有 alpha）。
     */
    private fun grab(reader: ImageReader): Bitmap? {
        var best: Bitmap? = null
        var bestOpaque = -1
        repeat(30) {
            Thread.sleep(200)
            val image: Image = reader.acquireLatestImage() ?: return@repeat
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = image.width
                val height = image.height
                // RGBA_8888 一个像素 4 字节；rowStride 可能比 width*4 大（对齐）。
                val bytes = ByteArray(rowStride * height)
                plane.buffer.rewind()
                plane.buffer.get(bytes)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val row = IntArray(width)
                var opaque = 0
                for (y in 0 until height) {
                    var i = y * rowStride
                    for (x in 0 until width) {
                        val base = i + x * pixelStride
                        val a = bytes[base + 3].toInt() and 0xFF
                        if (a != 0) opaque++
                        row[x] = (a shl 24) or
                            (bytes[base].toInt() and 0xFF shl 16) or
                            (bytes[base + 1].toInt() and 0xFF shl 8) or
                            (bytes[base + 2].toInt() and 0xFF)
                    }
                    bitmap.setPixels(row, 0, width, 0, y, width, 1)
                }
                if (opaque > bestOpaque) {
                    best?.recycle()
                    best = bitmap
                    bestOpaque = opaque
                } else {
                    bitmap.recycle()
                }
                if (bestOpaque > 0) return best
            } finally {
                image.close()
            }
        }
        return best
    }
}
