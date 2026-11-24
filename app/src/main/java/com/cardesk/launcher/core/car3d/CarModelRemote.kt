package com.cardesk.launcher.core.car3d

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.JsonLite
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * 远端车模包：**优先用本地缓存，找不到再回退内置 assets**。
 *
 * ## 为什么要有这一层
 *
 * 内置车模（`assets/car3d/lynk01|lynk03|lynk05`）每个 360K~430K，三份一起进包会
 * 一直占着安装包体积；而车模图片是**可以按车型单独更新**的内容资产 ——
 * 烘焙侧改了图、加个新车型，不该逼用户重装整包。所以：
 *
 * - 车模包放到公开对象存储（R2 自定义域，匿名 HTTPS 下载，**App 内不存任何密钥**）；
 * - App 启动时不联网（[Car3dAssets] 第一次真要画时才读文件），已下载的包放
 *   `filesDir/car3d-models/<dir>/`，读取路径优先走它，没有/损坏就回退 assets；
 * - 联网只在用户/设置页显式触发（[check] / [refresh]），不在桌面启动时偷偷拉。
 *
 * ## 缓存目录结构
 *
 * ```
 * filesDir/car3d-models/
 *   versions.json                # {"lynk01":2,"lynk05":1} —— 已安装版本（原子替换）
 *   lynk01/
 *     version.json               # 本目录自己的版本戳（防止崩溃后拿到半包）
 *     model.json
 *     cardtop/dark/closed.webp ...
 *   .dl/lynk05-v2.zip.part       # 下载中的临时文件（装完即删）
 *   lynk05.tmp/ 、lynk05.old/    # 解压/替换过渡目录（正常路径下都会清掉）
 * ```
 *
 * `version.json` 与 `versions.json` 两级版本戳缺一不可：替换目录和写版本表是两次
 * 磁盘操作，中间断电/被杀会留下「目录是新的、版本表还是旧的」的半包状态。
 * [openStream] 只认**两级版本一致**的目录，其余一律回退 assets —— 旧包坏了也
 * 不至于把桌面画成空白。
 *
 * ## 安装流程（[install]）
 *
 * 1. 下载到 `.dl/<dir>-v<n>.zip.part`，边下边算 SHA-256，大小/摘要不符直接删；
 * 2. 解压到 `<dir>.tmp/`，逐条目校验（拒绝绝对路径/`..`、跳过目录项与 `__MACOSX`、
 *    单条目 ≤ [MAX_ENTRY_BYTES]、总解压 ≤ [MAX_TOTAL_BYTES]），并确认包里有合法
 *    `model.json`；
 * 3. `<dir>` → `<dir>.old`，`<dir>.tmp` → `<dir>`，写 `versions.json`，最后删 `.old`。
 *    第 3 步任何一处失败都把 `.old` 放回去 —— **旧版本仍然可用**。
 *
 * ## 线程
 *
 * [openStream] / [installedVersions] 是同步的本地文件读取（绘制线程会调，很快）；
 * [check] / [refresh] 自己开后台线程，**回调统一回主线程**（设置页直接更新 UI）。
 * 同一时刻只允许一次检查（[busy]），重复触发会立刻回调「正在检查」。
 */
object CarModelRemote {

    /** R2 自定义域（与 `AppUpdate` 用同一个域，走 Cloudflare CDN）。 */
    const val HOST = "file.chuxi.bid"

    /** 域名根：清单里的相对 key 按它拼绝对地址。 */
    const val BASE_URL = "https://$HOST/"

    /** 车模索引的对象 key（上传脚本写的也是这个 key）。 */
    const val INDEX_PATH = "cardesk/car-models/index.json"

    /** 车模索引的完整地址。 */
    const val INDEX_URL = BASE_URL + INDEX_PATH

    /** 索引格式版本；与 `tools/build_car_models.py` 里的 `FORMAT` 必须一致。 */
    const val FORMAT = "cardesk.carmodels/1"

    /** 本地缓存目录名（`filesDir` 下）。 */
    const val CACHE_DIR = "car3d-models"

    /** 车模包里的一个文件（索引里列出的逐文件摘要，用于解压后的二次校验）。 */
    data class RemoteFile(
        /** 包内相对路径，如 `cardtop/dark/closed.webp`。 */
        val name: String,
        /** 文件 SHA-256（小写十六进制）。 */
        val sha256: String,
        /** 文件字节数。 */
        val bytes: Long,
    )

    /** 远端索引里的一个车模包。 */
    data class RemoteModel(
        /** 资产目录名（`lynk01` / `lynk03` / `lynk05`），也是缓存子目录名。 */
        val dir: String,
        /** 展示名（`领克 01`），只用于进度/结果文案。 */
        val name: String,
        /** 包的版本号，从 1 开始单调递增。 */
        val version: Int,
        /** 包体字节数（下载时用来早停 + 校验）。 */
        val size: Long,
        /** 包体 SHA-256（小写十六进制，64 位）。 */
        val sha256: String,
        /** 下载地址（解析时已按 [BASE_URL] 拼成绝对地址）。 */
        val url: String,
        /**
         * 包内逐文件清单（可选）。
         *
         * 索引里带了就用它做解压后的**二次校验**：zip 整体摘要只证明「下载的是
         * 这一个文件」，逐文件摘要才能证明「解出来的每个图确实属于这个版本」。
         * 老索引没有这段也能正常安装（退回只用整体校验）。
         */
        val files: List<RemoteFile> = emptyList(),
    )

