package com.cardesk.launcher.cluster

import android.graphics.Bitmap
import android.graphics.Color
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

    /** 夜间仪表盘面底色（合成证据图用）。 */
    private val CLUSTER_BG_NIGHT = 0xFF1A2430.toInt()

    /** 日间仪表盘面底色（白色，合成证据图用）。 */
    private val CLUSTER_BG_DAY = 0xFFFFFFFF.toInt()

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
     * 日夜切换后，副屏上的歌词**颜色要跟着换**（第一百三十七轮）。
     *
     * ## 这条链子有多长
     *
     * 设置里的日夜模式 → 桌面重上主题（`Skin.apply` → `Skin.isNight` 变）
     * → 投射服务下一次喂数发现日夜态变了 → 原地换配色 → 副屏上的字变了颜色。
     *
     * 中间任何一环没接上，这条测试就会红 ——
     * 所以它测的不是「配色表对不对」（那个是 JVM 单测 `ClusterLyricColorsTest` 管的事），
     * 而是**这条联动链真的接上了**。
     *
     * ## 怎么判定「颜色变了」
     *
     * 抓那块副屏的画面，只统计**不透明像素的 RGB 平均亮度**（不看 alpha）：
     *
     * - 夜间空态文字用 [ClusterLyricColors.NIGHT_SUB]（浅灰蓝）⇒ 亮度高；
     * - 日间空态文字用 [ClusterLyricColors.DAY_SUB]（深石板灰）⇒ 亮度低。
     *
     * 两个值差一个数量级（0.44 vs 0.09），阈值取中间毫无争议。
     */
    @Test
    fun 日夜切换后副屏上的歌词颜色会跟着换() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(DisplayManager::class.java)
        assertNotNull("拿不到 DisplayManager", manager)
        grantOverlayPermission(context.packageName)

        val reader = ImageReader.newInstance(clusterWidth, clusterHeight, PixelFormat.RGBA_8888, 3)
        val virtual = manager.createVirtualDisplay(
            "CarDesk 测试仪表",
            clusterWidth,
            clusterHeight,
            clusterDpi,
            reader.surface,
            0,
        )
        val display = virtual.display
        val clusterName = display.name
        val clusterId = display.displayId

        val prefs = Prefs(context)
        val beforeEnabled = prefs.clusterOverlayEnabled
        val beforeId = prefs.clusterDisplayId
        val beforeName = prefs.clusterDisplayName
        val beforeDayEnabled = prefs.dayNightEnabled
        val beforeMode = prefs.dayNightMode
        val beforeDayStart = prefs.dayStartMin
        val beforeNightStart = prefs.nightStartMin

        try {
            prefs.clusterDisplayId = clusterId
            prefs.clusterDisplayName = clusterName
            prefs.clusterOverlayEnabled = true

            // —— 先强制成**夜间**，等解析器给出夜间 + 服务换上深色底板上的亮字 ——
            forceDayNight(prefs, night = true)
            assertTrue(
                "日夜判定没有给出夜间（resolve=${resolveNight(prefs, context)}）",
                awaitNight(prefs, context, true),
            )
            ClusterLyricsService.apply(context)
            awaitReport("已投射")
            val nightLuma = awaitLuminance(reader) { luma -> luma > 0.25f }
            assertTrue("夜间没抓到画面", nightLuma > 0f)
            save(context, reader, "cluster-cast-night.png", CLUSTER_BG_NIGHT)

            // —— 再强制成**日间** ——
            forceDayNight(prefs, night = false)
            assertTrue(
                "日夜判定没有给出日间（resolve=${resolveNight(prefs, context)}）",
                awaitNight(prefs, context, false),
            )
            // 服务下一次喂数（700ms）就会重配颜色，等它转过来。
            val dayLuma = awaitLuminance(reader) { luma -> luma < 0.2f }
            save(context, reader, "cluster-cast-day.png", CLUSTER_BG_DAY)

            assertTrue(
                "日间模式下副屏上的字还是亮的（day=$dayLuma night=$nightLuma）—— " +
                    "日夜配色没联动上",
                dayLuma < nightLuma - 0.1f,
            )
        } finally {
            prefs.clusterOverlayEnabled = beforeEnabled
            prefs.clusterDisplayId = beforeId
            prefs.clusterDisplayName = beforeName
            prefs.dayNightEnabled = beforeDayEnabled
            prefs.dayNightMode = beforeMode
            prefs.dayStartMin = beforeDayStart
            prefs.nightStartMin = beforeNightStart
            context.stopService(android.content.Intent(context, ClusterLyricsService::class.java))
            virtual.release()
            reader.close()
        }
    }

    /**
     * 用**设置页那几个键**把桌面强制成日间 / 夜间。
     *
     * 走的是真实路径（日夜模式 → 跟随本地时间 → 时段表），
     * 不是直接改 `Skin.isNight` —— 后者会被桌面下一次刷新覆盖掉，测不出联动。
     *
     * 时段表怎么摆：
     * - 夜间：白天起止**相同**（`0` / `0`）⇒ `DayNight.isNight` 恒为 true；
     * - 日间：白天 `0:00` 起、夜间 `23:59` 起 ⇒ 几乎一整天都是白天。
     */
    private fun forceDayNight(prefs: Prefs, night: Boolean) {
        prefs.dayNightEnabled = true
        prefs.dayNightMode = com.cardesk.launcher.core.DayNight.MODE_FOLLOW_TIME
        if (night) {
            prefs.dayStartMin = 0
            prefs.nightStartMin = 0
        } else {
            prefs.dayStartMin = 0
            prefs.nightStartMin = 1439
        }
    }

    /**
     * 等**投射服务用的那个**日夜判定变成 [expected]。
     *
     * 注意这里等的是 [NightResolver] 的判定结果，**不是**桌面的 `Skin.isNight` ——
     * 投射服务现在**自己**去问解析器，不依赖桌面活着
     * （开机广播会独立拉起它，那时桌面还没跑过一次）。
     */
    private fun awaitNight(
        prefs: Prefs,
        context: android.content.Context,
        expected: Boolean,
        timeoutMs: Long = 20_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (resolveNight(prefs, context) == expected) return true
            Thread.sleep(200)
        }
        return resolveNight(prefs, context) == expected
    }

    /** 投射服务此刻判定的日夜态（与它用的是同一个解析器）。 */
    private fun resolveNight(prefs: Prefs, context: android.content.Context): Boolean =
        com.cardesk.launcher.core.NightResolver.resolve(prefs, context)

    /** 等投射状态变成「已投射」。 */
    private fun awaitReport(prefix: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ClusterLyricsService.lastReport.startsWith(prefix)) return
            Thread.sleep(200)
        }
    }

    /**
     * 反复抓帧，直到不透明像素的平均亮度满足 [accept]，返回那个亮度。
     *
     * 只统计 alpha > 100 的像素的 **RGB**（不看 alpha 本身）——
     * 抗锯齿边缘的 alpha 是渐变的，但 RGB 始终是字本身的颜色。
     */
    private fun awaitLuminance(reader: ImageReader, accept: (Float) -> Boolean): Float {
        val deadline = System.currentTimeMillis() + 12_000
        var last = -1f
        while (System.currentTimeMillis() < deadline) {
            val bmp = grab(reader)
            if (bmp != null) {
                val luma = meanLuminance(bmp)
                if (luma >= 0f) {
                    last = luma
                    if (accept(luma)) return luma
                }
            }
            Thread.sleep(300)
        }
        return if (last >= 0f) last else 0f
    }

    /** 不透明像素的平均相对亮度（0..1）。没有不透明像素时返回 -1。 */
    private fun meanLuminance(bitmap: Bitmap): Float {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        var n = 0
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a <= 100) continue
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b))
            n++
        }
        if (n == 0) return -1f
        return (sum / n).toFloat()
    }

    private fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /**
     * 把当前这一帧存成 PNG（肉眼可查）。
     *
     * ## 为什么要合成到仪表底色上
     *
     * 抓回来的帧是**透明底**的（虚拟屏背后什么都没有），
     * 直接看的话：夜间那张是浅灰蓝的字，落在查看器的白底上几乎看不见 ——
     * 看起来像「没画出来」，其实是「这套颜色是给深色仪表用的」。
     *
     * 所以按各自的车况合成到真实仪表底色上：
     * 夜间 → 深色盘面，日间 → 白色盘面。这样图就是**车机上真正看到的样子**。
     */
    private fun save(context: android.content.Context, reader: ImageReader, name: String, bg: Int) {
        val bmp = grab(reader) ?: return
        val canvas = android.graphics.Canvas(bmp)
        // 先铺底色，再把带 alpha 的字画上去。
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val flatCanvas = android.graphics.Canvas(flat)
        flatCanvas.drawColor(bg)
        flatCanvas.drawBitmap(bmp, 0f, 0f, null)
        val out = File(context.getExternalFilesDir(null), name)
        FileOutputStream(out).use { stream -> flat.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        canvas.drawColor(Color.TRANSPARENT)
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
