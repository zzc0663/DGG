package com.example.dgg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.dgg.R
import com.example.dgg.data.LogStore
import com.example.dgg.engine.EngineProgress
import com.example.dgg.engine.TaskEngine
import com.example.dgg.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：常驻通知 + 进程保活 + 停止入口。
 *
 * 为什么必须有它：任务跑起来之后本应用退到后台，只靠 Activity 存活的话，
 * 系统随时可以把进程回收，抖音还开着、脚本已经死了——这种「静默失效」是最难排查的故障。
 *
 * FGS type 选 `specialUse`：本服务不属于 media / location / dataSync 任何一类，
 * 硬套会被 Play 判违规；`specialUse` 需要在 Manifest 里用 `<property>` 声明子类型。
 *
 * 生命周期约定：**任务进入终态（停止/风控/异常）时本服务立即自行退出**，
 * 不留一个「什么都没有在跑」的常驻通知骗用户说还在工作。
 */
class ControlService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressJob: Job? = null

    /** 收到过至少一次活动态，才允许因终态而退出——避免收集到初始 IDLE 就立刻自杀 */
    @Volatile
    private var sawActive = false

    @Volatile
    private var lastText: String? = null

    private val closing = AtomicBoolean(false)

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LogStore.i(TAG, "收到停止指令（来源：${intent.getStringExtra(EXTRA_SOURCE) ?: "外部"}）")
                TaskEngine.stop("通知栏停止")
                shutdown("已按停止键终止")
                return START_NOT_STICKY
            }

            else -> {
                enterForeground()
                observeProgress()
            }
        }
        return START_NOT_STICKY
    }

    // ==================== 前台化 ====================

    private fun enterForeground() {
        val notification = buildNotification(TaskEngine.progress.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        LogStore.i(TAG, "前台服务已启动（常驻通知 + 进程保活）")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: EngineProgress): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, ControlService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SOURCE, "通知栏"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("抖关关 · ${progress.state.label}")
            .setContentText(progress.brief())
            .setStyle(NotificationCompat.BigTextStyle().bigText(progress.brief()))
            .setContentIntent(openApp)
            .setOngoing(progress.state.isActive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .build()
    }

    // ==================== 状态跟随 ====================

    private fun observeProgress() {
        if (progressJob != null) return
        progressJob = scope.launch {
            TaskEngine.progress.collect { onProgress(it) }
        }
        // 看门狗：极端情况下（引擎在服务订阅之前就结束、或引擎压根没起来），
        // 收集器只看到终态、sawActive 永远是 false，服务会变成一条挂着不动的常驻通知。
        // 2 秒后仍未见活动态就直接回收，宁可少一条通知，也不能骗用户说还在跑。
        scope.launch {
            delay(WATCHDOG_MS)
            if (!sawActive && !TaskEngine.isRunning) {
                shutdown("引擎未在 ${WATCHDOG_MS}ms 内启动，回收前台服务")
            }
        }
    }

    private fun onProgress(progress: EngineProgress) {
        if (closing.get()) return

        if (progress.state.isActive) sawActive = true

        val text = progress.brief()
        if (text != lastText) {
            lastText = text
            // 通知可见性取决于 POST_NOTIFICATIONS，未授权时 notify 会被静默丢弃，这里只记录一次
            runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification(progress)) }
                .onFailure { LogStore.w(TAG, "通知更新失败：${it.message}") }
        }

        if (sawActive && !progress.state.isActive) {
            shutdown("任务进入终态：${progress.state.label}")
        }
    }

    // ==================== 收尾 ====================

    private fun shutdown(reason: String) {
        if (!closing.compareAndSet(false, true)) return
        progressJob?.cancel()
        progressJob = null

        // 刻意**不撤悬浮窗**：任务结束后最需要看到的就是「为什么停了」。
        // 早期实现在这里 stopService(FloatWindowService)，结果用户点「结束」后小窗直接消失，
        // 既看不到结论，观感上还像是被踢回了应用。现在由悬浮窗自己保留终态面板，
        // 用户点「关闭悬浮窗」才撤（见 FloatWindowService.onFinish）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        LogStore.i(TAG, "前台服务已退出：$reason（悬浮窗保留终态面板）")
    }

    override fun onDestroy() {
        progressJob?.cancel()
        progressJob = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "前台服务"
        private const val CHANNEL_ID = "dgg_run"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_OPEN = 100
        private const val REQ_STOP = 101
        private const val WATCHDOG_MS = 2_000L

        const val ACTION_STOP = "com.example.dgg.action.STOP"
        const val EXTRA_SOURCE = "source"

        fun start(context: Context) {
            val intent = Intent(context, ControlService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, source: String = "界面") {
            val intent = Intent(context, ControlService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_SOURCE, source)
            runCatching { context.startService(intent) }
                .onFailure { LogStore.w(TAG, "停止前台服务失败：${it.message}") }
        }
    }
}
