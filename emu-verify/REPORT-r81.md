# 第八十一轮：歌词逐字擦除（修「一句歌词还没擦除完就到下一句」）

日期：2026-10-30 · 被测包：`dist/CarDesk-ecarx-cardesk-4145.apk`（修前）
与 `dist/CarDesk-ecarx-cardesk-4146.apk`（修后）

用户反馈原文：

> 「对于一些唱的快或慢的句子会不匹配，会出现一句歌词还没擦除完就到下一句了，优化一下」

## 0. 环境

| 项 | 值 |
| --- | --- |
| 设备 | `emulator-5554` = AVD `cardesk-aosp`（android-10 / API 29 / arm64-v8a） |
| 分辨率 | 1920×1080 @160dpi |
| 被测包 | `com.ecarx.cardesk`，**平台签名 + uid 1000** |
| 外网 | 可用（`ping www.kugou.com` 通）—— 逐字歌词要联网取 |
| 音源 | 媒体探针 `com.cardesk.mediaprobe` 注入 MediaSession（真机那首歌） |
| 工具 | 新增 `tools/lyric_shot.py`（钉位置 + 截图 + 裁歌词区） |

> 本轮 ecarx 平台镜像（第八十轮用的那台）已不在本机，
> 改用同为 **API 29 / 1920×1080@160dpi** 的 AVD `cardesk-aosp`。
> 歌词擦除是纯逻辑 + 渲染，**不依赖车控通道**，该镜像足够覆盖；
> 车控相关项仍以第八十轮 ecarx 镜像的结论为准（未重测）。

## 1. 根因：不是公式错，是**信息缺失**

原厂 APK（`com.claunchercard` 的 `MusicManager.computeLineWipeFraction`）与 CarDesk 的公式**完全一致**：

```
wipe = (播放位置 − 行首) / (下一行首 − 行首)
```

即**擦除进度被绑死在「下一句开始」上**。可歌手往往**早就唱完这一句**了
（唱得快 → 字挤在前半段；拖腔 → 字唱完后还吊着），
于是「高亮还在半路，句子已经唱完」⇒ 用户看到的正是
**「这一句还没擦除完就跳到下一句」**。

⇒ **只靠行时间戳修不了**：行内「字怎么分」这个信息根本不在行时间戳里。
真正的修法是**按字推进**，前提是能拿到**逐字时间**。

### 1.1 真实歌曲里的量级（实测 KRC，不是估的）

`怎么我睡不着你也不来救我` / 焦迈奇（真机诊断包里的那首）：

| 行 | 行首 | 行长 | 末字结束 | 下一行行首 | **空档** |
| --- | --- | --- | --- | --- | --- |
| 29「怎么我睡不着你也不知来找我」 | 98324 | 22264 | 103197 | 120588 | **17391ms** |
| 43「怎么我睡不着你也不知来找我」 | 174025 | 22354 | 178594 | 196379 | **17785ms** |
| 21「庸人对着放光的长方体思索」 | 58470 | 8981 | 63566 | 67451 | 3885ms |

**空档 17 秒** —— 歌手唱完这一句后，下一句隔了 17 秒才唱。
行级线性在这 17 秒里只会把擦除从 23% 慢慢推到 100%，
所以用户在整段空档里看到的都是「这句还没擦完」。

## 2. 逐字数据从哪来：酷狗 **KRC**

### 2.1 各家渠道穷举结果

| 渠道 | 结果 |
| --- | --- |
| 网易云 `api/song/lyric` | `klyric` **空**，`lyric/v1` 的 token **不带时间** ⇒ 拿不到逐字 |
| QQ `fcg_query_lyric_new.fcg` | 只给**明文 LRC** ⇒ 拿不到 |
| QQ `musicu.fcg` 的 `GetLyric` | 全部 `code 500003 / subcode 860100001` ⇒ 要登录态 |
| 酷狗 `lyrics.kugou.com/download?fmt=krc` | **给逐字数据，但是加密的** ⇒ 解密后就有 |

### 2.2 KRC 容器格式（有权威来源，不是猜的）

`download?fmt=krc` 的 `content` 是 base64，解码后是
`"krc1"` + 异或密文；密文按 16 字节密钥**循环异或**后 **zlib 解压**即得 KRC 文本。

格式语义由**两个互相独立的开源实现交叉确认**（npm `krc-reader` 与 `smart-lyric`）：

- 行标签 `[行首毫秒, 行时长毫秒]`
- 字标签 `<相对行首的偏移毫秒, 这个字的时长毫秒, 0>` + 这个字的文字

> 注意这跟 LRC 里那个语义至今**无权威来源**的 `<n,n,n>` **不是一回事**，
> 所以 KRC 走**独立解析器**（`KugouKrc.kt`），
> 不跟 `LyricWordTiming` 的自校准混在一起瞎猜。

### 2.3 为什么选酷狗

