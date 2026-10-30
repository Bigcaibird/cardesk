# 第八十三轮 · 在线更新源换 Cloudflare R2

> 诉求原文：「在线更新改成从 cf 的 r2 发布获取吧，域：`file.chuxi.bid`，
> API：`cfat_…`」
>
> 日期：2026-10-30　被测包：`com.ecarx.cardesk` 4149 / `1.0.1053-system`
> 设备：`emulator-5554`（AVD `cardesk-aosp`，android-10 / API 29 / 1920×1080@160dpi）

---

## 1. 结论

- **App 侧已全部换成 R2**：更新源从 `api.github.com/repos/…/releases`
  改为拉 `https://file.chuxi.bid/cardesk/latest.json`；
  解析、版本比较、下载、完整性校验、安装链路全部走 R2 自定义域，**App 内不存任何密钥**。
- **清单格式是我们自己定的**（R2 只是对象存储，没有「release」这种东西），
  定义在 `app/src/main/java/com/cardesk/launcher/core/update/R2Release.kt`，
  由 `tools/make_manifest.py` 生成、`tools/publish_r2.py` 上传。
- **模拟器实测通过**：拉清单 → 认新版本 → 下载 → SHA-256 校验 → 静默安装 → 设备版本真的升上去。
- **单测**：全量 **1172** 个用例全绿（上一轮 1165 − 删掉的 5 个 GitHubRelease 用例 + 新增 12 个）。

---

## 2. 取证：R2 侧到底有什么

用户给的是一个 Cloudflare API Token（`cfat_` 前缀）。查下来：

| 探查 | 结果 |
|---|---|
| `GET /client/v4/accounts` | ✅ 成功。account id = `889bd2a63b8928674c13e38148873176`（`328576666@qq.com's Account`） |
| `GET /client/v4/zones` | ✅ 能调通，但**列表为空** —— token 看不到 `chuxi.bid` 这个 zone |
| `GET /accounts/<acc>/r2/buckets` | ❌ `success=false`，`code 10000 Authentication error` —— **token 没有 R2 权限** |
| `GET /user/tokens/verify` | ❌ `1000 Invalid API Token`（cfat_ 类型不支持这个端点） |
| `https://file.chuxi.bid/` | ✅ 404（Cloudflare 的 27150 字节错误页）⇒ **自定义域已绑 bucket、公开可读** |
| `https://<bucket>.<acc>.r2.dev/` | ❌ 本机到 `*.r2.dev` 连接被 reset，**这条反查 bucket 名的路子走不通** |
| `https://<acc>.r2.cloudflarestorage.com`（S3 兼容端点） | ✅ 可达（但本机网络抖动，必须带重试） |

**卡点**：token 只有 account 读权限，列不出 bucket；`r2.dev` 也不可达。
→ 向用户确认后拿到 **bucket 名 = `file`**、**Access Key ID = `0b6ea892593bd36caad50f189ad628f2`**。

**这个 AK 是不是真的？** 不用猜，用 S3 端点的错误码区分：

```
AK = 32 位串，SK = 假的  →  SignatureDoesNotMatch   （key 被认出来了，只是签名不对）
AK = 不存在的 key        →  InvalidAccessKeyId
```

实测返回 `SignatureDoesNotMatch` ⇒ **`0b6ea…` 是有效的 R2 Access Key ID**。
（顺带：`ListBuckets` 返回 `AccessDenied` 是正常的 —— R2 的 S3 密钥按 bucket 授权，本来就列不出 bucket。）

### 2.1 S3 凭据格式的坑

- 用户口中的「S3 秘钥」`0b6ea892593bd36caad50f189ad628f2` 是 **32 位 = Access Key ID**；
  **Secret Access Key 是 64 位**，两者是配对的两把钥匙，缺一把签不了名。
- 用 cfat_ token 当 S3 密钥直接报错：
  `Credential access key has length 53, should be 32`。

---

## 3. 设计

### 3.1 清单格式（自己定的）

