package com.doujiajia.app.data.ota

import android.content.Context
import com.doujiajia.app.data.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 本地占位实现 —— **不发任何网络请求，也不假装查过服务端**。
 *
 * 读 `files/ota_manifest.json`（格式与服务端响应完全一致，见 `docs/OTA-升级方案.md` §4.2），
 * 这样在云端没做之前就能把「检查 → 下载 → 校验」整条链路跑通；
 * 服务端就绪后只需在 [OtaManager.installProvider] 换成 RemoteOtaProvider，UI 与状态机零改动。
 *
 * 写入方式（调试用，两个都行）：
 * ```
 * adb shell "run-as com.doujiajia.app sh -c 'echo {\"versionCode\":2,...} > files/ota_manifest.json'"
 * adb push manifest.json /data/local/tmp/ && adb shell run-as ... cp /data/local/tmp/manifest.json files/
 * ```
 * 也可以走调试通道的 `OTAJSON <json>` 命令（见 DebugChannel），它会直接覆盖这个文件。
 */
class LocalOtaProvider(context: Context) : OtaProvider {

    private val manifestFile = File(context.filesDir, FILE_NAME)

    override suspend fun latest(query: OtaQuery): Result<OtaManifest?> = withContext(Dispatchers.IO) {
        if (!manifestFile.isFile) {
            LogStore.i(
                TAG,
                "本地占位：没有 ${manifestFile.absolutePath}，视为「无更新」。" +
                    "要测更新流程请先写入该文件（或用调试通道 OTAJSON 命令）"
            )
            return@withContext Result.success(null)
        }

        runCatching {
            val text = manifestFile.readText()
            val manifest = OtaManifest.parse(JSONObject(text))
                ?: return@runCatching null
            if (manifest.versionCode <= query.currentVersionCode) {
                LogStore.i(
                    TAG,
                    "本地占位：manifest 版本 ${manifest.versionName}(${manifest.versionCode}) " +
                        "不高于当前 ${query.currentVersionCode}，视为无更新"
                )
                return@runCatching null
            }
            LogStore.i(
                TAG,
                "本地占位：发现新版本 ${manifest.versionName}(${manifest.versionCode})，" +
                    "sha256=${manifest.sha256.take(12)}… size=${manifest.size}"
            )
            manifest
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                LogStore.e(TAG, "本地 manifest 解析失败：${it.message}")
                Result.failure(it)
            }
        )
    }

    companion object {
        private const val TAG = "OTA"
        const val FILE_NAME = "ota_manifest.json"
    }
}
