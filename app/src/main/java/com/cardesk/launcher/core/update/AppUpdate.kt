package com.cardesk.launcher.core.update

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cardesk.launcher.core.SystemOps
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.net.FileDownload
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「检测 R2 上的最新版本清单 → 有新版就下载并安装」（第六十九轮起，第八十三轮换源）。
 *
 * ## 更新源：Cloudflare R2
 *
 * 原来是让用户手填一个 APK 地址（`prefs.updateUrl`）—— 填错就更新不了，
 * 而且地址一变就得重填；接着改成检测**本项目自己的 GitHub Release**。
 *
 * 第八十三轮改成 **Cloudflare R2 自定义域**：车机在国内访问 `api.github.com`
 * 又慢又不稳，而 R2 自定义域走的是 Cloudflare 自己的 CDN，稳得多。
 *
 * ```
 * GET https://file.chuxi.bid/cardesk/latest.json
 * ```
 *
 * R2 只是对象存储、没有「release」这种东西，所以清单格式是我们自己定的
 * （见 [R2Release]）。**下载走同一个域名的匿名访问**——
 * App 里不存任何密钥（仓库是公开的，密钥一旦进包就等于公开）。
 *
 * ## 检测时机：**手动触发**
 *
 * 本类**不注册任何定时器、也不在启动时自动跑**。
 * 只有用户点了「检查更新」才会联网查一次 —— 车机常年挂在后台，
 * 自动检查既费流量又会在用户没想更新的时候弹窗。
 *
 * ## 线程
 *
 * [check] / [download] 都是**阻塞**的，必须在后台线程调用（对话框里就是这么用的）。
 */
object AppUpdate {

    /** 更新源的域名（自定义域，走 Cloudflare CDN）。 */
    const val R2_HOST = "file.chuxi.bid"

    /** 更新清单的路径（R2 对象 key）。 */
    const val MANIFEST_PATH = "cardesk/latest.json"

    /** 正式版更新清单的完整地址（App 每次「检查更新」就拉这一个文件）。 */
    const val MANIFEST_URL = "https://$R2_HOST/$MANIFEST_PATH"

    // ------------------------------------------------------------------ 更新通道

    /**
     * 更新通道：**正式版**（`stable`）。
     *
     * 第三百零九轮起更新分两条通道，用户可以在「设置 → 在线更新 → 更新渠道」里选：
     * - 正式版：只发**验证过的稳定包**（`cardesk/latest.json`）；
     * - 开发版：`-preview` 包，功能更新更早、可能不稳（`cardesk/preview/latest.json`）。
     *
     * 两条通道各自一份清单、各自一套安装包，互不干扰 ——
     * 选正式版的用户不会被开发版弹窗打扰，反之亦然。
     */
    const val CHANNEL_STABLE = "stable"

    /** 更新通道：**开发版**（`preview`）。 */
    const val CHANNEL_PREVIEW = "preview"

    /** 开发版更新清单的路径（R2 对象 key）。 */
    const val MANIFEST_PATH_PREVIEW = "cardesk/preview/latest.json"

    /** 把任意输入归一成已知通道（空/未知一律当正式版，别让脏数据把用户带到开发版）。 */
    fun normalizeChannel(raw: String?): String =
        if (raw?.trim()?.equals(CHANNEL_PREVIEW, ignoreCase = true) == true) {
            CHANNEL_PREVIEW
        } else {
            CHANNEL_STABLE
        }

    /** 通道的中文名（设置页 / 更新弹窗展示用）。 */
    fun channelLabel(raw: String?): String =
        if (normalizeChannel(raw) == CHANNEL_PREVIEW) "开发版" else "正式版"

    /** 通道对应的清单路径（R2 对象 key）。 */
    fun manifestPath(raw: String?): String =
        if (normalizeChannel(raw) == CHANNEL_PREVIEW) MANIFEST_PATH_PREVIEW else MANIFEST_PATH

