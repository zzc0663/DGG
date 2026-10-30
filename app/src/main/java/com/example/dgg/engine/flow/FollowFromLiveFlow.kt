package com.example.dgg.engine.flow

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.dgg.data.FollowedStore
import com.example.dgg.data.LogStore
import com.example.dgg.data.RoomStore
import com.example.dgg.data.Settings
import com.example.dgg.util.CurrentRoomAnchor
import com.example.dgg.data.SettingsStore
import com.example.dgg.engine.Actions
import com.example.dgg.engine.Flow
import com.example.dgg.engine.FlowAbortException
import com.example.dgg.engine.FlowContext
import com.example.dgg.engine.LocatorConfig
import com.example.dgg.engine.PageState
import com.example.dgg.engine.Target
import com.example.dgg.util.Texts
import kotlinx.coroutines.delay
import kotlin.random.Random

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

        // 「选择直播间」的一次性指令：用户在悬浮面板点选了收藏的房间，
        // 先自动导航进去再开始干活。失败明确停机——绝不退化成随便进一个直播间。
        RoomStore.get(ctx.appContext).takePendingTarget()?.let { room ->
            ctx.publish(message = "正在进入收藏的直播间「$room」")
            LogStore.i(TAG, "检测到待进入的收藏直播间「$room」，先导航")
            if (!EnterSavedRoomNavigator.enter(ctx, room)) {
                throw FlowAbortException(
                    "未能自动进入收藏的直播间「$room」（原因见上方日志）。" +
                        "可改为手动进入直播间后重新开始，或检查主播是否在开播"
                )
            }
        }

        val remaining = store.remainingToday(settings.dailyLimit)
        if (remaining <= 0) {
            throw FlowAbortException(
                "今日关注已达上限 ${settings.dailyLimit}（已关注 ${store.todayCount()}），明天再跑"
            )
        }
        // 目标数在循环里**每轮重读**：悬浮面板上的「设置关注」要实时生效，
        // 不能要求用户停止任务再改。启动时只校验一次额度。
        var target = minOf(settings.followTarget.coerceAtLeast(1), remaining)
        ctx.publish(target = target, message = "准备就绪")
        LogStore.i(
            TAG,
            "开始：本轮目标 $target（界面设置 ${settings.followTarget} / 今日剩余 $remaining），" +
                "已关注名单 ${store.knownCount()} 人"
        )

        var followed = 0
        var reusedHits = 0
        var waitedRounds = 0
        var waitedMsTotal = 0L
        val handled = HashSet<String>()

        /**
         * 评论行文案 → **首次见到它的时刻**。
         *
         * 这是「列表有没有新评论进来」的唯一判据，跨轮保留（见 [scanOnce]）。
         * 为什么不用「最底那一行换人了没」：真机日志证明这个信号不可靠——
         * 底行会因为插入动画/节点复用短暂读不到（实测 9 行在一次采样里掉到 7 行），
         * 于是锚点会在「最底行」和「倒数第二行」之间来回跳，既会误判成"已推进"，
         * 也会在列表真的推进时判成"没动"。按"这一行我从没见过"判断，两种错都不会有。
         */
        val seenRows = HashMap<String, Long>()

        /** 只有第一轮允许「首页→直播页签→进房」的自动导航，理由见主循环里的注释 */
        var firstEnsure = true

        /**
         * 上一次读到的「界面设置目标数」。
         *
         * 实时目标只认**这个字段的变化**（用户在悬浮面板上改了）。不能拿
         * `remainingToday` 参与比较——它随每次关注成功递减，拿它比会让每一轮都
         * 误触发一次「目标数已调整」，日志刷屏还把真正的人工修改淹没掉。
         */
        var lastSeenTargetSetting = settings.followTarget
        var lastSeenDailyLimit = settings.dailyLimit

        while (followed < target) {
            if (ctx.stopped()) return
            ctx.awaitResume()
            ctx.guard()

            // 实时目标与上限：界面/面板上改了立刻生效（调小会在达到后自然结束，调大继续跑）
            val fresh = SettingsStore.get(ctx.appContext).current
            if (fresh.followTarget != lastSeenTargetSetting || fresh.dailyLimit != lastSeenDailyLimit) {
                lastSeenTargetSetting = fresh.followTarget
                lastSeenDailyLimit = fresh.dailyLimit
                val remainingNow = store.remainingToday(fresh.dailyLimit)
                if (remainingNow <= 0) {
                    // 上限被调到今日已关注数以下：立刻停，不要带着 0 目标空转
                    LogStore.i(
                        TAG,
                        "每日上限已调为 ${fresh.dailyLimit}，今日已关注 ${store.todayCount()}，停止"
                    )
                    ctx.publish(message = "已达每日上限 ${fresh.dailyLimit}")
                    break
                }
                val newTarget = minOf(fresh.followTarget.coerceAtLeast(1), remainingNow)
                if (newTarget != target) {
                    LogStore.i(TAG, "目标数已从 $target 调整为 $newTarget（界面实时修改）")
                    target = newTarget
                    ctx.publish(target = target, message = "目标数已改为 $newTarget")
                    if (followed >= target) break
                }
            }

            // 每轮先确认「还在抖音的直播间里」，做不到就尽力恢复：
            // 人为切换直播间（转场期间读不到直播间特征）会耐心等新房间，
            // 退出抖音会被自动拉回，真的恢复不了才中止。
            // 早期这里是「一 UNKNOWN 就按返回」，结果把用户刚切到的直播间退掉了。
            // 第一次调用允许自动导航（首页→直播页签→进房）：用户刚点了「开始服务」，
            // 明确要让脚本自己进直播间；之后的轮次不导航（跑一半在首页=用户想干别的，不抢）。
            if (!LiveGuard.ensureOnAirOrWait(ctx, allowAutoNavigate = firstEnsure)) {
                val detection = ctx.detector.detect()
                throw FlowAbortException(
                    "已经不在直播间，且自动恢复失败。" +
                        "当前页面判定 ${detection.page.name}（${detection.score}/${detection.threshold}），" +
                        "前台${if (ctx.actions.isDouyinForeground()) "是抖音但不在直播间" else "已不是抖音"}。" +
                        "请打开抖音并停在目标直播间后重新开始"
                )
            }
            // 已经确认站进直播间了：顺手预热评论容器节点。
            // 这一趟约 1 秒，但发生在「开始任务」那一瞬间（用户预期本来就要等），
            // 换来的是之后每次「关注后下滑」都能命中缓存、毫秒级完成。
            if (firstEnsure) warmUpCommentList(ctx)
            firstEnsure = false

            // 记住当前主播昵称：悬浮面板「收藏直播间」在面板展开时读不到实时树，
            // 用的就是这里每轮刷新的值（走快照缓存，无额外遍历）
            CurrentRoomAnchor.anchorFromSnapshot(ctx.actions)?.let {
                if (it != RoomStore.get(ctx.appContext).lastAnchor) {
                    RoomStore.get(ctx.appContext).rememberAnchor(it)
                    LogStore.d(TAG, "当前直播间主播：$it")
                }
            }

            // 开始等新评论之前先确认前台还在抖音：等待可能长达几分钟，
            // 用户切走（回消息、来电）时不该继续在这棵树上读，更不能等完再盲点。
            if (!LiveGuard.ensureDouyinForeground(ctx)) {
                throw FlowAbortException("抖音已不在前台，且自动拉回失败，已停止（不会盲点）")
            }

            // 追最新：**只认本轮首见的评论行**；没有新评论就等，绝不把窗口下滑到更旧的行。
            // 不能靠滑动去滚（550px 会切到下一个直播间、120px 没有位移，见 docs/M2 §12.2），
            // 所以「追最新」只能实现成「等列表自己滚到最新」——这里修的就是"等得够不够准"。
            val scan = awaitFreshScan(
                ctx, targets, store, handled, seenRows, lastSeenTargetSetting, lastSeenDailyLimit
            ) ?: return
            // 等待期间用户改了目标数/上限：回到循环顶部，那里会重读设置并刷新目标，
            // 调小到已达标也会立刻结束。不能把这次"空扫描"当成"没新评论"继续等。
            if (scan.settingsChanged) continue
            if (scan.waitedMs > 2_000) {
                waitedRounds++
                waitedMsTotal += scan.waitedMs
            }
            val candidates = scan.candidates

            // 只在**最新的几个人**里挑，不再对整屏候选随机。
            //
            // 一屏能挂 9 条评论（实测 y 1398~2090，覆盖好几分钟），对整屏 random() 的结果
            // 就是「关注完第一个之后一直在补关注几分钟前的人」。
            // candidates 已按「新→旧」排好序，前 NEWEST_WINDOW 个就是屏幕上最新的可用人；
            // 注意它**不会再往后顺延到旧行**——`awaitFreshScan` 只返回本轮首见的行，
            // 首见的人若都处理过，就继续等新评论（安静房间等几分钟是常态，如实上报）。
            val window =
                if (candidates.size > NEWEST_WINDOW) candidates.subList(0, NEWEST_WINDOW) else candidates
            // 加权而不是等概率：最新那一行权 3、第二 2、第三 1。
            // 用户要的是「刚说话的人」，等概率三选一有 2/3 概率挑到不是最新的那个，观感就是「又在挑老的」；
            // 但也不能写死「永远挑最底那条」——那是很明显的机器特征。权重随机两头都照顾到。
            val pick = pickWeighted(window)
            val nickname = pick.name
            handled += nickname
            ctx.publish(message = "挑中「$nickname」")
            LogStore.i(
                TAG,
                "候选 ${candidates.size} 人（最新窗口 ${window.size} 人：" +
                    window.joinToString("、") { "${it.name}@y${it.bottomY}" } +
                    "），挑中「$nickname」（y=${pick.bottomY}）"
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
                    // 刚在弹层里读到「已关注」，弹层必然还在，不必再探测
                    closeCard(ctx, targets, knownOpen = true)
                    // 关掉弹层后评论区同样脱离了底部，顺手拉回最新，
                    // 免得下一次扫描还在看这几个已经关注过的人
                    refreshComments(ctx, "跳过已关注的「$nickname」")
                    continue
                }

                CardState.PRIVATE -> {
                    // 点关注**之前**就认出是私密账号（卡里带「私密账号」标记或已经是「申请关注」态）：
                    // 连那一次点击都不做，直接下一个人。
                    skipPrivate(ctx, targets, store, nickname, card.evidence)
                    continue
                }

                CardState.FOLLOWABLE -> {
                    val button = card.node
                    if (button == null || !ctx.actions.tapNode(button, "关注按钮")) {
                        LogStore.w(TAG, "点击「关注」失败：$nickname")
                        closeCard(ctx, targets)
                        continue
                    }

                    // 关键：必须等到界面真的变成「已关注」才算成功，不接受「点了就当成功」。
                    // 但私密账号是**例外中的例外**——它永远不会变成已关注，只会变成申请态，
                    // 所以要在同一趟判据里先认出来，认出即返回，不等满 6 秒。
                    // 传 button：优先「刷新这颗按钮读文案」（毫秒级），而不是每轮重走一遍全树。
                    val outcome = awaitFollowOutcome(ctx, targets, VERIFY_TIMEOUT_MS, button)
                    when (outcome.state) {
                        CardState.PRIVATE -> {
                            // 关注动作已经发出去了（申请），但粉丝数不会增加、额度也白占。
                            // 不计进度、不写已关注名单，改记进私密名单，下一轮直接跳过这个人。
                            skipPrivate(ctx, targets, store, nickname, outcome.evidence)
                            continue
                        }

                        CardState.ALREADY_FOLLOWED -> Unit // 真的关注成功，落到下面的成功分支

                        else -> {
                            // 既没变成「已关注」，也不是已知的申请态：可能是没收录的新文案。
                            // 把弹层里所有短文案打进日志——这是唯一能把"未知文案"变成证据的地方，
                            // 下次照着补进 privateAccountHints 就行。
                            LogStore.w(
                                TAG,
                                "「$nickname」点关注后 ${VERIFY_TIMEOUT_MS}ms 内未变成「已关注」，" +
                                    "按未成功处理，不计入进度；弹层文案：" + cardLabelsDump(ctx)
                            )
                            closeCard(ctx, targets)
                            continue
                        }
                    }
                    // 弹层一定是开的：上一行 awaitFollowOutcome 刚在里面读到「已关注」。
                    closeCard(ctx, targets, knownOpen = true)

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
                        // 休息前也先把评论区拉回最新：要歇几分钟，不能让列表停在旧位置
                        // （列表一旦贴着最新，休息期间新评论会自己滚上来）
                        waitAfterFollow(ctx, 0L, "关注「$nickname」成功") { false }
                        ctx.publish(message = "已关注 $followed 个，按设置休息 ${settings.restSeconds}s")
                        LogStore.i(TAG, "到达休息点（每 ${settings.restAfterN} 个），休息 ${settings.restSeconds}s")
                        ctx.settle(settings.restSeconds * 1_000L)
                    } else {
                        // 间隔可能长达 60 分钟：分片等待，暂停/停止才收得到。
                        // 每片顺便看一眼设置：用户在等待期间改了目标数就提前结束，
                        // 不然他要等满这一小时才看到改动生效。
                        waitAfterFollow(ctx, settings.scanIntervalMs, "关注「$nickname」成功") {
                            val now = SettingsStore.get(ctx.appContext).current
                            now.followTarget != lastSeenTargetSetting ||
                                now.dailyLimit != lastSeenDailyLimit
                        }
                    }
                }
            }
        }

        ctx.publish(message = "已达本轮目标 $target")
        LogStore.i(
            TAG,
            "本轮完成：关注 $followed 人，达目标 $target" +
                "（其中 $waitedRounds 轮为新评论等待过，累计 ${waitedMsTotal / 1000}s；" +
                "直接复用采集节点 $reusedHits 次）"
        )
    }

    // ==================== 追最新 ====================

    /** 一次扫描的结论 */
    private class Scan(
        /** 屏上「合法评论行」的条数（含已处理/已关注的人，只用来判断"读不读得到评论"） */
        val rows: Int,
        /** 其中本轮**首见**的行数（= 列表确实有新评论进来的证据） */
        val freshRows: Int,
        /** 首见 + 未处理 + 未关注的人，按新→旧排序；空 = 还没有值得动手的新人 */
        val candidates: List<Candidate>,
        /** 为了等到这批新评论花了多久（0 = 上屏就是新的，没等） */
        val waitedMs: Long = 0,
        /**
         * true = 这次返回不是因为"等到了人/等不下去"，而是**等待期间用户改了目标设置**。
         *
         * 调用方必须 `continue` 回到主循环重读设置，不能把空候选当成"房间安静"继续等。
         */
        val settingsChanged: Boolean = false
    )

    /**
     * 等到「本轮首见且还没处理过」的评论行。
     *
     * 这是整个「追最新」的核心，替换掉旧的「等底行换人 6 秒、等不到就降级随便挑」：
     *
     * - **判据换成行级首见**：只要屏幕上出现一行从没见过的文案就算列表前进了，
     *   不再依赖"最底那一行"，因此不会因为底行读不到而抖动。
     * - **没有新评论就一直等**，绝不把候选窗口下滑到更旧的行。真机日志证明
     *   老实现 57% 的轮次走的是"降级按当前屏取人"，而那正是"关注到的都是几分钟前的人"的来源。
     * - 等待期间每次只跑**一次采集**（本来就需要的），不再额外为"看一眼底行"多跑一趟全树。
     *
     * 三种退出：等到新人 / 读不到评论行连续 [MAX_DRY_ROUNDS] 次（定位失效）/ 停了。
     * 房间安静到 [STALL_ABORT_MS] 都没有任何新评论时如实中止，不静默乱挑。
     *
     * @return null = 用户已停止（调用方直接 return，不要当成错误）
     */
    private suspend fun awaitFreshScan(
        ctx: FlowContext,
        targets: LiveTargets,
        store: FollowedStore,
        handled: Set<String>,
        seenRows: MutableMap<String, Long>,
        /** 进入等待时的「目标数」设置快照，用来发现等待期间用户改过它 */
        watchTarget: Int,
        /** 同上，每日上限 */
        watchDailyLimit: Int
    ): Scan? {
        val startAt = SystemClock.uptimeMillis()
        var emptyRounds = 0
        var lastReportAt = 0L
        var lastJumpAt = -JUMP_INTERVAL_MS
        while (true) {
            if (ctx.stopped()) return null
            ctx.awaitResume()
            ctx.guard()

            val scan = scanOnce(ctx, targets, store, handled, seenRows)
            if (scan.candidates.isNotEmpty()) {
                val waited = SystemClock.uptimeMillis() - startAt
                if (waited >= 2_000) {
                    LogStore.d(
                        TAG,
                        "等到新评论：等了 ${waited / 1000}s，屏上 ${scan.rows} 行、" +
                            "首见 ${scan.freshRows} 行、可用 ${scan.candidates.size} 人"
                    )
                }
                return Scan(scan.rows, scan.freshRows, scan.candidates, waited)
            }

            // 等待期间用户改了目标数/上限就立刻回主循环。
            // 不做这件事的话，「等新评论」最长能等 STALL_ABORT_MS（5 分钟），
            // 用户在这期间改设置完全没反应，观感就是「输入人数后没有变化」
            // （2026-10-29 真机反馈）。这里只比字段，不额外遍历界面，开销可忽略。
            val fresh = SettingsStore.get(ctx.appContext).current
            if (fresh.followTarget != watchTarget || fresh.dailyLimit != watchDailyLimit) {
                LogStore.i(
                    TAG,
                    "等待新评论期间目标设置变化（目标 $watchTarget→${fresh.followTarget}、" +
                        "上限 $watchDailyLimit→${fresh.dailyLimit}），回到主循环重新读取"
                )
                return Scan(
                    scan.rows,
                    scan.freshRows,
                    emptyList(),
                    SystemClock.uptimeMillis() - startAt,
                    settingsChanged = true
                )
            }

            val waited = SystemClock.uptimeMillis() - startAt
            if (scan.rows == 0) {
                emptyRounds++
                // **读不到评论的第一时间**就确认一次「是不是有浮层盖着」。
                //
                // 真机实测（2026-10-29）：手动点开礼物面板后，评论节点会从无障碍树里
                // 整片消失，流程只能干等 4 轮（约 10~30 秒）才判异常——太迟钝。
                // 这里只花一趟遍历（1.4s），认不出浮层就什么都不做（绝不误点），
                // 认出来就关掉重读，通常 2~3 秒就能继续。
                if (emptyRounds == 1) {
                    val closed = LiveGuard.recoverOverlay(ctx)
                    if (closed != null) {
                        LogStore.i(TAG, "读不到评论，但屏幕上有「$closed」浮层，已关闭并重新读取")
                        emptyRounds = 0
                        ctx.settle(OVERLAY_RETRY_MS)
                        continue
                    }
                }
                if (emptyRounds >= MAX_DRY_ROUNDS) {
                    // 读不到评论**不再直接判死**（2026-10-29 需求）。
                    //
                    // 三类成因里只有「live_comment_line 与当前版本不匹配」是真故障，
                    // 而礼物面板/观众列表盖住、转场加载这两种只要等一会儿就好。
                    // 真故障也不该悄悄结束任务——暂停等待、持续探测，用户回来点一下
                    // 「继续」就能接着跑，不用重新进直播间从头开始。
                    val reason = "连续 $MAX_DRY_ROUNDS 次读不到直播间评论"
                    emptyRounds = 0
                    val recovered = RecoveryWait.waitForComments(ctx, reason) {
                        // 探测用 seenRows 的**副本**：探测到的新评论不能被记成「已见」，
                        // 否则恢复之后这些行不再是首见，白白漏掉一批刚说话的人。
                        scanOnce(ctx, targets, store, handled, HashMap(seenRows)).rows
                    }
                    if (!recovered) return null
                    continue
                }
            } else {
                emptyRounds = 0
            }

            if (waited >= STALL_ABORT_MS) {
                throw FlowAbortException(
                    "这个直播间 ${STALL_ABORT_MS / 60_000} 分钟内没有出现新的评论" +
                        "（屏上 ${scan.rows} 行都是已经处理过的人）。" +
                        "换一个评论更热闹的直播间再开始——不会为了凑数去关注几分钟前的老观众"
                )
            }

            // 评论区「停住」时的正解：抖音会在底部浮出一个「99+条新消息」按钮，
            // 点它 = 让评论列表跳回最新。这是唯一的刷新入口——滑动是不行的（会切直播间），
            // 而干等它自己恢复实测要 2 分钟以上（19:53 那次等了 123 秒）。
            //
            // 这个 12 秒的节奏顺便当「定期体检」用：先看有没有浮层盖着。
            // 观众列表是**半屏**浮层，页面判定依然是 LIVE_ROOM、评论行也可能还在树里，
            // 不主动查就会一路等到 STALL_ABORT_MS（5 分钟）才报「房间安静」——
            // 真机实测（2026-10-29）就是这个表现。
            if (waited - lastJumpAt >= JUMP_INTERVAL_MS) {
                lastJumpAt = waited
                val closed = LiveGuard.recoverOverlay(ctx)
                if (closed != null) {
                    LogStore.i(TAG, "定期体检发现「$closed」浮层，已关闭并重新读取")
                    ctx.settle(OVERLAY_RETRY_MS)
                    continue
                }
                val stuck = if (waited >= 2_000) "评论区停在旧位置 ${waited / 1000}s" else "评论区未跟进最新"
                val jumped = jumpToNewest(ctx)
                if (jumped != null) {
                    LogStore.i(TAG, "$stuck，已点「$jumped」回到最新")
                    ctx.settle(JUMP_SETTLE_MS)
                    continue
                }
                // 没有浮标：人少的直播间不会浮浮标，直接滚一下评论容器把已有的新评论露出来。
                // diagnose = false：这里每隔 12 秒就会跑一次，不必每次都做前后快照取证。
                if (scrollCommentsToNewest(ctx, stuck, SystemClock.uptimeMillis(), diagnose = false)) {
                    continue
                }
            }

            if (waited - lastReportAt >= REPORT_INTERVAL_MS) {
                lastReportAt = waited
                ctx.publish(
                    message = if (scan.rows == 0) {
                        "读不到评论行（$emptyRounds/$MAX_DRY_ROUNDS）"
                    } else {
                        "等新评论 ${waited / 1000}s（屏上 ${scan.rows} 行都已处理）"
                    }
                )
            }
            // 分片等待：既能被「暂停/停止」立刻打断，也顺带给采集节奏加抖动
            ctx.settle(FRESH_POLL_MS)
        }
    }

    /**
     * 点掉抖音底部的「99+条新消息」浮标，让评论列表**跳回最新**。
     *
     * 为什么需要它（2026-10-28 真机实测，不求人直播间）：
     * 点开一次评论行之后，评论区就**脱离底部**了——新评论不再上滚，全部堆在这个浮标后面，
     * 屏幕上一直是同一批旧评论（实测 2 分钟内 6 行一字未变），要等它自己恢复得 2 分钟以上。
     * 这正是「关注完一个就再也追不到最新评论」的直接原因。
     *
     * 这个浮标是抖音自己的"回到最新"按钮，普通点击，不涉及任何付费动作；
     * 找不到它就返回 false（说明房间是真的安静），**绝不盲点坐标**。
     */
    private suspend fun jumpToNewest(ctx: FlowContext): String? {
        // **一趟「只比文案」的遍历**（findFirst），不建快照。
        // 旧实现走 snapshot：每个节点要读 text/desc/id/editable/四个 bounds（7 次跨进程调用），
        // 647 节点实测 1.4s；找浮标只需要文案，走 findFirst 大约 0.4s。
        // 关注后「点浮标 / 下滑」必须在 1 秒内完成，这条路径容不下第二趟全量快照。
        val pill = ctx.actions.findFirst(NEW_MESSAGE_TEXTS) ?: return null
        val label = pill.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: pill.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "新消息浮标"
        LogStore.d(TAG, "发现新消息浮标：\"$label\"")
        // 有节点就还走 tapNode：矩形内随机取点 + 付费守卫，比按快照矩形点更保险
        if (ctx.actions.tapNode(pill, "新消息按钮")) return label
        LogStore.w(TAG, "点新消息浮标失败（$label）")
        return null
    }

    /**
     * 把评论区拉回最新（点抖音底部的「99+条新消息」浮标），**关注成功后立刻调用**。
     *
     * 为什么必须紧跟关注动作而不是等下一轮：点开评论行会让评论区**脱离底部**
     * （见 [jumpToNewest] 的注释），之后新评论全部堆在浮标后面、屏幕上还是那批旧人。
     * 一次关注（点昵称 → 等弹层 → 点关注 → 校验 → 关弹层）要 5~10 秒，
     * 这期间至少堆积十几条新评论。以前要等完扫描间隔（默认 30 秒）再进下一轮扫描、
     * 发现「一个人都不新」才去点浮标——用户看到的就是「关注完界面卡住不动，半天没动静」。
     *
     * 找不到浮标 = 房间真的没有新评论（或列表还贴着底部），返回 false，**绝不盲点坐标**。
     */
    private suspend fun refreshComments(ctx: FlowContext, reason: String): Boolean {
        val startedAt = SystemClock.uptimeMillis()

        // ① **先滚**：命中容器缓存时只要几毫秒（performAction 直接发给评论容器）。
        //    对比一下：找「N条新消息」浮标要走一趟全树遍历，实测 700~900ms——
        //    单这一项就让「关注后 1 秒内下滑」不成立，所以这里把滚放在前面。
        //    滚动只是「往前挪一屏」，效果上等价于点浮标（都能让新评论露出来），
        //    而点浮标更适合「已经卡住很久」的场景——那条通道留在等待期的 12 秒体检里。
        //    settleAfterScroll = false：后面紧跟着就是扫描间隔，不必再等列表重绘。
        if (scrollCommentsToNewest(ctx, reason, startedAt, settleAfterScroll = false)) return true

        // ② 滚不动（列表已经在最新一屏）：再试浮标。此时花一趟遍历也没浪费——
        //    出现浮标就说明确实有新评论堆在下面，点它能让列表彻底贴回底部。
        val jumped = jumpToNewest(ctx)
        if (jumped != null) {
            ctx.settle(JUMP_SETTLE_MS)
            LogStore.i(
                TAG,
                "$reason → 已点「$jumped」把评论区拉回最新（耗时 ${SystemClock.uptimeMillis() - startedAt}ms）"
            )
            return true
        }
        return false
    }

    /**
     * 没有「N条新消息」浮标时，把评论区朝**最新方向**再滚一段。
     *
     * 为什么需要它：浮标要攒够几条新评论才浮出来（抖音的行为），人少的直播间经常
     * 一整分钟都浮不出来——关注完一个人，屏幕上还是那几条旧评论，看着像卡住。
     * 这时列表其实可能已经追加了 1~2 条新评论在可视区下方，滚一下就能露出来。
     *
     * **为什么用 `ACTION_SCROLL_FORWARD` 而不是手势**：抖音直播间的整页是竖向 ViewPager，
     * 手指从下往上就是「切下一个直播间」（docs/M2 §12.2 实测 550px 切房）。
     * SCROLL 动作直接发给评论容器，不产生全局手势，物理上不可能切房。
     *
     * 仍然做了两道保险：
     * 1. 只接受「高度 ≤ 55% 屏高、中心落在评论区范围内」的可滚动节点（排除整页容器）；
     * 2. 滚完比对**当前主播昵称**，变了就立刻暂停——宁可停下也不在别人的房间里乱点。
     */
    /**
     * 评论容器的节点缓存。
     *
     * 为什么必须要它：`findAccessibilityNodeInfosByViewId` 在**抖音刚重绘完**时必须重建
     * 无障碍节点树，实测 0.8~1.2 秒（关注完刚好发生过 `back()`，每次都是这个状态）。
     * 而 `performAction` 只要节点还指向活着的那棵 View，几毫秒就能滚。
     * 所以策略是：**先拿缓存直接滚**，滚不动（节点失效）再重建——把「关注后下滑」
     * 从 1 秒压到毫秒级，重建只在缓存失效时发生。
     */
    private var cachedCommentList: AccessibilityNodeInfo? = null

    /** 缓存判活：**必须用 `refresh()`**，不能拿 `performAction` 的返回值当判活（见 [scrollCommentsToNewest]） */
    private fun commentListAlive(node: AccessibilityNodeInfo?): Boolean =
        node != null && runCatching { node.refresh() && node.isScrollable }.getOrDefault(false)

    /**
     * 拿评论容器，**只走快通道**：缓存 → view id。拿不到就返回 null（调用方退回整树）。
     *
     * 刻意**不**用 [resolveCommentListFresh] 里的「按区域遍历找 scrollable」兜底：
     * 那是一次全树 BFS，在播视频的直播间要 8~16 秒，而这里是被每轮扫描调用的热路径。
     * 与其在这里等 16 秒，不如退一步用整树 collect（一趟，同样的钱还顺带采到人）。
     */
    private fun commentContainerForScan(ctx: FlowContext): AccessibilityNodeInfo? {
        if (commentListAlive(cachedCommentList)) return cachedCommentList
        cachedCommentList = null
        val byId = runCatching { ctx.actions.findByViewId(COMMENT_LIST_VIEW_ID) }
            .getOrNull()
            ?.takeIf { it.isScrollable }
            ?: return null
        cachedCommentList = byId
        return byId
    }

    /** 重建：先按 view id（快、稳），失败再按区域遍历（抖音改版换了混淆 id 时的退路） */
    private fun resolveCommentListFresh(ctx: FlowContext): AccessibilityNodeInfo? =
        ctx.actions.findByViewId(COMMENT_LIST_VIEW_ID)?.takeIf { it.isScrollable }
            ?: ctx.actions.scrollableIn(commentScrollArea(ctx))

    /**
     * 预热评论容器缓存。
     *
     * 放在任务启动时做（不在关注路径上）：那时慢一点无所谓，之后每次关注都能直接命中缓存。
     */
    private fun warmUpCommentList(ctx: FlowContext) {
        val startedAt = SystemClock.uptimeMillis()
        val node = resolveCommentListFresh(ctx)
        if (node == null) {
            LogStore.d(TAG, "评论容器预热失败（不影响后续，滚动时会按需重建）")
            return
        }
        cachedCommentList = node
        LogStore.d(TAG, "评论容器已预热：${node.viewIdResourceName ?: "无id"}（${SystemClock.uptimeMillis() - startedAt}ms）")
    }

    private suspend fun scrollCommentsToNewest(
        ctx: FlowContext,
        reason: String,
        startedAt: Long,
        /** true = 额外做「滚动前后评论行 y 变化」的取证；默认关，见 [SCROLL_DIAGNOSE] */
        diagnose: Boolean = SCROLL_DIAGNOSE,
        /**
         * 滚动后是否等一小段让列表重绘。
         *
         * 关注后那条路径**不需要**：紧接着就是「补足扫描间隔」（几秒到几十分钟），
         * 列表有充足时间自己重绘，白等 180ms 只会让「关注完的下滑」突破 1 秒。
         * 等待新评论的轮询路径需要——它 `continue` 之后立刻又要扫一遍。
         */
        settleAfterScroll: Boolean = true
    ): Boolean {
        // ① 优先**按 view id 直接取评论容器**（framework 原生查找，比自己 BFS 快数倍）。
        //    关注后要在 1 秒内完成「找浮标 → 下滑」，这条路径容不下第二趟遍历。
        // ① 先拿缓存直接滚：命中时只要几毫秒（关注后这条路要压在 1 秒内）
        // 取证模式才抓「滚动前的评论行 y」（两趟快照很贵，默认不开）
        val before = if (diagnose) commentRowTops(ctx) else emptyMap()
        // ⚠️ 判活要用 `refresh()`，**不能拿 scroll 的返回值当判活**：
        // 列表已经贴着最新一屏时 performAction 也返回 false，那是「滚不动」不是「节点失效」。
        // 这两件事混在一起会让每次关注都白重建一次容器（实测多花 600~900ms）。
        var reusedCache = false
        var scrolled = false
        cachedCommentList?.let { node ->
            if (commentListAlive(node)) {
                reusedCache = true
                scrolled = ctx.actions.scrollForward(node, "评论区")
            } else {
                cachedCommentList = null
            }
        }
        if (!reusedCache) {
            // 缓存失效（抖音重绘后节点脱离树）才重建，重建约 0.8~1.2s
            val fresh = resolveCommentListFresh(ctx)
            if (fresh == null) {
                LogStore.i(
                    TAG,
                    "$reason → 评论区里没有可滚动容器，本轮不下滑" +
                        "（耗时 ${SystemClock.uptimeMillis() - startedAt}ms）"
                )
                cachedCommentList = null
                return false
            }
            cachedCommentList = fresh
            scrolled = ctx.actions.scrollForward(fresh, "评论区")
        }
        if (!scrolled) {
            // 列表本来就贴着最新一屏时系统会拒绝——这是**正常状态**，不是失败
            LogStore.d(
                TAG,
                "$reason → 列表已到最新一屏（系统拒绝滚动），本轮不下滑" +
                    "（${if (reusedCache) "命中缓存" else "重建容器"}，" +
                    "共 ${SystemClock.uptimeMillis() - startedAt}ms）"
            )
            return false
        }
        // 260ms：抖音的评论滚动是「瞬时位移」，没有动画，等太久纯属白等。
        // ⚠️ 这里用 delay 而不是 ctx.settle()：settle 末尾会 guard() 抓一次全量快照（1.4s），
        // 那一下就把「1 秒内完成下滑」的预算吃光了。下滑本身不是点击动作（不触发任何付费/关注），
        // 风控检查交给主循环每轮的 guard() 与本轮扫描，不会漏。
        if (settleAfterScroll) delay(SCROLL_SETTLE_MS)
        val scrolledAt = SystemClock.uptimeMillis()

        if (!diagnose) {
            LogStore.i(
                TAG,
                "$reason → 已下滑评论（${if (reusedCache) "命中容器缓存" else "重建容器"}，" +
                    "共 ${scrolledAt - startedAt}ms）"
            )
            return true
        }

        // 方向取证：共同行的 y 变化为负 = 内容上移 = 露出的是更靠后的（更新）评论
        val after = commentRowTops(ctx)
        val shared = before.keys.intersect(after.keys)
        val dy = if (shared.isEmpty()) 0 else shared.sumOf { after.getValue(it) - before.getValue(it) } / shared.size
        LogStore.i(
            TAG,
            "$reason → 已滚动评论区（容器 ${cachedCommentList?.viewIdResourceName ?: "无id"}，" +
                "共同行 ${shared.size} 条、平均 y 变化 $dy（负=内容上移/更靠最新），" +
                "新增 ${(after.keys - before.keys).size} 行、消失 ${(before.keys - after.keys).size} 行，" +
                "共 ${SystemClock.uptimeMillis() - startedAt}ms）"
        )
        return dy != 0 || after.keys != before.keys
    }

    /**
     * 评论区可滚动的范围：从画面中段到输入栏上方，右侧留出按钮栏。
     *
     * 右侧必须避开：点赞/评论/分享/礼物那一列在屏幕最右边，落进去等于点了别的功能。
     * 下边界直接复用 [rowBottomLimit]（输入栏顶 - 余量），与「评论行不能被输入栏压住」
     * 用的是同一条线，不会出现两套坐标。
     */
    /**
     * 评论区可滚动的范围，**纯按屏幕比例算，不跑任何遍历**。
     *
     * 这个范围只用于「哪个可滚动容器算评论容器」的筛选，不需要精确到像素：
     * 左侧 6%~62% 避开右侧那列点赞/礼物/分享，纵向 42%~90% 覆盖评论行（实测 55%~93%）
     * 且不含底部输入栏。以前这里要读一次输入栏位置（快照 1.4s），
     * 而关注后的刷新要压在 1 秒内，这一趟必须省掉。
     */
    private fun commentScrollArea(ctx: FlowContext): Rect {
        val h = ctx.actions.screenHeight()
        val w = ctx.actions.screenWidth()
        return Rect(
            (w * 0.06f).toInt(),
            (h * COMMENT_AREA_TOP_RATIO).toInt(),
            (w * 0.62f).toInt(),
            (h * COMMENT_AREA_BOTTOM_RATIO).toInt()
        )
    }

    /**
     * 屏上评论行的「文案 → 上边缘 y」，用来判断滚动方向（内容上移还是下移）。
     *
     * 用一份新的快照纯读，不碰 [seenRows]，也不改变任何状态。
     */
    private fun commentRowTops(ctx: FlowContext): Map<String, Int> {
        val lineTarget = ctx.config.target("live_comment_line")
            ?: ctx.config.target("live_comment_nickname")
            ?: return emptyMap()
        return ctx.actions.snapshot(ttlMs = 0)
            .asSequence()
            .filter { it.bottom > it.top && ctx.actions.matchesFacts(it, lineTarget) }
            .mapNotNull { fact ->
                val text = fact.text?.trim()
                if (text.isNullOrEmpty()) null else text to fact.top
            }
            .toMap()
    }

    /**
     * 关注成功后的「先把评论区拉回最新，再把间隔等满」。
     *
     * 为什么刷新要点在这里，而不是关注一落定就点：
     * 关注动作本身要 5~10 秒（点昵称 → 等资料卡 → 点关注 → 校验 → 关弹层），这期间评论区
     * 已经脱离底部、新评论在往后堆；但抖音要**攒够几条**才会浮出「N条新消息」，
     * 刚关掉弹层那一瞬间常常还是 0 条（真机实测 22:56:14、22:57:01 两次都是「没有浮标」）。
     * 所以先等 [REFRESH_PROBE_MS]，再探。
     *
     * 关键点：这段耗时**算在扫描间隔里**（末尾只补剩余时间），
     * 所以整个节奏不会比用户设置的间隔更长，也就不必为了刷新牺牲拟人化间隔。
     *
     * @param intervalMs 扫描间隔；传 0 表示「只刷新、不等」（休息点前的用法）
     * @param shouldInterrupt 等待期间要检查的中断条件（用户改了目标数/每日上限）
     */
    private suspend fun waitAfterFollow(
        ctx: FlowContext,
        intervalMs: Long,
        reason: String,
        shouldInterrupt: () -> Boolean
    ) {
        val startAt = SystemClock.uptimeMillis()

        // ① 先刷新。间隔太短（< 3s）时不值得为它插一段探测，交给下一轮的即时刷新兜底
        if (intervalMs == 0L || intervalMs >= REFRESH_MIN_INTERVAL_MS) {
            // 裸 delay（不是 ctx.settle）：那会给这段等待多挂一次全树风控检查（8~16 秒），
            // 而这里要的只是「等资料卡收起的动画走完」，300ms 足够。
            // 刷新本身走评论容器缓存（6~12ms）+ 必要时一趟 findFirst，不再依赖整树快照。
            delay(REFRESH_PROBE_MS)
            if (!shouldInterrupt()) refreshComments(ctx, reason)
        }

        // ② 补足剩余间隔。间隔可能长达 60 分钟，必须分片等待，暂停/停止才收得到
        val rest = intervalMs - (SystemClock.uptimeMillis() - startAt)
        if (rest > 0) {
            ctx.settleLong(rest, capMs = Settings.MAX_SCAN_MS, shouldInterrupt = shouldInterrupt)
        }
    }

    /**
     *
     * 实测 40.8.0：输入框 `[72,2188][480,2302]`，最新一行评论底部约 2170。
     * 追最新之后挑中的就是最底部那几行，离输入栏只有十几像素，
     * 不设这条线的话点歪了会直接弹键盘（把输入区当成评论行点）。
     *
     * 走快照而不是 `find`：输入栏的位置在一轮里不会变，没必要为它再跑一趟全树遍历。
     */
    /**
     * 评论行可点的**下边界**：压到输入栏上的行不点（点歪会弹键盘）。
     *
     * ⚠️ 这里**刻意不读输入栏位置**（2026-10-30）。老实现每次都调
     * `snapshot(ttlMs = 5000)` 找「说点什么」，而在慢遍历的直播间里一次就是 8~16 秒——
     * 它和 `collect` 一趟叠在一起，让「一次扫描」变成两趟全树（16~32 秒），
     * 这正是「评论刷新卡顿」最大的单项。
     *
     * 用屏高比例代替：真实机 40.8.0 实测输入栏顶 = 屏高 91.4%（2188/2374），
     * 评论行最大底边 2104（88.6%），两边都有余量。[FALLBACK_ROW_LIMIT_RATIO] 取 0.915
     * 落在两者之间，误差几像素，最多多放过一条紧贴输入栏的行 ——
     * 而点击本身还走 [Actions.tapNickname] 的「贴行上部取点」，不会落到输入框上。
     */
    private fun rowBottomLimit(ctx: FlowContext): Int =
        (ctx.actions.screenHeight() * FALLBACK_ROW_LIMIT_RATIO).toInt()

    // ==================== 候选采集 ====================

    /** 一个候选人：昵称 + 采集时那个节点（为 null 表示只能靠回查）+ 所在行的下边缘（y 越大越新） */
    private class Candidate(
        val name: String,
        val node: AccessibilityNodeInfo?,
        val bottomY: Int
    )

    /**
     * 在「最新窗口」里按位置加权挑一个：越新权重越高（3 / 2 / 1）。
     *
     * 为什么不是 `random()`：三选一等概率会让 2/3 的轮次挑到不是最新的那个，
     * 用户看到的就是「又在关注老观众」。为什么也不是 `first()`：
     * 永远挑最底那一行是最容易被识别的机器特征。
     */
    private fun pickWeighted(window: List<Candidate>): Candidate {
        val weights = window.indices.map { NEWEST_WINDOW - it }.map { it.coerceAtLeast(1) }
        val total = weights.sum()
        var ticket = ctxRandom().nextInt(total)
        for (i in weights.indices) {
            ticket -= weights[i]
            if (ticket < 0) return window[i]
        }
        return window.first()
    }

    /** 本流程所有随机都走同一份种子源，便于统一做「抖动」级别的拟人化 */
    private fun ctxRandom(): kotlin.random.Random = Random.Default

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
     * 等抖音真正到前台。
     *
     * 「开始」按钮在抖加加界面上，所以点击那一刻抖音必然在后台；
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
     * 采集一次：**单趟遍历**同时产出「屏上有几行评论」「其中几行是首见的」「可动手的新人」。
     *
     * 三级采集（配置的主路径 → 整行冒号切分兜底）保持不变，但「新鲜度」判定放在**行级**：
     * 整行文案进 [seenRows]，第一次出现即视为「列表刚推进到它」。
     *
     * 为什么不是按人判断新鲜：已关注过的人又发了新评论，列表一样前进过，
     * 这时应该继续按新一屏取人（而不是因为"这个人在名单里"就把整屏当成没动）。
     *
     * 候选**只收首见的行** —— 这就是「永远只关注刚说话的人」的实现点：
     * 窗口不会再因为"最新的人都处理过了"而静默下滑到几分钟前的旧评论。
     */
    private fun scanOnce(
        ctx: FlowContext,
        targets: LiveTargets,
        store: FollowedStore,
        handled: Set<String>,
        seenRows: MutableMap<String, Long>
    ): Scan {
        var raw: List<Pair<String, AccessibilityNodeInfo>> = emptyList()
        var source = "主路径"
        var scoped = false

        // **在评论容器子树里采集**，不再从整棵树开始 BFS。
        //
        // 为什么这是本流程最要紧的一条（2026-10-30 真机日志 R-20261030-A88080）：
        // 直播间在播视频时，一通全树遍历实测 5~16 秒（610 节点 / 16099ms，每节点 26ms，
        // 是健康值的 10 倍以上——系统对无障碍 IPC 的节流）。而评论容器 r68 的子树只有
        // 几十个节点，同样的遍历只要几百毫秒。以前每轮扫描要跑**两趟**全树
        // （collect 一趟 + rowBottomLimit 的 snapshot 一趟），光扫描就 16~32 秒。
        //
        // 拿不到容器（抖音改版换了混淆 id）就退回整树，行为与旧版一致，不会采不到人。
        val root = commentContainerForScan(ctx)
        if (root != null) scoped = true

        if (targets.nickname != null) {
            raw = ctx.actions.collect(targets.nickname, MAX_COLLECT, root)
                .mapNotNull { node -> ctx.actions.labelOf(node)?.let { label -> label to node } }
        }
        if (raw.isEmpty() && targets.line != null) {
            source = "冒号切分兜底"
            raw = ctx.actions.collect(targets.line, MAX_COLLECT, root)
                .mapNotNull { node ->
                    val label = ctx.actions.labelOf(node) ?: return@mapNotNull null
                    splitNickname(label)?.let { name -> name to node }
                }
        }
        // 容器内一条都没读到：容器可能只是一个空壳（列表还没挂上），退回整树再采一次。
        // 这一趟只在异常情况下发生，正常运行时每轮只花掉容器内那点时间。
        if (raw.isEmpty() && scoped && targets.line != null) {
            source = "冒号切分兜底(整树兜底)"
            LogStore.d(TAG, "评论容器内未采到任何评论行，退回整棵树采集")
            raw = ctx.actions.collect(targets.line, MAX_COLLECT, null)
                .mapNotNull { node ->
                    val label = ctx.actions.labelOf(node) ?: return@mapNotNull null
                    splitNickname(label)?.let { name -> name to node }
                }
        }
        if (scoped && !source.contains("整树")) source += "(容器内)"

        val limit = rowBottomLimit(ctx)
        val now = SystemClock.uptimeMillis()
        val best = LinkedHashMap<String, Candidate>(raw.size)
        var rows = 0
        var freshRows = 0
        var skippedLow = 0
        var skippedThin = 0
        var skippedSeen = 0
        for ((rawName, node) in raw) {
            val name = rawName.trim()
            val bounds = ctx.actions.boundsOf(node)
            val bottom = bounds.bottom
            // 刚插入的行高度还没稳定（实测出现过 6px 的行），点下去落点会偏到相邻行或空白
            if (bounds.height() < MIN_ROW_HEIGHT) {
                skippedThin++
                continue
            }
            // 压到输入栏上的行不点：追最新之后挑中的就是最底部那几行，点歪会弹键盘
            if (bottom > limit) {
                skippedLow++
                continue
            }
            if (!isValidNickname(name)) continue

            // 行级首见：整行文案（昵称 + 正文）第一次出现 = 列表刚推进到它
            val label = ctx.actions.labelOf(node)?.trim().orEmpty()
            val key = if (label.isEmpty()) name else label
            val fresh = seenRows.put(key, now) == null
            rows++
            if (fresh) freshRows++

            if (name in ctx.config.excludeNicknames) continue
            if (name in handled) continue
            if (store.isFollowed(name)) continue
            // 判定过是私密账号的人直接排除：点开也只会再发一次没用的申请
            if (store.isPrivate(name)) continue
            if (!fresh) {
                skippedSeen++
                continue
            }
            // 同一个人连发几条评论时保留**最新**那一条（bottom 最大），不是第一次出现的那条
            val existing = best[name]
            if (existing == null || bottom > existing.bottomY) {
                best[name] = Candidate(name, node, bottom)
            }
        }

        // seenRows 是长会话里的活体集合：一次会话可能见到上千条评论，只保留最近的一批
        if (seenRows.size > SEEN_ROWS_CAP) {
            val iterator = seenRows.keys.iterator()
            repeat(seenRows.size - SEEN_ROWS_CAP / 2) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
        }

        // 从新到旧：调用方只取最前面几个，就能保证挑的是屏幕上最新的评论者
        val candidates = best.values.sortedByDescending { it.bottomY }

        if (raw.isEmpty()) {
            LogStore.w(TAG, "本轮未采集到任何评论文本（来源：$source），可能是定位配置不匹配或评论区为空")
        } else if (freshRows > 0 || candidates.isNotEmpty()) {
            LogStore.d(
                TAG,
                "扫描（$source）：原始 ${raw.size} → 评论行 $rows（首见 $freshRows）→ 可用新人 ${candidates.size}" +
                    "（已排除：本轮已处理 ${handled.size}、历史已关注 ${store.knownCount()}、" +
                    "黑名单 ${ctx.config.excludeNicknames.size}、压输入栏 $skippedLow、行未成型 $skippedThin、" +
                    "非首见 $skippedSeen）" +
                    "；最新 3 人 ${candidates.take(3).joinToString("、") { "${it.name}@y${it.bottomY}" }}"
            )
        }
        return Scan(rows, freshRows, candidates)
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

    private enum class CardState {
        FOLLOWABLE,
        ALREADY_FOLLOWED,

        /**
         * 对方是**私密账号**：关注只会发出一份申请，对方不通过就永远不是粉丝。
         *
         * 单独成态而不是并进「未变成已关注」：那一条要等满 [VERIFY_TIMEOUT_MS]（6 秒）
         * 才下结论，而私密账号**一出现申请文案就能确定**——用户要的是「马上下一轮」，
         * 不能在这个人身上白等 6 秒（真机反馈：关注了私密账号，人数根本没涨，还在原地磨）。
         */
        PRIVATE,

        UNKNOWN
    }

    private class CardProbe(
        val state: CardState,
        val node: AccessibilityNodeInfo? = null,
        /** 命中 PRIVATE 时具体是哪条文案，用来日志取证（也方便把漏收的文案补进配置） */
        val evidence: String? = null
    )

    private suspend fun awaitCard(ctx: FlowContext, targets: LiveTargets, timeoutMs: Long): CardProbe {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        // 点评论行有两种结果：直接弹资料卡，或弹出快捷操作栏（资料卡/喜欢/不喜欢/@TA/更多）。
        // 后者是临时浮层，几秒后自己消失，不点「资料卡」就永远等不到关注按钮。
        // 这里只兜一次，避免在两个浮层之间来回点。
        var triedProfileEntry = false
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return CardProbe(CardState.UNKNOWN)
            // 一趟快照同时判「已关注」和「私密账号」。
            //
            // 不能拆成两次 snapshot(ttl = 0)：单趟遍历实测约 1.4s，两次就是 2.8s，
            // 6 秒的等卡窗口只剩两次采样，本来就紧。
            //
            // 顺序是**先已关注、后私密**：一个人如果已经被通过申请，卡里是「取消关注」，
            // 那他就是真的已关注，该记进已关注名单；私密标记只是补充证据。
            //
            // lean = true：这里只用 text/desc/bounds，没必要为 id / editable 多付
            // 两次跨进程调用（610 节点上省 25%~30%）。页面与浮层判定同样不用 ID。
            val facts = ctx.actions.snapshot(ttlMs = 0, lean = true)
            val topLimit = cardTopLimit(ctx)
            if (alreadyFollowedIn(facts, targets, topLimit)) {
                return CardProbe(CardState.ALREADY_FOLLOWED)
            }
            privateEvidenceIn(facts, ctx.config.privateAccountHints, topLimit)?.let { evidence ->
                return CardProbe(CardState.PRIVATE, evidence = evidence)
            }
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

    /**
     * 点完「关注」之后等结论：**变成已关注** / **其实是私密账号（只发了申请）** / 超时。
     *
     * 三者分开的意义：
     * - 私密账号要**立刻**返回（申请文案一出现就能确定），不能陪着等满 6 秒；
     * - 超时才需要把弹层现有文案 dump 出来——那是唯一能把「没收录的新文案」变成证据的地方。
     *
     * **判据优先级（2026-10-30 重构，解决「关注完卡住十几秒」）**：
     * 1. 手上有刚点过的那颗按钮 → **刷新它、读它的文案**（1~3 次 IPC，毫秒级）。
     *    抖音点关注后就地把按钮文案换成「取消关注 / 申请关注」，根本不需要重读整棵树；
     * 2. 只有按钮节点**脱离节点树**（`refresh()` 返回 false，抖音重建了资料卡）时，
     *    才退回整树快照（播视频的直播间里 8~16 秒一趟，只在异常时付这笔钱）。
     *
     * 旧实现是「每 250ms 跑一趟 ttl=0 的全树快照」，而一趟要 8~16 秒 ——
     * 于是「等关注结果」实际耗时 10 秒以上，被用户感知成「关注完界面卡死」。
     *
     * @param button 刚点下去的「关注」按钮节点；null 表示调用方没有，直接走整树通道
     */
    private suspend fun awaitFollowOutcome(
        ctx: FlowContext,
        targets: LiveTargets,
        timeoutMs: Long,
        button: AccessibilityNodeInfo? = null
    ): CardProbe {
        val startAt = SystemClock.uptimeMillis()
        val deadline = startAt + timeoutMs
        val quickUntil = startAt + QUICK_PROBE_BUDGET_MS
        var useButton = button != null
        var snapshotDone = false
        var lastLabel: String? = null

        while (true) {
            if (ctx.stopped()) return CardProbe(CardState.UNKNOWN)
            val now = SystemClock.uptimeMillis()
            // 已经跑过整树判定才允许按超时退出——**绝不能「快速通道没结论 + 到了 6 秒」直接判失败**，
            // 那会把真的关注成功记成失败（vc36 的回归：人数不增加，见下面的注释）。
            if (now >= deadline && snapshotDone) return CardProbe(CardState.UNKNOWN)

            if (useButton && now < quickUntil) {
                val quick = quickProbe(button!!, targets, ctx.config.privateAccountHints)
                quick.result?.let {
                    LogStore.d(TAG, "关注结果（按钮直读，${SystemClock.uptimeMillis() - startAt}ms）：${it.state}")
                    return it
                }
                if (!quick.nodeAlive) {
                    LogStore.d(TAG, "关注按钮节点已脱离节点树（资料卡被重建），改走整树判定")
                    useButton = false
                } else if (quick.repurposed) {
                    // 节点还活着但文案变成既非「关注」也非已知结果的别的东西：
                    // 说明这颗 AccessibilityNodeInfo 已经被抖音复用到别的控件上了，
                    // 再刷新它读一百次也不会有结论。立即改走整树，不要耗到预算用完。
                    LogStore.d(TAG, "关注按钮节点已被复用（读到的文案是「${quick.label}」），改走整树判定")
                    useButton = false
                } else {
                    // 文案还是「关注」：界面还没刷新完，继续毫秒级轮询
                    if (quick.label != lastLabel) {
                        lastLabel = quick.label
                        LogStore.d(TAG, "等关注结果：按钮文案仍为「${quick.label}」")
                    }
                    delay(CARD_POLL_MS)
                    continue
                }
            }

            // ===== 整树判定 =====
            // vc36 的回归就出在这里：老实现「有按钮就永远不跑整树」，而实测抖音点关注后
            // 那颗按钮常常**不在原节点上更新**，于是 6 秒里一次判定都没做，直接判 UNKNOWN
            // ⇒ 关注明明成功、进度却不涨。现在改成：快速通道有预算，超出预算/节点失效一定转整树，
            // 而且**只要转进来就至少跑完一趟**（哪怕已经超时）——宁可多花一趟遍历，也不能漏计。
            useButton = false
            // lean = true：这条路径只用 text/desc/bounds，跳过 id 与 editable 两次跨进程调用
            // （每节点从 7 次降到 5 次，实测省 25%~30%）。页面/浮层/风控判定同样不用 ID，
            // 所以这份快照进缓存后对它们是安全的。
            val facts = ctx.actions.snapshot(ttlMs = 0, lean = true)
            snapshotDone = true
            val topLimit = cardTopLimit(ctx)
            if (alreadyFollowedIn(facts, targets, topLimit)) {
                return CardProbe(CardState.ALREADY_FOLLOWED)
            }
            privateEvidenceIn(facts, ctx.config.privateAccountHints, topLimit)?.let { evidence ->
                return CardProbe(CardState.PRIVATE, evidence = evidence)
            }
            if (SystemClock.uptimeMillis() >= deadline) return CardProbe(CardState.UNKNOWN)
            delay(CARD_POLL_MS)
        }
    }

    /**
     * [quickProbe] 的结论。
     *
     * @param result 非空 = 已能定论（成功 / 私密）
     * @param nodeAlive `refresh()` 是否成功——false 表示节点已脱离节点树
     * @param label 刷新后读到的文本（日志用）
     * @param repurposed 节点还活着，但文本既不是「关注」也不是任何已知结果 ⇒ 被复用到别的控件了
     */
    private class QuickProbe(
        val result: CardProbe?,
        val nodeAlive: Boolean,
        val label: String? = null,
        val repurposed: Boolean = false
    )

    /**
     * 毫秒级判据：刷新**那颗被点过的按钮**，只看它自己的文案。
     *
     * 之所以值得试：抖音的资料卡只有一颗主按钮，点「关注」之后多数情况下它会就地变成
     * 「取消关注」（成功）/「申请关注」（私密账号）。`refresh()` + 读 text/desc 只要
     * 1~3 次跨进程调用，对比整树快照的 610 节点 × 5 = 3000 次调用，差三个数量级。
     *
     * ⚠️ 但它**不能当成唯一判据**（vc36 的教训）：实测这颗按钮常常不是就地更新，
     * 节点对象被抖音复用后 `refresh()` 依旧返回 true，读到的却是别人的文案。
     * 所以这里额外给出 [QuickProbe.repurposed]，由调用方据此**立刻**转整树判定，
     * 而不是抱着一个失效节点空等到超时。
     *
     * 匹配一律**全等**（与 [privateEvidenceIn] 同口径）：包含匹配会让「关注」命中
     * 「申请关注」，把所有成功都判成私密账号。
     */
    private fun quickProbe(
        button: AccessibilityNodeInfo,
        targets: LiveTargets,
        hints: Set<String>
    ): QuickProbe {
        val alive = runCatching { button.refresh() }.getOrDefault(false)
        if (!alive) return QuickProbe(null, false)
        val text = runCatching { button.text?.toString()?.trim() }.getOrNull()
        val desc = runCatching { button.contentDescription?.toString()?.trim() }.getOrNull()

        // 已关注：取消关注 / 互相关注 / 配置里的 followed 文案 / 「已关注」
        // 「已关注」是第三种态（部分版本的资料卡按钮直接写这三个字，不是「取消关注」），
        // 只在**按钮自身**文案上认它，不进全局 ALREADY_FOLLOWED_TEXTS——
        // 全树判定里放行「已关注」会让「已关注你」这类半句文案被误判成成功。
        if (text != null && (ALREADY_FOLLOWED_TEXTS.contains(text) ||
                text == targets.followed.value || text == FOLLOWED_ALT_TEXT)
        ) {
            return QuickProbe(CardProbe(CardState.ALREADY_FOLLOWED), true, text)
        }
        if (text != null && hints.contains(text)) {
            return QuickProbe(CardProbe(CardState.PRIVATE, evidence = text), true, text)
        }
        if (desc != null && hints.contains(desc)) {
            return QuickProbe(CardProbe(CardState.PRIVATE, evidence = desc), true, desc)
        }

        // 文案没变 = 界面还没刷新完（正常等待）；文案变成别的东西 = 节点已被复用（别再等）
        val unchanged = text == targets.follow.value
        val repurposed = text != null && !unchanged
        return QuickProbe(null, true, text, repurposed)
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

    /** 弹层判定的下边界：顶部 [TOP_EXCLUDE_RATIO] 是直播间头部（主播自己的关注按钮） */
    private fun cardTopLimit(ctx: FlowContext): Int =
        (ctx.actions.screenHeight() * TOP_EXCLUDE_RATIO).toInt()

    /**
     * 在一份快照里判断「弹层里的按钮已经是关注状态」。
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
    private fun alreadyFollowedIn(
        facts: List<Actions.NodeFacts>,
        targets: LiveTargets,
        topLimit: Int
    ): Boolean {
        val labels = ALREADY_FOLLOWED_TEXTS + targets.followed.value
        return facts.any { fact ->
            val text = fact.text?.trim()
            !text.isNullOrEmpty() && fact.bottom > topLimit && labels.contains(text)
        }
    }

    /**
     * 弹层里有没有「私密账号 / 申请关注」这类证据，有则返回命中的那条文案。
     *
     * 同时看 `text` 和 `contentDescription`：申请态多数是按钮文案（text），
     * 但抖音有的版本把状态挂在按钮的 desc 上，两边都扫一遍几乎不额外花钱（同一份快照）。
     *
     * 匹配是**全等**——包含匹配会让「关注」命中「申请关注」，把所有人判成私密账号。
     */
    private fun privateEvidenceIn(
        facts: List<Actions.NodeFacts>,
        hints: Set<String>,
        topLimit: Int
    ): String? {
        if (hints.isEmpty()) return null
        for (fact in facts) {
            if (fact.bottom <= topLimit) continue
            val text = fact.text?.trim()
            if (!text.isNullOrEmpty() && hints.contains(text)) return text
            val desc = fact.desc?.trim()
            if (!desc.isNullOrEmpty() && hints.contains(desc)) return desc
        }
        return null
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
     *
     * @param knownOpen 调用方**刚刚**在弹层里读到过「已关注 / 私密账号」时为 true——弹层必然还在，
     *   直接返回即可，省掉那趟「还开着吗」的遍历（≈1.4s）。关注成功这条路径每关注一个人走一次，
     *   用户对这段的感觉就是「关注完了卡住」。其余路径保持探测，因为盲按返回会退出直播间。
     */
    private suspend fun closeCard(ctx: FlowContext, targets: LiveTargets, knownOpen: Boolean = false) {
        val stillOpen = knownOpen || cardStillOpen(ctx, targets)
        if (!stillOpen) {
            LogStore.d(TAG, "弹层已不在，跳过关闭动作")
            return
        }
        if (!ctx.actions.back()) {
            LogStore.w(TAG, "返回键未生效，弹层可能仍在（下一轮会重新判断）")
        }
        // 裸 delay 而不是 ctx.settle：settle 末尾会 guard()，而手势已经把快照缓存作废了
        // ⇒ 一次全树遍历 8~16 秒。这里紧接着的动作是「滚评论」（非点击、非付费），
        // 风控交给主循环每轮的 guard（2.5 秒降频）就够了，不值得为它再等十几秒。
        delay(CLOSE_SETTLE_MS)
    }

    /**
     * 弹层还在不在：**一趟快照**同时判「关注按钮还在」与「已是已关注态」。
     *
     * 原来拆成 `findCardButton`（一次 collect，≈1.4s）+ `isAlreadyFollowed`（一次 snapshot，≈1.4s），
     * 关一次弹层要 2.8s，而这条路径每关注一个人就走一次。
     * 这里只判「在不在」，不需要挑出面积最大的那颗按钮，所以一趟快照就够。
     */
    private fun cardStillOpen(ctx: FlowContext, targets: LiveTargets): Boolean {
        // lean：只判「关注按钮还在 / 已是已关注态」，两件事都只看 text/desc/bounds
        val facts = ctx.actions.snapshot(ttlMs = 0, lean = true)
        val topLimit = cardTopLimit(ctx)
        if (alreadyFollowedIn(facts, targets, topLimit)) return true
        return facts.any { fact ->
            fact.bottom > topLimit && fact.right > fact.left && fact.bottom > fact.top &&
                ctx.actions.matchesFacts(fact, targets.follow)
        }
    }

    /**
     * 判定「私密账号」后统一走这里：**不计进度、不写已关注名单**，只记进私密名单并关掉弹层。
     *
     * 为什么不能记进已关注名单：那会让「已关人数 / 今日已关注」两个数字说谎，
     * `remainingToday` 也会算错额度（用户要的正是「人数没增加」这个事实被正确对待）。
     * 为什么还要单独存一份：同一个人会反复在评论区说话，不记就得反复点开、反复发申请。
     */
    private suspend fun skipPrivate(
        ctx: FlowContext,
        targets: LiveTargets,
        store: FollowedStore,
        nickname: String,
        evidence: String?
    ) {
        LogStore.w(
            TAG,
            "「$nickname」是私密账号（命中「${evidence ?: "?"}」）：关注只会发出申请、" +
                "粉丝数不会增加，跳过且不计入进度"
        )
        store.markPrivate(nickname)
        ctx.publish(message = "「$nickname」是私密账号，已跳过")
        // 两个调用点都刚在弹层里读到私密证据，弹层必然还在
        closeCard(ctx, targets, knownOpen = true)
        // 拉回最新：不刷新的话这个人会一直留在屏幕上，下一次扫描还得再判一遍
        refreshComments(ctx, "私密账号「$nickname」跳过")
    }

    /**
     * 把弹层里的短文案拼成一行写进日志。
     *
     * 用途只有一个：**下次遇到没收录的申请态文案时能照着补进 `privateAccountHints`**。
     * 抖音的私密账号提示文案在版本间来回改过（已申请 / 已提交请求 / 等待通过…），
     * 与其猜，不如让真机把实际文案吐出来。只在超时这条罕见路径上跑一趟遍历，成本可接受。
     */
    private fun cardLabelsDump(ctx: FlowContext): String {
        val topLimit = cardTopLimit(ctx)
        val labels = ctx.actions.snapshot(ttlMs = 0, lean = true)
            .filter { it.bottom > topLimit }
            .mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() && t.length <= CARD_LABEL_MAX_LEN } }
            .distinct()
        return if (labels.isEmpty()) "（读不到任何短文案）" else labels.joinToString("、")
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

        /** 一屏最多 9 行（真机实测 y 1398~2090），留些余量给批量插入的瞬间 */
        const val MAX_COLLECT = 16
        const val MAX_BUTTON_SCAN = 8

        /** 连这么多次「一行评论都读不到」才算读不到（被浮层盖住 / 配置不匹配） */
        const val MAX_DRY_ROUNDS = 4

        /** 关掉意外浮层后等界面收起的时长（返回动画 + 评论重新挂上节点） */
        const val OVERLAY_RETRY_MS = 1_000L

        /**
         * 只在最新的这么多个**可用**候选里随机挑人。
         *
         * 一屏能挂 9 条评论（实测 y 1398~2090，覆盖好几分钟），整屏随机会让后半程
         * 一直在补关注几分钟前的人。取 3 既保证「挑的是刚说话的」，也保留随机性。
         *
         * 注意：与旧实现的区别是**不再往下顺延**——这里只装本轮首见的行，
         * 首见的人若都已处理过，`awaitFreshScan` 会继续等新评论，而不是退到更旧的行。
         */
        const val NEWEST_WINDOW = 3

        /**
         * 等新评论时的采样间隔。
         *
         * 一次全树遍历在这台真机上约 1.4s，间隔比它短没有意义（旧的 300ms 实际是 1.7s 一刀）；
         * 再叠加 `settle` 的抖动，实际约 2s 一次，和评论插入速度同量级。
         */
        const val FRESH_POLL_MS = 800L

        /**
         * 完全没有新评论时的等待上限。
         *
         * 不做「等不到就挑当前屏」的降级（那正是"关注到的都是几分钟前的人"的来源），
         * 但也不能无限等：5 分钟还没有任何新行，说明这个房间确实没人说话，如实中止并提示换房。
         */
        const val STALL_ABORT_MS = 5 * 60_000L

        /** 等待期间往悬浮窗/通知栏上报的间隔（每 2 秒刷一次太吵） */
        const val REPORT_INTERVAL_MS = 5_000L

        /** 首见行文案的容量上限：长会话里评论会累积上千条，只留最近的一批 */
        const val SEEN_ROWS_CAP = 600

        /**
         * 抖音「评论区脱离底部」时浮出来的回到最新按钮文案（40.8.0 实测 `99+条新消息`）。
         * 用 contains 匹配是因为数字会变（`3条新消息` / `99+条新消息`）。
         */
        val NEW_MESSAGE_TEXTS = listOf("条新消息", "回到最新")

        /** 两次点「新消息」之间的最小间隔：点完给列表时间跳过去，别连点 */
        const val JUMP_INTERVAL_MS = 12_000L

        /** 点完「新消息」后等列表跳到底部的动画 */
        const val JUMP_SETTLE_MS = 1_200L

        /**
         * 关注成功后、真正去刷评论之前的那一下短等。
         *
         * 只要 300ms：**等资料卡收起的动画走完**就够了。
         *
         * 旧值是 2500ms，理由是「抖音要攒够几条新评论才浮出『N条新消息』，等它出现再点」。
         * 那条理由现在已经不成立：刷新通道的第一选择是**直接滚评论容器**
         * （缓存命中 6~12ms），根本不需要等浮标。固定等 2.5 秒 + 后面跟一次全树风控检查，
         * 让「关注成功 → 评论动」硬生生晚 4 秒，是「刷新评论卡顿」里最容易感知的一段。
         */
        const val REFRESH_PROBE_MS = 300L

        /** 扫描间隔小于这个值时不插探测：间隔本身就短，交给下一轮的即时刷新兜底 */
        const val REFRESH_MIN_INTERVAL_MS = 3_000L

        /**
         * 评论可滚动区域的**上边界**占屏比。
         *
         * 0.42 是「主播画面下半部」的位置：再往上就是直播间头部（头像/关注/在线人数），
         * 那里也有可点控件；再往下就压到输入栏。实测评论行集中在 0.55~0.93。
         */
        const val COMMENT_AREA_TOP_RATIO = 0.42f

        /** 评论可滚动区域的下边界（避开底部输入栏）。输入栏实测在 0.93 屏高附近 */
        const val COMMENT_AREA_BOTTOM_RATIO = 0.90f

        /** 可滚动区域太矮（< 160px，约 3 行）就别滚了，滚一屏也只会滚过一条 */
        const val MIN_SCROLL_AREA_HEIGHT = 160

        /**
         * 滚动动作之后等列表把新内容画上去。
         *
         * 180ms：抖音的评论滚动是**瞬时位移**（不是动画，没有惯性），900ms 里绝大部分是白等。
         * 实测这个动作之后紧跟着就是「等扫描间隔」，列表有充足时间重绘，不需要更久。
         */
        const val SCROLL_SETTLE_MS = 180L

        /**
         * 评论列表容器的 view id（`com.ss.android.ugc.aweme:id/r68` 的后半段）。
         *
         * 用 id 直接取容器是因为 framework 的 `findAccessibilityNodeInfosByViewId`
         * 比自己 BFS 快数倍——这是「1 秒内下滑」的关键。
         * ⚠️ r68 是抖音混淆后的资源名，**大版本更新可能变**；变了也不会崩：
         * `findByViewId` 返回 null 后自动退回按区域遍历找 scrollable，只是慢一点。
         */
        const val COMMENT_LIST_VIEW_ID = "r68"

        /**
         * 是否在滚动前后各抓一趟快照、打印「共同行 y 变化 / 新增行数」的取证日志。
         *
         * 默认**关**：这两趟快照要 2.8 秒，而方向已经在真机上连续取证三次
         * （-231 / -103 / -236 px，负值 = 内容上移 = 露出更新的评论），不必每轮都验。
         * 需要重新取证（抖音改版后怀疑方向反了）时把它改成 true 再打一个包即可。
         */
        const val SCROLL_DIAGNOSE = false

        /**
         * 可点评论行的最小高度。
         *
         * 评论自下往上插入时会有一段高度展开动画，实测读到过只有 6px 的行
         * （`行=[36,2164][780,2170]`），点上去落在原地的空白，抖音当成「点空白」什么也不弹，
         * 白等 6 秒超时。低于这个高度的行直接跳过，下一轮它就是正常高度。
         */
        const val MIN_ROW_HEIGHT = 20

        /**
         * 评论行下边界占屏比，**纯比例、不跑遍历**（见 [rowBottomLimit]）。
         *
         * 真机 40.8.0：屏高 2374、输入栏顶 2188（91.4%）、最底一行评论 bottom 2104（88.6%）。
         * 0.915 落在两者之间，误差几像素。
         */
        const val FALLBACK_ROW_LIMIT_RATIO = 0.915

        const val TAP_SETTLE_MS = 600L
        const val COMMENT_WAIT_MS = 2_500L
        const val CLOSE_SETTLE_MS = 500L

        /** 关掉意外弹出的浮层后，等界面稳定再重新判定（M2/M3 的驻留判定已收敛进 LiveGuard） */
        const val CARD_TIMEOUT_MS = 6_000L
        /**
         * 等「取消关注」出现的窗口。
         *
         * 一次快照遍历约 1.4s，3s 只有两次采样，而实测按钮状态刷新常常要 1.5~4s，
         * 于是出现「画面弹出关注成功、进度却不涨」的假失败。放宽到 6s（采样 4 次）。
         */
        const val VERIFY_TIMEOUT_MS = 6_000L
        const val CARD_POLL_MS = 250L

        /**
         * 「直接刷新那颗按钮」这条快速通道的预算，超了必须转整树判定。
         *
         * 这个值是 vc36 回归修出来的（2026-10-30，用户报「点击完关注人数不增加」）：
         * 快速通道只在**按钮就地更新文案**时有效，而实测抖音常常重建资料卡 /
         * 复用那个节点——此时 `refresh()` 依旧返回 true，却永远读不到「取消关注」。
         * 老实现「有按钮就永不跑整树」⇒ 6 秒里一次判定都没做 ⇒ 关注成功却记成失败。
         *
         * 2000ms 的依据：历史注释记录「按钮状态刷新常常要 1.5~4s」，取偏小的一档，
         * 让「来得及就地更新」的那些人走毫秒级路径，其余的人最多多花 2 秒回到整树判定。
         */
        const val QUICK_PROBE_BUDGET_MS = 2_000L

        /** 点「资料卡」后等卡片滑入动画结束，避免拿动画中的 bounds 去点关注 */
        const val CARD_ENTRY_SETTLE_MS = 900L

        /**
         * 诊断日志里「弹层文案」的长度上限。
         *
         * 只要按钮级短文案：昵称、简介、粉丝数这些长文本混进来会把一行日志撑爆，
         * 反而不利于照着补配置。
         */
        const val CARD_LABEL_MAX_LEN = 8

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
            "来了", "关注了主播", "送出", "点亮", "升级", "加入粉丝团", "为主播", "分享了直播间",
            // 评论区跳回最新时会短暂插一行「加载中……」占位，实测被当成昵称收进了候选
            "加载中"
        )

        /**
         * 已关注态的按钮文案（抖音 40.8 实测）：
         * 「取消关注」= 你关注了他；「互相关注」= 双方互关。
         * 配置里的 followed_button 恒为「取消关注」，这里再兜一层互关。
         */
        val ALREADY_FOLLOWED_TEXTS = listOf("取消关注", "互相关注")

        /**
         * 资料卡按钮的第三种「已关注」写法。
         *
         * 只在 [quickProbe] 里认——那里读的是**按钮节点自己**的文案，
         * 不会把弹层正文里的「已关注你」误当成成功。
         */
        const val FOLLOWED_ALT_TEXT = "已关注"

        /**
         * 点评论行有时弹出的是快捷操作栏而不是资料卡，栏里这一项才是资料卡入口。
         * 这是实测（40.8.0）的文案，改版若变化会表现为「点开后等不到关注按钮」。
         */
        const val PROFILE_ENTRY_TEXT = "资料卡"
    }
}
