package com.cardesk.launcher.core.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * OEM（Ecarx / IP753）原车 **MediaCenter** 注册桥（第八十七轮，从 UNI `OfficialMediaBridge` 移植）。
 *
 * ## 为什么需要它
 *
 * 原车的仪表 / 方向盘方控 / DIM 音源选择**不走 Android `MediaSession`**，
 * 走的是 `ecarx.xsf.mediacenter` 的私有 Binder。我们接管的网易云 / QQ 音乐
 * 如果不注册进去，就会出现：
 * - 仪表上的歌名停在原车自带播放器（甚至空着）；
 * - 方向盘切歌 / 播放暂停没反应（原车把按键给了自己的音源）。
 *
 * 所以我们**冒充一个在线音乐源**注册进去：`registerMusic(IMusicClient)` 拿令牌，
 * 之后 `updateMusicPlaybackState` / `updatePlaylist` / `updateCurrentProgress` 把歌推上去，
 * 同时 `IMusicClient` 的 `onPlay/onPause/onNext/onPrevious` 回调再把方控动作转回 cardesk。
 *
 * ## 三条硬约束（都是真机/ANR 教训）
 *
 * 1. **只能接管三方在线播放器**（[MediaBridgePolicy.isEligible]）。
 *    原车收音机 / USB / 蓝牙有它们自己的 owner，我们不碰 —— UNI 注释：
 *    "Never pose as the original radio/BT/USB owner"。
 * 2. **Binder 调用一律走单线程 worker**（[worker]）。
 *    `registerMusic` / `updateMusicPlaybackState` 这些是同步 Binder 调用，在主线程上调过
 *    就会 ANR（UNI 注释里专门写了这条）。
 * 3. **同步 getter 只读不可变缓存 [Sample]**，绝不阻塞 UI 线程等 worker。
 *    原车会**同步**回调 `IMusicClient` / `IMusicPlaybackInfo` 的 getter，
 *    如果在回调里再去等 worker，立刻互锁（原车 Binder 线程 ↔ 我们的 worker）。
 *
 * ## 优雅降级
 *
 * 没有原车 SDK（`com.ecarx.sdk.mediacenter` 类找不到）、没有
 * `ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION` 权限、服务绑不上 ——
 * 全部走 `runCatching` / 记录 [reason] 后返回，**不抛异常、不闪退**。
 * 普通手机上这一路永远 `registered=false`，其它功能不受影响。
 *
 * 设置页开关：`session_bridge_enabled`（默认 **关**，见 `MusicSessionTracker.start`）。
 */
object OfficialMediaBridge {

    /** 原车方控回调 → cardesk 的控制命令。返回是否真的下发了命令。 */
    interface Commands {
        fun send(action: String, source: String): Boolean
    }

    // ---------------------------------------------------------------- 原车常量

    private const val SDK_PACKAGE = "com.ecarx.sdk.mediacenter"
    private const val PLUGIN_PACKAGE = "com.ecarx.systemui.plugin"
    private const val PERMISSION = "ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION"
    private const val SERVICE_ACTION = "ecarx.xsf.MEDIA_CENTER_SERVICE"
    private const val SERVICE_PACKAGE = "ecarx.xsf.mediacenter"
    private const val SERVICE_CLASS = "ecarx.xsf.mediacenter.MediaCenterService"
    private const val SERVICE_INTERFACE = "ecarx.xsf.mediacenter.IMediaCenterSvc"

    /** 在线音乐源类型 = 6（与 [MediaBridgePolicy.ONLINE_SOURCE_TYPE] 同源）。 */
    private const val ONLINE = MediaBridgePolicy.ONLINE_SOURCE_TYPE

    /** 选中会话消失了，先等 2.5s 再清空来源（防「暂停时会话销毁」把仪表闪一下）。 */
    private const val EMPTY_SOURCE_DELAY_MS = 2_500L

    /** 进度上报最短间隔（原车不需要 200ms 那么勤，太密会占满它的 Binder 线程）。 */
    private const val PROGRESS_INTERVAL_MS = 1_000L

