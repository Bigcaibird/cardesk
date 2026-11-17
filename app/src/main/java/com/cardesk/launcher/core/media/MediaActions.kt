package com.cardesk.launcher.core.media

/**
 * 「收藏 / 播放模式」这两件事的**协议抽象层**（第一九五轮）。
 *
 * ## 要解决的问题
 *
 * 用户要在第二页（导航页）的音乐卡上加：
 *
 * - **收藏按钮**（并同步歌曲的收藏状态）；
 * - **随机播放 / 顺序播放**切换。
 *
 * 桌面是**第三方**，不是这些播放器的车机 partner ——
 * 每家暴露的通道都不一样：
 *
 * | 播放器 | 收藏 | 播放模式 | 通道 |
 * |---|---|---|---|
 * | QQ 音乐车机版 | ✅ | ✅ | 第三方 URI 协议（`qqmusiccar://?action=20&m0=…`） |
 * | 网易云车机版 | ✅ | ✅ | **标准 MediaSession**（`sendCustomAction` / `setRating`） |
 * | 汽水音乐车机版 | ❌ | ❌ | 没找到对外通道 |
 *
 * → 所以桌面这一侧不能写死某一家，要**按包名分发**。
 *
 * ## 网易云为什么最后走的是「标准 API」而不是私有 JSK
 *
 * 最初以为只有它的私有 JSK/AIDL（`IIotServer`）能下指令，
 * 逆向到一半发现那条路**第三方根本接不上**
 * （`ServiceFacade` 是网易云进程内的静态 Map，`IIotServer` 也没在清单里导出）。
 *
 * 真正能用的是它 **MediaSession 回调里的两个标准入口**
 * （`vc/g$b`）：
 *
 * ```java
 * @Override public void onSetRating(RatingCompat rating) {
 *     if (rating.getRatingStyle() == 1) {          // RATING_HEART
 *         Bundle b = new Bundle();
 *         b.putBoolean("is_star", rating.hasHeart());
 *         g.f24419a.F("star_action", b);           // → 收藏 / 取消收藏
 *     }
 * }
 *
 * @Override public void onCustomAction(String action, Bundle extras) {
 *     g.f24419a.F(action, extras);                  // → "play_mode_action" 等
 * }
 * ```
 *
 * → 也就是说网易云**把收藏和播放模式挂在了标准会话回调上**，
 * 第三方拿 `MediaController` 就能读写，不需要碰它的私有通道。
 *
 * ## 为什么要单独抽一个文件
 *
 * 三件事揉在 UI 层会变成一大坨不可测的 if：
 *
 * ① 状态模型（收藏态 / 播放模式是什么）；
 * ② 「这个播放器支持吗」的判定；
 * ③ 真的下发指令。
 *
 * ①② 是**纯逻辑**（可在 JVM 单测里覆盖），③ 才碰 Android。
 * 所以本文件只放 ①②，③ 放到 [MediaActions] 这个执行层里。
 *
 * ## 收藏态为什么要分「三态」
 *
 * 不是「已收藏 / 未收藏」两态，而是**三态**：
 *
 * - **已收藏**（favorited = true）
 * - **未收藏**（favorited = false）
 * - **不知道**（favorite = null）
 *
 * 「不知道」必须单独一种：QQ 音乐只在 `update_song` 那类广播里带 `key_is_fav`，
 * `update_state`（只有播放态）里没有 → 那时若当成「未收藏」，
 * 界面会在每次播放态更新时闪一下「未收藏」——
 * **用户看着像收藏状态丢了**。
 *
 * ## 播放模式同理
 *
 * 播放模式是**三选一**（顺序 / 单曲 / 随机），
 * 但**不是所有播放器都告诉我们当前是哪个** →
 * 所以也有「不知道」（null）这一态。
 */
object MediaActions {

    // ---------------------------------------------------------------- 收藏态

    /**
     * 收藏态。
     *
     * @param favorited true = 已收藏；false = 未收藏；**null = 不知道**
     */
    data class FavoriteState(val favorited: Boolean?) {
        /** 状态已知吗。 */
        val known: Boolean get() = favorited != null

        /** 是否已收藏（状态未知时返回 false，**别拿它当状态用**，先看 [known]）。 */
        val isFavorited: Boolean get() = favorited == true

