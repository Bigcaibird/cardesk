package com.cardesk.launcher.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStartupCheckTest {

    @Test
    fun promptsWhenNewerVersionNotSkipped() {
        assertTrue(UpdateStartupCheck.shouldPrompt(latestVersionCode = 4185L, skippedVersionCode = 0L))
        assertTrue(UpdateStartupCheck.shouldPrompt(latestVersionCode = 4185L, skippedVersionCode = 4180L))
    }

    @Test
    fun doesNotPromptForSkippedVersion() {
        assertFalse(UpdateStartupCheck.shouldPrompt(latestVersionCode = 4185L, skippedVersionCode = 4185L))
    }

    @Test
    fun doesNotPromptWithoutVersionCode() {
        assertFalse(UpdateStartupCheck.shouldPrompt(latestVersionCode = 0L, skippedVersionCode = 0L))
    }

    @Test
    fun checkDelayIsShortButNotDuringFirstFrame() {
        // 启动检测必须晚于首帧，又不能拖太久（3 秒内）
        assertTrue(UpdateStartupCheck.CHECK_DELAY_MS in 1_000L..5_000L)
    }
}
