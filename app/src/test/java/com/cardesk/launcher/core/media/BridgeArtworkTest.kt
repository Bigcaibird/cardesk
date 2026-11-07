package com.cardesk.launcher.core.media

import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BridgeArtwork] 的单测（第132轮）。
 *
 * ## 这条测试守住什么
 *
 * 用户诉求：「接管方控之后，**仪表只有歌名和歌手，没有图片**；
 * 车机默认的爱趣听是有图片的。」
 *
 * 根因（反编译原车 `carmedia/MultiMedia.apk` 实锤）：
 * 原车 SDK 里 `IMediaInfo#getArtwork()` 返回 `android.net.Uri`，
 * 我们的代理**没实现**这个 getter → 返回 null → 仪表拿不到封面。
 *
 * 这些断言把「原车同一套取图口径」钉死：
 * - 取值顺序 **DISPLAY_ICON_URI → ART_URI → ALBUM_ART_URI**（原车 `MediaCenterHelper.D()`）；
 * - `ART_URI` 是**本地裸路径**时，文件存在才给 `file://`，不存在当没有图；
 * - 认不出来的 Uri 一律当没有图（宁可没图，也不给一个读不出来的）；
 * - 封面变了要重推（异步到手的图不能被「歌名没变」吃掉）。
 */
class BridgeArtworkTest {

    private fun meta(
        displayIcon: String? = null,
        art: String? = null,
        albumArt: String? = null,
    ): Map<String, String?> = mapOf(
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI to displayIcon,
        MediaMetadata.METADATA_KEY_ART_URI to art,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI to albumArt,
    )

    // ---------------------------------------------------------------- 优先级

    @Test
    fun `取值顺序与原车一致_先DISPLAY_ICON再ART再ALBUM_ART`() {
        assertEquals(
            listOf(
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            ),
            BridgeArtwork.URI_KEY_PRIORITY,
        )
    }

    @Test
    fun `三个都有时取DISPLAY_ICON_URI`() {
        val picked = BridgeArtwork.pickUri(
            meta(
                displayIcon = "content://icon/1",
                art = "content://art/1",
                albumArt = "content://album/1",
            ),
        )
        assertEquals("content://icon/1", picked)
    }

    @Test
    fun `没有DISPLAY_ICON时取ART_URI`() {
        val picked = BridgeArtwork.pickUri(
            meta(art = "content://art/1", albumArt = "content://album/1"),
        )
        assertEquals("content://art/1", picked)
    }

    @Test
    fun `只有ALBUM_ART_URI时取它`() {
        val picked = BridgeArtwork.pickUri(meta(albumArt = "content://album/1"))
        assertEquals("content://album/1", picked)
    }

    @Test
    fun `一个都没有时是空串`() {
        assertEquals(BridgeArtwork.EMPTY_ARTWORK, BridgeArtwork.pickUri(meta()))
    }

    // ---------------------------------------------------------------- 合法性

    @Test
    fun `只认content_file_http_https四种`() {
        assertTrue(BridgeArtwork.isImageUri("content://media/external/audio/albumart/12"))
        assertTrue(BridgeArtwork.isImageUri("file:///storage/emulated/0/Music/cover.jpg"))
        assertTrue(BridgeArtwork.isImageUri("http://img.y.qq.com/cover.jpg"))
        assertTrue(BridgeArtwork.isImageUri("https://img.y.qq.com/cover.jpg"))
    }

    @Test
    fun `认不出来的一律不算图`() {
        // 空 / 空白 / 垃圾串 / 播放器自己的资源（跨进程读不到）
        assertFalse(BridgeArtwork.isImageUri(null))
        assertFalse(BridgeArtwork.isImageUri(""))
        assertFalse(BridgeArtwork.isImageUri("   "))
        assertFalse(BridgeArtwork.isImageUri("not a uri"))
        assertFalse(BridgeArtwork.isImageUri("android.resource://com.tencent.qqmusiccar/drawable/cover"))
    }

    @Test
    fun `垃圾的DISPLAY_ICON会被跳过_继续看ART_URI`() {
        val picked = BridgeArtwork.pickUri(
            meta(displayIcon = "android.resource://x/y", art = "content://art/1"),
        )
        assertEquals("content://art/1", picked)
    }