    /** 解析好的远端索引。 */
    data class RemoteIndex(
        val format: String,
        /** 索引格式版本（与 [FORMAT] 一起构成线上契约）。 */
        val version: Int,
        /** 索引生成日期（`20261124`，仅诊断用）。 */
        val updated: String,
        val models: List<RemoteModel>,
    )

    /** 一次检查/安装的结果（[check] 回调里用）。 */
    data class CheckOutcome(
        /** 全部成功为 true；有车模安装失败为 false（其它车模不受影响）。 */
        val ok: Boolean,
        /** 给用户看的一句话结果。 */
        val message: String,
        /** 远端索引里一共有几个车模。 */
        val installed: Int,
        /** 本次更新/新装的：目录 → 新版本号。 */
        val updated: Map<String, Int>,
        /** 本次失败的车模目录。 */
        val failed: List<String>,
    )

    /**
     * [sync] 的返回值。
     *
     * 给设置页直接判断用：`error == null` 即成功；`updated == 0` 说明已经是最新；
     * `skipped = true` 表示这次**没有做网络/下载**（索引命中缓存或已有检查在跑）。
     */
    data class SyncResult(
        /** 本次新装/更新的车模数量（0 = 已经全部最新）。 */
        val updated: Int,
        /** true = 没有发生下载（索引缓存命中 / 正在检查 / 已是最新）。 */
        val skipped: Boolean,
        /** 失败原因（给人看的中文）；null = 成功。 */
        val error: String?,
        /** 远端索引里一共有几个车模。 */
        val total: Int = 0,
        /** 本次更新/新装明细：目录 → 新版本号。 */
        val updatedDirs: Map<String, Int> = emptyMap(),
        /** 本次失败的车模目录（其余车模不受影响）。 */
        val failedDirs: List<String> = emptyList(),
    )

    /** 最近一次失败原因（诊断/设置页展示用；成功时清空）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 最近一次检查结束的时间（毫秒；0 = 从没查过）。 */
    @Volatile
    var lastCheckAt: Long = 0L
        private set

    /**
     * 进度回调（**主线程**调用），例如「正在下载 lynk05…」。
     *
     * 设置页可以临时挂一个显示到界面上；不挂也不影响功能。
     */
    @Volatile
    var onProgress: ((String) -> Unit)? = null

    /** 同一时刻只允许一次检查（避免设置页连点和桌面同时触发）。 */
    private val busy = AtomicBoolean(false)

    /** 保护下面三个进程内缓存（它们只是磁盘状态的内存镜像）。 */
    private val lock = Any()

    /** `versions.json` 的内存副本（null = 还没读过）。 */
    private var versionsCache: Map<String, Int>? = null

    /** 每个缓存目录自己的 `version.json` 版本（用来挡半包）。 */
    private val dirVersions = HashMap<String, Int?>()

    /** 最近一次成功拉到的远端索引（[sync] 复用，省一次几 KB 请求）。 */
    @Volatile
    private var indexCache: RemoteIndex? = null

    /** 索引缓存的时间戳（配合 [INDEX_TTL_MS]）。 */
    @Volatile
    private var indexFetchedAt: Long = 0L

    // ------------------------------------------------------------ 下载/安装常量

    private const val VERSIONS_FILE = "versions.json"
    private const val DIR_VERSION_FILE = "version.json"
    private const val DL_DIR = ".dl"
    private const val TMP_SUFFIX = ".tmp"
    private const val OLD_SUFFIX = ".old"

    /** `car3d/` 是 assets 逻辑路径前缀，缓存目录里去掉它。 */
    private const val LOGICAL_PREFIX = "car3d/"

    /** 单包上限（现在最大 432K，留足余量；也是防恶意索引把盘写满的第一道闸）。 */
    private const val MAX_PACKAGE_BYTES = CarModelIndex.MAX_PACKAGE_BYTES

    /** zip 单条目解压上限（一张 webp 最大也就 1MB 量级）。 */
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024

    /** zip 解压总量上限（一个车型包 33 个文件）。 */
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "CarDesk-CarModels/1"

    /** 索引内存缓存有效期：10 分钟内 [sync] 不重复拉（[sync] 的 force 可绕过）。 */
    private const val INDEX_TTL_MS = 10 * 60 * 1000L

    // ------------------------------------------------------------ 读取（绘制/解析路径）

    /**
     * 丢弃进程内的磁盘状态镜像（`versions.json` / 各目录 `version.json` / 远端索引缓存）。
     *
     * 只有「绕过本对象直接改缓存目录」时才需要：单元测试把车模夹具拷进缓存目录后调它，
     * 再走正常解码路径。运行时代码不调用（所有写入都经过本对象的安装流程）。
     */
    fun invalidateCache() {
        synchronized(lock) {
            versionsCache = null
            dirVersions.clear()
        }
        indexCache = null
        indexFetchedAt = 0L
    }

    /** 车模缓存根目录（`filesDir/car3d-models`）。 */
    fun cacheRoot(context: Context): File = File(context.filesDir, CACHE_DIR)

