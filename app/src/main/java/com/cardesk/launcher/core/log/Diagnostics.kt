package com.cardesk.launcher.core.log

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.cardesk.launcher.core.media.MusicController
import com.cardesk.launcher.vehicle.VehicleController
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包导出：把日志 + 运行快照打成一个 zip，落到公共目录，用户在车上直接取走。
 *
 * ## 产物
 *
 * `
 * Download/CarDesk/cardesk-logs-20261023-085412.zip
 *   ├── summary.txt 环境 / 版本 / 权限 / 车控通道 / 设置全量键值
 *   └── logs/ 全部日志文件（cardesk-yyyyMMdd-N.txt）
 * `
 *
 * ## 为什么不用 FileProvider
 *
 * 本项目**刻意不引入任何运行时依赖**（纯 framework API，便于直接丢进 AOSP 源码树），
 * 所以分享走 `MediaStore` 的 `content://` URI + `ACTION_SEND`，
 * 不需要 androidx 的 FileProvider。
 */
object Diagnostics {

    /** 导出结果。 */
    sealed class Result {
        /** 成功。[uri] 为 null 表示写到了公共目录的普通路径（Android 9 及以下）。 */
        data class Ok(
            val uri: Uri?,
            val displayPath: String,
            val fileCount: Int,
            val bytes: Long,
        ) : Result()

        /** 失败。[reason] 是可直接给用户看的原因。 */
        data class Fail(val reason: String) : Result()
    }

    private const val TAG = LogTags.LOG
    private const val PUBLIC_DIR_NAME = "CarDesk"
    private const val STAMP_FMT = "yyyyMMdd-HHmmss"

    /**
     * 打进诊断包的日志总字节上限（2MB）。
     *
     * 日志文件本身有 4MB 上限，压缩后一般远小于此，
     * 但保险起见还是限一下 —— 诊断包要写到公共目录，车机存储紧张。
     * 超了只带**最新**的那部分，并在 summary 里注明截断了。
     */
    private const val PACK_LOG_BYTES = 2L * 1024 * 1024

    /** 诊断包文件名前缀（用于识别并清理旧包）。 */
    private const val PACK_NAME_PREFIX = "cardesk-logs-"

    /** 公共目录里最多保留几份诊断包。 */
    private const val KEEP_PACKS = 3

    /** 需要在快照里核对的权限。 */
    private val PERMISSIONS = listOf(
        android.Manifest.permission.INTERNET,
        android.Manifest.permission.ACCESS_NETWORK_STATE,
        android.Manifest.permission.ACCESS_WIFI_STATE,
        android.Manifest.permission.CHANGE_WIFI_STATE,
        android.Manifest.permission.WAKE_LOCK,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
        // `Manifest.permission.QUERY_ALL_PACKAGES` 是 API 30 起公开的常量，
        // 本项目 compileSdk 29（一致）编不到 —— 这里用权限名字符串。
        "android.permission.QUERY_ALL_PACKAGES",
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        // 频谱采集（氛围灯）：这两个之前**根本没在快照里**，
        // 于是「频谱没起来」在诊断包里无法自证 —— 只能翻几百行日志猜。
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.MODIFY_AUDIO_SETTINGS,
    )

    /**
     * 氛围灯自检要列的关键常量（**按名解析**，不硬编码十进制）。
     *
     * 真机上「灯不亮」有几种原因：功能号按名解析不到 /
     * 通道没就绪 / 权限没批 / 频谱没数据。
     * 这一段把这几件事一次性列清，不用再翻日志。
     */
    private val AMBIENT_CONSTANTS = listOf(
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_INTERACTIVE_EFFECT,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_MAIN_COLOR,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_EXPERIENCE,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_COLOR_SET,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_DRIVING,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_BRIGHTNESS_STATIONARY,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_MAIN_ZONES,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_TOP_ZONES,
        com.cardesk.launcher.vehicle.AdaptapiConstants.FUNC_AMBIENT_BOT_ZONES,
        com.cardesk.launcher.vehicle.AdaptapiConstants.AMBIENT_MAIN_COLOR_SET_COLOR,
        com.cardesk.launcher.vehicle.AdaptapiConstants.AMBIENT_BRIGHTNESS_HIGH,
        com.cardesk.launcher.vehicle.AdaptapiConstants.AMBIENT_BRIGHTNESS_LOW,
        com.cardesk.launcher.vehicle.AdaptapiConstants.AMBIENT_BRIGHTNESS_STATIONARY_HIGH,
        com.cardesk.launcher.vehicle.AdaptapiConstants.AMBIENT_BRIGHTNESS_STATIONARY_LOW,
        com.cardesk.launcher.vehicle.AdaptapiConstants.AMBIENT_EXPERIENCE_CUSTOM,
    )

