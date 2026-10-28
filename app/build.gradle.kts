import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 包名/版本可用 -P 覆盖，便于产出与参考车机桌面同包名的「替换版」：
//   gradle :app:assembleSystem -PcardeskPackage=com.ecarx.qq.tool -PcardeskVersionCode=4354
// 注意：applicationId 只影响安装身份，Kotlin/Java 包名（namespace）保持 com.cardesk.launcher 不变。
val pkgOverride = (project.findProperty("cardeskPackage") as String?)?.trim().orEmpty()

// 第五十七轮：版本号的**唯一来源**是仓库根目录的 `version.properties`
// （由 `tools/release.sh` 每次出包自动 +1，也可以手动 `gradle bumpVersion`）。
// `-PcardeskVersionCode/-PcardeskVersionName` 仍可临时覆盖（调试用，不要提交）。
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultVersionCode = versionProps.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 4123
val defaultVersionName = versionProps.getProperty("versionName")?.trim().orEmpty().ifEmpty { "1.0.1027" }
val versionCodeOverride = (project.findProperty("cardeskVersionCode") as String?)?.toIntOrNull() ?: defaultVersionCode
val versionNameOverride = (project.findProperty("cardeskVersionName") as String?)?.trim().orEmpty().ifEmpty { defaultVersionName }

android {
    namespace = "com.cardesk.launcher"
    // compileSdk / targetSdk 与参考 App 完全一致
    // （`aapt dump badging` 参考 APK → compileSdkVersion=29、targetSdkVersion=29）。
    //
    // 这不只是「口径对齐」，还直接决定**车机 SDK 能不能反射得到**：
    // Android 的隐藏 API（hidden API）限制按 targetSdk 分级 ——
    //   targetSdk 29 → 只能用「API 29 及以下支持」的成员
    //   targetSdk 34 → 只能用「API 27 及以下支持」的成员
    // `com.ecarx.sdk.weather.WeatherAPI`、`android.os.ServiceManager.getServiceNames`
    // 这些**车机/系统私有类**的成员属于新一档，targetSdk 34 会被拦掉
    // （`Method.invoke` 抛 NoSuchMethodException），
    // 而参考版 targetSdk 29 拿得到 —— 参考的天气主通路（车机天气 SDK）就是这么走的。
    compileSdk = 29

    defaultConfig {
        applicationId = pkgOverride.ifEmpty { "com.cardesk.launcher" }
        minSdk = 26
        // targetSdk 与参考 App 一致（参考 APK `aapt dump badging` → targetSdkVersion=29）。
        //
        // 这不只是「口径对齐」，还直接决定**车机 SDK 能不能反射得到**：
        // Android 的隐藏 API（hidden API）限制按 targetSdk 分级 ——
        //   targetSdk 29 → 只能用「API 29 及以下支持」的成员
        //   targetSdk 34 → 只能用「API 27 及以下支持」的成员
        // `com.ecarx.sdk.weather.WeatherAPI`、`android.os.ServiceManager.getServiceNames`
        // 这些**车机/系统私有类**的成员属于新一档，targetSdk 34 会被拦掉
        // （`Method.invoke` 抛 NoSuchMethodException），
        // 而参考版 targetSdk 29 拿得到 —— 参考的天气主通路（车机天气 SDK）就是这么走的。
        targetSdk = 29
        versionCode = versionCodeOverride
        versionName = versionNameOverride.ifEmpty { "0.1.0" }
    }

    // 平台签名（AOSP platform 测试密钥，与参考 App 的 PLATFORM.RSA 同一指纹）
    //
    // **密钥文件不入库**（见 `.gitignore` 的 `platform-key/`）：
    // 别人 clone 之后这个文件不存在，所以这里做**条件判断** ——
    // 有密钥就用平台签名，没有就退回 debug 签名（能构建，但装到车机上会因签名不符失败）。
    // 这样「拉下来就能编译」，而不是在配置阶段就炸。
    signingConfigs {
        create("platform") {
            val keyFile = rootProject.file("platform-key/cardesk-platform.jks")
            if (keyFile.exists()) {
                storeFile = keyFile
                storePassword = "cardesk"
                keyAlias = "cardesk"
                keyPassword = "cardesk"
            }
        }
    }

    // buildTypes.system：sharedUserId=android.uid.system + 平台签名，
    // manifest 覆盖在 src/system/AndroidManifest.xml。只有落在 /system/priv-app
    // 且系统信任该证书（AOSP platform 证书）时才会被授予 uid 1000 与签名级权限。
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("system") {
            initWith(getByName("debug"))
            if (rootProject.file("platform-key/cardesk-platform.jks").exists()) {
                signingConfig = signingConfigs.getByName("platform")
            }
            applicationIdSuffix = ""
            versionNameSuffix = "-system"
        }
    }

    compileOptions {
        // compileSdk 29 要求 Java 8 源码/字节码（AGP 会拦 Java 9+ 的 source/target）。
        // 车机是 Android 9（API 28），Java 8 字节码原生支持，没有兼容问题。
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// system 构建默认产出与参考车机桌面同包名的「替换版」：
//   assembleSystem                 → com.ecarx.qq.tool, versionCode 4354, 平台签名
//   assembleDebug                  → com.cardesk.launcher, versionCode 1, debug 签名
//
// 「自有包名 + 亿咖通前缀」的 system 版（与参考 App **并存**、不覆盖它）：
//   第四十二轮（4123）起包名从 com.ecarx.qq.cardesk 改成 **com.ecarx.cardesk**（去掉中间的 qq）。
//   assembleSystem -PcardeskPackage=com.ecarx.cardesk \
//                  -PcardeskVersionCode=4123 -PcardeskVersionName=1.0.1027-lynkco-tool-cardesk
//   → com.ecarx.cardesk，versionName 自动追加 "-system"，同样是平台签名 + uid 1000。
// 指定 -PcardeskPackage 后不再走「替换版」逻辑（版本号不会被抬到 4354）。
//
// versionCode 的约定：versionName = "1.0.0" + (versionCode - 3096)
//   参考包 4353 ↔ 1.0.01257；4385 ↔ 1.0.01289；… 所以 1.0.1027 → 4123。
androidComponents {
    onVariants(selector().withBuildType("system")) { variant ->
        // 只有「默认（未用 -PcardeskPackage 指定包名）」的 system 构建才是替换版，
        // 才改成参考包名并把版本号抬到 4354 以上。
        val replacement = pkgOverride.isEmpty()
        if (replacement) {
            variant.applicationId.set("com.ecarx.qq.tool")
        }
        variant.outputs.forEach { output ->
            output.versionCode.set(
                if (replacement) maxOf(versionCodeOverride, 4354) else versionCodeOverride,
            )
            if (replacement && versionNameOverride.isEmpty()) {
                output.versionName.set("1.0.01258-lynkco-tool-cardesk")
            }
        }
    }
}

dependencies {
    // 刻意不引入任何运行时依赖：纯 framework API 实现，减小包体、也方便直接丢进 AOSP 源码树
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
