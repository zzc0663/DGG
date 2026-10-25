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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮窗：任务跑起来之后，用户眼睛在抖音上，必须有一个「随时能看到在干什么、
 * 随时能停」的入口——通知栏要点两下才看得到，悬浮窗是零成本可见。
 *
 * 两个实现约束：
 *
 * 1. **普通 Service 而不是前台服务**。它和 [ControlService] 在同一个进程里，
 *    进程被前台服务钉住了，本服务实例就不会被单独回收；再开一个前台服务只为了
 *    多一条通知，反而干扰用户。
 *
 * 2. **没有悬浮窗权限就直接退出**，绝不尝试「用别的窗口类型绕过」。
 *    `TYPE_APPLICATION_OVERLAY` 在 API 26+ 是唯一合法途径，`TYPE_PHONE` 之类
 *    在新系统上会被直接拒绝。
 */
class FloatWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var binding: ViewFloatStatusBinding? = null
    private var params: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** 只在见到过活动态之后才允许因终态退出，避免收到初始 IDLE 就自杀 */
    @Volatile
    private var sawActive = false

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

        b.floatStop.setOnClickListener {
            LogStore.i(TAG, "悬浮窗「停止」被点击")
            ControlService.stop(this, "悬浮窗")
            stopSelf()
        }
        b.floatRoot.setOnTouchListener(DragListener())

        val added = runCatching { windowManager.addView(b.root, lp) }
            .onFailure { LogStore.e(TAG, "悬浮窗创建失败：${it.message}") }
            .isSuccess
        if (!added) {
            stopSelf()
            return
        }

        binding = b
        params = lp
        LogStore.i(TAG, "悬浮窗已创建 @ (${lp.x}, ${lp.y})")
    }

    /**
     * 拖动。手指位移超过 4dp 才判定为拖动，否则交给子控件（停止键）处理点击，
     * 避免「想点停止结果整颗胶囊被拖走了」。
     */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
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
                        runCatching { windowManager.updateViewLayout(v, lp) }
                            .onFailure { LogStore.w(TAG, "悬浮窗拖动失败：${it.message}") }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return dragging
            }
            return false
        }
    }

    // ==================== 状态跟随 ====================

    private fun observe() {
        if (job != null) return
        job = scope.launch {
            TaskEngine.progress.collect { progress ->
                if (progress.state.isActive) sawActive = true
                render(progress)
                if (sawActive && !progress.state.isActive) {
                    LogStore.i(TAG, "任务已到终态（${progress.state.label}），撤掉悬浮窗")
                    stopSelf()
                }
            }
        }
    }

    private fun render(progress: EngineProgress) {
        val b = binding ?: return
        b.floatStatus.text = shortStatus(progress)
        b.floatDot.setBackgroundResource(
            when (progress.state) {
                EngineState.RUNNING -> R.drawable.bg_dot_green
                EngineState.PAUSED -> R.drawable.bg_dot_amber
                else -> R.drawable.bg_dot_red
            }
        )
    }

    /** 胶囊很窄，只放最关键的三段：状态、进度、计数 */
    private fun shortStatus(progress: EngineProgress): String = buildString {
        append(progress.state.label)
        if (progress.target > 0) append(" ${progress.followed}/${progress.target}")
        if (progress.state == EngineState.RUNNING && progress.stepTotal > 0) {
            append(" · ${progress.stepIndex}/${progress.stepTotal}")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        job?.cancel()
        job = null
        scope.cancel()
        binding?.let { b -> runCatching { windowManager.removeView(b.root) } }
        binding = null
        params = null
        LogStore.i(TAG, "悬浮窗已移除")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "悬浮窗"

        fun start(context: Context) {
            val intent = Intent(context, FloatWindowService::class.java)
            runCatching { context.startService(intent) }
                .onFailure { LogStore.w(TAG, "悬浮窗启动失败：${it.message}") }
        }
    }
}
