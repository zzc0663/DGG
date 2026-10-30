package com.doujiajia.app.data.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.doujiajia.app.data.LogStore
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
        // 第 4 道（versionCode 严格递增）在 currentVersionCode = 0 时恒成立，等于没校验。
        // 读不到当前版本是异常状态，宁可拒绝也不能让降级保护静默失效。
        if (currentVersionCode <= 0) {
            return VerifyResult.Rejected("读不到当前应用版本号，无法校验版本递增（拒绝安装）")
        }

        val pm = context.packageManager
        val archive = archiveInfo(pm, apk)
            ?: return VerifyResult.Rejected("不是有效的 APK（解析失败，可能下载不完整）")

        if (archive.packageName != context.packageName) {
            return VerifyResult.Rejected("包名不符：${archive.packageName}（应为 ${context.packageName}）")
        }

        val installed = installedInfo(pm, context.packageName)
        // 两处都要带回退：API 28~32 读**未安装的 APK 文件**时 `signingInfo` 常常是空的
        // （系统只给已安装应用填），必须再按 GET_SIGNATURES 读一次 v1 签名。
        // 见 [fetchSigners] 与 2026-10-31 的 R-20261030-445040。
        val mine = fetchSigners(pm, context.packageName, installed)
        val theirs = fetchSigners(pm, apk, archive)
        if (mine.isEmpty()) {
            return VerifyResult.Rejected("读不到当前应用的签名，无法校验")
        }
        if (theirs.isEmpty()) {
            // 走到这里代表**两种读法都空了**（[fetchSigners] 已经试过
            // GET_SIGNING_CERTIFICATES 和 GET_SIGNATURES）。历史上的两次误判：
            // - 2026-10-30 R-20261030-50E355：AGP 在 minSdk >= 24 时默认不签 v1，
            //   包只有 v2 ⇒ API<28 读 v1 恒为 null ⇒ 修法是 `enableV1Signing = true`；
            // - 2026-10-31 R-20261030-445040：API 28~32 读**未安装的 APK 文件**不填
            //   `signingInfo` ⇒ 必须回退到 GET_SIGNATURES（那条回退已加上）。
            val api = Build.VERSION.SDK_INT
            LogStore.e(
                TAG,
                "两种读法都读不到下载包签名（API $api；GET_SIGNING_CERTIFICATES 与 " +
                    "GET_SIGNATURES 均返回空）；包大小 ${apk.length()} 字节，" +
                    "可能是包内没有 v1/v2 签名，或文件被截断"
            )
            return VerifyResult.Rejected(
                "安装包没有签名（本机 API $api 读不出签名信息），请手动安装最新版本"
            )
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

    // ==================== 平台差异封装（minSdk 24） ====================
    //
    // 🔴 这里有两个**互相独立**的 API 分界，历史上把它们当成一个就踩过坑：
    //
    // | 分界 | API | 影响 |
    // |---|---|---|
    // | 签名集合怎么取 | 28 (P) | `signingInfo` / `GET_SIGNING_CERTIFICATES` 从 28 起才有 |
    // | **读 APK 文件的签名** | **28~32** | `getPackageArchiveInfo` 读**未安装**的包时**不填 `signingInfo`** ⇒ 必须回退 `GET_SIGNATURES`（2026-10-31 补，见 [fetchSigners]） |
    // | 重载签名怎么传 | 33 (TIRAMISU) | `PackageInfoFlags.of(long)` 从 **33** 起才有 |
    //
    // 2026-10-30 线上事故：两个重载都用 `SDK_INT >= P` 判断，于是在 **Android 9~12L（API 28~32）**
    // 上调用 `PackageInfoFlags.of(0L)` → `NoSuchMethodError`（framework.jar 里根本没有这个类）。
    // 后果是**静默的**：`currentVersionCode()` 拿不到值返回 0，`archiveInfo()` 返回 null。
    // 面板表现是「发现新版本 32，当前 0」→ 下载 4.6 MB →「不是有效的 APK」→ 循环重试全失败。
    // 查证见日志 R-20261030-E82372：`No static method of(J)Landroid/content/pm/PackageManager$PackageInfoFlags;`

    /** 取 PackageInfo 需要的 flags（签名集合在 28 起改用 GET_SIGNING_CERTIFICATES） */
    private fun infoFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            legacyFlags()
        }

    /** v1（JAR）签名专用的 flags。API 28+ 仍可用，是读 APK 文件时唯一可靠的一条路 */
    @Suppress("DEPRECATION")
    private fun legacyFlags(): Int = PackageManager.GET_SIGNATURES

    /**
     * 读签名集合，**带一次回退**。
     *
     * 为什么必须回退（2026-10-31，日志 R-20261030-445040）：
     * 一台 Android 10（API 29）的设备升 1.4.11 → 最新版，连试 5 轮全是
     * `读不到下载包的签名（API 29，当前系统用 GET_SIGNING_CERTIFICATES）`。
     *
     * `SigningInfo` 虽然是 API 28 引入的，但 **API 28~32 的 `getPackageArchiveInfo` 读的是
     * 未安装的 APK 文件，不会给它填 `signingInfo`**（只有已安装应用的 `PackageInfo` 才有）。
     * 结果就是「包明明签了名，本机却读不到」。而包里有 v1 签名（构建时 `enableV1Signing = true`），
     * 用 `GET_SIGNATURES` 一定读得出来 —— 所以：**先按 API 分支读，空就换 GET_SIGNATURES 再读一次**。
     *
     * ⚠️ 这条回退不是"降级安全"：比对的仍然是证书 DER 字节，只是换了条读法。
     */
    private fun fetchSigners(pm: PackageManager, target: Any, info: PackageInfo?): List<ByteArray> {
        val primary = info?.let { signersOf(it) }.orEmpty()
        if (primary.isNotEmpty()) return primary

        val legacy = runCatching {
            when (target) {
                is File -> archiveInfo(pm, target)
                is String -> installedInfo(pm, target)
                else -> null
            }
        }.getOrNull()

        val fallback = legacy?.let { signersOfV1(it) }.orEmpty()
        if (fallback.isNotEmpty()) {
            LogStore.i(
                TAG,
                "签名回退成功：GET_SIGNING_CERTIFICATES 为空，改用 GET_SIGNATURES 读到 v1 签名" +
                    "（API ${Build.VERSION.SDK_INT}，${if (target is File) "下载包" else "已安装应用"}）"
            )
        }
        return fallback
    }

    private fun archiveInfo(pm: PackageManager, apk: File, flags: Int = infoFlags()): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, flags)
            }
        }.onFailure { LogStore.w(TAG, "解析 APK 失败：${it.message}") }.getOrNull()

    private fun installedInfo(pm: PackageManager, pkg: String, flags: Int = infoFlags()): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, flags)
            }
        }.onFailure { LogStore.w(TAG, "读取已安装版本失败：${it.message}") }.getOrNull()

    private fun signersOf(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            signersOfV1(info)
        }

    @Suppress("DEPRECATION")
    private fun signersOfV1(info: PackageInfo): List<ByteArray> =
        info.signatures?.map { it.toByteArray() }.orEmpty()

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
