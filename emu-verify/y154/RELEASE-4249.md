# 出包记录 4249（1.0.1153-system）

> 指令：「出个最新的apk吧，代码提交一下并release」
> 产出：源码提交 `6f5f62e` + 出包提交（见 git log）

---

## 一、提交了什么

| 提交 | 内容 |
|---|---|
| `6f5f62e` | 第145~153轮源码 + 单测 + 验证脚本 + 各轮证据（161 个文件，10338 增 / 450 删） |
| 本提交 | 出包产物：`version.properties` + `dist/README.md` + `dist/cardesk-latest.json` + 本目录 |

提交约定沿用仓库既有风格：源码与证据一次提交，出包产物（版本号 + R2 清单 + 交付说明）一次提交。

## 二、出包

走 `tools/release.sh`（不手搓 gradle）：自动升版本号 → 干净目录全量构建 → 跑全量单测 →
校验包名 / versionCode / **平台证书指纹** → 算 SHA-256 → 写 `dist/` + R2 清单 + `dist/README.md`。

| 项 | 值 |
|---|---|
| 产物 | `dist/CarDesk-platform-ecarx-cardesk-4249.apk` |
| 包名 | `com.ecarx.cardesk` |
| versionCode / versionName | 4249 / 1.0.1153-system |
| 大小 | 2 374 581 字节 |
| SHA-256 | `c644e79f86d26f677a8d755ece78361a2cd24cad2641508a792daf97f72e86c5` |
| 签名 | AOSP platform，指纹 `c8a2e9bc…92ab8`（校验通过） |
| 全量单测 | 201 个测试类 / **2106 条用例，失败 0 错误 0 跳过 0** |
| 开发包 | `dist-dev/CarDesk-debug-1.0.1153-com.cardesk.launcher.apk`（debug 签名） |

版本号自动 +1：4248 → **4249**（4248 已装到车上/设备，升版本号才能触发「发现新版本」）。

## 三、装机冒烟

| 设备 | 包 | 版本 | 结果 |
|---|---|---|---|
| emulator-5560 | `com.ecarx.cardesk`（system 签名） | 4249 / 1.0.1153-system | 启动正常，crash buffer 空，包内无 error/fatal |
| emulator-5556 | `com.cardesk.launcher`（debug） | 4249 / 1.0.1153 | 安装成功 |

截图：`00-smoke-4249-night.png`（5560 夜间模式，音乐卡封面背景正常、歌词与进度条正常）。

日志里那些 `ClassNotFoundException: com.ecarx.xui.adaptapi...` 是**模拟器非车机环境下的正常降级**：
车机通道探测失败后 App 走标准 Android 路径，不影响功能。

## 四、没做的事

- **没有上传 R2**：`tools/publish_r2.py` 需要 `R2_AK` / `R2_SK` 环境变量，是独立的手工发布步骤，
  历轮「出包」也不包含它。`dist/cardesk-latest.json` 与 APK 已就位，上传即生效。
- **没有推 git remote**：仓库没有配置远端（`git log` 只有本地提交历史）。
