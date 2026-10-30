package com.example.dgg.engine

/** 引擎状态。UI、前台通知、悬浮窗都消费这一套状态。 */
enum class EngineState {
    IDLE,
    RUNNING,

    /** 用户主动暂停（点「暂停」、或展开悬浮面板改目标数） */
    PAUSED,

    /**
     * **自愈暂停**：流程自己发现异常（读不到直播间评论、浮层关不掉）后主动挂起，
     * 任务**没有结束**，后台还在按退避节奏探测，探测成功会自动恢复到 [RUNNING]。
     *
     * 与 [PAUSED] 的关系：共用同一个 `paused` 标志、同一颗「继续」按钮——
     * 区别只在状态文案，以及「能不能自己恢复」。用户在面板上点「继续」同样立即生效。
     *
     * 为什么必须有它：以前这类异常一律走 `FlowAbortException` → 终态，
     * 协程都结束了，`resume()` 无从下手；用户只能回 App 重新点「开始服务」，
     * 还得重新进直播间。**异常不该等于任务死亡。**
     */
    PAUSED_WAIT,

    STOPPED,
    STOPPED_ANOMALY,
    ERROR;

    /** 任务是否还活着。三处渲染、前台服务去留、悬浮窗终态判定全都走这个判断。 */
    val isActive: Boolean get() = this == RUNNING || this == PAUSED || this == PAUSED_WAIT

    val label: String
        get() = when (this) {
            IDLE -> "空闲"
            RUNNING -> "运行中"
            PAUSED -> "已暂停"
            PAUSED_WAIT -> "等待恢复"
            STOPPED -> "已停止"
            STOPPED_ANOMALY -> "风控停机"
            ERROR -> "异常停止"
        }
}

/** 引擎对外暴露的进度快照 */
data class EngineProgress(
    val state: EngineState = EngineState.IDLE,
    val page: PageState = PageState.UNKNOWN,
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
    val stepLabel: String = "",
    val followed: Int = 0,
    val target: Int = 0,
    /**
     * [followed] 这个数字叫什么。
     *
     * M2 关注流程是「已关注」，M3 直播互动是「已发评论」——同一个计数字段被两条业务线复用，
     * 文案就必须由 Flow 自己声明，不能让通知栏/悬浮窗写死成「已关注」。
     */
    val counterLabel: String = "已关注",
    /** 养号：累计点赞数（[followed] 在养号流程里装的是「已浏览」） */
    val liked: Int = 0,
    /** 养号：累计收藏数 */
    val collected: Int = 0,
    val consecutiveFailures: Int = 0,
    val message: String = "",
    val updatedAt: Long = 0L
) {
    /**
     * 通知栏 / 悬浮窗 / 状态条的一行摘要。
     *
     * @param includeState 是否带上状态标签。通知栏的标题已经是「抖加加 · 运行中」，
     *                     正文再写一遍「运行中 · 已关注 4/300」就是重复，那里传 false。
     */
    fun brief(includeState: Boolean = true): String {
        val parts = mutableListOf<String>()
        if (includeState) parts += state.label
        if (stepTotal > 0) parts += "步骤 $stepIndex/$stepTotal"
        when {
            target > 0 -> parts += "$counterLabel $followed/$target"
            // 没有总量目标的流程（如直播互动）只显示已完成数
            followed > 0 -> parts += "$counterLabel $followed"
        }
        // 养号才有这两个数：关注/直播流程一直是 0，不该在它们的通知里多出一截
        if (liked > 0) parts += "赞 $liked"
        if (collected > 0) parts += "藏 $collected"
        if (message.isNotBlank()) parts += message
        return parts.joinToString(" · ")
    }
}

/**
 * 把分母换成**用户当前的设置值**，供所有展示进度的位置使用（通知栏 / 悬浮窗 / 主界面状态条）。
 *
 * [EngineProgress.target] 是流程启动那一刻（以及每次 publish 时）冻结的快照：
 * 用户在悬浮面板「设置关注」里把目标改成 300，这个字段不会跟着变，
 * 于是三处界面同时显示旧分母（2026-10-29 真机复现：面板里是 300、大号计数还是 0/200）。
 *
 * `target == 0` 时不替换：那是流程**主动声明「没有总量目标」**（直播互动 / 养号都这么发），
 * 拿关注目标凑个分母会冒出「已发评论 0/200」这种假数字。
 */
fun EngineProgress.withLiveTarget(liveTarget: Int): EngineProgress =
    if (target > 0) copy(target = liveTarget) else this
