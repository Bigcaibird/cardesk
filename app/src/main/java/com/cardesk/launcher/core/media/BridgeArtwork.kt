package com.cardesk.launcher.core.media

import android.media.MediaMetadata

/**
 * 原车仪表的**封面（artwork）**取值规则（纯逻辑，无 Android 运行时依赖，可 JVM 单测）。
 *
 * ## 为什么要有这个文件（第134轮）
 *
 * 症状：接管方控之后，**仪表只有歌名和歌手，没有图片**；
 * 车机自带的爱趣听（也是「在线音乐源」）却**有图**。
 *
 * 根因（反编译原车 `carmedia/MultiMedia.apk` 拿到的 SDK 实锤）：
 *
 * - `com.ecarx.sdk.mediacenter.IMediaInfo#getArtwork()` → 返回 **`android.net.Uri`**；
 * - `com.ecarx.sdk.mediacenter.IMusicPlaybackInfo#getArtwork()` / `#getNextArtwork()` /
 *   `#getPreviousArtwork()` → 同样是 **`Uri`**；
 * - 仪表侧 `com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction.IMedia#getArtwork()`
 *   → 也是 **`Uri`**。
 *
 * 而我们的 [OfficialMediaBridge] 的动态代理**只实现了**
 * `getTitle` / `getArtist` / `getAlbum` / `getDuration` …，
 * **`getArtwork()` 没实现** → 落到 `defaultValue()` → 返回 **null** →
 * 原车推给仪表的封面是空的 → **歌名歌手有、图没有**。
 *
 * 原车自己是怎么填的（`MediaCenterHelper.D(MediaMetadataCompat, String)`，
 * 反编译自同一个 apk）：从会话的 MediaSession metadata 里按
 * **DISPLAY_ICON_URI → ART_URI → ALBUM_ART_URI** 的顺序取，
 * 其中 `ART_URI` 若是**本地存在的文件**就转成 `Uri.fromFile(file)`，
 * 三个都没有时给 **`Uri.parse("")`**（空 Uri，不是 null）。
 *
 * 本文件把这套口径抽成**可单测**的纯逻辑：[OfficialMediaBridge] 只负责搬砖。
 */
object BridgeArtwork {

    /**
     * 封面 URI 的取值优先级（**与原车完全一致**）。
     *
     * 顺序不能改：原车先看 DISPLAY_ICON_URI（播放器给的小图标），
     * 再看 ART_URI（一般是大图），最后才是 ALBUM_ART_URI。
     */
    val URI_KEY_PRIORITY: List<String> = listOf(
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
    )

    /** 原车在没有封面时给的是 `Uri.parse("")` —— 空 Uri，不是 null。 */
    const val EMPTY_ARTWORK: String = ""

    /**
     * 这个字符串像不像一个「能直接喂给仪表的图片 Uri」。
     *
     * 只认四种 scheme（**与对标 App 用 Glide 加载时的实际用法一致**：
     * `content://`（MediaStore albumart）、`file://`（本地文件）、
     * `http(s)://`（网络图）都能加载）：
     *
     * - `content://` —— 最常见（`content://media/external/audio/albumart/123`）；
     * - `file://` —— 原车 `ART_URI` 指本地文件时自己转出来的；
     * - `http://` / `https://` —— 播放器给的网络图；
     * - 其余一律不认（`android.resource://`、乱串、`""`…）。
     *
     * 为什么把 `android.resource://` 排除掉：那是**播放器自己的**资源，
     * 仪表进程去读会 `FileNotFoundException`（跨应用私有资源），
     * 给了反而不如不给 —— 不给只是没图，给了可能让原车侧抛异常。
     */
    fun isImageUri(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return false
        return s.startsWith("content://") ||
            s.startsWith("file://") ||
            s.startsWith("http://") ||
            s.startsWith("https://")
    }

    /**
     * 这是不是**本地绝对路径**（不是 Uri）。
     *
     * 原车的 `ART_URI` 特例：值可能是一条**裸路径**（`/storage/emulated/0/.../cover.jpg`），
     * 原车判断文件存在就转 `Uri.fromFile(file)`，否则原样 `Uri.parse`。
     * 我们照抄这个口径（见 [resolveUri]）。
     */
    fun isLocalPath(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        return s.isNotEmpty() && s.startsWith("/")
    }

