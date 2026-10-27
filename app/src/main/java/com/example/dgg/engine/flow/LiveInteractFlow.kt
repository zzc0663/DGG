package com.example.dgg.engine.flow

import android.graphics.Rect
import android.os.SystemClock
import com.example.dgg.data.LogStore
import com.example.dgg.data.RoomStore
import com.example.dgg.data.PresetComments
import com.example.dgg.data.Settings
import com.example.dgg.engine.Flow
import com.example.dgg.engine.FlowAbortException
import com.example.dgg.engine.FlowContext
import com.example.dgg.engine.LocatorConfig
import com.example.dgg.engine.PageState
import com.example.dgg.engine.Target
import com.example.dgg.util.Texts
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

        if (settings.enterMode != ENTER_MANUAL) {
            // 系统推荐进入要「回首页 → 在推荐流里识别直播卡片 → 点进去」，需要直播卡片的定位特征，
            // 这一版没有校准，做出来就是猜。宁可明确告诉用户，也不假装支持。
            LogStore.w(TAG, "进入方式选择的是「系统推荐」，本版本仅支持手动进入，按手动模式继续")
            ctx.publish(message = "本版本只支持手动进入直播间，按当前位置继续")
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

        ctx.publish(counterLabel = "已发评论", followed = 0, target = 0, message = "准备就绪")
        LogStore.i(
            TAG,
            "开始：间隔 ${settings.commentGapMin}~${settings.commentGapMax}s，" +
                "评论模式 ${if (settings.commentMode == COMMENT_PRESET) "预设(${presets.size}条)" else "复制最新"}" +
                "，自动点赞 ${if (settings.likeOnComment) "每 ${settings.likeTrigger} 条" else "关闭"}"
        )

        var sent = 0
        var likes = 0
        var sinceLike = 0
        var failures = 0
        var micTried = false
        var lastComment = ""

        while (true) {
            if (ctx.stopped()) return
            ctx.awaitResume()
            ctx.guard()

            if (!ensureLiveRoom(ctx)) {
                throw FlowAbortException(
                    "已经不在直播间（可能被误退出或被弹窗盖住），互动已停止。" +
                        "请重新进入目标直播间再开始"
                )
            }

            // 连麦只试一次：反复申请比不申请危险得多
            if (settings.micRequest && !micTried) {
                micTried = true
                tryApplyMic(ctx, targets)
            }

            val comment = buildComment(ctx, targets, settings, presets, lastComment)
            if (comment == null) {
                failures++
                ctx.publish(message = "拿不到可发的评论内容（$failures/$MAX_CONTENT_FAILS）")
                if (failures >= MAX_CONTENT_FAILS) {
                    throw FlowAbortException(
                        "连续 $MAX_CONTENT_FAILS 次生成不出可发的评论。" +
                            "复制模式下通常是直播间没人说话、或 live_comment_line 与当前抖音版本不匹配"
                    )
                }
                ctx.settle(RETRY_WAIT_MS)
                continue
            }

            if (!waitBeforeComment(ctx, settings)) return

            if (sendComment(ctx, targets, comment)) {
                sent++
                sinceLike++
                lastComment = comment
                failures = 0
                ctx.publish(followed = sent, message = "已发评论 $sent 条")
                LogStore.i(TAG, "已发评论 #$sent：「$comment」")

                if (settings.likeOnComment && sinceLike >= settings.likeTrigger.coerceAtLeast(1)) {
                    likes += likeBurst(ctx, settings)
                    sinceLike = 0
                    ctx.publish(followed = sent, message = "已发评论 $sent 条 · 已点赞 $likes 次")
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
     */
    private suspend fun ensureLiveRoom(ctx: FlowContext): Boolean = LiveGuard.ensureOnAir(ctx)

    // ==================== 评论内容 ====================

    /**
     * 生成这一轮要发的评论。
     *
     * - 预设模式：从话术库里随机挑，尽量不连续发同一句；
     * - 复制模式：取直播间**最新**一条别人的评论正文——注意是取正文不是整行，
     *   否则会把自己的昵称一起发出去，等于自曝。
     */
    private fun buildComment(
        ctx: FlowContext,
        targets: LiveTargets,
        settings: Settings,
        presets: List<String>,
        lastComment: String
    ): String? = when (settings.commentMode) {
        COMMENT_PRESET -> {
            val pool = presets.filter { it != lastComment }.ifEmpty { presets }
            if (pool.isEmpty()) null else pool.random()
        }

        else -> copyLatestComment(ctx, targets, lastComment) ?: run {
            // 直播间里全是系统提示/广告/表情时，复制模式会拿不到一条能用的正文。
            // 这时候改发预设话术，比直接中止整个流程合理——用户要的是「互动」，不是「必须复制」。
            val fallback = presets.filter { it != lastComment }.ifEmpty { presets }.randomOrNull()
            if (fallback != null) {
                LogStore.w(TAG, "复制模式本轮没有可用正文（系统提示/广告/重复已全部排除），改发预设话术")
            }
            fallback
        }
    }

    private fun copyLatestComment(ctx: FlowContext, targets: LiveTargets, lastComment: String): String? {
        val line = targets.line ?: return null
        // collect 是按树的遍历顺序返回的，评论列表从上到下 → 最后一条就是最新的
        val bodies = ctx.actions.collect(line, MAX_COPY_SCAN)
            .mapNotNull { ctx.actions.labelOf(it) }
            .mapNotNull { Texts.afterColon(it) }

        val candidate = bodies.reversed().firstOrNull { body ->
            body.length in MIN_COPY_LEN..MAX_COPY_LEN &&
                body != lastComment &&
                !isSystemish(body)
        }
        if (candidate == null) {
            LogStore.d(TAG, "本次扫到 ${bodies.size} 条评论，没有可复制的正文（可能是系统提示或与上一条重复）")
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
     * 找输入控件刻意不用配置里的 comment_input（「说点什么」）：无障碍树里那句占位提示
     * 往往是一个 TextView，真正的 EditText 是另一个节点，对提示节点 SET_TEXT 必然失败。
     */
    private suspend fun sendComment(ctx: FlowContext, targets: LiveTargets, text: String): Boolean {
        val entry = ctx.actions.find(targets.input)
        if (entry == null) {
            LogStore.w(TAG, "未找到评论输入框 ${targets.input}（直播间评论面板可能未展开）")
            return false
        }
        if (!ctx.actions.tapNode(entry, "评论输入框")) {
            LogStore.w(TAG, "点击评论输入框失败")
            return false
        }
        ctx.settle(INPUT_FOCUS_MS)

        val editable = ctx.actions.findEditable()
        if (editable == null) {
            LogStore.w(TAG, "点了输入区，但屏上没有可编辑控件（面板可能没起来）")
            return false
        }
        if (!ctx.actions.typeText(editable, text, "评论输入框") { ctx.actions.findEditable() }) {
            return false
        }

        val send = ctx.actions.waitFor(targets.send, SEND_WAIT_MS)
        if (send == null) {
            LogStore.w(TAG, "输入框里已有内容，但 ${SEND_WAIT_MS}ms 内没等到「发送」按钮")
            return false
        }
        if (!ctx.actions.tapNode(send, "发送按钮")) {
            LogStore.w(TAG, "点击「发送」失败")
            return false
        }

        // 「评论过快」通常是一条一闪而过的 toast，发完立刻查一次最有价值
        ctx.guard()
        return awaitSubmitted(ctx, targets, text)
    }

    /**
     * 校验评论真的提交了。
     *
     * 判据是**输入区回到占位态**（重新出现「说点什么」）：发送成功后抖音会清空输入框并收起面板。
     * 不校验「评论出现在列表里」——列表刷新快、自己的昵称也拿不到，反而容易误判。
     */
    private suspend fun awaitSubmitted(ctx: FlowContext, targets: LiveTargets, text: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (ctx.actions.find(targets.input) != null) {
                LogStore.d(TAG, "输入区已回到占位态，判定「$text」已提交")
                return true
            }
            ctx.guard()
            delay(VERIFY_POLL_MS)
        }
        LogStore.w(TAG, "发「$text」后 ${VERIFY_TIMEOUT_MS}ms 内输入区未回到占位态，按未成功处理")
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

    /** 从配置取本流程要用的目标；缺关键项直接中止，不做静默降级 */
    private class LiveTargets(
        val input: Target,
        val send: Target,
        val mic: Target?,
        val line: Target?
    ) {
        companion object {
            fun from(config: LocatorConfig): LiveTargets {
                val input = config.target("comment_input")
                    ?: throw FlowAbortException("定位配置缺少 comment_input，无法找到评论输入框")
                val send = config.target("live_comment_send")
                    ?: throw FlowAbortException("定位配置缺少 live_comment_send，无法点发送")
                return LiveTargets(
                    input = input,
                    send = send,
                    mic = config.target("live_mic_button"),
                    line = config.target("live_comment_line")
                )
            }
        }
    }

    private companion object {
        const val TAG = "直播互动"

        /** 与界面一致：0=手动进入 1=系统推荐 */
        const val ENTER_MANUAL = 0

        /** 与界面一致：0=复制评论 1=预设评论 */
        const val COMMENT_PRESET = 1

        const val MAX_COPY_SCAN = 15
        const val MIN_COPY_LEN = 2
        const val MAX_COPY_LEN = 20

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
