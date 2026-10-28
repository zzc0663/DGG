package com.example.dgg.data.ota

import com.example.dgg.data.LogStore
import org.json.JSONObject

/**
 * 服务端返回的「有新版本」描述。
 *
 * 这个类是**客户端与服务端之间唯一的契约载体**：服务端只认 §`docs/OTA-升级方案.md` 里的字段，
 * 客户端只认这里解析出来的字段。任何一方私自加字段都不会生效（[parse] 只读白名单里的键）。
 *
 * 刻意不含 `force` / `minSupportedVersionCode`：本期不做强制更新（2026-10-28 决定），
 * 契约里不出现这两个字段，避免服务端以为下发就会生效。
 */
data class OtaManifest(
    /** 新版 versionCode，必须 > 当前（等于或小于一律当"无更新"，绝不降级安装） */
    val versionCode: Int,
    val versionName: String,
    /** 安装包直链，必须是 https（仅回环地址允许 http，供本地联调） */
    val url: String,
    /** 64 位小写十六进制，下载后逐字节校验 */
    val sha256: String,
    /** 字节数，仅用于进度显示，**不参与校验**（服务端写错也不会因此装坏包） */
    val size: Long = 0,
    val changelog: String = "",
    val channel: String = "",
    val publishedAt: Long = 0
) {
    companion object {
        private const val TAG = "OTA"

        /** sha256 必须是 64 位十六进制；大写也接受，比较前统一转小写 */
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

        /**
         * 解析一条 manifest。
         *
         * 返回 null 表示"这条描述不可用"，调用方当成"没有更新"处理——**不做任何猜测性兜底**
         * （例如 url 缺失时不要退回默认地址，那会把更新指向不可控的目标）。
         *
         * @param json 既接受裸对象 `{...}`，也接受包了一层的 `{"code":0,"data":{...}}`
         */
        fun parse(json: JSONObject): OtaManifest? {
            val obj = json.optJSONObject("data") ?: json

            val versionCode = obj.optInt("versionCode", 0)
            val versionName = obj.optString("versionName").trim()
            val url = obj.optString("url").trim()
            val sha256 = obj.optString("sha256").trim()

            if (versionCode <= 0) {
                LogStore.w(TAG, "manifest 不可用：versionCode=$versionCode")
                return null
            }
            if (versionName.isEmpty()) {
                LogStore.w(TAG, "manifest 不可用：versionName 为空")
                return null
            }
            if (!OtaUrls.isAllowed(url)) {
                LogStore.w(TAG, "manifest 不可用：url 不是 https 或不可解析（$url）")
                return null
            }
            if (!SHA256_PATTERN.matches(sha256)) {
                LogStore.w(TAG, "manifest 不可用：sha256 不是 64 位十六进制（$sha256）")
                return null
            }

            return OtaManifest(
                versionCode = versionCode,
                versionName = versionName,
                url = url,
                sha256 = sha256.lowercase(),
                size = obj.optLong("size", 0L).coerceAtLeast(0L),
                changelog = obj.optString("changelog").trim(),
                channel = obj.optString("channel").trim(),
                publishedAt = obj.optLong("publishedAt", 0L)
            )
        }
    }
}

/**
 * 下载地址的白名单规则。
 *
 * 只允许 https，**唯一的例外是回环地址**：本地联调时用
 * `python -m http.server` + `adb reverse tcp:8099 tcp:8099`，
 * 手机访问 `http://127.0.0.1:8099/xxx.apk` 就能把整条下载链路跑通，不必等云端。
 *
 * 放行回环不会削弱安全性：回环只能连到设备自己，且装包前还有签名一致性校验兜底。
 */
internal object OtaUrls {

    fun isAllowed(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (lower.startsWith("https://")) return true
        if (lower.startsWith("http://")) return isLoopback(lower)
        return false
    }

    private fun isLoopback(url: String): Boolean =
        url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
}
