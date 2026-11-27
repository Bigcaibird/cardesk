package com.cardesk.launcher.core.wallpaperstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperIndexTest {

    private val root = "https://file.chuxi.bid/"

    @Test
    fun `parses static and video entries with thumb resolution and chinese encoding`() {
        val digest = "a".repeat(64)
        val json = """
            {
              "schema": 1,
              "updatedAt": "2026-11-26T12:40:00Z",
              "items": [
                {
                  "name": "湖畔静思",
                  "type": "video",
                  "file": "湖畔 静思.mp4",
                  "key": "cardesk/wallpapers/video/湖畔 静思.mp4",
                  "thumbKey": "cardesk/wallpapers/thumbs/湖畔 静思.jpg",
                  "size": 12345678,
                  "sha256": "$digest",
                  "addedAt": "2026-11-26T12:40:00Z"
                },
                {
                  "name": "落日山脊",
                  "type": "static",
                  "file": "sunset.jpg",
                  "key": "cardesk/wallpapers/static/sunset.jpg",
                  "size": 654321,
                  "sha256": "invalid-short",
                  "addedAt": "2026-11-26T12:00:00Z"
                }
              ]
            }
        """.trimIndent()

        val index = WallpaperIndex.parse(json, root)
        assertEquals("2026-11-26T12:40:00Z", index.updatedAt)
        assertEquals(2, index.items.size)

        val video = index.items[0]
        assertEquals("湖畔静思", video.name)
        assertEquals(WallpaperKind.VIDEO, video.kind)
        assertTrue(video.url.startsWith("https://file.chuxi.bid/cardesk/wallpapers/video/"))
        assertTrue(video.url.contains("%20"))
        assertTrue(video.thumbUrl.startsWith("https://file.chuxi.bid/cardesk/wallpapers/thumbs/"))
        assertEquals(12345678L, video.size)
        assertEquals(digest, video.sha256)

        val staticItem = index.items[1]
        assertEquals("落日山脊", staticItem.name)
        assertEquals(WallpaperKind.STATIC, staticItem.kind)
        assertEquals("https://file.chuxi.bid/cardesk/wallpapers/static/sunset.jpg", staticItem.url)
        // Static without explicit thumbKey falls back to its own URL
        assertEquals(staticItem.url, staticItem.thumbUrl)
        // Invalid sha256 is discarded
        assertEquals("", staticItem.sha256)
    }

    @Test
    fun `rejects unsafe schemes and infers kind from extension`() {
        val json = """
            {
              "schema": 1,
              "updatedAt": "2026-11-26T12:40:00Z",
              "items": [
                { "name": "evil", "key": "file:///etc/passwd" },
                { "key": "cardesk/wallpapers/video/night_drive.mp4", "size": 999 }
              ]
            }
        """.trimIndent()

        val index = WallpaperIndex.parse(json, root)
        assertEquals(1, index.items.size)
        assertEquals("night_drive", index.items[0].name)
        assertEquals(WallpaperKind.VIDEO, index.items[0].kind)
        assertEquals("", index.items[0].thumbUrl)
    }

    @Test
    fun `targetFileName uses readable sanitized display name with original extension`() {
        val cat = OnlineWallpaperEntry(
            name = "慵懒黑猫",
            kind = WallpaperKind.VIDEO,
            file = "9bf649833cba39249319a65cd54bb8e0.mp4",
            url = "https://file.chuxi.bid/cardesk/wallpapers/video/9bf649833cba39249319a65cd54bb8e0.mp4",
            thumbUrl = "",
            size = 1046110L,
            sha256 = "",
            addedAt = "",
        )
        assertEquals("慵懒黑猫.mp4", WallpaperStore.targetFileName(cat))

        val unsafe = cat.copy(name = "a/b:c*d?", file = "raw.PNG", kind = WallpaperKind.STATIC)
        assertEquals("a_b_c_d_.png", WallpaperStore.targetFileName(unsafe))

        val blank = cat.copy(name = "   ")
        assertEquals("9bf649833cba39249319a65cd54bb8e0.mp4", WallpaperStore.targetFileName(blank))
    }
}
