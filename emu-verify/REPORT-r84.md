# 第八十四轮 · 云端应用库（手机传 APK，车机直接装）

> 诉求原文：「是否可以基于 r2，做一个在线文件应用下载功能，
> 这样我可以不用再带 u 盘去车上安装上传文件」
>
> 日期：2026-10-30　被测包：`com.ecarx.cardesk` 4150 / `1.0.1054-system`
> 设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）

---

## 1. 结论

- **能，而且不用带 U 盘了**：手机浏览器打开一个网页 → 选 APK → 传到 R2；
  车机在「设置 → 网络与升级 → **云端应用**」里看到它，点「下载安装」就装上。
  **出门前传好，到车上直接装**，也不用和车机连同一个局域网（车机走 4G 就行）。
- **上传端是 Cloudflare Worker**（`worker/`）：R2 的写入必须带凭据，
  而凭据放进浏览器里的网页等于公开，所以网页只发请求、**真正的写入在 Worker 里**
  （R2 binding，凭据由 Cloudflare 托管，不进代码、不进仓库、不进手机浏览器）。
  写操作要口令，没配口令时**一律拒绝**。
- **车机侧全链路已在模拟器上跑通并核验**：读真 R2 的清单 → 下载 → 校验长度与 SHA-256
  → 静默安装 → 设备版本真的从 4148 升到 4150，
  且**设备上的包与线上、本地三方逐字节一致**。
- 单测：全量 **1196** 个用例全绿（上一轮 1172，本轮 +24）。

## 2. 需求与边界（用户拍的三件事）

| 问题 | 用户的选择 |
|---|---|
| 文件怎么从手机传到 R2？ | **Cloudflare Worker 上传页**，手机浏览器直接传（密钥存 Worker secret，不进仓库） |
| 车机端要做出哪些能力？ | **只做 APK 安装中心**（最小可用）—— 不做通用文件下载，也不做车机→云端上传 |
| 文件私密性？ | **只有自己用**，公开可读、路径难猜就行（不引入鉴权） |

因为选了「只做 APK 安装中心」，本轮**没有**做：通用文件下载（只下 APK）、
车机往云端上传（照片回传）、已安装版本的「已是最新」判断（列表全给你，不比版本）。

## 3. 设计

### 3.1 全链路

```
手机浏览器 ──POST /api/upload──▶ Cloudflare Worker ──▶ R2
                                    │  R2 binding（凭据不出 Cloudflare）
                                    ├─▶ cardesk/apks/<文件名>.apk
                                    └─▶ cardesk/apks.json        （清单，每次上传后自动重写）

车机 CarDesk ──GET https://file.chuxi.bid/cardesk/apks.json──▶ 清单（公开只读）
              └── 下载 APK ──校验长度 + SHA-256──▶ 静默安装（PackageInstaller）
                                                    └─▶ 不行就退系统安装器
```

**车机端只依赖 R2 的公开自定义域**，不依赖 Worker 地址 ——
Worker 换域名、换账号，车机上的 App 一点不用改。

### 3.2 清单格式

R2 只是对象存储、没有「应用列表」这种东西，所以格式自己定
（权威在 `core/apkstore/ApkIndex.kt` 的解析端）：

```json
{
  "schema": 1,
  "updatedAt": "2026-10-30T09:45:37Z",
  "items": [
    {
      "name": "酷狗音乐",
      "file": "kugou.apk",
      "key": "cardesk/apks/kugou.apk",
      "size": 12345678,
      "sha256": "64位小写十六进制",
      "addedAt": "2026-10-30T09:45:25Z"
    }
  ]
}
```

- `key` 写 **R2 对象 key**（相对域名根）—— 和「在线更新」的 `latest.json` 同一个思路：
  填什么就传什么，改域名不用改清单。
