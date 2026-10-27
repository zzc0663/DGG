package com.example.dgg.data

import android.content.Context
import com.example.dgg.util.Texts
import org.json.JSONObject
import java.io.File

/**
 * 直播互动「预设评论」话术库。
 *
 * 两级来源，与定位配置同一套约定：
 * 1. `<app>/files/live_comments.json` 优先——运营想换话术时投一个文件即可，不用重新打包；
 * 2. 回退到 assets 的默认话术。
 *
 * 解析失败一律降级为空列表并记录原因，绝不抛异常：话术缺失只是「这一种模式用不了」，
 * 不应该让整个流程在启动瞬间崩掉。真正需要话术时由 Flow 给出可读的中止原因。
 */
object PresetComments {

    private const val TAG = "预设评论"
    private const val ASSET_NAME = "live_comments.json"

    @Volatile
    private var cached: List<String>? = null

    /** @return 去空、去重后的可用话术；为空表示两种来源都没读到内容 */
    fun load(context: Context, forceReload: Boolean = false): List<String> {
        if (!forceReload) cached?.let { return it }

        var source = "assets"
        var list: List<String>? = null

        val external = runCatching { File(context.filesDir, ASSET_NAME) }.getOrNull()
        if (external != null && external.isFile) {
            list = runCatching { parse(external.readText()) }
                .onFailure { LogStore.w(TAG, "外部话术解析失败，回退 assets：${it.message}") }
                .getOrNull()
            if (list != null) source = "外部文件"
        }

        if (list == null) {
            list = runCatching {
                val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                parse(text)
            }.onFailure {
                LogStore.e(TAG, "加载 $ASSET_NAME 失败：${it.message}")
            }.getOrElse { emptyList() }
        }

        val resolved = list
        cached = resolved
        LogStore.i(TAG, "已加载 ${resolved.size} 条预设评论（来源：$source）")
        return resolved
    }

    private fun parse(text: String): List<String> {
        val array = JSONObject(text).optJSONArray("comments") ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optString(it) }
            .map { Texts.sanitize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