R2 是对象存储，只有「一个 key 一个字节流」，没有 release / tag / 附件列表。
所以约定一个固定路径放清单，格式自己定：

```
https://file.chuxi.bid/cardesk/latest.json
```

```json
{
  "schema": 1,
  "version": "1.0.1053",
  "versionCode": 4149,
  "tag": "v1.0.1053",
  "name": "CarDesk 4149 / 1.0.1053-system",
  "notes": "在线更新源从 GitHub Release 换成 Cloudflare R2……",
  "publishedAt": "2026-10-30T08:22:57Z",
  "apk": {
    "name": "CarDesk-ecarx-cardesk-4149.apk",
    "url": "cardesk/CarDesk-ecarx-cardesk-4149.apk",
    "size": 1941656,
    "sha256": "a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a"
  }
}
```

三个关键取舍：

1. **字段名不照抄 GitHub**（不用 `tag_name` / `assets` / `browser_download_url`）。
   照抄会让后来人以为「还在读 GitHub」，而两边语义其实不同
   （GitHub 的 `assets` 是数组、可以挂多个包；这里只有一个安装包）。
2. **`apk.url` 写 R2 对象 key（相对域名根）**，不是完整地址。
   「填什么就传什么」：清单里写的 key 跟往 bucket 里上传时用的 key 完全一致，改域名也不用改清单。
3. **带 SHA-256**。R2 自定义域走公网，下载**中断或被截断不一定会报错**，
   只看 HTTP 200 和「文件大于 1KB」拦不住「下了 90% 就断」的残包。
   下载完本地算一遍，对不上就当这次更新失败 —— 车机装一个残缺的 APK 比不更新更糟。

### 3.2 版本比较

优先比 `versionCode`（单调递增的整数，不会踩「1.0.9 比 1.0.10 新」的字符串坑）；
清单没给 `versionCode`（或本地读不到）时退回比版本号字符串
（`ReleaseVersion`，兼容手写的旧清单）。

### 3.3 安全边界

- **App 内不存任何密钥**：下载走自定义域匿名访问。
- **上传端凭据只从环境变量读**（`R2_AK` / `R2_SK`），脚本和文档里都不写密钥。
  仓库是 Public 的，密钥一旦进仓库等于公开。
- `tools/publish_r2.py` 自己实现 AWS SigV4，**不引三方库**（项目全程零第三方依赖的立场）。

---

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `core/update/R2Release.kt` | **新增**：清单格式 + `JsonLite` 解析（相对 key 解析、只认 http/https、摘要格式校验） |
| `core/update/Digest.kt` | **新增**：SHA-256 计算（下载完整性校验） |
| `core/update/AppUpdate.kt` | 更新源换 R2；`fetchRelease` / `check` / `download` 改造；下载后加长度 + 摘要校验 |
| `core/update/ReleaseVersion.kt` | KDoc 改成 R2 口径（逻辑不变） |
| `core/update/GitHubRelease.kt` | **删除**：更新源已换，旧模型成死代码 |
| `ui/dialogs/ExtraDialogs.kt` | 对话框文案与类型改 R2 |
| `ui/SettingsOverlay.kt` | 设置页「更新源」改成「Cloudflare R2 · file.chuxi.bid」 |
| `tools/make_manifest.py` | **新增**：从 APK 生成 R2 清单 |
| `tools/publish_r2.py` | **新增**：上传到 R2（SigV4 手写）+ 回读校验 |
| `tools/release.sh` | 出包时自动产出 `dist/cardesk-latest.json`；新增 `--notes` / `--notes-file` |

---

## 5. 单测

`app/src/test/java/com/cardesk/launcher/core/update/R2ReleaseTest.kt`（**新增 12 个**）：
完整清单解析 / 相对 key 拼地址 / 根路径斜杠 / 完整地址原样 /
非 http 协议不认 / 没有版本号读不懂 / 读不懂的返回体不抛异常 /
没有安装包也能出版本 / 摘要统一小写 / 不是 64 位十六进制的摘要当作没有 /
大小与包名缺失时的默认值 / 摘要算得对 / 读不到的文件返回空 / 空文件也有摘要。

