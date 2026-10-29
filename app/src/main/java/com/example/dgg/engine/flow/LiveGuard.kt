package com.example.dgg.engine.flow

import android.os.SystemClock
import com.example.dgg.data.LogStore
import com.example.dgg.engine.FlowContext
import com.example.dgg.engine.OverlayRule
import com.example.dgg.engine.PageState
import com.example.dgg.engine.Target
import com.example.dgg.util.DouyinLauncher
import kotlinx.coroutines.delay

/**
 * 直播间驻留守卫（M2 自动关注 / M3 直播互动共用）。
 *
 * 它只回答一个问题：**现在能不能继续干活**。做不到就尽力恢复，恢复不了返回 false，
 * 由调用方抛出带原因的 [com.example.dgg.engine.FlowAbortException]，绝不「盲点」糊过去。
 *
 * 四条约定都是真机踩出来的：
 *
 * 1. **人为切换直播间时不要按返回**。切房间是「旧房间 → 转场动画（此刻读不到直播间特征）
 *    → 新房间」，中间那段 UNKNOWN 是完全正常的。早期实现一读到 UNKNOWN 就按返回，
 *    结果把用户刚切到的直播间退掉，脚本只能在直播频道页空转到中止。
 *    现在给足 [SWITCH_TOLERANCE_MS] 的耐心窗口，期间页面恢复就直接继续（用户切到哪个房间就跟哪个）。
 *
 * 2. **退出抖音要自动拉回**。用户切到微信回消息、或者抖音被系统杀进程，前台就不再是抖音。
 *    有悬浮窗权限（`SYSTEM_ALERT_WINDOW`）时后台 Service 可以正常 startActivity，
 *    所以能自己把抖音拉回来，最多试 [RELAUNCH_RETRY] 次。
 *
 * 3. **只有「页面上明确识别出浮层」才按返回**。资料卡是半屏浮层，会被判成 USER_PROFILE，
 *    按一次返回正好回到直播间。但页面是 UNKNOWN 时**绝不**按返回——那可能是转场、加载、
 *    或者键盘动画，按下去退的是真实页面。
 *
 * 4. **用户在抖音别处（首页/搜索）就中止，不猜**。脚本拿不到「该进哪个房间」的信息，
 *    乱点轻则进错房间、重则点到付费直播。宁可停下让人自己进。
 */
object LiveGuard {

    private const val TAG = "直播守卫"

    /** 前台窗口切换的中间态容忍（下拉通知栏、输入法弹出都会短暂读到别的窗口） */
    private const val FRONT_GRACE_MS = 1_200L
    private const val FRONT_POLL_MS = 300L

    /** 拉起抖音后等它回到前台的时长（冷启动比热启动慢得多） */
    private const val RELAUNCH_WAIT_MS = 8_000L
    private const val RELAUNCH_RETRY = 3

    /** 人为切换直播间的转场 + 新房间加载窗口，这段内判 UNKNOWN 不算异常 */
    private const val SWITCH_TOLERANCE_MS = 10_000L

    /**
     * 页面读不出来时最多重试几轮。
     *
     * 3 轮 = 约 30 秒，覆盖「抖音被系统杀掉后冷启动」（实测闪屏到首页要 10 秒以上）
     * 和「用户慢慢切房间」。再多就说明真的出问题了，该停下报错。
     */
    private const val UNKNOWN_RETRY = 3

    /** 按一次返回之后，等浮层收起的时长 */
    private const val DISMISS_SETTLE_MS = 3_500L

    /** 判定轮询间隔。一趟全树遍历约 1.4s，比这更密只会堆积 */
    private const val LIVE_POLL_MS = 400L

    /** 点「点击进入直播间」后等直播间加载出来的时长 */
    private const val ENTER_ROOM_WAIT_MS = 8_000L

    /**
     * 抖音「直播频道」预览页（上下滑看直播卡片的那一屏）的进房按钮文案。
     * 这一屏不是直播间，但它上面就摆着进房入口，点是安全的（免费看播，不涉及任何付费礼物）。
     */
    private const val LIVE_FEED_ENTER_TEXT = "点击进入直播间"

    /** 抖音首页顶部页签「直播」。只在屏幕最上方这一小段里认它，见 [enterFromHome] */
    private const val LIVE_TAB_TEXT = "直播"
    private const val LIVE_TAB_MAX_TOP_RATIO = 0.15f

