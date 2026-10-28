package com.example.dgg.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.dgg.data.LogStore
import com.example.dgg.data.SettingsStore
import com.example.dgg.engine.Actions
import com.example.dgg.engine.LocatorConfigLoader
import com.example.dgg.engine.PageDetector
import com.example.dgg.engine.risk.Humanizer
import com.example.dgg.util.ActiveWindow
import com.example.dgg.util.NodeDumper
import com.example.dgg.util.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** WATCH 采样间隔：一次全树遍历在这台真机上约 1.4s，2s 间隔才不会自己拖垮自己 */
    private const val WATCH_STEP_MS = 2_000L

    /** WATCH 每个采样点最多打印的行数（屏幕上一屏实测 9 行） */
    private const val WATCH_MAX_ROWS = 14

    @Volatile
    private var started = false

    /**
     * OTA 联调命令（OTACHECK / OTADL）是挂起函数，不能在主线程 `execute()` 里直接跑。
     * 用 IO 作用域异步执行，结果仍然只往 [LogStore] 写，命令通道保持"发完就返回"。
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
                        // 保留原始大小写：命令关键字统一转大写匹配，但**参数不能转**
                        // （json 里的 url、sha256、中文文本都必须原样）
                        val raw = cmdFile.readText().trim()
                        cmdFile.delete()
                        execute(raw, base)
                    }
                }.onFailure { LogStore.w(TAG, "命令处理异常：${it.message}") }
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed(tick, POLL_MS)
        LogStore.i(TAG, "已开启，命令文件 ${cmdFile.absolutePath}")
    }

    private fun execute(raw: String, base: File) {
        val dumpDir = File(base, "dump").apply { mkdirs() }
        val service = DggAccessibilityService.current
        // 命令关键字大小写不敏感，但参数必须原样（json / url / sha256 / 中文都靠这个）
        val cmd = raw.uppercase(Locale.US)
        val payload = raw.indexOf(' ').let { if (it >= 0) raw.substring(it + 1).trim() else "" }

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

            cmd.startsWith("WATCH ") -> {
                // 评论区「追最新」取证专用：按固定间隔采样「所有含评论特征的节点」，
                // 记录 top/bottom/高度/是否压输入栏，输出一条时间线。
                // 纯读取，不产生任何手势——用来区分「列表其实在动、我们的判据瞎了」
                // 和「列表真的被冻住了」这两种完全不同的故障。
                val secs = cmd.removePrefix("WATCH ").trim().toIntOrNull() ?: 60
                if (service == null) {
                    LogStore.e(TAG, "WATCH 失败：无障碍服务未连接")
                    return
                }
                watchComments(service, dumpDir, secs.coerceIn(5, 300))
            }

            cmd.startsWith("TEXT ") -> {
                // 调试通道专用的中文输入：`adb shell input text` 不支持中文，
                // 这里用无障碍 SET_TEXT 一次性写入第一个可编辑节点。
                val text = payload
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

            // ==================== OTA 联调（P0/P1 没有界面，靠这三条命令验证） ====================

            cmd.startsWith("OTAJSON ") -> {
                // 覆盖本地占位的 manifest，等价于"服务端返回了这条 JSON"
                val file = File(base, com.example.dgg.data.ota.LocalOtaProvider.FILE_NAME)
                runCatching { file.writeText(payload) }
                    .onSuccess { LogStore.i(TAG, "OTAJSON 已写入 ${file.name}（${payload.length} 字符）") }
                    .onFailure { LogStore.e(TAG, "OTAJSON 写入失败：${it.message}") }
            }

            cmd == "OTACHECK" -> {
                if (service == null) {
                    LogStore.e(TAG, "OTACHECK 失败：无障碍服务未连接（它只是被借来做 Context）")
                    return
                }
                scope.launch {
                    val manifest = com.example.dgg.data.ota.OtaManager.check(service, manual = true)
                    LogStore.i(TAG, "OTACHECK 结果：${manifest?.let { "有新版本 ${it.versionName}(${it.versionCode})" } ?: "无更新"}")
                }
            }

            cmd.startsWith("OTAVERIFY ") -> {
                if (service == null) {
                    LogStore.e(TAG, "OTAVERIFY 失败：无障碍服务未连接")
                    return
                }
                val path = payload
                val apk = File(path)
                val current = com.example.dgg.data.ota.OtaVerifier.currentVersionCode(service)
                val r = com.example.dgg.data.ota.OtaVerifier.verify(service, apk, current)
                val text = when (r) {
                    is com.example.dgg.data.ota.VerifyResult.Ok ->
                        "通过：${r.versionName}(${r.versionCode})，当前 $current"
                    is com.example.dgg.data.ota.VerifyResult.Rejected -> "拒绝：${r.reason}"
                }
                File(dumpDir, "verify.txt").writeText(text)
                LogStore.i(TAG, "OTAVERIFY $path → $text")
            }

            cmd.startsWith("OTADL ") -> {
                if (service == null) {
                    LogStore.e(TAG, "OTADL 失败：无障碍服务未连接")
                    return
                }
                val args = payload.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (args.size < 2) {
                    LogStore.e(TAG, "OTADL 用法：OTADL <url> <sha256>")
                    return
                }
                val dir = File(service.cacheDir, com.example.dgg.data.ota.OtaManager.DIR_NAME)
                val fileName = "dl-test.apk"
                scope.launch {
                    val downloaded = com.example.dgg.data.ota.OtaDownloader(dir)
                        .download(args[0], args[1], fileName) { done, total ->
                            if (total > 0 && done % (2 * 1024 * 1024) < 64 * 1024) {
                                LogStore.d(TAG, "OTADL 进度 $done/$total")
                            }
                        }
                    downloaded.fold(
                        onSuccess = { f ->
                            val v = com.example.dgg.data.ota.OtaVerifier.verify(
                                service, f, com.example.dgg.data.ota.OtaVerifier.currentVersionCode(service)
                            )
                            val verdict = when (v) {
                                is com.example.dgg.data.ota.VerifyResult.Ok ->
                                    "校验通过 ${v.versionName}(${v.versionCode})"
                                is com.example.dgg.data.ota.VerifyResult.Rejected -> "校验拒绝：${v.reason}"
                            }
                            LogStore.i(TAG, "OTADL 完成：${f.length()} 字节，$verdict")
                        },
                        onFailure = { LogStore.e(TAG, "OTADL 失败：${it.message}") }
                    )
                }
            }

            cmd.startsWith("OTASTART ") -> {
                // OTASTART <versionCode> <versionName> <url> <sha256>
                // 走**生产链路**：OtaService 前台服务下载 + 通知进度。用来验证 P3，
                // 不需要授权、也不需要点界面（「升级」入口要授权后才出现）。
                val args = payload.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val code = args.getOrNull(0)?.toIntOrNull() ?: 0
                if (service == null || args.size < 4 || code <= 0) {
                    LogStore.w(TAG, "OTASTART 用法：OTASTART <versionCode> <versionName> <url> <sha256>")
                    return
                }
                val manifest = com.example.dgg.data.ota.OtaManifest(
                    versionCode = code,
                    versionName = args[1],
                    url = args[2],
                    sha256 = args[3]
                )
                com.example.dgg.service.OtaService.start(service, manifest)
                LogStore.i(TAG, "OTASTART：已请求 OtaService 下载 ${manifest.versionName}($code)")
            }

            else -> LogStore.w(TAG, "未知命令：$cmd")
        }
    }

    /**
     * `WATCH <秒>`：评论区采样时间线。
     *
     * 为什么必须是它：直播间画面持续动画，`uiautomator dump` 等不到 idle，
     * 而「列表到底有没有前进」正是本次要判定的事实。这里每 [WATCH_STEP_MS]
     * 用快照采一次「所有命中 live_comment_line / live_comment_nickname 的可见节点」，
     * 按 bottom 从大到小输出，并标出：
     *
     * - `over=Y`  —— 该行压到了输入栏上方（`collectCandidates` 会把它过滤掉，
     *   但旧版 `newestRow` 没有——这正是「锚点被钉死」的嫌疑人）；
     * - `nick=`   —— 按冒号切出来的昵称，`-` 表示这压根不是一行评论。
     *
     * 整个过程只读，不点、不滑、不按返回。输出 `files/dump/watched.txt`。
     */
    private fun watchComments(service: AccessibilityService, dumpDir: File, totalSec: Int) {
        val actions = Actions(service, Humanizer(0))
        val config = LocatorConfigLoader.load(service, forceReload = true)
        val lineTarget = config.target("live_comment_line")
        val nickTarget = config.target("live_comment_nickname")
        val inputTarget = config.target("comment_input")
        if (lineTarget == null && nickTarget == null) {
            LogStore.e(TAG, "WATCH 失败：配置里没有 live_comment_line / live_comment_nickname")
            return
        }

        val outFile = File(dumpDir, "watched.txt")
        val sb = StringBuilder(1 shl 14)
        val samples = ((totalSec * 1_000L) / WATCH_STEP_MS).toInt().coerceIn(1, 200)
        val startAt = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())

        val job = object : Runnable {
            var k = 0
            override fun run() {
                runCatching {
                    val facts = actions.snapshot(ttlMs = 0)
                    // 输入栏顶 = 评论行可点的下边界（与 FollowFromLiveFlow.rowBottomLimit 同口径）
                    val limit = inputTarget?.let { t ->
                        facts.filter { actions.matchesFacts(it, t) && it.top > 0 }
                            .map { it.top }
                            .minOrNull()
                    } ?: -1
                    val rows = facts.filter { f ->
                        (lineTarget != null && actions.matchesFacts(f, lineTarget)) ||
                            (nickTarget != null && actions.matchesFacts(f, nickTarget))
                    }.sortedByDescending { it.bottom }

                    val elapsed = (SystemClock.uptimeMillis() - startAt) / 1_000
                    sb.append('#').append(k).append(" t=").append(elapsed).append("s")
                        .append(" nodes=").append(facts.size)
                        .append(" limit=").append(limit)
                        .append(" rows=").append(rows.size).append('\n')
                    rows.take(WATCH_MAX_ROWS).forEach { f ->
                        val label = (f.text ?: f.desc)?.replace('\n', ' ') ?: ""
                        sb.append("   [").append(f.top).append(',').append(f.bottom).append("] h=")
                            .append(f.bottom - f.top)
                            .append(if (limit > 0 && f.bottom > limit) " over=Y" else " over=n")
                            .append(" nick=").append((Texts.beforeColon(label) ?: "-").take(14))
                            .append(" | ").append(label.take(30)).append('\n')
                    }
                    outFile.writeText(sb.toString())
                    LogStore.d(
                        TAG,
                        "WATCH #$k rows=${rows.size} bottom=${rows.firstOrNull()?.bottom} " +
                            "top=${rows.firstOrNull()?.top}"
                    )
                }.onFailure { LogStore.w(TAG, "WATCH 采样异常：${it.message}") }
                k++
                if (k < samples) {
                    handler.postDelayed(this, WATCH_STEP_MS)
                } else {
                    LogStore.i(TAG, "WATCH 完成：$samples 次采样，输出 files/dump/watched.txt")
                }
            }
        }
        LogStore.i(TAG, "WATCH 开始：${totalSec}s，每 ${WATCH_STEP_MS}ms 一次（纯读取，不产生手势）")
        handler.postDelayed(job, 300L)
    }
}
