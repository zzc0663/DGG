package com.doujiajia.app.ui

import android.os.Bundle
import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.doujiajia.app.R
import com.doujiajia.app.data.FollowRecordStore
import com.doujiajia.app.data.FollowedStore
import com.doujiajia.app.service.FloatWindowService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 关注记录页（vc62）：时间 / 主播 / 被关注人。
 *
 * 分页策略：一次读 300 条，滚到底再读下一批。读取走
 * [FollowRecordStore.read] 的**倒序块读**（从文件尾往前扫），
 * 所以首屏耗时与文件总长度无关——「全部保留」也不会让它越用越慢。
 */
class FollowRecordsActivity : AppCompatActivity() {

    private lateinit var adapter: FollowRecordsAdapter
    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatToday: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatRecords: TextView

    private val rows = ArrayList<FollowRecordsAdapter.Row>()
    private var offset = 0
    private var loading = false
    private var noMore = false

    /** 条数要扫一遍文件，只在进页面时算一次；onResume 回来不必重扫 */
    private var countedOnce = false

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayPrettyFormat = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_follow_records)

        listView = findViewById(R.id.lv_records)
        tvEmpty = findViewById(R.id.tv_empty)
        tvStatToday = findViewById(R.id.tv_stat_today)
        tvStatTotal = findViewById(R.id.tv_stat_total)
        tvStatRecords = findViewById(R.id.tv_stat_records)

        findViewById<TextView>(R.id.tv_back).setOnClickListener { finish() }

        adapter = FollowRecordsAdapter(this)
        listView.adapter = adapter
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                // 提前 5 行开始加载，滚到底时下一批通常已经就位，看不到空白
                if (!loading && !noMore && totalItemCount > 0 &&
                    firstVisibleItem + visibleItemCount >= totalItemCount - 5
                ) {
                    loadMore()
                }
            }
        })

        loadMore()
    }

    override fun onResume() {
        super.onResume()
        // 折叠后的悬浮胶囊仍浮在左上角，正好压住本页统计卡的「今日」——
        // 实测（2026-11-02）那个数字被整块盖住。进页面整条隐藏，离开时恢复。
        FloatWindowService.hideTemporarily()
        renderStats(withRecordCount = !countedOnce)
        countedOnce = true
    }

    override fun onPause() {
        super.onPause()
        // 回主界面或切去抖音都要把悬浮窗还回来：它在抖音里是唯一的状态入口
        FloatWindowService.restoreAfterHide()
    }

    /**
     * 三个数字的口径（与悬浮窗一致）：
     * - 今日：跨天自动归零的当日关注数；
     * - 累计：[FollowedStore.totalFollowed] 只增不减，不受去重名单 3000 上限影响；
     * - 条记录：文件真实行数。它可能比「累计」小一截（累计在升级时用旧名单播种过），
     *   两个数字对不上不是 bug，界面上分开标注就是为了不让人误会。
     */
    private fun renderStats(withRecordCount: Boolean) {
        val followed = FollowedStore.get(this)
        tvStatToday.text = followed.todayCount().toString()
        tvStatTotal.text = followed.totalFollowed().toString()
        if (withRecordCount) {
            tvStatRecords.text = FollowRecordStore.get(this).countLines().toString()
        }
    }

    /**
     * 分页加载，**按天对齐**。
     *
     * 为什么不能简单地"读 300 条就渲染"：一页读满时，最后一页可能把某一天截断。
     * 如果那天同时被分成两块，主播头的「已关注 N」就会偏小 —— 用户在一天的多次任务里
     * 来回切房间时（A 关注 8 → B 关注 4 → 又回 A 关注 3），A 会显示成「8」和「3」两个块，
     * 而不是「已关注 11」。
     *
     * 所以：一页读满时**只渲染前面完整的天**，把不完整的那天留到下一页。
     * 默认每日上限 200 < [PAGE_SIZE] 300，正常情况下一次读就跨天，感觉不到差别。
     */
    private fun loadMore() {
        if (loading || noMore) return
        loading = true
        val page = FollowRecordStore.get(this).read(offset, PAGE_SIZE)
        if (page.isEmpty()) {
            noMore = true
            loading = false
            refresh()
            return
        }

        // 记录是时间倒序 ⇒ 同一天天然连续，顺序切分即可
        val days = ArrayList<MutableList<FollowRecordStore.Record>>()
        var lastKey: String? = null
        for (r in page) {
            val key = dayFormat.format(Date(r.ts))
            if (key != lastKey) {
                days.add(ArrayList())
                lastKey = key
            }
            days.last().add(r)
        }

        val completeDays = if (page.size < PAGE_SIZE) days.size else days.size - 1
        // 兜底：某一天自己就超过一页（用户把每日上限调到 300+）。这时候只能把它切块渲染，
        // 否则 completeDays == 0 会「什么都不渲染、offset 也不推进」⇒ 卡死在这个位置。
        val renderDays = if (completeDays <= 0) 1 else completeDays

        var consumed = 0
        for (i in 0 until renderDays) {
            consumed += days[i].size
            appendDay(days[i])
        }
        offset += consumed
        if (page.size < PAGE_SIZE) noMore = true

        loading = false
        refresh()
    }

    /** 渲染一整天：日期头 → 每个主播一个分组头 → 组内按时间倒序列出条目 */
    private fun appendDay(records: List<FollowRecordStore.Record>) {
        val key = dayFormat.format(Date(records[0].ts))
        val followed = records.count { it.kind == FollowRecordStore.KIND_FOLLOW }
        rows.add(FollowRecordsAdapter.Row.day(dayLabel(key), followed))

        // 按主播聚合，保持"首次出现"顺序 —— 记录是倒序的，所以最近活跃的直播间排在前面
        val groups = LinkedHashMap<String, MutableList<FollowRecordStore.Record>>()
        for (r in records) {
            if (r.kind != FollowRecordStore.KIND_FOLLOW) continue
            groups.getOrPut(r.anchor.orEmpty()) { ArrayList() }.add(r)
        }
        for ((anchor, list) in groups) {
            // 「主播」前缀在这里拼：未识别的那组自己就带「主播」二字，不能再加一遍
            rows.add(
                FollowRecordsAdapter.Row.anchor(
                    if (anchor.isEmpty()) "未识别主播" else "主播 $anchor",
                    list.size
                )
            )
            list.forEach { rows.add(FollowRecordsAdapter.Row.record(it)) }
        }
    }

    private fun refresh() {
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dayLabel(key: String): String {
        val todayKey = dayFormat.format(Date())
        val yesterdayKey = dayFormat.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )
        val suffix = when (key) {
            todayKey -> " · 今天"
            yesterdayKey -> " · 昨天"
            else -> ""
        }
        val pretty = runCatching { dayPrettyFormat.format(dayFormat.parse(key)!!) }
            .getOrDefault(key)
        return pretty + suffix
    }

    private companion object {
        const val PAGE_SIZE = 300
    }
}