- `sha256` 是车机下载后要校验的摘要；**清单没给就不校验**（不猜）。
- **一条坏数据只跳掉那一条**，不会让整份列表变空
  —— 整个应用列表因为一条脏数据就全空，比少一个包难用得多。
  （这和「在线更新」不一样：那边只有一条，坏了就整体读不懂。）

### 3.3 车机端和「在线更新」的关系

同一个 bucket、同一个公开域、同一套「下载 → 校验 → 安装」链路：

| | 在线更新 | 云端应用库 |
|---|---|---|
| 清单 | `cardesk/latest.json` | `cardesk/apks.json` |
| 条目 | 一个（CarDesk 自己） | 多个（任意 APK） |
| 版本比较 | 要比 versionCode | 不比（列表全给你） |

所以把下载 + 校验抽成了 `core/net/FileDownload.kt`（`AppUpdate` 和 `ApkStore` 共用），
`Digest` 也从 `core/update` 挪到了 `core/net` —— 方向是「上层依赖下层」。

## 4. 改动清单

**新增**

| 文件 | 作用 |
|---|---|
| `worker/src/index.js` | Worker：上传页 + 上传/删除/清单三个 API |
| `worker/wrangler.toml` | 部署配置（R2 binding） |
| `worker/README.md` | 部署步骤 + 清单格式 + 权限模型 |
| `app/.../core/apkstore/ApkIndex.kt` | 清单格式与解析 |
| `app/.../core/apkstore/ApkStore.kt` | 拉清单 / 下载 / 安装 |
| `app/.../core/net/FileDownload.kt` | 通用下载 + 长度/SHA-256 校验（两边共用） |
| `app/.../core/net/Digest.kt` | SHA-256（从 `core/update` 挪过来） |
| `tools/publish_apks.py` | 命令行上传端（与 Worker 同格式） |

**改**

| 文件 | 改动 |
|---|---|
| `ui/SettingsOverlay.kt` | 新增「云端应用」页 + `pillButton`（挂到指定容器的按钮） |
| `core/update/AppUpdate.kt` | 下载逻辑改用 `FileDownload` |
| `core/log/LogTags.kt` | 新增 `CarDesk/ApkStore` |
| `tools/publish_r2.py` | `put()` 支持自定义头；`request()` 回传响应头（读 `x-amz-meta-*` 要用） |
| `README.md` | 新增「云端应用库」一节 + 目录树 |
| `EMULATOR-VERIFY.md` | 新增 H7（H59~H66） |

**删**：`core/update/Digest.kt`（挪到 `core/net`）。

## 5. 单测

全量 **1196** 个用例全绿（上一轮 1172 − 搬走的 3 个 + 新增 27 个）。

| 文件 | 个数 | 覆盖 |
|---|---|---|
| `core/apkstore/ApkIndexTest.kt` | 15 | 完整清单 / 相对 key 拼地址 / 绝对地址原样 / 非 http 不认 / 一条坏不影响其它 / items 不是数组 / 坏 JSON 不抛 / 摘要转小写 / 非法摘要当没有 / 缺名字退到文件名 / 缺大小为 0 / **中文包名也能拼出地址** / **带空格的名字也能拼出地址** |
| `core/net/FileDownloadTest.kt` | 12 | 摘要 3 个（已知值 / 读不到 / 空文件）+ 下载 9 个（正常 / 长度不符 / 摘要不符 / HTTP 非 200 / 内容太小 / 没给长度就不校验 / 摘要大小写 / 连不上 / 进度到 100） |

`FileDownloadTest` 里**真的起了个本地 HTTP 服务**（用 `java.net.ServerSocket` 手写，
三十来行）—— 只测纯函数测不出「截断」「404」「连不上」这些真问题。

> `com.sun.net.httpserver` 在 **Android 编译单元测试**的类路径上不存在
> （属于 `jdk.httpserver` 模块，AGP 的测试 classpath 不含），
> `java.net.ServerSocket` 在 `java.base` 里，永远可用 —— 所以自己手写。
> 它只出现在**测试**里，永远不会进 APK（App 侧仍然是零第三方依赖）。

