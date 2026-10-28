package com.example.dgg.data.ota

import android.content.Context
import com.example.dgg.BuildConfig
import com.example.dgg.data.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * 正式 OTA 更新源。
 *
 * 契约沿用 docs/OTA-升级方案.md：
 * GET {base}/v1/ota/latest?app=dgg&channel=stable&versionCode=1&abi=...&deviceId=...
 * 返回 {code:0,data:{...}} 或 {code:0,data:null}。
 *
 * 不在客户端校验服务端签名；下载完成后由 OtaVerifier 用
 * 「包名 + 与已安装应用一致的签名 + sha256 + versionCode 递增」做最终校验。
 */
class RemoteOtaProvider(context: Context) : OtaProvider {

    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    override suspend fun latest(query: OtaQuery): Result<OtaManifest?> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildString {
                append(base)
                append("/v1/ota/latest?app=dgg")
                append("&channel=").append(enc(query.channel))
                append("&versionCode=").append(query.currentVersionCode)
                append("&abi=").append(enc(query.abi))
                append("&deviceId=").append(enc(query.deviceId))
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                query.etag?.let { setRequestProperty("If-None-Match", it) }
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return@runCatching null
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(text)
                val data = json.optJSONObject("data") ?: return@runCatching null
                val manifest = OtaManifest.parse(data) ?: return@runCatching null
                if (manifest.versionCode <= query.currentVersionCode) null else manifest
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            LogStore.w("OTA", "远程检查失败：${it.javaClass.simpleName} / ${it.message}")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
