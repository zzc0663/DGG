package com.example.dgg.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已关注名单 + 当日计数。
 *
 * 为什么必须持久化：脚本跑一晚上会关注几百人，重启应用后如果名单丢了，
 * 第二天会从同一批人开始重复关注——既浪费额度，也是最典型的机器行为特征。
 *
 * 为什么用昵称做键：可访问性通道拿不到用户 uid，昵称是唯一稳定标识。
 * **代价是同名不同人会误杀**，这一点不掩盖，写在文档里。名单有上限，
 * 超过后先进先出淘汰，避免 SharedPreferences 无限膨胀。
 */
class FollowedStore private constructor(private val prefs: SharedPreferences) {

    fun isFollowed(nickname: String): Boolean = names().contains(nickname)

    @Synchronized
    fun markFollowed(nickname: String) {
        val set = LinkedHashSet(names())
        set.add(nickname)
        while (set.size > MAX_NAMES) {
            val iterator = set.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        prefs.edit().putString(KEY_NAMES, set.joinToString(SEPARATOR)).apply()
    }

    fun knownCount(): Int = names().size

    fun todayCount(): Int {
        if (prefs.getString(KEY_DAY, null) != today()) return 0
        return prefs.getInt(KEY_DAY_COUNT, 0)
    }

    fun remainingToday(dailyLimit: Int): Int = (dailyLimit - todayCount()).coerceAtLeast(0)

    @Synchronized
    fun bumpToday(): Int {
        rolloverIfNeeded()
        val next = prefs.getInt(KEY_DAY_COUNT, 0) + 1
        prefs.edit().putInt(KEY_DAY_COUNT, next).apply()
        return next
    }

    private fun rolloverIfNeeded() {
        if (prefs.getString(KEY_DAY, null) != today()) {
            prefs.edit().putString(KEY_DAY, today()).putInt(KEY_DAY_COUNT, 0).apply()
            LogStore.i(TAG, "跨天，今日关注计数清零（日期 ${today()}）")
        }
    }

    private fun names(): Set<String> {
        val raw = prefs.getString(KEY_NAMES, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    private fun today(): String = dayFormat.format(Date())

    companion object {
        private const val TAG = "关注记录"
        private const val PREFS = "dgg_followed"
        private const val KEY_NAMES = "followed_names"
        private const val KEY_DAY = "day_key"
        private const val KEY_DAY_COUNT = "day_count"
        private const val SEPARATOR = "\n"
        private const val MAX_NAMES = 3_000

        private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        @Volatile
        private var instance: FollowedStore? = null

        fun get(context: Context): FollowedStore =
            instance ?: synchronized(this) {
                instance ?: FollowedStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
