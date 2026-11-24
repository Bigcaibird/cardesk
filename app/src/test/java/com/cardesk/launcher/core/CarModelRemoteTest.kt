package com.cardesk.launcher.core

import com.cardesk.launcher.core.car3d.CarModelIndex
import com.cardesk.launcher.core.car3d.CarModelRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端车模索引/版本表/zip 路径安全的纯逻辑单测。
 *
 * 这些是「线上格式」契约：脚本生成的 `index.json` 与 App 解析必须严格一致，
 * 所以这里不依赖 Android 框架（**不要**加 Robolectric），也不做网络/文件装配。
 */
class CarModelRemoteTest {

    /** 造一个合法的 64 位小写 sha256（每个测试用不同字符便于区分）。 */
    private fun sha(c: Char): String = c.toString().repeat(64)

    private val oneModel = """
        {
          "dir": "lynk01",
          "name": "领克 01",
          "version": 2,
          "size": 1234,
          "sha256": "${sha('a')}",
          "files": [{"name": "model.json", "sha256": "${sha('b')}", "bytes": 100}]
        }
    """.trimIndent()

    private fun indexJson(models: String = oneModel): String =
        """{"format":"${CarModelRemote.FORMAT}","version":1,"updated":"20261124","models":[$models]}"""

    // ------------------------------------------------------------ 线上常量

    @Test
    fun `线上常量_固定值不能随便改`() {
        assertEquals("cardesk.carmodels/1", CarModelRemote.FORMAT)
        assertEquals("cardesk/car-models/index.json", CarModelRemote.INDEX_PATH)
        assertEquals("https://file.chuxi.bid/", CarModelRemote.BASE_URL)
        assertEquals(
            "cardesk/car-models/lynk01-v2.zip",
            CarModelIndex.defaultObjectKey("lynk01", 2),
        )
    }

    // ------------------------------------------------------------ 索引解析

    @Test
    fun `索引解析_读到版本与逐文件清单`() {
        val index = CarModelIndex.parse(indexJson())!!
        assertEquals(CarModelRemote.FORMAT, index.format)
        assertEquals(1, index.version)
        assertEquals("20261124", index.updated)
        val model = index.models.single()
        assertEquals("lynk01", model.dir)
        assertEquals("领克 01", model.name)
        assertEquals(2, model.version)
        assertEquals(1234L, model.size)
        assertEquals(sha('a'), model.sha256)
        assertEquals("https://file.chuxi.bid/cardesk/car-models/lynk01-v2.zip", model.url)
        assertEquals("model.json", model.files.single().name)
        assertEquals(100L, model.files.single().bytes)
    }

    @Test
    fun `索引解析_缺省 name 与 url 自动补齐`() {
        val json =
            """{"format":"cardesk.carmodels/1","models":[{"dir":"lynk05","version":3,"size":10,"sha256":"${sha('c')}"}]}"""
        val index = CarModelIndex.parse(json)!!
        assertEquals(1, index.version) // 没写 version 时按 1
        val model = index.models.single()
        assertEquals("lynk05", model.name) // name 缺省用 dir
        assertEquals(
            "https://file.chuxi.bid/cardesk/car-models/lynk05-v3.zip",
            model.url,
        )
        assertTrue(model.files.isEmpty())
    }

    @Test
    fun `索引解析_只给逐文件摘要也能用_两者都缺则拒绝`() {
        // 精简索引（只有 files）：包级 size/sha256 缺省，安装时退化为逐文件校验
        val minimal =
            """{"format":"cardesk.carmodels/1","models":[{"dir":"lynk01","version":2,""" +
                """"files":[{"name":"model.json","sha256":"${sha('a')}","bytes":10}]}]}"""
        val model = CarModelIndex.parse(minimal)!!.models.single()
        assertEquals(0L, model.size)
        assertEquals("", model.sha256)
        assertEquals(1, model.files.size)

        // 既没有包摘要也没有文件清单：没有任何完整性依据，不能装
        assertNull(
            CarModelIndex.parse(
                """{"format":"cardesk.carmodels/1","models":[{"dir":"lynk01","version":1,"size":10}]}""",
            ),
        )
    }

