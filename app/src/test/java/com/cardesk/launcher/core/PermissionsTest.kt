package com.cardesk.launcher.core

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位权限处理的纯逻辑单测。
 *
 * 真机（领克 dcy11 / Android 9）的现象：「权限显示已授予，但一个位置都拿不到」。
 * 根因是 Android 位置权限有**两层** —— permission grant（`checkSelfPermission`）
 * 和 AppOps mode（`LocationManagerService` 真正拦人的那一层），
 * 而 `adb shell pm grant` 只写第一层。
 *
 * 这里只测「模式名怎么解释」这类不依赖 Android 设备的部分；
 * 真正的授权流程要设备侧验证。
 */
class PermissionsTest {

    @Test
    fun modeName_knownModes() {
        assertEquals("allow", Permissions.modeName(AppOpsManager.MODE_ALLOWED))
        assertEquals("ignore", Permissions.modeName(AppOpsManager.MODE_IGNORED))
        assertEquals("errored", Permissions.modeName(AppOpsManager.MODE_ERRORED))
        assertEquals("foreground", Permissions.modeName(AppOpsManager.MODE_FOREGROUND))
        assertEquals("default", Permissions.modeName(AppOpsManager.MODE_DEFAULT))
    }

    @Test
    fun modeName_unknownModeFallsBack() {
        assertEquals("mode999", Permissions.modeName(999))
    }

    @Test
    fun opName_stripsAndroidPrefix() {
        assertEquals("fine_location", Permissions.opName(Permissions.OP_FINE))
        assertEquals("coarse_location", Permissions.opName(Permissions.OP_COARSE))
    }

    @Test
    fun locationOps_usePublicStringApi() {
        // AppOpsManager 的 int 常量（OP_FINE_LOCATION）是 @hide 的，
        // 公开 API 只有字符串 op 名 —— 这里锁住，避免哪天改回 int 常量编译不过。
        assertTrue(Permissions.OP_FINE.startsWith("android:"))
        assertTrue(Permissions.OP_COARSE.startsWith("android:"))
    }
}
