package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲目文案取值链的纯逻辑单测。
 *
 *
 *
 */
class MediaTextTest {

    @Test
    fun hasMetadata_acceptsDisplayTitleOnly() {
        assertTrue(MediaText.hasMetadata(MediaText.Meta(displayTitle = "夜航星")))
        assertTrue(MediaText.hasMetadata(MediaText.Meta(title = "夜航星")))
        assertFalse(MediaText.hasMetadata(MediaText.Meta(artist = "不才")))
        assertFalse(MediaText.hasMetadata(null))
    }

    // ---------------------------------------------------------------- 歌名

    @Test
    fun resolveTrackName_normalSourceUsesDescriptionThenTitle() {
        val withDesc = MediaText.Meta(title = "TITLE", descriptionTitle = "DESC")
        assertEquals("DESC", MediaText.resolveTrackName(withDesc, "", false, true))

        val onlyTitle = MediaText.Meta(title = "TITLE")
        assertEquals("TITLE", MediaText.resolveTrackName(onlyTitle, "", false, true))
    }

    @Test
    fun resolveTrackName_lyricSourceSkipsUnstableTitles() {
        // DISPLAY_TITLE 是一句歌词（不稳定）→ 回退到稳定的 TITLE
        val meta = MediaText.Meta(
            title = "夜航星",
            displayTitle = "我看见夜空中最亮的星，请指引我靠近你",
        )
        assertEquals("夜航星", MediaText.resolveTrackName(meta, "", true, true))
    }

    @Test
    fun resolveTrackName_lyricSourceKeepsLastTitleWhenNotAllowed() {
        val meta = MediaText.Meta(title = "新歌", displayTitle = "新歌")
        assertEquals("旧歌", MediaText.resolveTrackName(meta, "旧歌", true, false))
        assertEquals("新歌", MediaText.resolveTrackName(meta, "旧歌", true, true))
    }

    @Test
    fun resolveTrackName_nullMetaKeepsLastTitle() {
        assertEquals("旧歌", MediaText.resolveTrackName(null, "旧歌", true, true))
        assertNull(MediaText.resolveTrackName(null, "", true, true))
    }

    // ---------------------------------------------------------------- 歌手

    @Test
    fun resolveArtistName_fallsBackToAlbumArtistThenSubtitle() {
        assertEquals("不才", MediaText.resolveArtistName(MediaText.Meta(artist = "不才"), false))
        assertEquals(
            "不才",
            MediaText.resolveArtistName(MediaText.Meta(albumArtist = "不才"), false),
        )
        assertEquals(
            "副标题",
            MediaText.resolveArtistName(MediaText.Meta(subtitle = "副标题"), false),
        )
    }

    @Test
    fun resolveArtistName_lyricSourceUnwrapsTitleArtistCombo() {
        val meta = MediaText.Meta(artist = "夜航星 - 不才")
        assertEquals("不才", MediaText.resolveArtistName(meta, true))
    }

    @Test
    fun looksLikeTitleArtistCombo_detectsSeparators() {
        assertTrue(MediaText.hasTitleArtistSeparator("歌名 - 歌手"))
        assertTrue(MediaText.hasTitleArtistSeparator("歌名 · 歌手"))
        assertFalse(MediaText.hasTitleArtistSeparator("歌名歌手"))
    }

    @Test
    fun unwrapTitleArtistCombo_keepsPlainText() {
        assertEquals("不才", MediaText.unwrapTitleArtistCombo("不才", "", ""))
        assertEquals("不才", MediaText.unwrapTitleArtistCombo("夜航星 - 不才", "", ""))
    }
}
