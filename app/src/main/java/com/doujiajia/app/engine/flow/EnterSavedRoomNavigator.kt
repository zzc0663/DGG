package com.doujiajia.app.engine.flow

import android.os.SystemClock
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.engine.FlowContext
import com.doujiajia.app.engine.PageState
import com.doujiajia.app.engine.Target
import com.doujiajia.app.util.CurrentRoomAnchor
import com.doujiajia.app.util.DouyinLauncher
import kotlinx.coroutines.delay

/**
 * 「自动进入收藏的直播间」导航链路（对标竞品的「选择直播间」）。
 *
 * 完整链路：**首页 → 点搜索 → 搜索页输入主播昵称 → 搜索 → 用户 tab → 点目标用户 →
 * 主页找「直播中」→ 进房**。每一环都有已校准的页面规则兜底：
 * HOME 9/6、SEARCH 6/5、SEARCH_RESULT 8/5、USER_PROFILE 8/5、LIVE_ROOM 8/5
 * （见 `docs/定位配置说明.md`，全部 40.8.0 真机实测）。
 *
 * 两条纪律：
 * 1. **只被「用户明确选择」触发**（悬浮面板点选了收藏项）。它本质上是在替用户操作抖音，
 *    没有明确授权时绝不能跑。
 * 2. **任何一环失败都如实上报**，由调用方决定停机。绝不退化成「随便进一个直播间」——
 *    乱进房是付费礼物误触的最大来源。
 */
object EnterSavedRoomNavigator {

    private const val TAG = "进房导航"

    /** 每一步页面等待的超时 */
    private const val PAGE_WAIT_MS = 8_000L
    private const val ACTION_SETTLE_MS = 1_500L

    /** 回首页时最多按几次返回（防把抖音一路退到桌面） */
    private const val MAX_BACK_TO_HOME = 4

    /** 搜索结果里最多试几张直播卡片 */
    private const val MAX_LIVE_CARDS = 3

    /** 用户直播卡片的进房按钮（40.8.0 综合结果页实测，注意是全角逗号） */
    private const val LIVE_CARD_DESC = "直播，按钮"

    /** 点卡片后等「本场点赞」锚点出现的总窗口（进房 + 加载 + 主播节点挂载） */
    private const val ANCHOR_WAIT_MS = 10_000L

    /** 锚点轮询间隔 */
    private const val ANCHOR_POLL_MS = 800L

