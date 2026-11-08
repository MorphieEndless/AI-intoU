package com.yingti.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkVerifierTest {
    private val pkg = "com.yingti.app"
    private val certs = setOf("a".repeat(64))

    private fun facts(
        packageName: String = pkg,
        versionCode: Long = 24,
        certSha256: Set<String>? = certs,
    ) = ArchiveFacts(packageName, versionCode, certSha256)

    @Test
    fun unparseableArchiveIsRejected() {
        assertEquals(
            "安装包无法解析，可能已损坏，请重新下载",
            ApkVerifier.problem(null, pkg, 23, certs),
        )
    }

    @Test
    fun wrongPackageIsRejected() {
        assertEquals(
            "安装包不是樱趣（包名不符），已拒绝安装",
            ApkVerifier.problem(facts(packageName = "com.other"), pkg, 23, certs),
        )
    }

    @Test
    fun staleVersionIsRejected() {
        assertEquals(
            "安装包版本不比当前版本新，无需安装",
            ApkVerifier.problem(facts(versionCode = 23), pkg, 23, certs),
        )
        assertEquals(
            "安装包版本不比当前版本新，无需安装",
            ApkVerifier.problem(facts(versionCode = 22), pkg, 23, certs),
        )
    }

    @Test
    fun mismatchedSignatureIsRejected() {
        assertEquals(
            "安装包签名与当前 App 不同（例如当前装的是 debug 版），无法覆盖安装。请到 Release 页面手动下载，并先卸载旧版",
            ApkVerifier.problem(facts(certSha256 = setOf("b".repeat(64))), pkg, 23, certs),
        )
    }

    @Test
    fun matchingArchiveIsAccepted() {
        assertNull(ApkVerifier.problem(facts(), pkg, 23, certs))
    }

    @Test
    fun missingCertInfoFallsThroughToSystemInstaller() {
        assertNull(ApkVerifier.problem(facts(certSha256 = null), pkg, 23, certs))
        assertNull(ApkVerifier.problem(facts(), pkg, 23, null))
        assertNull(ApkVerifier.problem(facts(certSha256 = emptySet()), pkg, 23, emptySet()))
    }
}
