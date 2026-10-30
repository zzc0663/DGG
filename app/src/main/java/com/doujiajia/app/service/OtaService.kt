package com.doujiajia.app.service

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
import com.doujiajia.app.R
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.data.ota.OtaManager
import com.doujiajia.app.data.ota.OtaManifest
import com.doujiajia.app.data.ota.OtaState
import com.doujiajia.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 更新包下载的前台服务。
 *
 * 为什么下载必须搬进服务：之前是 `MainActivity.uiScope` 里跑的协程——切到后台被
 * HyperOS 冻结、Activity 重建/回收即取消，用户看到的是「下载到 40% 就没了」。
 * 而下载恰恰是**用户最会切走**的那个环节（十几 MB、几十秒）。
 *
 * FGS type 用 `dataSync`：这是唯一与「在后台传输数据」对得上的类型，
 * 需要 `FOREGROUND_SERVICE_DATA_SYNC` 权限（API 34+ 强制）；6 小时系统上限远大于本场景。
 *
 * 边界刻意收窄：**只负责把包装下来并存好**，不安装。安装永远由用户在系统安装器里点确认，
 * 而且任务运行中会被 [com.doujiajia.app.data.ota.OtaInstaller] 直接拒绝（安装会杀进程）。
 */
class OtaService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var downloadJob: Job? = null
    private var progressJob: Job? = null

    /** 上一次进度的整数百分比，用来抑制重复 notify（下载回调 200ms 一次，通知不必跟着抖） */
    private var lastPercent = -1

    @Volatile
    private var manifest: OtaManifest? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            LogStore.i(TAG, "用户取消下载")
            cancelDownload("已取消下载")
            return START_NOT_STICKY
        }

        val parsed = intent?.getStringExtra(EXTRA_MANIFEST)?.let { raw ->
            runCatching { OtaManifest.parse(JSONObject(raw)) }.getOrNull()
        }
        if (parsed == null) {
            LogStore.w(TAG, "缺少更新信息，服务退出")
            stopSelf()
            return START_NOT_STICKY
        }
        manifest = parsed

        // 必须先在 5s 内进前台，否则系统直接抛 ForegroundServiceDidNotStartInTimeException
        enterForeground(parsed)

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val result = OtaManager.downloadAndVerify(this@OtaService, parsed)
            result.fold(
                onSuccess = {
                    postFinished(parsed, success = true, detail = null)
                    finish()
                },
                onFailure = { error ->
                    postFinished(parsed, success = false, detail = error.message)
                    finish()
                }
            )
        }
        observeProgress(parsed)

        // 进程被回收后不自动重来：重新下是个"要不要花流量"的决定，交给用户重新点一次
        return START_NOT_STICKY
    }

    // ==================== 前台化 / 通知 ====================

    private fun enterForeground(target: OtaManifest) {
        val notification = buildProgress(target, 0, target.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        LogStore.i(TAG, "开始下载 ${target.versionName}(${target.versionCode})，${formatBytes(target.size)}")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "更新下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "下载新版本安装包时的进度提示"
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildProgress(target: OtaManifest, done: Long, total: Long): Notification {
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val text = if (total > 0) {
            "${formatBytes(done)} / ${formatBytes(total)}"
        } else {
            formatBytes(done)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("正在下载 ${target.versionName}")
            .setContentText(text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "取消下载", cancelIntent())
            .build()
    }

    /** 盯 `OtaManager.state` 而不是给下载器加回调：状态本来就只有一个来源，别再开第二条通道 */
    private fun observeProgress(target: OtaManifest) {
        if (progressJob != null) return
        progressJob = scope.launch {
            OtaManager.state.collect { state ->
                if (state !is OtaState.Downloading) return@collect
                val percent = if (state.total > 0) {
                    ((state.downloaded * 100) / state.total).toInt().coerceIn(0, 100)
                } else {
                    -1
                }
                if (percent == lastPercent) return@collect
                lastPercent = percent
                runCatching {
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildProgress(target, state.downloaded, state.total)
                    )
                }.onFailure { LogStore.w(TAG, "通知更新失败：${it.message}") }
            }
        }
    }

    private fun postFinished(target: OtaManifest, success: Boolean, detail: String?) {
        val notification = if (success) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentTitle("更新已就绪：${target.versionName}")
                .setContentText("点开${getString(R.string.app_name)}，点右上角「安装」完成升级")
                .setStyle(NotificationCompat.BigTextStyle().bigText("安装包已下载并通过校验，点开${getString(R.string.app_name)}后点「安装」完成升级。"))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentTitle("更新下载失败")
                .setContentText(detail ?: "请稍后重试")
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail ?: "请稍后重试"))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setShowWhen(false)
                .build()
        }
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onFailure { LogStore.w(TAG, "完成通知失败：${it.message}") }
        LogStore.i(TAG, if (success) "下载完成：${target.versionName}" else "下载失败：$detail")
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQ_OPEN,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQ_CANCEL,
        Intent(this, OtaService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ==================== 收尾 ====================

    /**
     * 结束服务但**保留通知**（STOP_FOREGROUND_DETACH）：下载完成/失败的结果要留在通知栏里，
     * 用户很可能此刻正在别的应用，撤掉就等于什么都没发生。取消下载则直接撤掉。
     */
    private fun finish(keepNotification: Boolean = true) {
        progressJob?.cancel()
        progressJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(!keepNotification)
        }
        stopSelf()
    }

    private fun cancelDownload(reason: String) {
        downloadJob?.cancel()
        downloadJob = null
        // 半截文件刻意保留：下次点更新会从 .part 处续传，不白花流量
        LogStore.i(TAG, "$reason（已下载部分保留，下次续传）")
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
        finish(keepNotification = false)
    }

    override fun onDestroy() {
        progressJob?.cancel()
        progressJob = null
        downloadJob?.cancel()
        downloadJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    companion object {
        private const val TAG = "OTA服务"
        private const val CHANNEL_ID = "dgg_ota"
        private const val NOTIFICATION_ID = 1002
        private const val REQ_OPEN = 200
        private const val REQ_CANCEL = 201
        private const val EXTRA_MANIFEST = "manifest"

        const val ACTION_CANCEL = "com.doujiajia.app.action.OTA_CANCEL"

        fun start(context: Context, manifest: OtaManifest) {
            val intent = Intent(context, OtaService::class.java)
                .putExtra(EXTRA_MANIFEST, manifest.toJson().toString())
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { LogStore.w(TAG, "启动下载服务失败：${it.message}") }
        }
    }
}