        companion object {
            /** 不知道（播放器没报，或这条广播里没这个字段）。 */
            val UNKNOWN = FavoriteState(null)

            /** 已收藏。 */
            val FAVORITED = FavoriteState(true)

            /** 未收藏。 */
            val NOT_FAVORITED = FavoriteState(false)
        }
    }

    // ---------------------------------------------------------------- 播放模式

    /**
     * 播放模式。
     *
     * 顺序播放 → 单曲循环 → 随机播放 → 顺序播放（循环切换）。
     *
     * @param mode 当前模式；**null = 不知道**
     */
    data class PlayModeState(val mode: PlayMode?) {
        /** 状态已知吗。 */
        val known: Boolean get() = mode != null

        /** 当前模式（未知时返回 null）。 */
        val current: PlayMode? get() = mode

        companion object {
            /** 不知道。 */
            val UNKNOWN = PlayModeState(null)

            /** 顺序播放。 */
            val REPEAT_ALL = PlayModeState(PlayMode.REPEAT_ALL)

            /** 单曲循环。 */
            val REPEAT_ONE = PlayModeState(PlayMode.REPEAT_ONE)

            /** 随机播放。 */
            val SHUFFLE = PlayModeState(PlayMode.SHUFFLE)
        }
    }

    /**
     * 三种播放模式。
     *
     * 命名与 Android `PlaybackState` 的 `setRepeatMode` /
     * `setShuffleMode` 的语义对齐（虽然那套 API 各家都不发布，
     * 但名字统一，以后接上了不用改界面）。
     */
    enum class PlayMode {
        /** 顺序播放（列表循环）。 */
        REPEAT_ALL,

        /** 单曲循环。 */
        REPEAT_ONE,

        /** 随机播放。 */
        SHUFFLE,
    }

    // ---------------------------------------------------------------- 能力判定

    /**
     * 一个播放器支持哪些动作。
     *
     * @param favorite 支不支持收藏
     * @param playMode 支不支持切播放模式
     */
    data class Caps(
        val favorite: Boolean,
        val playMode: Boolean,
    ) {
        companion object {
            /** 什么都不支持。 */
            val NONE = Caps(favorite = false, playMode = false)

            /** 都支持。 */
            val ALL = Caps(favorite = true, playMode = true)
        }
    }

    /**
     * 各播放器的动作能力表（**按包名**）。
     *
     * ## 为什么是「按包名」而不是「按来源 id」
     *
     * 同一个来源 id 可能对应多个包（酷我有 clone 变体），
     * 而**能力是跟着包走的**（哪个包实现、哪个包没实现，差别在 App 本身）。
     *
     * ## 只填**实测过**的
     *
     * 汽水两项都不支持（`SodaMediaSource` 里记着它的 browse 树是空的，
     * 全包也没找到收藏/播放模式的对外通道）→ 不填。
     */
    private val CAPS: Map<String, Caps> = mapOf(
        // QQ 音乐车机版：第三方 URI 协议（`qqmusiccar://?action=20&m0=…`）。
        QqBroadcast.PACKAGE to Caps(favorite = true, playMode = true),
        // 网易云车机版：**标准 MediaSession** —— 会话回调里的
        // `onSetRating`（心形评分 → 收藏）与 `onCustomAction`
        // （`"play_mode_action"` → 播放模式）。详见本文件的 KDoc。
        MediaButtonTransport.NETEASE_PACKAGE to Caps(favorite = true, playMode = true),
    )

    /**
     * 这个包支持哪些动作。
     *
     * 表里没有的包 → [Caps.NONE]（**不猜**：猜错了会下发到不存在的通道上）。
     */
    fun capsOf(packageName: String?): Caps {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return Caps.NONE
        return CAPS[pkg] ?: Caps.NONE
    }

    /** 这个包支不支持收藏。 */
    fun supportsFavorite(packageName: String?): Boolean = capsOf(packageName).favorite

    /** 这个包支不支持切播放模式。 */
    fun supportsPlayMode(packageName: String?): Boolean = capsOf(packageName).playMode

    // ---------------------------------------------------------------- 播放模式循环

    /**
     * 点一下「播放模式」按钮后的**下一个模式**（纯逻辑，可单测）。
     *
     * 顺序：顺序播放 → 单曲循环 → 随机播放 → 顺序播放。
     *
     * ## 状态未知时从哪开始
     *
     * 未知 → 先给**顺序播放**（最保守的那个）。
     * 理由：随机播放会让用户「下一首听到的歌跟预期不一样」，
     * 在不知道当前状态时先落到最不容易出意外的档位。
     *
     * @param current 当前模式；null = 不知道
     * @return 下一个模式
     */
    fun nextPlayMode(current: PlayMode?): PlayMode = when (current) {
        PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
        PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
        PlayMode.SHUFFLE -> PlayMode.REPEAT_ALL
        null -> PlayMode.REPEAT_ALL
    }

