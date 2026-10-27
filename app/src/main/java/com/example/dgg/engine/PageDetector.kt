package com.example.dgg.engine

import com.example.dgg.data.LogStore

/**
 * 页面识别 —— **加权多特征投票**（计划 §2.1）。
 *
 * 为什么不是「命中一个特征就判定」：抖音版本不设限，任何单一控件都可能在某个版本里
 * 改名、改层级、被合并。多个特征加权后取最高分，只要超过阈值就成立，
 * 少掉一两个特征不会导致判定崩掉。
 *
 * **性能关键**：所有特征都在**同一份节点快照**上比对（[Actions.snapshot]）。
 * 旧实现对每条特征各遍历一次全树，21 条特征 × 647 节点 = 每次判定 30 秒，
 * 表现是「点了开始之后半分钟界面毫无动静」。现在是一趟遍历 + 21 次内存比对。
 *
 * 判定只在**页面发生变化时**打日志，否则轮询会把日志刷爆。
 */
class PageDetector(
    private val config: LocatorConfig,
    private val actions: Actions
) {

    data class Detection(
        val page: PageState,
        val score: Int,
        val threshold: Int,
        val pageKey: String,
        val hits: List<String>
    ) {
        val matched: Boolean get() = page != PageState.UNKNOWN

        fun summary(): String =
            if (matched) "${page.name}（$score/$threshold）"
            else "UNKNOWN（最高分 $score/${threshold}，最接近 $pageKey）"
    }

    private var lastLoggedPage: PageState? = null

    fun detect(): Detection {
        val facts = actions.snapshot()
        if (facts.isEmpty()) {
            val d = Detection(
                PageState.UNKNOWN, 0, 0, "",
                listOf("无根节点：无障碍未授权，或当前窗口不提供内容")
            )
            logOnChange(d)
            return d
        }

        var bestPassing: Pair<PageRule, Pair<Int, List<String>>>? = null
        var bestOverall: Triple<PageRule, Int, List<String>>? = null

        for (rule in config.pages.values) {
            var score = 0
            val hits = mutableListOf<String>()
            for (feature in rule.features) {
                if (facts.any { actions.matchesFacts(it, feature.target) }) {
                    score += feature.weight
                    hits += "${feature.target}(${feature.weight})"
                }
            }

            val overall = bestOverall
            if (overall == null || score > overall.second) {
                bestOverall = Triple(rule, score, hits)
            }
            if (score >= rule.threshold) {
                val current = bestPassing
                if (current == null || score > current.second.first) {
                    bestPassing = rule to (score to hits)
                }
            }
        }

        val passing = bestPassing
        if (passing != null) {
            val (rule, scoreAndHits) = passing
            val detection = Detection(
                page = PageState.fromName(rule.name),
                score = scoreAndHits.first,
                threshold = rule.threshold,
                pageKey = rule.name,
                hits = scoreAndHits.second
            )
            logOnChange(detection)
            return detection
        }

        val overall = bestOverall
        val detection = if (overall == null) {
            Detection(PageState.UNKNOWN, 0, 0, "", listOf("配置中无任何页面规则"))
        } else {
            Detection(
                page = PageState.UNKNOWN,
                score = overall.second,
                threshold = overall.first.threshold,
                pageKey = overall.first.name,
                hits = overall.third
            )
        }
        logOnChange(detection)
        return detection
    }

    private fun logOnChange(detection: Detection) {
        if (lastLoggedPage == detection.page) return
        lastLoggedPage = detection.page
        if (detection.matched) {
            LogStore.i(TAG, "页面 → ${detection.page.name}（${detection.score}/${detection.threshold}）证据：${detection.hits.joinToString(" + ")}")
        } else {
            LogStore.w(TAG, "页面 UNKNOWN：最高分 ${detection.score}/${detection.threshold}（最接近 ${detection.pageKey}），证据：${detection.hits.joinToString(" + ").ifEmpty { "无" }}")
        }
    }

    private companion object {
        const val TAG = "页面识别"
    }
}
