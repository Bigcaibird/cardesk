import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// ======================================================================
// 版本号（第五十七轮）
//
// `version.properties`（仓库根目录）是版本号的**唯一来源**：
//   - 每次出包由 `tools/release.sh` 自动 +1；
//   - 也可以手动跑 `gradle bumpVersion`；
//   - 构建时 `app/build.gradle.kts` 直接读这里的值，不需要再传 -P 参数。
//
// 规则：versionName = "1.0." + (versionCode - 3096)
//   4123 → 1.0.1027   4124 → 1.0.1028
// ======================================================================

val versionFile = file("version.properties")

/** 读出版本号（文件不存在时给个兜底值，不让构建直接挂掉）。 */
fun readVersionProps(): Properties = Properties().apply {
    if (versionFile.exists()) versionFile.inputStream().use { load(it) }
}

/** 打印当前版本号。 */
tasks.register("printVersion") {
    val props = readVersionProps()
    doLast {
        println("versionCode=${props.getProperty("versionCode")} versionName=${props.getProperty("versionName")}")
    }
}

/**
 * 版本号 +1 并写回 `version.properties`。
 *
 * 出包流程（`tools/release.sh`）会先跑这个任务，
 * 所以**以后每次出版本，版本号都会自动往上走一格**，不用手改。
 */
tasks.register("bumpVersion") {
    val props = readVersionProps()
    val currentCode = props.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 4123
    val nextCode = currentCode + 1
    val nextName = "1.0.${nextCode - 3096}"
    doLast {
        versionFile.writeText(
            buildString {
                append("# CarDesk 版本号（**唯一来源**）\n")
                append("#\n")
                append("# 第五十七轮起：每次出包由 `tools/release.sh` **自动 +1**，\n")
                append("# 也可以手动执行 `gradle bumpVersion`。\n")
                append("#\n")
                append("# 规则（历轮验证过）：versionName = \"1.0.\" + (versionCode - 3096)\n")
                append("#   4123 → 1.0.1027\n")
                append("#   $nextCode → $nextName\n")
                append("#\n")
                append("versionCode=$nextCode\n")
                append("versionName=$nextName\n")
            },
        )
        println("版本号已升到：versionCode=$nextCode versionName=$nextName")
    }
}
