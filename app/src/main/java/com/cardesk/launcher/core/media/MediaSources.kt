package com.cardesk.launcher.core.media

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 9 个固定媒体来源（： 的 9 行 +）。
 *
 * 文案与品牌色逐字 `android:text` / `android:tag`
 * 正是**用文案前缀**做包名映射（`startsWith("QQ")` / `startsWith("蓝牙")` …）
 * 所以这里保留「按文案前缀匹配」的规则，而不是按 id 匹配——两边行为才一致。
 *
 * 纯 Kotlin、不依赖 Android，可在 JVM 单测里覆盖「文案 → 包名 → 提示」的完整链路。
 */
object MediaSources {

    data class Source(val id: String, val label: String, val brandColor: Int)

    /** 菜单顺序 =XML 里的行顺序。 */
    val LIST: List<Source> = listOf(
        Source("qq", "QQ音乐", 0xFF31C27C.toInt()),
        Source("netease", "网易云音乐", 0xFFE6393F.toInt()),
        Source("kugou", "酷狗音乐", 0xFFFF6A3D.toInt()),
        Source("kuwo", "酷我音乐", 0xFFFFB020.toInt()),
        Source("soda", "汽水音乐", 0xFF00B8D9.toInt()),
        Source("local", "多媒体", 0xFFA855F7.toInt()),
        Source("generic", "通用音乐", 0xFFEC4899.toInt()),
        Source("bluetooth", "蓝牙音乐", 0xFF2F7BFF.toInt()),
        Source("usb", "USB 音乐", 0xFF8A94A6.toInt()),
    )

    /** 「通用音乐」：不锁定包、不启动 App，只跟随系统当前会话。 */
    const val GENERIC_ID = "generic"

    /** `multimediaPackage(false)`：多媒体来源的候选包（按序取第一个已安装的）。 */
    val MULTIMEDIA_CANDIDATES = listOf("com.tencent.wecarflow", "com.ecarx.multimedia")

    /** `multimediaPackage(true)`：蓝牙/USB 来源的候选包（多一个系统音乐）。 */
    val MEDIA_CANDIDATES = MULTIMEDIA_CANDIDATES + "com.android.music"

    /** 固定来源的候选包；酷我有 clone 变体，用 `installedPackage(clone, 原包)` 取第一个已安装的。 */
    private val FIXED_CANDIDATES: Map<String, List<String>> = mapOf(
        "qq" to listOf("com.tencent.qqmusiccar"),
        "netease" to listOf("com.netease.cloudmusic.iot"),
        "kugou" to listOf("com.kugou.android.auto"),
        "kuwo" to listOf("cn.kuwo.kwmusiccarclone", "cn.kuwo.kwmusiccar"),
        "soda" to listOf("com.luna.music.car"),
    )

    /** 每个来源 id 的首选包（会话追踪按包名反查来源时用）。 */
    val PRIMARY_PACKAGES: Map<String, String> = linkedMapOf(
        "qq" to "com.tencent.qqmusiccar",
        "netease" to "com.netease.cloudmusic.iot",
        "kugou" to "com.kugou.android.auto",
        "kuwo" to "cn.kuwo.kwmusiccar",
        "soda" to "com.luna.music.car",
    )

    /**
     * 「默认音乐应用」候选包。
     *
     * 顺序：**QQ 音乐车机版 → 爱趣听 → 酷我 → 酷我 clone → 网易云车机版**
     * 取第一个**装了且有启动入口**的。
     *
     * 注意这与上面的来源菜单候选顺序**不同**（酷我在这里 `kwmusiccar` 在前、
     * `kwmusiccarclone` 在后，两处都，别混用。
     */
    val DEFAULT_MUSIC_PACKAGES: List<String> = listOf(
        "com.tencent.qqmusiccar",
        "com.tencent.wecarflow",
        "cn.kuwo.kwmusiccar",
        "cn.kuwo.kwmusiccarclone",
        "com.netease.cloudmusic.iot",
    )

    /** 选择一个来源后的结果，供 UI 层决定「启动 / 提示 / 仅切换显示」。 */
    sealed interface Pick {
        /** 通用音乐：不启动任何 App，只切换显示。 */
        data object Generic : Pick

        /** 目标不可用：给出提示文案（三种 Toast）。 */
        data class Missing(val message: String) : Pick

        /** 目标可用：锁定该包的会话并启动它。 */
        data class Launch(val pkg: String) : Pick
    }

    fun byId(id: String): Source? = LIST.firstOrNull { it.id == id }

    /** 按文案找来源（统一以文案作为匹配键）。 */
    fun byLabel(label: String): Source? = LIST.firstOrNull { it.label == label }

