package com.example.dgg.data.ota

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.URLEncoder

/**
 * 一次「查更新」的请求参数。
 *
 * 做成独立对象（而不是把一堆参数塞进 provider 方法）有两个理由：
 * 1. 服务端就绪后要这些字段做灰度和统计，客户端必须一次性报全；
 * 2. 本地联调时可以随意伪造（改 versionCode 就能测"有更新/无更新"两条路径）。
 */
data class OtaQuery(
    val currentVersionCode: Int,
    /** 发布通道：stable / beta。灰度由服务端按 channel + deviceId 自己决定 */
    val channel: String,
    val abi: String,
    /** 设备标识（ANDROID_ID）。只用于灰度与统计，不含任何隐私数据 */
    val deviceId: String,
    /** 上次响应的 ETag；服务端支持 304 时可省流量 */
    val etag: String? = null
) {

    /** 拼成 query string（服务端就绪后 RemoteOtaProvider 直接用） */
    fun toQueryString(): String = buildList {
        add("app=dgg")
        add("channel=${enc(channel)}")
        add("versionCode=$currentVersionCode")
        add("abi=${enc(abi)}")
        add("deviceId=${enc(deviceId)}")
    }.joinToString("&")

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    companion object {
        const val DEFAULT_CHANNEL = "stable"

        fun forCurrent(context: Context, channel: String = DEFAULT_CHANNEL): OtaQuery {
            val pm = context.packageManager
            val code = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                    ).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0).versionCode
                }
            }.getOrDefault(0)

            val deviceId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull() ?: "unknown"

            return OtaQuery(
                currentVersionCode = code,
                channel = channel,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "",
                deviceId = deviceId
            )
        }
    }
}

/**
 * 更新源抽象。
 *
 * 与 `LicenseProvider` 同一套思路：**接口在客户端，实现可替换**。
 * - [LocalOtaProvider]：本期默认实现，读 `files/ota_manifest.json`，不需要任何服务端；
 * - RemoteOtaProvider（P4 待写）：HTTPS GET 上面的 query string，解析同一份 JSON。
 *
 * 换实现只需改 [OtaManager.installProvider] 一行，下载/校验/安装/UI 全都不用动。
 */
interface OtaProvider {

    /**
     * 查询是否有新版本。
     *
     * @return `Result.success(null)` = 确认没有更新（含服务端 304）；
     *         `Result.failure` = 查不到（网络异常/服务端错误），调用方应静默忽略、下次再查。
     */
    suspend fun latest(query: OtaQuery): Result<OtaManifest?>
}
