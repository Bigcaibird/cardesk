package com.cardesk.launcher.core.apkstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApkIndex] / [ApkEntry] 单测（纯逻辑）。
 *
 * 清单是**外部数据**（R2 上的一个 JSON 文件），车机拿它决定「有哪些包能装」。
 * 这里最怕两件事：
 *
 * 1. 拼错地址 → 点一下装不上，还不知道为什么；
 * 2. 一条脏数据把整份清单废掉 → 明明传了包，车机上却显示「一个都没有」。
 */
class ApkIndexTest {

    private val root = "https://file.chuxi.bid/"

    @Test
    fun `完整清单能解析出来`() {
        val json = """
            {
              "schema": 1,
              "updatedAt": "2026-10-30T09:00:00Z",
              "items": [
                {
                  "name": "酷狗音乐",
                  "file": "kugou.apk",
                  "key": "cardesk/apks/kugou.apk",
                  "size": 12345678,
                  "sha256": "b9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9",
                  "addedAt": "2026-10-30T09:00:00Z"
                }
              ]
            }
        """.trimIndent()
        val index = ApkIndex.parse(json, root)
        assertEquals("2026-10-30T09:00:00Z", index.updatedAt)
        assertEquals(1, index.items.size)
        val it = index.items[0]
        assertEquals("酷狗音乐", it.name)
        assertEquals("kugou.apk", it.file)
        assertEquals("https://file.chuxi.bid/cardesk/apks/kugou.apk", it.url)
        assertEquals(12345678L, it.size)
        assertEquals("b9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9", it.sha256)
        assertEquals("2026-10-30T09:00:00Z", it.addedAt)
    }

    @Test
    fun `相对 key 按域名根拼开`() {
        val index = ApkIndex.parse(
            """{"items":[{"key":"cardesk/apks/a.apk"}]}""",
            root,
        )
        assertEquals("https://file.chuxi.bid/cardesk/apks/a.apk", index.items[0].url)
    }

    @Test
    fun `相对 key 开头的斜杠也能拼对`() {
        val index = ApkIndex.parse(
            """{"items":[{"key":"/cardesk/apks/a.apk"}]}""",
            root,
        )
        assertEquals("https://file.chuxi.bid/cardesk/apks/a.apk", index.items[0].url)
    }

    @Test
    fun `绝对地址原样使用`() {
        val url = "https://file.chuxi.bid/cardesk/apks/b.apk"
        val index = ApkIndex.parse("""{"items":[{"key":"$url"}]}""", root)
        assertEquals(url, index.items[0].url)
    }

    @Test
    fun `非 http 协议的地址不认`() {
        // 清单是外部数据，不能让它把 App 指向 file:// 之类的地方
        assertTrue(ApkIndex.parse("""{"items":[{"key":"file:///etc/passwd"}]}""", root).items.isEmpty())
        assertTrue(ApkIndex.parse("""{"items":[{"key":"ftp://x/y.apk"}]}""", root).items.isEmpty())
    }

    @Test
    fun `一条坏了不影响其它条`() {
        val index = ApkIndex.parse(
            """
            {"items":[
              {"key":"cardesk/apks/good.apk","file":"good.apk"},
              {"key":"","file":"nokey.apk"},
              {"key":"file:///etc/passwd"},
              {"key":"cardesk/apks/also-good.apk","file":"also-good.apk"}
            ]}
            """.trimIndent(),
            root,
        )
        assertEquals(2, index.items.size)
        assertEquals("good.apk", index.items[0].file)
        assertEquals("also-good.apk", index.items[1].file)
    }

    @Test
    fun `中文包名也能拼出地址`() {
        // 用户传上去的 APK 名字里可能有中文。URI.create 碰到非 ASCII 会直接抛异常，
        // 那这条记录就被静默丢掉了 —— 用户看不到自己刚传的包。
        val index = ApkIndex.parse(
            """{"items":[{"key":"cardesk/apks/酷狗音乐.apk","file":"酷狗音乐.apk","name":"酷狗音乐"}]}""",
            root,
        )
        assertEquals(1, index.items.size)
        assertEquals(
            "https://file.chuxi.bid/cardesk/apks/%E9%85%B7%E7%8B%97%E9%9F%B3%E4%B9%90.apk",
            index.items[0].url,
        )
        assertEquals("酷狗音乐", index.items[0].name)
    }

    @Test
    fun `带空格的名字也能拼出地址`() {
        val index = ApkIndex.parse(
            """{"items":[{"key":"cardesk/apks/My App.apk"}]}""",
            root,
        )
        assertEquals("https://file.chuxi.bid/cardesk/apks/My%20App.apk", index.items[0].url)
    }

    @Test
    fun `items 不是数组就是空清单`() {
        assertTrue(ApkIndex.parse("""{"items":"nope"}""", root).items.isEmpty())
        assertTrue(ApkIndex.parse("""{"items":{}}""", root).items.isEmpty())
        assertTrue(ApkIndex.parse("""{}""", root).items.isEmpty())
    }

    @Test
    fun `读不懂的返回体不抛异常`() {
        assertTrue(ApkIndex.parse("", root).items.isEmpty())
        assertTrue(ApkIndex.parse("not json", root).items.isEmpty())
        assertTrue(ApkIndex.parse("[]", root).items.isEmpty())
        assertTrue(ApkIndex.parse("null", root).items.isEmpty())
    }

    @Test
    fun `空清单就是没有应用`() {
        val index = ApkIndex.parse("""{"schema":1,"updatedAt":"","items":[]}""", root)
        assertTrue(index.items.isEmpty())
        assertEquals("", index.updatedAt)
    }

    @Test
    fun `摘要统一转小写`() {
        val upper = "B9A4ED58F19C9F4476C5A79B77BDD890AA32F4C8D5464144143DAF7CD0DC56B9"
        val index = ApkIndex.parse("""{"items":[{"key":"a.apk","sha256":"$upper"}]}""", root)
        assertEquals(upper.lowercase(), index.items[0].sha256)
    }

    @Test
    fun `不是六十四位十六进制的摘要当作没有`() {
        // 半信摘要比不信更危险：宁可不做校验，也不能拿错摘要把好包判成坏包
        val index = ApkIndex.parse("""{"items":[{"key":"a.apk","sha256":"abc"}]}""", root)
        assertEquals("", index.items[0].sha256)
        val index2 = ApkIndex.parse("""{"items":[{"key":"a.apk","sha256":"z9a4ed58f19c9f4476c5a79b77bdd890aa32f4c8d5464144143daf7cd0dc56b9"}]}""", root)
        assertEquals("", index2.items[0].sha256)
    }

    @Test
    fun `缺名字就退到文件名`() {
        val index = ApkIndex.parse("""{"items":[{"key":"cardesk/apks/kugou.apk"}]}""", root)
        assertEquals("kugou.apk", index.items[0].file)
        assertEquals("kugou", index.items[0].name)
    }

    @Test
    fun `缺大小就是零表示不校验`() {
        val index = ApkIndex.parse("""{"items":[{"key":"a.apk"}]}""", root)
        assertEquals(0L, index.items[0].size)
    }
}
