package com.cardesk.launcher.core.media

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「这个 App 是不是**真的在出声**」的探测（第203轮）。
 *
 * ## 为什么需要它
 *
 * 第202轮用「位置有没有推进」判会话是不是真的在放（[SessionAudibility]）。
 * 真车（4276）上发现它会**误判正常播放**：网易云切歌后 `PlaybackState.position`
 * 停在 174 达 4 秒不动（歌实际在放，声音一直有）—— 因为它只在事件发生时上报位置快照，
 * 两次上报之间位置是「陈旧」的。
 *
 * 位置快照只能证明「变了」，不能证明「没变就是没放」。真正的地面真相是**音频输出**：
 * `AudioManager.registerAudioPlaybackCallback` 回调里，正在出声的播放器会带着
 * 它所属 App 的 uid（`AudioPlaybackConfiguration.getClientUid()`，@hide，走反射）。
 *
 * ## 用法
 *
 * - [ensure] 只注册一次；注册失败保持 [isReady] == false；
 * - [isReady] 为 true 之后，[isOutputting] 的结论是**权威**的
 *   （`false` 就真的没在出声，包括「会话自称在放」的僵尸）；
 * - 没就绪时调用方回落到位置判据。
 *
 * 权限：`getClientUid` / `isActive` 是 @hide 成员，本项目启动时已
 * `setHiddenApiExemptions("L")`，平台签名进程可直接反射。
 */
object AudioOutputProbe {

    private val lock = Any()

    /** 当前正在出声的 uid 集合。 */
    private val outputtingUids = HashSet<Int>()

    /** 是否已经收到过至少一次回调（收到之前结论不可信）。 */
    private var ready = false

    /** 注册是否成功过（失败就不再重试，避免每次刷新都抛一次）。 */
    private var attempted = false

    private var warned = false

    /** 最近一次回调里的播放器数量（诊断用）。 */
    private var lastCount = 0

    /** 注册监听（幂等；失败只警告一次）。 */
    fun ensure(context: Context) {
        if (attempted) return
        attempted = true
        val am = try {
            context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (t: Throwable) {
            null
        }
        if (am == null) {
            Logx.w(LogTags.MEDIA, "音频输出探测：拿不到 AudioManager")
            return
        }
        try {
            am.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
            // 注册本身不会立刻回调：先把当前快照读进来，否则要等到下一次播放配置变化
            // 才有结论（真车上第一拍会因此回落到位置判据）。
            val current = runCatching { am.activePlaybackConfigurations }.getOrNull()
            update(current)
            Logx.i(LogTags.MEDIA, "音频输出探测已注册", "count" to (current?.size ?: 0))
        } catch (t: Throwable) {
            Logx.w(LogTags.MEDIA, "音频输出探测注册失败（将回落到位置判据）", t)
        }
    }

    /** 回调是否已经到过（结论可用）。 */
    fun isReady(): Boolean = synchronized(lock) { ready }

    /** 这个 uid 现在是否在出声。 */
    fun isOutputting(uid: Int): Boolean {
        if (uid <= 0) return false
        return synchronized(lock) { uid in outputtingUids }
    }

    /** 诊断用：当前在出声的 uid 快照。 */
    fun snapshot(): Set<Int> = synchronized(lock) { HashSet(outputtingUids) }

    /** 诊断用：最近一次回调的播放器数量。 */
    fun lastConfigCount(): Int = synchronized(lock) { lastCount }

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            update(configs)
        }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>?) {
        val uids = HashSet<Int>()
        configs?.forEach { c ->
            val active = boolOf(c, "isActive")
            val uid = intOf(c, "getClientUid")
            if (uid > 0 && active) uids.add(uid)
        }
        synchronized(lock) {
            outputtingUids.clear()
            outputtingUids.addAll(uids)
            lastCount = configs?.size ?: 0
            ready = true
        }
        Logx.d(LogTags.MEDIA, "音频输出变化", "count" to (configs?.size ?: 0), "uids" to uids.sorted())
    }

    private fun boolOf(config: AudioPlaybackConfiguration, name: String): Boolean {
        return try {
            config.javaClass.getMethod(name).invoke(config) as? Boolean ?: true
        } catch (t: Throwable) {
            // 拿不到 isActive 就按「在放」算，宁可多算不可漏算
            warnOnce("isActive", t)
            true
        }
    }

    private fun intOf(config: AudioPlaybackConfiguration, name: String): Int {
        return try {
            (config.javaClass.getMethod(name).invoke(config) as? Int) ?: 0
        } catch (t: Throwable) {
            warnOnce(name, t)
            0
        }
    }

    private fun warnOnce(method: String, t: Throwable) {
        if (warned) return
        warned = true
        Logx.w(LogTags.MEDIA, "读取 AudioPlaybackConfiguration.$method 失败（隐藏 API 被拦？）", t)
    }
}
