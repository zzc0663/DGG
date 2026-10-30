package com.doujiajia.app.data

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

    /**
     * 这个昵称是否被判定过「私密账号」（关注只会发出申请，粉丝数不会增加）。
     *
     * 与已关注名单**分开存**：私密账号并没有被关注成功，混进 [names] 会让
     * 「已关人数 / 今日已关注」两个数字说谎，也会让 `remainingToday` 算错额度。
     * 但两边都要参与去重——不然每次见到同一个人还要白点一次「关注」。
     */
    fun isPrivate(nickname: String): Boolean = privateNames().contains(nickname)

    fun privateCount(): Int = privateNames().size

    /**
     * 记下「这个是私密账号，以后别再试」。
     *
     * 为什么要持久化：私密账号在直播间评论区会反复说话，一次会话里遇到同一个人好几次
     * 很常见；不记的话每一轮都要重新点开资料卡、点一次关注、再等界面变申请态，纯浪费。
     */
    @Synchronized
    fun markPrivate(nickname: String) {
        val set = LinkedHashSet(privateNames())
        if (!set.add(nickname)) return
        while (set.size > MAX_NAMES) {
            val iterator = set.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        prefs.edit().putString(KEY_PRIVATE, set.joinToString(SEPARATOR)).apply()
    }

    /**
     * 全部已关注昵称，**按关注时间倒序**（最新关注的最前）。
     * 悬浮面板「已关人数」直接展示它；数据本身就是这个顺序（markFollowed 追加到尾部）。
     */
    fun allNames(): List<String> = names().toList().asReversed()

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

    /**
     * 手动把「今日关注」清零（悬浮面板上的「重置」）。
     *
     * 只清当日计数，**不动已关注名单**：名单是去重用的，清了会让脚本回头重复关注同一批人，
     * 那是最典型的机器行为特征。用户想重置的永远是「今天还能关注多少个」这个额度。
     */
    @Synchronized
    fun resetToday() {
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_DAY_COUNT, 0)
            .apply()
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

    private fun privateNames(): Set<String> {
        val raw = prefs.getString(KEY_PRIVATE, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    private fun today(): String = dayFormat.format(Date())

    companion object {
        private const val TAG = "关注记录"
        private const val PREFS = "dgg_followed"
        private const val KEY_NAMES = "followed_names"
        /** 私密账号名单：关注只会发申请、粉丝数不增加，永远不会进 [KEY_NAMES] */
        private const val KEY_PRIVATE = "private_names"
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