    // ---------------------------------------------------------------- 本地路径（ART_URI 特例）

    @Test
    fun `本地裸路径算本地路径不是Uri`() {
        assertTrue(BridgeArtwork.isLocalPath("/storage/emulated/0/Music/cover.jpg"))
        assertFalse(BridgeArtwork.isLocalPath("content://media/1"))
        assertFalse(BridgeArtwork.isLocalPath(null))
    }

    @Test
    fun `本地路径文件存在时转成file开头`() {
        assertEquals(
            "file:///storage/emulated/0/Music/cover.jpg",
            BridgeArtwork.resolveUri("/storage/emulated/0/Music/cover.jpg", true),
        )
    }

    @Test
    fun `本地路径文件不存在时当没有图`() {
        assertEquals(
            BridgeArtwork.EMPTY_ARTWORK,
            BridgeArtwork.resolveUri("/storage/emulated/0/Music/cover.jpg", false),
        )
    }

    @Test
    fun `普通Uri原样透传`() {
        assertEquals(
            "content://media/external/audio/albumart/12",
            BridgeArtwork.resolveUri("content://media/external/audio/albumart/12", false),
        )
    }

    @Test
    fun `认不出来的候选解析后是空串`() {
        assertEquals(
            BridgeArtwork.EMPTY_ARTWORK,
            BridgeArtwork.resolveUri("android.resource://x/y", false),
        )
    }

    // ---------------------------------------------------------------- 变化判定

    @Test
    fun `封面变了要重推`() {
        assertTrue(BridgeArtwork.changed("", "content://a/1"))
        assertTrue(BridgeArtwork.changed("content://a/1", "content://a/2"))
    }

    @Test
    fun `封面没变不重推`() {
        assertFalse(BridgeArtwork.changed("", ""))
        assertFalse(BridgeArtwork.changed("content://a/1", "content://a/1"))
        assertFalse(BridgeArtwork.changed(null, null))
    }

    // ---------------------------------------------------------------- 歌的 key

    @Test
    fun `歌的key由来源歌名歌手组成`() {
        assertEquals(
            "com.tencent.qqmusiccar|春风吹|方大同",
            BridgeArtwork.songKey("com.tencent.qqmusiccar", "春风吹", "方大同"),
        )
    }

    @Test
    fun `空歌名歌手来源时key是空串`() {
        assertEquals("", BridgeArtwork.songKey("", "", ""))
        assertEquals("", BridgeArtwork.songKey(null, null, null))
    }

    // ---------------------------------------------------------------- getter 映射（第132轮根因）

    @Test
    fun `getArtwork必须被认出来`() {
        assertEquals(
            BridgeArtwork.ArtworkGetter.CURRENT,
            BridgeArtwork.artworkGetter("getArtwork"),
        )
    }

    @Test
    fun `next和previous的封面getter也认得`() {
        assertEquals(BridgeArtwork.ArtworkGetter.NEXT, BridgeArtwork.artworkGetter("getNextArtwork"))
        assertEquals(
            BridgeArtwork.ArtworkGetter.PREVIOUS,
            BridgeArtwork.artworkGetter("getPreviousArtwork"),
        )
    }

    @Test
    fun `别的getter不是取封面的`() {
        assertEquals(BridgeArtwork.ArtworkGetter.NONE, BridgeArtwork.artworkGetter("getTitle"))
        assertEquals(BridgeArtwork.ArtworkGetter.NONE, BridgeArtwork.artworkGetter("getArtist"))
        assertEquals(BridgeArtwork.ArtworkGetter.NONE, BridgeArtwork.artworkGetter("toString"))
        assertEquals(BridgeArtwork.ArtworkGetter.NONE, BridgeArtwork.artworkGetter(""))
    }

    @Test
    fun `key对不上就是不是这一首`() {
        val a = BridgeArtwork.songKey("com.tencent.qqmusiccar", "春风吹", "方大同")
        val b = BridgeArtwork.songKey("com.tencent.qqmusiccar", "春风吹", "方大同")
        val c = BridgeArtwork.songKey("com.tencent.qqmusiccar", "一生所爱", "卢冠廷")
        assertEquals(a, b)
        assertFalse(a == c)
    }
}
