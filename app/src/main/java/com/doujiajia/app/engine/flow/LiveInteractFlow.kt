package com.doujiajia.app.engine.flow

import android.graphics.Rect
import android.os.SystemClock
import com.doujiajia.app.data.InteractStore
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.data.RoomStore
import com.doujiajia.app.data.PresetComments
import com.doujiajia.app.data.Settings
import com.doujiajia.app.data.SettingsStore
import com.doujiajia.app.engine.Flow
import com.doujiajia.app.engine.FlowAbortException
import com.doujiajia.app.engine.FlowContext
import com.doujiajia.app.engine.LocatorConfig
import com.doujiajia.app.engine.PageState
import com.doujiajia.app.engine.Target
import com.doujiajia.app.util.Texts
import kotlinx.coroutines.delay

/**
 * M3 主流程：**直播间互动**（发评论 / 点赞 / 申请连麦）。
 *
 * 前提与 M2 相同：用户自己进好直播间，脚本不搜索、不切换直播间。
 * 一轮 = 等随机间隔 → 生成一条评论 → 点输入框 → 逐字输入 → 点发送 → 校验已提交；
 * 每发满 N 条评论穿插一次点赞（次数随机）。
 *
 * 三条硬约束（都是降封号项，不是可选项）：
 * 1. **评论内容是最大风险**，不是行为本身。预设话术只放通用短句，复制模式只搬「别人的正文」，
 *    并且剔除系统提示、超长内容和重复内容；
 * 2. **命中「评论过快」一类提示立刻停机且不重试**——继续试只会把限流升级成封号；
 * 3. 连麦**只申请一次**。反复申请是这个直播间里最扎眼的异常行为。
 *
 * vc60 起补上**安全阀**（此前这个循环是 `while (true)`，只有报错或手动停才退出）：
 * 本轮目标 / 每日上限 / 定条数休息三项都在 [InteractStore] 记账，
 * 且像 M2 一样**每轮重读设置**，面板上改了立刻生效。
 * 之前「跑起来就不知道什么时候停」是实际用过的人才提得出来的问题——直播间没人说话时
 * 它会一路发下去，发到被平台限流为止。
 */
class LiveInteractFlow : Flow {

    override val name = "直播互动"