    /** 这些页面是「压在直播间上的浮层」，按一次返回就能回到直播间 */
    private val OVERLAY_PAGES = setOf(PageState.USER_PROFILE)

    /**
     * 确保「正在抖音的某个直播间里」。
     *
     * @param allowAutoNavigate 允许「首页 → 直播页签 → 进房」的自动导航。
     *   只在**用户明确表达了意图**的场合传 true：任务刚启动（他点了「开始服务」）、
     *   或我们刚把抖音拉回来（他自己离开的）。任务跑到一半读到首页时不导航——
     *   那可能是用户想回个消息，抢他的界面等于抢手机。
     * @return true = 可以继续；false = 已尽力恢复仍不行，调用方应带原因中止
     */
    suspend fun ensureOnAir(ctx: FlowContext, allowAutoNavigate: Boolean = false): Boolean {
        // ① 前台不是抖音：先给一次窗口期（可能只是切窗的中间态），确认丢了就自动拉回。
        //    记下「是我们拉回来的」——只有这种情况才允许后面自动导航进房，
        //    避免脚本自己跑偏时在抖音里到处乱点。
        var relaunched = false
        if (!ctx.actions.isDouyinForeground() && !awaitForeground(ctx, FRONT_GRACE_MS)) {
            LogStore.w(TAG, "抖音已不在前台（用户切走了，或被系统回收）")
            if (!relaunchDouyin(ctx)) return false
            relaunched = true
        }

        // ② 等页面稳定到直播间。这一步同时覆盖「用户正手动切房间」的情况
        var page = awaitRoom(ctx, SWITCH_TOLERANCE_MS)
        if (page == PageState.LIVE_ROOM) return true

        // ③ 读不出页面：可能还在转场、加载、收键盘，或者抖音刚被拉起来还在闪屏。
        //    **绝不按返回**——这时按下去退的是真实页面。多重试几轮，冷启动比转场慢得多。
        var unknownRounds = 0
        while (page == PageState.UNKNOWN && unknownRounds < UNKNOWN_RETRY) {
            unknownRounds++
            // 抖音的「直播频道」预览页会被判成 UNKNOWN（页面规则只命中「首页/朋友」5/6），
            // 但它上面就摆着进房按钮：先试一次，别为了等页面判定干耗 30 秒。
            if (enterFromLiveFeed(ctx)) return true
            // 反过来，礼物面板 / 观众列表 / 评论键盘这些浮层也会把页面判成 UNKNOWN。
            // 不认出来的话就只能干等到超时中止，比旧版「按返回关掉」更差。
            val closed = recoverOverlay(ctx)
            if (closed != null) {
                if (awaitRoom(ctx, DISMISS_SETTLE_MS) == PageState.LIVE_ROOM) {
                    LogStore.i(TAG, "关掉「$closed」浮层后回到了直播间")
                    return true
                }
                // 浮层关掉了却没回到直播间（多为 live 面板吃掉了返回键、退到了预览页或首页）。
                // **不要接着按第二次返回**——那只会退得更远。跳出重试循环，
                // 交给下面的落点处置：预览页点进房、首页不抢、其它页返回 false。
                page = ctx.detector.detect().page
                LogStore.w(TAG, "关掉「$closed」后落在 ${page.name}，交给落点处置（不再按返回）")
                break
            }
            LogStore.w(
                TAG,
                "页面读不出来（可能在转场/加载/抖音刚启动），等第 $unknownRounds/$UNKNOWN_RETRY 轮，不按返回"
            )
            if (!ctx.actions.isDouyinForeground() && !relaunchDouyin(ctx)) return false
            page = awaitRoom(ctx, SWITCH_TOLERANCE_MS)
            if (page == PageState.LIVE_ROOM) return true
        }

        // ④ 停在「直播频道」预览页：点一下「点击进入直播间」自己进房。
        // 用户回微信再切回来、或者抖音被系统回收后重启，最常见的落点就是这个预览页——
        // 什么都不做就只能中止，做了就能继续跑，而且进的是免费看播，不涉付费。
        if (page != PageState.LIVE_ROOM && enterFromLiveFeed(ctx)) return true

        // ⑤ 明确识别出是「压在直播间上的浮层」（资料卡 / 用户主页）→ 按一次返回关掉
        if (page in OVERLAY_PAGES) {
            LogStore.w(TAG, "直播间被 $page 浮层盖住，按一次返回关掉")
            ctx.actions.back()
            if (awaitRoom(ctx, DISMISS_SETTLE_MS) == PageState.LIVE_ROOM) {
                LogStore.i(TAG, "关掉浮层后回到了直播间")
                return true
            }
        }

        // ⑥ 拉回抖音后（或任务刚启动）落在首页：点顶部「直播」页签进直播频道，再走一次进房。
        // 只在「用户明确表达过意图」时做——任务跑到一半发现自己在首页，说明用户可能想回消息，不抢。
        if (page == PageState.HOME && (relaunched || allowAutoNavigate) && enterFromHome(ctx)) {
            return true
        }

        // ⑦ 兜底：这几秒里用户可能又切走了
        if (!ctx.actions.isDouyinForeground()) {
            LogStore.w(TAG, "恢复过程中抖音再次离开前台，重新拉回")
            if (!relaunchDouyin(ctx)) return false
            if (awaitRoom(ctx, SWITCH_TOLERANCE_MS) == PageState.LIVE_ROOM) return true
        }

        LogStore.w(TAG, "已不在直播间（当前判定 $page），脚本不会盲目找房间，交由调用方中止")
        return false
    }

