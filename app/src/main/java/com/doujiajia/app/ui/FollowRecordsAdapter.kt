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
 * 关注记录列表（vc62）。
 *
 * 两种行：**日期头**与**记录**。用 ListView 的 view type 机制隔离，
 * 否则日期头那种「无背景 TextView」会被复用给记录行，卡片圆角就没了。
 */
class FollowRecordsAdapter(private val context: Context) : BaseAdapter() {

    /**
     * 列表行。日期头与记录互斥（[record] 为 null 即日期头）。
     * [dayLabel] 只在日期头上用，形如「2026-11-02 · 今天」。
     */
    class Row(
        val record: FollowRecordStore.Record? = null,
        val dayLabel: String = ""
    ) {
        val isDay: Boolean get() = record == null
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

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (rows[position].isDay) TYPE_DAY else TYPE_RECORD

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        return if (row.isDay) bindDay(row, convertView, parent)
        else bindRecord(row, convertView, parent)
    }

    private fun bindDay(row: Row, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_follow_record_day, parent, false)
        (v as TextView).text = row.dayLabel
        return v
    }

    private fun bindRecord(row: Row, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_follow_record, parent, false)
        val holder = (v.tag as? Holder) ?: Holder(
            time = v.findViewById(R.id.tv_time),
            nickname = v.findViewById(R.id.tv_nickname),
            anchor = v.findViewById(R.id.tv_anchor)
        ).also { v.tag = it }

        val r = row.record ?: return v
        holder.time.text = timeFormat.format(Date(r.ts))
        holder.nickname.text = r.nickname
        // 主播名读不到时如实写「—」：不猜、不拿上一个直播间凑数
        holder.anchor.text = r.anchor?.let { "主播 $it" } ?: "主播 —"
        return v
    }

    private class Holder(val time: TextView, val nickname: TextView, val anchor: TextView)

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_RECORD = 1
    }
}
