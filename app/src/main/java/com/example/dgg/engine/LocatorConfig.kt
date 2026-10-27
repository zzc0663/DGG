package com.example.dgg.engine

import android.content.Context
import com.example.dgg.data.LogStore
import org.json.JSONObject
import java.io.File

/** 一个可定位的目标。IMAGE 类型在 M1 恒不命中（图像通道 M2 接入）。 */
data class Target(
    val kind: Kind,
    val value: String,
    val imageThreshold: Float = 0.82f
) {
    enum class Kind(val jsonName: String) {
        TEXT("text"),
        TEXT_CONTAINS("textContains"),
        DESC("desc"),
        DESC_CONTAINS("descContains"),
        ID("id"),
        IMAGE("image");

        companion object {
            fun fromJson(name: String?): Kind? =
                values().firstOrNull { it.jsonName.equals(name, ignoreCase = true) }
        }
    }

    override fun toString(): String = "${kind.jsonName}:\"$value\""
}

/** 页面判定的一条证据：命中则加 [weight] 分 */
data class Feature(val weight: Int, val target: Target)

/** 一个页面的判定规则：得分 ≥ [threshold] 才认定处于该页面 */
data class PageRule(val name: String, val threshold: Int, val features: List<Feature>) {
    /** 理论满分，用于在加载期校验 threshold 是否可达 */
    val maxScore: Int get() = features.sumOf { it.weight }
}

/**
 * 定位配置：页面规则 + 命名目标。
 *
 * 全部来自 assets 的 JSON，改版时改配置不发版。解析失败一律降级到 [fallback]，
 * 绝不因为配置坏了就让应用崩溃——M1 阶段手上没有真实抖音，配置必然是粗糙的。
 */
data class LocatorConfig(
    val version: Int,
    val pages: Map<String, PageRule>,
    val targets: Map<String, Target>,
    /** 评论采集时要直接丢弃的昵称（自己、主播、角色标签等） */
    val excludeNicknames: Set<String> = DEFAULT_EXCLUDE_NICKNAMES
) {

    fun target(name: String): Target? = targets[name]

    companion object {
        private const val TAG = "定位配置"

        val DEFAULT_EXCLUDE_NICKNAMES: Set<String> = setOf(
            "我", "作者", "主播", "管理员", "房管", "粉丝团"
        )

        fun parse(root: JSONObject): LocatorConfig {
            val version = root.optInt("version", 0)
            val pagesJson = root.optJSONObject("pages")
                ?: throw IllegalArgumentException("缺少 pages 节点")
            val targetsJson = root.optJSONObject("targets") ?: JSONObject()

            val targets = LinkedHashMap<String, Target>()
            targetsJson.keys().forEach { name ->
                // 以 `_` 开头的是给人看的注释字段，值可以是任意类型，必须原样跳过。
                // 曾经这里无条件 getJSONObject()，配置里多写一行注释就抛异常，
                // 整份配置被丢弃、降级成只有 HOME/SEARCH_RESULT 的内置配置——
                // 于是 follow_button 消失，M2 一启动就终止。注释不该有这种杀伤力。
                if (name.startsWith("_")) return@forEach
                val obj = targetsJson.optJSONObject(name)
                if (obj == null) {
                    LogStore.w(TAG, "targets.$name 不是对象，已跳过")
                    return@forEach
                }
                val target = parseTarget(obj)
                if (target == null) {
                    LogStore.w(TAG, "targets.$name 类型非法，已跳过")
                } else {
                    targets[name] = target
                }
            }

            val pages = LinkedHashMap<String, PageRule>()
            pagesJson.keys().forEach { name ->
                if (name.startsWith("_")) return@forEach
                val obj = pagesJson.optJSONObject(name)
                if (obj == null) {
                    LogStore.w(TAG, "pages.$name 不是对象，已跳过")
                    return@forEach
                }
                val featuresJson = obj.optJSONArray("features")
                if (featuresJson == null || featuresJson.length() == 0) {
                    LogStore.w(TAG, "pages.$name 无 features，已跳过")
                    return@forEach
                }

                val features = (0 until featuresJson.length()).mapNotNull { i ->
                    val featureJson = featuresJson.optJSONObject(i) ?: return@mapNotNull null
                    val target = parseTarget(featureJson) ?: return@mapNotNull null
                    Feature(featureJson.optInt("w", 1), target)
                }
                if (features.isEmpty()) {
                    LogStore.w(TAG, "pages.$name 的 features 全部非法，已跳过")
                    return@forEach
                }

                val threshold = obj.optInt("threshold", -1)
                val rule = PageRule(name, threshold, features)
                when {
                    threshold <= 0 -> LogStore.w(TAG, "pages.$name 缺少合法 threshold，已跳过")
                    rule.maxScore < threshold -> LogStore.w(
                        TAG,
                        "pages.$name 阈值 $threshold 超过满分 ${rule.maxScore}，永远无法命中，已跳过"
                    )

                    else -> pages[name] = rule
                }
            }

            if (pages.isEmpty()) throw IllegalArgumentException("有效页面规则为 0")

            val exclude = root.optJSONArray("excludeNicknames")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .toSet()
            }?.takeIf { it.isNotEmpty() } ?: DEFAULT_EXCLUDE_NICKNAMES

            return LocatorConfig(version, pages, targets, exclude)
        }

        private fun parseTarget(obj: JSONObject): Target? {
            val kind = Target.Kind.fromJson(obj.optString("type")) ?: return null
            val value = obj.optString("value")
            if (value.isBlank()) return null
            val threshold = obj.optDouble("threshold", 0.82).toFloat()
            return Target(kind, value, threshold)
        }

        /**
         * 内置降级配置：只保留最基本的两条规则。
         * 它的作用是「配置全坏时任务仍能识别出自己在哪，并优雅地报 UNKNOWN」，
         * 而不是「替用户猜抖音界面」。
         */
        fun fallback(): LocatorConfig = LocatorConfig(
            version = -1,
            pages = mapOf(
                "HOME" to PageRule(
                    "HOME", threshold = 2,
                    features = listOf(Feature(2, Target(Target.Kind.TEXT, "首页")))
                ),
                "SEARCH_RESULT" to PageRule(
                    "SEARCH_RESULT", threshold = 3,
                    features = listOf(Feature(3, Target(Target.Kind.TEXT, "用户")))
                )
            ),
            targets = mapOf(
                "search_entry" to Target(Target.Kind.DESC_CONTAINS, "搜索"),
                "user_tab" to Target(Target.Kind.TEXT, "用户")
            )
        )
    }
}

