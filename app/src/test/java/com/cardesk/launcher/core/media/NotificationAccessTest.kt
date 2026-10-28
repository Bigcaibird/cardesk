package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotificationAccess.matchedComponents] 单测（纯逻辑，不依赖 Android）。
 *
 * 这段逻辑直接决定「要不要把用户弹到系统设置页去授权」，
 * 判错的代价是双向的：
 * - 漏判（明明授权了却还弹）→ 反复打扰用户；
 * - 误判（没授权却当成授权了）→ 音乐区永远显示占位，用户不知道原因。
 *
 * 所以这里把各种白名单写法都钉死。
 */
class NotificationAccessTest {

    private val main = "com.ecarx.qq.tool/com.cardesk.launcher.core.media.MediaNotificationListener"
    private val alias = "com.ecarx.qq.tool/com.ecarx.qq.tool.service.MusicBridgeService"
    private val other = "com.android.launcher3/com.android.launcher3.notification.NotificationListener"

    @Test
    fun `空白名单一律不命中`() {
        assertTrue(NotificationAccess.matchedComponents(null, listOf(main)).isEmpty())
        assertTrue(NotificationAccess.matchedComponents("", listOf(main)).isEmpty())
        assertTrue(NotificationAccess.matchedComponents("   ", listOf(main)).isEmpty())
    }

    @Test
    fun `候选组件为空时不命中`() {
        assertTrue(NotificationAccess.matchedComponents("$main:$alias", emptyList()).isEmpty())
    }

    @Test
    fun `命中主服务`() {
        assertEquals(
            listOf(main),
            NotificationAccess.matchedComponents(main, listOf(main)),
        )
    }

    @Test
    fun `命中同名别名服务`() {
        // 车机出厂预置的白名单指向的就是这个组件名
        assertEquals(
            listOf(alias),
            NotificationAccess.matchedComponents(alias, listOf(main, alias)),
        )
    }

    @Test
    fun `多组件白名单里能挑出我们的那几个`() {
        val enabled = "$other:$main:$alias"
        assertEquals(
            listOf(main, alias),
            NotificationAccess.matchedComponents(enabled, listOf(main, alias)),
        )
    }

    @Test
    fun `只别人的白名单不命中`() {
        assertTrue(NotificationAccess.matchedComponents(other, listOf(main, alias)).isEmpty())
    }

    @Test
    fun `条目包含不算命中（比对是精确相等）`() {
        // 不能把 "MusicBridgeServiceExtra" 当成 "MusicBridgeService"
        val enabled = "com.ecarx.qq.tool/com.ecarx.qq.tool.service.MusicBridgeServiceExtra"
        assertTrue(NotificationAccess.matchedComponents(enabled, listOf(alias)).isEmpty())
    }

    @Test
    fun `条目前后有空白也能命中`() {
        val enabled = " $other : $main "
        assertEquals(
            listOf(main),
            NotificationAccess.matchedComponents(enabled, listOf(main)),
        )
    }

    @Test
    fun `连续冒号产生的空条目被忽略`() {
        val enabled = "::$other::$main::"
        assertEquals(
            listOf(main),
            NotificationAccess.matchedComponents(enabled, listOf(main)),
        )
    }

    @Test
    fun `分隔符写成其它字符也能命中`() {
        // 个别 ROM 写出来的分隔符不统一，切分放宽但比对仍是精确相等
        assertEquals(
            listOf(main),
            NotificationAccess.matchedComponents("$other;$main", listOf(main)),
        )
        assertEquals(
            listOf(main),
            NotificationAccess.matchedComponents("$other $main", listOf(main)),
        )
    }

    @Test
    fun `白名单里有目标组件才算已授予`() {
        // MainActivity.isNotificationListenerEnabled():1570-1574 只看
        // 「com.ecarx.qq.tool.service.MusicBridgeService」这一个组件
        assertTrue(NotificationAccess.isReferenceGranted(alias, alias))
        assertTrue(NotificationAccess.isReferenceGranted("$other:$alias", alias))
        // 白名单里只有别的组件 →口径算「未授予」→ 会跳系统设置页
        assertFalse(NotificationAccess.isReferenceGranted(other, alias))
        assertFalse(NotificationAccess.isReferenceGranted("", alias))
        assertFalse(NotificationAccess.isReferenceGranted(null, alias))
    }
}
