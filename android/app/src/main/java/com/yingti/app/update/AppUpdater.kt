package com.yingti.app.update

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用内更新（零权限路线）：测速选出最快的镜像后，把下载 URL 交给系统
 * DownloadManager，文件落在应用专属的外部 Downloads 目录——任何 Android
 * 版本都不需要存储权限（DownloadProvider 只对调用方包外的路径要求
 * WRITE_EXTERNAL_STORAGE）。
 * App 轮询进度，下载完成后校验文件大小 + SHA-256 + 包名/版本号/签名，
 * 任何一项不符都会通过 remove() 连记录带文件一起删除；App 也从不发送
 * 安装 Intent——用户点系统「下载完成」通知开始安装，Android 14+ 的安装器
 * 会把它归因于系统下载应用而不是樱趣。
 * 已知限制（如实说明）：Android 8–13 的 AOSP 安装器会把「点通知安装」归因
 * 给入队下载的 App（EXTRA_ORIGINATING_UID），因为樱趣不声明
 * REQUEST_INSTALL_PACKAGES 而中止安装；各家 ROM 行为可能不同。
 * 此时的零权限退路是「在浏览器中打开 Release 页面」。
 */
class AppUpdater(
    private val client: OkHttpClient,
    private val downloads: SystemDownloads,
    private val currentVersion: String,
    private val verifyPackage: (File) -> String? = { null },
    val sources: List<UpdateSource> = UpdateSources.ALL,
    private val latestApiUrl: String = UpdateSources.LATEST_API,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = 500,
    private val stallTimeoutMs: Long = 30_000,
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    @Volatile
    private var activeDownloadId: Long? = null

    fun startCheck() {
        when (state.value) {
            is UpdateState.Checking, is UpdateState.Probing, is UpdateState.Downloading,
            is UpdateState.Verifying -> return
            else -> {}
        }
        checkJob = scope.launch { check() }
    }

    fun startDownload() {
        when (state.value) {
            is UpdateState.Probing, is UpdateState.Downloading, is UpdateState.Verifying -> return
            else -> {}
        }
        val release = state.value.release?.takeIf { it.apk != null } ?: return
        downloadJob = scope.launch { download(release) }
    }

    fun cancelDownload() {
        val job = downloadJob ?: return
        val release = when (val s = state.value) {
            is UpdateState.Probing -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Verifying -> s.release
            else -> return
        }
        job.cancel()
        scope.launch {
            job.join()
            activeDownloadId?.let { downloads.remove(it) }
            activeDownloadId = null
            val s = state.value
            if (s is UpdateState.Probing || s is UpdateState.Downloading || s is UpdateState.Verifying) {
                mutableState.value = UpdateState.Available(release)
            }
        }
    }

    /** Latest release lookup across every API-capable source, in order. */
    suspend fun check() {
        mutableState.value = UpdateState.Checking
        val apiClient = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        for (source in sources) {
            if (!source.proxiesApi) continue
            val release = try {
                fetchLatest(source, apiClient)
            } catch (e: ReleaseNotFound) {
                // 404 只在 GitHub 官方源算「还没有 Release」；镜像一个 404 就换下一个。
                if (source.prefix.isEmpty()) {
                    mutableState.value = UpdateState.UpToDate(null)
                    return
                }
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            mutableState.value = if (isNewer(release.version, currentVersion) && release.apk != null) {
                UpdateState.Available(release)
            } else {
                UpdateState.UpToDate(release)
            }
            return
        }
        mutableState.value = UpdateState.Failed("无法连接 GitHub 或镜像源，请检查网络后重试", null)
    }

    /** Concurrent 64 KiB range probes; returns the responsive sources fastest first. */
    suspend fun rankSources(asset: ReleaseAsset): List<UpdateSource> = coroutineScope {
        val probeClient = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
        sources.map { source ->
            async(Dispatchers.IO) {
                val start = System.nanoTime()
                val ok = runCatching { probe(source, asset, probeClient) }.getOrDefault(false)
                if (ok) source to (System.nanoTime() - start) else null
            }
        }.awaitAll().filterNotNull()
            .sortedBy { it.second }
            .map { it.first }
            .ifEmpty { sources }
    }

    /** Probes every source, then hands the fastest working mirror to the system DownloadManager. */
    suspend fun download(release: ReleaseInfo) {
        val apk = release.apk ?: return
        mutableState.value = UpdateState.Probing(release)
        val ranked = rankSources(apk)
        val safeTag = release.tag.map {
            if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_'
        }.joinToString("")
        val fileName = "AI-intoU-$safeTag.apk"
        var lastError = "下载源不可用"
        for (source in ranked) {
            val id = try {
                downloads.enqueue(source.wrap(apk.url), fileName, "AI-intoU ${release.tag}", "从 ${source.name} 下载")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // enqueue 失败是整个 DownloadManager 不可用，换源没有意义。
                mutableState.value = UpdateState.Failed(
                    "系统下载管理器不可用，请点「在浏览器中打开 Release 页面」手动下载",
                    release,
                )
                return
            }
            activeDownloadId = id
            val error = pollDownload(release, source, apk, id)
            activeDownloadId = null
            if (error == null) return // 终态（完成 / 用户删除 / 包校验拒绝）已经写入 state
            lastError = error
        }
        mutableState.value = UpdateState.Failed("所有下载源都失败了：$lastError（损坏的安装包已删除）", release)
    }

    /**
     * Polls one enqueued download until it finishes or stalls.
     * Returns null once a terminal state has been published (the caller must
     * stop), or a per-source failure string after removing the record.
     */
    private suspend fun pollDownload(
        release: ReleaseInfo,
        source: UpdateSource,
        apk: ReleaseAsset,
        id: Long,
    ): String? {
        val started = System.nanoTime()
        var lastBytes = -1L
        var lastProgressAt = started
        while (true) {
            coroutineContext.ensureActive()
            val snapshot = downloads.query(id)
                ?: run {
                    // 用户从通知或「下载」应用里删掉了这条记录——回到可重试状态。
                    mutableState.value = UpdateState.Available(release)
                    return null
                }
            when (snapshot.status) {
                DownloadStatus.PENDING, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> {
                    val total = if (apk.size > 0) apk.size else snapshot.total
                    val elapsed = System.nanoTime() - started
                    val bps = if (elapsed > 0) snapshot.downloaded * 1_000_000_000L / elapsed else 0L
                    mutableState.value = UpdateState.Downloading(
                        release, source, snapshot.downloaded, total, bps,
                    )
                    val now = System.nanoTime()
                    if (snapshot.downloaded > lastBytes) {
                        lastBytes = snapshot.downloaded
                        lastProgressAt = now
                    } else if (now - lastProgressAt >= stallTimeoutMs * 1_000_000L) {
                        downloads.remove(id)
                        return "${source.name}：下载停滞"
                    }
                }
                DownloadStatus.FAILED -> {
                    downloads.remove(id)
                    return "${source.name}：下载失败（${snapshot.reason}）"
                }
                DownloadStatus.SUCCESSFUL -> return verify(release, source, apk, id)
            }
            delay(pollIntervalMs)
        }
    }

    /** Same contract as [pollDownload]. Runs on Dispatchers.IO. */
    private suspend fun verify(
        release: ReleaseInfo,
        source: UpdateSource,
        apk: ReleaseAsset,
        id: Long,
    ): String? {
        mutableState.value = UpdateState.Verifying(release, source)
        return withContext(Dispatchers.IO) {
            val file = downloads.localFile(id)
            if (file == null || !file.exists()) {
                downloads.remove(id)
                return@withContext "无法读取下载文件（${source.name}）"
            }
            if (apk.size > 0 && file.length() != apk.size ||
                (apk.sha256 != null && sha256(file) != apk.sha256)
            ) {
                downloads.remove(id)
                return@withContext "校验失败（${source.name}）"
            }
            val problem = verifyPackage(file)
            if (problem != null) {
                // 文件本身与 GitHub 的摘要对得上，换源拿到的也是同样的字节——直接失败。
                downloads.remove(id)
                mutableState.value = UpdateState.Failed("$problem（已删除这个安装包）", release)
                return@withContext null
            }
            mutableState.value = UpdateState.Downloaded(release, source, id)
            null
        }
    }

    private class ReleaseNotFound : Exception()

    private fun fetchLatest(source: UpdateSource, apiClient: OkHttpClient): ReleaseInfo {
        val request = Request.Builder()
            .url(source.wrap(latestApiUrl))
            .header("Accept", "application/vnd.github+json")
            .build()
        apiClient.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw ReleaseNotFound()
                !response.isSuccessful -> throw java.io.IOException("HTTP ${response.code}")
                else -> {
                    val body = response.body ?: throw java.io.IOException("HTTP ${response.code}")
                    return ReleaseParser.parse(body.string())
                }
            }
        }
    }

    private fun probe(source: UpdateSource, asset: ReleaseAsset, probeClient: OkHttpClient): Boolean {
        val request = Request.Builder()
            .url(source.wrap(asset.url))
            .header("Range", "bytes=0-65535")
            .build()
        probeClient.newCall(request).execute().use { response ->
            if (response.code != 200 && response.code != 206) return false
            val body = response.body ?: return false
            val buffer = ByteArray(65536)
            var total = 0
            body.byteStream().use { input ->
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
            }
            val apkMagic = total >= 2 &&
                buffer[0] == 'P'.code.toByte() && buffer[1] == 'K'.code.toByte()
            if (!apkMagic) return false
            return when (response.code) {
                206 -> {
                    val range = response.header("Content-Range")
                    val ranged = range?.let {
                        CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull()
                    }
                    ranged == null || ranged == asset.size
                }
                else -> body.contentLength() == asset.size
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val CONTENT_RANGE = Regex("""bytes \d+-\d+/(\d+)""")
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** [latest] is null when the repository has no release at all. */
    data class UpToDate(val latest: ReleaseInfo?) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Probing(val release: ReleaseInfo) : UpdateState
    data class Downloading(
        val release: ReleaseInfo,
        val source: UpdateSource,
        val downloaded: Long,
        val total: Long,
        val bytesPerSecond: Long,
    ) : UpdateState
    data class Verifying(val release: ReleaseInfo, val source: UpdateSource) : UpdateState
    data class Downloaded(val release: ReleaseInfo, val source: UpdateSource, val downloadId: Long) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

val UpdateState.release: ReleaseInfo?
    get() = when (this) {
        is UpdateState.UpToDate -> latest
        is UpdateState.Available -> release
        is UpdateState.Probing -> release
        is UpdateState.Downloading -> release
        is UpdateState.Verifying -> release
        is UpdateState.Downloaded -> release
        is UpdateState.Failed -> release
        else -> null
    }
