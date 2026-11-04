package com.doujiajia.app.data

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关注记录：**时间 / 主播昵称 / 被关注人昵称**。
 *
 * ### 为什么不起用 SharedPreferences
 * 用户明确要求「**全部保留**」。按每天 200 人算，一年就是 7 万条 ≈ 4 MB；而 SP 每次写入
 * 都要把整份值重新序列化再写盘——每关注一个人就序列化 4 MB，主线程会肉眼可见地卡。
 * 这里改成**追加式文本文件**，写入是 O(1) 的 `appendText`。
 *
 * ### 为什么从文件尾倒着读
 * 文件只增不减，正序读一遍再取尾部会把整份文件都读进内存（而且每次翻页都要重读）。
 * [read] 按 64 KB 块**从尾部向前**扫，凑够这一页就停 ⇒ 首屏耗时与总条数无关，
 * 1 万条和 10 万条打开速度一样。
 *
 * ### 文件格式（tab 分隔，一行一条，永远以 `\n` 结尾）
 * ```
 * <epochMillis>\t<主播昵称|->\t<被关注人昵称>\t<kind>
 * ```
 * `kind` 预留：`F` = 真正关注成功（**本版只写这一种**）、`A` = 打开时已关注跳过、
 * `P` = 私密账号。留着是为了以后补记「跳过谁」时不用改文件格式（旧记录按 `F` 解析）。
 *
 * ### 与去重名单的关系
 * [FollowedStore.names] 仍有 3000 条上限（只是去重集合，超了先进先出淘汰）；
 * 而这里的记录**不淘汰**。所以「累计关注人数」用的是 [FollowedStore.totalFollowed] 这个
 * 独立计数器，不是名单大小——否则累计会在 3000 处凭空封顶。
 */
class FollowRecordStore private constructor(private val appContext: Context) {

    /** 一条关注记录。[anchor] 为 null = 当时读不到主播昵称（不在直播间/特征变了） */
    data class Record(
        val ts: Long,
        val anchor: String?,
        val nickname: String,
        val kind: Char = KIND_FOLLOW
    )

    /** 某主播在今天被关注了多少个（悬浮窗名单区用） */
    data class AnchorGroup(val anchor: String?, val count: Int)

    /**
     * **今天**的记录按主播聚合，最近活跃的在前。
     *
     * 实现要点：从文件尾部倒读，**一碰到非今天的记录就停**——不扫全文件。
     * 单日上限 200 条，200 一批最多翻 5 次（[MAX_GROUP_SCAN_PAGES]）就够，
     * 即使每日上限被调大也不会卡住名单展开。
     *
     * 只统计 [KIND_FOLLOW]：打开时就已关注、私密账号那些不算「已关注」。
     */
    @Synchronized
    fun todayGroups(): List<AnchorGroup> {
        val todayKey = dayFormat.format(Date())
        val groups = LinkedHashMap<String, Int>()
        var offset = 0
        var pages = 0
        while (pages++ < MAX_GROUP_SCAN_PAGES) {
            val page = read(offset, GROUP_PAGE)
            if (page.isEmpty()) break
            var reachedOlder = false
            for (r in page) {
                if (dayFormat.format(Date(r.ts)) != todayKey) {
                    reachedOlder = true
                    break
                }
                if (r.kind != KIND_FOLLOW) continue
                val key = r.anchor.orEmpty()
                groups[key] = (groups[key] ?: 0) + 1
            }
            if (reachedOlder) break
            offset += page.size
            if (page.size < GROUP_PAGE) break
        }
        return groups.map { AnchorGroup(it.key.ifEmpty { null }, it.value) }
    }

