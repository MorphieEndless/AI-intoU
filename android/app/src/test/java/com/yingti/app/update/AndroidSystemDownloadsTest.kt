package com.yingti.app.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
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
        val destination = request.destination.path.orEmpty()
        assertTrue(destination.contains(context.packageName))
        assertTrue(destination.endsWith("files/Download/AI-intoU-v0.19.0.apk"))

        assertNotNull(downloads.query(id))
        request.status = DownloadManager.STATUS_RUNNING
        assertEquals(DownloadStatus.RUNNING, downloads.query(id)!!.status)
        assertNotNull(downloads.localFile(id))
        downloads.remove(id)
        assertNull(downloads.query(id))
    }
}
