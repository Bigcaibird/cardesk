package com.cardesk.launcher

import android.app.Application
import android.net.Uri
import com.cardesk.launcher.core.media.ArtworkProvider
import com.cardesk.launcher.core.media.ArtworkStore
import com.cardesk.launcher.core.update.ApkFileProvider
import com.cardesk.launcher.core.update.AppUpdate
import com.cardesk.launcher.core.update.R2Apk
import com.cardesk.launcher.core.update.R2Release
import com.cardesk.launcher.ui.LanTransferServer
import com.cardesk.launcher.ui.LauncherView
import com.cardesk.launcher.vehicle.HvacState
import com.cardesk.launcher.vehicle.VehicleController
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PushbackInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class AuditFixesTest {

    @Test
    fun `LanTransferServer copyPartBody handles boundary split across 64KB chunk`() {
        val boundary = "--WebKitFormBoundary7MA4YWxkTrZu0gW".toByteArray(Charsets.ISO_8859_1)
        // 65536 - 5 bytes so that "\r\n--WebKit..." is split right across the 64KB read boundary
        val payload = ByteArray(65531) { (it % 251).toByte() }
        val trailer = "--\r\n".toByteArray(Charsets.ISO_8859_1)
        val full = ByteArrayOutputStream().apply {
            write(payload)
            write(byteArrayOf(13, 10))
            write(boundary)
            write(trailer)
        }.toByteArray()

        val input = PushbackInputStream(ByteArrayInputStream(full), 65536 + boundary.size + 16)
        val out = ByteArrayOutputStream()
        val written = LanTransferServer.copyPartBody(input, out, boundary, 1024L * 1024L)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, out.toByteArray())
        assertEquals('-'.code, input.read())
        assertEquals('-'.code, input.read())
        assertEquals(13, input.read())
        assertEquals(10, input.read())
        assertEquals(-1, input.read())
    }

    @Test
    fun `R2Release rejects http url and AppUpdate rejects missing sha256`() {
        val httpRelease = R2Release.parse(
            """{"version":"1.0.2","apk":{"url":"http://file.chuxi.bid/cardesk/a.apk","sha256":"${"a".repeat(64)}"}}""",
            "https://file.chuxi.bid/",
        )
        assertNull(httpRelease?.apk)

        val ctx: Application = RuntimeEnvironment.getApplication()
        val noDigest = R2Release(
            version = "1.0.2",
            versionCode = 9999L,
            tag = "v1.0.2",
            name = "test",
            notes = "",
            publishedAt = "",
            apk = R2Apk("a.apk", "https://file.chuxi.bid/cardesk/a.apk", 1024L, ""),
        )
        assertNull(AppUpdate.download(ctx, noDigest) {})
    }

    @Test
    fun `ApkFileProvider returns content uri for cached apk`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val apk = File(ctx.cacheDir, "test-update.apk").apply { writeText("dummy") }
        val uri = ApkFileProvider.uriFor(ctx, apk)
        assertEquals("content", uri.scheme)
        assertTrue(uri.toString().endsWith("/cache/test-update.apk"))
        apk.delete()
    }

    @Test
    fun `ArtworkProvider blocks path traversal and ArtworkStore prunes old files`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val provider = Robolectric.setupContentProvider(ArtworkProvider::class.java, "${ctx.packageName}.artwork")
        val cursor = provider.query(
            Uri.parse("content://${ctx.packageName}.artwork/cover/../../shared_prefs/cardesk"),
            null, null, null, null,
        )
        assertNull(cursor)

        val dir = File(ctx.filesDir, "test-artwork-prune").apply {
            deleteRecursively()
            mkdirs()
        }
        for (i in 1..105) {
            val f = File(dir, "k$i.jpg")
            f.writeBytes(byteArrayOf(1, 2, 3))
            f.setLastModified(1000L + i * 10L)
        }
        ArtworkStore.pruneCache(dir)
        val remaining = dir.listFiles()?.size ?: 0
        assertEquals(100, remaining)
        assertTrue(!File(dir, "k1.jpg").exists())
        assertTrue(File(dir, "k105.jpg").exists())
        dir.deleteRecursively()
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `LauncherView removes VehicleController observer on detach`() {
        val observersField = VehicleController::class.java.getDeclaredField("observers").apply {
            isAccessible = true
        }
        val observers = observersField.get(VehicleController) as java.util.concurrent.CopyOnWriteArrayList<(HvacState) -> Unit>
        val baseline = observers.size

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val view = LauncherView(activity)
        assertEquals(baseline + 1, observers.size)

        val detachMethod = android.view.View::class.java.getDeclaredMethod("onDetachedFromWindow").apply {
            isAccessible = true
        }
        detachMethod.invoke(view)
        assertEquals(baseline, observers.size)
    }

    @Test
    fun `OemKeyReceiver allows rapid double click and KeyActionExecutor handles click double long`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val prefs = com.cardesk.launcher.core.Prefs(ctx).apply {
            keysEnhanced = true
            voiceClickAction = com.cardesk.launcher.core.Prefs.ACTION_VOICE_ASSIST
            voiceDoubleAction = com.cardesk.launcher.core.Prefs.ACTION_360_TOGGLE
            voiceLongAction = com.cardesk.launcher.core.Prefs.ACTION_DRIVE_MODE
            voiceLongMode = 570491139
        }
        val executed = mutableListOf<Pair<Int, String>>()
        val executor = com.cardesk.launcher.ui.KeyActionExecutor(
            context = ctx,
            prefs = prefs,
            onToggle360 = {},
        ).apply {
            onExecuted = { action, source -> executed.add(action.action to source) }
        }
        com.cardesk.launcher.ui.OemKeyRuntime.attach(ctx, prefs, executor)
        try {
            val receiver = com.cardesk.launcher.ui.OemKeyReceiver(dynamic = true)
            val clickIntent = android.content.Intent(com.cardesk.launcher.core.OemKeyProtocol.ACTION_VOICE).apply {
                putExtra(com.cardesk.launcher.core.OemKeyProtocol.EXTRA_KEY_TYPE, com.cardesk.launcher.core.OemKeyProtocol.KEY_R_VOICE_ASSIST)
                putExtra(com.cardesk.launcher.core.OemKeyProtocol.EXTRA_KEY_ACTION, com.cardesk.launcher.core.OemKeyProtocol.ACTION_TYPE_CLICK)
            }
            // First click: passed through for native voice assist, not aborted
            receiver.onReceive(ctx, clickIntent)
            // Rapid second click (<200ms): must NOT be dropped by receiver throttling; must trigger DOUBLE
            receiver.onReceive(ctx, clickIntent)
            assertTrue(executed.any { it.first == com.cardesk.launcher.core.Prefs.ACTION_360_TOGGLE })

            val longIntent = android.content.Intent(com.cardesk.launcher.core.OemKeyProtocol.ACTION_VOICE).apply {
                putExtra(com.cardesk.launcher.core.OemKeyProtocol.EXTRA_KEY_TYPE, com.cardesk.launcher.core.OemKeyProtocol.KEY_R_VOICE_ASSIST)
                putExtra(com.cardesk.launcher.core.OemKeyProtocol.EXTRA_KEY_ACTION, com.cardesk.launcher.core.OemKeyProtocol.ACTION_TYPE_LONG)
            }
            receiver.onReceive(ctx, longIntent)
            assertTrue(executed.any { it.first == com.cardesk.launcher.core.Prefs.ACTION_DRIVE_MODE })
        } finally {
            com.cardesk.launcher.ui.OemKeyRuntime.detach(executor)
            executor.stop()
        }
    }

    @Test
    fun `GlassDrawable updates shader when Glass backdrop changes`() {
        val drawable = com.cardesk.launcher.core.GlassDrawable()
        drawable.setBounds(0, 0, 200, 100)
        val bmp1 = android.graphics.Bitmap.createBitmap(48, 27, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        val bmp2 = android.graphics.Bitmap.createBitmap(48, 27, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        val out = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        try {
            com.cardesk.launcher.core.Glass.publish(bmp1)
            drawable.draw(canvas)
            val shaderField = com.cardesk.launcher.core.GlassDrawable::class.java.getDeclaredField("backdropBmp").apply {
                isAccessible = true
            }
            assertEquals(bmp1, shaderField.get(drawable))

            com.cardesk.launcher.core.Glass.publish(bmp2)
            drawable.draw(canvas)
            assertEquals(bmp2, shaderField.get(drawable))
        } finally {
            com.cardesk.launcher.core.Glass.clear()
            bmp1.recycle()
            bmp2.recycle()
            out.recycle()
        }
    }

    @Test
    fun `ScenePage compact car status card matches bottom card height and opens vehicle details on tap`() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val page = com.cardesk.launcher.ui.pages.ScenePage(ctx).apply {
            carStatusCardCompact = true
            showCarStatusCard = true
            bind(
                com.cardesk.launcher.vehicle.VehicleInfo(
                    tires = floatArrayOf(240f, 240f, 240f, 230f),
                    doorsClosed = listOf(false, true, true, true),
                ),
                HvacState(),
                0,
            )
            layout(0, 0, 1920, 1080)
        }
        var opened = false
        page.onOpenVehicleStatus = { opened = true }

        val bmp = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        page.draw(canvas)

        val hitsField = com.cardesk.launcher.ui.pages.ScenePage::class.java.getDeclaredField("hits").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val hits = hitsField.get(page) as Map<String, android.graphics.RectF>
        val rect = hits["carStatusCard"]
        assertTrue(rect != null)
        assertEquals(150f, rect!!.height(), 0.5f)
        assertEquals(388f, rect.width(), 0.5f)

        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0)
        val up = android.view.MotionEvent.obtain(0L, 20L, android.view.MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0)
        page.onTouchEvent(down)
        page.onTouchEvent(up)
        assertTrue(opened)
        down.recycle()
        up.recycle()
        bmp.recycle()
    }
}