    // ---------------------------------------------------------------- 收藏态合并

    /**
     * 收到一条播放器广播后，**更新**收藏态（纯逻辑，可单测）。
     *
     * ## 为什么「未知」不覆盖已知
     *
     * QQ 音乐的 `update_state` 广播里**没有** `key_is_fav` →
     * 如果直接覆盖，已知的「已收藏」会被冲成「不知道」→ 按钮闪一下。
     *
     * → **只有确实带了这个字段的新值，才更新**。
     *
     * @param current 当前状态
     * @param incoming 新广播里的收藏态（null = 这条广播没带）
     * @return 合并后的状态
     */
    fun mergeFavorite(current: FavoriteState, incoming: FavoriteState?): FavoriteState {
        if (incoming == null || !incoming.known) return current
        return incoming
    }

    /**
     * 把 QQ 音乐广播里的 `key_is_fav` 读成收藏态（纯逻辑，可单测）。
     *
     * @param favorite `key_is_fav` 的值；**-1 = 这条广播里没有**
     * @return 对应的收藏态（-1 → 未知）
     */
    fun favoriteFromRaw(favorite: Int): FavoriteState = when {
        favorite < 0 -> FavoriteState.UNKNOWN
        favorite == 1 -> FavoriteState.FAVORITED
        else -> FavoriteState.NOT_FAVORITED
    }

    // ---------------------------------------------------------------- 网易云编码

    /**
     * 网易云车机版的播放模式编码（**车机侧口径**）。
     *
     * ## 出处
     *
     * `yc/y.java`（`"play_mode_action"` 的处理器）只认 **1 / 2 / 3**，
     * 其它值直接返回错误码 10。
     *
     * ## 这三个数分别是什么
     *
     * 网易云**内部**的播放模式是 0 / 1 / 2（顺序 / 单曲 / 随机），
     * 但它给车机侧用的是另一套编号 —— 见 `vc/g.java` 的 `onPlayModeChange`：
     *
     * ```java
     * num = 0 → i10 = 1     // 内部顺序 → 车机 1
     * num = 1 → i10 = 3     // 内部单曲 → 车机 3
     * num = 2 → i10 = 2     // 内部随机 → 车机 2
     * ```
     *
     * `yc/i.java`（`"get_play_mode_action"`）用的是同一张表，反向查也一样。
     *
     * → 所以：
     *
     * | 车机编码 | 含义 |
     * |---|---|
     * | 1 | 顺序播放（列表循环） |
     * | 2 | 随机播放 |
     * | 3 | 单曲循环 |
     */
    object NeteasePlayMode {
        /** 顺序播放（列表循环）。 */
        const val REPEAT_ALL = 1

        /** 随机播放。 */
        const val SHUFFLE = 2

        /** 单曲循环。 */
        const val REPEAT_ONE = 3
    }

    /**
     * 播放模式 → 网易云的车机编码（纯逻辑，可单测）。
     *
     * @return 1 / 2 / 3；理论上不会 null（枚举三个值都覆盖了）。
     */
    fun neteasePlayModeCode(mode: PlayMode): Int = when (mode) {
        PlayMode.REPEAT_ALL -> NeteasePlayMode.REPEAT_ALL
        PlayMode.SHUFFLE -> NeteasePlayMode.SHUFFLE
        PlayMode.REPEAT_ONE -> NeteasePlayMode.REPEAT_ONE
    }

    /**
     * 网易云的车机编码 → 播放模式（纯逻辑，可单测）。
     *
     * @param code 网易云给的 1 / 2 / 3
     * @return 对应的模式；**不认识就返回 null**（当「不知道」处理，别瞎猜）
     */
    fun playModeFromNeteaseCode(code: Int): PlayMode? = when (code) {
        NeteasePlayMode.REPEAT_ALL -> PlayMode.REPEAT_ALL
        NeteasePlayMode.SHUFFLE -> PlayMode.SHUFFLE
        NeteasePlayMode.REPEAT_ONE -> PlayMode.REPEAT_ONE
        else -> null
    }
}
