package com.example.dgg.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 取「当前真正的前台应用窗口」根节点。
 *
 * 为什么不直接用 `service.rootInActiveWindow`：实测在抖音全屏播放时它会返回
 * **后台应用残留的 Dialog 窗口**（截图里明明只有抖音推荐流），于是
 * 页面判定读到的是抖关关自己的界面，转储出来的也全是无关节点，
 * 还会让人误以为「抖音页面里根本没这些控件」。
 *
 * 改为在 `service.windows` 里挑**面积最大的应用窗口**：前台全屏应用必然胜出，
 * 悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）和 Dialog 都抢不到。
 * 拿不到窗口列表时才退回 `rootInActiveWindow`。
 */
object ActiveWindow {

    fun root(service: AccessibilityService?): AccessibilityNodeInfo? {
        if (service == null) return null

        val windows = runCatching { service.windows }.getOrNull()
        if (!windows.isNullOrEmpty()) {
            var best: AccessibilityWindowInfo? = null
            var bestScore = -1L
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = Rect()
                w.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height()
                // 前台应用和后台应用可能都是全屏、面积完全相等，纯比面积会选错。
                // 用「活动窗口」做高位权重，保证只要它是 active 就绝对胜出。
                val score = (if (w.isActive) 1L shl 40 else 0L) + area
                if (score > bestScore) {
                    bestScore = score
                    best = w
                }
            }
            best?.root?.let { return it }
        }

        return runCatching { service.rootInActiveWindow }.getOrNull()
    }
}
