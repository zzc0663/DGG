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
    val consecutiveFailures: Int = 0,
    val message: String = "",
    val updatedAt: Long = 0L
) {
    /** 通知栏 / 悬浮窗的一行摘要 */
    fun brief(): String = buildString {
        append(state.label)
        if (stepTotal > 0) append(" · 步骤 $stepIndex/$stepTotal")
        if (target > 0) append(" · 已关注 $followed/$target")
        if (message.isNotBlank()) append(" · $message")
    }
}
