package com.example.dgg.service

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.dgg.data.LogStore
import com.example.dgg.data.SettingsStore
import com.example.dgg.engine.Actions
import com.example.dgg.engine.LocatorConfigLoader
import com.example.dgg.engine.PageDetector
import com.example.dgg.engine.risk.Humanizer
import com.example.dgg.util.ActiveWindow
import com.example.dgg.util.NodeDumper
import java.io.File
import java.util.Locale

/**
 * 真机调试通道：轮询 `files/debug_cmd`，执行后删除并把结果写进 `files/dump/`。
 *
 * 为什么需要这么一条通道（三个约束叠加，别处都走不通）：
 *
 * 1. 本机 HyperOS 不给 adb shell `INJECT_EVENTS` / `WRITE_SECURE_SETTINGS`，
 *    `input tap`、`settings put`、`pm grant` 全部被拒；
 * 2. 直播间画面持续动画，`uiautomator dump` 永远等不到 idle state；
 * 3. 发给 cached 进程的广播被 Greezer 拦下（`need cached broadcast`）。
 *
 * 反过来，无障碍服务的 `rootInActiveWindow` / `dispatchGesture` 都能用，
 * 所以「写文件 → 服务读取执行 → 写结果文件」是唯一稳定的自动化入口。
 *
 * 启动点必须**同时**挂在服务连接与界面创建：HyperOS 在应用被强杀/覆盖安装后
 * 不一定会重新回调 `onServiceConnected`，只挂服务会静默失效。
 *
 * 仅 debuggable 构建生效；发布版本不会启动。
 */
object DebugChannel {

    private const val TAG = "调试通道"
    private const val CMD_FILE = "debug_cmd"
    private const val LOG_FILE = "runtime.log"
    private const val POLL_MS = 800L

    @Volatile
    private var started = false

    fun startIfNeeded(context: Context) {
        if (started) return
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        synchronized(this) {
            if (started) return
            started = true
        }

        val base = context.applicationContext.filesDir
        LogStore.attachFileSink(File(base, LOG_FILE))

        val cmdFile = File(base, CMD_FILE)
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                runCatching {
                    if (cmdFile.isFile) {
                        val cmd = cmdFile.readText().trim().uppercase(Locale.US)
                        cmdFile.delete()
                        execute(cmd, base)
                    }
                }.onFailure { LogStore.w(TAG, "命令处理异常：${it.message}") }
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed(tick, POLL_MS)
        LogStore.i(TAG, "已开启，命令文件 ${cmdFile.absolutePath}")
    }

    private fun execute(cmd: String, base: File) {
        val dumpDir = File(base, "dump").apply { mkdirs() }
        val service = DggAccessibilityService.current

        when {
            cmd == "DUMP" -> {
                if (service == null) {
                    LogStore.e(TAG, "DUMP 失败：无障碍服务未连接")
                    return
                }
                val n = NodeDumper.dump(service, File(dumpDir, "window.xml"))
                File(dumpDir, "result.txt").writeText("DUMP nodes=$n")
                LogStore.i(TAG, "DUMP 完成：$n 个节点")
            }

            cmd == "PAGE" -> {
                if (service == null) {
                    LogStore.e(TAG, "PAGE 失败：无障碍服务未连接")
                    return
                }
                val config = LocatorConfigLoader.load(service, forceReload = true)
                val actions = Actions(
                    service,
                    Humanizer(SettingsStore.get(service).current.jitterPercent)
                )
                val d = PageDetector(config, actions).detect()
                File(dumpDir, "page.txt").writeText(
                    buildString {
                        append("page=").append(d.page.name).append('\n')
                        append("score=").append(d.score).append('/').append(d.threshold).append('\n')
                        append("nearest=").append(d.pageKey).append('\n')
                        append("hits=")
                        if (d.hits.isEmpty()) append("(none)") else append(d.hits.joinToString(" | "))
                        append('\n')
                    }
                )
                LogStore.i(TAG, "PAGE 完成：${d.page.name} ${d.score}/${d.threshold}")
            }

            cmd == "BACK" -> {
                if (service == null) {
                    LogStore.e(TAG, "BACK 失败：无障碍服务未连接")
                    return
                }
                LogStore.i(TAG, "BACK：${service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)}")
            }

            cmd.startsWith("TAP ") -> {
                val v = cmd.removePrefix("TAP ").trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                if (service == null || v.size < 2) {
                    LogStore.w(TAG, "TAP 失败：service=${service != null} 参数=$cmd")
                    return
                }
                val path = Path().apply { moveTo(v[0], v[1]) }
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
                    .build()
                LogStore.i(TAG, "TAP (${v[0]},${v[1]})：${service.dispatchGesture(g, null, null)}")
            }

            cmd.startsWith("DTAP ") -> {
                // 双击：校准「点赞」时用。两个 stroke 的时间必须错开，
                // 否则系统会当成双指手势（见 Actions.doubleTap 的注释）。
                val v = cmd.removePrefix("DTAP ").trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                if (service == null || v.size < 2) {
                    LogStore.w(TAG, "DTAP 失败：service=${service != null} 参数=$cmd")
                    return
                }
                val path = Path().apply { moveTo(v[0], v[1]) }
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                    .addStroke(GestureDescription.StrokeDescription(path, 180, 60))
                    .build()
                LogStore.i(TAG, "DTAP (${v[0]},${v[1]})：${service.dispatchGesture(g, null, null)}")
            }

            cmd.startsWith("SWIPE ") -> {
                val v = cmd.removePrefix("SWIPE ").trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                if (service == null || v.size < 4) {
                    LogStore.w(TAG, "SWIPE 失败：service=${service != null} 参数=$cmd")
                    return
                }
                val path = Path().apply {
                    moveTo(v[0], v[1])
                    lineTo(v[2], v[3])
                }
                val g = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
                    .build()
                LogStore.i(TAG, "SWIPE (${v[0]},${v[1]})→(${v[2]},${v[3]})：${service.dispatchGesture(g, null, null)}")
            }

            cmd.startsWith("TEXT ") -> {
                // 调试通道专用的中文输入：`adb shell input text` 不支持中文，
                // 这里用无障碍 SET_TEXT 一次性写入第一个可编辑节点。
                val text = cmd.removePrefix("TEXT ")
                if (service == null || text.isBlank()) {
                    LogStore.w(TAG, "TEXT 失败：service=${service != null} 参数空")
                    return
                }
                val root = ActiveWindow.root(service)
                if (root == null) {
                    LogStore.e(TAG, "TEXT 失败：读不到活动窗口")
                    return
                }
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.addLast(root)
                var visited = 0
                var done = false
                while (queue.isNotEmpty() && visited < 1200 && !done) {
                    val node = queue.removeFirst()
                    visited++
                    if (node.isEditable) {
                        val ok = node.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    text
                                )
                            }
                        )
                        LogStore.i(TAG, "TEXT \"$text\"：$ok（第 $visited 个节点命中 editable）")
                        done = true
                        break
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.addLast(it) }
                    }
                }
                if (!done) LogStore.e(TAG, "TEXT 失败：页面上没有可编辑节点")
            }

            else -> LogStore.w(TAG, "未知命令：$cmd")
        }
    }
}
