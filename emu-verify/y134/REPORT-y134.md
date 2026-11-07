# 第134轮 验证报告：接管方控后仪表没有图片（桥没实现 `getArtwork`）

> 用户诉求：
> 「目前接管方控之后，仪表只有歌名和歌手，没有图片；
> 车机默认的爱趣听是有图片的，试着修复这个问题。」

## 1. 环境

| 项 | 值 |
|---|---|
| 模拟器 | `emulator-5556`（Android 10 / SDK 29 / arm64-v8a / 1920×1080） |
| 包名 | `com.ecarx.cardesk`（**system 变体**，与真车同包名，uid 1000） |
| 基线 | 第133轮 `57f15cb`（4231 / 1.0.1135-system） |
| 本轮产物 | 4232 / 1.0.1136-system |
| 取证来源 | `carmedia/MultiMedia.apk`（原车多媒体，**内含完整 `com.ecarx.sdk.mediacenter` SDK**） |

## 2. 根因：原车问我们要封面，我们没接

### 2.1 原车 SDK 的接口（反编译实锤）

`carmedia/MultiMedia.apk` 里带着**整个原车媒体 SDK**，反编译后接口长这样：

```java
// com.ecarx.sdk.mediacenter.IMediaInfo
public interface IMediaInfo {
    String getTitle();
    String getArtist();
    Uri   getArtwork();      // ← 仪表要的就是它
    ...
}

// com.ecarx.sdk.mediacenter.IMusicPlaybackInfo
public interface IMusicPlaybackInfo {
    String getTitle();
    String getArtist();
    Uri   getArtwork();
    Uri   getNextArtwork();
    Uri   getPreviousArtwork();
    ...
}

// com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction.IMedia（仪表侧）
public interface IMedia {
    String getTitle();
    String getArtist();
    Uri   getArtwork();      // ← 仪表渲染图片的依据
    ...
}
```

### 2.2 原车自己是怎么填这个 Uri 的

原车 `MediaCenterHelper.D(MediaMetadataCompat, String)`（同一个 apk 里）：

```java
if (containsKey(DISPLAY_ICON_URI) && !empty) {
    hVarA.b(Uri.parse(getString(DISPLAY_ICON_URI)));
} else if (containsKey(ART_URI) && !empty) {
    String s = getString(ART_URI);
    File f = new File(s);
    if (f.exists()) hVarA.b(Uri.fromFile(f)); else hVarA.b(Uri.parse(s));
} else if (!containsKey(ALBUM_ART_URI) || empty) {
    hVarA.b(Uri.parse(""));        // ← 没有图时给「空 Uri」，不是 null
} else {
    hVarA.b(Uri.parse(getString(ALBUM_ART_URI)));
}
```

→ **取值顺序 `DISPLAY_ICON_URI → ART_URI → ALBUM_ART_URI`**，
`ART_URI` 是本地裸路径时文件存在才转 `Uri.fromFile`，没图时给 `Uri.parse("")`。

原车桌面自己用 **Glide** 加载这个 Uri（`analysis/inner_jadx` 的
`MusicManager.loadCoverIntoViews(Uri, String)`）→ `content://` / `file://` 都能吃。

### 2.3 我们的桥漏了什么

`OfficialMediaBridge` 的动态代理只实现了：

```kotlin
"getTitle" -> s.title
"getArtist" -> s.artist
"getAlbum" -> s.album
"getDuration" -> s.duration
...
else -> defaultValue(method.returnType)   // ← getArtwork 落到这里 → null
```

**`getArtwork()` 从来没被实现过** → `defaultValue()` 对引用类型返回 **null** →
原车推给仪表的封面是空的 → **仪表只有歌名和歌手，没有图片**。

> 参考实现 **UNI 桌面的桥也是同样漏的**（`uni-launcher` 的
> `OfficialMediaBridge.playbackValue` 里也没有 `getArtwork`）——
> 这是整条传承链上的共同缺口，不是我们移植时丢的。

**为什么歌名歌手有、偏偏图没有**：歌名/歌手的 getter 我们实现了，
`getArtwork` 落在 `else` 分支 → null。**仪表能看到歌名歌手，恰恰证明桥是通的**，
缺的只有封面这一项。

## 3. 改了什么

