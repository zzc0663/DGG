package com.example.dgg.engine

import android.graphics.Rect
/**
 * 一屏推荐流内容的分类结果。
 *
 * 为什么必须先分类再动作：实测（抖音 40.8.0）推荐流是**混合信息流**，连滑四次是
 * 「图文 → 直播推荐卡 → 视频 → 视频」。直播卡上有一整块 `点击进入直播间按钮`
 * 覆盖几乎整屏，只要盲点一下就直接进直播间，后面所有页面判定、风控、返回逻辑全乱。
 */
data class FeedScreen(
    val kind: Kind,
    /** 去噪后的文案（可能为空 —— 视频没写文案很常见） */
    val copyText: String,
    val like: Hit?,
    val collect: Hit?,
    val comment: Hit?,
    /** 点赞按钮当前是不是「已点赞」 */
    val likeOn: Boolean,
    /** 收藏按钮当前是不是「已选中」 */
    val collectOn: Boolean,
    /** 同一屏的指纹：切换视频后必须变化，用来确认"停留的还是这一条" */
    val signature: String
) {

    /**
     * 单行化后的文案 —— 日志专用。
     *
     * 图文作品的文案带换行（实测 `评论区说单发这张会火，回复@…\n…`），
     * 直接打进日志会把一行拆成多行，后面用 grep 抓时间线就断了。
     */
    val copyOneLine: String get() = copyText.replace('\n', ' ').replace('\r', ' ').trim()

    /** 快照里量出来的矩形 + 当时的 desc，便于日志取证 */
    data class Hit(val rect: Rect, val desc: String)

    enum class Kind(val label: String) {
        VIDEO("视频"),
        IMAGE_SET("图文"),
        LIVE_CARD("直播卡"),
        AD("广告/商品"),
        UNKNOWN("认不出");

        /** 只有这两种值得停留和互动；其余一律只滑不点 */
        val interactive: Boolean get() = this == VIDEO || this == IMAGE_SET
    }

    val interactive: Boolean get() = kind.interactive

    fun summary(): String = buildString {
        append(kind.label)
        if (copyOneLine.isNotBlank()) append("「${copyOneLine.take(24)}」")
        if (like != null) append(if (likeOn) " 已赞" else " 未赞")
        if (collect != null && collectOn) append(" 已藏")
    }
}

/**
 * 把一份节点快照判成 [FeedScreen]。**纯函数，不碰无障碍服务**，可以单测。
 *
 * 全部判据来自真机实测（见 `docs/M4-养号助手.md` §3）：
 * - 点赞 `content-desc="未点赞，喜欢13.3万，按钮"` → 已赞是 `已点赞，喜欢…`
 * - 收藏 `content-desc="未选中，收藏9847，按钮"` → 已藏是 `已选中，收藏…`
 * - 直播卡有 `text="点击进入直播间"`
 * - 文案：视频页在 `text`、图文页在 `content-desc`，**两处都要扫**
 */
object FeedClassifier {

    /** 纯数量：整数/小数 + 可选 万/亿/w/W。抖音的点赞与评论数就是这种形态 */
    private val QUANTITY_ONLY = Regex("^\\d+(\\.\\d+)?\\s*[万亿wW]?$")

