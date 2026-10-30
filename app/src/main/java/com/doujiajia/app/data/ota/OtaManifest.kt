package com.doujiajia.app.data.ota

import com.doujiajia.app.BuildConfig
import com.doujiajia.app.data.LogStore
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
    /** 安装包直链：https / 回环 http / 与自己 API 同源的 http（见 [OtaUrls]） */
    val url: String,
    /** 64 位小写十六进制，下载后逐字节校验 */
    val sha256: String,
    /** 字节数，仅用于进度显示，**不参与校验**（服务端写错也不会因此装坏包） */
    val size: Long = 0,
    val changelog: String = "",
    val channel: String = "",
    val publishedAt: Long = 0
) {
    /**
     * 序列化回契约形状。
     *
     * 唯一用途是**持久化「已下载待安装」这个状态**：进程重启后要能恢复出「安装 v1.0.1」这个入口，
     * 而 `OtaState.Ready` 需要完整 manifest。存 json 比在 SharedPreferences 里散着存五个字段更好维护，
     * 也保证解析/序列化用的是同一套字段名（见 [parse] 的白名单）。
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("url", url)
        put("sha256", sha256)
        put("size", size)
        put("changelog", changelog)
        put("channel", channel)
        put("publishedAt", publishedAt)
    }

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
                LogStore.w(TAG, "manifest 不可用：url 既不是 https、也不是回环或同源 http（$url）")
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
 * 允许三类：
 * 1. `https://` 任意地址；
 * 2. 回环地址 `http://127.0.0.1:…`、`http://localhost:…`：本地联调时用
 *    `python -m http.server` + `adb reverse` 把整条链路跑通，不必等云端；
 * 3. **与 `BuildConfig.API_BASE_URL` 同源**的 `http://` 地址。
 *
 * 第 3 条是 2026-10-29 加的：测试服只有 3101 明文端口（没有 80/443、没装 nginx），
 * 而 OTA 下发的直链就是 `http://8.163.92.58:3101/api/v1/ota/download/…`。
 * 只认 https 的结果是**明文环境下永远查不到更新**——入口点了也只会说"无法连接"。
 *
 * 安全性没有实质下降：只放行**自己的服务器**（同 scheme+host+port），
 * 且装包前仍有 `OtaVerifier` 的四道校验（包名 / 与已装应用逐字节一致的签名 /
 * sha256 / versionCode 递增）兜底，下载到被改过的包也装不上。
 */
internal object OtaUrls {

    fun isAllowed(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (lower.startsWith("https://")) return true
        if (lower.startsWith("http://")) return isLoopback(lower) || isApiOrigin(lower)
        return false
    }

    private fun isLoopback(url: String): Boolean =
        url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")

    /**
     * 是否与 API 基址同源（scheme + host + port）。
     *
     * 用「同源前缀 + 边界字符」而不是 `contains`：`http://8.163.92.58:3101` 不能匹配
     * `http://8.163.92.58:31011/x`（端口被前缀吞掉会放行一个完全不同的服务）。
     */
    private fun isApiOrigin(url: String): Boolean {
        val origin = apiOrigin() ?: return false
        if (!url.startsWith(origin)) return false
        if (url.length == origin.length) return true
        val next = url[origin.length]
        return next == '/' || next == '?'
    }

    private fun apiOrigin(): String? {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        if (!base.lowercase().startsWith("http://")) return null
        val schemeEnd = base.indexOf("://") + 3
        if (schemeEnd < 3) return null
        val slash = base.indexOf('/', schemeEnd)
        return (if (slash < 0) base else base.substring(0, slash)).lowercase()
    }
}