    /**
     * `packageForSource(String label)`：返回该来源对应的包名候选（未安装则返回 null）。
     *
     * 注意对固定来源**不查安装状态**（直接返回包名）
     * 酷我/多媒体/蓝牙/USB 则取「第一个已安装的候选」。
     * 这里统一成「第一个已安装的候选」，缺失时的提示文案由 [pick] 按来源区分，
     * 与最终的用户可见行为一致。
     */
    fun packageCandidates(label: String): List<String> = when {
        label.startsWith("QQ") -> FIXED_CANDIDATES.getValue("qq")
        label.startsWith("网易") -> FIXED_CANDIDATES.getValue("netease")
        label.startsWith("酷狗") -> FIXED_CANDIDATES.getValue("kugou")
        label.startsWith("酷我") -> FIXED_CANDIDATES.getValue("kuwo")
        label.startsWith("汽水") -> FIXED_CANDIDATES.getValue("soda")
        label.startsWith("多媒体") -> MULTIMEDIA_CANDIDATES
        label.startsWith("蓝牙") || label.startsWith("USB") -> MEDIA_CANDIDATES
        else -> emptyList() // 通用音乐：不锁定包
    }

    /** 解析来源对应的包名；未安装返回 null。 */
    fun packageFor(label: String, isInstalled: (String) -> Boolean): String? =
        packageCandidates(label).firstOrNull { isInstalled(it) }

    /** 三种提示文案。 */
    fun missingMessage(source: Source): String = when {
        source.label.startsWith("蓝牙") -> "蓝牙未连接"
        source.label.startsWith("USB") -> "未检测到USB设备"
        else -> "未安装" + source.label
    }

    /**
     * 完整的选择结果，
     * 通用音乐只切换显示；其余来源若包已安装则锁定并启动，否则给提示。
     */
    fun pick(source: Source, isInstalled: (String) -> Boolean): Pick {
        if (source.label.startsWith("通用")) {
            Logx.d(LogTags.MEDIA, "来源解析：通用音乐", "sourceId" to source.id, "label" to source.label)
            return Pick.Generic
        }
        val pkg = packageFor(source.label, isInstalled)
        if (pkg == null) {
            // 排障场景：用户点了某个来源却提示「未安装」——这里能看到候选包都试了哪些
            Logx.d(
                LogTags.MEDIA, "来源解析：目标不可用",
                "sourceId" to source.id, "label" to source.label,
                "candidates" to packageCandidates(source.label).joinToString(","),
                "message" to missingMessage(source),
            )
            return Pick.Missing(missingMessage(source))
        }
        Logx.d(
            LogTags.MEDIA, "来源解析：可启动",
            "sourceId" to source.id, "label" to source.label, "pkg" to pkg,
        )
        return Pick.Launch(pkg)
    }

    /** 品牌色：来源标签左侧圆点的颜色。 */
    fun brandColor(label: String): Int = byLabel(label)?.brandColor ?: 0xFFEC4899.toInt()

    /**
     * 包名 → 来源文案。
     *
     * 注意这里返回的是**徽标文案**：「多媒体」「蓝牙音乐」「USB音乐」
     * （菜单里的那一项写作「USB 音乐」，两者在里本来就不一致）。
     */
    fun labelForPackage(pkg: String?): String {
        val label = if (pkg.isNullOrEmpty()) {
            GENERIC_LABEL
        } else {
            val lower = pkg.lowercase()
            when {
                lower.contains("qqmusic") -> "QQ音乐"
                lower.contains("netease") || lower.contains("cloudmusic") -> "网易云音乐"
                lower.contains("kugou") -> "酷狗音乐"
                lower.contains("kuwo") -> "酷我音乐"
                lower.contains("luna") || lower.contains("qishui") || lower.contains("soda") -> "汽水音乐"
                lower.contains("wecarflow") || lower.contains("ecarx.multimedia") -> "多媒体"
                lower.contains("bluetooth") || lower.contains("btmusic") -> "蓝牙音乐"
                lower.contains("usb") || lower.contains("localmusic") -> "USB音乐"
                else -> GENERIC_LABEL
            }
        }
        // 排障场景：音乐页左上角来源文案不对时，这里能看到包名被映射成了什么
        Logx.d(LogTags.MEDIA, "来源文案映射", "pkg" to (pkg ?: ""), "label" to label)
        return label
    }

    /** 包名 → 来源 id（会话追踪反查用；映射规则与 [labelForPackage] 一致）。 */
    fun idForPackage(pkg: String?): String {
        val id = if (pkg.isNullOrEmpty()) {
            ""
        } else {
            val lower = pkg.lowercase()
            when {
                lower.contains("qqmusic") -> "qq"
                lower.contains("netease") || lower.contains("cloudmusic") -> "netease"
                lower.contains("kugou") -> "kugou"
                lower.contains("kuwo") -> "kuwo"
                lower.contains("luna") || lower.contains("qishui") || lower.contains("soda") -> "soda"
                lower.contains("wecarflow") || lower.contains("ecarx.multimedia") -> "local"
                lower.contains("bluetooth") || lower.contains("btmusic") -> "bluetooth"
                lower.contains("usb") || lower.contains("localmusic") -> "usb"
                else -> GENERIC_ID
            }
        }
        Logx.d(LogTags.MEDIA, "来源 id 映射", "pkg" to (pkg ?: ""), "id" to id)
        return id
    }

    const val GENERIC_LABEL = "通用音乐"
}
