package com.doujiajia.app.data.license

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.doujiajia.app.data.LogStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备令牌的加密存储。
 *
 * 令牌等价于“已激活设备”的凭证：拿到它就可以冒充设备握手，因此不能明文放
 * SharedPreferences。这里用 Android Keystore 里的不可导出 AES 密钥做 AES-GCM，
 * 密文/IV 再落 SharedPreferences。
 */
class TokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dgg_license_secure", Context.MODE_PRIVATE)

    fun save(payload: JSONObject) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PAYLOAD, packed).apply()
        }.onFailure {
            Log.w(TAG, "保存设备令牌失败：${it.javaClass.simpleName}")
            clear()
        }
    }

    fun load(): JSONObject? {
        val packed = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return runCatching {
            val parts = packed.split(".")
            if (parts.size != 2) return@runCatching null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(encrypted)
            JSONObject(String(plain, Charsets.UTF_8))
        }.onFailure { error ->
            val name = error.javaClass.simpleName
            val message = error.message.orEmpty()
            LogStore.w(TAG, "读取设备令牌失败：$name / $message")
            // 只有「密文与密钥确实对不上」才销毁；Keystore 的临时故障（节点被系统回收、锁屏态访问）
            // 不代表令牌失效，保留密文让下次启动再试——否则一次偶发失败就要求用户重新激活。
            val fatal = error is javax.crypto.AEADBadTagException ||
                error is java.security.UnrecoverableKeyException ||
                name.contains("KeyPermanentlyInvalidated")
            if (fatal) {
                LogStore.w(TAG, "令牌密文无法恢复（$name），已清除，需要重新激活")
                clear()
            } else {
                LogStore.w(TAG, "保留密文，下次启动重试解密")
            }
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "LicenseStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dgg_license_token_v1"
        private const val KEY_PAYLOAD = "payload"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
