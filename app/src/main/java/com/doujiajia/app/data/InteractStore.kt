package com.doujiajia.app.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直播互动的当日评论计数。
 *
 * 为什么单独开一个 store 而不塞进 [FollowedStore]：M2 的「今日已关注」是关注额度，
 * M3 的「今日已评论」是评论额度，两条业务的上限、重置节奏、用户心智都不同——
 * 混用一个计数器会出现「关注 20 个就把评论额度吃光」这种说不清的账。
 *
 * 只有当日计数，没有名单：评论不像关注有「重复关注同一人」的去重需求，
 * 连续发同一句话靠 [com.doujiajia.app.engine.flow.LiveInteractFlow] 里的重复窗口挡。
 */
class InteractStore private constructor(private val prefs: SharedPreferences) {

    private fun today(): String = DAY_FORMAT.format(Date())

    private fun rolloverIfNeeded() {
        if (prefs.getString(KEY_DAY, null) != today()) {
            prefs.edit().putString(KEY_DAY, today()).putInt(KEY_COUNT, 0).apply()
        }
    }

    /** 今日已发评论数（跨进程、跨场次累计；跨天自动清零） */
    @Synchronized
    fun todayCount(): Int {
        rolloverIfNeeded()
        return prefs.getInt(KEY_COUNT, 0)
    }

    fun remainingToday(dailyLimit: Int): Int = (dailyLimit - todayCount()).coerceAtLeast(0)

    /** 发送成功后 +1，返回累计值 */
    @Synchronized
    fun bumpToday(): Int {
        rolloverIfNeeded()
        val next = prefs.getInt(KEY_COUNT, 0) + 1
        prefs.edit().putInt(KEY_COUNT, next).apply()
        return next
    }

    companion object {
        private const val SP_NAME = "interact_store"
        private const val KEY_DAY = "comment_day"
        private const val KEY_COUNT = "comment_day_count"
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        @Volatile
        private var instance: InteractStore? = null

        fun get(context: Context): InteractStore =
            instance ?: synchronized(this) {
                instance ?: InteractStore(
                    context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
