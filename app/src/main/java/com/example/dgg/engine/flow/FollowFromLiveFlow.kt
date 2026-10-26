package com.example.dgg.engine.flow

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.dgg.data.FollowedStore
import com.example.dgg.data.LogStore
import com.example.dgg.engine.Flow
import com.example.dgg.engine.FlowAbortException
import com.example.dgg.engine.FlowContext
import com.example.dgg.engine.LocatorConfig
import com.example.dgg.engine.PageState
import com.example.dgg.engine.Target
import kotlinx.coroutines.delay

/**
 * M2 主流程：**直播间评论里随机挑人关注**。
 *
 * 前提：用户已经手动停在一个目标直播间里（产品决策见 `docs/M2-直播间评论关注.md`）。
 * 本流程不搜索、不找直播间、不私信不点赞——只做：读评论 → 随机挑人 → 关注 → 关弹层 → 回直播间。
 *
 * 一轮 = 挑一个人 → 点昵称 → 等弹层 → 判断是否已关注 → 关注 → **校验真的变成已关注** → 关掉。
 * 任何一步不成立都只是「跳过这个人」，不重试、不猜坐标；
 * 只有「连续几轮连一个候选都采不到」才判定定位失效并中止。
 */
class FollowFromLiveFlow : Flow {

    override val name = "直播间评论关注"