    @Test
    fun `索引解析_格式或内容不可信时拒绝`() {
        assertNull(CarModelIndex.parse(""))
        assertNull(CarModelIndex.parse("not json"))
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/2","models":[$oneModel]}"""))
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/1"}"""))
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/1","models":[]}"""))
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/1","models":[{"dir":"lynk01","version":0,"size":1,"sha256":"${sha('a')}"}]}"""))
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/1","models":[{"dir":"lynk01","version":1,"size":1,"sha256":"abc"}]}"""))
        // dir 会被拼进缓存路径：带斜杠/上跳的直接拒绝
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/1","models":[{"dir":"../x","version":1,"size":1,"sha256":"${sha('a')}"}]}"""))
        assertNull(CarModelIndex.parse("""{"format":"cardesk.carmodels/1","models":[{"dir":"a/b","version":1,"size":1,"sha256":"${sha('a')}"}]}"""))
    }

    @Test
    fun `索引解析_逐文件清单不可信时整条车模作废`() {
        val badName = indexJson(
            """{"dir":"lynk01","version":1,"size":10,"sha256":"${sha('a')}","files":[{"name":"../evil.webp","sha256":"${sha('b')}","bytes":1}]}""",
        )
        assertNull(CarModelIndex.parse(badName))
        val badSha = indexJson(
            """{"dir":"lynk01","version":1,"size":10,"sha256":"${sha('a')}","files":[{"name":"a.webp","sha256":"zz","bytes":1}]}""",
        )
        assertNull(CarModelIndex.parse(badSha))
    }

    @Test
    fun `索引解析_重复目录只保留第一条`() {
        val duplicated = oneModel + "," + oneModel.replace("\"version\": 2", "\"version\": 9")
        val models = CarModelIndex.parse(indexJson(duplicated))!!.models
        assertEquals(1, models.size)
        assertEquals(2, models.single().version)
    }

    // ------------------------------------------------------------ 版本比较 / 地址拼接

    @Test
    fun `版本比较_只升级不降级`() {
        assertTrue(CarModelIndex.needsUpdate(1, null)) // 没装过
        assertTrue(CarModelIndex.needsUpdate(2, 1))
        assertFalse(CarModelIndex.needsUpdate(2, 2))
        assertFalse(CarModelIndex.needsUpdate(1, 2)) // 远端更旧：不回滚
    }

    @Test
    fun `url 拼接_相对 key 用域名根_绝对地址原样保留`() {
        assertEquals(
            "https://file.chuxi.bid/cardesk/car-models/lynk03-v4.zip",
            CarModelIndex.absoluteUrl("cardesk/car-models/lynk03-v4.zip"),
        )
        assertEquals("https://file.chuxi.bid/x/y.zip", CarModelIndex.absoluteUrl("/x/y.zip"))
        assertEquals("https://example.com/a.zip", CarModelIndex.absoluteUrl("https://example.com/a.zip"))
        assertTrue(CarModelIndex.absoluteUrl("lynk01.zip").startsWith(CarModelRemote.BASE_URL))
    }

    // ------------------------------------------------------------ versions.json / version.json

    @Test
    fun `版本表读写_按目录名排序且可往返`() {
        val text = CarModelIndex.writeVersions(mapOf("lynk05" to 2, "lynk01" to 1))
        // 顺序固定，同样的内容写出来字节一致（原子替换时不会因为顺序抖动画花）
        assertEquals("{\"lynk01\":1,\"lynk05\":2}\n", text)
        assertEquals(mapOf("lynk01" to 1, "lynk05" to 2), CarModelIndex.parseVersions(text))
    }

    @Test
    fun `版本表解析_坏值跳过不整体失败`() {
        val parsed = CarModelIndex.parseVersions(
            """{"lynk01":3,"lynk02":0,"lynk03":-1,"lynk04":"x","../evil":2,"a/b":1,"lynk05":"4"}""",
        )
        assertEquals(mapOf("lynk01" to 3, "lynk05" to 4), parsed)
        assertTrue(CarModelIndex.parseVersions("not json").isEmpty())
        assertTrue(CarModelIndex.parseVersions("[]").isEmpty())
    }

    @Test
    fun `目录版本戳解析_坏文件当作没装`() {
        assertEquals(5, CarModelIndex.parseDirVersion("""{"dir":"lynk01","version":5}"""))
        assertNull(CarModelIndex.parseDirVersion("""{"dir":"lynk01"}"""))
        assertNull(CarModelIndex.parseDirVersion("""{"version":0}"""))
        assertNull(CarModelIndex.parseDirVersion("garbage"))
    }

    @Test
    fun `车模包格式校验_只认 car3d 格式`() {
        assertEquals("cardesk.car3d/1", CarModelIndex.parseModelFormat("""{"format":"cardesk.car3d/1"}"""))
        assertEquals("", CarModelIndex.parseModelFormat("""{"views":{}}"""))
        assertEquals("", CarModelIndex.parseModelFormat("garbage"))
    }

    // ------------------------------------------------------------ zip 路径安全

    @Test
    fun `zip 条目名安全判定`() {
        assertTrue(CarModelIndex.isSafeEntryName("model.json"))
        assertTrue(CarModelIndex.isSafeEntryName("cardtop/dark/closed.webp"))
        assertTrue(CarModelIndex.isSafeEntryName("cardtop/")) // 目录项，安装时跳过

        assertFalse(CarModelIndex.isSafeEntryName(""))
        assertFalse(CarModelIndex.isSafeEntryName("/abs.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("\\abs.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("../evil.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("a/../../evil.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("a//b.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("a/./b.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("C:/x.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("a\\b.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("a\u0000b.webp"))
        assertFalse(CarModelIndex.isSafeEntryName("/"))
    }

    // ------------------------------------------------------------ 结果文案

    @Test
    fun `结果文案_已最新_更新_失败`() {
        assertEquals(
            "已安装 3 个车模，全部最新",
            CarModelRemote.buildMessage(3, emptyMap(), emptyList()),
        )
        assertEquals(
            "lynk05 更新到 v2",
            CarModelRemote.buildMessage(3, mapOf("lynk05" to 2), emptyList()),
        )
        assertEquals(
            "已更新 2 个车模：lynk01 v2、lynk05 v3",
            CarModelRemote.buildMessage(3, mapOf("lynk01" to 2, "lynk05" to 3), emptyList()),
        )
        assertEquals(
            "1 个车模未更新：lynk03",
            CarModelRemote.buildMessage(3, emptyMap(), listOf("lynk03")),
        )
    }
}
