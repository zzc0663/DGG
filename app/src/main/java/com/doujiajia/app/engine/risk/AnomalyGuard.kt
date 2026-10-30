package com.doujiajia.app.engine.risk

import android.view.accessibility.AccessibilityNodeInfo
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.engine.Actions

/**
 * 风控异常识别 —— **命中即停机，禁止任何重试**（计划 §3.5）。
 *
 * 理由：被风控拦下之后继续重试，是把「临时限流」升级成「封号」最快的路径。
 * 所以这里的语义只有两种：没命中（继续）、命中（终止），没有「再试一次」。
 */
object AnomalyGuard {

    data class Hit(val phrase: String, val field: String, val text: String) {
        override fun toString(): String = "「$phrase」($field=\"$text\")"
    }

    /** 命中任一词即视为风控拦截。宁可误停，不可误闯。 */
    private val PHRASES = listOf(
        "操作过于频繁",
        "操作频繁",
        "请稍后再试",
        "请稍后重试",
        "请完成安全验证",
        "安全验证",
        "拖动滑块",
        "滑块验证",
        "请输入验证码",
        "验证码",
        "登录异常",
        "账号存在风险",
        "账号异常",
        // 直播互动（M3）专项：评论频率被拦。这类提示往往是闪一下就消失的 toast，
        // 所以 LiveInteractFlow 在「点完发送」之后会立刻多查一次，不等到下一次 settle。
        "评论太快",
        "评论过快",
        "评论过于频繁",
        "发送太频繁",
        "发送过快",
        "发言太频繁",
        "稍后再评论"
    )

    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 30

    private const val TAG = "风控守卫"

    /** @return 命中的异常；null 表示当前界面未见风控提示 */
    fun check(root: AccessibilityNodeInfo?): Hit? {
        if (root == null) return null

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++

            match(node.text)?.let { phrase ->
                LogStore.e(TAG, "检测到风控提示：${Hit(phrase, "text", node.text.toString())}")
                return Hit(phrase, "text", node.text.toString())
            }
            match(node.contentDescription)?.let { phrase ->
                LogStore.e(
                    TAG,
                    "检测到风控提示：${Hit(phrase, "desc", node.contentDescription.toString())}"
                )
                return Hit(phrase, "desc", node.contentDescription.toString())
            }

            if (depth >= MAX_DEPTH) continue
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
        return null
    }

    /**
     * 在**已有快照**上检查。
     *
     * 每次 settle 之后都要查风控，而 [check(root)] 自己就遍历一遍全树（实测 647 节点的
     * 直播间约 1.4 秒）。一次等待要切片十几次，等于白花十几秒。
     * 上层已经有快照（页面判定刚抓过、且中间没有任何手势）时走这条重载，零额外遍历。
     */
    fun check(facts: List<Actions.NodeFacts>): Hit? {
        for (fact in facts) {
            val text = fact.text
            if (text != null) {
                match(text)?.let { phrase ->
                    LogStore.e(TAG, "检测到风控提示：${Hit(phrase, "text", text)}")
                    return Hit(phrase, "text", text)
                }
            }
            val desc = fact.desc
            if (desc != null) {
                match(desc)?.let { phrase ->
                    LogStore.e(TAG, "检测到风控提示：${Hit(phrase, "desc", desc)}")
                    return Hit(phrase, "desc", desc)
                }
            }
        }
        return null
    }

    private fun match(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val value = text.toString()
        return PHRASES.firstOrNull { value.contains(it) }
    }
}
