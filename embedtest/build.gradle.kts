plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 「可被嵌入」的测试承载应用（第140轮）。
 *
 * ## 它是为了什么存在的
 *
 * 车机（领克 dcy11）跑 Android 9 / API 28，而 **AOSP 9 有条硬性限制**：
 * 被放进 `VirtualDisplay` 的 Activity **必须自己声明 `resizeableActivity="true"`**
 * （activity 级；AOSP 9 **不继承** application 级的值）。
 * 源码见 `services/core/java/com/android/server/am/ActivityStackSupervisor.java`：
 *
 * ```java
 * boolean canPlaceEntityOnDisplay(int displayId, boolean resizeable, ...) {
 *     if (!resizeable && !displayConfigMatchesGlobal(displayId)) {
 *         // Can't apply wrong configuration to non-resizeable activities.
 *         return false;                      // ← 静默拒绝 → 回落到默认显示器（全屏抢屏）
 *     }
 * ```
 *
 * 高德车机版只在 **application** 级声明了 `resizeableActivity=true`，
 * 它的 `MainMapActivity` / `UsbFillActivity` **自己没声明** →
 * 在 API 28 上被判「不可调整大小」→ **放不进虚拟屏**（Android 10 改了继承规则，所以 API 29 上能放）。
 *
 * 第三方 App 的 manifest 我们改不了 → **API 28 模拟器上「用高德测画中画」这条路是走不通的**。
 *
 * ## 所以有了这个模块
 *
 * 一个最小 App：**activity 级**声明 `resizeableActivity="true"` + `allowEmbedded="true"`，
 * 画一屏有编号的彩色画面（截图一眼就能认出是它）。
 *
 * 把它装到 API 28 模拟器上、设成画中画承载目标，就能**真正**验证我们的承载链路：
 * 承载面落点、task 归属、换肤重建时的「搬家」是否保住 task 与画面。
 *
 * ## 它不进正式包
 *
 * - 它是**独立 APK**（`com.cardesk.embedtest`），与 `com.ecarx.cardesk` 并存；
 * - `tools/release.sh` 只出 `:app:assembleSystem`，这个模块**不出现在 `dist/`**；
 * - 需要时单独构建：`gradle :embedtest:assembleDebug`。
 */
android {
    namespace = "com.cardesk.embedtest"
    compileSdk = 29

    defaultConfig {
        applicationId = "com.cardesk.embedtest"
        minSdk = 26
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"

        // `android:allowEmbedded` 做成可切换（第140轮的 A/B 实验用）：
        //
        // AOSP 9（API 28）往虚拟屏里放**别的应用**时，会检查目标 Activity 有没有
        // 声明 `android:allowEmbedded="true"`（对应 `ActivityInfo.FLAG_ALLOW_EMBEDDED`），
        // 没有就**静默拒绝**、把应用放回主显示器（全屏抢屏）。
        //
        //   gradle :embedtest:assembleDebug                        → 带 allowEmbedded
        //   gradle :embedtest:assembleDebug -PembedtestAllowEmbedded=false → 不带
        //
        // 两者跑出来的行为差异，就是「为什么模拟器上高德进不了画中画」的直接证据。
        manifestPlaceholders["allowEmbedded"] =
            (project.findProperty("embedtestAllowEmbedded") as String?) ?: "true"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // 与 app 模块同名，好让根项目的 `assembleSystem` 聚合任务有对应变体
        // （release.sh 跑的是 `gradle clean assembleSystem`）。
        create("system") {
            initWith(getByName("debug"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
