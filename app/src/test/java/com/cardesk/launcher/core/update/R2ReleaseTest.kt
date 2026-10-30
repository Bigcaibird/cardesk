package com.cardesk.launcher.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R2Release] / [Digest] 单测（纯逻辑）。
 *
 * 这条链路是「车机上点检查更新」的全部依据：
 * 清单读不懂就查不到更新，包地址拼错就下不到，
 * 摘要算错就会把残包当好包装进车机。
 */
class R2ReleaseTest {

    private val root = "https://file.chuxi.bid/"

    @Test
    fun `完整清单能解析出来`() {
        val json = """
            {
              "schema": 1,
              "version": "1.0.1052",
              "versionCode": 4148,
              "tag": "v1.0.1052",
              "name": "CarDesk 4148 / 1.0.1052-system",
              "notes": "本轮更新说明",
              "publishedAt": "2026-10-30T15:00:00Z",
              "apk": {
                "name": "CarDesk-ecarx-cardesk-4148.apk",
                "url": "cardesk/CarDesk-ecarx-cardesk-4148.apk",
                "size": 1941768,
                "sha256": "b9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9"
              }
            }
        """.trimIndent()
        val r = R2Release.parse(json, root)
        assertNotNull(r)
        assertEquals("1.0.1052", r!!.version)
        assertEquals(4148L, r.versionCode)
        assertEquals("v1.0.1052", r.tag)
        assertEquals("CarDesk 4148 / 1.0.1052-system", r.name)
        assertEquals("本轮更新说明", r.notes)
        assertEquals("2026-10-30T15:00:00Z", r.publishedAt)
        val apk = r.apk
        assertNotNull(apk)
        assertEquals("CarDesk-ecarx-cardesk-4148.apk", apk!!.name)
        assertEquals("https://file.chuxi.bid/cardesk/CarDesk-ecarx-cardesk-4148.apk", apk.url)
        assertEquals(1941768L, apk.size)
        assertEquals("b9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9", apk.sha256)
    }

    @Test
    fun `相对地址按域名根拼开`() {
        val r = R2Release.parse(
            """{"version":"1.0.1052","apk":{"url":"cardesk/a.apk"}}""",
            root,
        )
        assertEquals("https://file.chuxi.bid/cardesk/a.apk", r?.apk?.url)
    }

    @Test
    fun `相对地址开头的斜杠也能拼对`() {
        val r = R2Release.parse(
            """{"version":"1.0.1052","apk":{"url":"/cardesk/a.apk"}}""",
            root,
        )
        assertEquals("https://file.chuxi.bid/cardesk/a.apk", r?.apk?.url)
    }

    @Test
    fun `完整地址原样使用`() {
        val url = "https://file.chuxi.bid/cardesk/b.apk"
        val r = R2Release.parse(
            """{"version":"1.0.1052","apk":{"url":"$url"}}""",
            root,
        )
        assertEquals(url, r?.apk?.url)
    }

    @Test
    fun `非 http 协议的地址不认`() {
        // 清单是外部数据，不能让它把 App 指向 file:// 之类的地方
        assertNull(R2Release.parse("""{"version":"1.0.1","apk":{"url":"file:///etc/passwd"}}""", root)?.apk)
        assertNull(R2Release.parse("""{"version":"1.0.1","apk":{"url":"ftp://x/y.apk"}}""", root)?.apk)
    }

    @Test
    fun `没有版本号就是读不懂`() {
        // 没有版本号没法跟本地比，不猜
        assertNull(R2Release.parse("""{"apk":{"url":"cardesk/a.apk"}}""", root))
        assertNull(R2Release.parse("""{"version":"   ","apk":{"url":"cardesk/a.apk"}}""", root))
    }

    @Test
    fun `读不懂的返回体不抛异常`() {
        assertNull(R2Release.parse("", root))
        assertNull(R2Release.parse("not json", root))
        assertNull(R2Release.parse("[]", root))
        assertNull(R2Release.parse("{}", root))
    }

    @Test
    fun `没有安装包也能解析出版本`() {
        val r = R2Release.parse("""{"version":"1.0.1052","versionCode":4148}""", root)
        assertEquals("1.0.1052", r?.version)
        assertNull(r?.apk)
    }

    @Test
    fun `摘要统一转小写`() {
        val upper = "B9A4ED58F19C9F4476C5A79B77BDD890AA32F4C8D5464144143DAF7CD0DC56B9"
        val r = R2Release.parse("""{"version":"1.0.1","apk":{"url":"a.apk","sha256":"$upper"}}""", root)
        assertEquals(upper.lowercase(), r?.apk?.sha256)
    }

    @Test
    fun `不是六十四位十六进制的摘要当作没有`() {
        // 半信摘要比不信更危险：宁可不做校验，也不能拿错摘要把好包判成坏包
        assertEquals("", R2Release.parse("""{"version":"1.0.1","apk":{"url":"a.apk","sha256":"abc"}}""", root)?.apk?.sha256)
        assertEquals(
            "",
            R2Release.parse("""{"version":"1.0.1","apk":{"url":"a.apk","sha256":"z9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9"}}""", root)?.apk?.sha256,
        )
    }

    @Test
    fun `大小和包名缺失时的默认值`() {
        val r = R2Release.parse("""{"version":"1.0.1","apk":{"url":"cardesk/CarDesk-x.apk"}}""", root)
        assertEquals(0L, r?.apk?.size)
        assertEquals("CarDesk-x.apk", r?.apk?.name)
    }

    @Test
    fun `摘要算得对`() {
        val f = File.createTempFile("cardesk-digest", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            // 已知值：SHA-256("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Digest.sha256Hex(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `读不到的文件摘要返回空`() {
        assertNull(Digest.sha256Hex(File("/definitely/not/exists.bin")))
    }

    @Test
    fun `空文件也有摘要`() {
        val f = File.createTempFile("cardesk-digest-empty", ".bin")
        try {
            f.writeBytes(ByteArray(0))
            assertTrue(Digest.sha256Hex(f)!!.length == 64)
        } finally {
            f.delete()
        }
    }
}