    /** 在「直播频道」预览页点进房。返回 true 表示已进到直播间 */
    private suspend fun enterFromLiveFeed(ctx: FlowContext): Boolean {
        val entry = ctx.actions.find(Target(Target.Kind.TEXT_CONTAINS, LIVE_FEED_ENTER_TEXT))
            ?: return false
        LogStore.w(TAG, "停在直播频道预览页，点「$LIVE_FEED_ENTER_TEXT」自动进房")
        ctx.actions.tapNode(entry, LIVE_FEED_ENTER_TEXT)
        if (awaitRoom(ctx, ENTER_ROOM_WAIT_MS) == PageState.LIVE_ROOM) {
            LogStore.i(TAG, "已自动进入直播间，继续执行")
            return true
        }
        LogStore.w(TAG, "点了「$LIVE_FEED_ENTER_TEXT」但没进到直播间")
        return false
    }

    /**
     * 在抖音首页点顶部「直播」页签，再点预览页的进房按钮。
     *
     * 只认屏幕最上方 15% 内的「直播」两个字——首页视频文案里出现「直播」很常见，
     * 不限制位置会点到某个视频上。
     */
    private suspend fun enterFromHome(ctx: FlowContext): Boolean {
        val tab = ctx.actions.find(Target(Target.Kind.TEXT, LIVE_TAB_TEXT)) ?: return false
        val top = ctx.actions.boundsOf(tab).top
        if (top < 0 || top > ctx.actions.screenHeight() * LIVE_TAB_MAX_TOP_RATIO) return false

        LogStore.w(TAG, "拉回抖音后停在首页，点顶部「$LIVE_TAB_TEXT」页签进直播频道")
        ctx.actions.tapNode(tab, "首页-$LIVE_TAB_TEXT 页签")
        delay(LIVE_POLL_MS * 8)
        return enterFromLiveFeed(ctx)
    }

