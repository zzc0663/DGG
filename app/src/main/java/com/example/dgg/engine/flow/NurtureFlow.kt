package com.example.dgg.engine.flow

import android.os.SystemClock
import com.example.dgg.data.LogStore
import com.example.dgg.data.Settings
import com.example.dgg.engine.FeedClassifier
import com.example.dgg.engine.FeedConfig
import com.example.dgg.engine.FeedScreen
import com.example.dgg.engine.Flow
import com.example.dgg.engine.FlowAbortException
import com.example.dgg.engine.FlowContext
import com.example.dgg.engine.NurtureTags
import com.example.dgg.engine.PageState
import com.example.dgg.engine.TagRule
import kotlinx.coroutines.delay

/**
 * M4 主流程：**主动刷推荐流视频**，按长尾分布随机停留，按文案标签加权点赞。
 *
 * 与 M2/M3 最大的不同：这条流程**自己推进页面**（上下滑），不依赖用户先把界面停好。
 * 因此它必须先回答两个问题，答错就会把手机点进直播间：
 *
 * 1. **现在是不是推荐流？** 靠加权投票判 `FEED`，判不出就明确中止，不猜。
 * 2. **这一屏是什么？** 推荐流是**混合信息流**（实测：图文 → 直播卡 → 视频 → 视频）。
 *    直播卡上有整块「点击进入直播间」覆盖屏幕，只能滑走，绝不能碰。
 *
 * 本流程**不关注任何人**（2026-10-28 决策），所以它不会涨粉 —— 它做的是
 * 「把账号养得像个正常活跃用户」，界面文案也按这个口径写。
 */
class NurtureFlow : Flow {

    override val name = "养号刷视频"

