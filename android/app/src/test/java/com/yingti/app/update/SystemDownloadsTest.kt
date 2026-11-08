package com.yingti.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemDownloadsTest {
    @Test
    fun mapsDownloadManagerStatusInts() {
        assertEquals(DownloadStatus.PENDING, downloadStatusOf(1))
        assertEquals(DownloadStatus.RUNNING, downloadStatusOf(2))
        assertEquals(DownloadStatus.PAUSED, downloadStatusOf(4))
        assertEquals(DownloadStatus.SUCCESSFUL, downloadStatusOf(8))
        assertEquals(DownloadStatus.FAILED, downloadStatusOf(16))
        assertEquals(DownloadStatus.FAILED, downloadStatusOf(0))
        assertEquals(DownloadStatus.FAILED, downloadStatusOf(-1))
        assertEquals(DownloadStatus.FAILED, downloadStatusOf(999))
    }
}