1. **用户的歌词就是酷狗这一份** —— 真机诊断包里「歌词已应用」的 54 行，
   与酷狗 `krc.php` 的行时间戳**逐行一致**（`[00:27.71]`），
   与网易云的 `[00:27.379]` 不同；
2. KRC 语义**有权威来源**（两个独立实现交叉确认）；
3. 解密只需 **XOR + zlib**（`java.util.Base64` + `Inflater`）——
   **零第三方依赖**，API 26+ 可用，JVM 可单测；
4. 复用 App **已有的**酷狗 URL（`lyrics.kugou.com/search?hash=` 已在用），
   只把 `fmt=lrc` 换成 `fmt=krc` ⇒ **不增加额外请求轮次**。

## 3. 实现

| 文件 | 改动 |
| --- | --- |
| `core/media/KugouKrc.kt`（**新增**） | KRC 解密（base64 → `krc1` → 循环异或 → zlib）+ 解析（行标签/字标签 → `LyricLine.words`）+ **时间窗自检**（末字结束超出下一行行首 + 容差 ⇒ 丢掉逐字数据） |
| `core/media/LyricWordTiming.kt`（**新增**） | `<n,n,n>` 的**语义自校准**（绝对 / 相对行首两种口径，按全篇投票定，判不出来就丢弃）；`mergeWords` 按行首时间戳**完全一致**贴词 |
| `core/media/MusicModel.kt` | `LyricLine` 加 `words`；`wipeFraction` 有逐字时间时走 `wipeFractionByWord`；`LrcParser` **不再直接剥掉** `<n,n,n>` |
| `core/media/OnlineLyricCatalog.kt` | 酷狗渠道**先取 `fmt=krc`**（命中即带逐字），失败才回退明文 LRC；取词循环**在同一层级内**优先选带逐字的候选 |
| `core/media/KugouLyricFetcher.kt` | 旧接口兜底路径也先试 KRC |

**回退口径不变**：没有任何逐字数据时，擦除仍是**行级线性**，
与原厂 APK 公式完全一致 —— 不猜、不倒退。

**贴词而不是换一份**：各家歌词分段不同（网易云 62 行 / QQ·酷狗 54 行），
换一份 = 换分段版式（属于另一个改动）。
`mergeWords` 按**行首时间戳完全一致**贴逐字，正文与行数一个字不动 ⇒ 对显示零风险。

## 4. 模拟器验证

### 4.1 逐字歌词确实取到了

```
I/CarDesk/Media: 酷狗 KRC 逐字歌词命中 lines=54 wordLines=54
I/CarDesk/Media: 歌词渠道命中 provider=kugou score=205 ... lines=54 wordTiming=true
I/CarDesk/Media: 歌词已应用 key=t:怎么我睡不着你也不来救我 lines=54 sourceDurationMs=277000
```

**54 行全部带逐字时间**（`wordLines=54`）。

### 4.2 新旧包同位置 A/B（这是本轮最关键的证据）

位置用媒体探针**钉死**（`--es position <ms> --es play false`，暂停态位置冻结），
所以两次截图是**同一个毫秒**。

#### 位置 103497ms（末字 103197ms 结束，下一句 120588ms 才开始 ⇒ 歌手已唱完）

| | 擦除比例 | 图 |
| --- | --- | --- |
| **旧包 4145（行级线性）** | **23.2%** | `r81-old-103497-lyric.png` —— 只有「怎么我」三个字亮 |
| **新包 4146（逐字）** | **100%** | `r81-new-103497-lyric.png` —— **整行全亮** |

> 这正是用户那句话的具象化：**旧包在歌手唱完之后，还剩 10 个字没擦完**。

#### 位置 101000ms（唱到一半）

| | 擦除比例 | 图 |
| --- | --- | --- |
| 旧包 4145 | 12.0% | `r81-old-101000-lyric.png` —— 只有「怎么」亮 |
| 新包 4146 | **64.6%** | `r81-new-101000-lyric.png` —— 「怎么我睡不着你也」+ 半个「不」亮，**边界卡在正在唱的那个字上** |

#### 播放中的连续帧

`r81-play-series-lyric.png`（3 帧，间隔 1.6s）：擦除边界**逐字推进**，
最后整行全亮 —— 不是阶跃。

### 4.3 崩溃判定

```
pid 3108 -> 3108
✅ 无崩溃（进程存活 / 无 SIGSEGV / 无新增 tombstone）
```

### 4.4 正式包复验

装 `dist/CarDesk-ecarx-cardesk-4146.apk` 重截 →
`r81-release-103497-lyric.png`，与开发包一致（整行全亮），
日志同样是 `lines=54 wordLines=54`。

## 5. 单测

新增 35 个（`KugouKrcTest` 18 + `LyricWordTimingTest` 17），
**全量 1157 个用例全绿**（上一轮 1122）。

关键几条：