    override suspend fun run(ctx: FlowContext) {
        val settings = ctx.settings
        val feed = ctx.config.feed

        // 「垂直养号」需要「搜索 → 进搜索结果 → 逐个打开视频」整条导航链，排在 P4。
        // 与其跑一个跟参数无关的流程，不如明确拒绝（M3 时养号页签就是这么处理的）。
        if (settings.nurtureMode == Settings.NURTURE_VERTICAL) {
            throw FlowAbortException(
                "「垂直养号」还没开放（计划 P4）。现在可以选「刷视频养号」或「首页连播」"
            )
        }
        val browseOnly = settings.nurtureMode == Settings.NURTURE_HOME

        val parsed = NurtureTags.parse(settings.nurtureTags)
        val rules = parsed.rules
        if (parsed.invalidLines.isNotEmpty()) {
            // 逐行容错：写错一行只跳过那一行，不能让整份标签配置失效
            LogStore.w(
                TAG,
                "有 ${parsed.invalidLines.size} 行标签规则看不懂，已跳过：" +
                    parsed.invalidLines.joinToString(" | ")
            )
        }

        if (!awaitDouyinForeground(ctx, FOREGROUND_TIMEOUT_MS)) {
            throw FlowAbortException(
                "等待 ${FOREGROUND_TIMEOUT_MS / 1000} 秒仍未切到抖音前台。请先打开抖音再开始"
            )
        }
        if (!ensureFeed(ctx)) {
            throw FlowAbortException(
                "没能进入推荐流。请手动打开抖音、停在首页的**视频画面**（能看到右侧点赞按钮）后重试"
            )
        }

        val minutes = settings.runMinutes
            .coerceIn(Settings.RUN_MINUTES_MIN, Settings.RUN_MINUTES_MAX)
        val deadline = SystemClock.uptimeMillis() + minutes * 60_000L
        val paceLabel = paceName(settings.nurturePace)

        ctx.publish(
            counterLabel = "已浏览",
            followed = 0,
            liked = 0,
            collected = 0,
            target = 0,
            message = "开始刷视频（${minutes}分钟 · ${paceLabel}节奏）"
        )
        LogStore.i(
            TAG,
            "开始：模式=${modeName(settings.nurtureMode)} 时长=${minutes}分钟 节奏=$paceLabel " +
                "点赞基线=${settings.probLike}% 收藏基线=${settings.probCollect}% " +
                "标签 ${rules.size} 条" +
                if (rules.isEmpty()) "（未配置标签，点赞就是基线概率）" else ""
        )

        var browsed = 0
        var liked = 0
        var collected = 0
        var unknownStreak = 0
        var sameScreenStreak = 0
        var lastSignature = ""
        var sinceRest = 0
        var nextRestAt = ctx.humanizer.intIn(REST_EVERY_MIN, REST_EVERY_MAX)
        // 初始值放在「够远」的位置，保证第一屏就可以互动
        var lastInteractRound = -INTERACT_GAP_ROUNDS
        var interactFailures = 0
        var round = 0

        while (!ctx.stopped()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                ctx.publish(message = "已到设定时长 $minutes 分钟")
                break
            }

            round++
            val roundStart = SystemClock.uptimeMillis()
            val width = ctx.actions.screenWidth()
            val height = ctx.actions.screenHeight()
            // ttl=0：每轮都要看**当前**这一屏。用缓存的快照会把上一屏的文案当成这一屏的，
            // 表现成「明明换了视频却还在按旧文案判点赞」。
            val classifyStart = SystemClock.uptimeMillis()
            val screen = FeedClassifier.classify(fastSnapshot(ctx, feed), width, height, feed)
            LogStore.d(
                TAG,
                "第 $round 屏（识别 ${SystemClock.uptimeMillis() - classifyStart}ms）：${screen.summary()}"
            )
            // 风控检查放在这里而不是滑动之后：刚抓的快照还在 700ms 缓存里，
            // 这次检查**不额外遍历一遍树**。养号一屏本来就要抓一次树，
            // 滑动后再单独查一次等于把开销翻倍——而真机上一趟遍历是秒级的。
            ctx.guard()

            // 「认得出但没换」也要熔断。真机踩过：画面停在九宫格页 + 左下直播悬浮小窗，
            // 每屏都被判成直播卡并「滑走」，连刷 39 屏其实一屏都没动，
            // 而直播卡不算「认不出」，原来的熔断一条都没触发。
            // 只在指纹非空时计数：很多视频确实没写文案（指纹为空），那不是卡住。
            val signature = screen.signature
            if (signature.isNotBlank() && signature == lastSignature) {
                sameScreenStreak++
                if (sameScreenStreak >= feed.stallSameScreenRounds) {
                    throw FlowAbortException(
                        "连续 ${sameScreenStreak + 1} 屏画面完全相同（「${signature.take(20)}」），" +
                            "滑动没有生效。可能是抖音停在活动页/直播卡里，或无障碍手势被系统拦了。" +
                            "已中止，请手动切到推荐流的视频画面后重试"
                    )
                }
                LogStore.w(TAG, "这一屏和上一屏完全相同（$sameScreenStreak 次），可能是滑动没生效")
            } else {
                sameScreenStreak = 0
            }
            lastSignature = signature

            when {
                screen.kind == FeedScreen.Kind.LIVE_CARD || screen.kind == FeedScreen.Kind.AD -> {
                    unknownStreak = 0
                    LogStore.i(TAG, "跳过${screen.kind.label}（只滑不点，防止误入直播间/商城）")
                }

                !screen.interactive -> {
                    unknownStreak++
                    if (unknownStreak >= feed.stallAbortRounds) {
                        throw FlowAbortException(
                            "连续 $unknownStreak 屏认不出内容（右侧既没有点赞/收藏按钮，也不是直播卡）。" +
                                "可能是抖音改版了，也可能当前根本不在推荐流的视频画面。" +
                                "已中止，避免继续空滑把账号行为搞得莫名其妙"
                        )
                    }
                }

                else -> {
                    unknownStreak = 0
                    browsed++
                    ctx.publish(followed = browsed, message = screen.summary())

                    val canInteract = !browseOnly &&
                        round - lastInteractRound >= INTERACT_GAP_ROUNDS &&
                        interactFailures < MAX_INTERACT_FAILURES

                    if (canInteract) {
                        val likeResult = tryLike(ctx, screen, settings, rules, feed, width)
                        val collectResult = tryCollect(ctx, screen, settings, rules, feed, width)
                        if (likeResult == ActionResult.OK) liked++
                        if (collectResult == ActionResult.OK) collected++
                        if (likeResult == ActionResult.OK || collectResult == ActionResult.OK) {
                            lastInteractRound = round
                        }
                        // 连续失败就停手：可能是被限流，继续点只会加重
                        interactFailures = if (likeResult == ActionResult.FAILED ||
                            collectResult == ActionResult.FAILED
                        ) interactFailures + 1 else 0

                        if (liked + collected > 0) {
                            ctx.publish(liked = liked, collected = collected)
                        }
                    }

                    // 停留放在**互动之后**：真人不会进屏就点赞，都是先看几秒。
                    // 把本轮已经花掉的识别/校验时间扣进观看时长，否则一屏会变成
                    // 「12 秒遍历 + 13 秒停留」= 25 秒，节奏整体翻倍。
                    stayOnScreen(
                        ctx,
                        settings.nurturePace,
                        alreadySpentMs = SystemClock.uptimeMillis() - roundStart
                    )

                    if (!browseOnly && screen.comment != null &&
                        ctx.humanizer.chance(PEEK_COMMENT_PERCENT)
                    ) {
                        peekComments(ctx, screen)
                    }
                }
            }

            if (ctx.stopped()) break

            // 2% 滑回去看一眼 —— 纯单向流是很好认的机器特征
            if (!browseOnly && ctx.humanizer.chance(REWATCH_PERCENT)) {
                rewatch(ctx, feed)
            } else {
                swipeNext(ctx, feed)
            }

            sinceRest++
            if (sinceRest >= nextRestAt) {
                // 休息也要看截止时间：原来直接睡满 30~180 秒，
                // 设了 2 分钟的运行会变成「刷 40 屏 + 再睡 111 秒」才结束。
                rest(ctx, deadline - SystemClock.uptimeMillis())
                sinceRest = 0
                nextRestAt = ctx.humanizer.intIn(REST_EVERY_MIN, REST_EVERY_MAX)
            }
        }

