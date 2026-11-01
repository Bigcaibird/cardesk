package com.cardesk.launcher.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 最近任务数据层（第九十五轮 C3）。
 *
 * 用假的 [RecentTasks.System] 钉住：去重/排除自己、清空保留锁定、权限不足降级、
 * 打开优先 moveTaskToFront、锁定持久化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentTasksTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    private class FakeSystem(
        var tasks: List<RecentTasks.Raw> = emptyList(),
        var moveResult: Boolean = true,
        var removeResult: Boolean = true,
        var listThrows: Boolean = false,
    ) : RecentTasks.System {
        val removed = ArrayList<Int>()
        var moveCalls = 0

        override fun list(max: Int): List<RecentTasks.Raw> {
            if (listThrows) throw SecurityException("denied")
            return tasks
        }

        override fun moveToFront(taskId: Int): Boolean {
            moveCalls++
            return moveResult
        }

        override fun remove(taskId: Int): Boolean {
            removed.add(taskId)
            return removeResult
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = Prefs(context)
        prefs.recentLocked = emptySet()
    }

    private fun tasks(system: RecentTasks.System) = RecentTasks(context, system)

    @Test
    fun 列表去重并排除自己() {
        val self = context.packageName
        val system = FakeSystem(
            tasks = listOf(
                RecentTasks.Raw(1, self, null),
                RecentTasks.Raw(2, "com.a", null),
                RecentTasks.Raw(3, "com.a", null),
                RecentTasks.Raw(4, "com.b", null),
            ),
        )
        val list = tasks(system).list()
        assertEquals(listOf("com.a", "com.b"), list.map { it.pkg })
        assertEquals(listOf(2, 4), list.map { it.taskId })
    }

    @Test
    fun 清空保留锁定的应用() {
        prefs.recentLocked = setOf("com.b")
        val self = context.packageName
        val system = FakeSystem(
            tasks = listOf(
                RecentTasks.Raw(1, "com.a", null),
                RecentTasks.Raw(2, "com.b", null),
                RecentTasks.Raw(3, "com.c", null),
                RecentTasks.Raw(4, self, null),
            ),
        )
        val removed = tasks(system).clear()
        assertEquals(2, removed)
        assertEquals(setOf(1, 3), system.removed.toSet())
        assertFalse("锁定/自己/不可用的任务不能被移除", system.removed.contains(2))
        assertFalse(system.removed.contains(4))
    }

    @Test
    fun 权限不足时整页降级() {
        val system = FakeSystem(listThrows = true)
        val recent = tasks(system)
        assertEquals(RecentTasks.State.NO_PERMISSION, recent.state)
        assertFalse(recent.available)
        assertTrue(recent.list().isEmpty())
        assertEquals(0, recent.clear())
    }

    @Test
    fun 打开优先挪任务到前台() {
        val system = FakeSystem(tasks = listOf(RecentTasks.Raw(9, "com.a", null)), moveResult = true)
        val recent = tasks(system)
        val app = recent.list().first()
        assertTrue(recent.open(app))
        assertEquals(1, system.moveCalls)
    }

    @Test
    fun 挪任务失败时回落到启动Intent() {
        val system = FakeSystem(tasks = listOf(RecentTasks.Raw(9, "com.not.installed", null)), moveResult = false)
        val recent = tasks(system)
        val app = recent.list().first()
        // 没有安装、没有启动入口 → 返回 false（不能崩）。
        assertFalse(recent.open(app))
    }

    @Test
    fun 锁定切换会持久化() {
        val recent = tasks(FakeSystem())
        assertTrue(recent.toggleLock("com.a"))
        assertTrue(recent.isLocked("com.a"))
        assertTrue(prefs.recentLocked.contains("com.a"))
        assertFalse(recent.toggleLock("com.a"))
        assertFalse(recent.isLocked("com.a"))
        assertFalse(prefs.recentLocked.contains("com.a"))
    }
}
