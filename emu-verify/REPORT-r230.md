# 第230轮 · 静态壁纸导入「没有图片选择器」修复

日期：2026-11-21
构建：`CarDesk 4307 / 1.0.1211-system`（平台签名）
验证环境：`cardesk28` AVD（API 28 / arm64 / 平台签名，`emulator-5560`）

---

## 一、问题

用户在车机上点「设置 → 壁纸 → 导入图片」，只弹一句
**「没有可用的图片选择器」**，换不了静态壁纸。

---

## 二、根因

`MainActivity.startWallpaperImport()` 只有一个动作：发
`Intent.ACTION_OPEN_DOCUMENT` + `image/*`，失败就 toast 这句话。

车机（领克 AAOS 820A）没有相册，也没有 DocumentsUI 接管这个 action，
`startActivityForResult` 抛 `ActivityNotFoundException` ⇒ 必然落到那句提示，
**没有任何兜底**。

`onActivityResult(REQ_WALLPAPER)` 也只取 `data?.data`：车机文件管理器若用
`clipData` / `EXTRA_STREAM` 回传，同样拿不到文件。

---

## 三、改法

### 3.1 三层兜底

| 层 | 做法 |
|---|---|
| 1 | **系统选择器降级链**：`OPEN_DOCUMENT`+图片 → `OPEN_DOCUMENT`+不限类型 → `GET_CONTENT`+图片，逐条 `resolveActivity` 预检 |
| 2 | 都不行 ⇒ **应用内图片列表**（缩略图 / 名称 / 大小 / 日期 / 路径；可重新扫描、可再试系统选择器） |
| 3 | 取址 **三级兜底**：`data` → `clipData` → `EXTRA_STREAM` |

### 3.2 文件

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/cardesk/launcher/core/ImagePicker.kt` | 策略链、intent 构造、取址三级兜底、`looksLikeImage()` |
| `app/src/main/java/com/cardesk/launcher/core/ImageLibrary.kt` | 扫描 `Pictures` / `DCIM` / `Download` / `Download/CarDesk`，纯逻辑可单测 |
| `app/src/main/java/com/cardesk/launcher/ui/ImagePickerOverlay.kt` | 应用内选择面板（后台扫描 + 缩略图采样） |
| `app/src/main/java/com/cardesk/launcher/MainActivity.kt` | `startWallpaperImport()` 走降级链；`onActivityResult` 用 `extractUri` |
| `app/src/main/java/com/cardesk/launcher/ui/LauncherView.kt` | `openImagePicker()` / `importWallpaperFromLibrary()`，与原系统路径复用同一导入流程 |

### 3.3 途中修掉的一个坑

原始降级链有一档「`GET_CONTENT` + 不限类型」。模拟器实测：相册与 DocumentsUI
都停用后，这一档被 **`com.android.music`** 接管 —— 用户点「导入壁纸」跳进音乐播放器。
⇒ 该档删除，只保留 3 档；`ImagePickerTest` 同步断言。

---

## 四、实测

| 场景 | 结果 |
|---|---|
| 系统选择器可用 | `handler=com.android.documentsui`，正常列出图片 |
| 停用 DocumentsUI | 降级到 `GET_CONTENT (image/*)`，相册接管，正常选图 |
| 相册也停用 | `改用应用内列表 tried=3`；面板列出 `Download/CarDesk/wp-test.jpg` |
| 应用内选中 | `自定义壁纸已导入 srcW=1600 srcH=900`，桌面换成该图 |
| 全量单测 | 2659 / 0 failures / 0 errors / 0 skipped |

截图：`emu-verify/r230-01-image-picker-fallback.png`、
`r230-02-image-picker-picked.png`、`r230-03-custom-wallpaper-applied.png`。

---

## 五、已知边界

1. 图片格式只认 `jpg / jpeg / png / webp / bmp / heic / heif`（**不含 gif**）。
2. 应用内列表上限 200 张、最多两层目录。
3. 视频壁纸开启时自定义静态壁纸不显示（优先级在视频壁纸之下）。
4. 未做实车复测（车机离线）。

---

## 六、交付

| 项 | 值 |
|---|---|
| APK | `dist/CarDesk-platform-ecarx-cardesk-4307.apk` |
| versionCode / Name | `4307` / `1.0.1211-system` |
| SHA-256 | `776dd3814194ff11091287104d8b59706f2e80f86f25e449d347ab42e8e3eecd` |
| 大小 | 3684939 字节 |
| 签名 | AOSP platform（`c8a2e9bc…92ab8`） |
| R2 | `cardesk/CarDesk-platform-ecarx-cardesk-4307.apk` + `cardesk/latest.json` |
