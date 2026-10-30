package com.doujiajia.app.data.log

import android.content.Context
import com.doujiajia.app.data.license.DeviceIdentity
import org.json.JSONObject

/**
 * 一次日志上报的请求体。
 *
 * 字段与服务端契约 `docs/日志上报-接口契约.md` 一一对应，**不要私自加字段**：
 * 服务端只认契约里的键，客户端也只发这些，任何一方单方面扩展都不会生效。
 */
data class LogUploadRequest(
    val deviceId: String,
    val versionCode: Int,
    val versionName: String,
    val channel: String,
    /** UTF-8 正文，按时间正序 */
    val content: String,
    /** 正文是否被截断（只上传了最近一段） */
    val truncated: Boolean,
    val source: String,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app", "doujiajia")
        put("deviceId", deviceId)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("channel", channel)
        put("source", source)
        put("size", content.toByteArray(Charsets.UTF_8).size)
        put("truncated", truncated)
        put("createdAt", createdAt)
        put("content", content)
    }

    companion object {
        fun from(context: Context, report: LogReport, channel: String = "stable"): LogUploadRequest {
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val code = info?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    it.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode
                }
            } ?: 0
            return LogUploadRequest(
                deviceId = DeviceIdentity.deviceId(context),
                versionCode = code,
                versionName = info?.versionName ?: "",
                channel = channel,
                content = report.content,
                truncated = report.truncated,
                source = report.source,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}

/**
 * 服务端受理凭据。
 *
 * `reportId` 是**唯一必填**字段：用户报障时需要把它念给客服，客服据此在云端查日志。
 * 其余字段都是"有则更好"：`status` 让人知道是立即入库还是排队，`ticketUrl` 能一键跳到工单，
 * `expiresAt` 提醒用户日志不是永久留存。缺了不影响功能，所以解析时不强制。
 */
data class LogUploadReceipt(
    val reportId: String,
    val uploadedAt: Long = 0L,
    /** 云端保留截止时间（毫秒）；0 表示未告知 */
    val expiresAt: Long = 0L,
    /** 服务端实际入库的字节数；0 表示未告知 */
    val size: Long = 0L,
    /** 受理状态：accepted（已入库）/ queued（排队）/ filtered（被过滤，需说明原因） */
    val status: String = STATUS_ACCEPTED,
    /** 客服工单/查看链接（可选，https）。为空则不展示入口 */
    val ticketUrl: String? = null
) {
    companion object {
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_QUEUED = "queued"
        const val STATUS_FILTERED = "filtered"
    }
}

/**
 * 日志上报通道抽象。
 *
 * 与 `OtaProvider` / `LicenseProvider` 同一套范式：接口在客户端、实现可替换。
 * - [LocalLogUploadProvider]：离线占位，把请求体落到本地文件并返回假凭据（自测用）；
 * - [RemoteLogUploadProvider]：POST 到云端正式接口。
 *
 * 这样"点一下上传"这条链路在没有服务端时也能跑通、也能测失败分支。
 */
interface LogUploadProvider {
    suspend fun upload(request: LogUploadRequest): Result<LogUploadReceipt>
}
