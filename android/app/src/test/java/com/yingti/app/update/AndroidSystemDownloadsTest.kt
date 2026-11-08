package com.yingti.app.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidSystemDownloadsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun enqueueConfiguresRequestAndLifecycleWorks() {
        val downloads = AndroidSystemDownloads(context)
        val id = downloads.enqueue(
            url = "https://example.com/app-release.apk",
            fileName = "AI-intoU-v0.19.0.apk",
            title = "AI-intoU v0.19.0",
            description = "从 GitHub 官方 下载",
        )

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val shadow = shadowOf(dm)
        val request = shadowOf(shadow.getRequest(id))
        assertEquals("application/vnd.android.package-archive", request.mimeType.toString())
        assertEquals(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            request.notificationVisibility,
        )
        assertEquals("AI-intoU v0.19.0", request.title.toString())
        assertEquals("从 GitHub 官方 下载", request.description.toString())
        // Robolectric 的 getExternalFilesDir 与真机目录结构不同，按 Context 实际返回值比对。
        val expected = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AI-intoU-v0.19.0.apk")
        assertTrue(expected.path.contains(context.packageName))
        assertEquals(expected.path, request.destination.path)

        assertNotNull(downloads.query(id))
        request.status = DownloadManager.STATUS_RUNNING
        assertEquals(DownloadStatus.RUNNING, downloads.query(id)!!.status)
        assertEquals(expected.path, downloads.localFile(id)?.path)
        downloads.remove(id)
        assertNull(downloads.query(id))
    }
}
