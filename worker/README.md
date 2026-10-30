# CarDesk 云端应用库 · 上传端（Cloudflare Worker）

车机要装别的 App 时，老路只有两条：**带 U 盘到车上插**，或者**在车上让电脑和车机连同一个局域网**。
两条都要求人在车旁边。

这个 Worker 是第三条路的**上传端**：手机浏览器打开网页 → 选 APK → 上传到 Cloudflare R2；
车机（CarDesk 的「设置 → 网络与升级 → 云端应用」页）再从 R2 拉下来直接装。

```
手机浏览器 ──上传──▶ 本 Worker ──▶ R2（cardesk/apks/xxx.apk）
                                     │
车机 CarDesk ──GET https://file.chuxi.bid/cardesk/apks.json ──▶ 清单（cardesk/apks.json）
              └── 下载 APK ──校验长度+SHA-256──▶ 静默安装 / 系统安装器
```

---

## 一、为什么必须有 Worker，而不是「网页直接写 R2」

R2 的写入必须带 S3 凭据（S3 Access Key）。**凭据一旦写进浏览器里的网页就等于公开** ——
谁打开这个页面就能拿到密钥往你的 bucket 里塞东西，还能覆盖你已有的文件。

所以网页只负责发请求，**真正的写入发生在 Worker 里**：Worker 用 R2 binding 读写 bucket，
凭据由 Cloudflare 托管，**既不进代码、也不进仓库、也不进手机浏览器**。

R2 binding 由 Cloudflare 内部授权，所以**这里根本不需要 S3 Access Key**。
真正要保管的只有一个**上传口令**（`UPLOAD_TOKEN`），用 `wrangler secret put` 存成 secret。

## 二、部署

前置：Cloudflare 账号（就是管着 R2 那个）、Node.js 16+。

```bash
cd worker

# 1) 登录（浏览器授权；或者 export CLOUDFLARE_API_TOKEN=<你的 API Token>）
npx wrangler login

# 2) 设上传口令（交互输入，**不回显**）
#    这个口令就是上传页上要填的那个 —— 换一台手机要重新填一次
npx wrangler secret put UPLOAD_TOKEN

# 3) 部署
npx wrangler deploy
```

部署完会打印一个地址：

```
https://cardesk-apk-store.<你的子域>.workers.dev
```

**用手机浏览器打开这个地址就是上传页。**

### 绑定自己的域名（可选）

在 Cloudflare Dashboard → Workers & Pages → `cardesk-apk-store` → Settings → Domains & Routes
→ Add → Custom domain，例如 `dl.chuxi.bid`。

> **车机端不依赖这个域名** —— 车机只读 R2 的公开自定义域 `file.chuxi.bid`。
> 换域名、换 Worker 地址都不用改车机上的 App。

### 重新部署

改了 `src/index.js` 之后重新 `npx wrangler deploy` 即可。

---

## 三、用法

1. 手机浏览器打开 Worker 地址；
2. 填**上传口令**（= 第 2 步设的那个）；
3. 选 `.apk` 文件，可填应用名（不填就用文件名）；
4. 点「上传」—— 上传完清单会自动重写；
5. 页面下方会列出云端已有的包，可以直接删掉传错的。

传完之后**车机上打开「云端应用」页**就能看到，点「下载安装」即可。

### 限制

| 项 | 值 | 说明 |
|---|---|---|
| 单包大小 | **100MB** | Workers 免费版单次请求体上限；代码里也会显式拒绝并提示 |
| 清单地址 | `cardesk/apks.json` | 车机固定读这个地址 |
| 包存放 | `cardesk/apks/<文件名>` | 同名会覆盖 |

---

## 四、清单格式

**权威定义在 `app/src/main/java/com/cardesk/launcher/core/apkstore/ApkIndex.kt`**（解析端）。
产出端有两个，格式必须一致：

- `worker/src/index.js` —— 手机上传（本文档）；
- `tools/publish_apks.py` —— 电脑命令行（开发和验证用）。

```json
{
  "schema": 1,
  "updatedAt": "2026-10-30T09:00:00Z",
  "items": [
    {
      "name": "酷狗音乐",
      "file": "kugou.apk",
      "key": "cardesk/apks/kugou.apk",
      "size": 12345678,
      "sha256": "64位小写十六进制",
      "addedAt": "2026-10-30T09:00:00Z"
    }
  ]
}
```

- `key` 是 **R2 对象 key**（相对域名根）—— 车机按域名根拼成绝对地址，
  和「在线更新」的 `latest.json` 同一个思路（**改域名不用改清单**）。
- `sha256` 是车机下载后要校验的摘要。**清单里没给就不校验**（不猜）。
- `name` / `file` / `addedAt` 只用于展示。

### 为什么每个包要带 `x-amz-meta-*`

摘要不能重新算（那要把所有包再下一遍），所以**上传时就把摘要写进对象的元数据**，
之后扫前缀时读回来。Worker 用 R2 binding 的 `customMetadata` 写的是同一份元数据
（S3 侧就是 `x-amz-meta-*` 头），所以两边互相都认得。

---

## 五、权限模型

| 操作 | 鉴权 | 说明 |
|---|---|---|
| `GET /` | 无 | 上传页本身（里面不含任何凭据） |
| `GET /api/index` | 无 | 读清单；车机也走这条路（公开自定义域匿名读） |
| `POST /api/upload` | **要口令** | 头 `x-cardesk-token` |
| `POST /api/delete` | **要口令** | 头 `x-cardesk-token` |

**没配 `UPLOAD_TOKEN` secret 时，所有写操作一律拒绝**（fail closed）——
这个 Worker 的地址是公开的，没口令就等于把 bucket 的写权限送人。

---

## 六、命令行上传（不用部署 Worker 也能用）

`tools/publish_apks.py` 用的是同一套格式，走 S3 API（凭据从环境变量读）：

```bash
export R2_AK=<Access Key ID>
export R2_SK=<Secret Access Key>

python3 tools/publish_apks.py --bucket file \
    --apk /path/to/app.apk --name "酷狗音乐"
```

它会上传包、**重写清单**、并回读校验（长度 + SHA-256）。
用途是**开发和验证**：不部署 Worker 也能把一个包放到真 R2 上，车机照样能拉到、能装。

> 密钥只从环境变量读，脚本和仓库里都不写密钥值。
