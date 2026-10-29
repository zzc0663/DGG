package com.example.dgg.engine.flow

import com.example.dgg.data.LogStore
import com.example.dgg.engine.Actions
import com.example.dgg.engine.FlowContext
import kotlinx.coroutines.delay

/**
 * 自愈等待：把「读不到界面」变成暂停而不是终止。
 *
 * 背景（2026-10-29 需求）：直播间里读不到评论行，以前 4 轮就判定位失效并
 * `FlowAbortException` → 终态。但那 4 轮里绝大多数不是真故障——
 *
 * | 真实成因 | 以前的处置 | 现在 |
 * |---|---|---|
 * | 误触打开了礼物面板 / 观众列表 | 等 8 秒后杀掉任务 | 按一次返回关掉，继续 |
 * | 抖音在转场、加载、评论区被「加载中」占位 | 误判成故障 | 等一会儿自己就好 |
 * | `live_comment_line` 真和当前版本不匹配 | 正确中止，但**不能再跑** | 暂停等待，人来了点「继续」就能接着跑 |
 *
 * 三条纪律：
 * 1. **暂停期间只读不点**——唯一的动作是 [LiveGuard.recoverOverlay] 里的 `back()`，
 *    而且只在明确识别出浮层时才按。绝不做「猜一个位置点一下」。
 * 2. **风控优先**：探测循环每轮都 `guard()`。等待恢复不等于可以放过验证码/滑块。
 * 3. **不设放弃上限**：用户要求「暂停期间持续尝试读取」。用退避控制开销
 *    （3s → 6s → 10s → 15s 封顶），每 30 秒往面板和通知栏报一次已等待多久。
 *
 * 与手动暂停的关系：共用 `paused` 标志和同一颗「继续」按钮。
 * 用户随时可以点「继续」——循环每轮都读 [FlowContext.isPaused]，
 * 发现被人接管就立刻让出控制权。
 */
object RecoveryWait {

    private const val TAG = "自愈等待"

    /** 第一次探测等 3 秒：给「加载中」这类过渡态一点时间，不要一暂停就疯狂遍历 */
    private const val PROBE_MIN_MS = 3_000L

    /** 退避上限。一次全树遍历 1.4s，再密也只是空转 */
    private const val PROBE_MAX_MS = 15_000L

    /** 等这么久才往界面报一次，避免通知栏/悬浮窗每几秒抖一下 */
    private const val REPORT_EVERY_MS = 30_000L

    /** 关掉浮层后等界面收起，再探测（返回动画 + 评论重排） */
    private const val OVERLAY_SETTLE_MS = 1_200L

    /** 面板/通知栏里「读的是什么」——两种入口共用一套循环，只有这句话不同 */
    private const val HINT_COMMENTS = "读不到评论行"
    private const val HINT_PAGE = "读不到直播间画面"

    /** 取证用：只收短文案（标题/按钮），评论正文自然被过滤 */
    private const val LABEL_MAX_LEN = 12
    private const val MAX_LABELS = 30

    /**
     * 挂起任务并持续探测，直到「评论读得回来了」或「用户接管」或「任务被停」。
     *
     * @param reason 展示给用户的暂停原因（会写进 `progress.message`）
     * @param probe  只读探测：返回**当前能读到的评论行数**。> 0 即视为恢复。
     *               调用方传入的必须是纯读操作（`scanOnce` 这类），不能有点击。
     * @return true = 可以继续干活（探测成功，或用户手动恢复）；
     *         false = 任务已被停止，调用方直接 return
     */
    suspend fun waitForComments(
        ctx: FlowContext,
        reason: String,
        probe: suspend () -> Int
    ): Boolean = waitFor(ctx, reason, HINT_COMMENTS) { probe() > 0 }

    /**
     * 通用入口：**整个页面都读不出来**时用（不是「读不到评论」，是「读不到直播间」）。
     *
     * 典型场景（2026-10-29 真机）：抖音的「人气开场」助力页整屏盖住直播间，
     * 页面判定掉成 `UNKNOWN(0/6)`。它不在 `overlays` 配置里——而且**不该**只凭
     * 「人气开场」去认：直播间右上角常年挂着一颗「人气开场 2人助力」徽标，
     * 单特征会让正常直播间被误判成浮层、按返回直接退房。
     *
     * 以前这种情况只能等 3 轮 ×10s 后抛 `FlowAbortException` 终止任务——用户什么都没做，
     * 进度却断了。现在改成「暂停 + 持续探测」：浮层被关掉（用户手动返回、抖音自己收起）
     * 就自动恢复，进度一点都不丢。
     *
     * @param probe 只读探测：true = 页面已经回到可工作的状态（直播间）
     */
    suspend fun waitForPage(
        ctx: FlowContext,
        reason: String,
        probe: suspend () -> Boolean
    ): Boolean = waitFor(ctx, reason, HINT_PAGE, probe)

