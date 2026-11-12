# 出包记录 4250（1.0.1154-system）

> 指令：「出包但暂不推送」
> 产出：源码提交 `6990b64`（第154轮）+ `c777cab`（第155轮）+ 本出包提交
> **本次只出包，不推远端、不打 tag、不发 GitHub Release**

---

## 一、提交了什么

| 提交 | 内容 |
|---|---|
| `6990b64` | 第154轮：设置页无效项整改（删 9 项、标 3 项「暂不可用」、修好槽位重置） |
| `c777cab` | 第155轮：把「暂不可用」那几项真正实现掉（删 1 项、实现 2 项、无效项清零） |
| 本提交 | 出包产物：`version.properties` + `dist/README.md` + `dist/cardesk-latest.json` + 本目录 |

提交约定沿用仓库既有风格：源码与证据一次提交，出包产物（版本号 + R2 清单 + 交付说明）一次提交。

## 二、出包

走 `tools/release.sh`（不手搓 gradle）：自动升版本号 → 干净目录全量构建 → 跑全量单测 →
校验包名 / versionCode / versionName / **平台证书指纹** → 算 SHA-256 → 写 `dist/` + R2 清单 + `dist/README.md`。

| 项 | 值 |
|---|---|
| 产物 | `dist/CarDesk-platform-ecarx-cardesk-4250.apk` |
| 包名 | `com.ecarx.cardesk` |
| versionCode / versionName | 4250 / 1.0.1154-system |
| 大小 | 2 373 425 字节 |
| SHA-256 | `ffdb522e7ac068de327bcb3e6558e76c8f8196c10574d7afcccfb9ff54d625ac` |
| 签名 | AOSP platform，指纹 `c8a2e9bc…92ab8`（校验通过） |
| 出包时全量单测 | 204 个测试类 / **2122 条用例，失败 0 错误 0 跳过 0**（`testDebugUnitTest`） |
| 开发包 | `dist-dev/CarDesk-debug-1.0.1154-com.cardesk.launcher.apk`（debug 签名，2 372 689 字节） |

版本号自动 +1：4249 → **4250**（4249 已经装到设备上，升版本号才能触发「发现新版本」）。

### 独立复核（不只信脚本自己的输出）

```
SHA-256  : ffdb522e7ac068de327bcb3e6558e76c8f8196c10574d7afcccfb9ff54d625ac  ✔ 与脚本一致
证书指纹 : c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8  ✔ 与期望一致
badging  : package=com.ecarx.cardesk versionCode=4250 versionName=1.0.1154-system
```

> 证书指纹必须对：签名不对装到车机上会拿不到车控权限，
> 现象是氛围灯不亮，很容易误判成「没接氛围灯」。

### 单测覆盖说明

`release.sh` 跑的是 `clean assembleSystem testDebugUnitTest` —— 只跑 debug 变体
（两个变体编译的是同一份源码）。本轮另外单独在仓库里跑过 **system + debug 两个变体**，
各 204 类 / 2122 条，失败 0 错误 0 跳过 0。

## 三、装机冒烟

| 设备 | 包 | 版本 | 结果 |
|---|---|---|---|
| emulator-5560 | `com.ecarx.cardesk`（system 签名） | 4250 / 1.0.1154-system | 启动正常，crash buffer 空，包内无 error/fatal |
| emulator-5556 | `com.cardesk.launcher`（debug） | 4250 / 1.0.1154 | 安装成功，crash buffer 空 |

截图：

- `00-smoke-4250-scene.png` —— 5560 首屏（场景页），胎压卡正常；
- `00b-smoke-4250-tirecard.png` —— 胎压卡特写，**与 4249 构建逐像素完全一致**
  （不同像素 0 / 56575）—— 证明「默认值 20 → 28」这个改动对老用户零影响。

日志里那些 `ClassNotFoundException: com.ecarx.xui.adaptapi...` 是**模拟器非车机环境下的正常降级**：
车机通道探测失败后 App 走标准 Android 路径，不影响功能。

设备 prefs 已整份还原（4372 字节、属主 `1000:1000`、键集合与备份一致）。

## 四、没做的事

- **没有推 git remote**（本次指令明确「暂不推送」）：
  远端仍停在 `ac768cd`（出包 4249），本地领先 3 个提交（`6990b64` / `c777cab` / 本提交）。
- **没有打 tag**：没有 `v1.0.1154`。
- **没有发 GitHub Release**：远端最新 release 仍是 v1.0.1153（4249）。
- **没有上传 R2**：`tools/publish_r2.py` 需要 `R2_AK` / `R2_SK` 环境变量，是独立的手工发布步骤，
  历轮「出包」也不包含它。`dist/cardesk-latest.json` 与 APK 已就位，上传即生效。

## 五、这版带了什么

见 `emu-verify/y155/SETTINGS-IMPLEMENT.md`（第155轮实现报告）与
`emu-verify/y154/SETTINGS-AUDIT.md`（第154轮审计报告）。

一句话：**设置页 154 个可点项里的 12 个「没有功能」的，全部处理完** ——
10 个删除，2 个真实现（桌面开机启动 / 胎压数字字号），
外加「恢复默认 15 槽位」当场生效、360 总开关拨开后子项立刻显示。