    fun classify(
        facts: List<Actions.NodeFacts>,
        width: Int,
        height: Int,
        cfg: FeedConfig
    ): FeedScreen {
        if (facts.isEmpty()) {
            return empty(cfg)
        }

        // 右侧竖排按钮栏的左边界。低于它的所有命中都不算按钮：
        // 顶部页签里也有「关注」「直播」，文案区里也可能出现「收藏」字样。
        val railLeft = width * cfg.rightRailLeftRatio
        val copyTop = height * cfg.copyAreaTopRatio
        // 直播卡的面积门槛。直播悬浮小窗（PiP）也带「点击进入直播间」，
        // 只按文字判定会让每一屏都变成直播卡（真机连刷 39 屏的坑）。
        val minLiveArea = (width.toLong() * height) * cfg.liveCardMinAreaRatio

        var liveCard = false
        var ad = false
        var imageSet = false
        var like: FeedScreen.Hit? = null
        var collect: FeedScreen.Hit? = null
        var comment: FeedScreen.Hit? = null
        var likeOn = false
        var collectOn = false

        val copyCandidates = ArrayList<String>()

        for (f in facts) {
            // 屏幕外的节点一律不参与判定。RecyclerView 会把**下一条**预加载进树里，
            // 实测下一条的点赞按钮 top=3376、文案 top=4244（屏高 2374），
            // 甚至出现 top > bottom 的坏 rect。不排除会出两个错：
            // 1) 文案把下一条视频的字拼进来 → 指纹永远对不上这一屏，标签也匹配错；
            // 2) 右侧按钮取到屏幕外那一个 → 点下去打空，然后校验必然失败。
            if (f.top >= height || f.bottom <= 0) continue

            // text 与 desc 都是候选文案，且**互不替代**：图文作品的 text 只有「第 25 集」，
            // 完整正文在 desc 里；视频作品反过来，desc 为空、正文在 text 里。
            for (raw in arrayOf(f.text, f.desc)) {
                val t = raw?.trim() ?: continue
                if (t.length < 2) continue

                if (cfg.liveCardHints.any { t.contains(it) } && areaOf(f) >= minLiveArea) {
                    liveCard = true
                }
                if (cfg.adHints.any { t.contains(it) }) ad = true

                // 图文标记要求是「按钮语义」或正好是「动图」，否则正文明明在聊图片也会被判成图文
                if (t == "动图" || (t.contains("，按钮") && cfg.imageHints.any { t.contains(it) })) {
                    imageSet = true
                }

                if (f.left >= railLeft && t.contains("，按钮")) {
                    when {
                        t.contains(cfg.likeHint) && like == null -> {
                            like = FeedScreen.Hit(f.rect(), t)
                            likeOn = t.contains(cfg.likeOn)
                        }

                        t.contains(cfg.collectHint) && collect == null -> {
                            collect = FeedScreen.Hit(f.rect(), t)
                            collectOn = t.contains(cfg.collectOn)
                        }

                        t.contains("评论") && comment == null -> {
                            comment = FeedScreen.Hit(f.rect(), t)
                        }
                    }
                }

                if (f.top < copyTop) continue
                if (cfg.copyExcludeHints.any { t.contains(it) }) continue
                // 纯数量（「4.2万」「1234」「1.2亿」）不是文案。实测点赞数节点就落在文案区上方，
                // 不过滤就会被拼进 copyText，标签匹配时变成噪音。
                if (QUANTITY_ONLY.matches(t)) continue
                // 纯作者名（「@sweet」）不是文案；带空格的是「@某人 说了句话」，要保留
                if (t.startsWith("@") && !t.contains(" ") && t.length < 16) continue
                copyCandidates += t
            }
        }

        val copyText = copyCandidates
            .distinct()
            .sortedByDescending { it.length }
            .take(cfg.copyMaxCandidates.coerceIn(1, 8))
            .joinToString(" ")

        val kind = when {
            liveCard -> FeedScreen.Kind.LIVE_CARD
            like == null && collect == null -> FeedScreen.Kind.UNKNOWN
            ad -> FeedScreen.Kind.AD
            imageSet -> FeedScreen.Kind.IMAGE_SET
            else -> FeedScreen.Kind.VIDEO
        }

        return FeedScreen(
            kind = kind,
            copyText = copyText,
            like = like,
            collect = collect,
            comment = comment,
            likeOn = likeOn,
            collectOn = collectOn,
            signature = copyText.ifBlank { "" }
        )
    }

    /** 节点面积（像素）。用 Long 是怕超大屏幕上的溢出，虽然实际用不上 */
    private fun areaOf(f: Actions.NodeFacts): Long {
        val w = (f.right - f.left).coerceAtLeast(0)
        val h = (f.bottom - f.top).coerceAtLeast(0)
        return w.toLong() * h
    }

    private fun empty(cfg: FeedConfig) = FeedScreen(
        kind = FeedScreen.Kind.UNKNOWN,
        copyText = "",
        like = null,
        collect = null,
        comment = null,
        likeOn = false,
        collectOn = false,
        signature = ""
    )

    /**
     * 点完之后重新读状态，判断操作是否真的生效。
     *
     * 养号的点赞**必须**走这个校验：实测点一下 desc 会从「未点赞，喜欢…」变成
     * 「已点赞，喜欢…」，拿不到这个变化就说明没点中（点歪到画面中央会变成暂停）。
     * M3 直播间做不到这一点，那边只能记「已发出手势」，这里不该退化成那样。
     */
    fun likeStateOf(
        facts: List<Actions.NodeFacts>,
        width: Int,
        height: Int,
        cfg: FeedConfig
    ): Boolean? {
        val railLeft = width * cfg.rightRailLeftRatio
        for (f in facts) {
            // 同 classify：屏幕外的是下一条预加载的按钮，它的「未点赞」不代表这一条
            if (f.top >= height || f.bottom <= 0) continue
            val d = f.desc ?: continue
            if (f.left < railLeft) continue
            if (!d.contains(cfg.likeHint)) continue
            return d.contains(cfg.likeOn)
        }
        return null
    }

    fun collectStateOf(
        facts: List<Actions.NodeFacts>,
        width: Int,
        height: Int,
        cfg: FeedConfig
    ): Boolean? {
        val railLeft = width * cfg.rightRailLeftRatio
        for (f in facts) {
            if (f.top >= height || f.bottom <= 0) continue
            val d = f.desc ?: continue
            if (f.left < railLeft) continue
            if (!d.contains(cfg.collectHint)) continue
            return d.contains(cfg.collectOn)
        }
        return null
    }
}
