package com.example.dgg.data.ota

import android.content.Context

/**
 * OTA 的本地状态（跨进程重启要保留的那点东西）。
 *
 * 只存**策略性**数据，不存下载进度——进度靠 `.part` 文件本身的长度，重启后天然可续，
 * 不需要额外维护一份"下到第几字节"，那样反而会出现文件和记录不一致的脏状态。
 */
class OtaStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 上次检查更新的时刻（毫秒）。用于"6 小时内不重复查" */
    var lastCheckAt: Long
        get() = sp.getLong(KEY_LAST_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** 服务端返回的 ETag，下次带 If-None-Match */
    var etag: String?
        get() = sp.getString(KEY_ETAG, null)
        set(value) = sp.edit().putString(KEY_ETAG, value).apply()

    /** 用户点过「忽略此版本」的 versionCode；更高的版本仍然会提示 */
    var ignoredVersionCode: Int
        get() = sp.getInt(KEY_IGNORED, 0)
        set(value) = sp.edit().putInt(KEY_IGNORED, value).apply()

    /** 已经下载并校验通过、等着安装的包（路径 + 版本号） */
    var readyPath: String?
        get() = sp.getString(KEY_READY_PATH, null)
        set(value) = sp.edit().putString(KEY_READY_PATH, value).apply()

    var readyVersionCode: Int
        get() = sp.getInt(KEY_READY_VERSION, 0)
        set(value) = sp.edit().putInt(KEY_READY_VERSION, value).apply()

    /**
     * 就绪包对应的完整 manifest（json 字符串）。
     *
     * 为什么连它一起存：进程被杀后在冷启动要能重建 `OtaState.Ready`（界面显示「安装 vX」），
     * 而 `Ready` 需要 versionName/changelog 这些字段；只存路径 + versionCode 恢复不出完整界面。
     */
    var readyManifestJson: String?
        get() = sp.getString(KEY_READY_MANIFEST, null)
        set(value) = sp.edit().putString(KEY_READY_MANIFEST, value).apply()

    fun clearReady() {
        sp.edit()
            .remove(KEY_READY_PATH)
            .remove(KEY_READY_VERSION)
            .remove(KEY_READY_MANIFEST)
            .apply()
    }

    companion object {
        private const val SP_NAME = "dgg_ota"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_ETAG = "etag"
        private const val KEY_IGNORED = "ignored_version_code"
        private const val KEY_READY_PATH = "ready_path"
        private const val KEY_READY_VERSION = "ready_version_code"
        private const val KEY_READY_MANIFEST = "ready_manifest_json"

        @Volatile
        private var instance: OtaStore? = null

        fun get(context: Context): OtaStore =
            instance ?: synchronized(this) {
                instance ?: OtaStore(context.applicationContext).also { instance = it }
            }
    }
}
