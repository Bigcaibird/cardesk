package com.cardesk.launcher.core.car3d

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.cardesk.launcher.core.CarModels
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.util.concurrent.locks.ReentrantLock

/**
 * 车模资源自动补齐：**本地缺哪个车型的车模，就在后台悄悄补齐**。
 *
 * ## 为什么要有这一层
 *
 * [CarModelRemote] 只会「用户点了才同步」，但车模是**每个车型都要用的内容资产**：
 * 车机刚刷完机 / 用户清了数据之后本地一个车模都没有，桌面就一直回退到自绘车模，
 * 而用户通常不会主动去设置页点「检查更新」。所以在启动时补一刀，但**只在真的缺的时候**：
 *
 * 1. 先读本地版本表（`filesDir/car3d-models/versions.json`，即
 *    [CarModelRemote.installedVersions]）。若已包含 [expectedDirs] 的全部目录
 *    ⇒ **一次网络请求都不发**，记一条日志就结束；下次启动同理；
 * 2. 缺目录 ⇒ 后台调一次 [CarModelRemote.sync]（`force = true`）。这一步会把远端
 *    资源包里**所有**车型都装上，不是只装当前选中的那个 —— 「补齐」的语义是整机可用，
 *    用户之后切车型不该再等一次下载；
 * 3. 每次尝试（成功或失败）结束后都**重新查一遍本地是否齐全**：齐全就结束；
 *    还不齐（网络失败 / 远端资源包里暂时没有这个车型）就**静默退避** ——
 *    每 [RETRY_INTERVAL_MS] 再试一次，只写 WARN 日志，不弹任何 UI、
 *    不打断用户，直到齐全或 [stop] 被调用。**终止条件是「本地齐全」，不是「这次 sync 有没有下载」**；
 * 4. 本地从「不齐」变成「齐」时，把本次 [CarModelRemote.SyncResult.updatedDirs] 的
 *    key（可能为空列表）回**主线程**交给 [onUpdated]，**只回调一次**，
 *    由宿主决定要不要刷新已缓存的页面（解码缓存失效 + 重绘）。
 *
 * ## 线程模型
 *
 * 网络与磁盘 IO **全部在一个后台线程**（`car-model-sync`，daemon）里做：
 * [start] 只负责把线程拉起来，自己不做任何 IO，所以从主线程调用是安全的。
 * 重试等待用 [ReentrantLock] + [java.util.concurrent.locks.Condition]，
 * [stop] 把它唤醒后等待**立刻**结束。
 *
 * 唯一的例外是**正在进行的那一次网络调用**：它没法从外部安全中断，
 * 线程会在这一轮返回后自行退出（结果按过期丢弃：不回调、不重试）。
 * 同理，[stop] 之后到线程真正退出之前 [isRunning] 仍可能是 true。
 *
 * 同一时刻最多一份 worker：重试等待期间重复 [start] 是空操作。
 *
 * ## 调用方
 *
 * ```kotlin
 * CarModelAutoSync.onUpdated = { dirs -> car3dPages.dropMemoryCache() }
 * CarModelAutoSync.start(context)   // Application.onCreate / 桌面 onCreate 均可
 * // 退出时（可选）
 * CarModelAutoSync.stop()
 * ```
 */
object CarModelAutoSync {

    /** 失败后重试间隔（毫秒）：5 分钟一次。 */
    const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

    /** worker 线程名（真机上 `adb shell ps -T` 能认出来）。 */
    private const val THREAD_NAME = "car-model-sync"

    /**
     * 本地从「缺」变成「齐」之后回调（**主线程**调用，整个进程生命周期内最多一次）；
     * 参数是触发这次补齐的 [CarModelRemote.SyncResult.updatedDirs] 的 key，
     * 如 `lynk03`；本次一次都没下载时是**空列表**（但回调照样发生，
     * 宿主收到空列表也应该让解码缓存失效 —— 因为本地可用的车模集合已经变了）。
     *
     * 不会拿「本来就是最新的」来打扰；[stop] 之后不会再投递（此时界面通常已经拆了）。
     * 回调抛异常只记日志，不影响补齐流程。
     */
    @Volatile
    var onUpdated: ((List<String>) -> Unit)? = null