    /**
     * 统一资产入口：**已安装的远端缓存优先**。
     *
     * APK 不再内置车模，所以「没装过」时这里会抛 `IOException`；调用方
     * （[com.cardesk.launcher.core.car3d.Car3dAssets]）把它当作缺图处理，最终回退
     * [com.cardesk.launcher.core.CarTopPainter] 的自绘俯视车模 —— 首装/断网时桌面
     * 不会空窗，自动补齐完成后调 [Car3dAssets.reset] 换成写实车模。
     *
     * 末尾仍保留 `assets.open` 兜底：万一将来有人把某个车型重新塞回 assets，
     * 这条通路照旧可用（现在 assets 里没有 `car3d/` 目录，必定抛 `IOException`）。
     *
     * @param logicalPath assets 逻辑路径，如 `car3d/lynk01/model.json`、
     *   `car3d/lynk01/cardtop/dark/closed.webp`（必须以 `car3d/` 开头）。
     * @return 可被 `.use {}` 正常关闭的流；缓存命中返回 [FileInputStream]，
     *   否则抛 `IOException`。
     */
    fun openStream(context: Context, logicalPath: String): InputStream {
        val local = localFile(context, logicalPath)
        if (local != null) {
            try {
                return FileInputStream(local)
            } catch (t: Throwable) {
                // 缓存文件突然不可读（被清理/权限异常）：不能让绘制崩，退回内置资源
                Logx.w(LogTags.CAR3D, "缓存车模读取失败，改走 assets 兜底", t, "path" to logicalPath)
            }
        }
        return context.assets.open(logicalPath)
    }

    /** 某个逻辑路径对应的缓存文件；不该用缓存时返回 null。 */
    private fun localFile(context: Context, logicalPath: String): File? {
        if (!logicalPath.startsWith(LOGICAL_PREFIX)) return null
        val rest = logicalPath.substring(LOGICAL_PREFIX.length)
        val slash = rest.indexOf('/')
        // 至少要 `car3d/<dir>/<某文件>`：`<dir>` 单段、后面还有内容
        if (slash <= 0 || slash >= rest.length - 1) return null
        val dir = rest.substring(0, slash)
        val rel = rest.substring(slash + 1)
        // `..` / 绝对路径之类绝不能拼到缓存目录外（与 zip 条目同一套判定）
        if (!CarModelIndex.isSafeEntryName(rel)) return null
        val installed = installedVersions(context)[dir] ?: return null
        // 两级版本戳必须一致：目录是新的、版本表还是旧的（或反过来）都当半包
        if (dirVersion(context, dir) != installed) return null
        val file = File(File(cacheRoot(context), dir), rel)
        return if (file.isFile) file else null
    }

    /** 已安装版本表（`versions.json`；读不到/损坏 = 空表，等价于全部回退 assets）。 */
    fun installedVersions(context: Context): Map<String, Int> {
        synchronized(lock) {
            versionsCache?.let { return it }
            val read = CarModelIndex.parseVersions(
                runCatching { versionsFile(context).readText(Charsets.UTF_8) }.getOrNull().orEmpty(),
            )
            versionsCache = read
            return read
        }
    }

    /**
     * 某个车型本地缓存里已安装的版本号；**没装过返回 0**。
     *
     * 只认「两级版本戳一致」的目录（与 [openStream] 同一判据）：半包状态返回 0，
     * 设置页因此会显示「未安装」而不是误报「已是 v2」。
     */
    fun installedVersion(context: Context, dir: String): Int {
        val installed = installedVersions(context)[dir] ?: return 0
        return if (dirVersion(context, dir) == installed) installed else 0
    }

    /**
     * 某个缓存目录 `version.json` 里记的版本；目录不存在/文件坏了 = null。
     *
     * 只在**第一次**看这个目录时读盘，之后走内存（绘制线程会高频调用）。
     * 安装成功时在锁内刷新该缓存，失败回滚时移除，保证不会误信半包。
     */
    private fun dirVersion(context: Context, dir: String): Int? = synchronized(lock) {
        if (dirVersions.containsKey(dir)) return dirVersions[dir]
        val root = File(cacheRoot(context), dir)
        val version = if (root.isDirectory) {
            runCatching {
                CarModelIndex.parseDirVersion(File(root, DIR_VERSION_FILE).readText(Charsets.UTF_8))
            }.getOrNull()
        } else {
            null
        }
        dirVersions[dir] = version
        version
    }

    // ------------------------------------------------------------ 检查/更新（设置页触发）

    /**
     * 拉一次远端索引到内存（**阻塞**：会做网络 IO，必须在后台线程调用）。
     *
     * 只取 `index.json`（几 KB），不下载任何车模包；成功后 [sync] 可以直接复用。
     * 失败**不抛异常**，返回 false 并把原因写进 [lastError]，设置页不用自己
     * try/catch。主线程调用会被直接拒绝（返回 false），不会触发 Android 的
     * `NetworkOnMainThreadException`。
     */
    fun ensureIndex(context: Context): Boolean {
        if (!isWorkerThread()) {
            lastError = "获取车模清单需要在后台线程调用"
            Logx.w(LogTags.CAR3D, "ensureIndex 在主线程被调用，已忽略")
            return false
        }
        return ensureIndexInternal(force = false) != null
    }