| 用例 | 钉住的行为 |
| --- | --- |
| `wipe_byWord_reachesFullWhenSingerFinishesEarly` | 歌手唱完 ⇒ 擦除**当场 100%**（行级此时只有 24%） |
| `lrcParser_wipeReachesFullWhenSingerFinishesEarly` | 同上，走 LRC 端到端 |
| `attach_detectsRelativeToLine` / `attach_detectsAbsolute` | 两种口径**各自**判得出 |
| `attach_keepsNothingWhenAmbiguous` | 判不出来 ⇒ **一律不挂词** |
| `parse_dropsWordsOutsideTimeWindow` | 逐字时间冲出本行时间窗 ⇒ **丢掉** |
| `mergeWords_requiresExactTimestampMatch` | 差一毫秒**不贴** |
| `lrcParser_plainLrcFallsBackToLineLevel` | 没逐字 ⇒ 行级线性**照旧** |

## 6. 本轮**没有**验证的（如实标注）

- **真机上的表现**：本轮模拟器验证用的是 AVD `cardesk-aosp`，
  真车（`com.tencent.wecarflow` 网络环境）能否稳定取到酷狗 KRC **未验证**；
- **QQ 音乐广播那条通道的逐字**：`com.tencent.wecarflow` 实测**不推歌词广播**
  （原厂文档也这么写），所以 `<n,n,n>` 自校准这条路径在真机上**没有数据可自证**，
  这次真正生效的是**酷狗 KRC** 这条通道；
- **QQ QRC 通道**（需 DES 解密 + `GetPlayLyricInfo` POST）本轮**未实现**，
  不是阻塞项（酷狗已足够），但它是「不带酷狗时」的备选；
- 车门 0/1 语义、频谱条是否随音乐跳动 —— 与本轮无关，沿用第八十轮结论。

## 7. 产物

- `dist/CarDesk-ecarx-cardesk-4146.apk`
- versionCode `4146` / versionName `1.0.1050-system`
- SHA-256 `0cdd310adae03523b41998af78bc0a4692a65cb286951da6587d560ab47b53da`
- 出包方式：`tools/release.sh`（rsync 到全新临时目录 + 干净全量构建 + 全量单测）

## 7.1 发布记录（v1.0.1050 / 4146）

- commit `c6f0872`、tag `v1.0.1050`（已推送）
- Release API 回读核验：
  - `tag_name=v1.0.1050`、`name=CarDesk 4146 / 1.0.1050-system`
  - `draft=false`、`prerelease=false`
  - `releases/latest` = v1.0.1050
  - 资产 `CarDesk-ecarx-cardesk-4146.apk`，1,941,236 B，
    `digest = sha256:0cdd310adae03523b41998af78bc0a4692a65cb286951da6587d560ab47b53da`
  - 线上下载的包与本地 `dist/` 的包 **`cmp` 逐字节一致**

> 发布过程中踩的坑（GitHub 网页发布，**下次照着避开**）：
>
> 1. **自动保存的草稿会先落成 `untagged-<id>`**，第一次点「Publish release」
>    只把草稿建出来 + 资产传上去（`POST /releases` 201、资产 204/200），
>    **并没有真正发布** —— 要再点一次才发出去。
> 2. **标题输入框的 `fill` 不会持久化**：GitHub 的 release 标题只认自己的
>    `change` 事件，`fill` 报成功但服务端存的是空。**填完必须按一次 Tab**（触发 change），
>    否则发布出来是空标题（GitHub 会拦，但草稿里就一直是空的）。
> 3. **网络不稳**：`POST /releases` 偶发 `ERR_CONNECTION_RESET`（10s 超时重置），
>    报出来的是 GitHub 的一句看不懂的「Something went really wrong, and we can't process that file」。
>    刷新页面重试即可（`upload_files` 报 `browser_action_failed` 也可能是这个原因，
>    **先查页面上的附件有没有挂上，别急着重传**）。
> 4. **判断「有没有发出去」要看 API，不要看页面**：
>    `api.github.com/repos/Bigcaibird/cardesk/releases/tags/<tag>` 返回 404 = 没发出去；
>    `releases/latest` 指到本轮 tag 才算真的发了。

## 8. 抓图过程中的坑

1. **裁剪框写成了 (x, y, w, h)**，而 PIL 的 `crop` 要的是
   `(left, upper, right, lower)` ⇒ 直接报 `Coordinate 'right' is less than 'left'`。
   已修正 `tools/lyric_shot.py` 里的 `CROP`。
2. **单测夹具别塞整首歌的 KRC** —— 本轮第一次写夹具时把整段 base64 塞进测试文件，
   工具输出被撑爆、文件写入失败。改成**手工构造 2 行的 KRC**（离线按同样协议加密，
   base64 只有 116 字符）—— 既能测全链路，又不会撑爆仓库。
3. **`[00:05.00]` 是 5 秒不是 5 分钟** —— 写断言时把 `min*60000 + sec*1000` 算错，
   3 个断言先红后绿。行级插值是 `(位置 − 行首)/(下一行首 − 行首)`，
   用毫秒算，别按「分」想。
