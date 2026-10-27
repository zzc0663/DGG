package com.example.dgg.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.dgg.data.LogStore
import com.example.dgg.engine.risk.Humanizer
import com.example.dgg.util.ActiveWindow
import com.example.dgg.util.DouyinLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * 原子操作层：定位 + 点击 + 滑动 + 输入 + 等待。
 *
 * 两条设计约束：
 * 1. **不盲点**。定位不到就返回 false，由上层决定重试或停机；这里绝不「凭坐标猜一下」。
 * 2. **动作用手势而不是 ACTION_CLICK**。ACTION_CLICK 的落点是控件中心，千次如一次，
 *    是最容易被识别的行为特征；手势可以带随机偏移与随机按压时长。
 *    仅在控件无有效 bounds 或手势派发失败时才退化为 ACTION_CLICK。
 */
class Actions(
    private val service: AccessibilityService,
    private val humanizer: Humanizer
) {

    private val handler = Handler(Looper.getMainLooper())

    // ==================== 定位 ====================

    fun root(): AccessibilityNodeInfo? = ActiveWindow.root(service)

    fun foregroundPackage(): String? = root()?.packageName?.toString()

    fun isDouyinForeground(): Boolean = foregroundPackage() == DouyinLauncher.PACKAGE

    /**
     * 在 [root] 子树里按 [target] 查找节点。IMAGE 类型在 M1 恒不命中（图像通道 M2 接入）。
     */
    fun find(target: Target, root: AccessibilityNodeInfo? = root()): AccessibilityNodeInfo? {
        if (target.kind == Target.Kind.IMAGE) {
            LogStore.d(TAG, "图像通道未启用（M2），跳过 $target")
            return null
        }
        val start = root ?: return null
        return findBy(start) { node -> matches(node, target) && isVisibleOnScreen(node) }
    }

    fun exists(target: Target, root: AccessibilityNodeInfo? = null): Boolean =
        find(target, root) != null

    /**
     * 收集**全部**命中 [target] 的节点（上限 [max]）。
     *
     * M2 采集评论昵称要用：直播评论里同时有十几条「看起来像昵称」的节点，
     * 只取第一个等于每次都在关注同一个人。
     */
    fun collect(target: Target, max: Int, root: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        if (target.kind == Target.Kind.IMAGE) {
            LogStore.d(TAG, "图像通道未启用（M2 之后），跳过收集 $target")
            return emptyList()
        }
        val start = root ?: root() ?: return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>(max)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(start)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES && result.size < max) {
            val node = queue.removeFirst()
            visited++
            if (matches(node, target) && isVisibleOnScreen(node)) {
                result += node
                if (result.size >= max) break
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return result
    }

    /** 节点的可读标签：优先 text，其次 contentDescription。用于从节点里取昵称。 */
    fun labelOf(node: AccessibilityNodeInfo): String? =
        textOf(node) ?: descOf(node)

    /** 节点在屏幕上的矩形；无有效 bounds 时返回空 Rect（宽高为 0） */
    fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    fun screenHeight(): Int = service.resources.displayMetrics.heightPixels

    fun screenWidth(): Int = service.resources.displayMetrics.widthPixels

    /**
     * 节点是否真的在屏幕内可见。
     *
     * 必须过滤：抖音的评论是 RecyclerView，滚出屏幕的行**仍然留在无障碍节点树里**，
     * bounds 却是失效的（甚至越界成负坐标）。不过滤会有两种后果，实测都踩过：
     * 1. 采集到的昵称在回查时「已经滚出屏幕」——白跑一轮；
     * 2. 更糟：命中屏幕外的旧节点，拿它的旧坐标去点，落点正好压在**礼物按钮**上，
     *    把礼物面板打开了，于是后续所有采集都在礼物面板上做，全盘失效。
     */
    private fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean {
        val r = boundsOf(node)
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.bottom > 0 && r.top < screenHeight() && r.right > 0 && r.left < screenWidth()
    }

    private fun matches(node: AccessibilityNodeInfo, target: Target): Boolean {
        return when (target.kind) {
            Target.Kind.TEXT -> textOf(node) == target.value
            Target.Kind.TEXT_CONTAINS -> textOf(node)?.contains(target.value) == true
            Target.Kind.DESC -> descOf(node) == target.value
            Target.Kind.DESC_CONTAINS -> descOf(node)?.contains(target.value) == true
            Target.Kind.ID -> node.viewIdResourceName == target.value
            Target.Kind.IMAGE -> false
        }
    }

    /**
     * 广度优先遍历。抖音的节点树很深且大量节点被标记为 not important，
     * 所以在 accessibility_service_config 里开了 flagIncludeNotImportantViews。
     */
    private fun findBy(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    // ==================== 点击 ====================

    suspend fun click(target: Target): Boolean {
        val node = find(target)
        if (node == null) {
            LogStore.w(TAG, "未找到目标 $target")
            return false
        }
        return tapNode(node, target.toString())
    }

    suspend fun clickByName(name: String, config: LocatorConfig): Boolean {
        val target = config.target(name)
        if (target == null) {
            LogStore.e(TAG, "配置中不存在目标 $name")
            return false
        }
        return click(target)
    }

    /**
     * 点击一个已拿到的节点。公开出来是给「先 collect 再挑一个」的场景用的：
     * 直播弹幕滚动快，采集到的坐标早已失效，调用方必须用它**当场的** bounds。
     */
    suspend fun tapNode(node: AccessibilityNodeInfo, label: String): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 0 && bounds.height() > 0) {
            val point = humanizer.pointIn(bounds)
            if (dispatchTap(point)) {
                LogStore.d(TAG, "点击 $label @ ($point) 落点并非正中")
                return true
            }
        }

        // 退化路径：控件无有效 bounds，或手势被系统拒绝
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        LogStore.w(TAG, "手势点击不可用，退化为 ACTION_CLICK：$label → $ok")
        return ok
    }

    /**
     * 点击一条**评论行**的昵称区。
     *
     * 实测（抖音 40.8.0）：一条评论是「一整行一个 text 节点」，内容形如
     * `‎昵称：正文[表情][表情]`，昵称并不是独立节点。
     * 此时 `tapNode` 的「bounds 内避开中心随机取点」会落在**正文区**，
     * 而正文区点击不打开资料卡（多半无响应或触发别的手势）。
     *
     * 所以这里只在行首一小段里取点：水平方向限制在左侧 2%~28% 且不超过 150px，
     * 垂直方向**贴住行的上部**（见下面的注释，取中心会落到行内空白处）。
     */
    suspend fun tapNickname(node: AccessibilityNodeInfo, label: String): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            LogStore.w(TAG, "评论行没有有效 bounds，无法点击：$label")
            return false
        }

        val minX = bounds.left + Math.max(6, (bounds.width() * 0.02f).toInt())
        val maxX = minOf(
            bounds.left + (bounds.width() * 0.28f).toInt(),
            bounds.left + 150
        ).coerceAtLeast(minX + 4)
        val x = minX + humanizer.randomInt(maxX - minX + 1)
        // 不能取 centerY：评论行的 bounds 会随内容差异很大（纯文本行高 32px，
        // 带等级标签/多行正文时能到 117px）。在高的行上 centerY 会落到文字**下方的空白**，
        // 抖音把「点空白」解释成打开快捷操作栏（资料卡/喜欢/不喜欢/@TA/更多），
        // 于是等不到资料卡里的关注按钮，整轮被判「没等到弹层」。
        // 固定贴住行的上部（文字一定在那儿），用小偏移而不是比例。
        val y = bounds.top + minOf(bounds.height() / 2, TEXT_HIT_INSET)

        LogStore.d(
            TAG,
            "点击昵称区 $label @ ($x,$y) 行=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] 高=${bounds.height()}"
        )
        if (dispatchTap(Point(x, y))) return true

        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        LogStore.w(TAG, "昵称区手势不可用，退化为 ACTION_CLICK：$label → $ok")
        return ok
    }

    private suspend fun dispatchTap(point: Point): Boolean {
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, humanizer.tapDurationMs())
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    // ==================== 滑动 ====================

    /** 上滑：从下方 72% 划到上方 28%，用于列表翻页 */
    suspend fun swipeUp(): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2
        return swipe(
            fromX = cx,
            fromY = (dm.heightPixels * 0.72f).toInt(),
            toX = cx,
            toY = (dm.heightPixels * 0.28f).toInt()
        )
    }

    suspend fun swipeDown(): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2
        return swipe(
            fromX = cx,
            fromY = (dm.heightPixels * 0.30f).toInt(),
            toX = cx,
            toY = (dm.heightPixels * 0.70f).toInt()
        )
    }

    /** 多段折线滑动：段数与横向漂移都随机，避免出现笔直的机器轨迹 */
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        val segments = humanizer.swipeSegments()
        val drift = (abs(toY - fromY) * DRIFT_RATIO).toInt().coerceAtLeast(4)

        val path = Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()) }
        for (i in 1..segments) {
            val t = i.toFloat() / segments
            val x = fromX + (toX - fromX) * t
            val y = fromY + (toY - fromY) * t
            val jitter = if (i < segments) humanizer.driftPx(drift).toFloat() else 0f
            path.lineTo(x + jitter, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, humanizer.swipeDurationMs())
        val ok = dispatch(GestureDescription.Builder().addStroke(stroke).build())
        LogStore.d(TAG, "滑动 ($fromX,$fromY)→($toX,$toY) ${if (ok) "成功" else "失败"}")
        return ok
    }

    // ==================== 输入 ====================

    /**
     * 逐字输入。
     *
     * 没有直接用一次 ACTION_SET_TEXT 写整串：那样界面上是「瞬间出现」，
     * 与真人逐键输入的特征完全不同。这里每个字符累积一次并留 80–200ms 间隔。
     */
    suspend fun typeText(target: Target, text: String): Boolean {
        // 刻意声明为非空类型：循环里可能因界面刷新而重新定位，
        // 用可空 var 会让编译器在第二次迭代就失去非空智能转换（跨迭代赋值）。
        var node: AccessibilityNodeInfo = find(target) ?: run {
            LogStore.w(TAG, "未找到输入框 $target")
            return false
        }
        if (!node.isEditable) {
            LogStore.w(TAG, "$target 不是可编辑节点，仍尝试 SET_TEXT")
        }

        val accumulated = StringBuilder()
        for (ch in text) {
            accumulated.append(ch)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    accumulated.toString()
                )
            }
            var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) {
                // 节点可能因界面刷新而失效，重新定位一次再试
                val refreshed = find(target)
                if (refreshed != null) {
                    node = refreshed
                    ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            if (!ok) {
                LogStore.e(TAG, "输入被拒绝，已输入「$accumulated」")
                return false
            }
            delay(humanizer.charDelayMs())
        }
        LogStore.d(TAG, "输入完成：\"$text\"（逐字 ${text.length} 次）")
        return true
    }

    // ==================== 其他 ====================

    fun back(): Boolean {
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        LogStore.d(TAG, "返回键 → $ok")
        return ok
    }

    /** 轮询等待目标出现，超时返回 null（由上层决定是否重试） */
    suspend fun waitFor(
        target: Target,
        timeoutMs: Long,
        pollMs: Long = 200
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            find(target)?.let { return it }
            delay(pollMs)
        }
        return null
    }

    /**
     * 等待一组目标里任意一个出现，返回先命中的那个。
     *
     * 仅用于「等某个东西出现」的场景；要判断「弹层里到底是关注还是已关注」，
     * 必须带上位置过滤（弹层与直播间背景共用一棵树），见 flows 层的实现。
     */
    suspend fun waitForAny(
        targets: List<Target>,
        timeoutMs: Long,
        pollMs: Long = 200
    ): Target? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            for (target in targets) {
                if (find(target) != null) return target
            }
            delay(pollMs)
        }
        return null
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = runCatching { service.dispatchGesture(gesture, callback, handler) }
                .getOrDefault(false)
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    private fun textOf(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun descOf(node: AccessibilityNodeInfo): String? =
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "操作"
        const val MAX_NODES = 800

        /** 滑动横向漂移相对纵向距离的比例 */
        const val DRIFT_RATIO = 0.03f

        /**
         * 点评论行时，纵向从行顶往下偏移的上限（px）。
         * 评论行高 32~117px 不等，取 centerY 会落进文字下方空白触发快捷操作栏；
         * 文字本身总在行顶附近，所以贴上部取点。24px 约等于一行小字号文字的中心。
         */
        const val TEXT_HIT_INSET = 24
    }
}
