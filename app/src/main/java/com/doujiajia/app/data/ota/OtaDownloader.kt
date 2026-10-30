package com.doujiajia.app.data.ota

import com.doujiajia.app.data.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 安装包下载器：`HttpURLConnection` + Range 断点续传 + 流式 sha256。
 *
 * 为什么不用 OkHttp：整个工程只有 4 个 implementation，为了一个 GET 引入一个网络栈
 * （以及它的混淆面、APK 体积、证书固定等额外话题）不划算。这里要的能力只有：
 * 超时、跟随重定向、Range、按块读。`HttpURLConnection` 全都能干。
 *
 * 为什么**不信 `Content-Length` / manifest 里的 size**：服务端/CDN 写错、被截断、
 * 缓存污染都会让这两个数不正确，唯一可信的判据是逐字节算出来的 sha256。
 * size 只用于进度条。
 */
class OtaDownloader(private val dir: File) {

    /**
     * 下载并校验。
     *
     * @param fileName 落盘文件名（不含目录），由调用方按版本号起名，避免不同版本互相覆盖
     * @param onProgress (已下载字节, 总字节)。总字节未知时为 -1
     * @return 成功返回**校验通过**的本地文件；失败时 `.part` 保留以便下次续传（sha 不符则删除）
     */
    suspend fun download(
        url: String,
        expectedSha256: String,
        fileName: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!OtaUrls.isAllowed(url)) {
            return@withContext Result.failure(IllegalArgumentException("下载地址不被允许：$url"))
        }
        val expected = expectedSha256.lowercase()
        dir.mkdirs()

        val target = File(dir, fileName)
        // 之前已经下好并校验过：直接复用，不重复消耗流量
        if (target.isFile && sha256Of(target) == expected) {
            LogStore.i(TAG, "已有校验通过的安装包，跳过下载：${target.name}")
            return@withContext Result.success(target)
        }

        var lastError = "下载失败"
        // sha 不符说明文件已损坏或被替换：删掉重下一次（不无限重试，避免把用户流量当燃料）
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = attemptDownload(url, expected, target, onProgress)
            if (result.isSuccess) return@withContext result
            lastError = result.exceptionOrNull()?.message ?: lastError
            LogStore.w(TAG, "第 ${attempt + 1}/$MAX_ATTEMPTS 次下载失败：$lastError")
        }
        Result.failure(IllegalStateException(lastError))
    }

    private suspend fun attemptDownload(
        url: String,
        expectedSha256: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): Result<File> {
        val part = File(target.parentFile, "${target.name}.part")
        var conn: HttpURLConnection? = null
        try {
            val existing = if (part.isFile) part.length() else 0L
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                // 续传：从已有长度之后开始要
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    // 服务端不支持 Range：从头来，把半截文件截断
                    if (existing > 0) {
                        LogStore.w(TAG, "服务端未接受 Range（HTTP 200），从头重新下载")
                        part.delete()
                    }
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    LogStore.i(TAG, "断点续传：从 ${existing} 字节继续")
                }
                else -> {
                    if (code == 416) {
                        // 客户端要的区间超出文件长度：说明 .part 已经比源文件还大，删掉重来
                        part.delete()
                    }
                    return Result.failure(IllegalStateException("HTTP $code"))
                }
            }

            val resumeFrom = if (part.isFile) part.length() else 0L
            val contentLength = conn.contentLengthLong
            val total = if (contentLength > 0) resumeFrom + contentLength else -1L

            var downloaded = resumeFrom
            var lastReportAt = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumeFrom > 0).use { output ->
                    while (true) {
                        coroutineContext.ensureActive() // 用户取消下载时能立刻退出
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= PROGRESS_INTERVAL_MS) {
                            lastReportAt = now
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(downloaded, total)

            val actual = sha256Of(part)
            if (actual != expectedSha256) {
                // 损坏或被替换：删掉，让上层重试；**绝不把校验不过的包交给安装器**
                LogStore.e(TAG, "sha256 不匹配，删除重下：期望 ${expectedSha256.take(16)}… 实际 ${actual.take(16)}…")
                part.delete()
                return Result.failure(IllegalStateException("sha256 校验不通过"))
            }

            if (target.exists() && !target.delete()) {
                return Result.failure(IllegalStateException("无法覆盖旧安装包：${target.name}"))
            }
            if (!part.renameTo(target)) {
                return Result.failure(IllegalStateException("重命名失败：${part.name}"))
            }
            LogStore.i(TAG, "下载完成并校验通过：${target.name}（${target.length()} 字节）")
            return Result.success(target)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "OTA"
        private const val MAX_ATTEMPTS = 2
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 16 * 1024
        private const val PROGRESS_INTERVAL_MS = 200L

        /** 流式算 sha256（安装包十几 MB，一次读进内存不划算） */
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
