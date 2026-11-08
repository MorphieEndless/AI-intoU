package com.yingti.app.update

import org.json.JSONObject

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class ReleaseInfo(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
    val apk: ReleaseAsset?,
)

object ReleaseParser {
    private val bodySha = Regex("""SHA-256:\s*`?([0-9a-fA-F]{64})`?""")

    /** Parses the GitHub `releases/latest` JSON. Throws on draft / prerelease. */
    fun parse(json: String): ReleaseInfo {
        val root = JSONObject(json)
        check(!root.optBoolean("draft") && !root.optBoolean("prerelease")) {
            "release is not final"
        }
        val tag = root.optString("tag_name")
        val body = if (root.isNull("body")) "" else root.optString("body")
        val title = if (root.isNull("name")) tag else root.optString("name").ifBlank { tag }
        val assets = root.optJSONArray("assets")
        val parsed = assets?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val a = array.optJSONObject(i) ?: return@mapNotNull null
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.isBlank() || url.isBlank()) return@mapNotNull null
                val digest = a.optString("digest")
                val sha = if (digest.startsWith("sha256:")) {
                    digest.removePrefix("sha256:").lowercase()
                } else {
                    bodySha.find(body)?.groupValues?.get(1)?.lowercase()
                }
                Triple(
                    ReleaseAsset(name, url, a.optLong("size"), sha),
                    a.optString("content_type") == "application/vnd.android.package-archive",
                    name.endsWith(".apk", ignoreCase = true),
                )
            }
        }.orEmpty()
        val apk = parsed.firstOrNull { it.second }?.first
            ?: parsed.firstOrNull { it.third }?.first
        return ReleaseInfo(
            tag = tag,
            version = tag.removePrefix("v").removePrefix("V"),
            title = title,
            notes = body,
            publishedAt = root.optString("published_at").take(10),
            pageUrl = root.optString("html_url").ifBlank { UpdateSources.RELEASES_PAGE },
            apk = apk,
        )
    }
}

/** Numeric dotted compare; suffixes after '-' or '+' are stripped ("0.19.0-beta" == "0.19.0"). */
fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.substringBefore('-').substringBefore('+')
        .split('.').map { it.toIntOrNull() ?: 0 }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

fun isNewer(latest: String, current: String) = compareVersions(latest, current) > 0