    /**
     * 串行化真正的一次 [CarModelRemote.sync]。
     *
     * 正常情况下只有一份 worker，用不到它；但 `stop()` 之后紧接着 `start()` 时，
     * 旧线程可能还卡在上一轮网络调用里，没有这把锁就会两个线程同时往
     * `car3d-models/` 里写（同名 `.part` / `.tmp` 会互相踩）。这里让新的等旧的收尾。
     */
    private val syncLock = Any()

    /** 保护 [worker] / [generation]，并用它的条件变量做「等 5 分钟」。 */
    private val lock = ReentrantLock()
    private val retrySignal = lock.newCondition()

    /** 当前 worker（null = 没在跑）。只在 [lock] 内改写。 */
    @Volatile
    private var worker: Thread? = null

    /** [worker] 所属的纪元；和 [generation] 不一致就说明这线程已经被 [stop] 作废、正在退出。 */
    @Volatile
    private var workerGeneration = 0

    /** 每轮 start/stop 递增；worker 靠它判断自己是否已经过期（过期 = 什么都不许再做）。 */
    @Volatile
    private var generation = 0

    // ------------------------------------------------------------------ 公开入口

    /**
     * 开始自动补齐。**幂等**：已经有一份在跑（包括正在等 5 分钟重试）时直接返回。
     *
     * 不做任何 IO：本地是否齐全的检查也在 worker 线程里做，所以主线程调用是安全的。
     *
     * 例外：[stop] 之后紧接着 [start] 时，旧的 worker 可能还卡在上一轮网络调用里
     * （它已经过期、会自行退出）。这种情况**允许**马上起新的一轮，否则「先停再开」
     * 会被旧线程拖住、甚至因为旧线程随后清掉 [worker] 而永远不再启动。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        val launched: Boolean
        lock.lock()
        try {
            if (worker != null && workerGeneration == generation) {
                launched = false
            } else {
                // 递增 generation，让可能还没退干净的上一轮线程彻底过期
                generation++
                val gen = generation
                val thread = Thread({ runLoop(app, gen) }, THREAD_NAME)
                thread.isDaemon = true
                worker = thread
                workerGeneration = gen
                thread.start()
                launched = true
            }
        } finally {
            lock.unlock()
        }
        if (launched) {
            Logx.d(LogTags.CAR3D, "车模自动补齐已启动")
        }
    }

    /**
     * 停止自动补齐（幂等）。
     *
     * 正在等重试的那一轮会被**立即**唤醒并退出；正在进行的网络调用无法安全中断，
     * 线程会在它返回后退出（那一轮的结果作废）。不阻塞调用方，可以从主线程调。
     */
    fun stop() {
        val signalled: Boolean
        lock.lock()
        try {
            if (worker == null) {
                signalled = false
            } else {
                generation++
                retrySignal.signalAll()
                signalled = true
            }
        } finally {
            lock.unlock()
        }
        if (signalled) {
            Logx.d(LogTags.CAR3D, "车模自动补齐正在停止")
        }
    }

    /**
     * 是否还有 worker 在跑（检查 / 同步 / 等重试都算）。
     *
     * 补充：刚 [stop] 的那一小段时间（等正在进行的网络调用返回）仍然返回 true。
     */
    fun isRunning(): Boolean = worker != null

    /**
     * 立刻同步一次（**阻塞**，必须在后台线程调用），给设置页的「检查更新」按钮用。
     *
     * 与自动补齐共用 [syncLock]：按钮和启动补齐撞在一起时不会两个线程同时往
     * `car3d-models/` 里写同名 `.part`。`force = true` 绕过索引缓存，刚发布的
     * 资源包点了就能看到。
     */
    fun syncNow(context: Context): CarModelRemote.SyncResult {
        val app = context.applicationContext
        return synchronized(syncLock) { CarModelRemote.sync(app, force = true) }
    }

