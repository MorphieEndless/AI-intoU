package com.yingti.app.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseParserTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val bodySha = "fedcba9876543210".repeat(4)

    private fun releaseJson(
        tag: String = "v0.19.0",
        digest: String? = "sha256:$sha",
        contentType: String = "application/vnd.android.package-archive",
        assetName: String = "app-release.apk",
        body: String = "更新说明",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """
    {
      "tag_name": "$tag",
      "name": "樱趣 $tag",
      "body": ${JSONObject.quote(body)},
      "published_at": "2026-11-20T12:34:56Z",
      "html_url": "https://github.com/o/r/releases/tag/$tag",
      "draft": $draft,
      "prerelease": $prerelease,
      "assets": [
        {
          "name": "$assetName",
          "size": 300000,
          "browser_download_url": "https://github.com/o/r/releases/download/$tag/$assetName",
          "content_type": "$contentType",
          "digest": ${digest?.let { JSONObject.quote(it) } ?: "null"}
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parsesGithubLatestShape() {
        val info = ReleaseParser.parse(releaseJson())
        assertEquals("v0.19.0", info.tag)
        assertEquals("0.19.0", info.version)
        assertEquals("樱趣 v0.19.0", info.title)
        assertEquals("更新说明", info.notes)
        assertEquals("2026-11-20", info.publishedAt)
        assertEquals("https://github.com/o/r/releases/tag/v0.19.0", info.pageUrl)
        val apk = info.apk!!
        assertEquals("app-release.apk", apk.name)
        assertEquals(300000L, apk.size)
        assertEquals(sha, apk.sha256)
        assertTrue(apk.url.endsWith("/app-release.apk"))
    }

    @Test
    fun shaFallsBackToBodyWhenDigestMissing() {
        val body = "修复若干问题\n\nSHA-256: `$bodySha`"
        val info = ReleaseParser.parse(releaseJson(digest = null, body = body))
        assertEquals(bodySha, info.apk!!.sha256)
    }

    @Test
    fun picksApkByExtensionWhenContentTypeIsGeneric() {
        val info = ReleaseParser.parse(
            releaseJson(contentType = "application/octet-stream", assetName = "yingti.APK"),
        )
        assertEquals("yingti.APK", info.apk!!.name)
    }

    @Test
    fun noApkAssetGivesNull() {
        val info = ReleaseParser.parse(
            releaseJson(contentType = "text/plain", assetName = "notes.txt"),
        )
        assertNull(info.apk)
    }

    @Test
    fun draftAndPrereleaseAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            ReleaseParser.parse(releaseJson(prerelease = true))
        }
        assertThrows(IllegalStateException::class.java) {
            ReleaseParser.parse(releaseJson(draft = true))
        }
    }

    @Test
    fun versionComparison() {
        assertTrue(isNewer("0.19.0", "0.17.0"))
        assertFalse(isNewer("0.17.0", "0.19.0"))
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("0.17.10", "0.17.9") > 0)
        assertEquals(0, compareVersions("0.19.0-beta", "0.19.0"))
        assertTrue(compareVersions("0.19.0", "0.19.0-beta") == 0)
        assertTrue(compareVersions("2.0.0", "10.0") < 0)
        assertTrue(compareVersions("abc", "0.0.1") < 0)
    }
}
