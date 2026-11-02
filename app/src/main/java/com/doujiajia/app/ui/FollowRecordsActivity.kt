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
    private var lastDayKey: String? = null

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

    private fun loadMore() {
        if (loading || noMore) return
        loading = true
        val page = FollowRecordStore.get(this).read(offset, PAGE_SIZE)
        if (page.isEmpty()) {
            noMore = true
        } else {
            appendRows(page)
            offset += page.size
            if (page.size < PAGE_SIZE) noMore = true
        }
        loading = false
        adapter.submit(rows)
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 按天插入日期头。记录本身是时间倒序，所以同一天一定是连续的 */
    private fun appendRows(page: List<FollowRecordStore.Record>) {
        for (r in page) {
            val key = dayFormat.format(Date(r.ts))
            if (key != lastDayKey) {
                rows.add(FollowRecordsAdapter.Row(dayLabel = dayLabel(key)))
                lastDayKey = key
            }
            rows.add(FollowRecordsAdapter.Row(record = r))
        }
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
