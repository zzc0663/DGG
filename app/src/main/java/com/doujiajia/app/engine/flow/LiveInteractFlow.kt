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
import com.doujiajia.app.engine.LiveCommentSender
import com.doujiajia.app.engine.Target
import com.doujiajia.app.engine.TargetKind
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

    /**
     * 分母是**评论**目标，不是关注目标。
     *
     * 不声明这一条时，悬浮窗/通知栏会拿 `followTarget`（关注数量）当分母，
     * 目标设 3 条却显示「3/200」——看着像闸门完全没生效（2026-11-01 真机实测）。
     */
    override val targetKind = TargetKind.COMMENT

    override suspend fun run(ctx: FlowContext) {
        val settings = ctx.settings

        // 评论的发送/校验/去重/话术全部交给 [LiveCommentSender]：
        // M2 关注流程休息时发的那条评论走的是**同一份实现**，改一处两边都生效。
        val sender = LiveCommentSender(ctx.config)

        /** 连麦按钮：本流程自己还要用（M2 的连麦是另一套，暂未接入） */
        val micTarget = ctx.config.target("live_mic_button")

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

        val presets = PresetComments.load(ctx.appContext)
        if (settings.commentMode == LiveCommentSender.COMMENT_PRESET && presets.isEmpty()) {
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

        // 开始前必须真的在直播间——但**不能拿 `detect()` 当判据**：
        // 用户点「只跑直播互动」的瞬间前台是本 App，detector 只可能给出 UNKNOWN，
        // vc60 首版就因此 100% 直接 abort，M3 从界面根本起不来（2026-11-01 实测）。
        // 交给 LiveGuard：它先把抖音拉回前台，再等页面稳定到直播间，必要时点进房。
        // 这里允许自动导航——用户刚刚明确点过开始，属于「已表达意图」的场合。
        // 放在本地校验（话术库/每日额度）**之后**：那几项能直接判死，不必先切一次前台。
        if (!ensureLiveRoom(ctx, allowAutoNavigate = true)) {
            val now = ctx.detector.detect()
            throw FlowAbortException(
                "开始前必须停在直播间画面（当前判定 ${now.page.name}，${now.score}/${now.threshold}）。" +
                    "已尝试把抖音拉回前台但未落在直播间，请手动进入目标直播间后再开始"
            )
        }

        // 目标与 M2 同口径：**每轮重读**（悬浮面板上改的立刻生效），启动时只校验一次额度。
        var target = minOf(settings.commentTarget.coerceAtLeast(1), remaining)
        ctx.publish(counterLabel = "已发评论", followed = 0, target = target, message = "准备就绪")
        LogStore.i(
            TAG,
            "开始：本轮目标 $target 条（界面设置 ${settings.commentTarget} / 今日剩余 $remaining）" +
                "，间隔 ${settings.commentGapMin}~${settings.commentGapMax}s" +
                "，模式 ${if (settings.commentMode == LiveCommentSender.COMMENT_PRESET) "预设(${presets.size}条)" else "复制最新"}" +
                "，每 ${settings.commentRestAfterN} 条休息 ${settings.commentRestSeconds}s" +
                "，自动点赞 ${if (settings.likeOnComment) "每 ${settings.likeTrigger} 条" else "关闭"}"
        )

        var sent = 0
        var likes = 0
        var sinceLike = 0
        var failures = 0
        var micTried = false

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
                tryApplyMic(ctx, micTarget)
            }

            val draft = sender.buildDraft(ctx, st, presets)
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
                        if (sender.buildDraft(ctx, st, presets) != null) 1 else 0
                    }
                    if (!recovered) return
                    continue
                }
                ctx.settle(RETRY_WAIT_MS)
                continue
            }
            val comment = draft.text

            if (!waitBeforeComment(ctx, st)) return

            if (sender.send(ctx, comment)) {
                sent++
                sinceLike++
                failures = 0
                val todayCount = sender.markSent(ctx, comment)
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
    private suspend fun ensureLiveRoom(
        ctx: FlowContext,
        allowAutoNavigate: Boolean = false
    ): Boolean = LiveGuard.ensureOnAirOrWait(ctx, allowAutoNavigate)

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
    private suspend fun tryApplyMic(ctx: FlowContext, button: Target?) {
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

    private companion object {
        const val TAG = "直播互动"

        const val MAX_CONTENT_FAILS = 3
        const val MAX_SEND_FAILS = 3
        const val MAX_LIKES_PER_BURST = 30

        const val MIN_GAP_SEC = 2

        const val DOUYIN_FOREGROUND_TIMEOUT_MS = 20_000L
        const val FRONT_POLL_MS = 300L
        const val FRONT_SETTLE_MS = 900L

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

    }
}
