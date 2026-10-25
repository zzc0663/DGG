package com.example.dgg.engine.risk

import android.view.accessibility.AccessibilityNodeInfo
import com.example.dgg.data.LogStore

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
        "账号异常"
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

    private fun match(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val value = text.toString()
        return PHRASES.firstOrNull { value.contains(it) }
    }
}
