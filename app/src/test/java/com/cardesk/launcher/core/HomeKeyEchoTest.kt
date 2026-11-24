package com.cardesk.launcher.core

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home 键「两条入口」的合流规则（第三百一十九轮）。
 *
 * 真车诊断包（2026-11-24）里看得很清楚：Input 通道接管之后，车机框架仍会在
 * **约 0.85~1.0 秒**后补发一条 `CATEGORY_HOME`。旧实现用 500ms 时间窗去重，
 * 回声正好落在窗外 ⇒ 被当成第二次按压，单击被判成双击，最近任务莫名其妙弹出。
 *
 * 这里锁住修复后的语义：
 * - 接管期间，HOME intent 一律按回声忽略，不参与手势记账；
 * - 接管不可用时，兜底路径仍然照常数按压（两条入口不能一起坏掉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HomeKeyEchoTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = Prefs(context)
        prefs.setFlag(HomeKeyRuntime.KEY_ENABLED, true)
        prefs.setFlag(HomeKeyRuntime.KEY_DOUBLE_RECENTS, true)
        HomeKeyRuntime.reset()
    }

    @After
    fun tearDown() {
        HomeKeyRuntime.interceptorActive = false
        HomeKeyRuntime.onGoHome = null
        HomeKeyRuntime.onShowRecents = null
        HomeKeyRuntime.reset()
    }

    @Test
    fun `接管期间 HOME intent 一律当回声，不产生任何动作`() {
        HomeKeyRuntime.interceptorActive = true
        assertEquals(HomeKeyPolicy.Action.NONE, HomeKeyRuntime.onHomeIntent(context, prefs))
        assertEquals(HomeKeyPolicy.Action.NONE, HomeKeyRuntime.onHomeIntent(context, prefs))
        // 连续三条回声也不能把状态机推成「双击」。
        assertEquals(HomeKeyPolicy.Action.NONE, HomeKeyRuntime.onHomeIntent(context, prefs))
    }

    @Test
    fun `接管期间的回声不会污染后续手势记账`() {
        HomeKeyRuntime.interceptorActive = true
        repeat(3) { HomeKeyRuntime.onHomeIntent(context, prefs) }
        // 接管失效后（例如车机重启输入服务），兜底路径的第一下必须还是「回桌面」，
        // 不能因为之前的回声残留而判成双击。
        HomeKeyRuntime.interceptorActive = false
        assertEquals(HomeKeyPolicy.Action.GO_HOME, HomeKeyRuntime.onHomeIntent(context, prefs))
    }

    @Test
    fun `接管不可用时兜底路径照常数按压`() {
        HomeKeyRuntime.interceptorActive = false
        assertEquals(HomeKeyPolicy.Action.GO_HOME, HomeKeyRuntime.onHomeIntent(context, prefs))
        assertEquals(HomeKeyPolicy.Action.SHOW_RECENTS, HomeKeyRuntime.onHomeIntent(context, prefs))
    }

    @Test
    fun `接管期间拦截通道自己的两下仍然算双击`() {
        HomeKeyRuntime.interceptorActive = true
        var recents = 0
        HomeKeyRuntime.onShowRecents = { recents++ }
        HomeKeyRuntime.onInterceptPress(context, prefs)
        // 中间夹一条 HOME intent 回声（真车行为），不能影响判定。
        HomeKeyRuntime.onHomeIntent(context, prefs)
        assertEquals(true, HomeKeyRuntime.onInterceptPress(context, prefs))
        assertEquals(1, recents)
    }
}
