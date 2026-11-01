package com.doujiajia.app.engine

import android.os.SystemClock
import com.doujiajia.app.data.InteractStore
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.data.Settings
import com.doujiajia.app.util.Texts
import kotlinx.coroutines.delay

/**
 * 直播间评论发送器：**M3 独立互动** 与 **M2 关注流程的「顺手发一条」** 共用这一套实现。
 *
 * 为什么要抽出来而不是各写一份：M2 休息时发的那条评论，和 M3 发的完全是同一件事——
 * 同一套 viewId 快路径、同一套「发完怎么算成功」的三判据、同一份话术库、同一个去重窗口。
 * 各写一份的下场是抖音改版后两份都要改，且**必然改漏一份**（本项目已经吃过这个亏：
 * vc41 改名后 M3 整整一个月没人跑到过）。
 *
 * ## 跨流程共享的「最近发过的正文」
 *
 * [recentBodies] 挂在 companion 上，进程内所有实例共用一份：
 * 1. **去重**：M2 休息时发了「刚来支持一下」，M3 接着跑时不能再挑到同一句；
 * 2. **防自杀式关注**：自己发的评论会出现在评论区里，长得和别人的一模一样。
 *    M2 扫描候选时若拿它去关注，就会尝试关注自己的昵称（必然失败），
 *    连续失败还会触发流程中止。M2 用 [recentBodies] 把这些行滤掉。
 *
 * 不持久化：进程重启后窗口清空。跨进程重复只影响「重启后第一句可能与上次重复」，
 * 这个代价远小于给高频路径加 SP 写入。
 */
class LiveCommentSender(config: LocatorConfig) {

    /** 候选链：viewId 快路径在前、文案兜底在后（见 [Actions.findAny]） */
    private val input: List<Target>
    private val send: List<Target>

    /**
     * 单独留一条**纯文案**目标，只用于「评论面板有没有收起」的判据。
     *
     * ⚠️ 不能用 viewId 那条：`h7l` 那个节点无论有没有输入过都挂在底栏，
     * 拿它当判据会得出「永远成功」——正好是这种校验最危险的失效方式。
     */
    private val inputVisible: List<Target>
    private val line: Target?

    init {
        val inputFallback = config.target("comment_input")
            ?: throw FlowAbortException("定位配置缺少 comment_input，无法判断评论面板是否已收起")
        val sendFallback = config.target("live_comment_send")
            ?: throw FlowAbortException("定位配置缺少 live_comment_send，无法点发送")
        input = listOfNotNull(config.target("comment_input_fast"), inputFallback)
        send = listOfNotNull(config.target("live_comment_send_fast"), sendFallback)
        inputVisible = listOf(inputFallback)
        line = config.target("live_comment_line")
        if (input.size > 1) LogStore.d(TAG, "评论输入框启用 viewId 快路径（${input.first()}）")
        if (send.size > 1) LogStore.d(TAG, "发送按钮启用 viewId 快路径（${send.first()}）")
    }

    /**
     * 这一轮要发的评论。
     *
     * [fromFallback] 是给界面看的：复制模式拿不到正文时会改发预设话术，
     * 这件事以前只写进日志，用户看到的是「互动在正常跑，但发的全是自己库存的话」。
     */
    class Draft(val text: String, val fromFallback: Boolean)

    /**
     * 生成这一轮要发的评论。
     *
     * - 预设模式：从话术库里随机挑，避开最近发过的；
     * - 复制模式：取直播间**最新**一条别人的评论正文——注意是取正文不是整行，
     *   否则会把自己的昵称一起发出去，等于自曝。
     */
    fun buildDraft(ctx: FlowContext, settings: Settings, presets: List<String>): Draft? =
        when (settings.commentMode) {
            COMMENT_PRESET -> pickPreset(presets)?.let { Draft(it, false) }

            else -> copyLatestComment(ctx)?.let { Draft(it, false) } ?: run {
                // 直播间里全是系统提示/广告/表情时，复制模式会拿不到一条能用的正文。
                // 这时候改发预设话术，比直接中止整个流程合理——用户要的是「互动」，不是「必须复制」。
                val fallback = pickPreset(presets)
                if (fallback != null) {
                    LogStore.w(TAG, "复制模式本轮没有可用正文（系统提示/广告/重复已全部排除），改发预设话术")
                }
                fallback?.let { Draft(it, true) }
            }
        }