    override suspend fun run(ctx: FlowContext) {
        val settings = ctx.settings
        val targets = LiveTargets.from(ctx.config)

        if (!awaitDouyinForeground(ctx, DOUYIN_FOREGROUND_TIMEOUT_MS)) {
            throw FlowAbortException(
                "等待 ${DOUYIN_FOREGROUND_TIMEOUT_MS / 1000} 秒仍未切到抖音前台。" +
                    "请手动打开抖音并进入目标直播间后再开始"
            )
        }

        // 「选择直播间」一次性指令：同 M2，先导航进收藏的房间
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

        val detection = ctx.detector.detect()
        if (detection.page != PageState.LIVE_ROOM) {
            throw FlowAbortException(
                "开始前必须停在直播间画面。当前页面判定 ${detection.page.name}" +
                    "（${detection.score}/${detection.threshold}）。请手动进入目标直播间后再开始"
            )
        }

        val presets = PresetComments.load(ctx.appContext)
        if (settings.commentMode == COMMENT_PRESET && presets.isEmpty()) {
            throw FlowAbortException(
                "评论模式是「预设评论」，但一条话术都没读到（assets/live_comments.json 缺失或为空）"
            )
        }

        val store = InteractStore.get(ctx.appContext)
        val remaining = store.remainingToday(settings.commentDailyLimit)
        if (remaining <= 0) {
            throw FlowAbortException(
                "今日评论已达上限 ${settings.commentDailyLimit}（已发 ${store.todayCount()} 条），明天再跑"
            )
        }

        // 目标与 M2 同口径：**每轮重读**（悬浮面板上改的立刻生效），启动时只校验一次额度。
        var target = minOf(settings.commentTarget.coerceAtLeast(1), remaining)
        ctx.publish(counterLabel = "已发评论", followed = 0, target = target, message = "准备就绪")
        LogStore.i(
            TAG,
            "开始：本轮目标 $target 条（界面设置 ${settings.commentTarget} / 今日剩余 $remaining）" +
                "，间隔 ${settings.commentGapMin}~${settings.commentGapMax}s" +
                "，模式 ${if (settings.commentMode == COMMENT_PRESET) "预设(${presets.size}条)" else "复制最新"}" +
                "，每 ${settings.commentRestAfterN} 条休息 ${settings.commentRestSeconds}s" +
                "，自动点赞 ${if (settings.likeOnComment) "每 ${settings.likeTrigger} 条" else "关闭"}"
        )

        var sent = 0
        var likes = 0
        var sinceLike = 0
        var failures = 0
        var micTried = false

        /**
         * 最近发过的评论。是**窗口**不是单个 `lastComment`：
         * 真机基线（`R-20261031-184122`）97 秒发 6 条，同一句出现 3 次——
         * 「不跟上一条重复」挡不住 A-B-A-B 这种交替，观感上就是机器在刷同一句。
         */
        val recent = ArrayDeque<String>(RECENT_WINDOW + 1)

        /**
         * 本轮用的设置快照，每轮从 [SettingsStore] 重取。
         *
         * UI 参数的 class 意义上全是快照——用户在悬浮面板上改的**数值**（间隔、点赞次数、
         * 要不要休息）以前要停止任务再启动才生效。这里跟 M2 关注流程对齐：每轮开头刷新一次。
         */
        var st = settings

        /** 只认「这两个字段变了」——拿会自然递减的 `remainingToday` 比会导致每轮都误报 */
        var lastSeenTarget = settings.commentTarget
        var lastSeenDailyLimit = settings.commentDailyLimit

        while (sent < target) {
            if (ctx.stopped()) return
            ctx.awaitResume()
            ctx.guard()

            // 实时目标与上限：改小到已达标就自然结束，改大继续跑
            val fresh = SettingsStore.get(ctx.appContext).current
            if (fresh.commentTarget != lastSeenTarget || fresh.commentDailyLimit != lastSeenDailyLimit) {
                lastSeenTarget = fresh.commentTarget
                lastSeenDailyLimit = fresh.commentDailyLimit
                val remainingNow = store.remainingToday(fresh.commentDailyLimit)
                if (remainingNow <= 0) {
                    LogStore.i(
                        TAG,
                        "每日上限已调为 ${fresh.commentDailyLimit}，今日已发 ${store.todayCount()} 条，停止"
                    )
                    ctx.publish(message = "已达每日上限 ${fresh.commentDailyLimit}")
                    break
                }
                val newTarget = minOf(fresh.commentTarget.coerceAtLeast(1), remainingNow)
                if (newTarget != target) {
                    LogStore.i(TAG, "本轮目标已从 $target 调整为 $newTarget（界面实时修改）")
                    target = newTarget
                    ctx.publish(target = target, message = "本轮目标已改为 $newTarget 条")
                }
                if (sent >= target) break
            }
            st = fresh

            if (!ensureLiveRoom(ctx)) {
                throw FlowAbortException(
                    "已经不在直播间（可能被误退出或被弹窗盖住），互动已停止。" +
                        "请重新进入目标直播间再开始"
                )
            }

            // 连麦只试一次：反复申请比不申请危险得多
            if (st.micRequest && !micTried) {
                micTried = true
                tryApplyMic(ctx, targets)
            }

            val draft = buildComment(ctx, targets, st, presets, recent)
            if (draft == null) {
                failures++
                ctx.publish(message = "拿不到可发的评论内容（$failures/$MAX_CONTENT_FAILS）")
                if (failures >= MAX_CONTENT_FAILS) {
                    // 与 M2「读不到评论」同一套处置（2026-10-29）：拿不到可复制的正文
                    // 最常见的是评论区被浮层盖住、或直播间一时没人说话，
                    // 都不是该结束任务的理由。暂停等待、持续探测，读到就自动恢复。
                    failures = 0
                    val recovered = RecoveryWait.waitForComments(
                        ctx,
                        "连续 $MAX_CONTENT_FAILS 次拿不到可发的评论内容"
                    ) {
                        if (buildComment(ctx, targets, st, presets, recent) != null) 1 else 0
                    }
                    if (!recovered) return
                    continue
                }
                ctx.settle(RETRY_WAIT_MS)
                continue
            }
            val comment = draft.text

            if (!waitBeforeComment(ctx, st)) return

            if (sendComment(ctx, targets, comment)) {
                sent++
                sinceLike++
                rememberSent(recent, comment)
                failures = 0
                val todayCount = store.bumpToday()
                ctx.publish(followed = sent, target = target, message = "已发评论 $sent/$target 条")
                LogStore.i(
                    TAG,
                    "已发评论 #$sent/$target：「$comment」（今日 $todayCount/${st.commentDailyLimit}）"
                )
                if (draft.fromFallback) {
                    ctx.publish(message = "复制模式没抄到可用正文，本条用的是预设话术")
                }

                if (st.likeOnComment && sinceLike >= st.likeTrigger.coerceAtLeast(1)) {
                    likes += likeBurst(ctx, st)
                    sinceLike = 0
                    ctx.publish(
                        followed = sent,
                        target = target,
                        message = "已发评论 $sent/$target 条 · 已点赞 $likes 次"
                    )
                }

                // 定条数休息：评论比关注更容易触发频控，这里的休息是**节奏**不是点缀。
                // 休息前不主动刷新评论区——发评论不像关注那样依赖列表位置。
                if (st.restComment && st.commentRestAfterN > 0 &&
                    sent % st.commentRestAfterN == 0 && sent < target
                ) {
                    LogStore.i(TAG, "到达休息点（每 ${st.commentRestAfterN} 条），休息 ${st.commentRestSeconds}s")
                    ctx.publish(message = "已发 $sent 条，按设置休息 ${st.commentRestSeconds}s")
                    if (!waitRest(ctx, st.commentRestSeconds * 1_000L)) return
                }
            } else {
                failures++
                ctx.publish(message = "发送失败 $failures 次")
                if (failures >= MAX_SEND_FAILS) {
                    throw FlowAbortException(
                        "连续 $MAX_SEND_FAILS 次发送评论失败，已停止。" +
                            "常见原因：live_comment_send（发送按钮）或 comment_input 定位与当前抖音版本不匹配，" +
                            "也可能是账号被限制了评论"
                    )
                }
                ctx.settle(RETRY_WAIT_MS)
            }
        }

        ctx.publish(message = "已达本轮目标 $target 条评论")
        LogStore.i(
            TAG,
            "本轮完成：发评论 $sent 条，达目标 $target 条" +
                "（今日累计 ${store.todayCount()} 条，点赞 $likes 次）"
        )
    }

