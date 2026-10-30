package com.doujiajia.app.data.license

import android.content.Context
import android.util.Log
import com.doujiajia.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 正式授权实现：激活 + 启动握手 + 自助解绑。
 *
 * 网络协议见 server/docs/03-技术方案.md；App 只保存服务端签发的设备令牌，
 * 不保存卡密明文。令牌由 TokenStore 用 Keystore 加密。
 */
class RemoteLicenseProvider(context: Context) : LicenseProvider {

    private val appContext = context.applicationContext
    private val store = TokenStore(appContext)
    private val api = LicenseApi()

    /** 上一次真的发过握手请求的时刻（成功失败都算），用于失败节流 */
    @Volatile
    private var lastAttemptAt = 0L

    private val _state = MutableStateFlow(
        LicenseStatus(
            authorized = false,
            expireAt = null,
            code = null,
            source = SOURCE,
            message = "正在检查授权…"
        )
    )
    override val state: StateFlow<LicenseStatus> = _state.asStateFlow()

    override suspend fun initialize() {
        val saved = store.load()
        if (saved == null) {
            _state.value = unactivated()
            return
        }
        _state.value = statusFrom(saved, offline = false, message = "正在与服务器握手…")
        handshake()
    }

    override suspend fun activate(code: String): Result<LicenseStatus> = withContext(Dispatchers.IO) {
        val normalized = code.trim()
        if (normalized.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("请输入授权密钥"))
        }
        runCatching {
            val body = JSONObject()
                .put("license_key", normalized)
                .put("device", DeviceIdentity.payload(appContext))
            val data = api.post("/api/v1/app/activate", body, null)
            persist(data)
        }.fold(
            onSuccess = {
                val state = statusFrom(store.load(), offline = false, message = "已授权")
                _state.value = state
                Log.i(TAG, "激活成功：${state.code}")
                Result.success(state)
            },
            onFailure = { error ->
                val message = userMessage(error)
                _state.value = LicenseStatus(
                    authorized = false, expireAt = null, code = null,
                    source = SOURCE, message = message
                )
                Log.w(TAG, "激活失败：${error.javaClass.simpleName} / $message")
                Result.failure(IllegalStateException(message, error))
            }
        )
    }

    override suspend fun handshake(): Result<LicenseStatus> = withContext(Dispatchers.IO) {
        val saved = store.load()
        if (saved == null) {
            _state.value = unactivated()
            return@withContext Result.failure(IllegalStateException("设备尚未激活"))
        }
        val token = saved.optString("token")
        if (token.isBlank()) {
            store.clear()
            _state.value = unactivated()
            return@withContext Result.failure(IllegalStateException("设备令牌为空，请重新激活"))
        }
        runCatching {
            val body = JSONObject()
                .put("device_id", DeviceIdentity.deviceId(appContext))
                .put("app_version_code", BuildConfig.VERSION_CODE)
                .put("app_version_name", BuildConfig.VERSION_NAME)
            val data = api.post("/api/v1/app/handshake", body, token)
            persist(data)
        }.fold(
            onSuccess = {
                val state = statusFrom(store.load(), offline = false, message = "授权有效")
                _state.value = state
                Log.i(TAG, "握手成功，到期 ${state.expireAt}")
                Result.success(state)
            },
            onFailure = { error ->
                if (error is ApiException && error.httpStatus in 400..499) {
                    if (error.code in CLEAR_TOKEN_CODES) store.clear()
                    val state = LicenseStatus(
                        authorized = false, expireAt = null, code = null,
                        source = SOURCE, message = userMessage(error)
                    )
                    _state.value = state
                    Log.w(TAG, "握手被拒绝：${error.code}")
                    Result.failure(IllegalStateException(state.message, error))
                } else {
                    // 网络/服务端故障：按本地令牌的离线截止时间决定是否宽限。
                    val now = System.currentTimeMillis()
                    val offlineUntil = saved.optLong("offlineUntil", 0L)
                    val expiresAt = saved.optLong("expiresAt", 0L)
                    val allowedUntil = minOf(
                        if (offlineUntil > 0) offlineUntil else Long.MAX_VALUE,
                        if (expiresAt > 0) expiresAt else Long.MAX_VALUE
                    )
                    if (allowedUntil > now) {
                        val state = statusFrom(saved, offline = true, message = "离线模式，联网后自动同步授权")
                        _state.value = state
                        Log.w(TAG, "网络失败，进入离线宽限：${error.message}")
                        Result.success(state)
                    } else {
                        val state = LicenseStatus(
                            authorized = false, expireAt = expiresAt.takeIf { it > 0 }, code = null,
                            source = SOURCE, message = "离线授权已到期，请联网后重试"
                        )
                        _state.value = state
                        Log.w(TAG, "网络失败且离线宽限已过")
                        Result.failure(IllegalStateException(state.message, error))
                    }
                }
            }
        )
    }

    /**
     * 距上次成功握手超过 [maxAgeMs] 才真的握手。
     *
     * 两层节流，缺一不可：
     * ① [maxAgeMs]（调用方给 6 小时）——业务语义上「多久算不新鲜」；
     * ② [MIN_ATTEMPT_INTERVAL_MS]（30 分钟）——**失败也要节流**。`lastHandshakeAt` 只在
     *    persist 成功时才更新，如果只按它判断，网络一直不通就会每次 onResume 都发一次请求，
     *    在地铁里频繁切前后台会打出一串无意义的重试。
     */
    override suspend fun refreshIfStale(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < MIN_ATTEMPT_INTERVAL_MS) return
        val saved = store.load() ?: return
        if (!_state.value.authorized) return
        val last = saved.optLong("lastHandshakeAt", 0L)
        if (last > 0 && now - last < maxAgeMs) return
        lastAttemptAt = now
        Log.i(TAG, "距上次握手 $((now - last) / 60_000) 分钟，重握手一次")
        handshake()
    }

    override suspend fun unbind(): Result<Unit> = withContext(Dispatchers.IO) {
        val saved = store.load()
        val token = saved?.optString("token").orEmpty()
        if (token.isBlank()) {
            store.clear()
            _state.value = unactivated()
            return@withContext Result.success(Unit)
        }
        runCatching {
            api.post("/api/v1/app/unbind", JSONObject(), token)
        }.fold(
            onSuccess = {
                store.clear()
                _state.value = unactivated()
                Log.i(TAG, "设备已解绑")
                Result.success(Unit)
            },
            onFailure = { error ->
                Log.w(TAG, "解绑失败：${error.message}")
                Result.failure(IllegalStateException(userMessage(error), error))
            }
        )
    }

    private fun persist(data: JSONObject) {
        val license = data.getJSONObject("license")
        val expiresAt = parseIso(license.optString("expires_at"))
        val offlineUntil = parseIso(data.optString("offline_until"))
        val masked = license.optString("masked_key")
        val payload = JSONObject()
            .put("token", data.getString("token"))
            .put("expiresAt", expiresAt)
            .put("offlineUntil", offlineUntil)
            .put("remainingDays", if (license.has("remaining_days")) license.optLong("remaining_days") else JSONObject.NULL)
            .put("maskedKey", masked)
            .put("lastHandshakeAt", System.currentTimeMillis())
        store.save(payload)
    }

    private fun statusFrom(saved: JSONObject?, offline: Boolean, message: String): LicenseStatus {
        if (saved == null) return unactivated()
        val expiresAt = saved.optLong("expiresAt", 0L).takeIf { it > 0 }
        val remaining = if (saved.has("remainingDays") && !saved.isNull("remainingDays"))
            saved.optLong("remainingDays") else null
        return LicenseStatus(
            authorized = true,
            expireAt = expiresAt,
            code = saved.optString("maskedKey").takeIf { it.isNotBlank() },
            source = SOURCE,
            offline = offline,
            remainingDays = remaining,
            message = message
        )
    }

    private fun unactivated() = LicenseStatus(
        authorized = false, expireAt = null, code = null, source = SOURCE,
        message = "未激活，请输入授权密钥"
    )

    private fun userMessage(error: Throwable): String {
        if (error is ApiException) {
            return when (error.code) {
                "LICENSE_INVALID" -> "授权密钥无效，请检查是否输入完整"
                "LICENSE_EXPIRED" -> "授权已到期，请联系管理员续期"
                "LICENSE_REVOKED" -> "授权已停用，请联系管理员"
                "LICENSE_SUSPENDED" -> "授权已暂停，请联系管理员"
                "DEVICE_LIMIT_REACHED" -> "设备数已满，请先解绑旧设备或联系管理员"
                "DEVICE_BLOCKED" -> "设备已被封禁，请联系管理员"
                "DEVICE_UNBOUND" -> "当前设备已解绑，请重新输入密钥"
                "TOKEN_INVALID", "TOKEN_EXPIRED" -> "授权凭证已失效，请重新输入密钥"
                "RATE_LIMITED" -> "操作过于频繁，请稍后再试"
                "MAINTENANCE" -> "服务器维护中，请稍后再试"
                else -> error.message.orEmpty().ifBlank { "请求失败，请稍后重试" }
            }
        }
        val msg = error.message.orEmpty()
        return if (error is IOException || msg.contains("Failed to connect", true) ||
            msg.contains("timeout", true) || msg.contains("Unable to resolve", true)
        ) "无法连接服务器，请检查网络后重试" else msg.ifBlank { "请求失败，请稍后重试" }
    }

    private fun parseIso(value: String): Long {
        if (value.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    private class LicenseApi {
        private val base = BuildConfig.API_BASE_URL.trimEnd('/')

        fun post(path: String, body: JSONObject, token: String?): JSONObject {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-App-Id", "doujiajia")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse {
                    throw IOException("服务器返回了无法解析的内容（HTTP $code）")
                }
                if (code !in 200..299) {
                    val error = json.optJSONObject("error")
                    throw ApiException(
                        code = error?.optString("code").orEmpty().ifBlank { "HTTP_$code" },
                        message = error?.optString("message").orEmpty().ifBlank { "请求失败（HTTP $code）" },
                        httpStatus = code
                    )
                }
                return json.optJSONObject("data")
                    ?: throw IOException("服务器响应缺少 data")
            } finally {
                conn.disconnect()
            }
        }
    }

    private class ApiException(
        val code: String,
        message: String,
        val httpStatus: Int
    ) : IOException(message)

    companion object {
        private const val TAG = "RemoteLicense"

        /** 两次握手尝试之间的最小间隔（失败也计入），避免网络差时频繁重试 */
        private const val MIN_ATTEMPT_INTERVAL_MS = 30 * 60 * 1000L
        private const val SOURCE = "REMOTE"
        private val CLEAR_TOKEN_CODES = setOf(
            "TOKEN_INVALID", "TOKEN_EXPIRED", "DEVICE_UNBOUND", "DEVICE_BLOCKED",
            "LICENSE_REVOKED", "LICENSE_INVALID"
        )
    }
}
