package com.example.dgg.data

/**
 * 三页签全部可配置参数的单一数据源。
 *
 * 字段顺序与界面顺序一致，便于对照维护。新增界面参数时必须同时改三处：
 * 本文件、[SettingsStore] 的读写、以及界面的绑定逻辑。
 */
data class Settings(
    // ===== 自动关注 · 参数设置 =====
    /** 订阅模式：0=手动校准 1=自动校准 */
    val calMode: Int = 1,
    /** 目标关注数量 */
    val followTarget: Int = 200,
    /** 扫描间隔（毫秒），实际会叠加抖动 */
    val scanIntervalMs: Int = 1500,
    /** 每日关注上限，达到即停 */
    val dailyLimit: Int = 200,
    /** 延迟抖动幅度（%），对扫描间隔与休息时长生效 */
    val jitterPercent: Int = 30,
    val idleLive: Boolean = false,
    val applyMic: Boolean = false,
    val continueAfterMic: Boolean = false,
    val intervalRest: Boolean = true,
    val restAfterN: Int = 5,
    val restSeconds: Int = 30,

    // ===== 养号助手 =====
    val runMinutes: Int = 60,
    /** 养号模式：0=挂机涨粉 1=垂直养号 2=首页连播 */
    val nurtureMode: Int = 0,
    val probLike: Int = 40,
    val probCollect: Int = 15,
    val probComment: Int = 10,
    val probShare: Int = 5,

    // ===== 直播互动 =====
    /** 进入方式：0=手动进入 1=系统推荐 */
    val enterMode: Int = 0,
    val micRequest: Boolean = false,
    val commentGapMin: Int = 5,
    val commentGapMax: Int = 15,
    val likeOnComment: Boolean = true,
    val likeTrigger: Int = 3,
    val likeCountMin: Int = 5,
    val likeCountMax: Int = 10,
    /** 评论模式：0=复制评论 1=预设评论 */
    val commentMode: Int = 0
)