    /** 通道对应的清单完整地址。 */
    fun manifestUrl(raw: String?): String = "https://$R2_HOST/${manifestPath(raw)}"

    /** 域名根（清单里写相对地址时按它拼开）。 */
    const val R2_ROOT_URL = "https://$R2_HOST/"

    /** 公共目录下的子目录名（诊断包、下载的更新包都放这儿）。 */
    const val PUBLIC_DIR_NAME = "CarDesk"

    // ------------------------------------------------------------------ 检查结果

    /** 检查结果。 */
    sealed interface CheckResult {
        /** 已是最新。 */
        data class UpToDate(val current: String, val latestVersion: String) : CheckResult

        /**
         * 可以更新。
         *
         * @param branchSwitch true = 这次不是「比版本号更大」，而是**跨通道**换包
         *   （装着开发版、选的正式版，或反过来）。两条通道各自递增版本号，
         *   互相之间的大小关系没有意义，所以这种情形直接给可更新，不比较。
         */
        data class Available(
            val release: R2Release,
            val current: String,
            val branchSwitch: Boolean = false,
        ) : CheckResult

        /** 没查到（网络不通 / 清单取不到 / 返回体看不懂）。 */
        data class Failed(val reason: String) : CheckResult
    }

    /** 安装结果。 */
    sealed interface InstallResult {
        /** 已提交静默安装（PackageInstaller）。 */
        object Silent : InstallResult

        /** 静默不行，已转交系统安装器。 */
        data class Installer(val location: String) : InstallResult

        /** 没装成。 */
        data class Fail(val reason: String) : InstallResult
    }

    // ------------------------------------------------------------------ 版本

    /** 当前装着的 `versionName`（`1.0.1038-system`）。读不到写 `?`。 */
    fun currentVersionName(context: Context): String = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 当前装着的 `versionCode`。 */
    fun currentVersionCode(context: Context): Long = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(0L)

    /**
     * 从一个包的 `versionName` 判断它属于哪条通道（纯函数，便于单测）。
     *
     * 约定：开发版包的版本号带 `-preview` 后缀（`1.0.1230-preview`），
     * 正式版是 `1.0.1219-system` 这种（没有 `-preview`）。后缀由打包脚本写死，
     * 所以判定**只看后缀**，不猜别的。
     *
     * @param name `versionName`；读不到时传 `?` 之类也没关系 —— 一律当正式版。
     */
    fun channelOfVersionName(name: String): String =
        if (name.contains("-preview", ignoreCase = true)) CHANNEL_PREVIEW else CHANNEL_STABLE

    /** 当前装着的包属于哪条通道。 */
    fun installedChannel(context: Context): String = channelOfVersionName(currentVersionName(context))

    /**
     * 选中的通道和已装包的通道是不是**跨分支**（纯函数）。
     *
     * 跨分支时不能拿版本号比大小：开发版可能已经到 4326，正式版还停在 4315，
     * 「4326 > 4315」只说明开发版发得早，不说明正式版是旧的。
     */
    fun isBranchSwitch(targetChannel: String, installed: String): Boolean =
        normalizeChannel(targetChannel) != normalizeChannel(installed)

    /**
     * 这个远端版本要不要摆到用户面前（纯函数，单测直接覆盖决策本身）。
     *
     * - 跨分支：**直接可更新**（用户明确切换了更新源，要的就是「换上那一支的包」）；
     * - 同分支：按 [isNewer] 的常规规则比版本号。
     */
    fun shouldOffer(
        remote: R2Release,
        localName: String,
        localCode: Long,
        branchSwitch: Boolean,
    ): Boolean = branchSwitch || isNewer(remote, localName, localCode)