    /**
     * 同步车模包（**阻塞**：网络 + 解压都在调用线程，必须在后台线程调用）。
     *
     * 设置页「检查更新」的推荐写法（`force = true` 才会绕过 10 分钟索引缓存）：
     *
     * ```kotlin
     * Thread {
     *     val r = CarModelRemote.sync(context, force = true)
     *     mainHandler.post {
     *         toast(
     *             when {
     *                 r.error != null -> r.error
     *                 r.updated > 0 -> "已更新 ${r.updated} 个车模"
     *                 else -> "车模已是最新"
     *             },
     *         )
     *     }
     * }.start()
     * ```
     *
     * 主线程直接调用**不会崩**（不会触发 `NetworkOnMainThreadException`），而是
     * 立刻返回 `error = "sync 需要在后台线程调用"`；不想自己开线程就直接用
     * [refresh]（内部已经开后台线程并回主线程）。
     *
     * @param force true = 忽略索引缓存重新拉 `index.json`（按钮应该传 true）；
     *   false = 允许复用 10 分钟内的索引。无论哪种，远端版本号不高于本地时
     *   都**不会重装**。
     */
    fun sync(context: Context, force: Boolean = false): SyncResult {
        if (!isWorkerThread()) {
            val result = SyncResult(0, true, "sync 需要在后台线程调用")
            Logx.w(LogTags.CAR3D, "sync 在主线程被调用，已忽略")
            return result
        }
        val app = context.applicationContext
        return try {
            doSync(app, force)
        } catch (t: Throwable) {
            lastError = "检查车模失败：${reason(t)}"
            Logx.w(LogTags.CAR3D, "同步远端车模异常", t)
            SyncResult(0, false, lastError, installedVersions(app).size)
        } finally {
            lastCheckAt = System.currentTimeMillis()
        }
    }

