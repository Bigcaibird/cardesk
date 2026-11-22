package com.cardesk.launcher.ui

import android.content.Context
import com.cardesk.launcher.core.VideoLibrary
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 第219轮：**视频库目录**必须和**局域网传输**的上传目录是同一个。
 *
 * ## 为什么要有这个测试
 *
 * 视频进车机的路径是「设置 → 局域网传输 → 手机扫码上传」，
 * 落点就是局域网传输的分享目录。
 * 视频库扫的目录必须**就是那个目录**，否则用户传完了却列不出来。
 *
 * 两个类各自写了一份目录名（`VideoLibrary.SHARE_DIR_NAME` 与
 * `LanTransferServer.SHARE_DIR_NAME`）⇒ 用测试把这条焊死，
 * 以后改了一边忘了另一边，测试立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoLibraryShareDirTest {

    @Test
    fun `视频库目录和局域网传输的分享目录是同一个`() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals(
            LanTransferServer.sharedDirectoryPath(ctx),
            VideoLibrary.libraryDir(ctx).absolutePath,
        )
    }

    @Test
    fun `视频库目录在Download下面`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        val path = VideoLibrary.libraryDir(ctx).absolutePath
        assertEquals(true, path.contains("/Download/"))
        assertEquals(true, path.endsWith("/${VideoLibrary.SHARE_DIR_NAME}"))
    }
}
