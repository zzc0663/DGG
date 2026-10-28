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
    /**
     * 扫描间隔的**数值**，配合 [scanIntervalUnit] 使用（30 秒 / 5 分钟）。
     *
     * 拆成「数值 + 单位」而不是只存毫秒：1500ms 这种写法用户没法在手机上读懂，
     * 而 60 分钟 = 3600000ms 在输入框里既难填也容易多打一个 0。
     */
    val scanIntervalValue: Int = DEFAULT_SCAN_VALUE,
    /** 扫描间隔单位：0=秒 1=分钟 */
    val scanIntervalUnit: Int = UNIT_SECOND,
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
    /** 养号模式：0=刷视频养号 1=垂直养号 2=首页连播（只刷不互动） */
    val nurtureMode: Int = 0,
    /**
     * 点赞**倾向**（基线概率，%）。
     *
     * 它不再等于最终概率：命中文案标签会按 `base × (1 + Σ加成)` 放大，上限
     * [NurtureTags.MAX_LIKE_PERCENT] = 60。默认仍 40，用户不配标签时行为与旧版一致。
     */
    val probLike: Int = 40,
    val probCollect: Int = 15,
    val probComment: Int = 10,
    val probShare: Int = 5,
    /**
     * 文案标签规则，一段多行文本（每行「关键词」或「关键词 加成%」）。
     *
     * 存原始文本而不是解析后的结构：用户手写的内容原样保留（含写错的行），
     * 下次打开编辑框能看见自己写的东西，不会"我写的规则怎么自己消失了"。
     * 解析在 [com.example.dgg.engine.NurtureTags.parse]，容错到逐行粒度。
     */
    val nurtureTags: String = "",
    /** 停留节奏：0=慢 1=标准 2=快（整体缩放停留时长） */
    val nurturePace: Int = PACE_NORMAL,

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
) {

    /**
     * 扫描间隔（毫秒）——由「数值 + 单位」派生，唯一出口。
     *
     * 在这里一次性夹到 [MIN_SCAN_SECONDS, MAX_SCAN_SECONDS]，
     * 所有消费方（Step / Flow / 日志）拿到的就一定是合法值，不必各自再 coerceIn。
     */
    val scanIntervalMs: Long
        get() = scanTotalSeconds().coerceIn(MIN_SCAN_SECONDS, MAX_SCAN_SECONDS) * 1_000L

    /** 扫描间隔总秒数（未夹取，界面换算单位时用） */
    fun scanTotalSeconds(): Long =
        if (scanIntervalUnit == UNIT_MINUTE) scanIntervalValue * 60L else scanIntervalValue.toLong()

    /** 人类可读的扫描间隔，例如「30 秒」「5 分钟」——日志/提示一律用它，别再打毫秒 */
    val scanIntervalLabel: String
        get() = "$scanIntervalValue ${if (scanIntervalUnit == UNIT_MINUTE) "分钟" else "秒"}"

    companion object {
        /** 单位 */
        const val UNIT_SECOND = 0
        const val UNIT_MINUTE = 1

        /** 停留节奏 */
        const val PACE_SLOW = 0
        const val PACE_NORMAL = 1
        const val PACE_FAST = 2

        /** 养号模式 */
        const val NURTURE_SWIPE = 0
        const val NURTURE_VERTICAL = 1
        const val NURTURE_HOME = 2

        /** 运行时长范围（分钟） */
        const val RUN_MINUTES_MIN = 1
        const val RUN_MINUTES_MAX = 1440

        /** 默认 30 秒（降封号：宁慢不快，原 1.5 秒过于机器化） */
        const val DEFAULT_SCAN_VALUE = 30

        /** 上限 60 分钟 = 3600 秒 */
        const val MAX_SCAN_SECONDS = 3600L
        const val MIN_SCAN_SECONDS = 1L
        const val MIN_SCAN_MS = MIN_SCAN_SECONDS * 1_000L
        const val MAX_SCAN_MS = MAX_SCAN_SECONDS * 1_000L

        /** 关注数量 / 每日上限的合法范围 */
        const val FOLLOW_COUNT_MIN = 1
        const val FOLLOW_COUNT_MAX = 10_000

        /** 某单位下数值的合法上限：秒 → 3600，分钟 → 60 */
        fun scanValueMax(unit: Int): Int =
            if (unit == UNIT_MINUTE) (MAX_SCAN_SECONDS / 60).toInt() else MAX_SCAN_SECONDS.toInt()
    }
}
