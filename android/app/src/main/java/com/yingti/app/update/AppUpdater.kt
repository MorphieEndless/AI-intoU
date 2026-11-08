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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用内更新：App 自己把 APK 下载到私有 cache 目录（不需要存储权限），
 * 再通过 FileProvider 调起系统安装器，因此清单里声明了
 * REQUEST_INSTALL_PACKAGES。把下载交给系统 DownloadManager 并不能绕开它：
 * 用户从下载通知或文件管理器打开 APK 时，PackageInstaller 会把安装归因给
 * 入队下载的 App（EXTRA_ORIGINATING_UID），未声明该权限会被直接拒绝；
 * 改走第三方文件管理器/浏览器，则要把「安装未知应用」授给那个 App
 * （它能装任何东西，范围更大），也拿不到应用内进度、镜像测速与完整性校验。
 * 声明权限本身不授予任何能力：用户在点安装时才单独为樱趣打开
 * 「允许安装未知应用」，并且随时可以在系统设置里收回。
 * 零权限退路始终保留：「在浏览器中打开 Release 页面」。
 */
class AppUpdater(
    private val client: OkHttpClient,
    private val downloadDir: File,
    private val currentVersion: String,
    val sources: List<UpdateSource> = UpdateSources.ALL,
    private val latestApiUrl: String = UpdateSources.LATEST_API,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun startCheck() {
        when (state.value) {
            is UpdateState.Checking, is UpdateState.Probing, is UpdateState.Downloading -> return
            else -> {}
        }
        checkJob = scope.launch { check() }
    }

    fun startDownload() {
        when (state.value) {
            is UpdateState.Probing, is UpdateState.Downloading -> return
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
            else -> return
        }
        job.cancel()
        scope.launch {
            job.join()
            downloadDir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
            if (state.value is UpdateState.Probing || state.value is UpdateState.Downloading) {
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

    /** Probes every source, then streams the APK from the fastest that verifies. */
    suspend fun download(release: ReleaseInfo) {
        val apk = release.apk ?: return
        mutableState.value = UpdateState.Probing(release)
        downloadDir.mkdirs()
        downloadDir.listFiles()?.forEach { it.delete() }
        val ranked = rankSources(apk)
        var lastError = "下载源不可用"
        for (source in ranked) {
            val part = File(downloadDir, apk.name + ".part")
            val error = streamTo(source, apk, part, release)
            if (error == null) {
                val target = File(downloadDir, apk.name)
                if (part.renameTo(target)) {
                    mutableState.value = UpdateState.Downloaded(release, source, target)
                    return
                }
                lastError = "保存文件失败（${source.name}）"
            } else {
                lastError = error
            }
            part.delete()
        }
        downloadDir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
        mutableState.value = UpdateState.Failed("所有下载源都失败了：$lastError", release)
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

    /** Streams one source into [part]; null on success, a user-facing reason on failure. */
    private suspend fun streamTo(
        source: UpdateSource,
        apk: ReleaseAsset,
        part: File,
        release: ReleaseInfo,
    ): String? {
        val request = Request.Builder().url(source.wrap(apk.url)).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use "${source.name}：HTTP ${response.code}"
                val body = response.body ?: return@use "${source.name}：HTTP ${response.code}"
                val expected = if (apk.size > 0) apk.size else body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastEmit = 0L
                val started = System.nanoTime()
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            downloaded += n
                            val now = System.nanoTime()
                            if (now - lastEmit >= 150_000_000L) {
                                lastEmit = now
                                emitDownloading(release, source, downloaded, expected, started, now)
                            }
                        }
                    }
                }
                val finished = System.nanoTime()
                emitDownloading(release, source, downloaded, expected, started, finished)
                yield() // 让 100% 的进度帧有机会渲染，也让取消在收尾前先落地
                when {
                    expected > 0 && downloaded != expected -> "校验失败（${source.name}）"
                    apk.sha256 != null && hex(digest) != apk.sha256 -> "校验失败（${source.name}）"
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "${source.name}：${e.message ?: "网络错误"}"
        }
    }

    private fun emitDownloading(
        release: ReleaseInfo,
        source: UpdateSource,
        downloaded: Long,
        total: Long,
        startedNanos: Long,
        nowNanos: Long,
    ) {
        val elapsed = nowNanos - startedNanos
        val bps = if (elapsed > 0) downloaded * 1_000_000_000L / elapsed else 0L
        mutableState.value = UpdateState.Downloading(release, source, downloaded, total, bps)
    }

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

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
    data class Downloaded(val release: ReleaseInfo, val source: UpdateSource, val file: File) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

val UpdateState.release: ReleaseInfo?
    get() = when (this) {
        is UpdateState.UpToDate -> latest
        is UpdateState.Available -> release
        is UpdateState.Probing -> release
        is UpdateState.Downloading -> release
        is UpdateState.Downloaded -> release
        is UpdateState.Failed -> release
        else -> null
    }