| 文件 | 改动 |
|---|---|
| `core/media/BridgeArtwork.kt`（新，纯逻辑） | ① 封面 Uri 取值优先级（**与原车一致**）② Uri 合法性 ③ 本地路径判定 ④ `getArtwork` 等 getter 的映射 ⑤ 封面变化判定 ⑥ 歌的 key |
| `core/media/ArtworkProvider.kt`（新） | `ContentProvider`：把我们自己的封面位图暴露成仪表读得到的 `content://` Uri；`ArtworkStore` 负责落盘与拼 Uri |
| `core/media/OfficialMediaBridge.kt` | ① `Sample` 加 `artwork` / `songKey` ② `Sample.from` 按原车口径从会话 metadata 取封面 Uri ③ `playbackValue` **实现 `getArtwork` / `getNextArtwork` / `getPreviousArtwork`** ④ 推给原车的指纹**含封面** ⑤ 按歌 key 的封面缓存 + 异步竞态处理 + 日志限频 |
| `core/media/MusicController.kt` | 封面到手 / 会话自带位图时**推给桥**（补齐「没有会话」的来源） |
| `core/media/MediaBridgePolicy.kt` | 诊断字段加 `artwork` / `artworkSource` |
| `AndroidManifest.xml` | 声明 `ArtworkProvider`（authority 用 `${applicationId}.artwork`） |

### 3.1 封面 Uri 的来源优先级

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1 | 会话 metadata 的 **DISPLAY_ICON_URI → ART_URI → ALBUM_ART_URI** | 与原车**逐字一致**的口径 |
| 2 | **我们自己的封面**（`CoverLoader` 搜到的网络图 / 会话自带的位图） | 会话只给位图、或**根本没有会话**时 |

第 2 条是必须的：**QQ 音乐车机版不发布会话**，它的广播里也**没有封面字段**
（实测 apk：广播字段只有 `key_title` / `key_artist` / `key_album` / `key_is_fav`），
所以 QQ 音乐的图只能靠我们自己搜。

### 3.2 为什么用 `content://` 而不是 `file://`

我们的封面文件落在 `/data/user/0/com.ecarx.cardesk/files/bridge-artwork/`，
**别的进程能不能读取决于 uid** —— 仪表未必跟我们同 uid。
`content://` 由**我们的进程**替别人读，权限只取决于 provider 的导出声明，与对方 uid 无关。

provider `exported=true` 且**不设任何读写权限**（只暴露一张封面图，路径只认自己生成的 key）。

### 3.3 三个容易踩的坑（都踩过并修掉了）

1. **authority 写死** → 调试包（`com.cardesk.launcher`）与系统包（`com.ecarx.cardesk`）
   会**抢同一个 authority**，表现为「provider 存在但永远读不到文件」。
   改成 `${applicationId}.artwork` + 代码里 `context.packageName` 现算。
2. **硬件位图不能直接压缩** —— `MediaMetadata.getBitmap()` 在 API 26+ 返回的是
   `Config.HARDWARE` 位图，`Bitmap.compress` 对它直接抛
   `IllegalStateException("unable to compress hardware bitmaps")`，
   被 `runCatching` 吞掉 → 这一路静默失败（仪表还是没图）。先 `copy(ARGB_8888)` 再压。
3. **日志被刷爆** —— 会话每 200ms~2.5s 推一次 metadata，不去重的话
   「封面已转成仪表可读的 Uri」会连着打十几条。按限频规矩改成
   **只在变化时记**（同一首歌、且新图不比已烘焙的更大 → 不重复烘焙）。

## 4. 模拟器端到端验证

### 4.1 替身：白名单里的包名发一个真会话

`com.tencent.qqmusic`（**在桥的白名单里**、模拟器上没装）用 `ncprobe` 改包名重编，
发一个真 `MediaSession`：歌名「春风吹」/ 歌手「方大同」/ 专辑 / 时长
（模拟的是「只有歌名没有图」的来源 —— 和 QQ 音乐车机版同一类）。

### 4.2 封面链路跑通（`y134-log-artwork.txt`）

```
开始取封面 key=id:150619 title=春风吹 artist=方大同 hasUri=false uri=
封面加载成功（网络搜索） url=https://y.gtimg.cn/music/photo_new/T002R500x500M000004J3oTJ2E4cuo.jpg w=500 h=500
封面已转成仪表可读的 Uri key=com.tencent.qqmusic|春风吹|方大同
  uri=content://com.ecarx.cardesk.artwork/cover/com.tencent.qqmusic_春风吹_方大同
  w=500 h=500 current=com.tencent.qqmusic|春风吹|方大同 命中=true
```

