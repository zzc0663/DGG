package com.example.dgg.data.log

import android.content.Context
import com.example.dgg.data.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 离线占位实现：把请求体原样落到 `files/last_log_upload.json`，返回一个本地编号。
 *
 * 用途只有一个 —— **在服务端就绪前把"点一下上传"这条链路跑通**（含失败文案、
 * 编号回显、重复点击拦截），不然这部分代码要等云端才能第一次运行，风险太大。
 *
 * 它不假装成功：编号带 `LOCAL-` 前缀，日志里也写明"未真正上传"，
 * 免得以后有人把占位当线上行为。
 */
class LocalLogUploadProvider(private val context: Context) : LogUploadProvider {

    override suspend fun upload(request: LogUploadRequest): Result<LogUploadReceipt> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(request.toJson().toString())
                val reportId = "LOCAL-${System.currentTimeMillis().toString(36).uppercase()}"
                LogStore.w(
                    TAG,
                    "本地占位：日志未真正上传，仅落盘 ${file.name}（${request.content.length} 字符），" +
                        "编号 $reportId。服务端就绪后请换成 RemoteLogUploadProvider"
                )
                LogUploadReceipt(
                    reportId = reportId,
                    uploadedAt = System.currentTimeMillis(),
                    size = file.length()
                )
            }
        }

    companion object {
        private const val TAG = "日志上报"
        const val FILE_NAME = "last_log_upload.json"
    }
}
