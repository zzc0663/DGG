package com.doujiajia.app.data.log

import android.content.Context
import com.doujiajia.app.data.LogStore
import java.io.File
import java.io.RandomAccessFile

/** 待上传的日志正文。 */
data class LogReport(
    /** UTF-8 文本，按时间正序 */
    val content: String,
    val size: Int,
    /** 是否因为超过上限被截断（只保留最近的一段） */
    val truncated: Boolean,
    /** 来源：file（落盘日志）/ memory（内存环形缓冲） */
    val source: String
)

/**
 * 日志采集：优先取落盘文件的**尾部**，落盘不可用才退回内存缓冲。
 *
 * 为什么取尾部而不是全量：出问题时真正有用的是最近几分钟（开机流程、几次点击之后发生了什么），
 * 而且日志文件上限 2MB，全量上传既不礼貌也没必要。默认只传最后 512KB。
 *
 * 为什么还要内存兜底：release 包在数据库目录不可写、或文件刚被滚动清空时，
 * `runtime.log` 可能不存在；内存环形缓冲（2000 条）好歹能给出一份现场。
 */
object LogCollector {

    /** 单次上报正文上限。超过就截断，并在请求里带 `truncated=true` */
    const val MAX_BYTES = 512 * 1024

    private const val FILE_NAME = "runtime.log"

    fun collect(context: Context, maxBytes: Int = MAX_BYTES): LogReport {
        val file = File(context.filesDir, FILE_NAME)
        if (file.isFile && file.length() > 0L) {
            val text = readTail(file, maxBytes.toLong())
            val bytes = text.toByteArray(Charsets.UTF_8)
            return LogReport(
                content = text,
                size = bytes.size,
                truncated = file.length() > bytes.size,
                source = "file"
            )
        }
        val text = LogStore.exportText()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val truncated = bytes.size > maxBytes
        val body = if (truncated) {
            // 内存版没有"尾部"概念，直接按字符近似截尾部（2000 条 × 平均百字，远小于上限）
            text.takeLast(maxBytes / 2)
        } else {
            text
        }
        return LogReport(
            content = body,
            size = body.toByteArray(Charsets.UTF_8).size,
            truncated = truncated,
            source = "memory"
        )
    }

    /**
     * 读文件最后 [maxBytes] 字节，并**丢掉可能被截断的第一行**。
     *
     * 不丢的话，上报内容的第一行会是个半截时间戳，客服/开发看到会以为日志本身坏了。
     */
    private fun readTail(file: File, maxBytes: Long): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt())
            raf.readFully(buffer)
            val text = String(buffer, Charsets.UTF_8)
            if (start <= 0L) return text
            val firstBreak = text.indexOf('\n')
            return if (firstBreak in 0 until text.length - 1) text.substring(firstBreak + 1) else text
        }
    }
}
