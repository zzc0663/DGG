package com.doujiajia.app.engine

/**
 * 一条标签规则：[word] 出现在视频文案里，就把互动概率放大 [bonusPercent]%。
 *
 * 「放大百分比」而不是「加百分点」，是因为最终公式是乘法（见 [NurtureTags.likeProbability]）。
 */
data class TagRule(val word: String, val bonusPercent: Int)

/**
 * 视频文案标签 —— 命中标签就提高点赞概率。
 *
 * **为什么用乘法**：`final = base × (1 + Σ加成)`。
 * 加法版（`base + Σ加成`）有个致命副作用：用户把「点赞倾向」拉到 0、标签写着
 * 「健身 +100%」，结果点赞概率变成 100% —— 用户会认为"我明明关了它还在点"。
 * 乘法下 base=0 恒为 0，用户的关闭意图不会被标签推翻。
 *
 * **两套上限，别用同一个常量**：
 * - 点赞 [MAX_LIKE_PERCENT] = 60（2026-10-28 用户决定放宽）
 * - 收藏/评论/转发 [MAX_INTERACT_PERCENT] = 40（维持界面既有承诺）
 */
object NurtureTags {

    const val MAX_LIKE_PERCENT = 60
    const val MAX_INTERACT_PERCENT = 40

    /** 最多几条规则：太多等于没规则，而且界面放不下 */
    const val MAX_RULES = 20

    /** 单条加成上限（%）与合计上限（%）。合计封顶是为了避免 20 条各 200% 的荒唐配置 */
    const val MAX_BONUS_PERCENT_PER_RULE = 200
    const val MAX_BONUS_PERCENT_TOTAL = 400

    data class ParseResult(
        val rules: List<TagRule>,
        /** 解析失败的行（原样回显给用户，不静默丢弃） */
        val invalidLines: List<String>
    ) {
        val isEmpty: Boolean get() = rules.isEmpty()
    }

    /**
     * 解析界面里那段多行文本。每行两种写法都接受：
     *
     * ```
     * 健身            → 加成 100%
     * 蛋白粉 80       → 加成 80%
     * 减脂 50%        → 加成 50%（% 可有可无）
     * ```
     *
     * **一行非法只跳过这一行**，不让整份配置失败。
     * `LocatorConfig.parse` 就吃过这个亏：一行写错的注释让整份定位配置降级，
     * M2 一启动就以「缺少 follow_button」终止。
     */
    fun parse(raw: String?): ParseResult {
        if (raw.isNullOrBlank()) return ParseResult(emptyList(), emptyList())

        val rules = LinkedHashMap<String, TagRule>()
        val invalid = ArrayList<String>()

        for (line in raw.lineSequence()) {
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text.startsWith("//") || text.startsWith("#")) continue

            val parts = text.split(WHITESPACE).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            var bonus = 100
            var wordParts = parts
            val last = parts.last().removeSuffix("%")
            val maybeNumber = last.toIntOrNull()
            if (maybeNumber != null && parts.size > 1) {
                bonus = maybeNumber.coerceIn(0, MAX_BONUS_PERCENT_PER_RULE)
                wordParts = parts.dropLast(1)
            }

            val word = wordParts.joinToString(" ").trim()
            if (word.isEmpty()) {
                invalid += text
                continue
            }
            if (word.length > 32) {
                invalid += text
                continue
            }
            if (rules.size >= MAX_RULES) {
                invalid += text
                continue
            }
            // 同名单词后者覆盖前者：用户改权重时不必先删旧行
            rules[word] = TagRule(word, bonus)
        }

        return ParseResult(rules.values.toList(), invalid)
    }

    /** 命中哪些标签（顺序按配置顺序，便于日志与用户核对） */
    fun match(copyText: String, rules: List<TagRule>): List<TagRule> {
        if (copyText.isBlank() || rules.isEmpty()) return emptyList()
        // 英文/数字标签要不区分大小写；中文没有大小写，ignoreCase 无副作用
        return rules.filter { copyText.contains(it.word, ignoreCase = true) }
    }

    /** 点赞的最终概率（%） */
    fun likeProbability(basePercent: Int, hits: List<TagRule>): Int =
        probability(basePercent, hits, MAX_LIKE_PERCENT)

    /** 收藏等其它互动的最终概率（%）—— 上限仍是 40 */
    fun interactProbability(basePercent: Int, hits: List<TagRule>): Int =
        probability(basePercent, hits, MAX_INTERACT_PERCENT)

    private fun probability(basePercent: Int, hits: List<TagRule>, cap: Int): Int {
        val base = basePercent.coerceIn(0, 100)
        if (base == 0) return 0
        val bonus = hits.sumOf { it.bonusPercent }.coerceAtMost(MAX_BONUS_PERCENT_TOTAL)
        // base × (100+bonus) / 100，四舍五入后夹到上限
        val scaled = (base.toLong() * (100 + bonus) + 50) / 100
        return scaled.coerceIn(0, cap.toLong()).toInt()
    }

    /** 日志用的命中描述：`减脂+50% 健身+100%` */
    fun describe(hits: List<TagRule>): String =
        if (hits.isEmpty()) "无命中" else hits.joinToString(" ") { "${it.word}+${it.bonusPercent}%" }

    private val WHITESPACE = Regex("\\s+")
}