    /**
     * 主线程 Handler —— **延迟初始化**。
     *
     * 为什么不用 `Handler(Looper.getMainLooper())` 直接初始化：`object` 的初始化块在任何
     * 首次访问时执行（包括 `snapshot()` / `stop()`），而 JVM 单测里没有 Looper 时
     * `Looper.getMainLooper()`/`Handler` 会抛 Stub 异常，进而变成 `ExceptionInInitializerError`
     * 把整个类废掉。真实运行路径（[start]/[update]/[dispatch]）一定会先有主线程，
     * 延迟到那时再建，`stop()`/`snapshot()` 在从未 bind 过时纯读 volatile 字段，不碰它。
     */
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 单线程 worker：**所有** Binder 调用都在这里串行执行。
     *
     * 用 `lazy` 常驻（不随 start/stop 创建销毁）：UNI 也是构造时建一个、
     * 永不 shutdown。线程设 daemon，车机上 App 被杀时不至于拖着进程。
     */
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "cardesk-oem-media").apply { isDaemon = true }
        }
    }

    // ---------------------------------------------------------------- 运行态

    @Volatile
    private var context: Context? = null

    @Volatile
    private var commands: Commands? = null

    @Volatile
    private var changed: (() -> Unit)? = null

    @Volatile
    private var running = false

    @Volatile
    private var registered = false

    @Volatile
    private var playbackAccepted = false

    @Volatile
    private var playClaimed = false

    @Volatile
    private var apiReady = false

    @Volatile
    private var bound = false

    @Volatile
    private var reason = "未连接原车媒体中心"

    @Volatile
    private var callbackCount = 0

    @Volatile
    private var lastCallback = ""

    /** 代数号：`stop()` 时 +1，让在飞的旧回调 / 旧代理全部失效。 */
    @Volatile
    private var generation = 0

    /** 不可变采样缓存：同步 getter 只读它（绝不等 worker）。 */
    @Volatile
    private var sample = Sample.EMPTY

    // ---------------------------------------------------------------- 以下只在 worker 线程访问

    private var api: Any? = null
    private var apiType: Class<*>? = null
    private var client: Any? = null
    private var playback: Any? = null
    private var mediaInfo: Any? = null
    private var wireClient: Any? = null
    private var wirePlayback: Any? = null
    private var mediaInfoType: Class<*>? = null
    private var clientType: Class<*>? = null
    private var playbackType: Class<*>? = null
    private var token: Any? = null
    private var connection: ServiceConnection? = null
    private var initialized = false
    private var lastSource = ""
    private var lastPlaying = false
    private var lastTrack = ""
    private var lastProgressAt = 0L

    // ---------------------------------------------------------------- 主线程侧

    private var waitingForSource = false

    /** 防误清空：选中会话消失后延迟清来源（暂停时车机 App 会销毁会话）。 */
    private val emptySource = Runnable {
        waitingForSource = false
        sample = Sample.EMPTY
        if (running) worker.execute { publish(Sample.EMPTY) }
    }

    /** 桥是否在运行（tracker 用它决定要不要每 tick 采样）。 */
    val isRunning: Boolean get() = running

    /**
     * 启动桥（幂等）。
     *
     * @param context 任意 Context（内部取 applicationContext）
     * @param commands 原车方控回调的落点（tracker 提供）
     * @param changed 原车回调之后的通知（让 UI 刷新播放态）
     */
    fun start(context: Context, commands: Commands, changed: () -> Unit) {
        if (running) return
        val app = context.applicationContext ?: context
        this.context = app
        this.commands = commands
        this.changed = changed
        this.reason = "正在连接原车媒体中心"
        this.running = true
        Logx.i(LogTags.MEDIA, "OEM 媒体桥启动", "sdk" to SDK_PACKAGE)
        worker.execute {
            // 兜底：initialize()/publish() 内部已各有一层 catch(Exception|LinkageError)，
            // 这里再包一层 runCatching 是防「SDK 的 Error 子类 / 反射抛 VerifyError」之类的漏网，
            // 让 worker 线程永远不会带着未捕获异常死掉（线程池会重开线程，但会丢任务）。
            runCatching {
                initialize()
                publish(sample)
            }.onFailure { onInitFailed(it) }
        }
    }

    /** 停止桥（幂等）：解绑服务、注销注册、清空来源。 */
    fun stop() {
        if (!running) return
        running = false
        generation++
        cancelEmptySource()
        waitingForSource = false
        sample = Sample.EMPTY
        worker.execute {
            release()
            val conn = connection
            if (bound && conn != null) {
                runCatching { context?.unbindService(conn) }
                    .onFailure { Logx.w(LogTags.MEDIA, "OEM 媒体桥解绑失败", it) }
            }
            bound = false
            apiReady = false
            initialized = false
            api = null
            connection = null
            registered = false
            playbackAccepted = false
            playClaimed = false
            token = null
            reason = "已停止"
        }
        Logx.i(LogTags.MEDIA, "OEM 媒体桥停止")
    }

    /**
     * 把「当前选中的三方会话」同步给原车（在会话/播放态变化时调）。
     *
     * `controller == null` 且之前有可用来源时不立刻清空，而是 2.5s 后清 ——
     * 车机音乐 App 暂停时常把会话销毁，立刻清空会让仪表上的歌名闪一下变空。
     */
    fun update(controller: MediaController?) {
        val ctx = context ?: return
        val next = Sample.from(ctx, controller)
        if (!next.available && running && sample.available) {
            if (!waitingForSource) {
                waitingForSource = true
                runCatching { main.postDelayed(emptySource, EMPTY_SOURCE_DELAY_MS) }
            }
            return
        }
        cancelEmptySource()
        waitingForSource = false
        sample = next
        if (running) worker.execute { publish(next) }
    }

    /**
     * 取消「延时清空来源」。
     *
     * 单独抽出来 + runCatching：`main` 是 lazy 的，一旦在没有 Looper 的环境（JVM 单测）
     * 被意外碰到，这里吞掉异常即可 —— 诊断路径不该因为一个 Runnable 而崩。
     */
    private fun cancelEmptySource() {
        runCatching { main.removeCallbacks(emptySource) }
    }

    /**
     * 诊断快照：`String -> 基础类型` 的**扁平** map（设置页 / emu-verify 报告用）。
     *
     * 键名见 [MediaBridgePolicy.snapshotKeywords]。全部是 Boolean/Int/String，
     * 可以直接 `toString()` 或塞进 JSON。
     */
    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "registered" to registered,
        "playbackAccepted" to playbackAccepted,
        "playClaimed" to playClaimed,
        "bound" to bound,
        "sourceType" to if (sample.available) ONLINE else -1,
        "callbackCount" to callbackCount,
        "lastCallback" to lastCallback,
        "reason" to reason,
    )

    // ---------------------------------------------------------------- 初始化（worker）

    private fun initialize() {
        if (initialized || !running) return
        initialized = true
        val ctx = context ?: run {
            reason = "没有上下文"
            return
        }
        // 权限检查本身也可能被某些魔改 ROM 抛 SecurityException，包在 runCatching 里：
        // 拿不到权限只是「桥不工作」，绝不能变成闪退。
        val granted = runCatching {
            ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            reason = "原车媒体中心权限未授予"
            Logx.w(LogTags.MEDIA, "OEM 媒体桥：权限未授予，跳过注册", "permission" to PERMISSION)
            return
        }
        try {
            var loader: ClassLoader = ctx.classLoader
            var apiClass: Class<*>? = null
            try {
                apiClass = Class.forName("$SDK_PACKAGE.MediaCenterAPI", true, loader)
            } catch (e: ClassNotFoundException) {
                // 车机上 SDK 不是装在我们进程里，而是挂在原车 SystemUI 插件包里
                loader = ctx.createPackageContext(
                    PLUGIN_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                ).classLoader
                apiClass = Class.forName("$SDK_PACKAGE.MediaCenterAPI", true, loader)
            }
            apiType = apiClass
            // apiClass 是 nullable（catch 分支里重新赋值过），必须显式兜底到插件 loader
            val sdkLoader: ClassLoader = apiClass?.classLoader ?: loader
            clientType = Class.forName("$SDK_PACKAGE.IMusicClient", true, sdkLoader)
            playbackType = Class.forName("$SDK_PACKAGE.IMusicPlaybackInfo", true, sdkLoader)
            mediaInfoType = Class.forName("$SDK_PACKAGE.IMediaInfo", true, sdkLoader)

            val session = generation
            val pType = playbackType ?: return
            val iType = mediaInfoType ?: return
            val cType = clientType ?: return
            // 三个动态代理：原车会**同步**回调它们的 getter，只读 sample（约束 3）
            playback = Proxy.newProxyInstance(sdkLoader, arrayOf(pType), InvocationHandler { p, m, a ->
                if (session == generation) playbackValue(p, m, a) else defaultValue(m.returnType)
            })
            mediaInfo = Proxy.newProxyInstance(sdkLoader, arrayOf(iType), InvocationHandler { p, m, a ->
                if (session == generation) playbackValue(p, m, a) else defaultValue(m.returnType)
            })
            client = Proxy.newProxyInstance(sdkLoader, arrayOf(cType), InvocationHandler { p, m, a ->
                if (session == generation) clientValue(p, m, a) else defaultValue(m.returnType)
            })
            // IP753 把 SDK 里 legacy IMusicClient 包装类的构造器声明成 package-private
            //（包装类本身是 public），所以必须 getDeclaredConstructor + setAccessible。
            val wrapClient = Class.forName("$SDK_PACKAGE.MusicClientWrapper", true, sdkLoader)
                .getDeclaredConstructor(cType)
            wrapClient.isAccessible = true
            wireClient = wrapClient.newInstance(client)
            wirePlayback = Class.forName("$SDK_PACKAGE.PlaybackInfoWrapper", true, sdkLoader)
                .getConstructor(pType)
                .newInstance(playback)

            val serviceLoader = sdkLoader
            // 先落到局部 val：`connection` 是可变属性，直接当参数用 Kotlin 不允许智能转换
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    worker.execute {
                        if (!running || session != generation) return@execute
                        fun fail(prefix: String, t: Throwable) {
                            reason = "$prefix：" + root(t).javaClass.simpleName
                            Logx.w(LogTags.MEDIA, reason, t)
                            notifyChanged()
                        }
                        try {
                            apiType = Class.forName(SERVICE_INTERFACE, true, serviceLoader)
                            api = Class.forName("$SERVICE_INTERFACE\$Stub", true, serviceLoader)
                                .getMethod("asInterface", IBinder::class.java)
                                .invoke(null, binder)
                            apiReady = api != null
                            register()
                            publish(sample)
                        } catch (e: Exception) {
                            fail("原车媒体Binder连接失败", e)
                        } catch (e: LinkageError) {
                            fail("原车媒体Binder连接失败", e)
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (session != generation) return
                    apiReady = false
                    registered = false
                    playbackAccepted = false
                    playClaimed = false
                    token = null
                    reason = "原车媒体服务已断开"
                    notifyChanged()
                }
            }
            connection = conn
            reason = "正在连接原车媒体中心"
            bound = ctx.bindService(
                Intent(SERVICE_ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)),
                conn,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                reason = "原车媒体中心未接受服务绑定"
                Logx.w(LogTags.MEDIA, "OEM 媒体桥：服务绑定失败", "component" to "$SERVICE_PACKAGE/$SERVICE_CLASS")
                notifyChanged()
            } else {
                Logx.i(LogTags.MEDIA, "OEM 媒体桥：已请求绑定", "component" to "$SERVICE_PACKAGE/$SERVICE_CLASS")
            }
        } catch (e: Exception) {
            onInitFailed(e)
        } catch (e: LinkageError) {
            onInitFailed(e)
        }
    }

    private fun onInitFailed(t: Throwable) {
        reason = "原车媒体接入失败：" + root(t).javaClass.simpleName
        Logx.w(LogTags.MEDIA, reason, t)
        notifyChanged()
    }

    /** 注册成在线音乐源（幂等）。 */
    private fun register() {
        if (!running || !sample.available || registered || api == null) return
        try {
            token = invoke("registerMusic", wireClient)
            registered = token != null
            reason = if (registered) "" else "原车媒体注册未返回令牌"
            if (registered) {
                invoke("updateMediaSourceTypeList", token, intArrayOf(ONLINE))
            }
            Logx.i(LogTags.MEDIA, "OEM 媒体桥注册结果", "registered" to registered)
        } catch (e: Exception) {
            onPublishFailed("原车媒体注册失败", e)
        } catch (e: LinkageError) {
            onPublishFailed("原车媒体注册失败", e)
        }
        notifyChanged()
    }

    // ---------------------------------------------------------------- 同步（worker）

    private fun publish(next: Sample) {
        if (!running) return
        if (!next.available) {
            if (registered) release()
            return
        }
        if (!initialized) initialize()
        if (!registered && apiReady) register()
        if (!registered) return
        try {
            val stateChanged = next.playing != lastPlaying
            val sourceChanged = next.source != lastSource
            val acquire = MediaBridgePolicy.shouldAcquire(next.playing, stateChanged, sourceChanged)
            lastSource = next.source
            lastPlaying = next.playing
            if (acquire) {
                playClaimed = (invoke("requestPlay", token) as? Boolean) == true
                if (playClaimed) invoke("updateCurrentSourceType", token, ONLINE)
            }
            val track = next.source + "\n" + next.title + "\n" + next.artist + "\n" + next.duration
            val trackChanged = track != lastTrack
            if (trackChanged) {
                // MediaBeanHelper 也是 package-private 静态工具（同 IP753 的老 API）
                val cType = clientType
                if (cType != null) {
                    val wrap = Class.forName("$SDK_PACKAGE.MediaBeanHelper", true, cType.classLoader)
                        .getDeclaredMethod("getIMediaList", List::class.java)
                    wrap.isAccessible = true
                    val queue = wrap.invoke(null, Collections.singletonList(mediaInfo))
                    invoke("updatePlaylist", token, ONLINE, queue)
                    lastTrack = track
                }
            }
            if (trackChanged || stateChanged || acquire) {
                playbackAccepted = (invoke("updateMusicPlaybackState", token, wirePlayback) as? Boolean) == true
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                lastProgressAt = now
                invoke("updateCurrentProgress", token, next.position)
            }
            reason = if (playbackAccepted) "" else "原车媒体未接受播放信息"
            Logx.d(
                LogTags.MEDIA, "OEM 媒体桥同步",
                "accepted" to playbackAccepted, "claimed" to playClaimed,
                "playing" to next.playing, "title" to next.title,
            )
        } catch (e: Exception) {
            onPublishFailed("原车媒体同步失败", e)
        } catch (e: LinkageError) {
            onPublishFailed("原车媒体同步失败", e)
        }
    }

    private fun onPublishFailed(prefix: String, t: Throwable) {
        reason = "$prefix：" + root(t).javaClass.simpleName
        Logx.w(LogTags.MEDIA, reason, t)
    }

    // ---------------------------------------------------------------- 原车回调（读缓存）

    /** `IMusicClient` 的 getter / 方控回调（原车会**同步**调进来，只读 [sample]）。 */
    private fun clientValue(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        objectMethod(proxy, method, args)?.let { return it }
        val current = sample
        return when (method.name) {
            "getCurrentProgress" -> current.position
            "getCurrentSourceType" -> if (current.available) ONLINE else -1
            "getMediaSourceTypeList" -> intArrayOf(ONLINE)
            "getMusicPlaybackInfo" -> playback
            // 标准 MediaSession 只暴露「当前这一条」，不是播放器的完整队列 ——
            // 只给原车这一条，别假装我们知道整个列表（UNI 注释同款理由）。
            "getPlaylist" -> if (
                current.available && args != null && args.isNotEmpty() &&
                (args[0] as? Number)?.toInt() == ONLINE
            ) {
                Collections.singletonList(mediaInfo)
            } else {
                Collections.emptyList<Any>()
            }
            "onNext" -> dispatch("next")
            "onPrevious" -> dispatch("previous")
            "onPlay" -> dispatch("play")
            "onPause" -> dispatch("pause")
            "onSourceSelected" -> (args != null && args.isNotEmpty() &&
                (args[0] as? Number)?.toInt() == ONLINE && dispatch("play"))
            else -> defaultValue(method.returnType)
        }
    }

    /** `IMusicPlaybackInfo` / `IMediaInfo` 的 getter（同样是同步、只读缓存）。 */
    private fun playbackValue(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        objectMethod(proxy, method, args)?.let { return it }
        val s = sample
        return when (method.name) {
            "getTitle" -> s.title
            "getArtist" -> s.artist
            "getAlbum" -> s.album
            "getDuration" -> s.duration
            "getPlayingItemPositionInQueue" -> 0
            "getSourceType" -> ONLINE
            "getPlaybackStatus" -> MediaBridgePolicy.wirePlayback(s.playing)
            "getLaunchIntent" -> s.launch
            "getCurrentLyricSentence", "getLyricContent", "getRadioFrequency", "getRadioStationName" -> ""
            else -> defaultValue(method.returnType)
        }
    }

    /**
     * 原车方控 / 仪表动作 → cardesk（第八十七轮）。
     *
     * 回主线程再执行：`setPlaying` / `next` 会摸 `MediaController`、打日志、
     * 甚至重排 Handler —— 这些都在主线程的状态机上，原车 Binder 线程直接调会踩竞态。
     * 派发时再校验一次 running / 代数 / 来源，防止「已经切走的会话」被旧回调误伤。
     */
    private fun dispatch(action: String): Boolean {
        if (!running || !sample.available) return false
        callbackCount++
        lastCallback = action
        Logx.i(LogTags.MEDIA, "OEM 方控回调", "action" to action, "source" to sample.source)
        val source = sample.source
        val session = generation
        main.post {
            if (running && session == generation && sample.available && source == sample.source) {
                commands?.send(action, source)
                changed?.invoke()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 收尾

    private fun release() {
        try {
            if (api != null && token != null) invoke("unregister", token)
        } catch (e: Exception) {
            Logx.d(LogTags.MEDIA, "OEM 媒体桥注销失败（忽略）", "err" to root(e).javaClass.simpleName)
        } catch (e: LinkageError) {
            Logx.d(LogTags.MEDIA, "OEM 媒体桥注销失败（忽略）", "err" to root(e).javaClass.simpleName)
        }
        token = null
        registered = false
        playbackAccepted = false
        playClaimed = false
        lastSource = ""
        lastTrack = ""
        lastPlaying = false
    }

    private fun notifyChanged() {
        val cb = changed ?: return
        main.post { cb() }
    }

    // ---------------------------------------------------------------- 反射小工具

    /**
     * 按「方法名 + 参数个数 + 参数类型」在 [apiType] 上找方法并调用。
     *
     * 为什么不用 `getMethod` 固定签名：不同 IP753 固件的 `IMediaCenterSvc` 参数顺序 /
     * 数组类型有出入，UNI 用这套模糊匹配活得很好。基本类型的装箱要单独放过
     * （反射拿到的是 `int.class`，传进来的是 `Integer`）。
     */
    private fun invoke(name: String, vararg args: Any?): Any? {
        val type = apiType ?: throw NoSuchMethodException(name)
        for (method in type.methods) {
            if (method.name != name || method.parameterTypes.size != args.size) continue
            val types = method.parameterTypes
            var match = true
            for (i in types.indices) {
                val arg = args[i] ?: continue
                if (types[i] == Int::class.javaPrimitiveType && arg is Int) continue
                if (types[i] == Long::class.javaPrimitiveType && arg is Long) continue
                if (!types[i].isInstance(arg)) {
                    match = false
                    break
                }
            }
            if (match) return method.invoke(api, *args)
        }
        throw NoSuchMethodException(name)
    }

    /** 反射调用的返回值兜底（代理方法必须返回一个合法值，否则原车侧会 NPE）。 */
    private fun defaultValue(type: Class<*>): Any? = when {
        type == Boolean::class.javaPrimitiveType -> false
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    /** 代理的 Object 方法（原车偶尔会拿它做 map key / 判重）。 */
    private fun objectMethod(proxy: Any, method: Method, args: Array<Any?>?): Any? = when (method.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> args != null && args.isNotEmpty() && proxy === args[0]
        "toString" -> "CarDesk official media client"
        else -> null
    }

    private fun root(t: Throwable): Throwable {
        var e = t
        while (e.cause != null && e.cause !== e) e = e.cause!!
        return e
    }

    /**
     * 给原车看的**不可变**采样（一次采样 = 一个快照，读到一半不会被改）。
     *
     * 只接受 [MediaBridgePolicy.isEligible] 的三方在线播放器；其它来源（收音机 / USB /
     * 蓝牙 / 系统多媒体）一律 [EMPTY] —— 我们不冒充它们的 owner。
     */
    private data class Sample(
        val available: Boolean,
        val playing: Boolean,
        val source: String,
        val title: String,
        val artist: String,
        val album: String,
        val position: Long,
        val duration: Long,
        val launch: PendingIntent?,
    ) {
        companion object {
            val EMPTY = Sample(false, false, "", "", "", "", 0L, 0L, null)

            fun from(context: Context?, controller: MediaController?): Sample {
                val ctx = context ?: return EMPTY
                val c = controller ?: return EMPTY
                val pkg = c.packageName
                if (!MediaBridgePolicy.isEligible(pkg)) return EMPTY
                return runCatching {
                    val meta = c.metadata
                    val state = c.playbackState
                    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    val launch = intent?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        PendingIntent.getActivity(ctx, 1, it, PendingIntent.FLAG_UPDATE_CURRENT)
                    }
                    Sample(
                        available = state != null,
                        playing = state?.state == PlaybackState.STATE_PLAYING,
                        source = pkg,
                        title = text(meta, MediaMetadata.METADATA_KEY_TITLE),
                        artist = text(meta, MediaMetadata.METADATA_KEY_ARTIST),
                        album = text(meta, MediaMetadata.METADATA_KEY_ALBUM),
                        position = maxOf(0L, state?.position ?: 0L),
                        duration = maxOf(0L, meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L),
                        launch = launch,
                    )
                }.getOrElse {
                    // 采样失败不能把桥打挂：退回 EMPTY，下一 tick 再来
                    EMPTY
                }
            }

            private fun text(meta: MediaMetadata?, key: String): String =
                meta?.getString(key).orEmpty()
        }
    }
}