    override suspend fun run(ctx: FlowContext) {
        val settings = ctx.settings
        val store = FollowedStore.get(ctx.appContext)
        val targets = LiveTargets.from(ctx.config)

        if (!awaitDouyinForeground(ctx, DOUYIN_FOREGROUND_TIMEOUT_MS)) {
            throw FlowAbortException(
                "等待 ${DOUYIN_FOREGROUND_TIMEOUT_MS / 1000} 秒仍未切到抖音前台。" +
                    "请手动打开抖音并进入目标直播间后再开始"
            )
        }

        val remaining = store.remainingToday(settings.dailyLimit)
        if (remaining <= 0) {
            throw FlowAbortException(
                "今日关注已达上限 ${settings.dailyLimit}（已关注 ${store.todayCount()}），明天再跑"
            )
        }
        val target = minOf(settings.followTarget.coerceAtLeast(1), remaining)
        ctx.publish(target = target, message = "准备就绪")
        LogStore.i(
            TAG,
            "开始：本轮目标 $target（界面设置 ${settings.followTarget} / 今日剩余 $remaining），" +
                "已关注名单 ${store.knownCount()} 人"
        )

        var followed = 0
        var dryRounds = 0
        val handled = HashSet<String>()

        while (followed < target) {
            if (ctx.stopped()) return
            ctx.awaitResume()
            ctx.guard()

            val candidates = collectCandidates(ctx, targets, store, handled)
            if (candidates.isEmpty()) {
                dryRounds++
                ctx.publish(message = "评论里暂时没有新面孔，等新评论进来（$dryRounds/$MAX_DRY_ROUNDS）")
                if (dryRounds >= MAX_DRY_ROUNDS) {
                    // 带上当前页面判定再报错，否则「采不到评论」这一个现象会指向两种完全不同的原因
                    val detection = ctx.detector.detect()
                    throw FlowAbortException(
                        "连续 $MAX_DRY_ROUNDS 次采集不到可关注的评论用户，已停止。" +
                            "当前页面判定 ${detection.page.name}（${detection.score}/${detection.threshold}）。" +
                            if (detection.page == PageState.LIVE_ROOM) {
                                "人在直播间但读不到评论，多半是 live_comment_nickname / live_comment_line " +
                                    "与当前抖音版本不匹配（见 docs/定位配置说明.md），也可能是这个直播间没人说话"
                            } else {
                                "已不在直播间（可能被误退出或被弹窗盖住），请重新进入目标直播间再开始"
                            }
                    )
                }
                // 刻意**不滑动**：抖音直播间里全屏上下滑是切换直播间，不是滚动评论列表，
                // 一滑就可能离开你选的直播间。直播评论本身在持续刷新，等着就行。
                ctx.settle(COMMENT_WAIT_MS)
                continue
            }
            dryRounds = 0

            if (!ctx.actions.isDouyinForeground()) {
                throw FlowAbortException("已经不在抖音前台（可能误退出了直播间），已停止")
            }

            // 随机挑，不按评论顺序从上往下点——顺序点是最容易被识别的机器特征
            val nickname = candidates.random()
            handled += nickname
            ctx.publish(message = "挑中「$nickname」")
            LogStore.i(TAG, "候选 ${candidates.size} 人，随机挑中「$nickname」")

            // 弹幕滚动很快，采集时的坐标早失效了；按昵称重新定位，找不到就丢掉这个人。
            // 必须用 contains：实测评论节点是「昵称：正文」整行，用等值匹配永远命中不了。
            val node = ctx.actions.find(Target(Target.Kind.TEXT_CONTAINS, nickname))
            if (node == null) {
                LogStore.w(TAG, "「$nickname」已滚出屏幕，跳过")
                continue
            }
            // 不能点整行：正文区点了不弹资料卡，必须落在行首昵称区
            if (!ctx.actions.tapNickname(node, "昵称「$nickname」")) {
                LogStore.w(TAG, "点击「$nickname」失败，跳过")
                continue
            }
            ctx.settle(TAP_SETTLE_MS)

            val card = awaitCard(ctx, targets, CARD_TIMEOUT_MS)
            when (card.state) {
                CardState.UNKNOWN -> {
                    LogStore.w(TAG, "点开「$nickname」后 ${CARD_TIMEOUT_MS}ms 内没等到弹层里的关注按钮，关掉重来")
                    closeCard(ctx, targets)
                    continue
                }

                CardState.ALREADY_FOLLOWED -> {
                    LogStore.i(TAG, "「$nickname」已经是关注状态，跳过并记入已关注名单")
                    store.markFollowed(nickname)
                    closeCard(ctx, targets)
                    continue
                }

                CardState.FOLLOWABLE -> {
                    val button = card.node
                    if (button == null || !ctx.actions.tapNode(button, "关注按钮")) {
                        LogStore.w(TAG, "点击「关注」失败：$nickname")
                        closeCard(ctx, targets)
                        continue
                    }

                    // 关键：必须等到界面真的变成「已关注」才算成功，不接受「点了就当成功」
                    val verified = awaitFollowedState(ctx, targets, VERIFY_TIMEOUT_MS)
                    closeCard(ctx, targets)
                    if (!verified) {
                        LogStore.w(
                            TAG,
                            "「$nickname」点关注后 ${VERIFY_TIMEOUT_MS}ms 内未变成「已关注」，" +
                                "按未成功处理，不计入进度"
                        )
                        continue
                    }

                    followed++
                    store.markFollowed(nickname)
                    val todayCount = store.bumpToday()
                    ctx.publish(followed = followed, target = target, message = "已关注「$nickname」")
                    LogStore.i(
                        TAG,
                        "已关注「$nickname」，进度 $followed/$target（今日 $todayCount/${settings.dailyLimit}）"
                    )

                    if (settings.intervalRest && settings.restAfterN > 0 &&
                        followed % settings.restAfterN == 0 && followed < target
                    ) {
                        ctx.publish(message = "已关注 $followed 个，按设置休息 ${settings.restSeconds}s")
                        LogStore.i(TAG, "到达休息点（每 ${settings.restAfterN} 个），休息 ${settings.restSeconds}s")
                        ctx.settle(settings.restSeconds * 1_000L)
                    } else {
                        ctx.settle(settings.scanIntervalMs.toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS))
                    }
                }
            }
        }

