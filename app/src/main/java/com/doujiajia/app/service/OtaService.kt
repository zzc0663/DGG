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

        // 完成通知单独一条通道，**必须 HIGH**。
        //
        // 之前「下载完成」也走上面那条 LOW：LOW 通道既不出横幅也没有提示音，
        // 通知只静静地躺在通知栏里，用户在抖音里根本不会知道包已经下好了 ——
        // 表现就是「下载完什么反应都没有」。HIGH 才会弹横幅 / 在状态栏浮出。
        // 通道的重要性建好之后改不了，所以不能复用旧通道，只能新开一个 id。
        val doneChannel = NotificationChannel(
            CHANNEL_DONE_ID,
            "更新就绪",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "新版本安装包下载完成，等待安装"
            setShowBadge(true)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(doneChannel)
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
        val appName = getString(R.string.app_name)
        val notification = if (success) {
            // 点通知本体或「安装」按钮都直达安装：多一步「先打开 App 再找那个小字」，
            // 用户就会一直挂着旧版本（实测 vc41 之前有人装了 3 天没升级）。
            val install = installIntent(target)
            NotificationCompat.Builder(this, CHANNEL_DONE_ID)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentTitle("更新已下载完成：${target.versionName}")
                .setContentText("点「安装」立即升级（会重启$appName）")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "安装包已下载并通过校验（${formatBytes(target.size)}）。" +
                            "点下面的「安装」直接打开系统安装器；也可以稍后从$appName 顶部的版本号进入。"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(install)
                .addAction(0, "安装", install)
                .addAction(0, "稍后", openAppIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setShowWhen(true)
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

    /**
     * 「点通知就装」。
     *
     * 不在服务里直接 `OtaInstaller.install()`：那会绕过 Activity 的三项前置判断
     * （任务是否在跑、安装未知应用是否已授权、弹窗该不该弹），用户在跑任务时会被
     * 一个"请先停止任务"的 toast 打断却不知道发生了什么。回到 Activity 走统一入口。
     */
    private fun installIntent(target: OtaManifest): PendingIntent = PendingIntent.getActivity(
        this,
        REQ_INSTALL,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_INSTALL_NOW, target.versionCode)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
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

        /** 完成通知专用通道（IMPORTANCE_HIGH）。与进度通道分开是因为重要性建好之后改不了 */
        private const val CHANNEL_DONE_ID = "dgg_ota_ready"

        private const val NOTIFICATION_ID = 1002
        private const val REQ_OPEN = 200
        private const val REQ_CANCEL = 201
        private const val REQ_INSTALL = 202
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
