package com.doujiajia.app.data.license

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.doujiajia.app.BuildConfig
import com.doujiajia.app.data.LogStore
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

    private const val TAG = "设备身份"

    private const val PREFS = "dgg_device"
    private const val KEY_UUID = "install_uuid"

    /**
     * 上一次算出的 deviceId 前 8 位。
     *
     * 只作**诊断**用途：`deviceId = sha256(ANDROID_ID:installUuid:包名)`，其中 installUuid
     * 是首次启动随机生成的，一旦重装/清数据就会变，服务端随即把它当成一台新设备。
     * `max_devices` 默认是 1，所以「重装 ⇒ 设备数已满 ⇒ 授权失效」全靠这个字段在日志里暴露出来。
     */
    private const val KEY_LAST_DEVICE_ID = "last_device_id"

    fun payload(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var installUuid = prefs.getString(KEY_UUID, null)
        if (installUuid.isNullOrBlank()) {
            installUuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, installUuid).apply()
            LogStore.i(TAG, "首次生成本机 installUuid（重装或清数据会重新生成，deviceId 随之变化）")
        }
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "unknown"
        val raw = "$androidId:$installUuid:${context.packageName}"
        val id = sha256(raw)
        // 漂移检测：发生变化基本就等价于「服务端眼里这是一台新设备」
        val last = prefs.getString(KEY_LAST_DEVICE_ID, null)
        if (last == null) {
            prefs.edit().putString(KEY_LAST_DEVICE_ID, id).apply()
            LogStore.i(TAG, "deviceId 初始化 ${id.take(8)}…")
        } else if (last != id) {
            LogStore.w(
                TAG,
                "deviceId 已变化 ${last.take(8)}… → ${id.take(8)}…" +
                    "（服务端会视为新设备；若授权设备数已满，激活会被拒）"
            )
            prefs.edit().putString(KEY_LAST_DEVICE_ID, id).apply()
        }
        return JSONObject().apply {
            put("device_id", id)
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
