package com.doujiajia.app.data

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 运行日志：内存环形缓冲 + 监听器。
 *
 * M0 阶段先只做内存（2000 条）。落盘（按天滚动写文件）留到 M1，
 * 因为那时候才有常驻服务真正长时间运行，需要跨进程重启后仍可回溯。
 *
 * 线程安全：后台服务与 UI 会并发写，故所有读写都在同一把锁内。
 */
object LogStore {

    enum class Level(val label: String) {
        DEBUG("D"), INFO("I"), WARN("W"), ERROR("E")
    }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val CAPACITY = 2000
    private const val MAX_LOGCAT_CHARS = 3500
    private const val MAX_LOG_FILE_BYTES = 2L * 1024 * 1024

    @Volatile
    private var fileSink: File? = null

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    fun log(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
        }
        mirrorToLogcat(level, tag, message)
        appendToFile(entry)
        listeners.forEach { it() }
    }

    /**
     * 把日志同时追加到文件。
     *
     * 本机 HyperOS 会把第三方应用的 logcat 输出吞掉（`adb logcat` 里只能看到系统与
     * 目标 App 的日志，本进程一条都没有），所以真机联调时 logcat 完全不可用。
     * 文件通道是唯一能拿到「引擎当时看到了什么、怎么判断的」的手段。
     *
     * 只在调试构建 attach；超过上限直接清空重写，避免长期运行把存储写满。
     */
    fun attachFileSink(file: File) {
        synchronized(lock) { fileSink = file }
        runCatching { if (file.length() > MAX_LOG_FILE_BYTES) file.writeText("") }
    }

    private fun appendToFile(entry: Entry) {
        val file = fileSink ?: return
        runCatching {
            if (file.length() > MAX_LOG_FILE_BYTES) file.writeText("")
            file.appendText(format(entry) + "\n")
        }
    }

    /**
     * 同时写到 logcat。
     *
     * 没有这一条，真机联调只能靠「打开应用 → 翻日志页 → 截图」，
     * 自动化观察不到中间过程；而这类任务出问题时最需要的恰恰是
     * 「它到底看到了什么、为什么这样判断」。
     *
     * logcat 单条上限约 4KB，超长截断，避免异常堆栈类消息把日志冲掉。
     */
    private fun mirrorToLogcat(level: Level, tag: String, message: String) {
        val text = if (message.length > MAX_LOGCAT_CHARS) {
            message.substring(0, MAX_LOGCAT_CHARS) + "…(截断)"
        } else {
            message
        }
        runCatching {
            when (level) {
                Level.DEBUG -> Log.d(tag, text)
                Level.INFO -> Log.i(tag, text)
                Level.WARN -> Log.w(tag, text)
                Level.ERROR -> Log.e(tag, text)
            }
        }
    }

    fun snapshot(levelFilter: Level? = null): List<Entry> = synchronized(lock) {
        val all = buffer.toList()
        if (levelFilter == null) all else all.filter { it.level.ordinal >= levelFilter.ordinal }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun format(entry: Entry): String = synchronized(timeFormat) {
        val time = timeFormat.format(Date(entry.time))
        "$time ${entry.level.label}/${entry.tag}: ${entry.message}"
    }

    fun exportText(): String = snapshot().joinToString("\n") { format(it) }
}