## 6. 实现过程中踩到的三个真坑

### 6.1 中文展示名进不了 HTTP 头

第一次上传直接失败：

```
!! 上传失败 HTTP 0：transport-error: 'latin-1' codec can't encode characters
   in position 12-20: ordinal not in range(256)
```

对象元数据（S3 侧就是 `x-amz-meta-*` 头）**只能是 ASCII** ——
HTTP 头值按 latin-1 编码，中文放进去直接就炸。

**修法**：展示名存的时候做**百分号编码**（`encodeURIComponent` / `quote(safe="")`），
读的时候解回来。Worker 和 Python 两边**用同一套编码**，所以互相都认得。

（顺带一提：文件名本身带中文是**没问题**的 —— 那是对象 key，走 URL 路径编码。）

### 6.2 R2 ListObjects 的 query 值必须逐字编码

重写清单时列对象，报 `SignatureDoesNotMatch`。把服务端返回的
`CanonicalRequest` 打出来一看：

```
GET
/file/
list-type=2&prefix=cardesk%2Fapks%2F      ← 服务端规范化后的样子
```

我签名时用的是 `prefix=cardesk/apks/`（**裸斜杠**）——
查询参数的值必须逐字百分号编码（连 `/` 也要编成 `%2F`），
否则服务端按 RFC3986 重新规范化后两边对不上，签名必然不同。

**修法**：查询参数用 `quote(prefix, safe="")`；
但**对象 key 的路径编码不能这么干**（路径里的 `/` 是分隔符，必须保留）——
所以这两个地方要用两个不同的编码函数，别混。

### 6.3 百分号编码把安全检查绕过去了

车机端拼下载地址时做了百分号编码（中文包名会让 `URI.create` 直接抛异常，
整条记录被静默丢掉）。但编码之后：

```
file:///etc/passwd  →  file%3A///etc/passwd
```

再看协议检查就变成了「这是个相对路径」，**放行了** —— 而清单是外部数据，
不能让它把 App 指向 `file://` 之类的地方。

**修法**：协议检查必须针对**原始**串做，编码在它之后：

```kotlin
if (ABSOLUTE_URI.containsMatchIn(rawKey)) {
    if (!rawKey.startsWith("http://") && !rawKey.startsWith("https://")) return null
}
```

单测 `非 http 协议的地址不认` 当场就抓到了这次回归（它本来是通过的）。

## 7. 模拟器验证

被测包：4148 的**临时构建**（源码与 4150 完全相同、只有版本号不同）——
这样「装上去的确实是从云端下的那个包」才说得清。
云端清单：真 R2 的 `https://file.chuxi.bid/cardesk/apks.json`。

| # | 项 | 结果 |
|---|---|---|
| H59 | 页面在 | ✅ `r84-apkstore-list.png` |
| H60 | 清单真能解析 | ✅ `云端应用清单已拉取 count=1 updatedAt=2026-10-30T09:45:37Z` |
| H61 | 公网下载 + 校验 | ✅ `云端应用下载完成 … bytes=1957140 elapsedMs=3644` |
| H62 | 静默安装真的生效 | ✅ 设备 4148 → **4150 / `1.0.1054-system`** |
| H63 | 装完确实是那个包 | ✅ `2851c53f…caa982`（线上 = 本地 = 设备） |
| H64 | 中文展示名没乱码 | ✅ `CarDesk 4150（云端应用库自测）` |
| H65 | 刷新按钮 | ✅ 再次 `清单已拉取 count=1` |
| H66 | 不闪退 | ✅ `Fatal signal` / `FATAL EXCEPTION` 计数 **0** |

**装完之后系统弹了「Select a Home app」** ——
CarDesk 是 HOME 应用，被自己更新掉之后系统要重新确认主屏程序，
这是**重装 launcher 的正常副作用**，不是崩溃。

