package com.example.dgg.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.dgg.R
import com.example.dgg.data.LogStore
import com.example.dgg.databinding.ActivityLogBinding
import java.io.File

/**
 * 运行日志页。
 *
 * 用 ScrollView + 动态 TextView 而不是 RecyclerView：recyclerview 依赖本机只缓存了
 * 1.1.0（过旧），新版本需要联网拉取；日志条目量级最高 2000、只渲染末 400 条，
 * ScrollView 完全够用且零依赖。后续若要支持万级日志再换。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    /** null = 全部；否则为最小级别 */
    private var minLevel: LogStore.Level? = null

    private val onLogChanged: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            LogStore.clear()
            LogStore.i(TAG, "日志已清空")
        }
        binding.btnExport.setOnClickListener { export() }

        setupFilters()
        LogStore.addListener(onLogChanged)
        render()
    }

    override fun onDestroy() {
        LogStore.removeListener(onLogChanged)
        super.onDestroy()
    }

    private fun setupFilters() {
        val items = listOf(
            binding.fltAll to null,
            binding.fltDebug to LogStore.Level.DEBUG,
            binding.fltWarn to LogStore.Level.WARN,
            binding.fltError to LogStore.Level.ERROR
        )
        fun paint(selected: Int) {
            items.forEachIndexed { index, (view, _) ->
                val on = index == selected
                view.setBackgroundResource(
                    if (on) R.drawable.bg_segment_selected else R.drawable.bg_segment_normal
                )
                view.setTextColor(
                    ContextCompat.getColor(this, if (on) R.color.white else R.color.text_primary)
                )
                view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        items.forEachIndexed { index, (view, level) ->
            view.setOnClickListener {
                minLevel = level
                paint(index)
                render()
            }
        }
        paint(0)
    }

    private fun render() {
        val all = LogStore.snapshot(minLevel)
        val shown = all.takeLast(MAX_RENDER)

        binding.logContainer.removeAllViews()
        if (shown.isEmpty()) {
            binding.logContainer.addView(lineView("（暂无日志）", ContextCompat.getColor(this, R.color.text_tertiary)))
        } else {
            shown.forEach { entry ->
                binding.logContainer.addView(lineView(LogStore.format(entry), colorOf(entry.level)))
            }
        }

        binding.tvFooter.text = if (all.size > shown.size) {
            "共 ${all.size} 条，仅显示最近 ${shown.size} 条 · 上限 ${LogStore.snapshot().size}/2000"
        } else {
            "共 ${all.size} 条 · 上限 2000"
        }

        // 渲染后滚到底部，跟随最新日志
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun lineView(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.START
        setPadding(0, 3, 0, 3)
        setTextIsSelectable(true)
    }

    private fun colorOf(level: LogStore.Level): Int = ContextCompat.getColor(
        this,
        when (level) {
            LogStore.Level.DEBUG -> R.color.text_tertiary
            LogStore.Level.INFO -> R.color.text_primary
            LogStore.Level.WARN -> R.color.warning_amber
            LogStore.Level.ERROR -> R.color.brand_red
        }
    )

    /** 导出为 txt 走系统分享；无可用分享目标时退化为复制到剪贴板 */
    private fun export() {
        val text = LogStore.exportText()
        if (text.isBlank()) {
            toast("没有可导出的日志")
            return
        }
        val saved = runCatching {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "dgg-log-${System.currentTimeMillis()}.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "DGG 运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出日志"))
            file
        }

        if (saved.isFailure) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DGG 运行日志", text))
            toast("无可用分享目标，已复制到剪贴板")
            LogStore.w(TAG, "导出失败，已退化为剪贴板：${saved.exceptionOrNull()?.message}")
        } else {
            LogStore.i(TAG, "日志已导出：${saved.getOrNull()?.name}")
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "日志页"

        /** 单次渲染上限，避免 2000 条全部建 TextView 卡顿 */
        private const val MAX_RENDER = 400
    }
}