    // ==================== 前置与环境 ====================

    private suspend fun awaitDouyinForeground(ctx: FlowContext, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (ctx.actions.isDouyinForeground()) {
                delay(FRONT_SETTLE_MS)
                return true
            }
            delay(FRONT_POLL_MS)
        }
        return false
    }

    /**
     * 确认还在直播间。被浮层盖住（键盘、礼物面板、资料卡）就按一次返回把它关掉。
     *
     * 与 M2 共用 [LiveGuard]：容忍人为切换直播间的转场（期间读到 UNKNOWN 是正常的，
     * 早期实现在这里按返回，把用户刚切到的直播间退掉了），会在抖音不在前台时自动拉回。
     *
     * 走 [LiveGuard.ensureOnAirOrWait]：页面**整个读不出来**（未识别的全屏浮层，实测有
     * 「人气开场」助力页）时不再直接终止，而是暂停 + 持续探测，浮层一没就自动接着发评论。
     */
    private suspend fun ensureLiveRoom(ctx: FlowContext): Boolean = LiveGuard.ensureOnAirOrWait(ctx)

    // ==================== 评论内容 ====================

    /**
     * 生成这一轮要发的评论。
     *
     * - 预设模式：从话术库里随机挑，避开 [recent] 窗口里发过的句子；
     * - 复制模式：取直播间**最新**一条别人的评论正文——注意是取正文不是整行，
     *   否则会把自己的昵称一起发出去，等于自曝。
     *
     * [fromFallback] 是给界面看的：复制模式拿不到正文时会改发预设话术，
     * 这件事以前只写进日志，用户看到的是「互动在正常跑，但发的全是自己库存的话」。
     */
    private class Draft(val text: String, val fromFallback: Boolean)

    private fun buildComment(
        ctx: FlowContext,
        targets: LiveTargets,
        settings: Settings,
        presets: List<String>,
        recent: Collection<String>
    ): Draft? = when (settings.commentMode) {
        COMMENT_PRESET -> pickPreset(presets, recent)?.let { Draft(it, false) }

        else -> copyLatestComment(ctx, targets, recent)?.let { Draft(it, false) } ?: run {
            // 直播间里全是系统提示/广告/表情时，复制模式会拿不到一条能用的正文。
            // 这时候改发预设话术，比直接中止整个流程合理——用户要的是「互动」，不是「必须复制」。
            val fallback = pickPreset(presets, recent)
            if (fallback != null) {
                LogStore.w(TAG, "复制模式本轮没有可用正文（系统提示/广告/重复已全部排除），改发预设话术")
            }
            fallback?.let { Draft(it, true) }
        }
    }

    /**
     * 挑一条预设话术，避开 [recent] 窗口里刚发过的。窗口全遮住就把话术库原样放开
     * （宁可重复也不空手——这一轮已经在等间隔了，再返回 null 等于白等）。
     */
    private fun pickPreset(presets: List<String>, recent: Collection<String>): String? {
        if (presets.isEmpty()) return null
        val pool = presets.filter { it !in recent }.ifEmpty { presets }
        return pool.random()
    }

    /** 记进重复窗口：[RECENT_WINDOW] 是上限，满了丢最早的一条 */
    private fun rememberSent(recent: ArrayDeque<String>, text: String) {
        recent.remove(text)
        recent.addLast(text)
        while (recent.size > RECENT_WINDOW) recent.removeFirst()
    }

    private fun copyLatestComment(
        ctx: FlowContext,
        targets: LiveTargets,
        recent: Collection<String>
    ): String? {
        val line = targets.line ?: return null
        // collect 是按树的遍历顺序返回的，评论列表从上到下 → 最后一条就是最新的
        val bodies = ctx.actions.collect(line, MAX_COPY_SCAN)
            .mapNotNull { ctx.actions.labelOf(it) }
            .mapNotNull { Texts.afterColon(it) }

        val candidate = bodies.reversed().firstOrNull { body ->
            body.length in MIN_COPY_LEN..MAX_COPY_LEN &&
                body !in recent &&
                !isSystemish(body)
        }
        if (candidate == null) {
            LogStore.d(TAG, "本次扫到 ${bodies.size} 条评论，没有可复制的正文（可能是系统提示或与最近发过的重复）")
        }
        return candidate
    }

    /**
     * 系统提示（「xx 来了」「xx 送出 xx」）不是人类评论，复制出来很假。
     *
     * 同时挡掉**会被平台当成垃圾内容**的正文：复制评论模式是把别人的话原样再发一遍，
     * 万一抄到导流/广告/骂人的内容，代价全落在自己账号头上。这里宁可少发一条，
     * 也不做「先发了再说」。
     */
    private fun isSystemish(body: String): Boolean {
        if (body.startsWith("[") || body.endsWith("]")) return true
        if (SYSTEM_HINTS.any { body.contains(it) }) return true
        if (SPAM_HINTS.any { body.contains(it, ignoreCase = true) }) return true
        // 纯符号/纯表情
        if (body.none { it.isLetterOrDigit() || it.code > 0x2E80 }) return true
        return false
    }

    // ==================== 发送 ====================

    /**
     * 发一条评论：点输入框 → 找**真正的输入控件** → 逐字输入 → 点发送 → 校验已提交。
     *
     * 输入框和发送按钮都按「viewId 快路径 → 文案兜底」两组候选取（见 [Actions.findAny]）：
     * 只有文案时要跑全树（直播间 5~7 秒），只有 viewId 则在抖音混淆资源名一改就彻底用不了。
     *
     * 找输入控件刻意不用配置里的 comment_input（「说点什么」）：无障碍树里那句占位提示
     * 往往是一个 TextView，真正的 EditText 是另一个节点，对提示节点 SET_TEXT 必然失败。
     */
    private suspend fun sendComment(
        ctx: FlowContext,
        targets: LiveTargets,
        text: String
    ): Boolean {
        val entry = ctx.actions.findAny(targets.input)
        if (entry == null) {
            LogStore.w(TAG, "未找到评论输入框 ${targets.input}（直播间评论面板可能未展开）")
            return false
        }
        if (!ctx.actions.tapNode(entry, "评论输入框")) {
            LogStore.w(TAG, "点击评论输入框失败")
            return false
        }
        ctx.settle(INPUT_FOCUS_MS)

        // 先问「现在谁有输入焦点」（一次 IPC），没焦点才退回按 isEditable 的全树遍历
        val editable = ctx.actions.findInputEdit()
        if (editable == null) {
            LogStore.w(TAG, "点了输入区，但屏上没有可编辑控件（面板可能没起来）")
            return false
        }
        if (!ctx.actions.typeText(editable, text, "评论输入框") { ctx.actions.findInputEdit() }) {
            return false
        }

        val send = ctx.actions.waitForFirst(targets.send, SEND_WAIT_MS)
        if (send == null) {
            LogStore.w(TAG, "输入框里已有内容，但 ${SEND_WAIT_MS}ms 内没等到「发送」按钮 ${targets.send}")
            return false
        }
        if (!ctx.actions.tapNode(send, "发送按钮")) {
            LogStore.w(TAG, "点击「发送」失败")
            return false
        }

        // 「评论过快」通常是一条一闪而过的 toast，发完立刻查一次最有价值。
        // **必须 force**：guard 有 5 秒降频窗口，而前面刚 settle 过（已经查了一次），
        // 不强制的话这一下会被跳过 —— 而它恰恰是唯一能抓到那条 toast 的时机。
        ctx.guard(force = true)
        return awaitSubmitted(ctx, targets, text)
    }

    /**
     * 校验评论真的提交了。两条判据，命中**任意一条**就算成功。
     *
     * ① **输入区回到占位态**（重新出现「说点什么」）：发送成功后抖音会清空输入框并收起面板。
     *    这是老判据。它的弱点是依赖那句占位文案——抖音一改版就恒真不起来，只能判失败。
     * ② **屏上没有焦点且输入已清空**（A5 新增的正向证据）：发送成功的另一面是
     *    「面板收起来了」，而收起来的表现就是拿不到输入焦点。这条不依赖任何文案，
     *    只要 TikTok 不改「发完收面板」这个交互就一直成立。
     *
     * 两者是 **OR** 不是 AND：任何一条单独成立都足以说明「面板已经回到待输入态」。
     * 反过来做成 AND 的话，遇到「文案改了但焦点确实丢了」的版本会把明明成功的评论判成失败，
     * 连续 3 次就整个流程中止——那是比漏一个占位提示严重得多的方向。
     *
     * ⚠️ 不校验「评论出现在列表里」——列表刷新快、自己的昵称也拿不到，反而容易误判。
     * ⚠️ 这里刻意只用**文本目标**（[LiveTargets.inputVisible]）判①，不用 viewId：
     *    `h7l` 那个节点无论有没有输入过都挂在底栏，拿它当判据会得出「永远成功」。
     */
    private suspend fun awaitSubmitted(ctx: FlowContext, targets: LiveTargets, text: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (ctx.actions.findAny(targets.inputVisible) != null) {
                LogStore.d(TAG, "输入区已回到占位态，判定「$text」已提交")
                return true
            }
            val focused = ctx.actions.focusedEditable()
            if (focused == null) {
                LogStore.d(TAG, "评论面板已收起（无输入焦点），判定「$text」已提交")
                return true
            }
            // 焦点还在但字已经没了：抖音在这种版本上「清空输入框」先于「收面板」发生
            if (ctx.actions.textOf(focused).isNullOrEmpty()) {
                LogStore.d(TAG, "输入框已清空，判定「$text」已提交")
                return true
            }
            ctx.guard()
            delay(VERIFY_POLL_MS)
        }
        LogStore.w(TAG, "发「$text」后 ${VERIFY_TIMEOUT_MS}ms 内两条判据都没满足，按未成功处理")
        return false
    }

    // ==================== 点赞 / 连麦 ====================

    /**
     * 连发若干次点赞。次数取「每次自动点赞次数」的随机区间。
     *
     * **为什么是双击画面**：40.8.0 直播间底栏只有「表情入口 / 小心心 / 礼物 / 更多面板」四颗，
     * 其中「小心心」是 **1 钻付费礼物**而不是点赞（点下去会弹支付确认），
     * 节点树里也找不到任何带「点赞」文案的可点控件。双击画面是这个界面里唯一非付费的点赞入口。
     *
     * 落点先过 [Actions.freePointIn] 校验，确保底下没有可点控件——画面上飘着礼物特效和
     * 送礼用户头像，压上去会弹资料卡（实测踩过）。取不到空白点就这一轮不点赞。
     *
     * 局限：**点赞是否真的生效无法定量验证**。万人房的「本场点赞」每秒涨几百，
     * 我们那几下被自然增长完全淹没。所以这里记录的是「已发出 N 次点赞手势」，不宣称成功。
     */
    private suspend fun likeBurst(ctx: FlowContext, settings: Settings): Int {
        val times = ctx.humanizer.intIn(settings.likeCountMin, settings.likeCountMax)
            .coerceIn(1, MAX_LIKES_PER_BURST)
        var done = 0
        for (i in 1..times) {
            if (ctx.stopped()) break
            val spot = ctx.actions.freePointIn(likeArea(ctx))
            if (spot == null) {
                LogStore.w(TAG, "点赞区域全是可点控件（礼物特效/人物卡片），本轮放弃点赞")
                break
            }
            if (!ctx.actions.doubleTap(spot)) break
            done++
            delay(ctx.humanizer.longIn(LIKE_GAP_MIN_MS, LIKE_GAP_MAX_MS))
        }
        ctx.guard()
        LogStore.i(TAG, "已发出 $done/$times 次点赞手势（双击空白画面）")
        return done
    }

    /** 双击点赞的安全区域：画面上部中间，避开评论列表 / 右侧按钮栏 / 顶部主播信息 */
    private fun likeArea(ctx: FlowContext): Rect {
        val w = ctx.actions.screenWidth()
        val h = ctx.actions.screenHeight()
        return Rect(
            (w * LIKE_AREA_LEFT).toInt(),
            (h * LIKE_AREA_TOP).toInt(),
            (w * LIKE_AREA_RIGHT).toInt(),
            (h * LIKE_AREA_BOTTOM).toInt()
        )
    }

    /**
     * 申请连麦：**只点一次**，不确认任何后续弹窗。
     *
     * 后面的确认弹窗（如果有）交给用户肉眼看着处理——自动点「确认」意味着脚本会在
     * 不知道弹窗内容的情况下同意任何东西，这个风险不能接受。
     */
    private suspend fun tryApplyMic(ctx: FlowContext, targets: LiveTargets) {
        val button = targets.mic
        if (button == null) {
            LogStore.w(TAG, "定位配置缺少 live_mic_button，跳过申请连麦")
            return
        }
        val node = ctx.actions.find(button)
        if (node == null) {
            LogStore.w(TAG, "没找到连麦/连线按钮 ${button}（直播间可能不支持连麦），跳过")
            return
        }
        if (ctx.actions.tapNode(node, "连麦按钮")) {
            LogStore.i(TAG, "已点击连麦按钮；如出现确认弹窗需人工处理，本流程不会重复申请")
            ctx.settle(MIC_SETTLE_MS)
        }
    }

    // ==================== 间隔 ====================

    /**
     * 发送间隔：在「评论发送间隔」区间里随机取值，并且**分片等待**。
     *
     * 为什么要分片：区间上限是分钟级时，一次 `delay(90_000)` 期间既响应不了暂停，
     * 也响应不了停止。切成 ≤1.2s 的小段后，每段之间都查一次暂停/停止/风控。
     *
     * @return false 表示期间被停止，调用方应直接退出
     */
    private suspend fun waitBeforeComment(ctx: FlowContext, settings: Settings): Boolean {
        val low = minOf(settings.commentGapMin, settings.commentGapMax).coerceAtLeast(MIN_GAP_SEC)
        val high = maxOf(settings.commentGapMin, settings.commentGapMax).coerceAtLeast(low)
        var remaining = ctx.humanizer.longIn(low * 1_000L, high * 1_000L)

        ctx.publish(message = "等待 ${remaining / 1000}s 后发下一条评论")
        LogStore.d(TAG, "下一轮评论间隔 ${remaining}ms")
        while (remaining > 0) {
            if (ctx.stopped()) return false
            ctx.awaitResume()
            val slice = minOf(remaining, ctx.humanizer.longIn(SLICE_MIN_MS, SLICE_MAX_MS))
            ctx.settle(slice)
            remaining -= slice
        }
        return !ctx.stopped()
    }

    /**
     * 定条数休息。与 [waitBeforeComment] 同一套分片，差别是**要报剩余秒数**：
     * 60 秒的等待期间界面上一句话不变，用户会以为卡死了（M2 的休息实现就是这个毛病）。
     *
     * @return false 表示期间被停止
     */
    private suspend fun waitRest(ctx: FlowContext, totalMs: Long): Boolean {
        var remaining = totalMs.coerceAtLeast(1_000L)
        var sincePublish = 0L
        while (remaining > 0) {
            if (ctx.stopped()) return false
            ctx.awaitResume()
            val slice = minOf(remaining, ctx.humanizer.longIn(SLICE_MIN_MS, SLICE_MAX_MS))
            ctx.settle(slice)
            remaining -= slice
            sincePublish += slice
            if (sincePublish >= REST_PUBLISH_EVERY_MS && remaining > 0) {
                sincePublish = 0
                ctx.publish(message = "休息中，还有 ${remaining / 1000}s")
            }
        }
        return !ctx.stopped()
    }

    /**
     * 从配置取本流程要用的目标；缺关键项直接中止，不做静默降级。
     *
     * [input] / [send] 是**候选链**（viewId 在前、文案兜底在后），见 [Actions.findAny]；
     * [inputVisible] 单独留一条纯文案目标，只用于「面板有没有收起来」的判据
     * （viewId 节点恒在，不能当这个判据，见 [awaitSubmitted]）。
     */
    private class LiveTargets(
        val input: List<Target>,
        val send: List<Target>,
        val inputVisible: List<Target>,
        val mic: Target?,
        val line: Target?
    ) {
        companion object {
            fun from(config: LocatorConfig): LiveTargets {
                val fallback = config.target("comment_input")
                    ?: throw FlowAbortException("定位配置缺少 comment_input，无法判断评论面板是否已收起")
                val sendFallback = config.target("live_comment_send")
                    ?: throw FlowAbortException("定位配置缺少 live_comment_send，无法点发送")
                val input = listOfNotNull(config.target("comment_input_fast"), fallback)
                val send = listOfNotNull(config.target("live_comment_send_fast"), sendFallback)
                if (input.size > 1) LogStore.d(TAG, "评论输入框启用 viewId 快路径（${input.first()}）")
                if (send.size > 1) LogStore.d(TAG, "发送按钮启用 viewId 快路径（${send.first()}）")
                return LiveTargets(
                    input = input,
                    send = send,
                    inputVisible = listOf(fallback),
                    mic = config.target("live_mic_button"),
                    line = config.target("live_comment_line")
                )
            }
        }
    }

    private companion object {
        const val TAG = "直播互动"

        /** 与界面一致：0=复制评论 1=预设评论 */
        const val COMMENT_PRESET = 1

        const val MAX_COPY_SCAN = 15
        const val MIN_COPY_LEN = 2
        const val MAX_COPY_LEN = 20

        /**
         * 重复窗口：多少条之内不重发同一句话。
         * 6 的依据是真机基线（6 条里撞了 3 次）；不取更大值是因为短话术库会被整窗口遮住，
         * 那时 [pickPreset] 会把话术库原样放开，等于这项保护失效。
         */
        const val RECENT_WINDOW = 6

        const val MAX_CONTENT_FAILS = 3
        const val MAX_SEND_FAILS = 3
        const val MAX_LIKES_PER_BURST = 30

        const val MIN_GAP_SEC = 2

        const val DOUYIN_FOREGROUND_TIMEOUT_MS = 20_000L
        const val FRONT_POLL_MS = 300L
        const val FRONT_SETTLE_MS = 900L

        const val INPUT_FOCUS_MS = 900L
        const val SEND_WAIT_MS = 3_000L
        const val VERIFY_TIMEOUT_MS = 3_000L
        const val VERIFY_POLL_MS = 250L

        const val LIKE_GAP_MIN_MS = 120L
        const val LIKE_GAP_MAX_MS = 320L

        /** 双击点赞的安全区域（相对屏幕的比例）：画面上部中间，四边都避开控件 */
        const val LIKE_AREA_LEFT = 0.42f
        const val LIKE_AREA_TOP = 0.16f
        const val LIKE_AREA_RIGHT = 0.62f
        const val LIKE_AREA_BOTTOM = 0.32f
        const val MIC_SETTLE_MS = 1_200L

        const val RECOVER_SETTLE_MS = 900L

        /** 判「不在直播间」要连续确认几次；一次不算数（收键盘等中间态会读到空窗口） */
        const val CONFIRM_CHECKS = 2
        const val RECHECK_MS = 500L
        const val RETRY_WAIT_MS = 3_000L

        const val SLICE_MIN_MS = 600L
        const val SLICE_MAX_MS = 1_200L

        /** 休息期间每隔多久报一次剩余秒数 */
        const val REST_PUBLISH_EVERY_MS = 10_000L

        /** 复制模式要剔除的系统提示片段 */
        val SYSTEM_HINTS = listOf(
            "来了", "关注了主播", "送出", "点亮", "升级", "加入粉丝团", "分享了直播间", "为主播点赞"
        )

        /**
         * 复制模式要剔除的垃圾内容特征：导流、广告、拉互关、联系方式。
         * 抄这些发出去，等于自己发广告——平台罚的是本账号。
         *
         * 「点点关注」「元」是实测补的：第一次真机联调就抄到一条
         * 「点点关注+18000元」，正好从「关注我」这类词的空隙里漏了过去。
         */
        val SPAM_HINTS = listOf(
            "微信", "威信", "vx", "wx", "qq", "加v", "+v", "私信", "私我", "http", "www.",
            "点我", "主页", "主业", "关注我", "互关", "回关", "免费领", "优惠券", "刷单",
            "兼职", "日结", "招聘", "二维码", "扫码",
            "点点关注", "点关注", "关注领", "红包", "福袋", "小黄车", "上车", "秒杀",
            "元", "包邮", "领券", "低价", "福利价"
        )
    }
}