        ctx.publish(message = "已达本轮目标 $target")
        LogStore.i(TAG, "本轮完成：关注 $followed 人，达目标 $target")
    }

    // ==================== 候选采集 ====================

    /**
     * 等抖音真正到前台。
     *
     * 「开始」按钮在抖关关界面上，所以点击那一刻抖音必然在后台；
     * 启动流程时会把抖音拉到前台，但切进程 + resume 需要时间。
     * 直接判一次 isDouyinForeground() 必然为 false，会把每次正常启动都误杀成「抖音不在前台」。
     */
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
     * 三级采集：配置的主路径 → 整行文本冒号切分兜底 → 过滤。
     * 返回去重、过滤后的昵称列表；空列表代表「这一屏没有可用新人」。
     */
    private fun collectCandidates(
        ctx: FlowContext,
        targets: LiveTargets,
        store: FollowedStore,
        handled: Set<String>
    ): List<String> {
        var raw: List<String> = emptyList()
        var source = "主路径"
        if (targets.nickname != null) {
            raw = ctx.actions.collect(targets.nickname, MAX_COLLECT)
                .mapNotNull { ctx.actions.labelOf(it) }
        }
        if (raw.isEmpty() && targets.line != null) {
            source = "冒号切分兜底"
            raw = ctx.actions.collect(targets.line, MAX_COLLECT)
                .mapNotNull { ctx.actions.labelOf(it) }
                .mapNotNull { splitNickname(it) }
        }

        val filtered = raw.asSequence()
            .map { it.trim() }
            .filter { isValidNickname(it) }
            .filter { it !in ctx.config.excludeNicknames }
            .filter { it !in handled }
            .filter { !store.isFollowed(it) }
            .distinct()
            .toList()

        if (raw.isEmpty()) {
            LogStore.w(TAG, "本轮未采集到任何评论文本（来源：$source），可能是定位配置不匹配或评论区为空")
        } else {
            LogStore.d(
                TAG,
                "采集（$source）：原始 ${raw.size} 条 → 可用 ${filtered.size} 人" +
                    "（已排除：本轮已处理 ${handled.size}、历史已关注 ${store.knownCount()}、" +
                    "黑名单 ${ctx.config.excludeNicknames.size}）"
            )
        }
        return filtered
    }

    /** 整行形如「昵称：评论内容」，取冒号前的部分；没有冒号就不是评论行 */
    private fun splitNickname(line: String): String? {
        val cleaned = sanitize(line)
        val index = cleaned.indexOfFirst { it == '：' || it == ':' }
        if (index <= 0) return null
        return sanitize(cleaned.substring(0, index))
    }

    /**
     * 去掉不可见字符。
     *
     * 实测抖音的评论文本以 **U+200E（LEFT-TO-RIGHT MARK）** 开头（`‎紫丽丽：…`）。
     * 不清理会有两个后果：
     * 1. 昵称带着零宽字符落盘，同一人换条评论就可能被当成新面孔，去重失效；
     * 2. 用昵称回查节点时，字符串两边不一致导致定位不到。
     */
    private fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c.code < 0x20 -> {}                       // 控制字符
                c.code in 0x200B..0x200F -> {}             // 零宽空格 / LRM / RLM
                c.code in 0x202A..0x202E -> {}             // 方向控制
                c.code in 0x2060..0x2069 -> {}
                c.code == 0xFEFF -> {}                     // BOM
                else -> sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    private fun isValidNickname(name: String): Boolean {
        if (name.length !in MIN_NICK_LENGTH..MAX_NICK_LENGTH) return false
        if (name.all { it.isDigit() }) return false
        if (name.any { it == '：' || it == ':' || it == '\n' }) return false
        // 表情文本化后是「[调皮][比心]」这类方括号，昵称里不该出现
        if (name.startsWith("[") || name.endsWith("]")) return false
        // 系统提示会被误当成昵称（「xx 来了」「xx 关注了主播」），整条丢弃
        if (SYSTEM_MESSAGE_HINTS.any { name.contains(it) }) return false
        return true
    }

    // ==================== 弹层判定 ====================

    private enum class CardState { FOLLOWABLE, ALREADY_FOLLOWED, UNKNOWN }

    private class CardProbe(val state: CardState, val node: AccessibilityNodeInfo? = null)

    private suspend fun awaitCard(ctx: FlowContext, targets: LiveTargets, timeoutMs: Long): CardProbe {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return CardProbe(CardState.UNKNOWN)
            findCardButton(ctx, targets.followed)?.let { return CardProbe(CardState.ALREADY_FOLLOWED, it) }
            findCardButton(ctx, targets.follow)?.let { return CardProbe(CardState.FOLLOWABLE, it) }
            delay(CARD_POLL_MS)
        }
        return CardProbe(CardState.UNKNOWN)
    }

    private suspend fun awaitFollowedState(ctx: FlowContext, targets: LiveTargets, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (findCardButton(ctx, targets.followed) != null) return true
            delay(CARD_POLL_MS)
        }
        return false
    }

    /**
     * 在**弹层范围内**找一个按钮：排除屏幕顶部 [TOP_EXCLUDE_RATIO] 区域，取面积最大的那个。
     *
     * 为什么必须做位置过滤：抖音的半屏资料卡多数是 Activity 内的浮层而不是独立窗口，
     * `rootInActiveWindow` 会把直播间背景一起返回。而**主播自己也有一个「关注」按钮**
     * （通常在右上角头部）。如果不过滤，会有两个后果：
     * 1. 点到主播的关注按钮——关注了错的人；
     * 2. 更糟：主播若已关注，头部按钮显示「已关注」，会被误判成「这个候选人已关注」，
     *    于是**跳过所有人**，功能看起来「在跑但一个人也没关注」。
     *
     * 这是 M2 最容易出现的系统性误判，所以宁可写死一个位置假设，也要把两颗按钮分开。
     */
    private fun findCardButton(ctx: FlowContext, target: Target): AccessibilityNodeInfo? {
        val topLimit = (ctx.actions.screenHeight() * TOP_EXCLUDE_RATIO).toInt()
        return ctx.actions.collect(target, MAX_BUTTON_SCAN)
            .filter { node ->
                val bounds = ctx.actions.boundsOf(node)
                bounds.width() > 0 && bounds.height() > 0 && bounds.centerY() > topLimit
            }
            .maxByOrNull { node ->
                val bounds = ctx.actions.boundsOf(node)
                bounds.width().toLong() * bounds.height()
            }
    }

    // ==================== 收尾 ====================

    /**
     * 关掉资料卡/半屏弹层。
     *
     * **刻意不用配置里的「关闭」按钮**：实测直播间右上角有一颗 desc 为「关闭 按钮」的控件，
     * 那是**退出直播间**的。如果拿 descContains "关闭" 去点它，弹层一消失就会把人踢出房间，
     * 后面每一轮都在错误的页面上瞎跑。
     *
     * 现在改为：先确认弹层还在（还能找到关注/已关注按钮）→ 用系统返回关闭 → 再确认一次。
     * 弹层已经不在了就什么都不做，避免多余的返回把直播间退掉。
     */
    private suspend fun closeCard(ctx: FlowContext, targets: LiveTargets) {
        val stillOpen = findCardButton(ctx, targets.follow) != null ||
            findCardButton(ctx, targets.followed) != null
        if (!stillOpen) {
            LogStore.d(TAG, "弹层已不在，跳过关闭动作")
            return
        }
        if (!ctx.actions.back()) {
            LogStore.w(TAG, "返回键未生效，弹层可能仍在（下一轮会重新判断）")
        }
        ctx.settle(CLOSE_SETTLE_MS)
    }

    /** 从配置里取出本流程要用的目标，缺关键项直接中止（不做静默降级） */
    private class LiveTargets(
        val nickname: Target?,
        val line: Target?,
        val follow: Target,
        val followed: Target
    ) {
        companion object {
            fun from(config: LocatorConfig): LiveTargets {
                val follow = config.target("follow_button")
                    ?: throw FlowAbortException("定位配置缺少 follow_button，无法判断关注按钮")
                val followed = config.target("followed_button")
                    ?: throw FlowAbortException("定位配置缺少 followed_button，无法校验关注结果")
                val nickname = config.target("live_comment_nickname")
                val line = config.target("live_comment_line")
                if (nickname == null && line == null) {
                    throw FlowAbortException(
                        "定位配置缺少 live_comment_nickname 与 live_comment_line，无法采集评论用户"
                    )
                }
                return LiveTargets(
                    nickname = nickname,
                    line = line,
                    follow = follow,
                    followed = followed
                )
            }
        }
    }

    private companion object {
        const val TAG = "直播间关注"

        const val MAX_COLLECT = 12
        const val MAX_BUTTON_SCAN = 8
        const val MAX_DRY_ROUNDS = 4
        const val MIN_GAP_MS = 600L
        const val MAX_GAP_MS = 15_000L

        const val TAP_SETTLE_MS = 600L
        const val COMMENT_WAIT_MS = 2_500L
        const val CLOSE_SETTLE_MS = 500L
        const val CARD_TIMEOUT_MS = 6_000L
        const val VERIFY_TIMEOUT_MS = 3_000L
        const val CARD_POLL_MS = 250L

        /** 等抖音切到前台的初始等待：冷启动抖音可能要几秒 */
        const val DOUYIN_FOREGROUND_TIMEOUT_MS = 10_000L
        const val FRONT_POLL_MS = 300L
        const val FRONT_SETTLE_MS = 900L

        /** 屏幕顶部这个比例内出现的「关注」按钮属于直播间头部（主播的），不属于弹层 */
        const val TOP_EXCLUDE_RATIO = 0.30f

        const val MIN_NICK_LENGTH = 2
        const val MAX_NICK_LENGTH = 24

        val SYSTEM_MESSAGE_HINTS = listOf(
            "来了", "关注了主播", "送出", "点亮", "升级", "加入粉丝团", "为主播", "分享了直播间"
        )
    }
}