链路：**会话 → 封面搜索 → 落盘 → `content://` Uri → 缓存命中当前这首歌**。

### 4.3 provider 跨进程吐图（`adb shell` 是另一个 uid）

```
$ adb shell content read --uri 'content://com.ecarx.cardesk.artwork/cover/com.tencent.qqmusic_春风吹_方大同' > cover.jpg
$ file cover.jpg
cover.jpg: JPEG image data, JFIF standard 1.01, ... baseline, precision 8, 500x500, components 3
$ shasum -a 256 cover.jpg 磁盘上的同名文件
123cd7bef89f43c36465662e486aae82d43e9f904e09db39741db085f82d98ff  cover.jpg      ← provider 读回来的
123cd7bef89f43c36465662e486aae82d43e9f904e09db39741db085f82d98ff  磁盘文件      ← 应用自己写的
```

**逐字节一致** → 仪表进程（另一个 uid）能完整读到这张封面。

### 4.4 桌面卡片也确实是这张图

`y134-01-desktop-cover.png`：右侧音乐卡与底部 Dock 都是「春风吹 / 方大同」的封面。

## 5. 单测与反向验证

| 项 | 结果 |
|---|---|
| 全量单测 | **1841 项全绿**（新增 `BridgeArtworkTest` 21 项 + `ArtworkStoreTest` 6 项） |
| 反向验证 | `tools/verify_y134_reverse.sh`：**11 条全部变红**，还原后 0 红灯 |

反向验证逐条结果（`RED` = 失败用例数）：

| # | 改坏的地方 | RED |
|---|---|---|
| 1 | 认不出 `getArtwork`（回到「压根没实现」） | 1 |
| 2 | 取值顺序颠倒（`ART_URI` 压过 `DISPLAY_ICON_URI`） | 2 |
| 3 | 不认 `content://`（MediaStore albumart 是最常见的一种） | 6 |
| 4 | 认 `android.resource://`（跨进程根本读不到） | 3 |
| 5 | 本地路径文件不存在也当有图 | 1 |
| 6 | 封面变了不重推（异步到手的图永远推不出去） | 1 |
| 7 | `sanitize` 放过 `..`（目录穿越） | 1 |
| 8 | `sanitize` 放过 `/`（斜杠能把路径拆开） | 3 |
| 9 | authority 写死（调试包与系统包抢同一个 authority） | 1 |
| 10 | 歌的 key 恒为空（迟到的封面认不出是不是这一首的） | 2 |
| 11 | 代理里把封面应答改成 `null`（接线检查，回到「仪表没图」） | 1 |

## 6. 产物

| 项 | 值 |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4232.apk` |
| versionCode / versionName | 4232 / 1.0.1136-system |
| 大小 | 2 315 549 字节 |
| SHA-256 | `27681da7b5be95d10934ebd316bd11284ade1dbcd6291ad3649d4251aba8c118` |
| 签名 | AOSP platform（SHA-256 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`） |

**正式包 4232 复验**（`y134-log-artwork-release-4232.txt`）：装正式包后重跑同一场景 ——
封面搜索 → 落盘 → `content://` Uri → 命中；跨进程读回 47 773 字节 JPEG，
SHA-256 `123cd7bef89f43c36465662e486aae82d43e9f904e09db39741db085f82d98ff`
与磁盘文件**逐字节一致**。

## 7. 遗留 / 待真车复验

1. **「仪表真的把图画出来了」这一段模拟器上验不了** —— 模拟器没有原车
   `ecarx.xsf.mediacenter` 服务，也没有仪表（DIM）。
   **真车复验**：装包后放一首歌，看仪表有没有图；
   诊断包看 `bridge.artwork`（Uri）与 `bridge.artworkSource`
   （`session` / `broadcast` / `external` / `none`）——
   `artwork` 有值但仪表没图 = 原车侧不认这个 Uri；`artwork` 为空 = 我们这边没拿到图。
2. **会话只给位图时**，走的是我们自己的位图 → 同样落盘 → `content://`，
   与「网络搜到的图」同一条路。
3. **`getNextArtwork` / `getPreviousArtwork` 仍返回 `null`** ——
   与原车 `MusicPlaybackInfo` 的行为一致（我们只有一首）。
4. **没有发 GitHub Release、没有传 R2**（等明确指示）。