证据：`emu-verify/r84-apkstore-*.png`（4 张）、`emu-verify/update-apkstore-log.txt`。

### 7.1 上传端（Worker）本轮没上真机

Worker 需要用户自己的 Cloudflare 账号执行 `npx wrangler deploy` 才能拿到地址
（见 `worker/README.md`）。本轮验的是**车机侧**的完整链路；
上传端用 `tools/publish_apks.py` 走 S3 API 产生**同一份格式**的清单 ——
格式的权威在 `core/apkstore/ApkIndex.kt`，两边都照它写。

## 8. 发布

- 产物：`dist/CarDesk-ecarx-cardesk-4150.apk`，1,957,140 字节，
  SHA-256 `2851c53f8e398a9b0dfe5bbdba43e72a8e531de16c40904eaef4603bbdcaa982`
- 版本：`versionCode 4150` / `versionName 1.0.1054-system`
- 干净全量构建（`clean assembleSystem testDebugUnitTest`）+ 1196 单测全绿
- 云端应用库：`cardesk/apks/CarDesk-ecarx-cardesk-4150.apk`（1,957,140 B）+
  `cardesk/apks.json`（1 个条目，324 B），公开域匿名可读、包与本地逐字节一致

### 8.1 GitHub Release（源码与 tag 的分发渠道）

GitHub Release 继续发，和「在线更新」无关 —— App 的更新检查读 R2，
GitHub Release 只负责源码 / tag / APK 的分发。

| 项 | 值 |
|---|---|
| 仓库 | `Bigcaibird/cardesk`（Public） |
| Tag | `v1.0.1054`（= `versionCode 4150 - 3096`，沿用已推送的 Existing tag） |
| Release 名称 | `CarDesk 4150 / 1.0.1054-system` |
| 资产 | `CarDesk-ecarx-cardesk-4150.apk`（1,957,140 字节） |
| SHA-256 | `2851c53f8e398a9b0dfe5bbdba43e72a8e531de16c40904eaef4603bbdcaa982` |
| Release 标签 | `Latest` |
| 发布提交 | `b628640` |
| 发布时间 | `2026-10-30T10:03:07Z` |

**API 回读核验**（`releases/tags/v1.0.1054` → 200）：

```
tag_name     : v1.0.1054
name         : CarDesk 4150 / 1.0.1054-system
draft        : False
prerelease   : False
published_at : 2026-10-30T10:03:07Z
html_url     : https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1054
assets_count : 1
  asset      : CarDesk-ecarx-cardesk-4150.apk 1957140 bytes
  digest     : sha256:2851c53f8e398a9b0dfe5bbdba43e72a8e531de16c40904eaef4603bbdcaa982
releases/latest = v1.0.1054
```

**同一个包，四处一致** —— 本地 `dist/`、GitHub 资产、GitHub 自算 digest、
R2 上那一份（`cardesk/apks/` 与 `cardesk/` 各一份），sha256 全是
`2851c53f…caa982`；再从 Release 的下载链接把包拉下来 `cmp`，**逐字节相同**。

Release 正文只写改动点，不写实现细节（与用户的要求一致）：

```
**改动点**

- 新增「云端应用库」：手机上把 APK 传到 Cloudflare R2，车机上直接下载并安装
- 出门前传好，到车上不用再插 U 盘，也不用和车机连同一个局域网
- 云端应用下载新增文件长度与 SHA-256 校验，对不上就当没下成功
```

---

## 9. 这一轮新踩的 GitHub 发布坑

1. **刚 push 的 tag，Tag 下拉里看不到 —— 必须先 `reload`** ——
   本轮发布页是在 push tag **之前**打开的，打开后页面里还挂着一个
   `Uh oh! There was an error while loading. Please reload this page.` 的对话框，
   Tag 按钮停在「Select tag」。`reload` 之后 tag 下拉里就有 `v1.0.1054` 了
   （列表项 `role=menuitemradio`）。**tag 下拉是打开页面那一刻的快照**，不是实时的。
