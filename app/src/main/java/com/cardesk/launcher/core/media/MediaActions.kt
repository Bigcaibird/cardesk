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
 * | 汽水音乐车机版 | ✅ | ❌ | **标准 MediaSession**（`setRating` → 心形评分） |
 * | 其它播放器 | 探测 | 探测 | **通用兜底**：标准 MediaSession 通道 + 运行时探测 |
 *
 * → 所以桌面这一侧不能写死某一家，要**按包名分发**（已知包走专用通道，
 * 未知包走通用兜底）。
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

    // ---------------------------------------------------------------- 运行时探测

    /**
     * **运行时探测**到的会话事实（第一九六轮）。
     *
     * ## 为什么要探测
     *
     * 能力表只覆盖**逆向过**的三个播放器。
     * 桌面上还有一堆别的音乐 App（爱趣听、酷我、汽水…），
     * 它们各有各的私有协议，桌面不可能一家一家逆 →
     * 只能靠**标准 MediaSession 通道**兜底（见 [StandardMediaActions]）。
     *
     * 但**不能无脑乱发**：亮一个「点了没反应」的按钮比没有更糟。
     * → 所以先看这个会话**自己发布的事实**，再决定亮不亮。
     *
     * | 能力 | 判据 |
     * |---|---|
     * | 收藏 | 会话 metadata 里**有**心形评分（`METADATA_KEY_USER_RATING`）|
     * | 播放模式 | 会话 `PlaybackState` 的 `repeatMode` / `shuffleMode` **被设置过** |
     *
     * @param favoriteHeart metadata 里心形评分读出来的值；
     *   **null = 没有心形评分项**（→ 不支持标准收藏）
     * @param repeatMode `PlaybackState.getRepeatMode()`（[RepeatMode] 口径）
     * @param shuffleMode `PlaybackState.getShuffleMode()`（[ShuffleMode] 口径）
     */
    data class Probe(
        val favoriteHeart: Boolean?,
        val repeatMode: Int = RepeatMode.NONE,
        val shuffleMode: Int = ShuffleMode.NONE,
    ) {
        companion object {
            /** 什么事实都没探到（没有会话 / 没有元数据）。 */
            val EMPTY = Probe(favoriteHeart = null)
        }
    }

    /**
     * `PlaybackState` 的**重复模式**取值（与 Android framework 同口径）。
     *
     * ## 为什么要自己定义一份
     *
     * `PlaybackState.REPEAT_MODE_UNSET` 是 **API 29** 才加的，
     * 而本工程跑在 **API 28** 的车机上 →
     * 直接引用 framework 常量在低版本上拿不到「未设置」这个态。
     *
     * → 自己定义一份（值与 framework 完全一致），纯 Kotlin、可单测。
     */
    object RepeatMode {
        /** 不重复（播完一遍就停）。 */
        const val NONE = 0

        /** 单曲循环。 */
        const val ONE = 1

        /** 列表循环（顺序播放）。 */
        const val ALL = 2

        /** 未设置（API 29+ 才有这个值；低版本用 [NONE]）。 */
        const val UNSET = -1
    }

    /**
     * `PlaybackState` 的**随机模式**取值（与 Android framework 同口径）。
     */
    object ShuffleMode {
        /** 不随机。 */
        const val NONE = 0

        /** 随机播放。 */
        const val ALL = 1

        /** 未设置（API 29+ 才有这个值；低版本用 [NONE]）。 */
        const val UNSET = -1
    }

    /**
     * 这个会话**有没有暴露**播放模式（能力探测用，纯逻辑，可单测）。
     *
     * ## 判据
     *
     * 「设置过」= `repeatMode` 或 `shuffleMode` **不是 NONE**。
     *
     * ## 为什么不用 `!= UNSET` 判定
     *
     * `REPEAT_MODE_UNSET`（-1）是 API 29 才有的，
     * API 28 的 framework 里 `PlaybackState` 的默认值是 **NONE（0）** →
     * 用 `!= UNSET` 判的话，API 28 上**每个会话都会「暴露」播放模式** →
     * 按钮到处乱亮。
     *
     * → 统一按「**大于 0**」判定：只有 ONE / ALL / SHUFFLE_ALL 才算数。
     *
     * @param repeatMode `PlaybackState.getRepeatMode()`
     * @param shuffleMode `PlaybackState.getShuffleMode()`
     */
    fun exposesPlayMode(repeatMode: Int, shuffleMode: Int): Boolean =
        repeatMode > RepeatMode.NONE || shuffleMode > ShuffleMode.NONE

    /**
     * 从会话的 `PlaybackState` 模式字段读**播放模式**（纯逻辑，可单测）。
     *
     * - `shuffleMode == ALL` → **随机播放**（随机优先于重复）；
     * - `repeatMode == ONE` → **单曲循环**；
     * - `repeatMode == ALL` → **顺序播放**；
     * - 其余 → **不知道**（null）。
     *
     * @param repeatMode `PlaybackState.getRepeatMode()`
     * @param shuffleMode `PlaybackState.getShuffleMode()`
     */
    fun playModeFromState(repeatMode: Int, shuffleMode: Int): PlayMode? = when {
        shuffleMode == ShuffleMode.ALL -> PlayMode.SHUFFLE
        repeatMode == RepeatMode.ONE -> PlayMode.REPEAT_ONE
        repeatMode == RepeatMode.ALL -> PlayMode.REPEAT_ALL
        else -> null
    }

    /**
     * 各播放器的动作能力表（**按包名**）。
     *
     * ## 为什么是「按包名」而不是「按来源 id」
     *
     * 同一个来源 id 可能对应多个包（酷我有 clone 变体），
     * 而**能力是跟着包走的**（哪个包实现、哪个包没实现，差别在 App 本身）。
     *
     * ## 表里是「**有专用通道**」的包
     *
     * 表里没有的包 → 走 [capsOf] 的**运行时探测**（通用兜底）。
     */
    private val CAPS: Map<String, Caps> = mapOf(
        // QQ 音乐车机版：第三方 URI 协议（`qqmusiccar://?action=20&m0=…`）。
        QqBroadcast.PACKAGE to Caps(favorite = true, playMode = true),
        // 网易云车机版：**标准 MediaSession** —— 会话回调里的
        // `onSetRating`（心形评分 → 收藏）与 `onCustomAction`
        // （`"play_mode_action"` → 播放模式）。详见本文件的 KDoc。
        MediaButtonTransport.NETEASE_PACKAGE to Caps(favorite = true, playMode = true),
        // 汽水音乐车机版（第一九六轮）：**收藏走标准评分通道**。
        //
        // 逆向结论（`com.luna.player.impl.remotecontrol.CoreRemoteControlListener`）：
        //
        // ```java
        // public void onSetRating(RatingCompat ratingCompat) {
        //     if (ratingCompat == null) { super.onSetRating(ratingCompat); }
        //     else { handleCollectOrUnCollectCommand(ratingCompat.hasHeart()); }   // ← 收藏
        // }
        // ```
        //
        // → 桌面下发**标准心形评分**它就真的收藏。
        // 实测它的会话 `rating type=1`（心形评分在），收藏态也读得到。
        //
        // 播放模式：它只在**进程内**的指令中心里
        // （`LunaCommandKeys.SWITCH_PLAY_MODE` → `SwitchPlayModeCommand`，
        // 由 BYD 的**语音**通道触发），第三方接不上；
        // 会话实测 `custom actions=[]`、`PlaybackState` 也没有模式字段
        // → **按钮隐藏**。
        SodaMediaSource.PACKAGE to Caps(favorite = true, playMode = false),
    )

    /**
     * 这个包支持哪些动作。
     *
     * ## 两级判定
     *
     * 1. **能力表里有的包** → 直接查表（专用通道，逆向验证过的）；
     * 2. **表里没有的包** → 看 [probe]（运行时探测，通用兜底）：
     *    - 会话发了**心形评分** → 说明它用标准收藏语义 → 亮收藏按钮；
     *    - 会话**暴露了**播放模式字段 → 说明它用标准模式语义 → 亮播放模式按钮；
     *    - 探测不到 → **什么都不亮**（不猜：猜错了会亮一个点了没反应的按钮）。
     *
     * @param packageName 播放器包名
     * @param probe 运行时探测到的事实（null = 没探到 → 兜底一律不支持）
     */
    fun capsOf(packageName: String?, probe: Probe? = null): Caps {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return Caps.NONE
        CAPS[pkg]?.let { return it }
        // —— 表里没有 → 运行时探测（通用兜底）
        if (probe == null) return Caps.NONE
        return Caps(
            favorite = probe.favoriteHeart != null,
            playMode = exposesPlayMode(probe.repeatMode, probe.shuffleMode),
        )
    }

    /** 这个包支不支持收藏。 */
    fun supportsFavorite(packageName: String?, probe: Probe? = null): Boolean =
        capsOf(packageName, probe).favorite

    /** 这个包支不支持切播放模式。 */
    fun supportsPlayMode(packageName: String?, probe: Probe? = null): Boolean =
        capsOf(packageName, probe).playMode

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

    // ------------------------------------------------- QQ 收藏回报的采纳判定（第二轮二三）

    /**
     * 一条带 `key_is_fav` 的 QQ 广播（`update_fav` / `update_song` / …），
     * 它的**收藏部分**该不该写到当前心形上。
     */
    enum class FavReportVerdict {
        /** 采纳（写心形；若它就是挂起指令的确认，顺手取消挂起的乐观更新）。 */
        APPLY,

        /** 丢弃 —— 报文里的 `key_title` 是**上一首歌**（回报走服务器推送，迟到几分钟），
         *  它的收藏态绝不能写到当前这首歌的心形上。 */
        DROP_STALE_SONG,

        /** 丢弃 —— 我们刚下发的收藏指令还没被确认，
         *  这条与目标相反的回报是**上一次切换的迟到回声**（推送乱序）。 */
        DROP_ECHO,
    }

    /**
     * 判定一条 QQ 收藏回报怎么处理（纯逻辑，可单测）。
     *
     * ## 为什么要做这个判定（第二轮二三，修「收藏态大概率显示不对」）
     *
     * 实测抓包（2026-11-23，cardesk28 + QQ 3.14.0.4）：
     *
     * - `update_fav` 的载荷**自带 `key_title`** ——
     *   `{"method":"update_fav","data":{"key_title":"看见",…,"key_is_fav":0}}`；
     * - 它的送达路径是 **TME 服务器推送**（`TmePush: T5M-RemoteDataImpl: push
     *   request: t.push.report.message`）→ 延迟 = 网络往返，
     *   模拟器实测一次 **61 秒**；
     * - QQ 会把同一条 `update_fav` **发两遍**（重复广播）。
     *
     * 旧逻辑无条件把 `key_is_fav` 写到当前心形上 → 用户取消收藏 A 歌后
     * 切到 B 歌（已收藏），A 的迟到回报把 B 的心形冲成「未收藏」——
     * 这就是「在 QQ 音乐上收藏态大概率显示/同步不对」的主因。
     *
     * @param payloadTitle 报文里的 `key_title`（空 = 报文没带歌名）
     * @param currentTitle 当前正在显示的曲目标题（空 = 还没有曲目）
     * @param incomingFavorited 报文里 `key_is_fav` 的值（true = 已收藏）
     * @param pendingTarget 刚下发的收藏指令目标态（null = 没有挂起的指令）
     * @param echoGrace 是否仍在「回声宽限窗」内
     *   （挂起乐观更新未决，或距上次下发不足 [MusicController.FAVORITE_ECHO_GRACE_MS]）
     */
    fun favoriteReportVerdict(
        payloadTitle: String,
        currentTitle: String,
        incomingFavorited: Boolean,
        pendingTarget: Boolean?,
        echoGrace: Boolean,
    ): FavReportVerdict {
        val pt = payloadTitle.trim()
        val ct = currentTitle.trim()
        // 迟到的旧歌回报：歌名两边都有、但对不上 → 丢弃。
        // 任一边为空（报文没带歌名 / 卡片还没有曲目）→ 无法判定，按当前歌处理。
        if (pt.isNotEmpty() && ct.isNotEmpty() && pt != ct) {
            return FavReportVerdict.DROP_STALE_SONG
        }
        // 挂起指令未决（或刚下发不久）时，与目标相反的回报是上一次切换的回声。
        // 与目标一致 → 这就是我们要的确认（APPLY，调用方会取消挂起的乐观更新）。
        if (echoGrace && pendingTarget != null && incomingFavorited != pendingTarget) {
            return FavReportVerdict.DROP_ECHO
        }
        return FavReportVerdict.APPLY
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

    // ---------------------------------------------------------------- 收藏结果

    /**
     * 播放器回报的「收藏指令执行结果」码（第一九六轮）。
     *
     * ## 出处
     *
     * 网易云处理完 `star_action` 会回一条同名 session event，
     * bundle 里是 `code` + `message`（`yc/s0.java`）：
     *
     * ```java
     * if (areEqual(Boolean.valueOf(isStar), vc.g.f24419a.D())) {
     *     bundle.putInt("code", -2);
     *     bundle.putString("message", isStar ? "已经是收藏状态了" : "已经是未收藏状态了");
     *     mediaSession.sendSessionEvent(action, bundle);
     *     return new IoTMediaResult(6, "", "");
     * }
     * ...
     * bundle2.putInt("code", 0);
     * bundle2.putString("message", "操作成功");
     * mediaSession.sendSessionEvent(action, bundle2);
     * ```
     */
    object FavoriteResult {
        /** 操作成功（收藏 / 取消收藏生效）。 */
        const val OK = 0

        /** 状态**本来就是**这样（幂等空操作，没有真正改）。 */
        const val ALREADY = -2

        /** 失败。 */
        const val FAILED = -1
    }

    /**
     * 播放器的收藏结果 → **校正后**的收藏态（纯逻辑，可单测）。
     *
     * ## 为什么 0 和 -2 都算「成功」
     *
     * `-2`（已经是该状态）虽然**没有真的改**，
     * 但它明确告诉了桌面「**当前状态就是你要的那个**」→
     * 桌面据此把状态校正过来，界面才不会一直停在旧状态。
     *
     * @param code 播放器回报的结果码
     * @param target 桌面**请求**的目标状态（true = 收藏）
     * @return 校正后的收藏态；**null = 失败了**，调用方要回滚
     */
    fun favoriteStateFromResult(code: Int, target: Boolean): FavoriteState? = when (code) {
        FavoriteResult.OK, FavoriteResult.ALREADY ->
            if (target) FavoriteState.FAVORITED else FavoriteState.NOT_FAVORITED
        else -> null
    }

    // ---------------------------------------------------------------- 通用编码

    /**
     * **通用**播放模式编码（顺序=1 / 单曲=2 / 随机=3）。
     *
     * ## 这套编码是谁定的
     *
     * `sendCustomAction` **没有**官方约定的参数格式，
     * 但桌面需要一套自己的口径发给「走标准通道」的播放器 →
     * 这里定一套**自定的通用编码**。
     *
     * 注意它和 [NeteasePlayMode] **不是**同一个编号
     * （网易云是 1=顺序 / 2=随机 / 3=单曲）→ 别混用。
     */
    object GenericPlayMode {
        /** 顺序播放（列表循环）。 */
        const val REPEAT_ALL = 1

        /** 单曲循环。 */
        const val REPEAT_ONE = 2

        /** 随机播放。 */
        const val SHUFFLE = 3
    }

    /**
     * **通用**播放模式编码（纯逻辑，可单测）。
     *
     * | 模式 | 通用编码 |
     * |---|---|
     * | 顺序播放 | 1 |
     * | 单曲循环 | 2 |
     * | 随机播放 | 3 |
     */
    fun genericPlayModeCode(mode: PlayMode): Int = when (mode) {
        PlayMode.REPEAT_ALL -> GenericPlayMode.REPEAT_ALL
        PlayMode.REPEAT_ONE -> GenericPlayMode.REPEAT_ONE
        PlayMode.SHUFFLE -> GenericPlayMode.SHUFFLE
    }

    /**
     * 模式 → Android 标准**重复模式**（`PlaybackState.REPEAT_MODE_*`，纯逻辑，可单测）。
     *
     * 顺序 → ALL(2)，单曲 → ONE(1)，随机 → NONE(0)（随机靠 shuffle 字段表达）。
     */
    fun androidRepeatMode(mode: PlayMode): Int = when (mode) {
        PlayMode.REPEAT_ALL -> RepeatMode.ALL
        PlayMode.REPEAT_ONE -> RepeatMode.ONE
        PlayMode.SHUFFLE -> RepeatMode.NONE
    }

    /**
     * 模式 → Android 标准**随机模式**（`PlaybackState.SHUFFLE_MODE_*`，纯逻辑，可单测）。
     *
     * 随机 → ALL(1)，其余 → NONE(0)。
     */
    fun androidShuffleMode(mode: PlayMode): Int = when (mode) {
        PlayMode.SHUFFLE -> ShuffleMode.ALL
        else -> ShuffleMode.NONE
    }
}
