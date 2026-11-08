package com.yingti.app.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException

enum class DownloadStatus { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED }

data class DownloadSnapshot(
    val status: DownloadStatus,
    val downloaded: Long,
    val total: Long,
    val reason: Int = 0,
)

interface SystemDownloads {
    /** Enqueues [url] into the app-specific external Downloads dir as [fileName]; throws if the system refuses. */
    fun enqueue(url: String, fileName: String, title: String, description: String): Long

    /** null = the record is gone (user removed it from the notification / Downloads app). */
    fun query(id: Long): DownloadSnapshot?

    /** Removes the record AND deletes its file (also clears the notification). */
    fun remove(id: Long)

    /** Local file of a SUCCESSFUL download, readable by path without any permission. */
    fun localFile(id: Long): File?
}

/** Pure mapping, unit-tested on the JVM. */
fun downloadStatusOf(dmStatus: Int): DownloadStatus = when (dmStatus) {
    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
    DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
    DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESSFUL
    else -> DownloadStatus.FAILED
}

/**
 * 下载落在应用专属的外部 Downloads 目录（getExternalFilesDir），任何 Android
 * 版本都不需要存储权限：DownloadProvider 只对调用方包外的路径要求
 * WRITE_EXTERNAL_STORAGE。
 * 安装由用户点系统「下载完成」通知发起，App 自己不发送安装 Intent，
 * 因此不声明任何安装权限。
 */
class AndroidSystemDownloads(private val context: Context) : SystemDownloads {
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override fun enqueue(url: String, fileName: String, title: String, description: String): Long {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("外部存储不可用，无法下载更新")
        // 同名旧文件会让 DownloadManager 报错或改名，先删掉。
        File(dir, fileName).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle(title)
            .setDescription(description)
        return dm.enqueue(request)
    }

    override fun query(id: Long): DownloadSnapshot? {
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return DownloadSnapshot(downloadStatusOf(status), downloaded, total, reason)
        }
    }

    override fun remove(id: Long) {
        dm.remove(id)
    }

    override fun localFile(id: Long): File? {
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val column = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val value = if (column >= 0) it.getString(column) else null
            return value?.let { uri -> Uri.parse(uri).path?.let(::File) }
        }
    }
}