2. **`upload_files` 又报 `browser_action_failed`，但文件其实已经挂上了** ——
   和上一轮同一个坑，本轮第三次踩到。报错后页面已经从 `releases/new`
   跳到 `releases/edit/untagged-e708763c41fb99fb1fba`（草稿自动落成），
   资产行里文件名是 `CarDesk-ecarx-cardesk-4150.apk`、大小 `(1.87 MB)`，
   字段名 `release[release_assets_attributes][][name]`。
   ⇒ **已经是 release asset，不是待上传队列**，别重传。
3. **别用 `query(kind="dom")` 的 `value=` 属性判断资产文件名在不在** ——
   `input.js-release-asset-filename` 的 outerHTML 里 `value=""`，
   但语义树里它的 `inlinetextbox` 子节点名字就是
   `CarDesk-ecarx-cardesk-4150.apk`（GitHub 用 DOM property 填的，不写 attribute）。
   上一轮记的「标题不能看 `value=` 属性」是同一个坑，这次扩展到资产文件名。
4. **附件一挂上，页面会换 URL，但已填的标题和正文都不会丢** ——
   本轮实测：`releases/new` → `releases/edit/untagged-…` 之后，
   标题 `CarDesk 4150 / 1.0.1054-system` 仍在（语义树 `inlinetextbox`），
   正文预览渲染出来也还是那三条改动点。
5. **正文核对用 Preview 标签读渲染结果，比读 textarea 可靠** ——
   点 `Preview` 标签后
   `query(kind="text", selector=".markdown-body, .comment-body, #previewable-comment-form-component-…")`
   直接读到「改动点 + 3 条」，一眼就能看出有没有丢字、有没有多字。
6. **发布按钮的 `click` 这次报 `browser_action_failed`，但发布其实成功了** ——
   上一轮用 `click` + `selector: button.btn-primary[type="submit"]` 是「成功 + 跳页」，
   本轮同一个调用报了 `browser_action_failed`。**浏览器返回值不能作为发布成败的依据**，
   判发没发出去一律看 API：`releases/tags/<tag>` 404 = 没发出去；
   `releases/latest` 指到本轮才算发。（本轮是用户手动点了一下才发出去的。）
7. `scroll` 报的 `atStart` 依旧不可信 —— 本轮又遇到
   `actualDeltaY: -105, atStart: true`，实际页面还有一屏没滚完。
   以 `inspect` 里的 `rect.y` 为准。

---

## 10. 未能覆盖 / 待办

- **Worker 还没部署**：需要用户在 Cloudflare 账号下执行
  `npx wrangler login` → `npx wrangler secret put UPLOAD_TOKEN` → `npx wrangler deploy`。
  部署后手机浏览器打开 `*.workers.dev` 就是上传页。
  （**车机侧不依赖它**，现在就能用 `tools/publish_apks.py` 传。）
- **上传端没在真机/真手机上点过**：Worker 页面是纯静态 HTML + 原生 fetch，
  代码审过、语法校验过，但没在真实手机上跑过上传。
- **单包 100MB 上限**：Workers 免费版请求体上限，超过传不上去（代码里显式拒绝并提示）。
  超大 APK 得走别的路子。
- **车机端没有「已安装」判断**：列表里那个包已经装过了也会照装一遍
  （静默安装就是重装）。这一版按「最小可用」没做。
- **真车实测**：车机能不能访问 `file.chuxi.bid`（DNS、TLS 证书链、国内网络）只能在真车验；
  模拟器测得公网下载 1.9 MB 用时 **3.6 秒**。
- **R2 凭据轮换**：上传用的 S3 密钥是长期凭据，建议只在发布机上用；换密钥不影响已发布内容。