/** 配置加载器：带内存缓存，失败降级并明确记录来源 */
object LocatorConfigLoader {

    private const val TAG = "定位配置"
    private const val ASSET_NAME = "douyin_locator.json"

    @Volatile
    private var cached: LocatorConfig? = null

    fun load(context: Context, forceReload: Boolean = false): LocatorConfig {
        if (!forceReload) cached?.let { return it }

        var degraded = false
        var source = "assets"

        // 外部文件优先：真机校准定位规则时不必重新打包，覆盖一份 JSON 即可。
        // 路径 <app>/files/douyin_locator.json —— 刻意用**内部**存储：
        // /sdcard/Android/data/ 在 Android 11+ 连 run-as 都进不去，adb 没法投递文件。
        // 解析失败会自动回退 assets，不会因为一份写坏的配置让任务彻底跑不起来。
        val external = runCatching { File(context.filesDir, ASSET_NAME) }.getOrNull()

        var config: LocatorConfig? = null
        if (external != null && external.isFile) {
            config = runCatching {
                LocatorConfig.parse(JSONObject(external.readText()))
            }.onFailure {
                LogStore.w(TAG, "外部配置解析失败，回退 assets：${it.message}")
            }.getOrNull()
            if (config != null) source = "外部文件"
        }

        if (config == null) {
            config = runCatching {
                val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                LocatorConfig.parse(JSONObject(text))
            }.onFailure {
                degraded = true
                LogStore.e(TAG, "加载 $ASSET_NAME 失败，降级为内置配置：${it.message}")
            }.getOrElse { LocatorConfig.fallback() }
        }

        // 用 val 承接，避免可空 var 在后续使用处丢失智能转换
        val resolved = config

        cached = resolved
        LogStore.i(
            TAG,
            buildString {
                append("已加载 v${resolved.version}（来源：$source）")
                append("：${resolved.pages.size} 个页面规则（${resolved.pages.keys.joinToString("/")}）")
                append("，${resolved.targets.size} 个命名目标")
                if (degraded) append(" [降级]")
            }
        )
        return resolved
    }
}