    /**
     * 按优先级挑一个封面 URI（纯逻辑）。
     *
     * @param values 各个 metadata key 的值（key 用 [URI_KEY_PRIORITY] 里的原车 key）
     * @return 挑中的 URI 字符串；都没有则 [EMPTY_ARTWORK]（空串）
     */
    fun pickUri(values: Map<String, String?>): String {
        for (key in URI_KEY_PRIORITY) {
            val v = values[key]?.trim().orEmpty()
            if (v.isEmpty()) continue
            // 本地裸路径不算 Uri，交给 [resolveUri] 处理（要判断文件是否存在）
            if (isLocalPath(v)) return v
            if (isImageUri(v)) return v
        }
        return EMPTY_ARTWORK
    }

    /**
     * 把候选值解析成最终给仪表的 Uri 字符串（纯逻辑；「文件是否存在」由调用方判断）。
     *
     * @param candidate [pickUri] 的结果
     * @param localFileExists 候选是本地路径时，该文件是否存在
     *
     * 口径与原车 `MediaCenterHelper.D()` 一致：
     * - 本地路径且文件存在 → `file://` + 路径（原车用 `Uri.fromFile`）；
     * - 其余能认的 → 原样；
     * - 认不出来 → 空串（= 原车的 `Uri.parse("")`）。
     */
    fun resolveUri(candidate: String?, localFileExists: Boolean): String {
        val s = candidate?.trim().orEmpty()
        if (s.isEmpty()) return EMPTY_ARTWORK
        if (isLocalPath(s)) {
            return if (localFileExists) "file://$s" else EMPTY_ARTWORK
        }
        return if (isImageUri(s)) s else EMPTY_ARTWORK
    }

    /**
     * 封面**变了**没有（决定要不要重推给原车）。
     *
     * 为什么要判：封面是**异步**到手的 —— 会话先给歌名，几百毫秒后封面才下载完。
     * 如果指纹里不含封面，那张图就永远推不出去（歌名不变 → `trackChanged` 为 false）。
     */
    fun changed(oldValue: String?, newValue: String?): Boolean =
        (oldValue ?: EMPTY_ARTWORK) != (newValue ?: EMPTY_ARTWORK)

    /**
     * 原车 SDK 里**取封面**的那几个 getter（纯逻辑，可单测）。
     *
     * 实测（反编译 `carmedia/MultiMedia.apk`）：
     * - `IMediaInfo#getArtwork()` → `Uri`（**仪表要的就是它**）；
     * - `IMusicPlaybackInfo#getArtwork()` → `Uri`；
     * - `IMusicPlaybackInfo#getNextArtwork()` / `#getPreviousArtwork()` → `Uri`。
     */
    enum class ArtworkGetter {
        /** 当前这一首的封面。 */
        CURRENT,

        /** 下一首的封面（我们只有一首 → 给 null）。 */
        NEXT,

        /** 上一首的封面（我们只有一首 → 给 null）。 */
        PREVIOUS,

        /** 不是取封面的 getter。 */
        NONE,
    }

    /**
     * 这个 getter 是不是取封面的（纯逻辑，可单测）。
     *
     * ## 为什么要单列一个函数
     *
     * 根因就是「**没实现 `getArtwork`**」——
     * 未实现的 getter 会落到 `defaultValue()` → 返回 **null** →
     * 仪表上只有歌名和歌手、没有图片。
     * 把它单独抽出来钉住，就是钉住「这个 getter 必须有人应答」。
     */
    fun artworkGetter(methodName: String): ArtworkGetter = when (methodName) {
        "getArtwork" -> ArtworkGetter.CURRENT
        "getNextArtwork" -> ArtworkGetter.NEXT
        "getPreviousArtwork" -> ArtworkGetter.PREVIOUS
        else -> ArtworkGetter.NONE
    }

    /**
     * 这首歌的「封面指纹」（用于判断手上的封面是不是**这一首**的）。
     *
     * 封面是异步来的，迟到的旧封面不能盖掉新歌的封面 ——
     * 用「来源 + 歌名 + 歌手」做 key，对不上就不认。
     */
    fun songKey(source: String?, title: String?, artist: String?): String {
        val s = source?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        if (s.isEmpty() && t.isEmpty() && a.isEmpty()) return ""
        return "$s|$t|$a"
    }
}