    /**
     * 本机需要哪些车模资产目录：全部车型 → [CarModels.assetDir] 去重。
     *
     * 现在的结果是 `{lynk01, lynk03, lynk05}`（01/02/02HB 共用 `lynk01`）。
     * 只依赖车型表，不碰 Android API，纯 JVM 单测可以直接断言。
     */
    fun expectedDirs(): Set<String> {
        val dirs = LinkedHashSet<String>(CarModels.MODEL_IDS.size)
        for (modelId in CarModels.MODEL_IDS) {
            CarModels.assetDir(modelId)?.let { dirs.add(it) }
        }
        return dirs
    }

    /**
     * 本地还缺哪些目录（纯函数，给单测和日志用）。
     *
     * 顺序跟随 [CarModels.MODEL_IDS]（`lynk01` → `lynk03` → `lynk05`），
     * 与 [expectedDirs] 的遍历顺序一致，日志与断言都稳定。
     */
    internal fun missingDirs(installed: Set<String>): List<String> =
        expectedDirs().filterNot { it in installed }

    // ------------------------------------------------------------------ worker

    /** worker 主循环：检查本地 → 缺则同步 → 失败等 5 分钟再来，直到齐全/成功/被停止。 */
    private fun runLoop(app: Context, gen: Int) {
        // 后台优先级：下载 + 解压不能让桌面掉帧
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            var attempt = 0
            while (isCurrent(gen)) {
                // 1) 本地是否齐全。读的是几 KB 的版本表，放在 worker 线程做（主线程不做 IO）。
                val missing = missingDirs(installedDirs(app))
                if (missing.isEmpty()) {
                    Logx.i(
                        LogTags.CAR3D,
                        "本地车模齐全，不发起网络请求",
                        "dirs" to expectedDirs().joinToString(","),
                        "attempt" to attempt,
                    )
                    return
                }

                attempt++
                if (attempt == 1) {
                    Logx.i(
                        LogTags.CAR3D,
                        "本地车模不齐全，开始后台补齐",
                        "missing" to missing.joinToString(","),
                    )
                }

                // 2) 一次性把远端资源包里所有车型装上。成败都不直接决定停止与否 ——
                //    唯一的终止条件是「装完之后本地是不是真的齐了」。
                val result = syncOnce(app, gen, attempt)
                if (!isCurrent(gen)) {
                    Logx.d(LogTags.CAR3D, "补齐已停止，丢弃本轮结果", "attempt" to attempt)
                    return
                }
                val stillMissing = missingDirs(installedDirs(app))
                if (stillMissing.isEmpty()) {
                    onCompleted(gen, result)
                    return
                }

                // 3) 还不齐（网络失败 / 某个车型装失败 / 远端资源包暂时没收录这个车型）
                //    ⇒ 静默退避，5 分钟后再整轮来一次；只记 WARN，不打扰用户。
                val clean = result != null && result.error == null && result.failedDirs.isEmpty()
                Logx.w(
                    LogTags.CAR3D,
                    "车模仍未补齐，${RETRY_INTERVAL_MS / 60_000} 分钟后重试",
                    "attempt" to attempt,
                    "missing" to stillMissing.joinToString(","),
                    "clean" to clean,
                    "reason" to (result?.error ?: CarModelRemote.lastError ?: "无"),
                )
                awaitRetry(gen)
            }
        } catch (t: Throwable) {
            // 兜底：worker 不能因为意外异常静默消失，下一次 start 还能重新拉起
            Logx.w(LogTags.CAR3D, "车模自动补齐线程异常退出", t)
        } finally {
            lock.lock()
            try {
                // 只清自己那一条：stop 之后的这一小段时间里没人会改成别的线程
                if (worker === Thread.currentThread()) worker = null
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * 真正调一次 [CarModelRemote.sync]（阻塞）。
     *
     * @return null = 抛异常 / 已被 stop 作废；否则是同步结果（`error` 里带失败原因）。
     */
    private fun syncOnce(app: Context, gen: Int, attempt: Int): CarModelRemote.SyncResult? {
        return try {
            synchronized(syncLock) {
                // 等锁期间可能被 stop 了：这时连网络都不要再碰
                if (!isCurrent(gen)) return null
                CarModelRemote.sync(app, force = true)
            }
        } catch (t: Throwable) {
            // sync 自己已经兜底了异常，这里再兜一层：worker 不能因为一次调用炸掉
            Logx.w(LogTags.CAR3D, "车模同步调用异常", t, "attempt" to attempt)
            null
        }
    }

    /** 读本地已安装的车型目录。读失败按「一个都没有」处理：宁可多同步一次，也别漏补齐。 */
    private fun installedDirs(app: Context): Set<String> =
        runCatching { CarModelRemote.installedVersions(app).keys }.getOrElse { emptySet<String>() }

    /**
     * 本地已经齐全：记一条 INFO，并把本次变动的目录名回主线程回调（**整个进程只此一次**）。
     *
     * 参数允许为空列表：本次可能一个包都没下载（远端本来就有、只是刚刷新了索引），
     * 但「本地从不齐变齐」这件事宿主必须知道一次，否则解码缓存里可能还留着旧的空结果。
     */
    private fun onCompleted(gen: Int, result: CarModelRemote.SyncResult?) {
        val updated = result?.updatedDirs?.keys?.toList() ?: emptyList()
        // 按源码字段判断「这次是不是干净的成功」：error 非空 / 有失败车型都算没干净，
        // 但终止条件看的是本地齐全，所以这里只用于日志，不影响是否结束。
        val clean = result != null && result.error == null && result.failedDirs.isEmpty()
        Logx.i(
            LogTags.CAR3D,
            "车模补齐完成，本地已齐全",
            "updated" to updated.joinToString(","),
            "remoteTotal" to (result?.total ?: 0),
            "clean" to clean,
        )
        postUpdated(gen, updated)
    }

    /** 等 [RETRY_INTERVAL_MS]；[stop] 会提前唤醒（醒来后由调用方的 isCurrent 判断退出）。 */
    private fun awaitRetry(gen: Int) {
        lock.lock()
        try {
            var remainingNanos = RETRY_INTERVAL_MS * 1_000_000L
            while (isCurrent(gen) && remainingNanos > 0L) {
                remainingNanos = try {
                    retrySignal.awaitNanos(remainingNanos)
                } catch (e: InterruptedException) {
                    // 外部强行打断：保留中断标记并退出，别把状态吞掉
                    Thread.currentThread().interrupt()
                    return
                }
            }
        } finally {
            lock.unlock()
        }
    }

    // ------------------------------------------------------------------ 回调

    /** 把 [onUpdated] 投递到主线程（本来就在主线程就直接跑，省一次 Handler 往返）。 */
    private fun postUpdated(gen: Int, dirs: List<String>) {
        val main = Looper.getMainLooper()
        if (main == null || Looper.myLooper() == main) {
            // 理论上 Android 进程里 main looper 一定存在；真没有就退化成本线程投递，
            // 宁可线程不对也不能把结果吞掉（与 CarModelRemote 的 postMain 同一策略）。
            deliverUpdated(gen, dirs)
            return
        }
        Handler(main).post { deliverUpdated(gen, dirs) }
    }

    /** 主线程上真正执行回调；[stop] 之后过期的不再投递。 */
    private fun deliverUpdated(gen: Int, dirs: List<String>) {
        if (!isCurrent(gen)) return
        val callback = onUpdated ?: return
        runCatching { callback(dirs) }
            .onFailure { Logx.w(LogTags.CAR3D, "onUpdated 回调抛异常", it) }
    }

    /** 当前 worker 是否还是「有效的那一轮」（volatile 读，不必加锁）。 */
    private fun isCurrent(gen: Int): Boolean = gen == generation
}
