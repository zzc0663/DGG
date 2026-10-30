package com.doujiajia.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 参数持久化。
 *
 * 选择 SharedPreferences 而非 DataStore 的理由：全部参数是 25 个标量，
 * 没有集合、没有迁移需求；DataStore 会引入 datastore-preferences 依赖（本机未缓存）
 * 以及一整套协程 Flow 管道，对当前场景无收益。等参数出现嵌套结构或需要跨进程观察时再换。
 *
 * 全进程单例，后续 TaskEngine / 前台服务共用同一份缓存。
 */
class SettingsStore private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: Settings = readFromSp()

    /** 当前参数快照（内存缓存，读取零成本） */
    val current: Settings
        get() = cached

    /**
     * 修改参数：变换后立即落盘并更新缓存。
     * 只写单一字段会很啰嗦（25 个字段 × 2 处映射），这里整份回写，量级可忽略。
     */
    fun edit(transform: (Settings) -> Settings) {
        cached = transform(cached)
        writeAll(cached)
    }

    fun update(newSettings: Settings) {
        cached = newSettings
        writeAll(newSettings)
    }

    private fun readFromSp(): Settings {
        val d = Settings()
        return Settings(
            calMode = sp.getInt(KEY_CAL_MODE, d.calMode),
            followTarget = sp.getInt(KEY_FOLLOW_TARGET, d.followTarget),
            scanIntervalValue = sp.getInt(KEY_SCAN_VALUE, migrateScanValue()),
            scanIntervalUnit = sp.getInt(KEY_SCAN_UNIT, d.scanIntervalUnit),
            dailyLimit = sp.getInt(KEY_DAILY_LIMIT, d.dailyLimit),
            jitterPercent = sp.getInt(KEY_JITTER, d.jitterPercent),
            idleLive = sp.getBoolean(KEY_IDLE_LIVE, d.idleLive),
            applyMic = sp.getBoolean(KEY_APPLY_MIC, d.applyMic),
            continueAfterMic = sp.getBoolean(KEY_CONTINUE_MIC, d.continueAfterMic),
            intervalRest = sp.getBoolean(KEY_INTERVAL_REST, d.intervalRest),
            restAfterN = sp.getInt(KEY_REST_AFTER_N, d.restAfterN),
            restSeconds = sp.getInt(KEY_REST_SECONDS, d.restSeconds),
            runMinutes = sp.getInt(KEY_RUN_MINUTES, d.runMinutes),
            nurtureMode = sp.getInt(KEY_NURTURE_MODE, d.nurtureMode),
            probLike = sp.getInt(KEY_PROB_LIKE, d.probLike),
            probCollect = sp.getInt(KEY_PROB_COLLECT, d.probCollect),
            probComment = sp.getInt(KEY_PROB_COMMENT, d.probComment),
            probShare = sp.getInt(KEY_PROB_SHARE, d.probShare),
            nurtureTags = sp.getString(KEY_NURTURE_TAGS, d.nurtureTags).orEmpty(),
            nurturePace = sp.getInt(KEY_NURTURE_PACE, d.nurturePace),
            enterMode = sp.getInt(KEY_ENTER_MODE, d.enterMode),
            micRequest = sp.getBoolean(KEY_MIC_REQUEST, d.micRequest),
            commentGapMin = sp.getInt(KEY_GAP_MIN, d.commentGapMin),
            commentGapMax = sp.getInt(KEY_GAP_MAX, d.commentGapMax),
            likeOnComment = sp.getBoolean(KEY_LIKE_ON_COMMENT, d.likeOnComment),
            likeTrigger = sp.getInt(KEY_LIKE_TRIGGER, d.likeTrigger),
            likeCountMin = sp.getInt(KEY_LIKE_MIN, d.likeCountMin),
            likeCountMax = sp.getInt(KEY_LIKE_MAX, d.likeCountMax),
            commentMode = sp.getInt(KEY_COMMENT_MODE, d.commentMode)
        )
    }

    private fun writeAll(s: Settings) {
        sp.edit()
            .putInt(KEY_CAL_MODE, s.calMode)
            .putInt(KEY_FOLLOW_TARGET, s.followTarget)
            .putInt(KEY_SCAN_VALUE, s.scanIntervalValue)
            .putInt(KEY_SCAN_UNIT, s.scanIntervalUnit)
            .putInt(KEY_DAILY_LIMIT, s.dailyLimit)
            .putInt(KEY_JITTER, s.jitterPercent)
            .putBoolean(KEY_IDLE_LIVE, s.idleLive)
            .putBoolean(KEY_APPLY_MIC, s.applyMic)
            .putBoolean(KEY_CONTINUE_MIC, s.continueAfterMic)
            .putBoolean(KEY_INTERVAL_REST, s.intervalRest)
            .putInt(KEY_REST_AFTER_N, s.restAfterN)
            .putInt(KEY_REST_SECONDS, s.restSeconds)
            .putInt(KEY_RUN_MINUTES, s.runMinutes)
            .putInt(KEY_NURTURE_MODE, s.nurtureMode)
            .putInt(KEY_PROB_LIKE, s.probLike)
            .putInt(KEY_PROB_COLLECT, s.probCollect)
            .putInt(KEY_PROB_COMMENT, s.probComment)
            .putInt(KEY_PROB_SHARE, s.probShare)
            .putString(KEY_NURTURE_TAGS, s.nurtureTags)
            .putInt(KEY_NURTURE_PACE, s.nurturePace)
            .putInt(KEY_ENTER_MODE, s.enterMode)
            .putBoolean(KEY_MIC_REQUEST, s.micRequest)
            .putInt(KEY_GAP_MIN, s.commentGapMin)
            .putInt(KEY_GAP_MAX, s.commentGapMax)
            .putBoolean(KEY_LIKE_ON_COMMENT, s.likeOnComment)
            .putInt(KEY_LIKE_TRIGGER, s.likeTrigger)
            .putInt(KEY_LIKE_MIN, s.likeCountMin)
            .putInt(KEY_LIKE_MAX, s.likeCountMax)
            .putInt(KEY_COMMENT_MODE, s.commentMode)
            .apply()
    }

    /**
     * 旧版本把扫描间隔直接存毫秒（`scan_interval=1500`）。
     * 新版本存「数值 + 单位」，所以首次读取时把旧毫秒换算成秒——只在旧键存在且新键从未写过时生效。
     */
    private fun migrateScanValue(): Int {
        // 全新安装（没有旧键）：直接用新的默认值 30 秒，不要沿用旧的 1.5 秒
        if (!sp.contains(KEY_SCAN_INTERVAL_LEGACY)) return Settings.DEFAULT_SCAN_VALUE
        val legacyMs = sp.getInt(KEY_SCAN_INTERVAL_LEGACY, LEGACY_DEFAULT_SCAN_MS)
        val seconds = (legacyMs + 500L) / 1000L   // 旧值是 Int 毫秒，四舍五入到秒
        return seconds.coerceIn(Settings.MIN_SCAN_SECONDS, Settings.MAX_SCAN_SECONDS).toInt()
    }

    companion object {
        private const val SP_NAME = "dgg_settings"

        private const val KEY_CAL_MODE = "cal_mode"
        private const val KEY_FOLLOW_TARGET = "follow_target"
        private const val KEY_SCAN_VALUE = "scan_interval_value"
        private const val KEY_SCAN_UNIT = "scan_interval_unit"
        /** 旧键：扫描间隔直接存毫秒，已废弃，仅用于一次性迁移 */
        private const val KEY_SCAN_INTERVAL_LEGACY = "scan_interval"
        private const val LEGACY_DEFAULT_SCAN_MS = Settings.DEFAULT_SCAN_VALUE * 1000
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_JITTER = "jitter_percent"
        private const val KEY_IDLE_LIVE = "idle_live"
        private const val KEY_APPLY_MIC = "apply_mic"
        private const val KEY_CONTINUE_MIC = "continue_after_mic"
        private const val KEY_INTERVAL_REST = "interval_rest"
        private const val KEY_REST_AFTER_N = "rest_after_n"
        private const val KEY_REST_SECONDS = "rest_seconds"
        private const val KEY_RUN_MINUTES = "run_minutes"
        private const val KEY_NURTURE_MODE = "nurture_mode"
        private const val KEY_PROB_LIKE = "prob_like"
        private const val KEY_PROB_COLLECT = "prob_collect"
        private const val KEY_PROB_COMMENT = "prob_comment"
        private const val KEY_PROB_SHARE = "prob_share"
        private const val KEY_NURTURE_TAGS = "nurture_tags"
        private const val KEY_NURTURE_PACE = "nurture_pace"
        private const val KEY_ENTER_MODE = "enter_mode"
        private const val KEY_MIC_REQUEST = "mic_request"
        private const val KEY_GAP_MIN = "gap_min"
        private const val KEY_GAP_MAX = "gap_max"
        private const val KEY_LIKE_ON_COMMENT = "like_on_comment"
        private const val KEY_LIKE_TRIGGER = "like_trigger"
        private const val KEY_LIKE_MIN = "like_min"
        private const val KEY_LIKE_MAX = "like_max"
        private const val KEY_COMMENT_MODE = "comment_mode"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
