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
import com.example.dgg.util.Texts
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
        var reusedHits = 0
        var waitedRounds = 0
        val handled = HashSet<String>()

        /**
         * 上一轮评论区**最底那一行**的文案（= 当时最新的一条评论）。
         *
         * 直播间评论是自下往上堆的，所以「列表有没有推进到最新」只能靠「底行有没有换人」判断。
         * 这是本轮新增的「追最新」锚点：每关注一个人要花 6 秒以上（点开资料卡 → 点关注 → 校验），
         * 回来时列表很可能还停在刚才那一屏，不追就会一直从几分钟前的旧评论里挑人。
         */
        var anchor: String? = null

        while (followed < target) {
            if (ctx.stopped()) return
            ctx.awaitResume()
            ctx.guard()

            // 点评论有几率误开礼物面板/操作栏之类的浮层（尤其点到滚动中的失效坐标），
            // 一旦盖上，后面所有采集都是在浮层上做的，表现为「整页 UNKNOWN 然后空转」。
            // 每轮先确认还在直播间，不在就按一次返回把它关掉。
            ensureLiveRoom(ctx)

            // 追最新：等「列表底部那一行」换人，说明列表已经滚到了比上一轮更新的评论。
            // **不能用滑动去滚**——实测在评论区里做 550px 纵向滑动会直接切到下一个直播间
            // （主播从「毛里求斯黑猩猩」变成「迅猛龙特…」），短滑又不产生任何位移：
            // 这套列表不可滚动，只能等它自己往上滚。
            val latest = if (anchor != null) {
                awaitNewestChanged(ctx, targets, anchor, WAIT_NEW_COMMENT_MS)
            } else {
                null
            }
            if (latest != null) {
                if (latest != anchor) {
                    waitedRounds++
                    LogStore.d(TAG, "评论区已推进到新评论，按最新一屏采集")
                } else {
                    LogStore.w(
                        TAG,
                        "评论区 ${WAIT_NEW_COMMENT_MS}ms 内没有新评论（房间可能很安静），" +
                            "退化为「当前屏最新处」取人"
                    )
                }
                // 点击前把此刻的最新一行记为下一轮的基准（省掉一次多余的遍历）
                anchor = latest
            } else {
                anchor = newestRow(ctx, targets)?.label
            }

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

            // 只在**最新的几个人**里随机挑，不再对整屏候选随机。
            //
            // 之前是对全部候选 random()，屏幕上一屏能挂 9 条评论（实测 y 1195~2170，
            // 覆盖好几分钟），关注完第一个之后经常又从几分钟前的旧评论里挑人，
            // 观感就是「一直在补关注早就不在看的人」。
            // candidates 已按「新→旧」排好序，前 NEWEST_WINDOW 个就是屏幕上最新的可用人；
            // 若最新的几个都已关注过（被过滤掉了），列表自然往后顺延，不会卡住。
            val window =
                if (candidates.size > NEWEST_WINDOW) candidates.subList(0, NEWEST_WINDOW) else candidates
            val pick = window.random()
            val nickname = pick.name
            handled += nickname
            ctx.publish(message = "挑中「$nickname」")
            LogStore.i(
                TAG,
                "候选 ${candidates.size} 人（最新窗口 ${window.size} 人：" +
                    window.joinToString("、") { it.name } + "），随机挑中「$nickname」"
            )

            // 优先复用采集时那个节点（省掉一次整树回查），失效了才按昵称回查。
            // 必须用 contains：实测评论节点是「昵称：正文」整行，用等值匹配永远命中不了。
            val node = resolveCandidate(ctx, pick)
            if (node == null) {
                LogStore.w(TAG, "「$nickname」已滚出屏幕，跳过")
                continue
            }
            reusedHits += if (node === pick.node) 1 else 0
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
        LogStore.i(
            TAG,
            "本轮完成：关注 $followed 人，达目标 $target" +
                "（直接复用采集节点 $reusedHits 次，等新评论 $waitedRounds 次）"
        )
    }

    // ==================== 追最新 ====================

    /** 评论区里的一行：整行文案 + 屏幕下边缘（y 越大越新） */
    private class Row(val label: String, val bottom: Int)

    /**
     * 取当前屏**最底（最新）**的那一行评论。
     *
     * 走快照在内存里比对，避免为了一个问题再跑一趟全树遍历。
     * 这里刻意不套用任何业务过滤（已关注/黑名单），因为它的用途是「判断列表有没有动」，
     * 锚点必须是屏幕上真实的最底一行，而不是「最底一个还没关注的人」。
     */
    private fun newestRow(ctx: FlowContext, targets: LiveTargets): Row? {
        val target = targets.nickname ?: targets.line ?: return null
        // ttl = 0：要的是「此刻」的界面，不能吃缓存
        val facts = ctx.actions.snapshot(ttlMs = 0)
        var best: Row? = null
        for (f in facts) {
            if (!ctx.actions.matchesFacts(f, target)) continue
            val label = (f.text ?: f.desc)?.trim() ?: continue
            if (label.isEmpty()) continue
            val current = best
            if (current == null || f.bottom > current.bottom) best = Row(label, f.bottom)
        }
        return best
    }

    /**
     * 等评论区「滚到最新」。
     *
     * 判据是**最底那一行换人**：列表满屏时每行的 y 是固定的（旧行被顶掉、新行补在底部），
     * 所以「y 变大」不是有效信号；而每一行的文案包含评论正文，换人必然换文案。
     *
     * @return 退出时看到的最新一行文案（null = 一直读不到评论行）；
     *         与传入的 [anchor] 不同即代表列表已推进，相同代表超时未推进
     */
    private suspend fun awaitNewestChanged(
        ctx: FlowContext,
        targets: LiveTargets,
        anchor: String,
        timeoutMs: Long
    ): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return last
            val newest = newestRow(ctx, targets)
            if (newest != null) {
                last = newest.label
                if (newest.label != anchor) {
                    LogStore.d(
                        TAG,
                        "评论区底行已换：「${anchor.take(18)}」→「${newest.label.take(18)}」"
                    )
                    return last
                }
            }
            delay(NEW_COMMENT_POLL_MS)
        }
        return last
    }

    /**
     * 评论行可点的下边界：输入栏顶再往上留一点。
     *
     * 实测 40.8.0：输入框 `[72,2188][480,2302]`，最新一行评论底部约 2170。
     * 追最新之后挑中的就是最底部那几行，离输入栏只有十几像素，
     * 不设这条线的话点歪了会直接弹键盘（把输入区当成评论行点）。
     *
     * 走快照而不是 `find`：输入栏的位置在一轮里不会变，没必要为它再跑一趟全树遍历。
     */
    private fun rowBottomLimit(ctx: FlowContext, targets: LiveTargets): Int {
        val input = targets.input
        if (input != null) {
            val tops = ctx.actions.snapshot(ttlMs = INPUT_BAR_CACHE_MS)
                .filter { ctx.actions.matchesFacts(it, input) && it.top > 0 }
                .map { it.top }
            if (tops.isNotEmpty()) return tops.min() - ROW_LIMIT_MARGIN
        }
        return (ctx.actions.screenHeight() * FALLBACK_ROW_LIMIT_RATIO).toInt()
    }

    // ==================== 候选采集 ====================

    /** 一个候选人：昵称 + 采集时那个节点（为 null 表示只能靠回查）+ 所在行的下边缘（y 越大越新） */
    private class Candidate(
        val name: String,
        val node: AccessibilityNodeInfo?,
        val bottomY: Int
    )

    /**
     * 把「挑中的候选人」变成「现在能点的节点」。
     *
     * 1. 先验采集时那个节点还能不能用（refresh + 仍在屏上 + 标签没被 RecyclerView 复用换人）；
     * 2. 用不了才按昵称回查整棵树——这条路安全但慢，评论刷新快时经常白跑。
     *
     * 两者都失败就返回 null，调用方跳过这个人（不猜坐标、不重试）。
     */
    private fun resolveCandidate(ctx: FlowContext, cand: Candidate): AccessibilityNodeInfo? {
        val held = cand.node
        if (held != null) {
            if (ctx.actions.stillValid(held, cand.name)) {
                LogStore.d(TAG, "复用采集节点：「${cand.name}」（省掉一次全树回查）")
                return held
            }
            LogStore.d(TAG, "采集节点已失效/已被复用，按昵称回查「${cand.name}」")
        }
        return ctx.actions.find(Target(Target.Kind.TEXT_CONTAINS, cand.name))
    }

    /**
     * 确认当前确实在直播间；被浮层盖住就按一次返回尝试关掉最上面一层。
     *
     * 两条保护（M3 实测补上）：
     * 1. **一次 UNKNOWN 不算数**：收键盘/窗口切换的中间态会让某一刻读不到应用窗口，
     *    单次误判就按返回会把好好的直播间退掉；
     * 2. **前台不是抖音就绝不按返回**，否则退的是别的应用。
     *
     * 确认不在之后只按一次，不做循环：真的退出直播间时反复返回会把抖音一层层退到底，
     * 反而更难恢复。剩下的交给 dryRounds 中止。
     *
     * @return 恢复后是否在直播间
     */
    private suspend fun ensureLiveRoom(ctx: FlowContext): Boolean {
        repeat(CONFIRM_CHECKS) { i ->
            ctx.actions.invalidateSnapshot()
            if (ctx.detector.detect().page == PageState.LIVE_ROOM) return true
            if (i < CONFIRM_CHECKS - 1) delay(RECHECK_MS)
        }

        if (!ctx.actions.isDouyinForeground()) {
            LogStore.w(TAG, "连续 $CONFIRM_CHECKS 次读不到直播间，且前台不是抖音——不按返回")
            return false
        }

        LogStore.w(TAG, "连续 $CONFIRM_CHECKS 次不在直播间，按一次返回尝试关掉浮层")
        ctx.actions.back()
        ctx.settle(RECOVER_SETTLE_MS)
        ctx.actions.invalidateSnapshot()
        return ctx.detector.detect().page == PageState.LIVE_ROOM
    }

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
     * 返回去重、过滤后的候选人（**带节点**，供点击前复用）；空列表代表「这一屏没有可用新人」。
     */
    private fun collectCandidates(
        ctx: FlowContext,
        targets: LiveTargets,
        store: FollowedStore,
        handled: Set<String>
    ): List<Candidate> {
        var raw: List<Pair<String, AccessibilityNodeInfo>> = emptyList()
        var source = "主路径"
        if (targets.nickname != null) {
            raw = ctx.actions.collect(targets.nickname, MAX_COLLECT)
                .mapNotNull { node -> ctx.actions.labelOf(node)?.let { label -> label to node } }
        }
        if (raw.isEmpty() && targets.line != null) {
            source = "冒号切分兜底"
            raw = ctx.actions.collect(targets.line, MAX_COLLECT)
                .mapNotNull { node ->
                    val label = ctx.actions.labelOf(node) ?: return@mapNotNull null
                    splitNickname(label)?.let { name -> name to node }
                }
        }

        val limit = rowBottomLimit(ctx, targets)
        val best = LinkedHashMap<String, Candidate>(raw.size)
        var skippedLow = 0
        for ((rawName, node) in raw) {
            val name = rawName.trim()
            when {
                !isValidNickname(name) -> continue
                name in ctx.config.excludeNicknames -> continue
                name in handled -> continue
                store.isFollowed(name) -> continue
            }
            val bottom = ctx.actions.boundsOf(node).bottom
            // 压到输入栏上的行不点：追最新之后挑中的就是最底部那几行，点歪会弹键盘
            if (bottom > limit) {
                skippedLow++
                continue
            }
            // 同一个人连发几条评论时保留**最新**那一条（bottom 最大），不是第一次出现的那条
            val existing = best[name]
            if (existing == null || bottom > existing.bottomY) {
                best[name] = Candidate(name, node, bottom)
            }
        }
        // 从新到旧：调用方只取最前面几个，就能保证挑的是屏幕上最新的评论者
        val filtered = best.values.sortedByDescending { it.bottomY }

        if (raw.isEmpty()) {
            LogStore.w(TAG, "本轮未采集到任何评论文本（来源：$source），可能是定位配置不匹配或评论区为空")
        } else {
            LogStore.d(
                TAG,
                "采集（$source）：原始 ${raw.size} 条 → 可用 ${filtered.size} 人" +
                    "（已排除：本轮已处理 ${handled.size}、历史已关注 ${store.knownCount()}、" +
                    "黑名单 ${ctx.config.excludeNicknames.size}、压输入栏 $skippedLow）" +
                    "；最新 3 人 ${filtered.take(3).joinToString("、") { "${it.name}@y${it.bottomY}" }}"
            )
        }
        return filtered
    }

    /** 整行形如「昵称：评论内容」，取冒号前的部分；没有冒号就不是评论行 */
    private fun splitNickname(line: String): String? = Texts.beforeColon(line)

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
        // 点评论行有两种结果：直接弹资料卡，或弹出快捷操作栏（资料卡/喜欢/不喜欢/@TA/更多）。
        // 后者是临时浮层，几秒后自己消失，不点「资料卡」就永远等不到关注按钮。
        // 这里只兜一次，避免在两个浮层之间来回点。
        var triedProfileEntry = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return CardProbe(CardState.UNKNOWN)
            if (isAlreadyFollowed(ctx, targets)) return CardProbe(CardState.ALREADY_FOLLOWED)
            findCardButton(ctx, targets.follow)?.let { return CardProbe(CardState.FOLLOWABLE, it) }

            if (!triedProfileEntry) {
                val entry = ctx.actions.find(Target(Target.Kind.TEXT_CONTAINS, PROFILE_ENTRY_TEXT))
                if (entry != null) {
                    triedProfileEntry = true
                    LogStore.i(TAG, "点评论后出现的是快捷操作栏，改点「$PROFILE_ENTRY_TEXT」进资料卡")
                    ctx.actions.tapNode(entry, PROFILE_ENTRY_TEXT)
                    // 资料卡是滑入动画，~500ms 时按钮还在移动：这时读到的 bounds 拿去点会点空，
                    // 表现为「点了关注但状态没变」（实测一次：3s 校验失败、界面其实已经关注成功）。
                    delay(CARD_ENTRY_SETTLE_MS)
                    continue
                }
            }
            delay(CARD_POLL_MS)
        }
        return CardProbe(CardState.UNKNOWN)
    }

    private suspend fun awaitFollowedState(ctx: FlowContext, targets: LiveTargets, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (isAlreadyFollowed(ctx, targets)) return true
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

    /**
     * 判断「当前弹层里的按钮已经是关注状态」。
     *
     * 配置里的 `followed_button` 是主判据（40.8 实测为「取消关注」），
     * 但抖音还有第三种态：对方也关注了你，按钮显示「互相关注」——
     * 既不是「关注」也不是「取消关注」。
     *
     * 这一层兜底不是锦上添花，是**防止取关**：资料卡里那个 `text="关注"` 的残留节点
     * 在已关注后仍然存在于节点树上，如果这里漏判，就会走到 FOLLOWABLE 分支去点
     * 「关注按钮」，而那个坐标正好落在「取消关注」上——把已经关注的人取关。
     * 所以宁可多重判一次已关注，也不能漏。
     *
     * **走快照而不是逐个文案 collect**：这里原来对 3 个文案各跑一趟全树遍历（1.4s/趟），
     * 一次判断要 4 秒多，而校验窗口只有 3 秒——经常一次都没采到就判「没变成已关注」，
     * 实测表现为「界面明明弹出关注成功，进度却不涨」。现在一趟遍历 + 内存比对。
     */
    private fun isAlreadyFollowed(ctx: FlowContext, targets: LiveTargets): Boolean {
        val topLimit = (ctx.actions.screenHeight() * TOP_EXCLUDE_RATIO).toInt()
        val labels = ALREADY_FOLLOWED_TEXTS + targets.followed.value
        // ttl = 0：校验期间必须读「此刻」的界面，不能吃 700ms 缓存
        return ctx.actions.snapshot(ttlMs = 0).any { fact ->
            val text = fact.text?.trim()
            !text.isNullOrEmpty() && fact.bottom > topLimit && labels.contains(text)
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
            isAlreadyFollowed(ctx, targets)
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
        val followed: Target,
        /** 评论输入区，用来量出「评论行的下边界」，避免点评论时点到输入框 */
        val input: Target?
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
                    followed = followed,
                    input = config.target("comment_input")
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

        /**
         * 只在最新的这么多个**可用**候选里随机挑人。
         *
         * 一屏能挂 9 条评论（实测 y 1195~2170，覆盖好几分钟），整屏随机会让后半程
         * 一直在补关注几分钟前的人。取 3 既保证「挑的是刚说话的」，也保留随机性。
         */
        const val NEWEST_WINDOW = 3

        /**
         * 等「评论区底部换行」的超时。
         *
         * 热闹的直播间 1 秒内就会有新评论，等待几乎不花时间；
         * 冷清房间没人说话时不该一直干等，6 秒后降级为「当前屏最新处」取人。
         */
        const val WAIT_NEW_COMMENT_MS = 6_000L
        const val NEW_COMMENT_POLL_MS = 300L

        /** 评论行下边界 = 输入栏顶 - 这个余量（实测最新一行底部 2170、输入栏顶 2188） */
        const val ROW_LIMIT_MARGIN = 8

        /** 输入栏位置在一轮里不会变，量它时允许吃几秒前的快照，省一趟全树遍历 */
        const val INPUT_BAR_CACHE_MS = 5_000L

        /** 拿不到输入栏位置时按屏高比例兜底（实测输入栏顶 ≈ 屏高 91%） */
        const val FALLBACK_ROW_LIMIT_RATIO = 0.92

        const val TAP_SETTLE_MS = 600L
        const val COMMENT_WAIT_MS = 2_500L
        const val CLOSE_SETTLE_MS = 500L

        /** 关掉意外弹出的浮层后，等界面稳定再重新判定 */
        const val RECOVER_SETTLE_MS = 900L

        /** 判「不在直播间」要连续确认几次；一次不算数（收键盘等中间态会读到空窗口） */
        const val CONFIRM_CHECKS = 2
        const val RECHECK_MS = 500L
        const val CARD_TIMEOUT_MS = 6_000L
        /**
         * 等「取消关注」出现的窗口。
         *
         * 一次快照遍历约 1.4s，3s 只有两次采样，而实测按钮状态刷新常常要 1.5~4s，
         * 于是出现「画面弹出关注成功、进度却不涨」的假失败。放宽到 6s（采样 4 次）。
         */
        const val VERIFY_TIMEOUT_MS = 6_000L
        const val CARD_POLL_MS = 250L

        /** 点「资料卡」后等卡片滑入动画结束，避免拿动画中的 bounds 去点关注 */
        const val CARD_ENTRY_SETTLE_MS = 900L

        /**
         * 等抖音切到前台的初始等待。
         *
         * 冷启动实测要 10s 以上（Splash → 首页 → 恢复上次的 LivePlayActivity），
         * 给 10s 会在正常启动时误杀成「未切到抖音前台」。这里放宽到 20s，
         * 反正等到了就立刻继续，不会白白多等。
         */
        const val DOUYIN_FOREGROUND_TIMEOUT_MS = 20_000L
        const val FRONT_POLL_MS = 300L
        const val FRONT_SETTLE_MS = 900L

        /** 屏幕顶部这个比例内出现的「关注」按钮属于直播间头部（主播的），不属于弹层 */
        const val TOP_EXCLUDE_RATIO = 0.30f

        const val MIN_NICK_LENGTH = 2
        const val MAX_NICK_LENGTH = 24

        val SYSTEM_MESSAGE_HINTS = listOf(
            "来了", "关注了主播", "送出", "点亮", "升级", "加入粉丝团", "为主播", "分享了直播间"
        )

        /**
         * 已关注态的按钮文案（抖音 40.8 实测）：
         * 「取消关注」= 你关注了他；「互相关注」= 双方互关。
         * 配置里的 followed_button 恒为「取消关注」，这里再兜一层互关。
         */
        val ALREADY_FOLLOWED_TEXTS = listOf("取消关注", "互相关注")

        /**
         * 点评论行有时弹出的是快捷操作栏而不是资料卡，栏里这一项才是资料卡入口。
         * 这是实测（40.8.0）的文案，改版若变化会表现为「点开后等不到关注按钮」。
         */
        const val PROFILE_ENTRY_TEXT = "资料卡"
    }
}
