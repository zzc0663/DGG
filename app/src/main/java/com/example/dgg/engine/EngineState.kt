package com.example.dgg.engine

/** 引擎状态。UI、前台通知、悬浮窗都消费这一套状态。 */
enum class EngineState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    STOPPED_ANOMALY,
    ERROR;

    val isActive: Boolean get() = this == RUNNING || this == PAUSED

    val label: String
        get() = when (this) {
            IDLE -> "空闲"
            RUNNING -> "运行中"
            PAUSED -> "已暂停"
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
    /** 通知栏 / 悬浮窗的一行摘要 */
    fun brief(): String = buildString {
        append(state.label)
        if (stepTotal > 0) append(" · 步骤 $stepIndex/$stepTotal")
        when {
            target > 0 -> append(" · $counterLabel $followed/$target")
            // 没有总量目标的流程（如直播互动）只显示已完成数
            followed > 0 -> append(" · $counterLabel $followed")
        }
        // 养号才有这两个数：关注/直播流程一直是 0，不该在它们的通知里多出一截
        if (liked > 0 || collected > 0) append(" · 赞 $liked · 藏 $collected")
        if (message.isNotBlank()) append(" · $message")
    }
}