    suspend fun enter(ctx: FlowContext, nickname: String): Boolean {
        if (!goHome(ctx)) return false

        // ① 首页点搜索（右上角 desc='搜索' 的按钮，40.8.0 实测）
        val searchEntry = ctx.actions.find(Target(Target.Kind.DESC, "搜索"))
        if (searchEntry == null) {
            LogStore.e(TAG, "首页上没找到搜索入口（desc='搜索'），定位可能随版本变化")
            return false
        }
        ctx.actions.tapNode(searchEntry, "首页搜索按钮")
        if (!ctx.waitForPage(PageState.SEARCH, PAGE_WAIT_MS)) {
            LogStore.e(TAG, "点搜索后没有进入搜索页")
            return false
        }

        // ② 输入主播昵称。搜索框是 editable 节点（默认填推荐词，按文本定位命不中）
        val input = ctx.actions.find(Target(Target.Kind.EDITABLE, ""))
        if (input == null) {
            LogStore.e(TAG, "搜索页没找到输入框（editable）")
            return false
        }
        if (!ctx.actions.typeText(input, nickname, "搜索框")) {
            LogStore.e(TAG, "输入昵称「$nickname」失败")
            return false
        }
        ctx.settle(600)

        // ③ 点「搜索」确认（搜索页键盘/标题栏上的确认按钮）
        val confirm = ctx.actions.find(Target(Target.Kind.TEXT, "搜索"))
        if (confirm == null) {
            LogStore.e(TAG, "搜索页没找到「搜索」确认按钮")
            return false
        }
        ctx.actions.tapNode(confirm, "搜索确认")
        if (!ctx.waitForPage(PageState.SEARCH_RESULT, PAGE_WAIT_MS)) {
            LogStore.e(TAG, "搜索后没有进入结果页")
            return false
        }

        // ④ 综合结果页里找「用户直播卡片」的预览画面（desc='直播，按钮'，40.8.0 实测），
        //    点它直接进直播间。**校验只用「本场点赞」锚点**：页面判定（LIVE_ROOM 投票）
        //    在进房加载期会误判，而「<昵称>xx万本场点赞」desc 只在真正的直播间里有——
        //    锚点出现 = 进房成功 + 拿到主播名，一个信号两用（真机 00:01 教训：
        //    waitForPage(LIVE_ROOM) 通过但主播节点没挂上，导致误判「进错房间」）。
        repeat(MAX_LIVE_CARDS) { attempt ->
            if (ctx.stopped()) return false
            var card = ctx.actions.find(Target(Target.Kind.DESC, LIVE_CARD_DESC))
            if (card == null) {
                // 列表可能还没加载出直播卡，等一轮再找
                ctx.settle(2_000)
                card = ctx.actions.find(Target(Target.Kind.DESC, LIVE_CARD_DESC))
            }
            if (card == null) {
                LogStore.e(TAG, "搜索结果里没有「$nickname」的直播卡片（可能没在开播）")
                return false
            }
            ctx.actions.tapNode(card, "用户卡片直播预览")

            // 轮询等「本场点赞」锚点：出现即进房成功，同时拿到主播昵称
            val deadline = SystemClock.uptimeMillis() + ANCHOR_WAIT_MS
            var anchor: String? = null
            while (SystemClock.uptimeMillis() < deadline) {
                if (ctx.stopped()) return false
                delay(ANCHOR_POLL_MS)
                ctx.actions.invalidateSnapshot()
                anchor = CurrentRoomAnchor.currentAnchorName(
                    com.doujiajia.app.service.DggAccessibilityService.current
                )
                if (anchor != null) break
            }

            if (anchor == null) {
                // 点了没反应 / 落在半开预览：先清理现场回结果页，再试下一张
                LogStore.w(TAG, "点直播卡片后没等到直播间（第 ${attempt + 1}/$MAX_LIVE_CARDS 张，当前页 ${ctx.detector.detect().page}）")
                if (!returnToSearchResult(ctx)) return false
                return@repeat
            }

            if (anchor.contains(nickname)) {
                LogStore.i(TAG, "已进入收藏的直播间「$nickname」（主播 $anchor）")
                return true
            }

            LogStore.w(TAG, "进的是「$anchor」不是「$nickname」，退出重找")
            ctx.actions.back()
            if (!returnToSearchResult(ctx)) return false
        }
        LogStore.e(TAG, "试了 $MAX_LIVE_CARDS 张直播卡片都不是「$nickname」")
        return false
    }

    /**
     * 从意外页面回到搜索结果页：先看是不是已经在（点了没反应的常见态），
     * 不在就按返回清理半开预览，最多 2 次。
     */
    private suspend fun returnToSearchResult(ctx: FlowContext): Boolean {
        repeat(2) { i ->
            ctx.actions.invalidateSnapshot()
            if (ctx.detector.detect().page == PageState.SEARCH_RESULT) return true
            LogStore.d(TAG, "按返回回搜索结果页（第 ${i + 1}/2 次）")
            ctx.actions.back()
            delay(ACTION_SETTLE_MS)
        }
        ctx.actions.invalidateSnapshot()
        val page = ctx.detector.detect().page
        if (page == PageState.SEARCH_RESULT) return true
        LogStore.e(TAG, "无法回到搜索结果页（当前 $page），中止进房导航")
        return false
    }

    /**
     * 回到抖音首页。
     *
     * 抖音不在前台先拉起；还在别的页面就按返回（用户已明确选择要进某个房间，
     * 这里的返回是导航的一部分，不属于「乱退」），最多 [MAX_BACK_TO_HOME] 次。
     */
    private suspend fun goHome(ctx: FlowContext): Boolean {
        if (!ctx.actions.isDouyinForeground()) {
            if (!DouyinLauncher.launch(ctx.appContext)) {
                LogStore.e(TAG, "拉起抖音失败")
                return false
            }
            // 等抖音到前台（用任意一页判定成功当信号）
            val deadline = SystemClock.uptimeMillis() + 12_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (ctx.stopped()) return false
                ctx.actions.invalidateSnapshot()
                if (ctx.detector.detect().page != PageState.UNKNOWN) break
                delay(400)
            }
        }
        if (ctx.detector.detect().page == PageState.HOME) return true

        repeat(MAX_BACK_TO_HOME) { i ->
            if (ctx.stopped()) return false
            LogStore.d(TAG, "按返回回首页（第 ${i + 1}/$MAX_BACK_TO_HOME 次）")
            ctx.actions.back()
            delay(ACTION_SETTLE_MS)
            ctx.actions.invalidateSnapshot()
            if (ctx.detector.detect().page == PageState.HOME) return true
        }
        LogStore.e(TAG, "按了 $MAX_BACK_TO_HOME 次返回仍未回到首页（当前 ${ctx.detector.detect().page}）")
        return false
    }
}