`GitHubReleaseTest.kt`（**删除 5 个**）。

全量：**1172 个用例，0 失败 0 错误 0 跳过**（干净全量构建 + `assembleSystem`）。

---

## 6. 模拟器验证

### 6.1 为什么先打本地模拟 R2

改更新源要验的是**链路本身**（清单解析 → 比版本 → 下载 → 校验 → 安装），
与清单从哪儿来无关。本机起一个 `http://10.0.2.2:8899/`（模拟器看到的是宿主机），
放一份同格式的清单，就能把链路完整跑通，且不依赖外网与密钥。

做法：临时把 `AppUpdate` 的三个常量指向 `10.0.2.2:8899` 并把 scheme 改成 `http`，
验证完**原样改回** `https://file.chuxi.bid/`（改完 `grep` 确认无残留）。

### 6.2 实测

被测包：`com.ecarx.cardesk` 4100 / `1.0.1004-system`（临时构建，低于清单里的 4148，
这样「发现新版本 → 安装 → 版本真的升上去」才是一条完整的升级路径）。

| # | 项 | 结果 |
|---|---|---|
| H48 | 设置页更新源显示「Cloudflare R2」 | ✅ `r83-update-page-r2.png` |
| H49 | 拉到清单、认出新版本 | ✅ `检查更新：有新版本 current=1.0.1004-system latest=1.0.1052 apk=CarDesk-ecarx-cardesk-4148.apk` |
| H50 | 相对 key 拼成绝对地址 | ✅ `http://10.0.2.2:8899/cardesk/CarDesk-ecarx-cardesk-4148.apk` |
| H51 | 下载 + SHA-256 校验通过 | ✅ `更新下载完成 file=cardesk-update-1.0.1052.apk bytes=1941768 elapsedMs=49` |
| H52 | **静默安装真的生效** | ✅ `dumpsys package` → `versionCode=4148` / `1.0.1052-system`（设备上换成了线上那个正式包） |
| H53 | 已是最新分支 | ✅ `检查更新：已是最新 current=1.0.1052-system latest=1.0.1052` |
| H54 | 不闪退 | ✅ `Fatal signal` / `FATAL EXCEPTION` 计数 **0** |

> **H52 的旁证**：装完之后再打开设置页，更新源那一行显示的是**旧文案**
> （「GitHub Releases · Bigcaibird/cardesk」）—— 因为装上去的正是上一轮发布的
> 4148 正式包，它本来就还是 GitHub 源。这反过来证明装上去的确实是那个包。

证据：`emu-verify/r83-*.png`、`emu-verify/update-r2-log.txt`。

### 6.3 稳定性

`Fatal signal` / `FATAL EXCEPTION` 计数 0；安装过程中 App 被自己的包替换杀掉是正常现象
（PackageInstaller 换包会重启进程），装完 `versionCode` 确实变了。

### 6.4 真 R2 终验（交付前，公开自定义域）

拿到凭据后把 4149 的 APK 与清单传上 R2（bucket `file`），然后**从公开域名**再验一遍。

**上传**（`tools/publish_r2.py`，上传后自动回读校验）：

```
== 上传成功：cardesk/CarDesk-ecarx-cardesk-4149.apk
== 回读一致：1941656 字节，sha256 a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a
== 上传成功：cardesk/latest.json
== 回读一致：590 字节，sha256 e4fb9327c2fff6358ca9d5f6f5499a52dbfa5f09c7073517f0cdbd9b6020992c
```

**公开域可读**（`curl` 实测，不带任何凭据）：

```
GET https://file.chuxi.bid/cardesk/latest.json          → 200，590 字节，内容与本地清单一致
GET https://file.chuxi.bid/cardesk/CarDesk-ecarx-cardesk-4149.apk
     → 200，1941656 字节，Content-Type: application/vnd.android.package-archive
     → sha256 a09894ad…c7423a（与本地包逐字节一致）
```