    private suspend fun waitFor(
        ctx: FlowContext,
        reason: String,
        hint: String,
        probe: suspend () -> Boolean
    ): Boolean {
        ctx.pauseForRecovery("$reason，等待恢复（自动重试中）")
        LogStore.w(TAG, "$reason → 任务已暂停，开始自愈探测（只读，不点击）")

        var waited = 0L
        var interval = PROBE_MIN_MS
        var lastReportAt = 0L
        // 屏幕上到底有什么，只 dump 一次：认不出来的浮层要靠这份文案去补 overlays 规则
        var dumpedLabels = false

        while (true) {
            if (ctx.stopped()) {
                LogStore.i(TAG, "等待恢复期间任务被停止（已等 ${waited / 1000}s）")
                return false
            }

            // 风控优先于自愈：暂停期间界面可能正弹着验证码/滑块，
            // 这种情况必须立刻走 AnomalyStopException → 风控停机，绝不继续等
            ctx.guard()

            // 用户点了「继续」（悬浮窗、主界面、通知栏都是同一个 resume）
            if (!ctx.isPaused()) {
                LogStore.i(TAG, "用户手动恢复，结束等待（已等 ${waited / 1000}s）")
                return true
            }

            // 用户切走（回消息、来电）时读到的都不是直播间，先试着把抖音拉回来。
            // 失败也没关系：下一轮还会试，用户自己切回来同样能恢复。
            if (!ctx.actions.isDouyinForeground()) {
                LogStore.w(TAG, "等待期间抖音不在前台，尝试自动拉回")
                LiveGuard.ensureDouyinForeground(ctx)
            }

            // 先把现场收拾干净：礼物面板 / 观众列表 / 资料卡 / 评论键盘
            val closed = LiveGuard.recoverOverlay(ctx)
            if (closed != null) {
                LogStore.i(TAG, "等待期间识别到「$closed」并已关闭，立即重新探测")
                delay(OVERLAY_SETTLE_MS)
            } else if (probe()) {
                LogStore.i(TAG, "已恢复读取（共等待 ${waited / 1000}s），自动恢复任务")
                ctx.resumeFromRecovery()
                return true
            } else if (!dumpedLabels) {
                // 一次就够：这是「未收录浮层」的唯一取证入口，照着文案补 overlays 即可
                dumpedLabels = true
                LogStore.w(TAG, "仍读不出来，屏上文案（补 overlays 规则用）：${screenLabels(ctx)}")
            }

            if (waited - lastReportAt >= REPORT_EVERY_MS) {
                lastReportAt = waited
                ctx.publish(
                    message = "等待恢复：已等待 ${waited / 1000}s" +
                        "（$hint，仍在重试；可手动点「继续」接管）"
                )
            }

            delay(interval)
            waited += interval
            interval = (interval * 2).coerceAtMost(PROBE_MAX_MS)
        }
    }

    /**
     * 屏上的短文案（text 与 content-desc 分开列），拼成一行。
     *
     * 用途只有一个：遇到**没收录**的浮层时，把它真实的长什么样写进日志，
     * 下次照着补 `douyin_locator.json` 的 `overlays` 段，不用再猜。
     *
     * **必须两边都抓**：抖音的按钮语义一半挂在 text 上（「赠送」），另一半挂在 desc 上
     * （「人气票 1 钻 双击赠送」「关闭」）。只抓 text 会让补规则的人凭空猜 desc。
     * 只留 ≤[LABEL_MAX_LEN] 字的（标题/按钮），评论正文自然被过滤掉。
     */
    private fun screenLabels(ctx: FlowContext): String {
        val facts = ctx.actions.snapshot(ttlMs = 0)
        fun pick(read: (Actions.NodeFacts) -> String?): String {
            val items = facts.mapNotNull { read(it)?.trim()?.takeIf { s -> s.isNotEmpty() && s.length <= LABEL_MAX_LEN } }
                .distinct()
                .take(MAX_LABELS)
            return if (items.isEmpty()) "（无）" else items.joinToString("、")
        }
        return "text=[" + pick { it.text } + "] desc=[" + pick { it.desc } + "]"
    }
}
