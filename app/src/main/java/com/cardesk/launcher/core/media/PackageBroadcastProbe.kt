package com.cardesk.launcher.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 读某个音乐 App **注册了哪些广播接收器**（诊断用，第126轮新增）。
 *
 * ## 为什么需要它
 *
 * 真车问题：「打开方控后，方向盘还是控爱趣听，控不到 QQ音乐」。
 *
 * 方控命令要送到 QQ音乐，**必须知道它接收哪些 action**。
 * QQ 音乐车机版**不发布可枚举的 MediaSession**（诊断包实测：24 分钟的会话枚举里
 * 一次都没出现过它），所以它靠**广播**跟外部通信 ——
 * 那条广播的 action 我们知道（`com.tencent.qqmusiccar.action.PLAY_COMMAND_SEND_FOR_THIRD`），
 * 但**反向**（第三方 → QQ音乐 下命令）用哪个 action，还不知道。
 *
 * 而这个信息**不用拿到 APK** 也能问出来：
 * `PackageManager.getPackageInfo(pkg, GET_RECEIVERS)` 会把
 * **每个接收器 + 它的 intent-filter 里的 action** 都列出来。
 *
 * 本应用是 `sharedUserId=android.uid.system` 的系统应用 →
 * 读别的 app 的包信息不需要额外权限。
 */
object PackageBroadcastProbe {

    /**
     * 列出该包注册的接收器及其 action。
     *
     * 用 [PackageManager.queryBroadcastReceivers] 而不是
     * `getPackageInfo(GET_RECEIVERS)` —— 后者给的是 `ActivityInfo`，
     * **它上面没有 intent-filter**（filter 在 [android.content.pm.ResolveInfo] 里）。
     *
     * @return 「接收器类名 → action1|action2|…」的多行文本；读不到时返回一行说明。
     */
    fun describeReceivers(context: Context, pkg: String): String {
        val pm = context.packageManager
        // 不带 action 的 Intent = 「全部接收器」。
        val ris = runCatching {
            pm.queryBroadcastReceivers(Intent(), 0)
        }.getOrElse {
            Logx.w(LogTags.MEDIA, "读接收器失败", it, "pkg" to pkg)
            return "读不到 $pkg 的接收器：${it.javaClass.simpleName}"
        }
        val mine = ris.filter { it.activityInfo?.packageName == pkg }
        if (mine.isEmpty()) return "$pkg 没有静态注册的接收器"

        val sb = StringBuilder()
        sb.append(pkg).append(" 注册了 ").append(mine.size).append(" 个接收器：\n")
        for (ri in mine) {
            val name = ri.activityInfo?.name ?: continue
            val exported = ri.activityInfo?.exported
            sb.append("  ").append(name)
                .append(if (exported == true) "  [exported]" else "  [非 exported]")
                .append('\n')
            // 注意：`IntentFilter.getActions()` 是**隐藏 API**，公开的只有
            // `countActions()` + `getAction(i)`（还有 `actionsIterator()`）。
            // 这里用公开的那套，不碰隐藏 API。
            val filter = ri.filter
            if (filter == null || filter.countActions() == 0) {
                sb.append("    （无 action 过滤）\n")
            } else {
                for (i in 0 until filter.countActions()) {
                    sb.append("    ← ").append(filter.getAction(i)).append('\n')
                }
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 该包**已安装的版本**（诊断用，第128轮）。
     *
     * ## 为什么需要
     *
     * 真车诊断包证明了一件事：**车上的 QQ 音乐车机版与我们在模拟器上
     * 逆向的那个版本未必是同一个**（模拟器实测的是 1.9.8.22，
     * 车上的广播内容是 `module:"app"` / `update_app_foreground`，
     * 与模拟器上见过的 `module:"play"` 系列不是一回事）。
     *
     * 协议对不对得上，全靠版本号对齐 —— 所以诊断包里必须直接写出来，
     * 不然每次都要重新问用户「你车上 QQ 音乐是哪个版本」。
     *
     * @return 「版本名 (versionCode)」；没装则返回「未安装」
     */
    fun versionOf(context: Context, pkg: String): String {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getPackageInfo(pkg, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrElse { "未安装" }
    }

    /**
     * 只列出「看起来像给第三方用」的 action（带关键词的）。
     *
     * 只关心 `qqmusiccar` / `play` / `media` / `music` / `lyric` / `third` 这些关键词，
     * 免得几百行系统内部 action 淹没重点。
     */
    fun describeInterestingActions(context: Context, pkg: String): String {
        val full = describeReceivers(context, pkg)
        val keep = full.lineSequence().filter { line ->
            val lower = line.lowercase()
            lower.contains("qqmusic") || lower.contains("play") || lower.contains("media") ||
                lower.contains("music") || lower.contains("lyric") || lower.contains("third") ||
                lower.contains("car") || lower.contains("command") || lower.contains("key")
        }.toList()
        return if (keep.isEmpty()) full else keep.joinToString("\n")
    }
}
