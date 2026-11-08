package com.yingti.app.update

data class UpdateSource(val name: String, val prefix: String, val proxiesApi: Boolean) {
    fun wrap(url: String) = prefix + url
}

object UpdateSources {
    const val REPO = "MorphieEndless/AI-IntoU"
    const val REPO_PAGE = "https://github.com/$REPO"
    const val RELEASES_PAGE = "$REPO_PAGE/releases"
    const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    // 镜像源都是第三方公开代理，随时可能失效：测速环节会剔除连不通的，
    // 安装前的完整性靠 SHA-256 与签名校验保证，不依赖对镜像源的信任。
    val ALL = listOf(
        UpdateSource("GitHub 官方", "", proxiesApi = true),
        UpdateSource("ghfast.top", "https://ghfast.top/", proxiesApi = false),
        UpdateSource("gh-proxy.com", "https://gh-proxy.com/", proxiesApi = true),
        UpdateSource("cdn.gh-proxy.com", "https://cdn.gh-proxy.com/", proxiesApi = true),
        UpdateSource("edgeone.gh-proxy.com", "https://edgeone.gh-proxy.com/", proxiesApi = true),
    )
}