    /**
     * 追加一条记录。
     *
     * **永不抛异常**：写记录是「锦上添花」，它失败绝不能把关注主流程带崩
     * （关注已经成功了，记录丢了只是少一行明细）。
     */
    @Synchronized
    fun append(anchor: String?, nickname: String, kind: Char = KIND_FOLLOW) {
        if (nickname.isBlank()) return
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.appendText(
                buildString {
                    append(System.currentTimeMillis()); append('\t')
                    append(sanitize(anchor)); append('\t')
                    append(sanitize(nickname)); append('\t')
                    append(kind); append('\n')
                },
                Charsets.UTF_8
            )
        }.onFailure {
            LogStore.w(TAG, "关注记录写入失败（不影响关注结果）：${it.message}")
        }
    }

    /**
     * 分页读，**最新在最前**。
     *
     * @param offset 已经跳过的条数（0 = 从最新一条开始）
     * @param limit  本页最多返回多少条
     */
    fun read(offset: Int, limit: Int): List<Record> {
        if (limit <= 0 || offset < 0) return emptyList()
        return readTailLines(offset + limit)
            .drop(offset)
            .take(limit)
            .mapNotNull { parse(it) }
    }

    /** 文件里一共有多少条（坏行也算，因为它确实占了一行）。记录页顶部的「共 N 条」用它。 */
    @Synchronized
    fun countLines(): Int {
        val f = file()
        if (!f.exists()) return 0
        var count = 0
        runCatching {
            f.inputStream().buffered(BLOCK).use { ins ->
                val buf = ByteArray(BLOCK)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    for (i in 0 until n) if (buf[i] == NL) count++
                }
            }
        }.onFailure { LogStore.w(TAG, "关注记录行数统计失败：${it.message}") }
        return count
    }

    /** 清空全部记录（目前没有入口，留着给「彻底清除数据」用） */
    @Synchronized
    fun clear() {
        runCatching { file().takeIf { it.exists() }?.delete() }
    }

    // ==================== 内部 ====================

    /**
     * 从文件尾部向前读，返回**最多 [maxLines] 行、新→旧**的原始文本行。
     *
     * 块边界会切断 UTF-8 多字节字符（一个汉字 3 字节），所以**必须按字节处理**：
     * 块开头那个 `\n` 之前的部分不一定是完整行，原样留给下一轮拼，
     * 绝不能先 `String(bytes)` 再切——那样只会得到一堆 `�`。
     */
    @Synchronized
    private fun readTailLines(maxLines: Int): List<String> {
        val f = file()
        if (!f.exists() || f.length() == 0L) return emptyList()
        val out = ArrayList<String>(minOf(maxLines, 256))
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                var pos = raf.length()
                var pending = ByteArray(0)
                val buf = ByteArray(BLOCK)
                while (pos > 0L && out.size < maxLines) {
                    val want = minOf(BLOCK.toLong(), pos).toInt()
                    pos -= want
                    raf.seek(pos)
                    raf.readFully(buf, 0, want)

                    val combined = ByteArray(want + pending.size)
                    System.arraycopy(buf, 0, combined, 0, want)
                    System.arraycopy(pending, 0, combined, want, pending.size)

                    // 从尾向前按 \n 切：先切出来的必然是最新的行
                    var end = combined.size
                    var i = combined.lastIndex
                    while (i >= 0 && out.size < maxLines) {
                        if (combined[i] == NL) {
                            if (end > i + 1) {
                                out.add(String(combined, i + 1, end - i - 1, Charsets.UTF_8))
                            }
                            end = i
                        }
                        i--
                    }
                    if (pos > 0L) {
                        // 块还没到文件头：开头那截是半行，留给下一轮
                        pending = combined.copyOfRange(0, end)
                    } else {
                        // 到文件头了，剩下这截就是最早的一行（老文件可能没有结尾换行）
                        if (end > 0) out.add(String(combined, 0, end, Charsets.UTF_8))
                        pending = ByteArray(0)
                    }
                }
            }
        }.onFailure { LogStore.w(TAG, "关注记录读取失败：${it.message}") }
        return out
    }

    private fun parse(line: String): Record? {
        if (line.isBlank()) return null
        val p = line.split('\t')
        if (p.size < 3) return null
        val ts = p[0].trim().toLongOrNull() ?: return null
        val anchor = p[1].trim().takeIf { it.isNotEmpty() && it != PLACEHOLDER }
        val nickname = p[2].trim().takeIf { it.isNotEmpty() } ?: return null
        val kind = p.getOrNull(3)?.trim()?.firstOrNull() ?: KIND_FOLLOW
        return Record(ts, anchor, nickname, kind)
    }

    /** tab / 换行会把一行拆成两条记录，写入前必须清掉 */
    private fun sanitize(v: String?): String {
        val t = v?.trim().orEmpty()
        if (t.isEmpty()) return PLACEHOLDER
        return t.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }

    private fun file(): File = File(appContext.filesDir, FILE_NAME)

    companion object {
        private const val TAG = "关注记录"
        private const val FILE_NAME = "follow_records.tsv"

        /** 单次从文件尾部读的块大小：64 KB 大约 800~1000 条记录，够一页（300 条）翻三次还多 */
        private const val BLOCK = 64 * 1024
        private val NL = '\n'.code.toByte()

        /** [todayGroups] 单批读多少条 */
        private const val GROUP_PAGE = 200

        /** [todayGroups] 最多翻几批就放弃（正常情况下第一批就跨天结束了） */
        private const val MAX_GROUP_SCAN_PAGES = 5

        private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        const val KIND_FOLLOW = 'F'
        const val KIND_ALREADY = 'A'
        const val KIND_PRIVATE = 'P'

        /** 取不到主播昵称时的占位符（不猜、不补，界面上显示成「主播 —」） */
        private const val PLACEHOLDER = "-"

        @Volatile
        private var instance: FollowRecordStore? = null

        fun get(context: Context): FollowRecordStore =
            instance ?: synchronized(this) {
                instance ?: FollowRecordStore(context.applicationContext).also { instance = it }
            }
    }
}