    /**
     * 导出诊断包。
     *
     * 必须在**后台线程**调用（会读文件、写 zip、拷到公共目录）。
     */
    fun export(context: Context): Result {
        val app = context.applicationContext
        val stamp = SimpleDateFormat(STAMP_FMT, Locale.CHINA).format(Date())
        val name = "cardesk-logs-$stamp.zip"
        return try {
            // 从**最新**的文件往回装，装满 PACK_LOG_BYTES 就停
            val all = Logx.logDirectory()?.listFiles().orEmpty().sortedBy { it.lastModified() }
            val picked = ArrayList<File>()
            var pickedBytes = 0L
            for (f in all.asReversed()) {
                if (pickedBytes + f.length() > PACK_LOG_BYTES) continue
                picked.add(f)
                pickedBytes += f.length()
            }
            val logs = picked.sortedBy { it.name }
            val truncated = logs.size < all.size
            val summary = summary(app, truncated, all.size - logs.size)
            val cacheZip = File(app.cacheDir, name)
            var bytes = 0L
            ZipOutputStream(FileOutputStream(cacheZip)).use { zos ->
                zos.putNextEntry(ZipEntry("summary.txt"))
                zos.write(summary.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                for (file in logs) {
                    val entryName = "logs/${file.name}"
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            bytes = cacheZip.length()

            val target = publish(app, cacheZip, name)
            // 公共目录已经有副本了，cache 里的临时 zip 用完就删（车机存储紧张）
            runCatching { cacheZip.delete() }
            // 公共目录里的旧诊断包只留最近几份
            pruneOldPacks(app)
            when (target) {
                is PublishResult.PublishedUri -> {
                    Logx.i(TAG, "诊断包已导出", "uri" to target.contentUri, "bytes" to bytes, "files" to logs.size)
                    Result.Ok(target.contentUri, target.display, logs.size, bytes)
                }
                is PublishResult.Path -> {
                    Logx.i(TAG, "诊断包已导出", "path" to target.path, "bytes" to bytes, "files" to logs.size)
                    Result.Ok(null, target.path, logs.size, bytes)
                }
                is PublishResult.Fail -> {
                    Logx.w(TAG, "诊断包导出失败", "reason" to target.reason)
                    Result.Fail(target.reason)
                }
            }
        } catch (t: Throwable) {
            Logx.e(TAG, "诊断包导出异常", t)
            Result.Fail(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 分享诊断包（需要先 [export] 拿到 Uri）。 */
    fun shareIntent(uri: Uri, subject: String = "CarDesk 诊断日志"): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ------------------------------------------------------------------ 清理旧诊断包

    /**
     * 公共目录里只保留最近 [KEEP_PACKS] 份诊断包，多余的删掉。
     *
     * 每次导出都会在 `Download/CarDesk/` 留一个 zip，车机存储紧张，
     * 不清理的话导个十几次就把公共目录塞满了。
     *
     * ## 安全要求
     *
     * 这里**绝对不能**用 `delete(uri.buildUpon().appendQueryParameter(_ID, id))`
     * 这种「把条件塞进 query param」的写法 —— MediaProvider 不一定按 过滤，
     * 实测会把 `Downloads` 集合里**所有**文件删掉（连用户的下载文件一起没）。
     *
     * 必须用标准 selection 形式，并且条件里同时带上**文件名**和**相对路径**，
     * 保证只可能删到「我们自己生成的、放在 CarDesk 目录里的」诊断包。
     */
    private fun pruneOldPacks(context: Context) {
        runCatching {
            val resolver = context.contentResolver
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // Android 10+：走 MediaStore
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                )
                // 条件要够窄：只看我们自己前缀的 zip，且只在我们自己的目录下
                val selection =
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
                val args = arrayOf("$PACK_NAME_PREFIX%", "%$PUBLIC_DIR_NAME%")
                val rows = ArrayList<Pair<Long, String>>()
                resolver.query(collection, projection, selection, args, null)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val name = c.getString(nameIdx)
                        // 二次确认名字，别只信 query 的 LIKE
                        if (!name.startsWith(PACK_NAME_PREFIX)) continue
                        rows.add(c.getLong(idIdx) to name)
                    }
                }
                if (rows.size <= KEEP_PACKS) return
                // 按文件名里的时间戳排序（cardesk-logs-yyyyMMdd-HHmmss.zip）
                rows.sortBy { it.second }
                rows.take(rows.size - KEEP_PACKS).forEach { (id, name) ->
                    // 精确删除：selection 里同时带 _id / 文件名 / 目录，三重约束
                    val delSelection =
                        "${MediaStore.Downloads._ID} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ? " +
                            "AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
                    val delArgs = arrayOf(id.toString(), name, "%$PUBLIC_DIR_NAME%")
                    val deleted = resolver.delete(collection, delSelection, delArgs)
                    Logx.d(LogTags.LOG, "清理旧诊断包", "name" to name, "deleted" to deleted)
                }
            } else {
                // Android 9 及以下：直接删文件（目录本身就是我们自己写的）
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_DIR_NAME,
                )
                val packs = dir.listFiles { f -> f.name.startsWith(PACK_NAME_PREFIX) && f.name.endsWith(".zip") }
                    ?.sortedBy { it.name }
                    ?: return
                if (packs.size <= KEEP_PACKS) return
                packs.take(packs.size - KEEP_PACKS).forEach { f ->
                    runCatching { f.delete() }
                    Logx.d(LogTags.LOG, "清理旧诊断包", "name" to f.name)
                }
            }
        }.onFailure { Logx.d(LogTags.LOG, "清理旧诊断包失败", "reason" to "${it.javaClass.simpleName}: ${it.message}") }
    }

    // ------------------------------------------------------------------ 快照

    /**
     * 运行快照：环境 / 版本 / 权限 / 车控通道 / 设置全量键值 / 日志占用。
     *
     * 用的就是 `Prefs` 那个 SharedPreferences 文件，所以**任何**设置项都会出现在里面，
     * 不需要为了打日志去改 `Prefs`。
     *
     * @param truncated 是否因为超出包体上限而截断了日志
     * @param droppedCount 被截掉的文件数
     */
    fun summary(context: Context, truncated: Boolean = false, droppedCount: Int = 0): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        sb.append("==== CarDesk 诊断快照 ====\n")
        sb.append("生成时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())).append('\n')
        sb.append("包名: ").append(app.packageName).append('\n')
        sb.append("版本: ").append(versionName(app)).append(" (code ").append(versionCode(app)).append(")\n")
        sb.append("安装时间: ").append(installTime(app)).append('\n')
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("指纹: ").append(Build.FINGERPRINT).append('\n')
        sb.append("分辨率: ").append(display(app)).append('\n')
        sb.append("日志级别: ").append(Logx.level().label).append('\n')

        sb.append("\n---- 权限 ----\n")
        for (perm in PERMISSIONS) {
            sb.append(perm.substringAfterLast('.'))
                .append(": ")
                .append(if (hasPermission(app, perm)) "已授予" else "未授予")
                .append('\n')
        }

        sb.append("\n---- 车控通道 ----\n")
        runCatching {
            sb.append("已初始化: ").append(VehicleController.initialized).append('\n')
            sb.append("当前通道: ").append(VehicleController.sourceLabel).append('\n')
            sb.append("是否回退模拟车: ").append(VehicleController.isMockFallback).append('\n')
            sb.append("最近一次写入成功: ").append(VehicleController.lastWriteOk).append('\n')
            sb.append("最近一次写入失败原因: ").append(VehicleController.lastWriteError ?: "无").append('\n')
            sb.append("最近一次写入时间戳: ").append(VehicleController.lastWriteAt).append('\n')
            runCatching { VehicleController.gateway.id }.onSuccess { sb.append("网关实现: ").append(it).append('\n') }
            runCatching { VehicleController.gateway.lastError() }
                .onSuccess { sb.append("网关最近错误: ").append(it ?: "无").append('\n') }
        }.onFailure { sb.append("车控状态读取失败: ").append(it.message).append('\n') }
        sb.append("空调状态: ")
        runCatching { sb.append(VehicleController.state.toString()) }
            .onFailure { sb.append("读取失败: ").append(it.message) }
        sb.append('\n')
        // 注意：这里**不**调用 VehicleController.selfTest()——它会真的写一次空调再写回，
        // 导出诊断包这种只读动作不该改变车况。
        // 但 是**纯读**的，可以放心用（第三十六轮新增）。
        sb.append("\n---- 车辆信号自检（只读）----\n")
        runCatching { sb.append(VehicleController.signalSelfTest()).append('\n') }
            .onFailure { sb.append("信号自检失败: ").append(it.message).append('\n') }

        sb.append("\n---- 媒体 ----\n")
        runCatching {
            val mc = MusicController.get()
            sb.append("歌名: ").append(mc.currentTitle).append('\n')
            sb.append("歌手: ").append(mc.currentArtist).append('\n')
            sb.append("时长(ms): ").append(mc.currentDurationMs).append('\n')
            sb.append("歌词状态: ").append(mc.lyricState).append('\n')
        }.onFailure { sb.append("媒体状态读取失败: ").append(it.message).append('\n') }

        // 车辆信号 / 通知使用权：这两项之前只在设置页里看得到，
        // 诊断包里没有 → 每次都要翻几百行日志才能确认「续航到底读没读到」。
        sb.append("\n---- 车辆信号 ----\n")
        runCatching { sb.append(StatusSnapshot.signalLine()).append('\n') }
            .onFailure { sb.append("车辆信号读取失败: ").append(it.message).append('\n') }
        sb.append("\n---- 通知使用权 ----\n")
        runCatching { sb.append(StatusSnapshot.notificationLine(app)).append('\n') }
            .onFailure { sb.append("通知使用权读取失败: ").append(it.message).append('\n') }

        // 定位状态：真机上「天气一直获取中」的原因有好几种
        // （AppOps 没打开 / 全局位置开关关着 / provider 没数据 / 不是 system app），
        // 之前只能翻几百行日志猜。这里直接给一行结论。
        sb.append("\n---- 定位状态 ----\n")
        runCatching { sb.append(com.cardesk.launcher.core.Permissions.describeLocationState(app)).append('\n') }
            .onFailure { sb.append("定位状态读取失败: ").append(it.message).append('\n') }

        // 天气状态：真机上「天气拿不到」的原因有几种（车机天气 SDK 在不在 /
        // 高德的位置缓存有没有 / 城市名是什么 / 上次天气缓存是什么，
        // 这一段能把它们一次性列清，不用再翻几百行日志。
        sb.append("\n---- 天气 ----\n")
        runCatching {
            sb.append("车机天气 SDK: ")
                .append(if (com.cardesk.launcher.core.CarWeatherApi.isAvailable()) "存在" else "不存在").append('\n')
            // 车机上一定能看到「这台车的天气 SDK 有哪些入口」——
            // `Class.getMethods()` 不受隐藏 API 限制，即使 invoke 调不通也拿得到。
            runCatching {
                val cls = Class.forName(com.cardesk.launcher.core.CarWeatherApi.WEATHER_API_CLASS)
                sb.append("车机天气 SDK 方法: ")
                    .append(com.cardesk.launcher.core.CarWeatherApi.describeMethods(cls)).append('\n')
            }.onFailure {
                sb.append("车机天气 SDK 方法: (类不存在 ").append(it.javaClass.simpleName).append(")\n")
            }
            sb.append("高德包名: ")
                .append(com.cardesk.launcher.core.AmapCitySource.findAmapPackage(app) ?: "(未安装)").append('\n')
            sb.append("高德位置缓存: ")
                .append(
                    com.cardesk.launcher.core.AmapCitySource.readPositionFromFile(app)?.let { "${it.first},${it.second}" }
                        ?: "(读不到)",
                ).append('\n')
            sb.append("高德城市文件: ")
                .append(com.cardesk.launcher.core.AmapCitySource.readCityFromFile(app) ?: "(读不到)").append('\n')
            sb.append("高德区域广播城市: ")
                .append(com.cardesk.launcher.core.AmapCitySource.broadcastCity ?: "(本进程还没收到)").append('\n')
            val wprefs = com.cardesk.launcher.core.Prefs(app)
            sb.append("持久化城市: ")
                .append(wprefs.weatherCity.ifBlank { "(空)" })
                .append(" 省份=").append(wprefs.weatherProvince.ifBlank { "-" })
                .append(" 区划码=").append(wprefs.weatherAreaCode.ifBlank { "-" }).append('\n')
            val fixLat = wprefs.weatherFixLat
            val fixLon = wprefs.weatherFixLon
            sb.append("持久化坐标: ")
                .append(
                    if (fixLat.isNaN() || fixLon.isNaN()) "(空)"
                    else "$fixLat,$fixLon @${wprefs.weatherFixTime}",
                ).append('\n')
            val wsp = app.getSharedPreferences("cardesk", Context.MODE_PRIVATE)
            sb.append("天气缓存 城市: ").append(wsp.getString("weather_last_city", "") ?: "").append('\n')
            sb.append("天气缓存 天气: ").append(wsp.getString("weather_last_sky", "") ?: "").append('\n')
            sb.append("天气缓存 温度: ").append(wsp.getInt("weather_last_temp", Integer.MIN_VALUE)).append('\n')
        }.onFailure { sb.append("天气状态读取失败: ").append(it.message).append('\n') }

        // 车机定位/天气能力探测（第三十一轮新增）：
        // 把这台车 AdaptAPI 的 TBox 方法、系统服务名、ContentProvider authority 全列出来。
        // 真机上「天气拿不到」时，这一行能直接告诉我们**这台车到底有什么接口**，
        // 下一轮就能按真实接口接，而不是继续猜方法名。
        sb.append("\n---- 车机定位能力探测 ----\n")
        runCatching {
            sb.append("隐藏 API 豁免: ")
                .append(com.cardesk.launcher.core.HiddenApiCompat.tryExempt()).append('\n')
            sb.append(com.cardesk.launcher.core.CarCapabilityProbe.probe(app)).append('\n')
        }.onFailure { sb.append("能力探测失败: ").append(it.message).append('\n') }

        // 氛围灯自检（这一轮新增）：
        // 「灯不亮」有好几种原因（频谱没权限 / 频谱没数据 / 车控通道没就绪 /
        // 厂商功能号按名解析不到），以前诊断包里**一件都看不到**，
        // 只能翻几百行日志猜。这里一次性列清。
        sb.append("\n---- 氛围灯 ----\n")
        runCatching {
            val analyzer = com.cardesk.launcher.core.audio.SpectrumAnalyzer.get()
            sb.append("录音权限: ")
                .append(com.cardesk.launcher.core.Permissions.describeRecordAudioState(app)).append('\n')
            sb.append("频谱状态: ").append(analyzer.state.label)
            if (analyzer.stateReason.isNotBlank()) {
                sb.append("（").append(analyzer.stateReason).append("）")
            }
            sb.append(" 来源=").append(analyzer.sourceLabel).append('\n')
            sb.append("灯珠通道: ")
                .append(com.cardesk.launcher.core.ambient.AmbientLightController.api().backendName)
                .append('\n')
            // 第七十九轮：频谱的**口径**也列出来 ——
            // 「频谱有没有起来」只看状态不够，还要看我们按什么口径在算。
            sb.append("频谱口径: 分段=")
                .append(com.cardesk.launcher.core.audio.SpectrumBands.DEFAULT_BAND_COUNT)
                .append(" 采集点数=")
                .append(
                    // 报**实际**用上的点数（期望值会被设备范围夹小 / 被拒时退回兜底），
                    // 没挂上采集器时（0）就报期望值。
                    com.cardesk.launcher.core.audio.SpectrumAnalyzer.get().captureSize
                        .takeIf { it > 0 }
                        ?: com.cardesk.launcher.core.audio.SpectrumAnalyzer.CAPTURE_SIZE,
                )
                .append(" 轮询=").append(com.cardesk.launcher.core.audio.SpectrumCapture.POLL_INTERVAL_MS)
                .append("ms 回落=").append(com.cardesk.launcher.core.audio.SpectrumAnalyzer.SMOOTH_DECAY)
                .append('\n')
            sb.append("当前颜色: ")
                .append(com.cardesk.launcher.core.ambient.AmbientLightController.color?.toHex() ?: "(无)")
                .append('\n')
            sb.append("关键常量按名解析:\n")
            for (name in AMBIENT_CONSTANTS) {
                val value = com.cardesk.launcher.vehicle.AdaptapiConstants.resolve(name)
                sb.append("  ").append(name).append(" = ")
                    .append(value?.toString() ?: "(解析不到)").append('\n')
            }
        }.onFailure { sb.append("氛围灯状态读取失败: ").append(it.message).append('\n') }

        sb.append("\n---- 设置全量键值（SharedPreferences: cardesk）----\n")
        runCatching {
            val sp = app.getSharedPreferences("cardesk", Context.MODE_PRIVATE)
            val keys = sp.all.keys.sorted()
            if (keys.isEmpty()) sb.append("(空)\n")
            for (k in keys) {
                val v = sp.all[k]
                // 第七十九轮：连**类型**一起列出来。
                //
                // `SharedPreferences.getInt()` 在键值被存成别的类型时会**直接抛 ClassCastException** ——
                // 氛围灯设置页一进来就要读 4 个整型设置键（ambient_mode / ambient_sensitivity /
                // ambient_min_brightness / ambient_max_brightness），
                // 任何一个类型不对整页就崩，而旧格式只印 `toString()`，看不出类型。
                sb.append(k).append(" = ").append(truncate(v?.toString()))
                    .append(" [").append(v?.javaClass?.simpleName ?: "null").append(']')
                    .append('\n')
            }
        }.onFailure { sb.append("读取设置失败: ").append(it.message).append('\n') }

        sb.append("\n---- 日志文件 ----\n")
        val dir = Logx.logDirectory()
        if (dir == null || !dir.exists()) {
            sb.append("(无)\n")
        } else {
            val files = dir.listFiles().orEmpty().sortedBy { it.name }
            if (files.isEmpty()) sb.append("(无)\n")
            var total = 0L
            for (f in files) {
                sb.append(f.name).append("  ").append(f.length()).append(" bytes\n")
                total += f.length()
            }
            sb.append("合计: ").append(total).append(" bytes（上限 ")
                .append(FileLogSink.MAX_TOTAL_BYTES).append(" bytes / ")
                .append(FileLogSink.MAX_FILES).append(" 份）\n")
            // 落盘降级 = 车机存储不够了，日志只剩 Logcat —— 这时要明确写出来，
            // 否则用户看到「日志很少」会以为是 App 的问题。
            if (Logx.degraded()) {
                sb.append("⚠ 日志落盘已降级：磁盘剩余空间不足，只写 Logcat 不写文件\n")
            }
            if (truncated) {
                sb.append("⚠ 诊断包内日志已截断（超出包体上限，丢弃了最老的 ")
                    .append(droppedCount).append(" 个文件）\n")
            }
        }
        return sb.toString()
    }

    private fun truncate(s: String?): String {
        if (s == null) return "null"
        return if (s.length > 300) s.take(300) + "…" else s
    }

    private fun versionName(context: Context): String = runCatching {
        val pm = context.packageManager
        pm.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(context: Context): Long = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrDefault(0L)

    private fun installTime(context: Context): String = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .format(Date(context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime))
    }.getOrDefault("?")

    private fun display(context: Context): String = runCatching {
        val dm = context.resources.displayMetrics
        "${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi"
    }.getOrDefault("?")

    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ 发布到公共目录

    private sealed class PublishResult {
        data class PublishedUri(val contentUri: Uri, val display: String) : PublishResult()
        data class Path(val path: String) : PublishResult()
        data class Fail(val reason: String) : PublishResult()
    }

    /**
     * 把 zip 放进公共目录。
     *
     * - Android 10+：走 `MediaStore.Downloads`，拿得到 `content://` Uri，可以直接分享；
     * - Android 9 及以下：直接写 `Download/CarDesk/`（需要 `WRITE_EXTERNAL_STORAGE`）。
     */
    private fun publish(context: Context, zip: File, name: String): PublishResult {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            publishViaMediaStore(context, zip, name)
        } else {
            publishViaFile(context, zip, name)
        }
    }

    private fun publishViaMediaStore(context: Context, zip: File, name: String): PublishResult = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR_NAME")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            PublishResult.Fail("公共目录写入失败（MediaStore 返回空 Uri）")
        } else {
            resolver.openOutputStream(uri)?.use { out: OutputStream -> zip.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            PublishResult.PublishedUri(uri, "Download/$PUBLIC_DIR_NAME/$name")
        }
    } catch (t: Throwable) {
        PublishResult.Fail(t.message ?: "公共目录写入异常")
    }

    private fun publishViaFile(context: Context, zip: File, name: String): PublishResult {
        if (!hasPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return PublishResult.Fail("没有存储写入权限（Android 9 及以下需要 WRITE_EXTERNAL_STORAGE）")
        }
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                PUBLIC_DIR_NAME,
            )
            if (!dir.exists() && !dir.mkdirs()) {
                return PublishResult.Fail("无法创建目录 ${dir.absolutePath}")
            }
            val out = File(dir, name)
            zip.inputStream().use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
            PublishResult.Path(out.absolutePath)
        } catch (t: Throwable) {
            PublishResult.Fail(t.message ?: "公共目录写入异常")
        }
    }
}
