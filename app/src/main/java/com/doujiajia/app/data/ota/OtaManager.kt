package com.doujiajia.app.data.ota

import android.content.Context
import com.doujiajia.app.data.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** OTA 状态。单一数据源，界面/通知/日志都读它（与 `TaskEngine.progress` 同构）。 */
sealed class OtaState {
    /** 还没查过，或已经回到平静状态（无更新 / 用户忽略） */
    object Idle : OtaState()
    object Checking : OtaState()
    data class UpToDate(val versionCode: Int) : OtaState()
    data class Available(val manifest: OtaManifest) : OtaState()
    data class Downloading(val downloaded: Long, val total: Long) : OtaState()
    object Verifying : OtaState()

    /** 已下载且校验通过，等用户点安装（P2 才接安装器） */
    data class Ready(val file: File, val manifest: OtaManifest) : OtaState()
    data class Failed(val message: String) : OtaState()
}

/**
 * OTA 编排器。
 *
 * 职责边界刻意收得很窄：
 * - 只做「查 → 下 → 校验 → 交给安装器」，**不碰 UI、不碰通知、不碰任务引擎**；
 * - 服务端实现可替换（[installProvider]），本地占位与云端实现共用这一套状态机。
 *
 * 这样 P2 接 UI 时只需要 `collect(OtaManager.state)` + 两个按钮，不会再动这里。
 */
object OtaManager {

    private const val TAG = "OTA"

    /** 自动检查的最小间隔：6 小时内不重复查（手动检查不受限制） */
    const val MIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    /** 下载与检查串行化：避免用户连点两次「立即更新」下出两份包 */
    private val lock = Mutex()

    @Volatile
    private var provider: OtaProvider? = null

    /**
     * 替换更新源。服务端就绪后调用这个换掉本地占位实现，其余代码零改动。
     * 传 null 表示回到默认的 [LocalOtaProvider]。
     */
    fun installProvider(newProvider: OtaProvider?) {
        provider = newProvider
        LogStore.i(TAG, "更新源已切换为 ${newProvider?.javaClass?.simpleName ?: "LocalOtaProvider(默认)"}")
    }

    private fun providerOf(context: Context): OtaProvider =
        provider ?: LocalOtaProvider(context.applicationContext).also { provider = it }

    /**
     * 检查更新。
     *
     * @param manual 用户主动点「升级」= true（不受 6 小时间隔限制、不看"忽略此版本"）
     * @return 有可用更新时返回 manifest，否则 null（无更新 / 被忽略 / 查不到都返回 null）
     */
    suspend fun check(context: Context, manual: Boolean): OtaManifest? = lock.withLock {
        val store = OtaStore.get(context)
        val current = OtaVerifier.currentVersionCode(context)

        if (!manual) {
            val since = System.currentTimeMillis() - store.lastCheckAt
            if (since in 0 until MIN_CHECK_INTERVAL_MS) {
                LogStore.d(TAG, "距上次检查 ${since / 60_000} 分钟，未到 ${MIN_CHECK_INTERVAL_MS / 60_000} 分钟，跳过")
                return null
            }
        }

        _state.value = OtaState.Checking
        val query = OtaQuery.forCurrent(context)
        LogStore.i(TAG, "检查更新：当前 ${current}（${query.channel}/${query.abi}）")

        // 读不到自己的 versionCode 是异常状态（自家应用一定能读到），**不能当成「版本 0」继续**：
        // 那样会「永远有新版本」→ 下载 4.6 MB → 校验第 4 道（严格递增）在 0 上恒成立而形同失效。
        // 2026-10-30 就是这么在 API 28~32 的设备上连试 5 轮全失败（日志 R-20261030-E82372）。
        if (current <= 0) {
            LogStore.e(TAG, "读不到当前 versionCode（$current），中止检查更新以免无意义下载")
            _state.value = OtaState.Idle
            return null
        }

        val result = providerOf(context).latest(query)
        store.lastCheckAt = System.currentTimeMillis()

        val manifest = result.getOrElse { e ->
            // 查不到不算错误：网络抖动、服务端维护都很正常，静默等下次
            LogStore.w(TAG, "检查更新失败（忽略）：${e.message}")
            _state.value = OtaState.Idle
            return null
        }

        if (manifest == null) {
            LogStore.i(TAG, "已是最新版本（$current）")
            _state.value = OtaState.UpToDate(current)
            return null
        }

        if (!manual && store.ignoredVersionCode >= manifest.versionCode) {
            LogStore.i(TAG, "该版本 ${manifest.versionCode} 已被用户忽略，不提示")
            _state.value = OtaState.UpToDate(current)
            return null
        }

        _state.value = OtaState.Available(manifest)
        LogStore.i(TAG, "发现新版本 ${manifest.versionName}(${manifest.versionCode})，当前 $current")
        return manifest
    }