**模拟器走真域名做一次完整升级**：装一个**同源**的 4148 构建
（`-PcardeskVersionCode=4148`，源码与 4149 完全相同，只有版本号不同），
它检查的就是真实 R2：

| # | 项 | 结果 |
|---|---|---|
| — | 清单地址 | ✅ `url=https://file.chuxi.bid/cardesk/latest.json` |
| — | 认出新版本 | ✅ `检查更新：有新版本 current=1.0.1052-system latest=1.0.1053 apk=CarDesk-ecarx-cardesk-4149.apk` |
| — | 下载地址是公开域 | ✅ `https://file.chuxi.bid/cardesk/CarDesk-ecarx-cardesk-4149.apk` |
| — | 下载 + 长度 + SHA-256 校验 | ✅ `更新下载完成 file=cardesk-update-1.0.1053.apk bytes=1941656 elapsedMs=8369`（公网 8.4 秒） |
| — | 静默安装生效 | ✅ 设备上 `versionCode=4149` / `1.0.1053-system` |
| — | **装上去的包字节一致** | ✅ 设备 `base.apk` 的 sha256 = `a09894ad…c7423a`，与线上、与本地三方一致 |
| — | 已是最新分支 | ✅ `检查更新：已是最新 current=1.0.1053-system latest=1.0.1053` |
| — | 装完的新包确实换了源 | ✅ 设置页「更新源」显示 `Cloudflare R2 · file.chuxi.bid`（`r83-r2-installed-4149.png`） |
| — | 不闪退 | ✅ `Fatal signal` / `FATAL EXCEPTION` 计数 **0** |

证据：`emu-verify/r83-r2-*.png`、`emu-verify/update-r2-log.txt`。

---

## 7. 发布

- 产物：`dist/CarDesk-ecarx-cardesk-4149.apk`，1,941,656 字节，
  SHA-256 `a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a`
- 版本：`versionCode 4149` / `versionName 1.0.1053-system`
- 清单：`dist/cardesk-latest.json`（出包时自动生成）
- 干净全量构建（`clean assembleSystem testDebugUnitTest`）+ 1172 单测全绿

### 7.1 R2（**更新检查真正依赖的那一份**）

| 对象 | key | 大小 | 状态 |
|---|---|---|---|
| 更新清单 | `cardesk/latest.json` | 590 B | ✅ 已上传，回读一致 |
| 安装包 | `cardesk/CarDesk-ecarx-cardesk-4149.apk` | 1,941,656 B | ✅ 已上传，回读一致 |

访问地址：`https://file.chuxi.bid/cardesk/latest.json`（公开只读，无需凭据）。

### 7.2 GitHub Release（源码与 tag 的分发渠道）

GitHub Release 继续发，但**不再承担 App 的更新检查** ——
App 从这一版起只读 R2。

| 项 | 值 |
|---|---|
| 仓库 | `Bigcaibird/cardesk`（Public） |
| Tag | `v1.0.1053`（= `versionCode 4149 - 3096`，沿用已推送的 Existing tag） |
| Release 名称 | `CarDesk 4149 / 1.0.1053-system` |
| 资产 | `CarDesk-ecarx-cardesk-4149.apk`（1,941,656 字节） |
| SHA-256 | `a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a` |
| Release 标签 | `Latest` |
| 发布提交 | `5b3b2cb` |
| 发布时间 | `2026-10-30T08:58:39Z` |

**API 回读核验**（`releases/tags/v1.0.1053` → 200）：

```
tag_name     : v1.0.1053
name         : CarDesk 4149 / 1.0.1053-system
draft        : False
prerelease   : False
published_at : 2026-10-30T08:58:39Z
html_url     : https://github.com/Bigcaibird/cardesk/releases/tag/v1.0.1053
assets_count : 1
  asset      : CarDesk-ecarx-cardesk-4149.apk 1941656 bytes
releases/latest = v1.0.1053
```

