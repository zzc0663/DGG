package com.example.dgg.data.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.dgg.BuildConfig
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * 设备身份：ANDROID_ID + 首启 UUID，哈希后提交。
 *
 * 不采集 IMEI/MAC/序列号；清除数据或重装会生成新的 install UUID，服务端视为新设备，
 * 需要管理员解绑。这是有意的隐私取舍。
 */
object DeviceIdentity {

    private const val PREFS = "dgg_device"
    private const val KEY_UUID = "install_uuid"

    fun payload(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var installUuid = prefs.getString(KEY_UUID, null)
        if (installUuid.isNullOrBlank()) {
            installUuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, installUuid).apply()
        }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "unknown"
        val raw = "$androidId:$installUuid:${context.packageName}"
        return JSONObject().apply {
            put("device_id", sha256(raw))
            put("name", deviceName())
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("os_version", Build.VERSION.RELEASE ?: "")
            put("app_version_code", BuildConfig.VERSION_CODE)
            put("app_version_name", BuildConfig.VERSION_NAME)
        }
    }

    fun deviceId(context: Context): String = payload(context).getString("device_id")

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
        return "$manufacturer ${Build.MODEL}".trim().take(80)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