    /**
     * 发一条评论：点输入框 → 找**真正的输入控件** → 逐字输入 → 点发送 → 校验已提交。
     *
     * 找输入控件刻意不用配置里的 comment_input（「说点什么」）：无障碍树里那句占位提示
     * 往往是一个 TextView，真正的 EditText 是另一个节点，对提示节点 SET_TEXT 必然失败。
     */
    suspend fun send(ctx: FlowContext, text: String): Boolean {
        val entry = ctx.actions.findAny(input)
        if (entry == null) {
            LogStore.w(TAG, "未找到评论输入框 $input（直播间评论面板可能未展开）")
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

        val sendNode = ctx.actions.waitForFirst(send, SEND_WAIT_MS)
        if (sendNode == null) {
            LogStore.w(TAG, "输入框里已有内容，但 ${SEND_WAIT_MS}ms 内没等到「发送」按钮 $send")
            return false
        }
        if (!ctx.actions.tapNode(sendNode, "发送按钮")) {
            LogStore.w(TAG, "点击「发送」失败")
            return false
        }

        // 「评论过快」通常是一条一闪而过的 toast，发完立刻查一次最有价值。
        // **必须 force**：guard 有 5 秒降频窗口，而前面刚 settle 过（已经查了一次），
        // 不强制的话这一下会被跳过 —— 而它恰恰是唯一能抓到那条 toast 的时机。
        ctx.guard(force = true)
        return awaitSubmitted(ctx, text)
    }

    /** 记入重复窗口 + 当日计数，返回今日累计条数 */
    fun markSent(ctx: FlowContext, text: String): Int {
        rememberShared(text)
        return InteractStore.get(ctx.appContext).bumpToday()
    }

    /**
     * 校验评论真的提交了。三条判据命中**任意一条**就算成功。
     *
     * ① **输入区回到占位态**（重新出现「说点什么」）：发送成功后抖音会清空输入框并收起面板。
     * ② **屏上没有输入焦点**：面板收起来了，这条不依赖任何文案，抖音不改交互就一直成立。
     * ③ **输入框已清空但焦点还在**：有的版本「清空」先于「收面板」发生。
     *
     * 三条是 **OR** 不是 AND：任何一条单独成立都足以说明「面板已回到待输入态」。
     * 反过来做成 AND，遇到「文案改了但焦点确实丢了」的版本会把明明成功的判成失败，
     * 连续 3 次就整个流程中止——那是比漏一个占位提示严重得多的方向。
     */
    private suspend fun awaitSubmitted(ctx: FlowContext, text: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (ctx.actions.findAny(inputVisible) != null) {
                LogStore.d(TAG, "输入区已回到占位态，判定「$text」已提交")
                return true
            }
            val focused = ctx.actions.focusedEditable()
            if (focused == null) {
                LogStore.d(TAG, "评论面板已收起（无输入焦点），判定「$text」已提交")
                return true
            }
            if (ctx.actions.textOf(focused).isNullOrEmpty()) {
                LogStore.d(TAG, "输入框已清空，判定「$text」已提交")
                return true
            }
            ctx.guard()
            delay(VERIFY_POLL_MS)
        }
        LogStore.w(TAG, "发「$text」后 ${VERIFY_TIMEOUT_MS}ms 内三条判据都没满足，按未成功处理")
        return false
    }

    /**
     * 挑一条预设话术，避开最近发过的。窗口全遮住就把话术库原样放开
     * （宁可重复也不空手——这一轮已经在等间隔了，再返回 null 等于白等）。
     */
    private fun pickPreset(presets: List<String>): String? {
        if (presets.isEmpty()) return null
        val recent = recentBodies()
        val pool = presets.filter { it !in recent }.ifEmpty { presets }
        return pool.random()
    }

    private fun copyLatestComment(ctx: FlowContext): String? {
        val target = line ?: return null
        // collect 是按树的遍历顺序返回的，评论列表从上到下 → 最后一条就是最新的
        val bodies = ctx.actions.collect(target, MAX_COPY_SCAN)
            .mapNotNull { ctx.actions.labelOf(it) }
            .mapNotNull { Texts.afterColon(it) }

        val recent = recentBodies()
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

    companion object {
        const val TAG = "评论发送"

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

        const val INPUT_FOCUS_MS = 900L
        const val SEND_WAIT_MS = 3_000L
        const val VERIFY_TIMEOUT_MS = 3_000L
        const val VERIFY_POLL_MS = 250L

        /** 跨流程共享：最近发出的正文（进程内，重启清空） */
        private val recentShared = ArrayDeque<String>(RECENT_WINDOW + 1)

        /** 最近发出的正文快照。M2 扫描候选时用它滤掉「自己刚发的评论行」 */
        fun recentBodies(): List<String> = synchronized(recentShared) { recentShared.toList() }

        /** 判断某条正文是不是自己刚发的（M2 用它避免去关注自己） */
        fun isSelfBodies(body: String): Boolean = synchronized(recentShared) { body in recentShared }

        private fun rememberShared(text: String) = synchronized(recentShared) {
            recentShared.remove(text)
            recentShared.addLast(text)
            while (recentShared.size > RECENT_WINDOW) recentShared.removeFirst()
        }

        /** 复制模式要剔除的系统提示片段 */
        private val SYSTEM_HINTS = listOf(
            "来了", "关注了主播", "送出", "点亮", "升级", "加入粉丝团", "分享了直播间", "为主播点赞"
        )

        /**
         * 复制模式要剔除的垃圾内容特征：导流、广告、拉互关、联系方式。
         * 抄这些发出去，等于自己发广告——平台罚的是本账号。
         *
         * 「点点关注」「元」是实测补的：第一次真机联调就抄到一条
         * 「点点关注+18000元」，正好从「关注我」这类词的空隙里漏了过去。
         */
        private val SPAM_HINTS = listOf(
            "微信", "威信", "vx", "wx", "qq", "加v", "+v", "私信", "私我", "http", "www.",
            "点我", "主页", "主业", "关注我", "互关", "回关", "免费领", "优惠券", "刷单",
            "兼职", "日结", "招聘", "二维码", "扫码",
            "点点关注", "点关注", "关注领", "红包", "福袋", "小黄车", "上车", "秒杀",
            "元", "包邮", "领券", "低价", "福利价"
        )
    }
}
