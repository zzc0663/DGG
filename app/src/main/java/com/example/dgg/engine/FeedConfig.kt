package com.example.dgg.engine

import org.json.JSONObject

/**
 * 推荐流（养号）的行为参数 —— 与定位规则放在同一份 `douyin_locator.json` 的 `feed` 段。
 *
 * 为什么单独一段而不是塞进 `targets`：这里的很多东西**不是控件**，是行为参数
 * （文案区从屏幕哪个高度开始、滑动起止点比例、熔断轮数）。塞进 targets 会让
 * `Target` 这个「可定位目标」的语义被污染，而且改版时要改的地方本来就不同。
 *
 * 全部字段都有默认值，且解析一律 `opt*`：配置里少写、写错一个字段不该让整份配置降级
 * ——`LocatorConfig.parse` 曾经因为一行字符串注释把整份配置丢掉，M2 直接起不来。
 */
data class FeedConfig(
    /** 文案候选只从屏幕这个高度以下取（比例）。实测文案在 0.86、作者名 0.83、推荐标记 0.79 */
    val copyAreaTopRatio: Float = 0.78f,
    /** 文案最多取几条拼接（视频页的「文案 / 话题 / 音乐名」是分开的多个节点） */
    val copyMaxCandidates: Int = 3,
    /** 含这些片段的文本一律不当文案（都是按钮语义或系统标记） */
    val copyExcludeHints: List<String> = DEFAULT_COPY_EXCLUDE,
    /** 点赞按钮的 desc 里一定含 [likeHint]；状态词的**前缀**区分已赞/未赞 */
    val likeHint: String = "喜欢",
    val likeOn: String = "已点赞",
    val likeOff: String = "未点赞",
    /** 收藏按钮：实测 desc 是「未选中，收藏9847，按钮」/「已选中，收藏…，按钮」 */
    val collectHint: String = "收藏",
    val collectOn: String = "已选中，收藏",
    val collectOff: String = "未选中，收藏",
    /** 命中即判定为直播推荐卡 —— **必须滑走，绝不点** */
    val liveCardHints: List<String> = listOf("点击进入直播间", "直播中"),
    /**
     * 命中 [liveCardHints] 的节点还**必须占屏幕这么多面积**才算直播卡。
     *
     * 2026-10-28 真机踩坑：抖音的**直播悬浮小窗**（左下角 PiP，看直播时退回来自动悬浮）
     * 也带 `点击进入直播间` 文案，而且它一直浮在最上层。只看「有没有这段文字」时，
     * 每一屏都会被判成直播卡 —— 实测连刷 **39 屏**都是同一句小窗标题
     * （`撩开你的长发 怎么有香水味 #女大学生 #随拍`），引擎一直在「滑走」却什么都没发生，
     * 也永远不会熔断（直播卡不算「认不出」）。
     * 真直播卡的按钮是一整块盖住屏幕的（面积 > 50%），PiP 只有百分之几。
     */
    val liveCardMinAreaRatio: Float = 0.12f,
    /** 命中即判定为商品/广告，照样只滑不点 */
    val adHints: List<String> = listOf("搜同款", "购买", "商品", "购物", "广告"),
    /** 图文作品标记（「图片1，按钮」「动图」）—— 浏览行为更短，但目前与视频同等对待 */
    val imageHints: List<String> = listOf("图片", "动图"),

    // ===== 滑动轨迹（比例，相对屏幕宽高）=====
    val swipeFromYMin: Float = 0.60f,
    val swipeFromYMax: Float = 0.68f,
    val swipeToYMin: Float = 0.18f,
    val swipeToYMax: Float = 0.25f,
    val swipeXMin: Float = 0.30f,
    val swipeXMax: Float = 0.70f,

    /**
     * 播放器容器的 content-desc —— 快照剪枝用。**默认空 = 不剪枝。**
     *
     * 2026-10-28 真机实测：剪掉 `desc="视频"` 的子树后，节点 1173 → 488、耗时 12.6s → 5.8s，
     * 但**点赞/收藏/文案全在它子树里**，剪完每屏都判成「认不出」，8 屏就熔断。
     * 所以默认关掉。留着这个开关是为了「哪天节点数再涨一倍」时还有得调，
     * 但开之前必须先验证分类仍然正确（看日志里的 第 N 屏 是否还能识别出视频/图文）。
     */
    val videoContainerDesc: String = "",

    /** 连续这么多屏都认不出来就中止 —— 改版后会一直空滑，用户以为还在跑 */
    val stallAbortRounds: Int = 8,
    /**
     * 连续这么多屏**指纹完全相同**就中止。
     *
     * 与 [stallAbortRounds] 互补：那条管「认不出」，这条管「认得出但没换」。
     * 真机上滑动被吞、停在活动页/直播卡里、无障碍手势被系统拦掉，都会表现成
     * 「同一屏无限重复」——日志刷得很热闹，实际一屏都没动。没有这条就只能靠运行时长兜底。
     */
    val stallSameScreenRounds: Int = 6,
    /** 右侧竖排按钮栏的起始比例：x 小于屏宽这个值的一律不当按钮（防点到文案/顶部页签） */
    val rightRailLeftRatio: Float = 0.80f
) {
    companion object {
        /**
         * 实测出来的排除词。少一个就会把按钮文案当视频文案塞进标签匹配：
         * 「分享1.2万」「共1181人推荐」「搜同款吊带背心」都曾经是候选。
         */
        val DEFAULT_COPY_EXCLUDE: List<String> = listOf(
            "，按钮", "已选中", "未选中", "喜欢", "评论", "分享", "收藏", "音乐",
            "人推荐", "推荐按钮", "搜同款", "玩同款", "拍同款", "发弹幕", "进度条",
            "图片", "动图", "说点什么", "关注", "播放视频", "暂停视频", "首页", "朋友",
            "展开", "道具", "全屏", "倍速", "清屏",
            "消息", "我", "直播", "团购", "广州", "商城", "搜索", "侧边栏", "拍摄"
        )

        fun parse(json: JSONObject?): FeedConfig {
            val d = FeedConfig()
            if (json == null) return d

            fun str(key: String, def: String): String =
                json.optString(key).takeIf { it.isNotBlank() } ?: def

            fun list(key: String, def: List<String>): List<String> {
                val arr = json.optJSONArray(key) ?: return def
                val items = (0 until arr.length())
                    .mapNotNull { arr.optString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                return items.ifEmpty { def }
            }

            return FeedConfig(
                copyAreaTopRatio = json.optDouble("copyAreaTopRatio", d.copyAreaTopRatio.toDouble())
                    .toFloat().coerceIn(0.3f, 0.99f),
                copyMaxCandidates = json.optInt("copyMaxCandidates", d.copyMaxCandidates)
                    .coerceIn(1, 8),
                copyExcludeHints = list("copyExcludeHints", d.copyExcludeHints),
                likeHint = str("likeHint", d.likeHint),
                likeOn = str("likeOn", d.likeOn),
                likeOff = str("likeOff", d.likeOff),
                collectHint = str("collectHint", d.collectHint),
                collectOn = str("collectOn", d.collectOn),
                collectOff = str("collectOff", d.collectOff),
                liveCardHints = list("liveCardHints", d.liveCardHints),
                liveCardMinAreaRatio = json.optDouble(
                    "liveCardMinAreaRatio", d.liveCardMinAreaRatio.toDouble()
                ).toFloat().coerceIn(0.02f, 0.9f),
                adHints = list("adHints", d.adHints),
                imageHints = list("imageHints", d.imageHints),
                swipeFromYMin = json.optDouble("swipeFromYMin", d.swipeFromYMin.toDouble()).toFloat(),
                swipeFromYMax = json.optDouble("swipeFromYMax", d.swipeFromYMax.toDouble()).toFloat(),
                swipeToYMin = json.optDouble("swipeToYMin", d.swipeToYMin.toDouble()).toFloat(),
                swipeToYMax = json.optDouble("swipeToYMax", d.swipeToYMax.toDouble()).toFloat(),
                swipeXMin = json.optDouble("swipeXMin", d.swipeXMin.toDouble()).toFloat(),
                swipeXMax = json.optDouble("swipeXMax", d.swipeXMax.toDouble()).toFloat(),
                videoContainerDesc = json.optString("videoContainerDesc", d.videoContainerDesc),
                stallAbortRounds = json.optInt("stallAbortRounds", d.stallAbortRounds).coerceIn(2, 50),
                stallSameScreenRounds = json.optInt(
                    "stallSameScreenRounds", d.stallSameScreenRounds
                ).coerceIn(2, 50),
                rightRailLeftRatio = json.optDouble("rightRailLeftRatio", d.rightRailLeftRatio.toDouble())
                    .toFloat().coerceIn(0.5f, 0.95f)
            )
        }
    }
}