    /**
     * 下载并校验。
     *
     * 成功后文件已通过 [OtaVerifier] 全部四道校验，但**还没有安装**——
     * 安装必须由用户点系统安装器确认（见 docs/OTA-升级方案.md §5）。
     */
    suspend fun downloadAndVerify(context: Context, manifest: OtaManifest): Result<File> =
        lock.withLock {
            val dir = File(context.cacheDir, DIR_NAME)
            val fileName = "dgg-${manifest.versionCode}.apk"
            _state.value = OtaState.Downloading(0, manifest.size)

            val downloader = OtaDownloader(dir)
            val file = downloader.download(manifest.url, manifest.sha256, fileName) { done, total ->
                _state.value = OtaState.Downloading(done, total)
            }.getOrElse { e ->
                LogStore.e(TAG, "下载失败：${e.message}")
                _state.value = OtaState.Failed(e.message ?: "下载失败")
                return Result.failure(e)
            }

            _state.value = OtaState.Verifying
            when (val verify = OtaVerifier.verify(context, file, OtaVerifier.currentVersionCode(context))) {
                is VerifyResult.Ok -> {
                    OtaStore.get(context).apply {
                        readyPath = file.absolutePath
                        readyVersionCode = manifest.versionCode
                        readyManifestJson = manifest.toJson().toString()
                    }
                    _state.value = OtaState.Ready(file, manifest)
                    LogStore.i(TAG, "安装包就绪：${manifest.versionName}(${verify.versionCode})，等待用户安装")
                    Result.success(file)
                }

                is VerifyResult.Rejected -> {
                    // 校验不过的包立刻删掉：既省空间，也避免被误当成"已下载完成"
                    file.delete()
                    OtaStore.get(context).clearReady()
                    _state.value = OtaState.Failed(verify.reason)
                    LogStore.e(TAG, "校验拒绝：${verify.reason}")
                    Result.failure(IllegalStateException(verify.reason))
                }
            }
        }

    /** 用户点了「忽略此版本」：持久化到该版本为止，更高的版本仍会提示 */
    fun ignoreVersion(context: Context, versionCode: Int) {
        LogStore.i(TAG, "用户忽略版本 $versionCode")
        OtaStore.get(context).ignoredVersionCode = versionCode
        _state.value = OtaState.Idle
    }

    /**
     * 冷启动恢复「已经下载好、等着安装」的状态。
     *
     * 为什么需要：下载在服务里跑完、`OtaState` 只在内存里；进程一被杀，入口就没了，
     * 用户会在 `cache/ota` 里留一个 6MB 的包却找不到「安装」按钮。这里把持久化状态还原成
     * `OtaState.Ready`，界面照常渲染。
     *
     * 顺手做两个自清理：文件不在了 / 这个版本已经装上了 → 清掉记录，不留幽灵入口。
     *
     * @return 是否恢复出了 Ready 状态
     */
    fun restoreReady(context: Context): Boolean {
        val store = OtaStore.get(context)
        val path = store.readyPath ?: return false
        val json = store.readyManifestJson ?: return false
        val file = File(path)
        val manifest = runCatching { OtaManifest.parse(JSONObject(json)) }.getOrNull()
        if (manifest == null || !file.isFile || file.length() <= 0L) {
            LogStore.w(TAG, "上次的安装包已不可用（文件缺失或记录损坏），清理")
            store.clearReady()
            return false
        }
        if (manifest.versionCode <= OtaVerifier.currentVersionCode(context)) {
            LogStore.i(TAG, "版本 ${manifest.versionCode} 已安装，清理残留安装包")
            file.delete()
            store.clearReady()
            return false
        }
        _state.value = OtaState.Ready(file, manifest)
        LogStore.i(TAG, "恢复待安装状态：${manifest.versionName}(${manifest.versionCode})")
        return true
    }

    /** 下载目录（相对于 cacheDir）；P2 接安装器时要把它加进 file_paths.xml 才能用 FileProvider 分享 */
    const val DIR_NAME = "ota"
}