    /**
     * 检查远端索引并安装/更新（**后台线程 + 主线程回调**）。
     *
     * 比 [sync] 省事：调用方不用自己开线程，回调一定在主线程；同一个时刻只允许
     * 一次检查，重复触发会立刻回调「正在检查车模，请稍候」。
     */
    fun check(context: Context, callback: (CheckOutcome) -> Unit) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) {
            postMain {
                callback(
                    CheckOutcome(
                        ok = false,
                        message = "正在检查车模，请稍候",
                        installed = installedVersions(app).size,
                        updated = emptyMap(),
                        failed = emptyList(),
                    ),
                )
            }
            return
        }
        val thread = Thread({
            val result = try {
                sync(app, force = false)
            } catch (t: Throwable) {
                // sync 自己已经兜底，这里再兜一层：回调绝对不能丢
                SyncResult(0, false, reason(t))
            } finally {
                busy.set(false)
            }
            postMain { callback(result.toCheckOutcome()) }
        }, "car-models-check")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 同 [check]，但只回一句给用户看的结果（设置页按钮最省事的用法）。
     *
     * ```kotlin
     * CarModelRemote.refresh(context) { msg -> toast(msg) }
     * ```
     */
    fun refresh(context: Context, onDone: (String) -> Unit) {
        check(context) { outcome -> onDone(outcome.message) }
    }

    /** 后台线程里的完整流程：拉索引 → 逐个比较版本 → 需要则安装。 */
    private fun doSync(context: Context, force: Boolean): SyncResult {
        progress("正在获取车模清单…")
        val index = ensureIndexInternal(force)
        if (index == null) {
            return SyncResult(
                updated = 0,
                skipped = false,
                error = lastError ?: "车模清单读取失败",
                total = installedVersions(context).size,
            )
        }
        lastError = null
        progress("车模清单：${index.models.size} 个")
        val installed = installedVersions(context)
        val updated = LinkedHashMap<String, Int>()
        val failed = ArrayList<String>()
        for (model in index.models) {
            val current = installed[model.dir]
            if (!CarModelIndex.needsUpdate(model.version, current)) {
                Logx.d(
                    LogTags.CAR3D,
                    "车模已是最新",
                    "dir" to model.dir,
                    "version" to current,
                )
                continue
            }
            val action = if (current == null) "安装" else "更新"
            progress("正在$action ${model.name}（v${model.version}）…")
            try {
                install(context, model)
                updated[model.dir] = model.version
                Logx.i(
                    LogTags.CAR3D,
                    "车模已$action",
                    "dir" to model.dir,
                    "version" to model.version,
                    "size" to model.size,
                )
            } catch (t: Throwable) {
                failed.add(model.dir)
                val why = reason(t)
                lastError = "${model.dir} $action 失败：$why"
                Logx.w(
                    LogTags.CAR3D,
                    "车模${action}失败，保留原有版本",
                    t,
                    "dir" to model.dir,
                    "version" to model.version,
                )
            }
        }
        return SyncResult(
            updated = updated.size,
            skipped = updated.isEmpty() && failed.isEmpty(),
            error = if (failed.isEmpty()) null else lastError,
            total = index.models.size,
            updatedDirs = updated,
            failedDirs = failed,
        )
    }

    /** [SyncResult] → 设置页/Toast 用的 [CheckOutcome]（拼一句人话）。 */
    private fun SyncResult.toCheckOutcome(): CheckOutcome = CheckOutcome(
        ok = error == null && failedDirs.isEmpty(),
        message = if (error != null && failedDirs.isEmpty()) {
            error
        } else {
            buildMessage(total, updatedDirs, failedDirs)
        },
        installed = total,
        updated = updatedDirs,
        failed = failedDirs,
    )

    /** 拉索引（命中内存缓存就直接用；force 绕缓存）。失败写 [lastError] 并返回 null。 */
    private fun ensureIndexInternal(force: Boolean): RemoteIndex? {
        val cached = indexCache
        if (!force && cached != null && System.currentTimeMillis() - indexFetchedAt < INDEX_TTL_MS) {
            return cached
        }
        val index = fetchIndex() ?: return null
        indexCache = index
        indexFetchedAt = System.currentTimeMillis()
        return index
    }

    /** 把结构化结果拼成一句人类可读的话（[refresh] 直接展示）。 */
    internal fun buildMessage(
        total: Int,
        updated: Map<String, Int>,
        failed: List<String>,
    ): String {
        val parts = ArrayList<String>(3)
        if (updated.size == 1) {
            val e = updated.entries.first()
            parts.add("${e.key} 更新到 v${e.value}")
        } else if (updated.size > 1) {
            parts.add(
                "已更新 ${updated.size} 个车模：" +
                    updated.entries.joinToString("、") { "${it.key} v${it.value}" },
            )
        }
        if (failed.isNotEmpty()) {
            parts.add("${failed.size} 个车模未更新：" + failed.joinToString("、"))
        }
        if (parts.isEmpty()) parts.add("已安装 $total 个车模，全部最新")
        return parts.joinToString("；")
    }

    /** 拉索引文本并解析（失败写 [lastError]）。 */
    private fun fetchIndex(): RemoteIndex? {
        val text = try {
            httpGetText(INDEX_URL)
        } catch (t: Throwable) {
            lastError = "车模清单下载失败：${reason(t)}"
            Logx.w(LogTags.CAR3D, "车模清单下载失败", t, "url" to INDEX_URL)
            return null
        }
        val index = CarModelIndex.parse(text)
        if (index == null) {
            lastError = "车模清单格式不对"
            Logx.w(LogTags.CAR3D, "车模清单格式不对", "url" to INDEX_URL, "bytes" to text.length)
            return null
        }
        return index
    }

    // ------------------------------------------------------------ 安装

    /** 下载 → 校验 → 解压到 `.tmp` → 原子替换 → 写版本表；失败时旧版本仍可用。 */
    private fun install(context: Context, model: RemoteModel) {
        val root = cacheRoot(context)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建车模缓存目录")
        val dl = File(root, DL_DIR)
        if (!dl.isDirectory && !dl.mkdirs()) throw IOException("无法创建下载目录")
        val part = File(dl, "${model.dir}-v${model.version}.zip.part")
        part.delete()
        val tmp = File(root, model.dir + TMP_SUFFIX)
        try {
            download(model, part)
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) throw IOException("无法创建临时安装目录")
            unzip(model, part, tmp)
            writeDirVersion(tmp, model)
            activate(context, root, model, tmp)
        } finally {
            // 无论成败，临时下载与临时目录都不留（成功时 tmp 已被改名走）
            part.delete()
            tmp.deleteRecursively()
        }
    }

    /** 下载到 [part]：边下边算 SHA-256；大小超限/摘要不符都算失败（不留半包）。 */
    private fun download(model: RemoteModel, part: File) {
        // size = 0 表示索引没给包体大小（老/精简索引）：只靠上限兜底，装完再按
        // 逐文件摘要校验；给了就严格比对，宁可下不完也不能装错包。
        val expected = model.size
        if (expected > MAX_PACKAGE_BYTES) {
            throw IOException("包体大小异常（$expected 字节）")
        }
        val conn = openConnection(model.url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            // 服务器声明了长度就先比一次：明显不对的包（截断/换包）不必等下载完
            val declared = conn.getContentLengthLong()
            if (expected > 0 && declared > 0 && declared != expected) {
                throw IOException("Content-Length 与清单不符：$declared / $expected")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(part).use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        // 早停：比声明的/允许的大就断开，别把盘写满
                        if (expected > 0 && total > expected) {
                            throw IOException("包体比清单声明的大（>$expected 字节）")
                        }
                        if (total > MAX_PACKAGE_BYTES) {
                            throw IOException("包体超过上限（$MAX_PACKAGE_BYTES 字节）")
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (expected > 0 && total != expected) {
                throw IOException("包体大小不符：期望 $expected，实际 $total")
            }
            if (model.sha256.isNotEmpty()) {
                val actual = toHex(digest.digest())
                if (!actual.equals(model.sha256, ignoreCase = true)) {
                    throw IOException("SHA-256 不符：期望 ${model.sha256}，实际 $actual")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解压到 [dest]。
     *
     * 安全校验不是可选项：车模包来自网络，zip 条目名可以写成 `../../` 把文件
     * 解到别的目录去。路径不安全直接判整包失败（宁可不装，也不碰缓存目录外的文件）。
     */
    private fun unzip(model: RemoteModel, zip: File, dest: File) {
        var total = 0L
        var count = 0
        val extracted = ArrayList<String>()
        val destCanonical = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name ?: throw IOException("zip 条目没有名字")
                // 目录项、打包工具附带的元数据目录：跳过
                if (entry.isDirectory || name.endsWith("/")) {
                    zin.closeEntry()
                    continue
                }
                if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) {
                    zin.closeEntry()
                    continue
                }
                if (!CarModelIndex.isSafeEntryName(name)) {
                    throw IOException("zip 条目路径不安全：$name")
                }
                val out = File(dest, name)
                // isSafeEntryName 已挡掉 `..`，这里再按 canonicalPath 兜一层（符号链接等）
                if (!out.canonicalPath.startsWith(destCanonical)) {
                    throw IOException("zip 条目落到缓存目录外：$name")
                }
                out.parentFile?.mkdirs()
                var entryBytes = 0L
                FileOutputStream(out).use { os ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = zin.read(buffer)
                        if (n < 0) break
                        entryBytes += n
                        total += n
                        if (entryBytes > MAX_ENTRY_BYTES) throw IOException("zip 条目过大：$name")
                        if (total > MAX_TOTAL_BYTES) throw IOException("zip 解压总量过大")
                        os.write(buffer, 0, n)
                    }
                }
                zin.closeEntry()
                count++
                extracted.add(name)
            }
        }
        if (count == 0) throw IOException("zip 里没有文件")
        // 没有合法 model.json 的包装上去只会让该车型「永久回退自绘」，不如当场拒
        val modelJson = File(dest, "model.json")
        if (!modelJson.isFile) throw IOException("车模包缺少 model.json")
        val format = CarModelIndex.parseModelFormat(modelJson.readText(Charsets.UTF_8))
        if (format != Car3dAssets.FORMAT) {
            throw IOException("车模包 model.json 格式不对：${format.ifEmpty { "?" }}")
        }
        // 索引带逐文件摘要时做二次校验：zip 整体摘要只证明「下载对了」，逐文件摘要
        // 才能证明「解出来的每张图确实属于这个版本」。老索引没 files 也能正常装。
        for (f in model.files) {
            if (!CarModelIndex.isSafeEntryName(f.name)) {
                throw IOException("文件清单路径不安全：${f.name}")
            }
            val file = File(dest, f.name)
            if (!file.isFile) throw IOException("车模包缺少 ${f.name}")
            if (file.length() != f.bytes) throw IOException("文件大小不符：${f.name}")
            if (!sha256Of(file).equals(f.sha256, ignoreCase = true)) {
                throw IOException("文件摘要不符：${f.name}")
            }
        }
        // 清单给了就必须覆盖 zip 里的全部文件：多出来的文件没有摘要背书，不能落盘
        if (model.files.isNotEmpty()) {
            val listed = HashSet<String>(model.files.size)
            for (f in model.files) listed.add(f.name)
            for (name in extracted) {
                if (!listed.contains(name)) throw IOException("车模包含清单外的文件：$name")
            }
        }
    }

    /**
     * 原子替换：旧目录先改名 `.old`，新目录顶上，写版本表成功后才删 `.old`。
     *
     * 版本表写失败时把新目录撤掉、`.old` 放回去 —— 运行时至少还能用旧包。
     */
    private fun activate(context: Context, root: File, model: RemoteModel, tmp: File) {
        val target = File(root, model.dir)
        val old = File(root, model.dir + OLD_SUFFIX)
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) {
            throw IOException("无法暂存旧车模目录（${model.dir}）")
        }
        if (!tmp.renameTo(target)) {
            if (old.exists() && !old.renameTo(target)) {
                Logx.w(LogTags.CAR3D, "回滚旧车模目录失败", "dir" to model.dir)
            }
            throw IOException("无法启用新车模目录（${model.dir}）")
        }
        try {
            publishVersions(context, model)
        } catch (t: Throwable) {
            // 版本表没写成功 = 这次安装不算数：撤掉新目录，旧目录放回原位
            target.deleteRecursively()
            if (old.exists() && !old.renameTo(target)) {
                Logx.w(LogTags.CAR3D, "回滚旧车模目录失败", t, "dir" to model.dir)
            }
            synchronized(lock) { dirVersions.remove(model.dir) }
            throw t
        }
        old.deleteRecursively()
        synchronized(lock) { dirVersions[model.dir] = model.version }
    }

    /** 写 `<dir>/version.json`（解压后、替换前写，内容只是版本戳）。 */
    private fun writeDirVersion(dir: File, model: RemoteModel) {
        val text = JsonLite.write(
            linkedMapOf<String, Any?>(
                "dir" to model.dir,
                "version" to model.version,
                "sha256" to model.sha256,
            ),
        ) + "\n"
        writeFileAtomic(File(dir, DIR_VERSION_FILE), text)
    }

    /** 把本次安装版本并入 `versions.json`（写临时文件再原子改名）。 */
    private fun publishVersions(context: Context, model: RemoteModel) {
        val root = cacheRoot(context)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建车模缓存目录")
        // 以磁盘为准重读：安装是串行的，但首次安装前内存里可能还没读过
        val current = CarModelIndex.parseVersions(
            runCatching { versionsFile(context).readText(Charsets.UTF_8) }.getOrNull().orEmpty(),
        )
        val next = LinkedHashMap(current)
        next[model.dir] = model.version
        writeFileAtomic(versionsFile(context), CarModelIndex.writeVersions(next))
        synchronized(lock) {
            versionsCache = next
            dirVersions[model.dir] = model.version
        }
    }

    /** 写临时文件 → fsync → rename 覆盖（Android/Linux 的 rename 是原子的）。 */
    private fun writeFileAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (tmp.renameTo(target)) return
        // 个别文件系统不允许 rename 覆盖已存在文件：删掉再来一次
        if (target.exists() && target.delete() && tmp.renameTo(target)) return
        tmp.delete()
        throw IOException("无法写入 ${target.name}")
    }

    private fun versionsFile(context: Context): File = File(cacheRoot(context), VERSIONS_FILE)

    // ------------------------------------------------------------ 网络/杂项

    /** 建一个只读 HTTPS 连接（跟随重定向；http 也允许，便于本地联调）。 */
    private fun openConnection(rawUrl: String): HttpURLConnection {
        val absolute = CarModelIndex.absoluteUrl(rawUrl)
        if (!absolute.startsWith("https://") && !absolute.startsWith("http://")) {
            throw IOException("下载地址不是 http(s)：$rawUrl")
        }
        val conn = URL(absolute).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/octet-stream")
        return conn
    }

    /** 读一个小的文本资源（索引 JSON），上限 1MB 防异常大响应。 */
    private fun httpGetText(url: String): String {
        val conn = openConnection(url)
        try {
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    sb.append(buffer, 0, n)
                    if (sb.length > 1024 * 1024) throw IOException("响应体过大")
                }
                sb.toString()
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 进度回调（回主线程；回调异常不能影响安装流程）。 */
    private fun progress(message: String) {
        val callback = onProgress ?: return
        postMain { runCatching { callback(message) } }
    }

    /** 当前线程是否允许做网络/解压（主线程返回 false；无 Looper 的线程算允许）。 */
    private fun isWorkerThread(): Boolean {
        val main = Looper.getMainLooper() ?: return true
        return Looper.myLooper() != main
    }

    /** 切回主线程（本来就在主线程就直接跑，省一次 Handler 往返）。 */
    private fun postMain(block: () -> Unit) {
        val main = Looper.getMainLooper()
        if (main != null && Looper.myLooper() == main) {
            block()
            return
        }
        if (main == null) {
            // 理论上 Android 进程里不会发生；退化成当前线程回调，别把结果吞掉
            block()
            return
        }
        Handler(main).post(block)
    }

    private fun reason(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    /** 算一个文件的 SHA-256（解压后的逐文件校验用，车模包很小，开销可忽略）。 */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * 远端车模索引/版本表的**纯逻辑**部分（不碰 Android API，JVM 单测直接测）。
 *
 * 单独抽出来是因为这些是「线上格式」的契约，出错会让整条链路静默失效：
 * 解析放宽了会把坏包装进去，收紧错了会让正常包永远装不上。所以这里每个函数
 * 都只做字符串/数值处理，`CarModelRemoteTest` 里逐条钉死。
 *
 * 解析用项目自带的 [JsonLite] 而不是 `org.json`：`org.json` 在 JVM 单测里是
 * android.jar 的 stub（`isReturnDefaultValues = true` 时静默返回 null/0），
 * 格式契约就没法验证了（`R2Release` 也是这么做的）。
 */
internal object CarModelIndex {

    /** 单个车模包的上限（索引里的 `size` 不能超过它；缺省 size 时下载也按它兜底）。 */
    const val MAX_PACKAGE_BYTES = 16L * 1024 * 1024

    /** 解析索引原文；格式不对/一个可用车模都没有 → null（调用方按「读不到」处理）。 */
    fun parse(json: String): CarModelRemote.RemoteIndex? {
        val root = JsonLite.parseObject(json) ?: return null
        if (JsonLite.str(root, "format").trim() != CarModelRemote.FORMAT) return null
        val rawModels = root["models"] as? List<*> ?: return null
        val models = ArrayList<CarModelRemote.RemoteModel>(rawModels.size)
        val seen = HashSet<String>(rawModels.size)
        for (item in rawModels) {
            val map = item as? Map<String, Any?> ?: continue
            val model = parseModel(map) ?: continue
            // 同一个 dir 出现两次：不知道该信哪条，跳过重复的那条
            if (!seen.add(model.dir)) continue
            models.add(model)
        }
        if (models.isEmpty()) return null
        return CarModelRemote.RemoteIndex(
            format = CarModelRemote.FORMAT,
            version = JsonLite.int(root, "version", 1),
            updated = JsonLite.str(root, "updated").trim(),
            models = models,
        )
    }

    /** 解析单个 model 段；任何字段不可信 → null（跳过这条，不影响其它车模）。 */
    private fun parseModel(map: Map<String, Any?>): CarModelRemote.RemoteModel? {
        val dir = JsonLite.str(map, "dir").trim()
        // dir 会被直接拼进缓存路径，只允许单段安全名
        if (dir.isEmpty() || dir.contains('/') || !isSafeEntryName(dir)) return null
        val version = JsonLite.int(map, "version", 0)
        if (version <= 0) return null
        // 包级 size / sha256 允许缺省：索引也可以只给逐文件摘要（files）。
        // 但两个都没有 = 没有任何完整性依据，这种包不装。
        val size = JsonLite.long(map, "size", 0L)
        if (size < 0L || size > MAX_PACKAGE_BYTES) return null
        val rawSha = JsonLite.str(map, "sha256").trim().lowercase()
        if (rawSha.isNotEmpty() && !isSha256(rawSha)) return null
        val name = JsonLite.str(map, "name").trim().ifEmpty { dir }
        val rawUrl = JsonLite.str(map, "url").trim()
        val key = if (rawUrl.isEmpty()) defaultObjectKey(dir, version) else rawUrl
        val url = absoluteUrl(key)
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        // 逐文件清单可选；给了就必须可信，坏一条就整条 model 作废
        val files = parseFiles(map["files"]) ?: return null
        if (rawSha.isEmpty() && files.isEmpty()) return null
        return CarModelRemote.RemoteModel(
            dir = dir,
            name = name,
            version = version,
            size = size,
            sha256 = rawSha,
            url = url,
            files = files,
        )
    }

    /** 解析可选的 `files: [{name, sha256, bytes}]`；没给 = 空表，格式坏 = null。 */
    private fun parseFiles(raw: Any?): List<CarModelRemote.RemoteFile>? {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: return null
        val files = ArrayList<CarModelRemote.RemoteFile>(list.size)
        for (item in list) {
            val map = item as? Map<String, Any?> ?: return null
            val name = JsonLite.str(map, "name").trim()
            if (!isSafeEntryName(name)) return null
            val sha = JsonLite.str(map, "sha256").trim().lowercase()
            if (!isSha256(sha)) return null
            val bytes = JsonLite.long(map, "bytes", 0L)
            if (bytes <= 0L) return null
            files.add(CarModelRemote.RemoteFile(name, sha, bytes))
        }
        return files
    }

    /** 缺省对象 key：`cardesk/car-models/<dir>-v<version>.zip`。 */
    fun defaultObjectKey(dir: String, version: Int): String =
        "cardesk/car-models/$dir-v$version.zip"

    /** 相对 key 按 [CarModelRemote.BASE_URL] 拼成绝对地址；已经是绝对地址就原样返回。 */
    fun absoluteUrl(url: String): String {
        if (url.startsWith("https://") || url.startsWith("http://")) return url
        return CarModelRemote.BASE_URL + url.removePrefix("/")
    }

    /**
     * 是否需要下载：没装过，或远端版本比本地新。
     *
     * 远端版本比本地**小**时不动（不回滚）—— 线上索引万一被旧构建覆盖，
     * 不能让已经更新过的车机被降级。
     */
    fun needsUpdate(remoteVersion: Int, installedVersion: Int?): Boolean =
        installedVersion == null || remoteVersion > installedVersion

    /** 把 `versions.json` 解析成 `目录 → 版本`；脏值/脏 key 跳过（不整体失败）。 */
    fun parseVersions(json: String): Map<String, Int> {
        val root = JsonLite.parseObject(json) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for ((key, value) in root) {
            if (key.isEmpty() || key.contains('/') || !isSafeEntryName(key)) continue
            val version = when (value) {
                is Long -> value.toInt()
                is Int -> value
                is Double -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            } ?: continue
            if (version > 0) out[key] = version
        }
        return out
    }

    /** 生成 `versions.json`（按目录名排序，保证同样的内容写出来字节一致）。 */
    fun writeVersions(versions: Map<String, Int>): String {
        val sorted = LinkedHashMap<String, Int>(versions.size)
        for (key in versions.keys.sorted()) sorted[key] = versions.getValue(key)
        return JsonLite.write(sorted) + "\n"
    }

    /** 解析 `<dir>/version.json` 的版本号；坏文件返回 null（当作没装）。 */
    fun parseDirVersion(json: String): Int? {
        val root = JsonLite.parseObject(json) ?: return null
        val version = JsonLite.int(root, "version", 0)
        return if (version > 0) version else null
    }

    /** 车模包 `model.json` 的 `format`（解压后校验，避免装进别的资源包）。 */
    fun parseModelFormat(json: String): String {
        val root = JsonLite.parseObject(json) ?: return ""
        return JsonLite.str(root, "format").trim()
    }

    /** 64 位十六进制 SHA-256 判定（长度/字符集都不对就当没给摘要）。 */
    fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    /**
     * zip 条目名是否安全（能直接 `File(dest, name)` 落盘而不会跑出 `dest`）。
     *
     * 拒绝：空、绝对路径（`/`、`\`）、反斜杠、NUL、`.`/`..`/空路径段、盘符
     * （`C:/x`，zip 可能由任意平台打出来）。允许嵌套路径与目录项末尾的 `/`
     * （安装时目录项本来就会被跳过）。
     */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 512) return false
        if (name.indexOf('\u0000') >= 0) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        // 反斜杠在 Android 上是合法文件名字符，但出现在 zip 条目名里只会是打包错误/攻击
        if (name.contains('\\')) return false
        val trimmed = name.trimEnd('/')
        if (trimmed.isEmpty()) return false
        // Windows 盘符（`C:` 段）：Android 用不到，但没必要放进来
        if (trimmed.length >= 2 && trimmed[1] == ':' && trimmed[0].isLetter()) return false
        for (part in trimmed.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") return false
        }
        return true
    }
}
