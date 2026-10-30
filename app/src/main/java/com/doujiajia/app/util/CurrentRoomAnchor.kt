package com.doujiajia.app.util

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.doujiajia.app.data.LogStore

/**
 * 取「当前所在直播间」的锚点信息。
 *
 * 直播间左上角有一个 Button，desc 是「<主播昵称><粉丝数>万本场点赞」（40.8.0 实测
 * `desc='小好130.4万本场点赞'`），它下面第一个子 TextView 的 text 就是纯昵称。
 * 「收藏直播间」靠它拿到当前房间；拿不到（不在直播间 / 特征变了）就如实返回 null。
 */
object CurrentRoomAnchor {

    private const val LIKE_DESC_KEY = "本场点赞"

    /** 当前直播间的主播昵称；不在直播间或读不到时返回 null */
    fun currentAnchorName(service: AccessibilityService?): String? {
        if (service == null) return null

        // 不挑「前台窗口」、直接把所有应用窗口都试一遍：
        // 面板展开时我们自己的 overlay 可能是 active，挑窗逻辑（ActiveWindow）会把
        // 抖音窗口排到后面甚至选错；而「本场点赞」desc 只在直播间里有，逐个窗口
        // 找到它就是最可靠的信号。
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = w.root
            if (root == null) {
                LogStore.d("收藏房间", "窗口 type=${w.type} 的 root 为 null（可能 inactive 被裁剪）")
                continue
            }
            val pkg = root.packageName?.toString()
            if (pkg == service.packageName) continue
            findAnchorIn(root)?.let { return it }
                ?: LogStore.d("收藏房间", "窗口 $pkg 里没有「本场点赞」desc（节点树 ${w.root.childCount} 层起）")
        }
        LogStore.w(
            "收藏房间",
            "所有窗口都没读到主播昵称：共 ${windows.size} 个窗口，" +
                "类型=${windows.joinToString { "${it.type}" }}，active=${windows.count { it.isActive }}"
        )
        return findAnchorIn(runCatching { service.rootInActiveWindow }.getOrNull())
    }

    /** 在一棵节点树里找「<昵称><粉丝数>本场点赞」按钮，取其昵称子节点 */
    private fun findAnchorIn(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString() ?: ""
            if (LIKE_DESC_KEY in desc) {
                // 第一个 text 非空、且不是「xxx万本场点赞」本身的子节点就是昵称
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrBlank() && LIKE_DESC_KEY !in text) return text
                }
                return null
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    /**
     * 从**快照事实**里剥主播昵称（给运行中的 Flow 用）。
     *
     * Flow 每轮都会做一次快照遍历（页面判定/风控/采集共用），这里只是从同一份
     * 缓存里多比一个 desc——零额外遍历成本。悬浮窗那边「收藏直播间」展开面板时
     * 读不到实时树（抖音窗口会退为 inactive），所以Flow 必须替它记住这个值。
     */
    fun anchorFromSnapshot(actions: com.doujiajia.app.engine.Actions): String? {
        val fact = actions.snapshot().firstOrNull { it.desc?.contains(LIKE_DESC_KEY) == true }
            ?: return null
        return stripAnchor(fact.desc ?: return null)
    }

    /**
     * `desc='小好151.9万本场点赞'` → `小好`。
     * 非贪婪 + 行尾锚定：昵称本身带数字也不会被切掉（「小好2」→「小好2」）。
     */
    fun stripAnchor(desc: String): String? {
        val m = ANCHOR_REGEX.find(desc) ?: return null
        return m.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    private val ANCHOR_REGEX = Regex("^(.*?)\\d+(?:\\.\\d+)?万?本场点赞$")

    private const val MAX_NODES = 1200
}