    /**
     * 识别并关掉「压在直播间上、按一次返回就能关掉」的浮层。
     *
     * 规则来自 `douyin_locator.json` 的 `overlays` 段（加权多特征，2026-10-29 真机取证）：
     * 礼物面板、观众列表、长按菜单、资料卡、评论键盘。
     *
     * ⚠️ **不要用「页面判定不是 LIVE_ROOM」当前置条件**。真机实测：点右上角人数弹出的
     * 观众列表是**半屏**面板，屏幕上主播名、在线观众数、热度榜全都还在，页面判定依然是
     * `LIVE_ROOM`——按页面判定做门禁，这个最常见的一次误触就永远修不掉。
     * 安全性改由「阈值 + 文案唯一性」保证：正常直播间里这些特征一条都凑不够分
     * （礼物面板要 5 分，而直播间最多只能靠评论里偶然出现的「钻」拿 2 分）。
     *
     * **只按返回，绝不点浮层里的任何控件**：礼物面板里每个可点元素都是
     * 「选礼物 / 赠送 / 充值」。`Actions.tapNode` 的付费守卫只能是最后一道保险，
     * 不能当成第一道。
     *
     * @return 关掉的浮层名；null = 屏幕上没有可识别的浮层
     */
    suspend fun recoverOverlay(ctx: FlowContext): String? {
        // 不在抖音前台时按返回，退掉的是别人家的界面
        if (!ctx.actions.isDouyinForeground()) return null

        // 一趟全树遍历（这台真机 1.4s），所有特征都在这份快照上比对
        val facts = ctx.actions.snapshot(ttlMs = 0)
        if (facts.isEmpty()) return null

        var best: OverlayRule? = null
        var bestScore = 0
        for (rule in ctx.config.overlays.values) {
            val score = rule.features.sumOf { feature ->
                if (facts.any { ctx.actions.matchesFacts(it, feature.target) }) feature.weight else 0
            }
            if (score >= rule.threshold && score > bestScore) {
                best = rule
                bestScore = score
            }
        }
        val rule = best ?: return null

        // 只在真的要动手时才算一次页面判定（复用上面那份快照缓存），纯粹为了日志好读
        val page = ctx.detector.detect().page
        LogStore.w(
            TAG,
            "识别到「${rule.label}」浮层（$bestScore/${rule.threshold}，当前判定 ${page.name}），按一次返回关闭"
        )
        if (!ctx.actions.back()) {
            LogStore.w(TAG, "返回键没生效，「${rule.label}」可能还在（下一轮会重试）")
            return null
        }
        return rule.label
    }

    /**
     * 只确认「抖音在前台」，不做页面判定。
     * 用于「采集到候选之后准备点击」这种只需要知道前台还在不在的场景。
     */
    suspend fun ensureDouyinForeground(ctx: FlowContext): Boolean {
        if (ctx.actions.isDouyinForeground()) return true
        LogStore.w(TAG, "抖音已不在前台，尝试拉回")
        return relaunchDouyin(ctx)
    }

    /** 轮询到抖音回到前台 */
    private suspend fun awaitForeground(ctx: FlowContext, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return false
            if (ctx.actions.isDouyinForeground()) return true
            delay(FRONT_POLL_MS)
        }
        return ctx.actions.isDouyinForeground()
    }

    /** 拉起抖音并等它真正到前台。冷启动可能要十几秒，所以重试次数少、单次等待长 */
    private suspend fun relaunchDouyin(ctx: FlowContext): Boolean {
        repeat(RELAUNCH_RETRY) { i ->
            if (ctx.stopped()) return false
            LogStore.w(TAG, "自动拉回抖音（第 ${i + 1}/$RELAUNCH_RETRY 次）")
            if (!DouyinLauncher.launch(ctx.appContext)) {
                LogStore.e(TAG, "拉起抖音失败：应用可能已被卸载或禁用")
                return false
            }
            if (awaitForeground(ctx, RELAUNCH_WAIT_MS)) {
                LogStore.i(TAG, "抖音已回到前台")
                // 刚 resume 时窗口内容可能还是上一帧，等一帧再往下读
                delay(FRONT_POLL_MS * 3)
                return true
            }
            delay(1_000L)
        }
        LogStore.e(TAG, "$RELAUNCH_RETRY 次拉回抖音都失败，请检查抖音是否还在运行")
        return false
    }

    /**
     * 轮询到页面变成直播间，返回**最后一次**的判定结果（不是布尔）。
     *
     * 调用方需要知道「不是直播间」到底是「浮层」「首页」还是「读不出来」，
     * 这三种的处置完全不同：浮层按返回、首页得停、读不出来只能继续等。
     *
     * 这里必须 [com.example.dgg.engine.Actions.invalidateSnapshot]：等的就是「界面已经换了」，
     * 吃 700ms 快照缓存会一直读到切换前的旧树，白等到超时。
     */
    private suspend fun awaitRoom(ctx: FlowContext, timeoutMs: Long): PageState {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = PageState.UNKNOWN
        while (SystemClock.uptimeMillis() < deadline) {
            if (ctx.stopped()) return last
            ctx.actions.invalidateSnapshot()
            val detection = ctx.detector.detect()
            last = detection.page
            ctx.publish(page = last)
            if (last == PageState.LIVE_ROOM) return last
            delay(LIVE_POLL_MS)
        }
        return last
    }
}