    /** 版本展示串：`1.0.1038（4134）`。 */
    fun versionLabel(context: Context): String {
        val name = currentVersionName(context)
        val code = currentVersionCode(context)
        return if (code > 0) "$name（$code）" else name
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 拉 R2 上的最新版本清单（阻塞）。
     *
     * @return 解析好的清单；**网络失败抛 IOException**，清单结构不对返回 null。
     */
    fun fetchRelease(
        context: Context,
        channel: String = CHANNEL_STABLE,
        timeoutMs: Int = 10_000,
    ): R2Release? {
        val url = manifestUrl(channel)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CarDesk/${currentVersionName(context)}")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return R2Release.parse(text, R2_ROOT_URL)
    }

    /**
     * 检查有没有新版本（阻塞）。
     *
     * 清单里只放**一个**最新版本，所以这里不需要「挑最新」那一步；
     * 跟本地比的时候优先比 `versionCode`，清单没给才退回比版本号字符串。
     *
     * @param allowBranchSwitch 是否把「跨通道」也算作可更新。用户手动检查更新时为 true：
     *   刚在设置里把更新源从开发版切到正式版，此时 4326-preview → 4315-system 的
     *   versionCode 是**变小**的，按常规规则会判成「已是最新」，用户就切不过去。
     *   启动自动检测保持 false（否则每次开机都提示「可切换到另一条通道」）。
     */
    fun check(
        context: Context,
        channel: String = CHANNEL_STABLE,
        allowBranchSwitch: Boolean = false,
    ): CheckResult {
        val ch = normalizeChannel(channel)
        val url = manifestUrl(ch)
        val label = channelLabel(ch)
        val current = currentVersionName(context)
        val currentCode = currentVersionCode(context)
        val installed = installedChannel(context)
        val branchSwitch = allowBranchSwitch && isBranchSwitch(ch, installed)
        return try {
            val release = fetchRelease(context, ch)
            if (release == null) {
                Logx.w(LogTags.SETTING, "检查更新：清单读不懂", "url" to url, "channel" to ch)
                return CheckResult.Failed("更新清单读不懂（格式不对）")
            }
            if (shouldOffer(release, current, currentCode, branchSwitch)) {
                Logx.i(
                    LogTags.SETTING,
                    if (branchSwitch) "检查更新：跨通道，可直接切换" else "检查更新：有新版本",
                    "channel" to label, "installed" to channelLabel(installed),
                    "current" to current, "latest" to release.version,
                    "latestCode" to release.versionCode,
                    "apk" to (release.apk?.name ?: ""),
                )
                CheckResult.Available(release, current, branchSwitch)
            } else {
                Logx.i(
                    LogTags.SETTING, "检查更新：已是最新",
                    "channel" to label, "installed" to channelLabel(installed),
                    "current" to current, "latest" to release.version,
                    "latestCode" to release.versionCode,
                )
                CheckResult.UpToDate(current, release.version)
            }
        } catch (t: Throwable) {
            Logx.w(LogTags.SETTING, "检查更新失败", t, "url" to url, "channel" to ch)
            CheckResult.Failed(t.message ?: "网络异常")
        }
    }

    /**
     * 远端是否比本地新。
     *
     * 优先比 `versionCode`：它是单调递增的整数，不会踩「1.0.9 比 1.0.10 新」
     * 这种字符串比较的坑。清单里没给 `versionCode`（或本地读不到）时，
     * 退回比版本号字符串，兼容手写的旧清单。
     */
    private fun isNewer(remote: R2Release, localName: String, localCode: Long): Boolean {
        if (remote.versionCode > 0 && localCode > 0) return remote.versionCode > localCode
        return ReleaseVersion.isNewer(remote.version, localName)
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下载 APK（阻塞）。
     *
     * 下载完做两道校验：长度（清单给了大小才查）和 SHA-256（清单给了摘要才查）。
     * 任一对不上就删掉半成品、当作下载失败 —— 车机装一个残缺的 APK 比不更新更糟。
     *
     * 第八十四轮：真正的下载 + 校验抽到了 [FileDownload]，
     * 「云端应用库」用的是同一套逻辑（公网下载被截断不会报错，只看 HTTP 200 不够）。
     *
     * @param onProgress 0..100 的进度（主线程回调）。
     * @return 下载好的文件；失败返回 null。
     */
    fun download(context: Context, release: R2Release, onProgress: (Int) -> Unit): File? {
        val apk = release.apk
        if (apk == null || apk.url.isBlank()) {
            Logx.w(LogTags.SETTING, "更新下载失败：清单里没有安装包", "version" to release.version)
            return null
        }
        val file = File(context.cacheDir, "cardesk-update-${release.version}.apk")
        val result = FileDownload.download(
            url = apk.url,
            target = file,
            expectSize = apk.size,
            expectSha256 = apk.sha256,
            onProgress = onProgress,
        )
        return when (result) {
            is FileDownload.Result.Ok -> {
                Logx.i(
                    LogTags.SETTING, "更新下载完成",
                    "file" to file.name, "bytes" to result.bytes,
                    "elapsedMs" to result.elapsedMs,
                )
                file
            }
            is FileDownload.Result.Failed -> {
                Logx.w(LogTags.SETTING, "更新下载失败", "reason" to result.reason, "url" to apk.url)
                null
            }
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 安装下载好的 APK。
     *
     * 先走**静默安装**（system 变体有 `INSTALL_PACKAGES`，装完直接生效）；
     * 静默不行就退到**系统安装器**（普通安装包唯一能走的路）。
     *
     * @param allowDowngrade 允许装版本号更小的包。只在**跨更新通道切换**时为 true：
     *   两条通道各自递增版本号，切到另一条时目标包的 versionCode 可能更小，
     *   系统默认会以 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒装。
     */
    fun install(context: Context, apk: File, allowDowngrade: Boolean = false): InstallResult {
        if (SystemOps.installApk(context, apk, allowDowngrade)) return InstallResult.Silent
        // —— 回退：把 APK 放进公共目录，再叫系统安装器 ——
        val published = publishToPublic(context, apk)
        return when (published) {
            null -> InstallResult.Fail("静默安装失败，且无法写入公共目录")
            else -> {
                val opened = openInstaller(context, published)
                if (opened) {
                    InstallResult.Installer(published.location)
                } else {
                    InstallResult.Fail("已下载到 ${published.location}，但拉不起系统安装器，请手动安装")
                }
            }
        }
    }

    /** 已发布到公共目录的文件。 */
    private data class Published(val uri: android.net.Uri?, val location: String)

    /**
     * 把 APK 放进 `Download/CarDesk/`。
     *
     * - Android 10+：走 `MediaStore.Downloads`，拿得到 `content://` Uri；
     * - Android 9 及以下：直接写文件（需要 `WRITE_EXTERNAL_STORAGE`）。
     */
    private fun publishToPublic(context: Context, apk: File): Published? {
        val name = apk.name
        return if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Published(uri, "Download/$PUBLIC_DIR_NAME/$name")
            } catch (t: Throwable) {
                Logx.w(LogTags.SETTING, "更新包写入公共目录失败", t)
                null
            }
        } else {
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_DIR_NAME,
                )
                if (!dir.exists() && !dir.mkdirs()) return null
                val out = File(dir, name)
                apk.inputStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                Published(null, out.absolutePath)
            } catch (t: Throwable) {
                Logx.w(LogTags.SETTING, "更新包写入公共目录失败", t)
                null
            }
        }
    }

    /** 叫起系统安装器。 */
    private fun openInstaller(context: Context, published: Published): Boolean {
        val uri = published.uri ?: android.net.Uri.fromFile(File(published.location))
        return runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Logx.w(LogTags.SETTING, "拉起系统安装器失败", it, "location" to published.location)
            false
        }
    }

    // ------------------------------------------------------------------ 杂项
}
