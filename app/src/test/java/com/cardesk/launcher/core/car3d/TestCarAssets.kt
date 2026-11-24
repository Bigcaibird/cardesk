package com.cardesk.launcher.core.car3d

import android.content.Context
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 单元测试用的车模夹具。
 *
 * 第三百一十二轮起 APK 不再内置车模图（`app/src/main/assets/car3d/` 已移除），但
 * 解码、绘制、截图类用例需要真实资产。仓库根目录的 `assets-src/car3d/<dir>/` 是
 * 打包发布资源包用的**源目录**，这里在用例启动时把它拷进 [CarModelRemote] 的缓存
 * 目录并补上版本戳 —— 于是被测代码走的是**生产路径**（缓存优先），既不依赖 APK
 * 资产，也不需要在测试目录里再存一份副本。
 *
 * 用法：在需要车模图的测试类里加一行
 * `@get:Rule val carAssets = TestCarAssets.Rule()`。
 */
object TestCarAssets {

    /** 与发布产物一致的三个车型目录。 */
    val ALL = listOf("lynk01", "lynk03", "lynk05")

    /** 夹具的版本戳（版本号本身对解码没有意义，只要与 `versions.json` 一致）。 */
    private const val FIXTURE_VERSION = 1

    /** 资源包源目录（相对仓库根，`cardesk/` 下的 `assets-src/car3d`）。 */
    private const val SOURCE = "assets-src/car3d"

    /**
     * JUnit Rule：在本用例的**所有** `@Before` 之前装好夹具。
     *
     * 装夹具必须早于测试体里的 `Car3dAssets.reset()` / 首次解码，否则会把已经
     * 缓存的「缺图/损坏」状态带进去。
     */
    class Rule(private val dirs: List<String> = ALL) : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    install(RuntimeEnvironment.getApplication(), dirs)
                    base.evaluate()
                }
            }
    }

    /** 把 [dirs] 对应的车模目录拷进缓存目录，并写 `version.json` / `versions.json`。 */
    fun install(context: Context, dirs: List<String> = ALL) {
        val src = sourceRoot() ?: error("找不到 $SOURCE（当前工作目录=${File(".").absolutePath}）")
        val root = CarModelRemote.cacheRoot(context)
        if (!root.isDirectory && !root.mkdirs()) error("无法创建车模缓存目录：$root")
        val versions = LinkedHashMap<String, Int>()
        for (dir in dirs) {
            val from = File(src, dir)
            if (!from.isDirectory) continue
            val to = File(root, dir)
            to.deleteRecursively()
            from.copyRecursively(to, overwrite = true)
            // 与安装流程写出的目录版本戳同构（CarModelIndex.parseDirVersion 要求 dir + version）
            File(to, "version.json").writeText("{\"dir\":\"$dir\",\"version\":$FIXTURE_VERSION}\n")
            versions[dir] = FIXTURE_VERSION
        }
        File(root, "versions.json").writeText(CarModelIndex.writeVersions(versions))
        // 进程内还缓存着「一个都没装」的旧镜像，必须丢掉
        CarModelRemote.invalidateCache()
        // Car3dAssets 是进程级单例：别的用例可能已经把它标成「缺图/损坏」。夹具装好后必须
        // 抹掉这些记忆，否则本用例读到的还是别人留下的 broken 状态（Robolectric 同一个
        // JVM 里跑完所有测试类，单例状态会跨类残留）。
        Car3dAssets.reset()
    }

    /** 从测试工作目录往上找工作区根（Gradle 单测的 cwd 通常是模块目录 `app/`）。 */
    private fun sourceRoot(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val f = dir ?: return null
            val candidate = File(f, SOURCE)
            if (candidate.isDirectory) return candidate
            dir = f.parentFile
        }
        return null
    }
}
