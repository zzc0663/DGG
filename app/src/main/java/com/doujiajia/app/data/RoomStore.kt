package com.doujiajia.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏的直播间。
 *
 * 为什么只存「主播昵称」而不存房间 id：无障碍通道拿不到直播间 id，
 * 昵称是唯一稳定标识（和 [FollowedStore] 用昵称去重是同一个取舍——同名主播会撞，
 * 已知代价）。
 *
 * 「自动进收藏的房间」的完整链路是：首页 → 搜索昵称 → 用户 tab → 进主页 → 点「直播中」。
 * 每一步都可能失败（改名、没开播、搜索被风控），所以流程把结果如实上报，
 * 失败就明确停机，绝不退化成「随便进一个直播间」。
 */
class RoomStore private constructor(private val prefs: SharedPreferences) {

    data class SavedRoom(val nickname: String, val savedAt: Long)

    @Synchronized
    fun all(): List<SavedRoom> {
        val raw = prefs.getString(KEY_ROOMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("n").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SavedRoom(n, o.optLong("t"))
            }
        }.getOrDefault(emptyList())
    }

    /** @return true=新增成功；false=已收藏过或超出上限 */
    @Synchronized
    fun add(nickname: String): Boolean {
        val name = nickname.trim()
        if (name.isEmpty()) return false
        val rooms = all().toMutableList()
        if (rooms.any { it.nickname == name }) return false
        if (rooms.size >= MAX_ROOMS) rooms.removeAt(rooms.lastIndex)
        rooms.add(0, SavedRoom(name, System.currentTimeMillis()))
        persist(rooms)
        return true
    }

    /**
     * 最近一次确认所在的直播间主播昵称（由运行中的 Flow 每轮刷新）。
     * 悬浮面板「收藏直播间」在面板展开时读不到实时节点树（抖音窗口退为 inactive），
     * 就用这个值——它比实时读更可靠，因为 Flow 是在面板必然收起的时刻读的。
     */
    @Volatile
    var lastAnchor: String? = null
        private set

    fun rememberAnchor(nickname: String) {
        lastAnchor = nickname
    }

    @Synchronized
    fun remove(nickname: String) {
        persist(all().filterNot { it.nickname == nickname })
    }

    /**
     * 「选择直播间」点选后写入的待进入目标。
     * 任务启动时读走并清空——它是「用户明确想让脚本进这个房间」的一次性指令。
     */
    @Synchronized
    fun takePendingTarget(): String? {
        val v = prefs.getString(KEY_PENDING, null)?.takeIf { it.isNotBlank() } ?: return null
        prefs.edit().remove(KEY_PENDING).apply()
        return v
    }

    @Synchronized
    fun setPendingTarget(nickname: String) {
        prefs.edit().putString(KEY_PENDING, nickname).apply()
    }

    @Synchronized
    fun clearPendingTarget() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    private fun persist(rooms: List<SavedRoom>) {
        val arr = JSONArray()
        rooms.forEach { arr.put(JSONObject().put("n", it.nickname).put("t", it.savedAt)) }
        prefs.edit().putString(KEY_ROOMS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "dgg_rooms"
        private const val KEY_ROOMS = "saved_rooms"
        private const val KEY_PENDING = "pending_room"
        private const val MAX_ROOMS = 20

        @Volatile
        private var instance: RoomStore? = null

        fun get(context: Context): RoomStore =
            instance ?: synchronized(this) {
                instance ?: RoomStore(
                    context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
