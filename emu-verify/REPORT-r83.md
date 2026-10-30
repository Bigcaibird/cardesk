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

---

## 7. 发布

- 产物：`dist/CarDesk-ecarx-cardesk-4149.apk`，1,941,656 字节，
  SHA-256 `a09894ad776c37604a5188edfe3242df016f8b009d4a8c447380126d32c7423a`
- 版本：`versionCode 4149` / `versionName 1.0.1053-system`
- 清单：`dist/cardesk-latest.json`（出包时自动生成）
- 干净全量构建（`clean assembleSystem testDebugUnitTest`）+ 1172 单测全绿

> GitHub 发布与 R2 上传的实测结果见本文件后续小节。

---

## 8. 未能覆盖 / 待办

- **真 R2 终验**依赖 Secret Access Key；拿到后要跑一遍「模拟器里拉
  `file.chuxi.bid/cardesk/latest.json` → 认版本 → 下载 → 校验 → 安装」。
- **真车实测**：车机能不能访问 `file.chuxi.bid`（域名解析、TLS 证书链、国内网络），
  模拟器验证不了，只能上真车。
- **旧 GitHub Release 链路已下线**：App 不再读 GitHub。
  仓库的 GitHub Release 继续作为源码 / tag 的分发渠道，但**不再承担更新检查**。
