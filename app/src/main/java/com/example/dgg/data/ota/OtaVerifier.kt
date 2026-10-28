package com.example.dgg.data.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.dgg.data.LogStore
import java.io.File
import java.security.MessageDigest

/** 校验结论。用携带原因的 [Rejected] 而不是一堆 object，是为了让日志和界面文案能直接复用。 */
sealed class VerifyResult {
    data class Ok(val versionCode: Int, val versionName: String) : VerifyResult()
    data class Rejected(val reason: String) : VerifyResult()
}

/**
 * 安装包校验：**四道，缺一不可**。
 *
 * 本应用走侧载分发（不上商店），客户端就是唯一的信任边界——OTA 一旦被利用，等于给攻击者
 * 一个自动更新的后门。所以这里不做"尽力而为"，任何一道不过都直接拒绝，绝不留降级路径。
 *
 * | # | 校验 | 防什么 |
 * |---|---|---|
 * | 1 | 能解析成 APK | 下载到 HTML 错误页 / 截断文件 |
 * | 2 | 包名一致 | 被换成别的应用 |
 * | 3 | 签名与当前应用**逐字节一致** | 服务端被攻破、中间人替换、二次打包 |
 * | 4 | versionCode 严格递增 | 降级攻击（把人降到已知有漏洞的旧版） |
 *
 * 第 3 道是重点：**不信任服务端下发的签名指纹**，只跟本机已安装应用比。
 * 让服务端下发期望值等于把信任建立在同一个地方，没有增加任何安全性。
 */
object OtaVerifier {

    private const val TAG = "OTA"

    /**
     * @param currentVersionCode 当前已安装版本的 versionCode（见 [currentVersionCode]）
     */
    fun verify(context: Context, apk: File, currentVersionCode: Int): VerifyResult {
        if (!apk.isFile || apk.length() <= 0L) {
            return VerifyResult.Rejected("安装包不存在或为空")
        }

        val pm = context.packageManager
        val archive = archiveInfo(pm, apk)
            ?: return VerifyResult.Rejected("不是有效的 APK（解析失败，可能下载不完整）")

        if (archive.packageName != context.packageName) {
            return VerifyResult.Rejected("包名不符：${archive.packageName}（应为 ${context.packageName}）")
        }

        val installed = installedInfo(pm, context.packageName)
        val mine = installed?.let { signersOf(it) }.orEmpty()
        val theirs = signersOf(archive)
        if (mine.isEmpty()) {
            return VerifyResult.Rejected("读不到当前应用的签名，无法校验")
        }
        if (theirs.isEmpty()) {
            return VerifyResult.Rejected("安装包没有签名")
        }
        if (!sameSigners(mine, theirs)) {
            LogStore.e(
                TAG,
                "签名不一致，拒绝安装：当前 ${fingerprints(mine)} / 下载包 ${fingerprints(theirs)}"
            )
            return VerifyResult.Rejected("签名与当前应用不一致（拒绝安装）")
        }

        val newCode = versionCodeOf(archive)
        if (newCode <= currentVersionCode) {
            return VerifyResult.Rejected("版本号未递增（下载包 $newCode / 当前 $currentVersionCode）")
        }

        LogStore.i(
            TAG,
            "校验通过：${archive.versionName}($newCode)，签名 ${fingerprints(mine)}"
        )
        return VerifyResult.Ok(newCode, archive.versionName ?: "")
    }

    /** 当前已安装应用的 versionCode（API 28+ 用 longVersionCode 的低 32 位） */
    fun currentVersionCode(context: Context): Int {
        val info = installedInfo(context.packageManager, context.packageName) ?: return 0
        return versionCodeOf(info)
    }

    // ==================== 平台差异封装（minSdk 24，签名 API 在 28 变过） ====================

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }.onFailure { LogStore.w(TAG, "解析 APK 失败：${it.message}") }.getOrNull()

    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            ))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    private fun signersOf(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }

    /** 集合比对（签名顺序可能不同，但内容必须完全一致） */
    private fun sameSigners(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        if (a.size != b.size) return false
        val left = a.map { it.toList() }.toSet()
        val right = b.map { it.toList() }.toSet()
        return left == right
    }

    /** 证书指纹（sha256 前 16 字节），只用于日志比对，不参与判定 */
    private fun fingerprints(signers: List<ByteArray>): String =
        signers.joinToString("、") { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(16) + "…"
        }

    private fun versionCodeOf(info: PackageInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
}