**GitHub 自己算出来的附件摘要与本地包一致** —— 发布页 Assets 区直接显示
`sha256:a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a`，
和 `dist/CarDesk-ecarx-cardesk-4149.apk` 本地算的、以及 R2 上那个包**三处完全相同**
（同一个包，三处一致）。

Release 正文只写改动点，不写实现细节：

```
**改动点**

- 在线更新源：GitHub Releases → Cloudflare R2
- 下载后新增文件长度与 SHA-256 校验，对不上即判更新失败
- 更新清单格式改为本项目自定（`apk.url` 写 R2 对象 key）
```

---

## 8. 这一轮新踩的 GitHub 发布坑

1. **`upload_files` 报 `browser_action_failed`，但文件其实已经挂上了** ——
   和上一轮同一个坑，本轮又踩了一次。报错后页面已经跳到
   `releases/edit/untagged-f94ee1ba8cfcc4e02946`（草稿自动落成），
   页面出现 `(1.85 MB)`，资产按钮的可访问名是
   `1 file selected: CarDesk-ecarx-cardesk-4149.apk`，
   字段名是 `release[release_assets_attributes][][name]`
   ⇒ **已经是 release asset，不是待上传队列**。别急着重传，先查附件挂上没有。
2. **`screenshot` 在这个 Electron 面板里会失败** —— `fullPage` 报
   `BROWSER_OPERATION_TIMEOUT`（页面 1677px 高），`viewport` 直接 `browser_action_failed`。
   目视核对只能改用结构化读取：
   `query(kind="semantic", text="CarDesk 4149 / 1.0.1053-system")`
   在语义树里唯一命中标题（`matchCount=1`，命中项是 `input#release_name` 的 `inlinetextbox` 子节点）。
   **别用 `query(kind="dom")` 看 `value=` 属性判断标题在不在** ——
   `fill` 只改 DOM property，不写 `value` 属性，看属性会误判成「标题丢了」。
3. **`fill` 返回的 `textLength` 可以拿来核对长度** —— 本轮返回
   `textLength: 30, textChanged: false`；30 正好是
   `CarDesk 4149 / 1.0.1053-system` 的字符数，说明「上次的值还在」且没有多填。
4. **`scroll` 报的 `atStart` 不可信** —— 页面明明还差一屏才到顶，`scroll` 却返回
   `actualDeltaY: -461, atStart: true`，而 `inspect` 里的 `scrollPosition` 是 `y=477.5`。
   两个数对不上时以 `inspect` 的 `rect.y` 为准（`rect.y < 0` 就是还在视口外）。
5. **ref 只在同一次快照里有效** —— 草稿页 `inspect` 的返回元素数会跳（130 / 176 / 181），
   拿过期的 ref 会报 `stale_element_ref`，重新 `inspect` 即可。
6. 发布按钮用 `click` + `selector: button.btn-primary[type="submit"]` 可靠
   （「Save draft」也是 `type=submit`，但 class 是 `btn js-save-draft`，不会误命中）。
   点击后必然跳页（`UNEXPECTED_NAVIGATION`），**这在发布场景下是预期结果**，别当成失败重试。
7. 本轮草稿页是 `releases/edit/untagged-…`（附件一挂上就自动落成草稿），
   在这个页面上点一次 `Publish release` 就直接发布成功，不需要点第二次。

---

## 9. 未能覆盖 / 待办

- **真车实测**：车机能不能访问 `file.chuxi.bid`（域名解析、TLS 证书链、国内网络），
  模拟器验证不了，只能上真车。模拟器测得公网下载 1.9 MB 用时 **8.4 秒**（未计车机网络差异）。
- **旧 GitHub Release 链路已下线**：App 不再读 GitHub。
  仓库的 GitHub Release 继续作为源码 / tag 的分发渠道，但**不再承担更新检查**。
- **R2 凭据轮换**：上传用的 S3 密钥是长期凭据，建议只在发布机上用；换密钥不影响已发布内容。
