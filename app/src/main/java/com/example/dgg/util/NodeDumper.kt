package com.example.dgg.util

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.dgg.data.LogStore
import java.io.File

/**
 * 把当前活动窗口的节点树按 **uiautomator 同格式** 写到文件。
 *
 * 为什么需要它：直播间画面持续动画，`uiautomator dump` 会一直等 UI idle 直到超时
 * （实测报 `ERROR: could not get idle state`）。也就是说真机校准直播间时，
 * 系统自带的那条路根本走不通，只能由无障碍服务自己遍历。
 *
 * 输出格式刻意与 uiautomator 一致，好让 `tools/parse_dump.py` 直接解析同一份脚本。
 */
object NodeDumper {

    private const val TAG = "节点转储"
    private const val MAX_DEPTH = 60
    private const val MAX_NODES = 4000

    /** @return 写入的节点数；-1 表示拿不到活动窗口（多半是无障碍没连上或当前窗口不可读） */
    fun dump(service: AccessibilityService, file: File): Int {
        val root = pickRoot(service)
        if (root == null) {
            LogStore.e(TAG, "拿不到窗口根节点：无障碍未连接，或当前窗口来自其他用户空间/安全窗口")
            return -1
        }

        val sb = StringBuilder(1 shl 16)
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        sb.append("<hierarchy rotation=\"0\">\n")

        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            val r = Rect()
            node.getBoundsInScreen(r)
            sb.append("  <node")
            sb.append(" text=\"").append(esc(node.text)).append('"')
            sb.append(" resource-id=\"").append(esc(node.viewIdResourceName)).append('"')
            sb.append(" class=\"").append(esc(node.className)).append('"')
            sb.append(" package=\"").append(esc(node.packageName)).append('"')
            sb.append(" content-desc=\"").append(esc(node.contentDescription)).append('"')
            sb.append(" clickable=\"").append(node.isClickable).append('"')
            sb.append(" enabled=\"").append(node.isEnabled).append('"')
            sb.append(" bounds=\"[").append(r.left).append(',').append(r.top).append("][")
                .append(r.right).append(',').append(r.bottom).append("]\"")
            if (node.childCount == 0) {
                sb.append(" />\n")
            } else {
                sb.append(">\n")
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
                sb.append("  </node>\n")
            }
        }

        walk(root, 0)
        sb.append("</hierarchy>\n")

        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())
            LogStore.i(TAG, "已转储 $count 个节点 → ${file.absolutePath}")
            count
        }.getOrElse {
            LogStore.e(TAG, "写文件失败：${it.message}")
            -1
        }
    }

    /**
     * 选哪个窗口来转储。
     *
     * 不能直接用 `rootInActiveWindow`：实测在抖音全屏播放时它会把**后台应用残留的
     * Dialog 窗口**当成活动窗口返回（截图里明明只有抖音推荐流），于是校准拿到的
     * 是一棵毫不相干的节点树，还会让人误以为「抖音页面里没这些控件」。
     *
     * 改为在 `service.windows` 里挑**面积最大的应用窗口**：前台全屏应用必然胜出，
     * 悬浮窗（TYPE_ACCESSIBILITY_OVERLAY / TYPE_APPLICATION_OVERLAY）和 Dialog 都不会抢到。
     * 拿不到窗口列表时才退回 `rootInActiveWindow`。
     */
    private fun pickRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows = runCatching { service.windows }.getOrNull()
        if (!windows.isNullOrEmpty()) {
            var best: AccessibilityWindowInfo? = null
            var bestArea = -1
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = Rect()
                w.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) {
                    bestArea = area
                    best = w
                }
            }
            best?.root?.let { return it }
        }
        return service.rootInActiveWindow
    }

    private fun esc(cs: CharSequence?): String {
        if (cs == null) return ""
        val s = cs.toString()
        if (s.isEmpty()) return ""
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\n' -> out.append("&#10;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
