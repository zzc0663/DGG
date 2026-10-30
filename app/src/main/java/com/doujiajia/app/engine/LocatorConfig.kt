package com.doujiajia.app.engine

import android.content.Context
import com.doujiajia.app.data.LogStore
import org.json.JSONObject
import java.io.File

/**
 * 一个可定位的目标。IMAGE 类型在 M1 恒不命中（图像通道 M2 接入）。
 *
 * [Kind.EDITABLE] 是「按可编辑性定位」，[value] 无意义（恒为空）：
 * 抖音的输入框文案不可靠——搜索框默认填的是「推荐搜索词」，直播间输入框外面盖着
 * 「说点什么」的提示节点——按文本找必然踩空，只能按 `isEditable` 找。
 */
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
        IMAGE("image"),

        /** 找不到别的特征时的最后手段：屏上第一个可编辑节点 */
        EDITABLE("editable");

        companion object {
            fun fromJson(name: String?): Kind? =
                values().firstOrNull { it.jsonName.equals(name, ignoreCase = true) }
        }
    }

    override fun toString(): String =
        if (kind == Kind.EDITABLE) "editable" else "${kind.jsonName}:\"$value\""
}

/** 页面判定的一条证据：命中则加 [weight] 分 */
data class Feature(val weight: Int, val target: Target)

/** 一个页面的判定规则：得分 ≥ [threshold] 才认定处于该页面 */
data class PageRule(val name: String, val threshold: Int, val features: List<Feature>) {
    /** 理论满分，用于在加载期校验 threshold 是否可达 */
    val maxScore: Int get() = features.sumOf { it.weight }
}

/**
 * 「压在直播间上、按一次返回就能关掉」的浮层规则（礼物面板 / 观众列表 / 资料卡 / 评论键盘）。
 *
 * 与 [PageRule] 同一套加权投票，但用途相反：页面规则回答「我在哪」，
 * 这里回答「有什么东西盖住了我」。识别到之后**只允许按一次返回**，
 * 绝不点浮层里的任何控件——礼物面板里每个可点元素都是「选礼物 / 赠送 / 充值」。
 *
 * 为什么配置化：这套文案是抖音改版最频繁的地方，2026-10-29 实测
 * 礼物面板只暴露礼物卡节点（`赠送`、`双击赠送`、`N 钻`），观众列表只暴露
 * 两个页签（`在线观众` / `玩家榜`），全靠硬编码三个词必然越用越不准。
 */
