package com.yingti.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val certSha256: Set<String>?,
)

object ApkVerifier {
    /** null = OK to install, otherwise a user-facing Chinese reason. */
    fun problem(
        archive: ArchiveFacts?,
        expectedPackage: String,
        installedVersionCode: Long,
        installedCerts: Set<String>?,
    ): String? {
        if (archive == null) return "安装包无法解析，可能已损坏，请重新下载"
        if (archive.packageName != expectedPackage) return "安装包不是樱趣（包名不符），已拒绝安装"
        if (archive.versionCode <= installedVersionCode) return "安装包版本不比当前版本新，无需安装"
        val archiveCerts = archive.certSha256
        if (!archiveCerts.isNullOrEmpty() && !installedCerts.isNullOrEmpty() && archiveCerts != installedCerts) {
            return "安装包签名与当前 App 不同（例如当前装的是 debug 版），无法覆盖安装。请到 Release 页面手动下载，并先卸载旧版"
        }
        return null
    }

    fun archiveFacts(context: Context, file: File): ArchiveFacts? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.path, signatureFlags()) ?: return null
        return ArchiveFacts(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            certSha256 = certHashes(info),
        )
    }

    fun installedFacts(context: Context): ArchiveFacts? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = try {
            pm.getPackageInfo(context.packageName, signatureFlags())
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        return ArchiveFacts(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            certSha256 = certHashes(info),
        )
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int {
        var flags = PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= 28) flags = flags or PackageManager.GET_SIGNING_CERTIFICATES
        return flags
    }

    @Suppress("DEPRECATION")
    private fun certHashes(info: PackageInfo): Set<String>? {
        val signers: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
        } else {
            info.signatures
        }
        val hashes = signers?.map { sha256Hex(it.toByteArray()) }?.toSet().orEmpty()
        return hashes.ifEmpty { null }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
