package com.cardesk.launcher.core.dock

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八十七轮：覆盖包是「入库的二进制资产」，构建脚本（`tools/build_insets.py`）
 * 生成它、Gradle 只是把它打进 APK；这里用 JVM 直接读文件，守住三件最容易
 * 悄悄坏掉的事（它们都会让真车上的预留区完全失效，但编译/安装都不报错）：
 *
 * 1. 资产没入库 / 被 `*.apk` 的 gitignore 漏掉 / 构建时忘了重新生成；
 * 2. 生成的 APK 其实没有签名（或没有 v1 块 —— 部分 ROM 只认 JAR 签名）；
 * 3. 不小心开了代码（`hasCode=true`）或丢了 AndroidManifest，装不上/装上了不生效。
 *
 * 单测工作目录是 `app/`（Gradle 模块目录），所以路径是 `src/main/assets/...`。
 */
class NavInsetsAssetTest {

    private val assetName = "cardesk-nav-insets.apk"

    private fun assetFile(): File {
        val candidates = listOf(
            File("src/main/assets/$assetName"),
            File("app/src/main/assets/$assetName"),
        )
        return candidates.firstOrNull { it.isFile } ?: candidates.first()
    }

    @Test
    fun `覆盖包资产存在且非空`() {
        val apk = assetFile()
        assertTrue("覆盖包不存在：${apk.absolutePath}（跑 tools/build_insets.py 生成）", apk.isFile)
        assertTrue("覆盖包为空：${apk.absolutePath}", apk.length() > 0L)
    }

    @Test
    fun `覆盖包是 zip 且含 AndroidManifest 与资源表`() {
        ZipFile(assetFile()).use { zip ->
            assertTrue("覆盖包缺少 AndroidManifest.xml", zip.getEntry("AndroidManifest.xml") != null)
            assertTrue("覆盖包缺少 resources.arsc", zip.getEntry("resources.arsc") != null)
        }
    }

    @Test
    fun `覆盖包含 v1 签名块`() {
        ZipFile(assetFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val manifest = names.filter { it == "META-INF/MANIFEST.MF" }
            val signatures = names.filter { it.startsWith("META-INF/") && it.endsWith(".SF") }
            val blocks = names.filter {
                it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC"))
            }
            assertTrue("缺少 META-INF/MANIFEST.MF：$names", manifest.isNotEmpty())
            assertTrue("缺少 META-INF/*.SF：$names", signatures.isNotEmpty())
            assertTrue("缺少 META-INF/*.RSA/DSA/EC 签名块：$names", blocks.isNotEmpty())
        }
    }

    @Test
    fun `覆盖包不含代码（hasCode=false）`() {
        ZipFile(assetFile()).use { zip ->
            assertFalse("覆盖包不应含 classes.dex", zip.getEntry("classes.dex") != null)
        }
    }
}