data class OverlayRule(
    val key: String,
    val label: String,
    val threshold: Int,
    val features: List<Feature>
) {
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
    val excludeNicknames: Set<String> = DEFAULT_EXCLUDE_NICKNAMES,
    /**
     * 推荐流（养号）的行为参数。
     *
     * 与 [pages]/[targets] 同源同一份 JSON，但它描述的是「怎么动」而不是「控件在哪」，
     * 所以单独成类（见 [FeedConfig]）。缺这一段时用默认值——养号是老功能的新增，
     * 不该因为旧配置文件没有 feed 段就跑不起来。
     */
    val feed: FeedConfig = FeedConfig(),
    /**
     * 浮层规则（礼物面板 / 观众列表 / 资料卡 / 评论键盘）。
     * 缺这一段时用 [DEFAULT_OVERLAYS]——等价于 M2 时代的「赠送 / 资料卡 / 发送」三条硬编码，
     * 旧配置文件不会因为少一段而行为退化。
     */
    val overlays: Map<String, OverlayRule> = DEFAULT_OVERLAYS,
    /**
     * 「私密账号」的判据文案（**全等**匹配）。
     *
     * 私密账号关注后只是**发出一份申请**，对方不通过就永远不是粉丝——抖音官方口径是
     * 「开启私密账号后，所有关注操作都会变成提交申请，不会直接关注成功」。
     * 对脚本来说这是一次**白干的动作**：粉丝数不会增加，占了一次关注额度，
     * 还多花一次点击。所以要在弹层里尽早认出它，跳过并把这个人记进私密名单。
     *
     * 缺这一段时用 [DEFAULT_PRIVATE_HINTS]，旧配置文件不会因此丢掉这个能力。
     */
    val privateAccountHints: Set<String> = DEFAULT_PRIVATE_HINTS
) {

    fun target(name: String): Target? = targets[name]

    companion object {
        private const val TAG = "定位配置"

        val DEFAULT_EXCLUDE_NICKNAMES: Set<String> = setOf(
            "我", "作者", "主播", "管理员", "房管", "粉丝团"
        )

        /**
         * 配置里没有 `privateAccountHints` 时的兜底。
         *
         * 只收「一定是申请态」的文案：`私密账号`（资料卡上的标记）+ 各种版本的申请按钮文案。
         * 一律**全等**匹配——写成包含匹配会让「关注」命中「申请关注」，把所有人判成私密账号。
         */
        val DEFAULT_PRIVATE_HINTS: Set<String> = setOf(
            "私密账号", "私密帐户", "申请关注", "已申请", "已提交请求", "等待通过",
            "请求关注", "已发送关注请求", "关注请求已发送", "已发送申请", "申请已发送"
        )

        /**
         * 配置里没有 `overlays` 段时的兜底：等价于 M2 时代硬编码的三个精确文案。
         *
         * 刻意只保留最保守的三条（阈值就是单条特征的分），宁可少认几种浮层
         * （认不出的表现是「等一会儿再中止/暂停」，不会误点），也不要多认。
         */
        val DEFAULT_OVERLAYS: Map<String, OverlayRule> = linkedMapOf(
            "GIFT_PANEL" to OverlayRule(
                "GIFT_PANEL", "礼物面板", 3,
                listOf(Feature(3, Target(Target.Kind.TEXT, "赠送")))
            ),
            "PROFILE_CARD" to OverlayRule(
                "PROFILE_CARD", "资料卡", 2,
                listOf(Feature(2, Target(Target.Kind.TEXT, "资料卡")))
            ),
            "COMMENT_KEYBOARD" to OverlayRule(
                "COMMENT_KEYBOARD", "评论键盘", 2,
                listOf(Feature(2, Target(Target.Kind.TEXT, "发送")))
            )
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

            val overlays = parseOverlays(root.optJSONObject("overlays"))

            val privateHints = root.optJSONArray("privateAccountHints")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    .toSet()
            }?.takeIf { it.isNotEmpty() } ?: DEFAULT_PRIVATE_HINTS

            return LocatorConfig(
                version,
                pages,
                targets,
                exclude,
                FeedConfig.parse(root.optJSONObject("feed")),
                overlays,
                privateHints
            )
        }

        /**
         * 解析 `overlays` 段。缺段 / 全非法 → 用 [DEFAULT_OVERLAYS]。
         *
         * 与 [parse] 里的 pages 解析同一套纪律：跳过 `_` 开头的注释键、`optJSONObject()`
         * 而不是硬转、阈值超过满分直接丢弃并告警——一份写坏的配置只会少认几种浮层，
         * 不会让整份定位配置降级。
         */
        private fun parseOverlays(json: JSONObject?): Map<String, OverlayRule> {
            if (json == null) return DEFAULT_OVERLAYS
            val result = LinkedHashMap<String, OverlayRule>()
            json.keys().forEach { key ->
                if (key.startsWith("_")) return@forEach
                val obj = json.optJSONObject(key)
                if (obj == null) {
                    LogStore.w(TAG, "overlays.$key 不是对象，已跳过")
                    return@forEach
                }
                val rulesJson = obj.optJSONArray("features")
                if (rulesJson == null || rulesJson.length() == 0) {
                    LogStore.w(TAG, "overlays.$key 无 features，已跳过")
                    return@forEach
                }
                val features = (0 until rulesJson.length()).mapNotNull { i ->
                    val featureJson = rulesJson.optJSONObject(i) ?: return@mapNotNull null
                    val target = parseTarget(featureJson) ?: return@mapNotNull null
                    Feature(featureJson.optInt("w", 1), target)
                }
                if (features.isEmpty()) {
                    LogStore.w(TAG, "overlays.$key 的 features 全部非法，已跳过")
                    return@forEach
                }
                val label = obj.optString("label").takeIf { it.isNotBlank() } ?: key
                val rule = OverlayRule(key, label, obj.optInt("threshold", -1), features)
                when {
                    rule.threshold <= 0 -> LogStore.w(TAG, "overlays.$key 缺少合法 threshold，已跳过")
                    rule.maxScore < rule.threshold -> LogStore.w(
                        TAG,
                        "overlays.$key 阈值 ${rule.threshold} 超过满分 ${rule.maxScore}，永远无法命中，已跳过"
                    )

                    else -> result[key] = rule
                }
            }
            if (result.isEmpty()) {
                LogStore.w(TAG, "overlays 段没有任何有效规则，改用内置 ${DEFAULT_OVERLAYS.size} 条")
                return DEFAULT_OVERLAYS
            }
            return result
        }

        private fun parseTarget(obj: JSONObject): Target? {
            val kind = Target.Kind.fromJson(obj.optString("type")) ?: return null
            // editable 按可编辑性定位，不需要 value；其余类型缺 value 一律视为非法
            if (kind == Target.Kind.EDITABLE) return Target(kind, "")
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
