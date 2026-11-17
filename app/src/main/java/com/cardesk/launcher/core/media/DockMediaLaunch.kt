package com.cardesk.launcher.core.media

import android.content.Context
import android.content.Intent
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 「点 Dock 媒体卡的封面 / 歌名区 → 拉起对应音乐 App」（第一九七轮）。
 *
 * ## 为什么单独一个文件
 *
 * 这个判定有**两层**，每一层都可能单独不成立，而且**失败都不该让桌面崩掉**：
 *
 * ① 「正在使用的音乐 App」是**哪个包**、拉不拉得起来；
 * ② 真的把 App 起起来。
 *
 * ① 是**纯逻辑**（[resolve] + [messageFor]，可在 JVM 单测里完整覆盖），
 * ② 才碰 Android（[openCurrentApp]）。
 *
 * ## 「正在使用的音乐 App」按**当前会话的包名**判定
 *
 * 用的是 [MusicController.track] 的 `pkg` —— 也就是**此刻正在出声的那个 App**。
 *
 * 不用「用户设置的默认来源」（`prefs.musicSource`）是有意的：
 * 默认来源是**用户上次选的**，而实际在播的可能是另一个 App
 * （用户在 QQ 音乐里放歌、但默认来源还停在网易云）。
 * 「正在使用」问的是**当下**，答案只存在于会话里。
 *
 * ## 为什么不用会话的 `sourceId`
 *
 * `sourceId` 是**我们自己的分类标签**（`MediaSources` 映射出来的），
 * 「通用音乐」这个 id 更是**不对应任何 App**（见 [MediaSources.GENERIC_ID]）——
 * 拿它去拉起 App 必然落空。包名才是能直接喂给 `PackageManager` 的东西。
 */
object DockMediaLaunch {

    /**
     * 一次点击该怎么处理。
     *
     * @param OpenApp 拉起这个包（包名非空）
     * @param NoSession 当前没有会话 —— 没有「正在使用的音乐 App」可拉
     * @param NotLaunchable 这个包拉不起来（没装 / 没有启动入口）
     */
    sealed class Action {
        /** 拉起指定包名对应的 App。 */
        data class OpenApp(val pkg: String) : Action()

        /** 当前没有任何媒体会话 → 没有可拉起的 App。 */
        object NoSession : Action()

        /** 会话在，但那个 App 拉不起来。 */
        object NotLaunchable : Action()
    }

    /**
     * 「当前会话的包名」→ 该做什么。
     *
     * @param trackPkg 当前会话所属包名（null / 空串 = 没有会话）
     * @param isLaunchable 这个包有没有启动入口（调用方用 `getLaunchIntentForPackage` 判定）
     */
    fun resolve(trackPkg: String?, isLaunchable: (String) -> Boolean): Action {
        val pkg = trackPkg?.trim().orEmpty()
        if (pkg.isEmpty()) return Action.NoSession
        // 包名在，但起不来（没装 / 没启动入口）→ 明确报「拉不起来」，
        // 不要退化成「没有会话」—— 那两个的提示语不一样。
        if (!isLaunchable(pkg)) return Action.NotLaunchable
        return Action.OpenApp(pkg)
    }

    /**
     * 给用户的提示文案（拉不起来的时候用）。
     *
     * 单独抽出来是为了**单测能锁住文案**，
     * 也为了让 [Action] 这个纯逻辑层不依赖 Android 的 `Context`。
     */
    fun messageFor(action: Action): String? = when (action) {
        is Action.OpenApp -> null
        Action.NoSession -> "当前没有正在使用的音乐 App"
        Action.NotLaunchable -> "该音乐应用无法打开"
    }

    /**
     * 拉起**当前正在使用**的音乐 App（Android 侧，桌面与全局 Dock 共用）。
     *
     * ## 为什么拉的是「正在使用的」而不是「用户选的」
     *
     * 用户点这块的意图是「回到我正在听的那个 App」——
     * 谁在出声就拉谁，而不是用户上次在设置里选的那个。
     *
     * @param context 用来起 Intent 的上下文（Service / Activity 都行，
     *   靠 `FLAG_ACTIVITY_NEW_TASK` 兼容 Service 场景）
     * @param trackPkg 当前会话所属包名（一般传 `MusicController.track?.pkg`）
     * @return 实际做了什么（用于日志与提示）
     */
    fun openCurrentApp(context: Context, trackPkg: String?): Action {
        val action = resolve(trackPkg) { pkg ->
            runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg)
            }.getOrNull() != null
        }
        when (action) {
            is Action.OpenApp -> {
                val intent = runCatching {
                    context.packageManager.getLaunchIntentForPackage(action.pkg)
                }.getOrNull()
                if (intent == null) {
                    // 判定与实际拉起之间 App 可能被卸载了 —— 记日志，不崩。
                    Logx.w(LogTags.MEDIA, "Dock 媒体卡：拿不到启动 Intent", "pkg" to action.pkg)
                    return Action.NotLaunchable
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val started = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrElse {
                    Logx.w(LogTags.MEDIA, "Dock 媒体卡：拉起音乐 App 失败", it, "pkg" to action.pkg)
                    false
                }
                if (!started) return Action.NotLaunchable
                Logx.event(
                    LogTags.MEDIA,
                    "Dock 媒体卡：拉起当前音乐 App",
                    "pkg" to action.pkg,
                )
                return action
            }
            Action.NoSession ->
                Logx.i(LogTags.MEDIA, "Dock 媒体卡：当前没有媒体会话，无 App 可拉起")
            Action.NotLaunchable ->
                Logx.w(LogTags.MEDIA, "Dock 媒体卡：当前会话的 App 拉不起来", "pkg" to (trackPkg ?: ""))
        }
        return action
    }
}
