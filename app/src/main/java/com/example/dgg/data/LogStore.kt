package com.example.dgg.data

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

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    fun log(level: Level, tag: String, message: String) {
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), level, tag, message))
        }
        listeners.forEach { it() }
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

    fun format(entry: Entry): String {
        val time = timeFormat.format(Date(entry.time))
        return "$time ${entry.level.label}/${entry.tag}: ${entry.message}"
    }

    fun exportText(): String = snapshot().joinToString("\n") { format(it) }
}
