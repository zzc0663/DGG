package com.example.dgg.data.log

import android.content.Context
import com.example.dgg.data.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 上报状态。界面只读它，不自己维护"上传中"标志。 */
sealed class LogUploadState {
    object Idle : LogUploadState()
    data class Uploading(val size: Int) : LogUploadState()
    data class Done(val receipt: LogUploadReceipt) : LogUploadState()
    data class Failed(val message: String) : LogUploadState()
}

/**
 * 日志上报编排：采集 → 打请求 → 交凭据。
 *
 * 职责刻意收窄，与 `OtaManager` 同构：不碰界面、不管按钮文案，只有一个状态源。
 * 用 [Mutex] 串行化，避免用户连点两次把同一份日志推两遍。
 */
object LogUploader {

    private const val TAG = "日志上报"

    private val _state = MutableStateFlow<LogUploadState>(LogUploadState.Idle)
    val state: StateFlow<LogUploadState> = _state.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var provider: LogUploadProvider? = null

    /**
     * 替换上报通道。服务端就绪后传 [RemoteLogUploadProvider]；传 null 回到本地占位。
     */
    fun installProvider(newProvider: LogUploadProvider?) {
        provider = newProvider
        LogStore.i(TAG, "上报通道已切换为 ${newProvider?.javaClass?.simpleName ?: "LocalLogUploadProvider(默认)"}")
    }

    private fun providerOf(context: Context): LogUploadProvider =
        provider ?: LocalLogUploadProvider(context.applicationContext).also { provider = it }

    suspend fun upload(context: Context): Result<LogUploadReceipt> = lock.withLock {
        val current = _state.value
        if (current is LogUploadState.Uploading) {
            return Result.failure(IllegalStateException("正在上传，请稍候"))
        }

        val report = runCatching { LogCollector.collect(context) }.getOrElse { error ->
            _state.value = LogUploadState.Failed(error.message ?: "读取日志失败")
            return Result.failure(error)
        }
        if (report.content.isBlank()) {
            _state.value = LogUploadState.Failed("没有可上传的日志")
            LogStore.w(TAG, "日志内容为空，取消上传")
            return Result.failure(IllegalStateException("没有可上传的日志"))
        }

        _state.value = LogUploadState.Uploading(report.size)
        LogStore.i(
            TAG,
            "开始上传日志：来源=${report.source} 大小=${report.size}B 截断=${report.truncated}"
        )

        val request = LogUploadRequest.from(context, report)
        val result = providerOf(context).upload(request)
        result.fold(
            onSuccess = { receipt ->
                _state.value = LogUploadState.Done(receipt)
                LogStore.i(TAG, "日志已上传：编号 ${receipt.reportId}")
            },
            onFailure = { error ->
                _state.value = LogUploadState.Failed(error.message ?: "上传失败")
                LogStore.w(TAG, "日志上传失败：${error.message}")
            }
        )
        result
    }

    /** 用户看到失败后点"重试"时用，避免界面拿 Failed 状态当"正在上传" */
    fun reset() {
        _state.value = LogUploadState.Idle
    }
}
