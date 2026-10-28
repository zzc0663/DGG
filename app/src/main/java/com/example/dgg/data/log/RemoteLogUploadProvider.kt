package com.example.dgg.data.log

import com.example.dgg.BuildConfig
import com.example.dgg.data.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 正式日志上报：`POST {API_BASE_URL}/v1/log/upload`，请求体是 JSON，正文就在里面。
 *
 * 为什么用 POST + JSON 而不是 multipart 上传文件：正文最大 512KB，JSON 塞得下；
 * multipart 要引额外的解析分支（服务端）与边界处理（客户端），换来的只是几十 KB 的编码开销。
 * 真要传原始文件（比如以后加截图），再单独开一条接口。
 *
 * **不做重试**：日志上报是用户主动点的，失败立刻告诉用户"再点一次"比后台悄悄重试三次更诚实，
 * 也避免弱网下把 512KB 反复推上去。
 */
class RemoteLogUploadProvider : LogUploadProvider {

    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    override suspend fun upload(request: LogUploadRequest): Result<LogUploadReceipt> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("$base/v1/log/upload")
                val body = request.toJson().toString().toByteArray(Charsets.UTF_8)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setFixedLengthStreamingMode(body.size)
                }
                try {
                    conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code ${text.take(120)}")
                    }
                    parseReceipt(text)
                } finally {
                    conn.disconnect()
                }
            }.onFailure {
                LogStore.w(TAG, "日志上报失败：${it.javaClass.simpleName} / ${it.message}")
            }
        }

    /**
     * 解析受理凭据。
     *
     * 只有 `data.reportId` 是硬要求；`status` / `ticketUrl` / `expiresAt` / `size` 有就读、没有不报错
     * —— 服务端分阶段上线时不必等所有字段都齐。
     *
     * `code` 非 0 视为业务拒绝：服务端明确说"不收"时必须让用户看到原因，
     * 不能像 OTA 那样静默（这里是用户主动操作，静默等于骗人）。
     */
    private fun parseReceipt(text: String): LogUploadReceipt {
        val json = JSONObject(text)
        val code = json.optInt("code", 0)
        val message = json.optString("message").trim()
        if (code != 0) {
            throw IllegalStateException(message.ifEmpty { "服务端拒绝：code=$code" })
        }
        val data = json.optJSONObject("data")
            ?: json.optJSONObject("result")
            ?: throw IllegalStateException("响应缺少 data")
        val reportId = data.optString("reportId").trim()
        if (reportId.isEmpty()) throw IllegalStateException("响应缺少 reportId")
        val ticketUrl = data.optString("ticketUrl").trim().takeIf { it.startsWith("https://") }
        return LogUploadReceipt(
            reportId = reportId,
            uploadedAt = data.optLong("uploadedAt", 0L),
            expiresAt = data.optLong("expiresAt", 0L),
            size = data.optLong("size", 0L),
            status = data.optString("status").trim()
                .ifEmpty { LogUploadReceipt.STATUS_ACCEPTED },
            ticketUrl = ticketUrl
        )
    }

    companion object {
        private const val TAG = "日志上报"
    }
}
