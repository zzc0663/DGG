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

    /**
     * 去重判据 = 真正关注过的 ∪ 打开资料卡时「本来就已关注」的。
     *
     * 两边都要排除（不然每次见到同一个人还要白点一次「关注」），但**只有前者算成绩**，
     * 见 [knownCount] 与 [alreadyCount] 的注释。
     */
    fun isFollowed(nickname: String): Boolean =
        names().contains(nickname) || alreadyNames().contains(nickname)

    /** 真正由本机关注成功的人。悬浮面板「已关」和名单前半段只认这一边。 */
    @Synchronized
    fun markFollowed(nickname: String) {
        val set = LinkedHashSet(names())
        if (!set.add(nickname)) return
        trim(set)
        prefs.edit().putString(KEY_NAMES, set.joinToString(SEPARATOR)).apply()
    }

    /**
     * 打开资料卡时**对方本来就已关注**（按钮已是「取消关注」），我们没关注他。
     *
     * 为什么必须单独存：混进 [KEY_NAMES] 会让「已关人数」凭空多出一截。
     * 2026-10-30 真机就是这个问题——同一场直播里 6 个人本来已关注，
     * 面板显示「已关 13」而实际只关注了 7 个，用户一眼就看出对不上
     * （日志 `R-20261030-F16CD0`：23:12:01 `历史已关注 13`，同期进度 7/200）。
     */
    @Synchronized
    fun markAlreadyFollowed(nickname: String) {
        val set = LinkedHashSet(alreadyNames())
        if (!set.add(nickname)) return
        trim(set)
        prefs.edit().putString(KEY_ALREADY, set.joinToString(SEPARATOR)).apply()
    }

    /** 本机真正关注过的人数——面板「已关 N」用的就是它 */
    fun knownCount(): Int = names().size

    /** 打开时就已关注、被我们跳过的人数（**不计入**「已关」） */
    fun alreadyCount(): Int = alreadyNames().size

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
     * 一次性迁移：把 vc41 及以前的「已关注名单」整批移进「打开时已关注」这一边。
     *
     * 为什么要迁：vc41 之前两条路都写同一个键（真关注 + 打开就是已关注），
     * 混在一起之后**没有任何办法事后区分**谁是谁，面板数字必然虚高。
     * 与其留一个永远对不上的数字，不如把历史记录明确降级成「只参与去重、不计成绩」，
     * 让「已关」从本版起重新累计——**去重集合一个都没丢**（union 不变），
     * 所以不会重复关注任何人，只是下次遇到他们要多开一次资料卡（发现是「取消关注」就跳过）。
     *
     * 只跑一次（[KEY_MIGRATED]）。当前只有 vc41 装过这个包（换了包名等于换了应用），
     * 所以受影响的就本机这一份 13 条，迁移代价可以忽略。
     */
    private fun migrateLegacyOnce() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val legacy = names()
        if (legacy.isNotEmpty() && alreadyNames().isEmpty()) {
            prefs.edit()
                .putString(KEY_ALREADY, legacy.joinToString(SEPARATOR))
                .remove(KEY_NAMES)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
            LogStore.i(TAG, "名单迁移：${legacy.size} 条旧记录无法区分来源，已归入「打开时已关注」（只参与去重）")
        } else {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
    }

    /** 全部**本机关注过**的昵称，按关注时间倒序（最新关注的最前）。
     * 「原本就已关注」的那批不在这里，见 [alreadyNames]。
     */
    fun allNames(): List<String> = names().toList().asReversed()

    /** 原本就已关注、被跳过的昵称，同样最新在前 */
    fun alreadyNames(): List<String> = alreadyNamesSet().toList().asReversed()

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

    private fun names(): Set<String> = readSet(KEY_NAMES)

    /** 「打开时就已关注」的名单（不计入成绩，只参与去重） */
    private fun alreadyNamesSet(): Set<String> = readSet(KEY_ALREADY)

    private fun privateNames(): Set<String> = readSet(KEY_PRIVATE)

    private fun readSet(key: String): Set<String> {
        val raw = prefs.getString(key, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    }

    /** 超过上限就先进先出淘汰，避免 SharedPreferences 无限膨胀 */
    private fun trim(set: LinkedHashSet<String>) {
        while (set.size > MAX_NAMES) {
            val iterator = set.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun today(): String = dayFormat.format(Date())

    companion object {
        private const val TAG = "关注记录"
        private const val PREFS = "dgg_followed"
        /** 本机**真正关注成功**的昵称 */
        private const val KEY_NAMES = "followed_names"
        /** 打开资料卡时就已关注、被跳过的昵称（vc42 起与上面分开，之前混在 [KEY_NAMES] 里） */
        private const val KEY_ALREADY = "already_followed_names"
        /** 私密账号名单：关注只会发申请、粉丝数不增加，永远不会进 [KEY_NAMES] */
        private const val KEY_PRIVATE = "private_names"
        /** vc42 的一次性迁移标记：「已关注名单」已与「打开时已关注」拆开 */
        private const val KEY_MIGRATED = "split_migrated_v42"
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
                ).also {
                    it.migrateLegacyOnce()
                    instance = it
                }
            }
    }
}
