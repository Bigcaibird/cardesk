package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 包名 → 来源文案的映射单测。
 *
 * 徽标文案与「来源 → 包名」的映射。
 */
class MediaSourceLabelTest {

    @Test
    fun labelForPackage_matchesReferenceMapping() {
        assertEquals("QQ音乐", MediaSources.labelForPackage("com.tencent.qqmusiccar"))
        assertEquals("网易云音乐", MediaSources.labelForPackage("com.netease.cloudmusic.iot"))
        assertEquals("酷狗音乐", MediaSources.labelForPackage("com.kugou.android.auto"))
        assertEquals("酷我音乐", MediaSources.labelForPackage("cn.kuwo.kwmusiccar"))
        assertEquals("汽水音乐", MediaSources.labelForPackage("com.luna.music.car"))
        // 旧版缺的三项
        assertEquals("多媒体", MediaSources.labelForPackage("com.tencent.wecarflow"))
        assertEquals("多媒体", MediaSources.labelForPackage("com.ecarx.multimedia"))
        assertEquals("蓝牙音乐", MediaSources.labelForPackage("com.oem.btmusic"))
        assertEquals("USB音乐", MediaSources.labelForPackage("com.oem.usb.player"))
        assertEquals("USB音乐", MediaSources.labelForPackage("com.oem.localmusic"))
        // 兜底
        assertEquals("通用音乐", MediaSources.labelForPackage("com.oem.something"))
        assertEquals("通用音乐", MediaSources.labelForPackage(""))
    }

    @Test
    fun idForPackage_mapsToSourceIds() {
        assertEquals("qq", MediaSources.idForPackage("com.tencent.qqmusiccar"))
        assertEquals("netease", MediaSources.idForPackage("com.netease.cloudmusic.iot"))
        assertEquals("kugou", MediaSources.idForPackage("com.kugou.android.auto"))
        assertEquals("kuwo", MediaSources.idForPackage("cn.kuwo.kwmusiccar"))
        assertEquals("soda", MediaSources.idForPackage("com.luna.music.car"))
        assertEquals("local", MediaSources.idForPackage("com.tencent.wecarflow"))
        assertEquals("bluetooth", MediaSources.idForPackage("com.oem.btmusic"))
        assertEquals("usb", MediaSources.idForPackage("com.oem.usb.player"))
        assertEquals("generic", MediaSources.idForPackage("com.oem.something"))
        assertEquals("", MediaSources.idForPackage(""))
    }

    @Test
    fun trackerDelegatesToMediaSources() {
        assertEquals("local", MusicSessionTracker.sourceIdForPackage("com.ecarx.multimedia"))
        assertEquals("多媒体", MusicSessionTracker.sourceLabelForPackage("com.ecarx.multimedia"))
    }
}
