package com.example.dgg.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.example.dgg.R
import com.example.dgg.data.FollowedStore
import com.example.dgg.data.LogStore
import com.example.dgg.databinding.ViewFloatStatusBinding
import com.example.dgg.engine.EngineProgress
import com.example.dgg.engine.EngineState
import com.example.dgg.engine.TaskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮窗：收起态是一条窄胶囊，展开态是控制面板。
 *
 * 为什么要展开面板：任务跑起来之后用户的注意力在抖音上。回抖关关看进度等于把主播画面切走，
 * 切回来还可能掉出直播间；通知栏要点两下才看得到、还遮着状态栏。所以「看进度 / 暂停 / 结束」
 * 这些高频操作必须就地完成——**点面板上的按钮不跳任何界面**。
 *
 * 两条与早期版本不同的约定：
 *
 * 1. **任务到终态时不撤悬浮窗**，而是自动展开面板显示结论（风控停机 / 异常原因都在 message 里）。
 *    早期实现里前台服务 shutdown 会顺手 stopService 本服务，结果用户点「结束」后悬浮窗直接消失，
 *    什么结论都看不到；更糟的是有人会以为「被踢回应用了」。现在只有用户点「关闭悬浮窗」才撤。
 *
 * 2. **普通 Service 而不是前台服务**。它和 [ControlService] 在同一个进程里，
 *    进程被前台服务钉住了，本服务实例就不会被单独回收；再开一个前台服务只为了多一条通知，
 *    反而干扰用户。
 *
 * 3. **没有悬浮窗权限就直接退出**，绝不尝试「用别的窗口类型绕过」。
 *    `TYPE_APPLICATION_OVERLAY` 在 API 26+ 是唯一合法途径，`TYPE_PHONE` 之类在新系统上会被直接拒绝。
 */
class FloatWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var binding: ViewFloatStatusBinding? = null
    private var params: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var idleJob: Job? = null

    /** 只在见到过活动态之后才允许因终态做收尾，避免收到初始 IDLE 就自杀 */
    @Volatile
    private var sawActive = false

    /** 面板是否展开。收起态只有一个胶囊，展开态才有按钮 */
    @Volatile
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            LogStore.w(TAG, "没有悬浮窗权限，不创建小窗（请先在「悬浮窗」一栏授权）")
            stopSelf()
            return START_NOT_STICKY
        }
        attach()
        observe()
        return START_NOT_STICKY
    }

    // ==================== 窗口 ====================

    private fun attach() {
        if (binding != null) return

        val b = ViewFloatStatusBinding.inflate(LayoutInflater.from(this))
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = (dm.heightPixels * 0.12f).toInt()
        }

        b.floatExpand.setOnClickListener { setExpanded(true) }
        b.floatCollapse.setOnClickListener { setExpanded(false) }
        b.floatToggle.setOnClickListener { onToggle() }
        b.floatFinish.setOnClickListener { onFinish() }
        b.floatClose.setOnClickListener {
            LogStore.i(TAG, "用户关闭悬浮窗")
            stopSelf()
        }
        b.floatReset.setOnClickListener { onResetToday() }

        // 拖动只挂在胶囊和面板标题上：挂在整块 root 会让面板里的按钮在滑动时误吞事件
        val drag = DragListener()
        b.floatPill.setOnTouchListener(drag)
        b.floatHeader.setOnTouchListener(drag)

        val added = runCatching { windowManager.addView(b.root, lp) }
            .onFailure { LogStore.e(TAG, "悬浮窗创建失败：${it.message}") }
            .isSuccess
        if (!added) {
            stopSelf()
            return
        }

        binding = b
        params = lp
        setExpanded(false, animate = false)
        render(TaskEngine.progress.value)
        LogStore.i(TAG, "悬浮窗已创建 @ (${lp.x}, ${lp.y})")
    }

    private fun setExpanded(value: Boolean, animate: Boolean = true) {
        val b = binding ?: return
        if (expanded == value && animate) return
        expanded = value
        b.floatPill.visibility = if (value) View.GONE else View.VISIBLE
        b.floatPanel.visibility = if (value) View.VISIBLE else View.GONE
        // 展开时面板自带背景，root 的胶囊底色要撤掉，否则圆角会漏出一圈
        b.floatRoot.setBackgroundResource(if (value) 0 else R.drawable.bg_float_pill)
        if (animate) LogStore.d(TAG, if (value) "悬浮面板已展开" else "悬浮面板已收起")
    }

    /**
     * 拖动。手指位移超过 4dp 才判定为拖动，否则交给子控件处理点击，
     * 避免「想点按钮结果整块被拖走了」。
     */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            val root = binding?.root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(root, lp) }
                            .onFailure { LogStore.w(TAG, "悬浮窗拖动失败：${it.message}") }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return dragging
            }
            return false
        }
    }

    // ==================== 按钮 ====================

    private fun onToggle() {
        when (TaskEngine.progress.value.state) {
            EngineState.RUNNING -> {
                LogStore.i(TAG, "悬浮窗「暂停」被点击")
                TaskEngine.pause()
            }

            EngineState.PAUSED -> {
                LogStore.i(TAG, "悬浮窗「继续」被点击")
                TaskEngine.resume()
            }

            else -> LogStore.d(TAG, "当前无运行任务，忽略暂停/继续")
        }
    }

    /**
     * 「结束」只停引擎，**不撤悬浮窗、不拉起任何界面**。
     *
     * 早期实现是 `ControlService.stop()` + `stopSelf()`：悬浮窗立刻消失，
     * 用户既看不到「为什么停的」，也会因为画面突然变化而以为被踢回应用了。
     * 现在终态后面板自动展开显示结论，由用户自己点「关闭悬浮窗」撤掉。
     */
    private fun onFinish() {
        val state = TaskEngine.progress.value.state
        if (state.isActive) {
            LogStore.i(TAG, "悬浮窗「结束」被点击（当前 ${state.label}）")
            TaskEngine.stop("悬浮窗结束")
        } else {
            LogStore.i(TAG, "任务已是终态（${state.label}），关闭悬浮窗")
            stopSelf()
        }
    }

    private fun onResetToday() {
        val store = FollowedStore.get(this)
        store.resetToday()
        LogStore.i(TAG, "今日累计已手动重置（名单不清空，仍是 ${store.knownCount()} 人）")
        render(TaskEngine.progress.value)
    }

    // ==================== 状态跟随 ====================

    private fun observe() {
        if (job != null) return
        job = scope.launch {
            var lastState = EngineState.IDLE
            TaskEngine.progress.collect { progress ->
                if (progress.state.isActive) sawActive = true

                // 新的一轮任务开始时把面板收回去：终态那一轮是展开着给人看结论的，
                // 不收起来会一直挡住抖音画面。
                if (progress.state.isActive && !lastState.isActive && expanded) setExpanded(false)
                lastState = progress.state

                render(progress)

                if (sawActive && !progress.state.isActive) {
                    // 终态：保留悬浮窗并把结论摊开给人看，绝不自动消失
                    setExpanded(true)
                    LogStore.i(TAG, "任务已到终态（${progress.state.label}），面板保留待用户关闭")
                }
            }
        }
        // 兜底：引擎压根没起来（前台服务 watchdog 会回收），别留一个永远「准备中」的空胶囊
        idleJob = scope.launch {
            delay(IDLE_SELF_CLOSE_MS)
            if (!sawActive && !TaskEngine.isRunning) {
                LogStore.i(TAG, "任务从未进入活动态，自动收起悬浮窗")
                stopSelf()
            }
        }
    }

    private fun render(progress: EngineProgress) {
        val b = binding ?: return

        val dot = when (progress.state) {
            EngineState.RUNNING -> R.drawable.bg_dot_green
            EngineState.PAUSED -> R.drawable.bg_dot_amber
            else -> R.drawable.bg_dot_red
        }
        b.floatDot.setBackgroundResource(dot)
        b.floatDotPanel.setBackgroundResource(dot)

        b.floatStatus.text = shortStatus(progress)
        b.floatState.text = progress.state.label

        val label = progress.counterLabel.ifBlank { "已关注" }
        b.floatCounterLabel.text = label
        b.floatCounter.text = when {
            progress.target > 0 -> "${progress.followed}/${progress.target}"
            progress.followed > 0 -> "${progress.followed}"
            else -> "—"
        }
        b.floatMessage.text = progress.message.ifBlank {
            if (progress.state.isActive) "" else "任务已结束"
        }

        val today = FollowedStore.get(this).todayCount()
        b.floatToday.text = "今日累计 $today"

        val active = progress.state.isActive
        b.floatToggle.visibility = if (active) View.VISIBLE else View.GONE
        b.floatToggle.text = if (progress.state == EngineState.PAUSED) "继续" else "暂停"
        b.floatFinish.text = if (active) "结束" else "关闭"
    }

    /** 胶囊很窄，只放最关键的三段：状态、进度 */
    private fun shortStatus(progress: EngineProgress): String = buildString {
        append(progress.state.label)
        if (progress.target > 0) append(" ${progress.followed}/${progress.target}")
        else if (progress.followed > 0) append(" ${progress.followed}")
        if (progress.state == EngineState.RUNNING && progress.stepTotal > 0) {
            append(" · ${progress.stepIndex}/${progress.stepTotal}")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        job?.cancel()
        job = null
        idleJob?.cancel()
        idleJob = null
        scope.cancel()
        binding?.let { b -> runCatching { windowManager.removeView(b.root) } }
        binding = null
        params = null
        LogStore.i(TAG, "悬浮窗已移除")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "悬浮窗"

        /** 引擎从未启动时，悬浮窗最多空挂这么久 */
        private const val IDLE_SELF_CLOSE_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, FloatWindowService::class.java)
            runCatching { context.startService(intent) }
                .onFailure { LogStore.w(TAG, "悬浮窗启动失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatWindowService::class.java)) }
        }
    }
}
