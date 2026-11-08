package com.yingti.app.update

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdaterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentLinkedQueue<String>()

    private val apkBytes = ByteArray(2 + 300 * 1024).also { bytes ->
        bytes[0] = 'P'.code.toByte()
        bytes[1] = 'K'.code.toByte()
        val random = Random(42)
        for (i in 2 until bytes.size) bytes[i] = random.nextInt(256).toByte()
    }
    private val apkSha = sha256(apkBytes)
    private val corruptBytes = apkBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    private val assetUrl = "https://github.com/o/r/releases/download/v0.19.0/app-release.apk"

    private class Mirror {
        var apiStatus = 500
        var apiBody = ""
        var probeStatus = 206
        var probeBody = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())
        var probeTotal = -1L
        var probeDelayMs = 0L
        var downloadStatus = 200
        var downloadBody: ByteArray? = null
        var throttleBytes = 0L
        var throttlePeriodMs = 0L
    }

    private val mirrors = mutableMapOf<String, Mirror>()
    private var officialStatus = 500
    private var officialBody = ""

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                requests.add(path)
                if (path == "/latest") {
                    return MockResponse().setResponseCode(officialStatus).setBody(officialBody)
                }
                val name = path.removePrefix("/").substringBefore("/")
                val m = mirrors[name] ?: return MockResponse().setResponseCode(404)
                if (path.endsWith("/latest")) {
                    return MockResponse().setResponseCode(m.apiStatus).setBody(m.apiBody)
                }
                if (request.getHeader("Range") != null) {
                    val resp = MockResponse().setResponseCode(m.probeStatus)
                    if (m.probeDelayMs > 0) resp.setHeadersDelay(m.probeDelayMs, TimeUnit.MILLISECONDS)
                    if (m.probeStatus == 206) {
                        val total = if (m.probeTotal >= 0) m.probeTotal else apkBytes.size
                        resp.setHeader("Content-Range", "bytes 0-${m.probeBody.size - 1}/$total")
                    }
                    return resp.setBody(Buffer().write(m.probeBody))
                }
                val resp = MockResponse().setResponseCode(m.downloadStatus)
                if (m.throttleBytes > 0) {
                    resp.throttleBody(m.throttleBytes, m.throttlePeriodMs, TimeUnit.MILLISECONDS)
                }
                return resp.setBody(Buffer().write(m.downloadBody ?: apkBytes))
            }
        }
        server.start()
        dir = temp.newFolder()
    }

    @After
    fun stopServer() {
        scope.cancel()
        server.shutdown()
    }

    private fun mirror(name: String, proxiesApi: Boolean = true): UpdateSource {
        mirrors[name] = Mirror()
        return UpdateSource(name, server.url("/$name/").toString(), proxiesApi)
    }

    private fun updater(currentVersion: String = "0.17.0", sources: List<UpdateSource>) = AppUpdater(
        client = OkHttpClient(),
        downloadDir = dir,
        currentVersion = currentVersion,
        sources = sources,
        latestApiUrl = server.url("/latest").toString(),
        scope = scope,
    )

    private fun release(tag: String = "v0.19.0") = ReleaseInfo(
        tag = tag,
        version = tag.removePrefix("v"),
        title = tag,
        notes = "",
        publishedAt = "2026-11-20",
        pageUrl = "https://github.com/o/r/releases/tag/$tag",
        apk = ReleaseAsset("app-release.apk", assetUrl, apkBytes.size.toLong(), apkSha),
    )

    private fun latestJson(tag: String = "v0.19.0") = """
    {
      "tag_name": "$tag",
      "name": "$tag",
      "body": "notes",
      "published_at": "2026-11-20T00:00:00Z",
      "html_url": "https://github.com/o/r/releases/tag/$tag",
      "draft": false,
      "prerelease": false,
      "assets": [{
        "name": "app-release.apk",
        "size": ${apkBytes.size},
        "browser_download_url": "$assetUrl",
        "content_type": "application/vnd.android.package-archive",
        "digest": "sha256:$apkSha"
      }]
    }
    """.trimIndent()

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun checkFallsBackAcrossApiSourcesAndSkipsDownloadOnlyMirrors() = runBlocking {
        val m0 = mirror("m0", proxiesApi = false)
        mirrors["m0"]!!.apply { apiStatus = 200; apiBody = latestJson("v9.9.9") }
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        mirrors["m2"]!!.apply { apiStatus = 200; apiBody = latestJson() }
        val u = updater(sources = listOf(m0, m1, m2))
        u.check()
        val s = u.state.value
        assertTrue(s is UpdateState.Available)
        assertEquals("0.19.0", (s as UpdateState.Available).release.version)
        assertTrue(requests.none { it.startsWith("/m0/") })
    }

    @Test
    fun checkReportsUpToDateForSameVersion() = runBlocking {
        val m1 = mirror("m1")
        mirrors["m1"]!!.apply { apiStatus = 200; apiBody = latestJson("v0.17.0") }
        val u = updater(sources = listOf(m1))
        u.check()
        val s = u.state.value
        assertTrue(s is UpdateState.UpToDate)
        assertEquals("v0.17.0", (s as UpdateState.UpToDate).latest?.tag)
    }

    @Test
    fun checkFailsWhenEverySourceFails() = runBlocking {
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        val u = updater(sources = listOf(m1, m2))
        u.check()
        val s = u.state.value
        assertTrue(s is UpdateState.Failed)
        assertEquals("无法连接 GitHub 或镜像源，请检查网络后重试", (s as UpdateState.Failed).message)
        assertNull(s.release)
    }

    @Test
    fun official404MeansNoReleasesYet() = runBlocking {
        officialStatus = 404
        val u = updater(sources = listOf(UpdateSource("GitHub 官方", "", proxiesApi = true)))
        u.check()
        val s = u.state.value
        assertTrue(s is UpdateState.UpToDate)
        assertNull((s as UpdateState.UpToDate).latest)
    }

    @Test
    fun rankSourcesOrdersFastBeforeSlowAndDropsInvalid() = runBlocking {
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        val m3 = mirror("m3")
        mirrors["m1"]!!.probeDelayMs = 400
        mirrors["m3"]!!.apply { probeStatus = 200; probeBody = "<html>oops</html>".toByteArray() }
        val u = updater(sources = listOf(m1, m2, m3))
        val ranked = u.rankSources(release().apk!!)
        assertEquals(listOf("m2", "m1"), ranked.map { it.name })
    }

    @Test
    fun downloadStreamsVerifiesAndReportsProgress() = runBlocking {
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        mirrors["m1"]!!.apply {
            throttleBytes = 32768
            throttlePeriodMs = 60
        }
        mirrors["m2"]!!.probeDelayMs = 300
        val u = updater(sources = listOf(m1, m2))
        val states = java.util.Collections.synchronizedList(mutableListOf<UpdateState>())
        val collector = scope.launch { u.state.collect { states.add(it) } }
        u.download(release())
        collector.cancel()
        val s = u.state.value
        assertTrue(s is UpdateState.Downloaded)
        s as UpdateState.Downloaded
        assertEquals("m1", s.source.name)
        assertArrayEquals(apkBytes, s.file.readBytes())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        val progress = states.filterIsInstance<UpdateState.Downloading>()
        assertTrue("expected Downloading progress frames in $states", progress.isNotEmpty())
        assertTrue(progress.all { it.total == apkBytes.size.toLong() && it.downloaded in 1..it.total })
        assertTrue(progress.zipWithNext().all { (a, b) -> b.downloaded >= a.downloaded })
    }

    @Test
    fun downloadFallsBackWhenFastestSourceIsCorrupt() = runBlocking {
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        mirrors["m1"]!!.downloadBody = corruptBytes
        mirrors["m2"]!!.probeDelayMs = 300
        val u = updater(sources = listOf(m1, m2))
        u.download(release())
        val s = u.state.value
        assertTrue(s is UpdateState.Downloaded)
        s as UpdateState.Downloaded
        assertEquals("m2", s.source.name)
        assertArrayEquals(apkBytes, s.file.readBytes())
    }

    @Test
    fun downloadFailsWhenEverySourceIsCorrupt() = runBlocking {
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        mirrors["m1"]!!.downloadBody = corruptBytes
        mirrors["m2"]!!.downloadBody = corruptBytes
        val u = updater(sources = listOf(m1, m2))
        u.download(release())
        val s = u.state.value
        assertTrue(s is UpdateState.Failed)
        s as UpdateState.Failed
        assertNotNull(s.release)
        assertTrue(s.message.startsWith("所有下载源都失败了"))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun cancelDownloadStopsAndRestoresAvailable() = runBlocking {
        val m1 = mirror("m1")
        val m2 = mirror("m2")
        mirrors["m1"]!!.apply {
            apiStatus = 200
            apiBody = latestJson()
            throttleBytes = 16384
            throttlePeriodMs = 50
        }
        mirrors["m2"]!!.probeDelayMs = 300
        val u = updater(sources = listOf(m1, m2))
        u.check()
        u.startDownload()
        waitFor("downloading state") { u.state.value is UpdateState.Downloading }
        u.cancelDownload()
        waitFor("available state") { u.state.value is UpdateState.Available }
        waitFor("part cleanup") { dir.listFiles()!!.none { it.name.endsWith(".part") } }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
