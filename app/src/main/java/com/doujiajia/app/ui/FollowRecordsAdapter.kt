package com.doujiajia.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.doujiajia.app.R
import com.doujiajia.app.data.FollowRecordStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关注记录列表（vc63：三层结构）。
 *
 * 三种行：**日期头 → 主播分组头 → 一条记录**。
 * 必须用 ListView 的 view type 机制隔离开：否则日期头那种无背景 TextView 会被复用给记录行，
 * 卡片圆角就没了；主播头复用给记录行还会带上不该有的加粗。
 */
class FollowRecordsAdapter(private val context: Context) : BaseAdapter() {

    /**
     * 列表行。三种构造方式见 companion，[type] 直接决定用哪个 item 布局。
     *
     * @param dayLabel    日期头文案（「2026年11月4日 · 今天」）
     * @param dayCount    日期头右侧「共 N 人」
     * @param anchorLabel 主播头文案（「主播 雪儿」或「未识别主播」）
     * @param anchorCount 主播头右侧「已关注 N」
     */
    class Row private constructor(
        val record: FollowRecordStore.Record?,
        val dayLabel: String,
        val dayCount: Int,
        val anchorLabel: String,
        val anchorCount: Int,
        val type: Int
    ) {
        companion object {
            const val TYPE_DAY = 0
            const val TYPE_ANCHOR = 1
            const val TYPE_RECORD = 2

            fun day(label: String, count: Int) = Row(null, label, count, "", 0, TYPE_DAY)

            fun anchor(label: String, count: Int) = Row(null, "", 0, label, count, TYPE_ANCHOR)

            fun record(r: FollowRecordStore.Record) = Row(r, "", 0, "", 0, TYPE_RECORD)
        }
    }

    private val rows = ArrayList<Row>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(list: List<Row>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Row = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 3

    override fun getItemViewType(position: Int): Int = rows[position].type

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        return when (row.type) {
            Row.TYPE_DAY -> bindDay(row, convertView, parent)
            Row.TYPE_ANCHOR -> bindAnchor(row, convertView, parent)
            else -> bindRecord(row, convertView, parent)
        }
    }

    private fun bindDay(row: Row, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_follow_record_day, parent, false)
        val holder = (v.tag as? DayHolder) ?: DayHolder(
            label = v.findViewById(R.id.tv_day),
            count = v.findViewById(R.id.tv_day_count)
        ).also { v.tag = it }
        holder.label.text = row.dayLabel
        holder.count.text = if (row.dayCount > 0) "共 ${row.dayCount} 人" else ""
        return v
    }

    private fun bindAnchor(row: Row, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_follow_record_anchor, parent, false)
        // 主播名可能不完整（抖音在 contentDescription 里截断超长昵称），如实显示、超长走省略号
        (v as TextView).text = "${row.anchorLabel} · 已关注 ${row.anchorCount}"
        return v
    }

    private fun bindRecord(row: Row, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_follow_record, parent, false)
        val holder = (v.tag as? RecordHolder) ?: RecordHolder(
            time = v.findViewById(R.id.tv_time),
            nickname = v.findViewById(R.id.tv_nickname)
        ).also { v.tag = it }

        val r = row.record ?: return v
        holder.time.text = timeFormat.format(Date(r.ts))
        holder.nickname.text = r.nickname
        return v
    }

    private class DayHolder(val label: TextView, val count: TextView)

    private class RecordHolder(val time: TextView, val nickname: TextView)
}