        ctx.publish(
            followed = browsed,
            liked = liked,
            collected = collected,
            message = "结束：浏览 $browsed · 赞 $liked · 藏 $collected"
        )
        LogStore.i(TAG, "本轮结束：浏览 $browsed 屏，点赞 $liked，收藏 $collected")
    }

    /**
     * 养号专用快照：**省字段 + 不展开屏幕外的子树**。
     *
     * 真机实测（抖音 40.8.0 / 视频页 / 屏高 2374）：一趟遍历会撞上 `MAX_NODES = 1200` 上限、
     * 单次 5~19 秒。原因是 RecyclerView 把下一条、下下条都挂在树里 ——
     * 下一条的点赞按钮 `top=3376`、文案 `top=4244`，还有整片「券后价 / 已售1万+件」商品列表。
     * 它们在屏幕外，本来就进不了 `facts`，但旧实现照样把子树读完。
     * `pruneOffscreen = true` 后这些直接不展开。
     *
     * 这是本流程唯一允许的抓树入口，别在这里改回 `snapshot(ttlMs = 0L)`。
     */
    private fun fastSnapshot(ctx: FlowContext, feed: FeedConfig) = ctx.actions.snapshot(
        ttlMs = 0L,
        pruneSubtreeOfDesc = feed.videoContainerDesc.ifBlank { null },
        lean = true,
        pruneOffscreen = true
    )

    // ==================== 互动 ====================

    private enum class ActionResult { SKIPPED, OK, FAILED }

    /**
     * 掷骰决定要不要点赞，要的话点完必须**校验 desc 真的变了**。
     *
     * 为什么校验这么严：实测点赞按钮的 `content-desc` 会从「未点赞，喜欢13.3万，按钮」
     * 变成「已点赞，喜欢…」。拿不到这个变化就说明手势没落到按钮上（点歪到画面中央
     * 会变成暂停播放）。M3 直播间做不到这种校验，那时只能写「已发出 N 次手势」——
     * 这里不该退化成那样。
     */
    private suspend fun tryLike(
        ctx: FlowContext,
        screen: FeedScreen,
        settings: Settings,
        rules: List<TagRule>,
        feed: FeedConfig,
        width: Int
    ): ActionResult {
        val hit = screen.like ?: return ActionResult.SKIPPED
        if (screen.likeOn) {
            LogStore.d(TAG, "这条已经赞过了，跳过")
            return ActionResult.SKIPPED
        }

        val hits = NurtureTags.match(screen.copyText, rules)
        val probability = NurtureTags.likeProbability(settings.probLike, hits)
        val roll = ctx.humanizer.intIn(1, 100)
        val willLike = roll <= probability
        // 无论点不点都留一条：用户反馈"点赞太多/太少"时，没有这行就没法定位是概率配错了还是识别错了
        LogStore.i(
            TAG,
            "点赞判定：文案「${screen.copyOneLine.take(30)}」命中 ${NurtureTags.describe(hits)} → " +
                "基线 ${settings.probLike}% 实际 $probability%，掷 $roll → ${if (willLike) "点赞" else "跳过"}"
        )
        if (!willLike) return ActionResult.SKIPPED

        val point = ctx.humanizer.pointIn(hit.rect)
        if (!ctx.actions.tapPoint(point, "点赞")) {
            LogStore.w(TAG, "点赞手势没下发成功")
            return ActionResult.FAILED
        }
        val ok = waitLikeState(ctx, width, feed, expect = true)
        if (ok) {
            LogStore.i(TAG, "点赞成功（desc 已变成「${feed.likeOn}」）")
        } else {
            LogStore.w(TAG, "点赞后状态没变成「${feed.likeOn}」，按失败处理（不计数）")
        }
        return if (ok) ActionResult.OK else ActionResult.FAILED
    }

    private suspend fun tryCollect(
        ctx: FlowContext,
        screen: FeedScreen,
        settings: Settings,
        rules: List<TagRule>,
        feed: FeedConfig,
        width: Int
    ): ActionResult {
        val hit = screen.collect ?: return ActionResult.SKIPPED
        if (settings.probCollect <= 0) return ActionResult.SKIPPED
        if (screen.collectOn) {
            LogStore.d(TAG, "这条已经收藏过了，跳过")
            return ActionResult.SKIPPED
        }

        val hits = NurtureTags.match(screen.copyText, rules)
        // 上限用 40（MAX_INTERACT_PERCENT），**不是**点赞的 60：界面承诺「其余每项最多 40%」
        val probability = NurtureTags.interactProbability(settings.probCollect, hits)
        val roll = ctx.humanizer.intIn(1, 100)
        val willCollect = roll <= probability
        LogStore.i(
            TAG,
            "收藏判定：命中 ${NurtureTags.describe(hits)} → 基线 ${settings.probCollect}% " +
                "实际 $probability%，掷 $roll → ${if (willCollect) "收藏" else "跳过"}"
        )
        if (!willCollect) return ActionResult.SKIPPED

        if (!ctx.actions.tapPoint(ctx.humanizer.pointIn(hit.rect), "收藏")) {
            LogStore.w(TAG, "收藏手势没下发成功")
            return ActionResult.FAILED
        }
        val ok = waitCollectState(ctx, width, feed, expect = true)
        if (ok) {
            LogStore.i(TAG, "收藏成功（desc 已变成「${feed.collectOn}」）")
        } else {
            LogStore.w(TAG, "收藏后状态没变成「${feed.collectOn}」，按失败处理")
        }
        return if (ok) ActionResult.OK else ActionResult.FAILED
    }

    private suspend fun waitLikeState(
        ctx: FlowContext,
        width: Int,
        feed: FeedConfig,
        expect: Boolean
    ): Boolean {
        val height = ctx.actions.screenHeight()
        repeat(VERIFY_ATTEMPTS) { i ->
            // 点赞有心形动画，状态文案不会立刻更新；给几百毫秒再看
            delay(ctx.humanizer.longIn(VERIFY_DELAY_MIN, VERIFY_DELAY_MAX))
            val state = FeedClassifier.likeStateOf(fastSnapshot(ctx, feed), width, height, feed)
            if (state == expect) return true
            LogStore.d(TAG, "第 ${i + 1} 次校验点赞状态：$state")
        }
        return false
    }

    private suspend fun waitCollectState(
        ctx: FlowContext,
        width: Int,
        feed: FeedConfig,
        expect: Boolean
    ): Boolean {
        val height = ctx.actions.screenHeight()
        repeat(VERIFY_ATTEMPTS) { i ->
            delay(ctx.humanizer.longIn(VERIFY_DELAY_MIN, VERIFY_DELAY_MAX))
            val state = FeedClassifier.collectStateOf(fastSnapshot(ctx, feed), width, height, feed)
            if (state == expect) return true
            LogStore.d(TAG, "第 ${i + 1} 次校验收藏状态：$state")
        }
        return false
    }

    // ==================== 停留与滑动 ====================

    /**
     * 在这一屏停留。区间来自**分段加权**（见 [pickStayTier]），不是均匀随机。
     *
     * `jitter = false` 很关键：区间已经在 [pickStayTier] 里掷过骰，
     * 再叠一层 ±30% 抖动会让"1.5~4 秒"实测成"1~5.2 秒"，日志与行为对不上。
     */
    private suspend fun stayOnScreen(ctx: FlowContext, pace: Int, alreadySpentMs: Long = 0L) {
        val tier = pickStayTier(ctx)
        val factor = when (pace) {
            Settings.PACE_SLOW -> PACE_FACTOR_SLOW
            Settings.PACE_FAST -> PACE_FACTOR_FAST
            else -> PACE_FACTOR_NORMAL
        }
        val low = tier.first * factor / 100
        val high = tier.second * factor / 100
        val target = ctx.humanizer.longIn(low, high)

        // 视频页一趟无障碍遍历实测 5~12 秒，这段脚本"看"的时间本来就等于在观看。
        // 不扣掉的话，每条视频实际占用 = 遍历 + 停留，节奏会比设定的慢一倍，
        // 而日志还写着「观看 X 秒」——用户会认为参数没生效。
        val remain = target - alreadySpentMs
        ctx.publish(
            message = "观看 " + String.format("%.1f", target / 1000.0) + " 秒" +
                if (alreadySpentMs >= target) "（识别已占 ${alreadySpentMs / 1000}s）" else ""
        )
        if (remain <= 0) return
        // 分片等待：用户点「暂停」时最迟 400ms 就生效，不会等这条视频看完
        ctx.settleLong(remain, capMs = remain, chunkMs = STAY_CHUNK_MS, jitter = false)
    }

    /**
     * 真人看视频的时长是**长尾**：大量 1~4 秒划过，少数看十几秒，极少数看完。
     *
     * 均匀随机（比如 2~20 秒）听起来随机，实际会让"每次看 8~14 秒"变成一个稳定特征，
     * 反倒更像脚本。这里按 40/35/20/5 的概率分四档，均值约 13.6 秒。
     */
    private fun pickStayTier(ctx: FlowContext): Pair<Long, Long> {
        val r = ctx.humanizer.intIn(1, 100)
        return when {
            r <= 40 -> 1_500L to 4_000L      // 划过
            r <= 75 -> 4_000L to 12_000L     // 正常看
            r <= 95 -> 12_000L to 35_000L    // 看完
            else -> 35_000L to 90_000L       // 深度停留（走神/看评论区）
        }
    }

    /**
     * 上滑到下一屏。
     *
     * 落点必须避开三块区域，否则会变成点击或拖进度条：
     * 右侧按钮栏（x > 0.8 屏宽）、左下文案/进度条（y > 0.78 屏高）、顶部页签（y < 0.15 屏高）。
     */
    private suspend fun swipeNext(ctx: FlowContext, feed: FeedConfig) {
        val width = ctx.actions.screenWidth()
        val height = ctx.actions.screenHeight()
        val x = ctx.humanizer.intIn(
            (width * feed.swipeXMin).toInt(),
            (width * feed.swipeXMax).toInt()
        )
        val fromY = ctx.humanizer.intIn(
            (height * feed.swipeFromYMin).toInt(),
            (height * feed.swipeFromYMax).toInt()
        )
        val toY = ctx.humanizer.intIn(
            (height * feed.swipeToYMin).toInt(),
            (height * feed.swipeToYMax).toInt()
        )
        if (!ctx.actions.swipe(x, fromY, x, toY)) {
            LogStore.w(TAG, "滑动没有下发成功")
        }
        // 刻意**不用 settle**：settle 末尾会做一次风控检查，而滑动刚把快照作废，
        // 那次检查必然重新遍历整棵树。养号每屏已经抓一次树了，多这一趟就是白白慢一倍。
        // 风控改在主循环开头查（复用 classify 的快照），语义不变、开销减半。
        delay(ctx.humanizer.delayMs(SWIPE_SETTLE_MS))
    }

    /** 滑回去看上一条，停 2~5 秒再滑回来 */
    private suspend fun rewatch(ctx: FlowContext, feed: FeedConfig) {
        val width = ctx.actions.screenWidth()
        val height = ctx.actions.screenHeight()
        val x = ctx.humanizer.intIn(
            (width * feed.swipeXMin).toInt(),
            (width * feed.swipeXMax).toInt()
        )
        val fromY = ctx.humanizer.intIn(
            (height * feed.swipeToYMin).toInt(),
            (height * feed.swipeToYMax).toInt()
        )
        val toY = ctx.humanizer.intIn(
            (height * feed.swipeFromYMin).toInt(),
            (height * feed.swipeFromYMax).toInt()
        )
        LogStore.i(TAG, "滑回去看一眼上一条（模拟真人回看）")
        ctx.actions.swipe(x, fromY, x, toY)
        delay(ctx.humanizer.delayMs(REWATCH_SETTLE_MS))
        ctx.settleLong(ctx.humanizer.longIn(2_000L, 5_000L), jitter = false)
    }

    /**
     * 打开评论区看几秒再关掉（不评论）。
     *
     * 关掉后要确认回到 FEED：评论区是浮层，正常按一次返回就收起；若没收起，
     * 再退一次；还不行就**不猜**，交给下一轮分类去判 UNKNOWN 并熔断。
     */
    private suspend fun peekComments(ctx: FlowContext, screen: FeedScreen) {
        val hit = screen.comment ?: return
        LogStore.i(TAG, "看一眼评论区（${hit.desc}）")
        if (!ctx.actions.tapPoint(ctx.humanizer.pointIn(hit.rect), "打开评论区")) return
        ctx.settle(PEEK_OPEN_SETTLE_MS)
        ctx.settleLong(ctx.humanizer.longIn(PEEK_MIN_MS, PEEK_MAX_MS), jitter = false)

        ctx.actions.back()
        ctx.settle(PEEK_CLOSE_SETTLE_MS)
        if (ctx.detector.detect().page == PageState.FEED) return

        LogStore.w(TAG, "关一次评论区后仍不在推荐流，再退一次")
        ctx.actions.back()
        ctx.settle(PEEK_CLOSE_SETTLE_MS)
        if (ctx.detector.detect().page != PageState.FEED) {
            // 不盲操作：下一轮 classify 会给出 UNKNOWN，连续多次就中止
            LogStore.w(TAG, "仍不在推荐流，交给下一轮判定（不猜坐标）")
        }
    }

    // ==================== 导航 ====================

    /**
     * 确保停在推荐流。
     *
     * 不用 `DouyinLauncher.launch()` 那一套「猜房间」逻辑：养号不需要进任何特定页面，
     * 点不到就如实中止，让用户手动停好——瞎猜比不做更糟。
     */
    private suspend fun ensureFeed(ctx: FlowContext): Boolean {
        val page = ctx.detector.detect().page
        if (page == PageState.FEED) return true

        if (page == PageState.LIVE_ROOM) {
            // 从直播间启动养号是很常见的误操作：先退出来，别在直播间里刷
            LogStore.i(TAG, "当前在直播间，先退出（养号不该在直播间里跑）")
            ctx.actions.back()
            ctx.settle(NAV_SETTLE_MS)
        }

        if (ctx.actions.clickByName("nav_home_tab", ctx.config)) {
            ctx.settle(NAV_SETTLE_MS)
        }
        if (ctx.detector.detect().page == PageState.FEED) return true

        if (ctx.actions.clickByName("feed_tab_recommend", ctx.config)) {
            ctx.settle(NAV_SETTLE_MS)
        }
        return ctx.detector.detect().page == PageState.FEED
    }

    private suspend fun awaitDouyinForeground(ctx: FlowContext, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (ctx.actions.isDouyinForeground()) {
                // 刚 resume 时窗口内容可能还是上一帧，稍等再读，避免抓到空树
                delay(FRONT_SETTLE_MS)
                return true
            }
            delay(FRONT_POLL_MS)
        }
        return false
    }

    /**
     * 休息（模拟真人放下手机）。
     *
     * [maxMs] 是「离运行截止还剩多久」：休息不能把已经设好的运行时长顶穿，
     * 否则用户选了 2 分钟、实际却挂了 4 分钟。
     */
    private suspend fun rest(ctx: FlowContext, maxMs: Long) {
        val seconds = ctx.humanizer.intIn(REST_SECONDS_MIN, REST_SECONDS_MAX)
        val duration = minOf(seconds * 1_000L, maxMs)
        // 剩不到 5 秒就别休息了，直接回主循环走正常结束路径
        if (duration < MIN_REST_MS) return
        LogStore.i(TAG, "连刷了一段，休息 ${duration / 1000}s（模拟放下手机）")
        ctx.publish(message = "休息中 ${duration / 1000}s")
        ctx.settleLong(duration, jitter = false)
    }

    private fun modeName(mode: Int): String = when (mode) {
        Settings.NURTURE_HOME -> "首页连播"
        Settings.NURTURE_VERTICAL -> "垂直养号"
        else -> "刷视频养号"
    }

    private fun paceName(pace: Int): String = when (pace) {
        Settings.PACE_SLOW -> "慢"
        Settings.PACE_FAST -> "快"
        else -> "标准"
    }

    private companion object {
        const val TAG = "养号"

        const val FOREGROUND_TIMEOUT_MS = 20_000L
        const val FRONT_POLL_MS = 300L
        const val FRONT_SETTLE_MS = 600L

        /** 两次互动之间至少隔一屏：连着三屏都点赞是最典型的刷量特征 */
        const val INTERACT_GAP_ROUNDS = 2

        /** 连续这么多次互动失败就不再点（可能被限流，继续点只会加重） */
        const val MAX_INTERACT_FAILURES = 6

        /** 点赞/收藏后的状态校验：最多看 2 次，每次间隔随机（每次都要抓一趟树，别贪多） */
        const val VERIFY_ATTEMPTS = 2
        const val VERIFY_DELAY_MIN = 180L
        const val VERIFY_DELAY_MAX = 420L

        /** 停留分片：400ms 一片，暂停响应够快，也不至于太碎 */
        const val STAY_CHUNK_MS = 400L

        val PACE_FACTOR_SLOW = 160L
        val PACE_FACTOR_NORMAL = 100L
        val PACE_FACTOR_FAST = 60L

        const val SWIPE_SETTLE_MS = 500L
        const val REWATCH_SETTLE_MS = 600L

        /** 每 20~40 屏休息一次 */
        const val REST_EVERY_MIN = 20
        const val REST_EVERY_MAX = 40
        const val REST_SECONDS_MIN = 30
        const val REST_SECONDS_MAX = 180
        /** 剩余时间不到这个数就不休息了（省得刚坐下就结束，日志还写「休息 0s」） */
        const val MIN_REST_MS = 5_000L

        /** 3% 概率看一眼评论区 */
        const val PEEK_COMMENT_PERCENT = 3
        const val PEEK_OPEN_SETTLE_MS = 800L
        const val PEEK_CLOSE_SETTLE_MS = 700L
        const val PEEK_MIN_MS = 3_000L
        const val PEEK_MAX_MS = 8_000L

        /** 2% 概率回看 */
        const val REWATCH_PERCENT = 2

        const val NAV_SETTLE_MS = 1_500L
    }
}
