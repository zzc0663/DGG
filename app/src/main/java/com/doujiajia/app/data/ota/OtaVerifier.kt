package com.doujiajia.app.data.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.doujiajia.app.data.LogStore
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Locale
import java.util.zip.ZipFile

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
            // 走到这里代表三路读法全空（见 [fetchSigners]）。历史复盘：
            // - 2026-10-30 R-20261030-50E355：AGP 在 minSdk >= 24 时默认不签 v1，
            //   包只有 v2 ⇒ API<28 读 v1 恒为 null ⇒ 修法是 `enableV1Signing = true`；
            // - 2026-10-30 R-20261030-445040：API 28~32 读**未安装的 APK 文件**不填
            //   `signingInfo` ⇒ 加了 GET_SIGNATURES 回退，但 flags 没换（默认参数仍是
            //   GET_SIGNING_CERTIFICATES）⇒ 回退等于原地重读；
            // - 2026-10-31 R-20261031-DAFA78（API 29，vc53）：上一条的后果暴露 —— 四轮下载全部
            //   卡在「两种读法都读不到签名」。现已是「显式换 flags + 直读 zip 证书」三路，
            //   本机再走到这里就是包真的没 v1 签名了。
            val api = Build.VERSION.SDK_INT
            LogStore.e(
                TAG,
                "三路读法都读不到下载包签名（API $api；GET_SIGNING_CERTIFICATES / " +
                    "GET_SIGNATURES / 直读 META-INF 全部为空）；包大小 ${apk.length()} 字节"
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
     * 读签名集合：**三路递进，前一路空才走下一路**。
     *
     * 为什么需要三路 —— 这块的平台行为比文档写的乱，两次线上事故都栽在"以为只有一种读法"：
     *
     * | 路 | 手段 | 失效场景 |
     * |---|---|---|
     * | 1 | API 28+ `signingInfo.apkContentsSigners` | **读未安装的 APK 文件**时 API 28~32 不填 `signingInfo` |
     * | 2 | `GET_SIGNATURES` 读 v1（JAR）签名 | API 30+ 对 `targetSdk>=30` 停用该 flag |
     * | 3 | **自己解 zip 读 `META-INF` 下的 `.RSA`（PKCS#7）** | 包里没有 v1 签名（minSdk>=24 时 AGP 默认就不签，见 build.gradle.kts） |
     *
     * 🔴 **2026-10-31 R-20261031-DAFA78（Android 10 / API 29）踩的坑**：
     * 第 2 路当时写成 `archiveInfo(pm, target)`，走的是默认参数 `flags = infoFlags()` ——
     * 在 API 28+ 它返回的仍是 `GET_SIGNING_CERTIFICATES`。于是"回退"拿到的是同一份空
     * `signingInfo`，再读 `info.signatures` 自然还是 null。日志打印的
     * 「两种读法都读不到签名」其实**只有一种读法，跑了两遍**。
     * ⇒ 换读法就必须**显式传另一套 flags**，不能依赖默认参数。
     *
     * 第 3 路是给这张表兜底的：它完全不经过 PackageManager，只认 APK 的 zip 结构，
     * 因此不受 API 级别与 targetSdk 影响。代价是只认 v1 签名（v2/v3 是 APK Signing Block，
     * 不是 zip 条目），但这正好是"最老、最保守"的那份证书，与已安装应用的 `signatures` 同源。
     *
     * ⚠️ 三路都只换"读法"，比对物始终是证书 DER 字节，没有降低任何安全标准。
     */
    private fun fetchSigners(pm: PackageManager, target: Any, info: PackageInfo?): List<ByteArray> {
        val label = if (target is File) "下载包" else "已安装应用"

        // 路 1：按 API 分支（28+ 用 GET_SIGNING_CERTIFICATES）
        val primary = info?.let { signersOf(it) }.orEmpty()
        if (primary.isNotEmpty()) return primary

        // 路 2：**显式**换成 GET_SIGNATURES 重读一次（关键：把 legacyFlags 传进去）
        val legacyFlags = legacyFlags()
        val legacy = runCatching {
            when (target) {
                is File -> archiveInfo(pm, target, legacyFlags)
                is String -> installedInfo(pm, target, legacyFlags)
                else -> null
            }
        }.onFailure { LogStore.w(TAG, "$label 回退读 v1 签名失败：${it.message}") }.getOrNull()
        val fallback = legacy?.let { signersOfV1(it) }.orEmpty()
        if (fallback.isNotEmpty()) {
            LogStore.i(
                TAG,
                "签名回退成功：signingInfo 为空，改用 GET_SIGNATURES 读到 v1 签名" +
                    "（$label，API ${Build.VERSION.SDK_INT}）"
            )
            return fallback
        }

        // 路 3：只对下载包有意义（已安装应用的第一路必然读得到）
        if (target is File) {
            val fromJar = signerCertsFromJar(target)
            if (fromJar.isNotEmpty()) {
                LogStore.i(
                    TAG,
                    "签名回退成功：前两路皆空，直读 APK 内 META-INF 证书取得 ${fromJar.size} 张" +
                        "（下载包，API ${Build.VERSION.SDK_INT}）"
                )
                return fromJar
            }
        }

        LogStore.w(TAG, "$label 签名三路皆空（API ${Build.VERSION.SDK_INT}）")
        return emptyList()
    }

    /**
     * 绕过 PackageManager，直接从 APK 的 `META-INF` 下 `.RSA/.DSA/.EC` 条目（PKCS#7 SignedData）里取证书。
     *
     * 只做"取证书 DER"，**不做签名完整性验证** —— 完整性由下载时的流式 sha256
     * 与 [OtaDownloader] 的长度校验负责，这里只回答"这份包的签名者是谁"。
     *
     * ⚠️ 我们的包是自签单证书，`generateCertificates` 返回 1 张。若将来换成带中间 CA 的
     * 证书链，这里会多返回中间证书，[sameSigners] 的严格相等会误判 —— 那时要改成子集比对。
     */
    private fun signerCertsFromJar(apk: File): List<ByteArray> = runCatching {
        val out = ArrayList<ByteArray>()
        val factory = CertificateFactory.getInstance("X.509")
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.uppercase(Locale.ROOT)
                if (!name.startsWith("META-INF/")) continue
                if (!name.endsWith(".RSA") && !name.endsWith(".DSA") && !name.endsWith(".EC")) continue
                zip.getInputStream(entry).use { input ->
                    factory.generateCertificates(input).forEach { out.add(it.encoded) }
                }
            }
        }
        out
    }.onFailure { LogStore.w(TAG, "直读 APK 证书失败：${it.message}") }.getOrDefault(emptyList())

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
