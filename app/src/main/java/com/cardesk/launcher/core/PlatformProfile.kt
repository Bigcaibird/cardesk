package com.cardesk.launcher.core

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.cardesk.launcher.vehicle.VehicleGateway

/**
 * 平台/机型识别：用「密度 + 屏幕长边」区分 E03 与 820A 两套车机平台
 * （：dpi<=160 && max(w,h)>=1800 → 820A），
 * 并据此切换 360 视角顺序、布局画布尺寸等。这里把同一套判断集中到一处。
 */
enum class CarPlatform(val label: String) {
    E03("E03（吉利/领克主流车机）"),
    E820A("820A（高通 820A 平台）"),
    UNKNOWN("未识别平台"),
}

data class PlatformProfile(
    val platform: CarPlatform,
    val densityDpi: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val sdkInt: Int,
    val abi: String,
    val platformSigned: Boolean,
    val systemUid: Boolean,
    val defaultLauncher: Boolean,
    val notificationAccess: Boolean,
    val overridden: Boolean = false,
) {
    /** 设计稿 1920×1080 的缩放比（短边为主的等比缩放）。 */
    val designScale: Float get() = Design.scaleFor(screenWidth, screenHeight)

    /** 在 1280 画布上会把设置面板缩到 1180×880 / 左栏 300。 */
    val compactUi: Boolean get() = screenWidth < 1600 || designScale < 1f

    fun summary(): String = buildString {
        appendLine("平台：${platform.label}${if (overridden) "（手动指定）" else "（自动识别）"}")
        appendLine("屏幕：${screenWidth}×${screenHeight} @ ${densityDpi}dpi（缩放 ${"%.3f".format(designScale)}）")
        appendLine("系统：Android ${sdkInt} / $abi")
        appendLine("签名：${if (platformSigned) "平台证书" else "非平台证书"}")
        appendLine("uid：${if (systemUid) "1000（system）" else "普通应用"}")
        appendLine("默认桌面：${if (defaultLauncher) "是" else "否"}")
        append("通知使用权：${if (notificationAccess) "已授权" else "未授权（媒体会话不可见）"}")
    }
}

object Platforms {

    /**
     * 相同的平台判定规则；[override] 允许设置页强制指定平台（0 自动 / 1 E03 / 2 820A），
     * 用于在单一台架/真机上验证另一套平台的布局与 360 视角顺序。
     */
    fun detect(context: Context, override: Int = 0): PlatformProfile {
        val metrics = screenMetrics(context)
        val dpi = metrics.densityDpi
        val longest = maxOf(metrics.widthPixels, metrics.heightPixels)
        val detected = when {
            dpi <= 160 && longest >= 1800 -> CarPlatform.E820A
            dpi in 161..260 -> CarPlatform.E03
            else -> if (longest >= 1800) CarPlatform.E820A else CarPlatform.E03
        }
        val platform = when (override) {
            1 -> CarPlatform.E03
            2 -> CarPlatform.E820A
            else -> detected
        }
        val overridden = override == 1 || override == 2
        val notificationAccess = runCatching {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            enabled.contains(context.packageName)
        }.getOrDefault(false)
        return PlatformProfile(
            platform = platform,
            densityDpi = dpi,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            platformSigned = SystemOps.isPlatformSigned(context),
            systemUid = SystemOps.isSystemUid(context),
            defaultLauncher = isDefaultLauncher(context),
            notificationAccess = notificationAccess,
            overridden = overridden,
        )
    }

    private fun screenMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }.onFailure {
            metrics.setTo(context.resources.displayMetrics)
        }
        if (metrics.widthPixels == 0) metrics.setTo(context.resources.displayMetrics)
        return metrics
    }

    fun isDefaultLauncher(context: Context): Boolean = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        resolved?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    /** 车控通道优先级：平台相关的首选/兜底顺序。 */
    fun gatewayPriority(platform: CarPlatform): List<String> = when (platform) {
        CarPlatform.E820A -> listOf(VehicleGateway.CHANNEL_ADAPTAPI, VehicleGateway.CHANNEL_CARLIB, VehicleGateway.CHANNEL_ECARX, VehicleGateway.CHANNEL_MOCK)
        CarPlatform.E03 -> listOf(VehicleGateway.CHANNEL_ADAPTAPI, VehicleGateway.CHANNEL_ECARX, VehicleGateway.CHANNEL_CARLIB, VehicleGateway.CHANNEL_MOCK)
        CarPlatform.UNKNOWN -> listOf(VehicleGateway.CHANNEL_ADAPTAPI, VehicleGateway.CHANNEL_ECARX, VehicleGateway.CHANNEL_CARLIB, VehicleGateway.CHANNEL_MOCK)
    }
}
