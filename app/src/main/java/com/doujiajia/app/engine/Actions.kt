package com.doujiajia.app.engine

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
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.engine.risk.Humanizer
import com.doujiajia.app.util.ActiveWindow
import com.doujiajia.app.util.DouyinLauncher
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
        return findBy(start) { node -> matches(node, target) && isOnScreen(node) }
    }

    // ==================== 节点快照（一次遍历，多处复用） ====================

    /**
     * 某个节点的**只读特征快照**。
     *
     * 为什么要这个：每次读 `node.text` / `node.contentDescription` / `getChild(i)`
     * 都是一次跨进程调用。实测在这台真机的直播间里（647 个节点），**一趟全树遍历约 1.4 秒**。
     * 而页面判定有 21 条特征（5 页 × 各 4~5 条）、风控检查 1 趟、每一轮采集又要 1 趟……
     * 如果每条特征都自己遍历一遍（旧实现就是），一次 `detect()` 要 30 秒，
     * 表现就是「点了开始之后半分钟没有任何动作」。
     *
     * 现在改成：**遍历一次抓成快照，所有特征在内存里比对**。
     */
    class NodeFacts(
        val text: String?,
        val desc: String?,
        val id: String?,
        val editable: Boolean,
        /**
         * 屏幕坐标里的**下边缘**。
         *
         * 直播评论是自下往上堆的（最新一条恒在列表底部），所以「谁是最新的一条」
         * 只能靠 y 判断：bottom 越大越新。业务流程用它排序/找最新一行，
         * 不再为这个信息额外跑一趟遍历。
         */
        val bottom: Int = 0,
        /** 屏幕坐标里的**上边缘**，用于量「评论行不能压过输入栏」这类边界 */
        val top: Int = 0,
        /** 左边缘。养号靠 x 位置区分「右侧竖排按钮栏」与「左下文案区」，没有它就得再遍历一次 */
        val left: Int = 0,
        /** 右边缘，与 [left] 配套 */
        val right: Int = 0
    ) {
        /** 快照里量出来的矩形，点它的时候不必再回表找节点 */
        fun rect(): Rect = Rect(left, top, right, bottom)
    }

    private var snapshotCache: Snapshot? = null

    private class Snapshot(val at: Long, val facts: List<NodeFacts>)

    /**
     * 抓一份快照。[ttlMs] 内重复调用直接复用上一份。
     *
     * 缓存的安全性靠两点保证：
     * 1. **任何手势之后立刻失效**（见 [invalidateSnapshot]，所有 dispatchBack/dispatchTap/swipe 都会调）；
     * 2. TTL 很短（默认 700ms），小于一个轮询周期——界面上真的冒出风控弹窗，最多晚 0.7 秒被发现。
     *
     * @param pruneSubtreeOfDesc 传入一个 desc 值时，**该 desc 命中的节点不再展开子树**。
     *   专为养号设计：抖音视频页实测 1173 个节点、单次遍历 **12.6 秒**（每节点约 8 次跨进程调用），
     *   其中绝大多数是 `content-desc="视频"` 那个播放器容器下的贴纸/挂件空节点，养号用不到。
     *   默认 null = 不剪枝，M2/M3 的老行为完全不变。
     *
     *   ⚠️ 刻意**不用「屏幕中央区域」当剪枝条件**：风控弹窗的容器也是「无文本 + 居中」，
     *   按区域剪会连「操作频繁」这类弹窗一起剪掉，导致风控漏检——那是比慢严重得多的问题。
     *   按 desc 精确剪则不会误伤：弹窗容器的 desc 不是「视频」。
     * @param lean 跳过「养号用不上」的字段（id、editable），每节点省 2 次跨进程调用。
     *   只对信任调用方开启：靠 id 定位的流程（M2/M3 部分路径）不能用 lean。
     * @param pruneOffscreen **整棵子树都在屏幕外时不再展开**。
     *   屏幕外的叶子节点本来就不会进 `facts`（[isOnScreen] 已拦），但旧实现仍会把它们的子树
     *   全部走一遍。抖音视频页的 RecyclerView 会预加载下一条/下 N 条，实测下一条的点赞按钮
     *   top=3376、文案 top=4244（屏高 2374），还有成片的「券后价 / 已售 1万+件」商品列表，
     *   这些全在屏幕外却要花十几秒去读。默认关，只给养号这种「每屏必抓一次树」的流程开。
     *
     *   ⚠️ 但别指望它解决「一屏十几秒」：实测同一台机器、同样 1200 个节点，
     *   一趟 0.2s 也见过、20s 也见过（抖动来自系统对无障碍 IPC 的节流，与节点数无关），
     *   而剪枝只命中 0~2 棵子树。真正的开销在「1200 个节点 × 每节点数次跨进程调用」。
     */
    fun snapshot(
        ttlMs: Long = SNAPSHOT_TTL_MS,
        root: AccessibilityNodeInfo? = null,
        pruneSubtreeOfDesc: String? = null,
        lean: Boolean = false,
        pruneOffscreen: Boolean = false
    ): List<NodeFacts> {
        val cached = snapshotCache
        if (cached != null && SystemClock.uptimeMillis() - cached.at <= ttlMs) return cached.facts

        val startedAt = SystemClock.uptimeMillis()
        val start = root ?: root()
        if (start == null) {
            val empty = emptyList<NodeFacts>()
            snapshotCache = Snapshot(SystemClock.uptimeMillis(), empty)
            return empty
        }

        val facts = ArrayList<NodeFacts>(256)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(start)
        var visited = 0
        var pruned = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            // bounds 只取一次：isOnScreen 和 bottom 都要用，重复取就是重复跨进程调用
            val bounds = boundsOf(node)
            var text: String? = null
            var desc: String? = null
            // 屏外节点不参与判定：RecyclerView 滚出屏幕的行仍留在树里，且 bounds 已失效
            if (isOnScreen(bounds)) {
                text = textOf(node)
                desc = descOf(node)
                facts += NodeFacts(
                    text = text,
                    desc = desc,
                    id = if (lean) null else node.viewIdResourceName,
                    editable = if (lean) false else node.isEditable,
                    bottom = bounds.bottom,
                    top = bounds.top,
                    left = bounds.left,
                    right = bounds.right
                )
            }

            // 播放器子树整片不展开：视频页 1173 节点里绝大部分是它下面的空容器，
            // 而养号要的文案在左下、按钮在右侧，都不在它子树里。
            // 注意这里**没有**用 `!node.isVisibleToUser` 当剪枝条件：实测它很少命中
            // （35 趟快照里只有 2 趟剪掉 89 个节点），却要给每个节点多一次跨进程调用。
            val skipChildren = (pruneSubtreeOfDesc != null && desc == pruneSubtreeOfDesc) ||
                (pruneOffscreen && isFullyOffscreen(bounds))
            if (skipChildren) {
                pruned++
                continue
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        val elapsed = SystemClock.uptimeMillis() - startedAt
        // 慢遍历必须留痕：养号在播放中的视频页上遇到过「一屏十几秒」的情况，
        // 没有这条日志就分不清是脚本在发呆还是无障碍被系统节流了。
        if (elapsed >= SLOW_SNAPSHOT_MS) {
            LogStore.w(
                TAG,
                "快照耗时 ${elapsed}ms（遍历 $visited 节点 / 剪枝 $pruned / 保留 ${facts.size} 条）"
            )
        }
        snapshotCache = Snapshot(SystemClock.uptimeMillis(), facts)
        return facts
    }

    /** 界面被操作过，快照作废 */
    fun invalidateSnapshot() {
        snapshotCache = null
    }

    /** 在快照上比对一条特征，语义与 [matches] 完全一致 */
    fun matchesFacts(facts: NodeFacts, target: Target): Boolean {
        return when (target.kind) {
            Target.Kind.TEXT -> facts.text == target.value
            Target.Kind.TEXT_CONTAINS -> facts.text?.contains(target.value) == true
            Target.Kind.DESC -> facts.desc == target.value
            Target.Kind.DESC_CONTAINS -> facts.desc?.contains(target.value) == true
            Target.Kind.ID -> facts.id == target.value
            Target.Kind.EDITABLE -> facts.editable
            Target.Kind.IMAGE -> false
        }
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
            if (matches(node, target) && isOnScreen(node)) {
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

    /**
     * 「复用采集阶段拿到的节点」的校验：刷新后再确认**还在屏上、内容没被别人顶替**。
     *
     * 两件事必须都做，少一件就等于没校验：
     * - `refresh()` 只保证节点对象还挂在树上。评论列表是 RecyclerView，View 被回收复用后
     *   节点对象依然有效（refresh 返回 true），但**显示的内容已经换成另一个人了**；
     * - 所以还要比对标签，确认这条节点仍然承载着 [expectedLabel]。
     *
     * 直播弹幕滚动很快，采集到点击之间的几百毫秒足以让行位移甚至换人。此前一律「按昵称回查」，
     * 安全但每次都要重走一遍整棵树（含大量屏外节点），一屏刷新快的时候几乎每轮都白跑。
     * 有了这道校验，绝大多数轮次可以直接用采集时的节点，失效了才回退回查。
     */
    fun stillValid(node: AccessibilityNodeInfo, expectedLabel: String): Boolean {
        if (!runCatching { node.refresh() }.getOrDefault(false)) return false
        if (!isOnScreen(node)) return false
        val label = labelOf(node) ?: return false
        return label.contains(expectedLabel)
    }

    /**
     * 找当前屏上第一个**可编辑**节点。
     *
     * 直播间的评论输入框，无障碍树里「说点什么」这句占位提示和真正的 EditText 可能不是同一个节点，
     * 用 textContains("说点什么") 命中的往往是提示 TextView，对它 SET_TEXT 必然失败。
     * 所以点完输入区后按 `isEditable` 重新找一次真正的输入框。
     */
    fun findEditable(root: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val start = root ?: root() ?: return null
        return findBy(start) { node -> node.isEditable && isOnScreen(node) }
    }

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
     *
     * 公开出去是给业务流程用的：它自己留住采集时的节点，点击前要再校验一次可见性。
     */
    fun isOnScreen(node: AccessibilityNodeInfo): Boolean = isOnScreen(boundsOf(node))

    private fun isOnScreen(r: Rect): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.bottom > 0 && r.top < screenHeight() && r.right > 0 && r.left < screenWidth()
    }

    /**
     * 整棵子树都在屏幕外。用来**停止向下遍历**，与 [isOnScreen] 相反但更保守：
     * 矩形无效（宽高 <= 0，容器没报到 bounds）时一律返回 false，绝不剪；
     * 否则可能把风控弹窗这类「bounds 还没算好」的东西整片剪掉 —— 那是比慢严重得多的问题。
     */
    private fun isFullyOffscreen(r: Rect): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.top >= screenHeight() || r.bottom <= 0
    }

    private fun matches(node: AccessibilityNodeInfo, target: Target): Boolean {
        return when (target.kind) {
            Target.Kind.TEXT -> textOf(node) == target.value
            Target.Kind.TEXT_CONTAINS -> textOf(node)?.contains(target.value) == true
            Target.Kind.DESC -> descOf(node) == target.value
            Target.Kind.DESC_CONTAINS -> descOf(node)?.contains(target.value) == true
            Target.Kind.ID -> node.viewIdResourceName == target.value
            Target.Kind.EDITABLE -> node.isEditable
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
     *
     * **花钱守卫**：见 [isPaidAction]，命中就直接拒绝，不走任何退化路径。
     */
    suspend fun tapNode(node: AccessibilityNodeInfo, label: String): Boolean {
        if (isPaidAction(node)) {
            LogStore.e(
                TAG,
                "拒绝点击 $label：目标看起来是**付费**动作" +
                    "（text=\"${textOf(node)}\" desc=\"${descOf(node)}\"）。" +
                    "这一步可能产生真实消费，任何流程都不允许自动执行"
            )
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 0 && bounds.height() > 0) {
            val point = humanizer.pointIn(bounds)
            if (dispatchTap(point)) {
                // 把 bounds 一起打出来：同类问题（点了没反应）最需要的就是
                // 「当时这颗按钮在哪儿、点到了哪儿」，缺了它只能靠猜。
                LogStore.d(TAG, "点击 $label @ ($point) 目标=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
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
        // 取证：点位如果压在悬浮窗上，日志里留一句。手势期间悬浮窗已经被
        // TouchGuard 置成不可触摸（所以不会被吞），这里只为了让「点了没反应」这类
        // 问题下一次能直接被排除，而不是靠猜。
        if (TouchGuard.covers(point.x, point.y)) {
            LogStore.d(TAG, "点击点 (${point.x},${point.y}) 压在悬浮窗上（已临时置为不可触摸，不会吞点击）")
        }
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, humanizer.tapDurationMs())
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * 一趟遍历里同时试多个候选文案，**命中第一个就返回**。
     *
     * 为什么需要它：`snapshot()` 会把每个节点的 text/desc/id/editable/四个 bounds 全读一遍
     * （7 次跨进程调用/节点，647 节点实测 1.4s）；而「找浮标」只需要比较文案，
     * 走这里大约是 snapshot 的 1/4~1/3。逐个 `find` 更差——有几个文案就要几趟。
     *
     * ⚠️ 命中可能是「带这串字的容器」而不是文本节点本身（BFS 父节点在前）。
     * 抖音的「N条新消息」胶囊容器就是那个胶囊（实测 198×53），点它中心仍在胶囊内，
     * 所以这里不做「取面积最小」那一步——省下来的正是一趟 snapshot。
     */
    fun findFirst(texts: List<String>): AccessibilityNodeInfo? {
        if (texts.isEmpty()) return null
        val start = root() ?: return null
        return findBy(start) { node ->
            if (!isOnScreen(node)) return@findBy false
            val text = textOf(node)
            if (!text.isNullOrEmpty() && texts.any { text.contains(it) }) return@findBy true
            val desc = descOf(node)
            !desc.isNullOrEmpty() && texts.any { desc.contains(it) }
        }
    }

    /**
     * 按 view id 找节点（`com.ss.android.ugc.aweme:id/r68` → 传 `"r68"`）。
     *
     * 走 framework 的 `findAccessibilityNodeInfosByViewId`，它是原生递归实现，
     * 比自己用 Kotlin BFS 一节点一节点问要快数倍——「关注后 1 秒内完成下滑」全靠这一步。
     * 找不到（抖音改版换了混淆 id）返回 null，调用方必须留遍历兜底。
     */
    fun findByViewId(id: String): AccessibilityNodeInfo? {
        val start = root() ?: return null
        val full = if (id.contains(":id/")) id else (start.packageName?.toString() ?: "") + ":id/" + id
        val nodes = runCatching { start.findAccessibilityNodeInfosByViewId(full) }.getOrNull()
        return nodes?.firstOrNull { isOnScreen(it) }
    }

    /**
     * 找一个**没有可点控件**的空白落点。
     *
     * 点赞要双击画面，但直播间画面上浮着礼物飘屏、送礼用户头像、互动挂件这些临时元素，
     * 它们可点且会弹资料卡/充值页——实测就点开过别人的资料卡。
     * 所以落点不能随便取：先随机试几个点，用 `isClickable` 校验底下是空的，全都压着控件就放弃
     * （宁可这轮不点赞，也不盲点）。
     */
    fun freePointIn(area: Rect, attempts: Int = 6): Point? {
        repeat(attempts) {
            val point = humanizer.pointIn(area)
            if (!hasClickableAt(point.x, point.y)) return point
        }
        return null
    }

    /** 屏幕坐标 (x,y) 处是否压着一个可点控件 */
    fun hasClickableAt(x: Int, y: Int, root: AccessibilityNodeInfo? = null): Boolean {
        val start = root ?: root() ?: return false
        return findBy(start) { node ->
            node.isClickable && isOnScreen(node) && boundsOf(node).contains(x, y)
        } != null
    }

    /**
     * 花钱守卫：目标是不是一个**会真实消费**的动作。
     *
     * 实测教训（40.8.0）：直播间底栏那颗叫「**小心心**」的控件是**1 钻的付费礼物**，
     * 不是点赞按钮。点下去直接弹「送出小心心礼物需支付1钻，确定支付？」。
     * 也就是说按文案「心形/小心心 = 点赞」去挑控件，会花掉用户的钱。
     *
     * 这条守卫放在最底层（而不是各业务自己判断），因为只要有一条流程漏判，
     * 后果就是真实扣款——这类错误不允许靠「记得检查」来避免。
     */
    private fun isPaidAction(node: AccessibilityNodeInfo): Boolean {
        val label = (textOf(node) ?: "") + " " + (descOf(node) ?: "")
        return PAID_HINTS.any { label.contains(it) }
    }

    /**
     * 双击同一个点。
     *
     * 抖音直播间里**双击画面就是点赞**（飘心），而右下角那颗心形按钮在无障碍树里
     * 没有稳定的 text/desc（实测 40.8.0 整个节点树里搜不到「点赞」按钮），
     * 所以点赞只能走双击。好处不止是能定位：双击本来就是真人的主要点赞方式，
     * 比「精准点某个图标」更不像脚本。
     *
     * 两次点击之间必须留出间隔：`GestureDescription` 里同时进行的两个 stroke 会被系统
     * 当成**双指**手势（缩放/双指点击），根本不是双击。所以 gap 一定要大于第一下的按压时长。
     */
    suspend fun doubleTap(point: Point): Boolean {
        val firstDuration = humanizer.tapDurationMs()
        val gap = humanizer.longIn(
            maxOf(firstDuration + 20, DOUBLE_TAP_GAP_MIN_MS),
            DOUBLE_TAP_GAP_MAX_MS
        )
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, firstDuration))
            .addStroke(GestureDescription.StrokeDescription(path, gap, humanizer.tapDurationMs()))
            .build()
        val ok = dispatch(gesture)
        LogStore.d(TAG, "双击 @ ($point) 间隔 ${gap}ms → $ok")
        return ok
    }

    /**
     * 按坐标点一下。
     *
     * 养号场景点的是**快照里量出来的矩形**（推荐流的点赞/收藏按钮），手边没有
     * `AccessibilityNodeInfo`：快照刻意只存 [NodeFacts]，不在缓存里持节点引用——
     * 那个引用随时会失效，拿着它点等于盲点。
     *
     * ⚠️ 这条路径**绕过了 [tapNode] 的付费守卫**（没有节点就念不出文案）。
     * 所以调用方必须自己保证落点是「已从 desc 确认过语义的按钮」，不许拿它点未知区域。
     */
    suspend fun tapPoint(point: Point, label: String = "坐标点击"): Boolean {
        val ok = dispatchTap(point)
        LogStore.d(TAG, "$label @ ($point) → $ok")
        return ok
    }

    /**
     * 按**快照里量出来的矩形**点一下，取点仍走 [Humanizer.pointIn] 的随机区间。
     *
     * 存在的理由：像「99+条新消息」浮标这种目标，手边只有 [NodeFacts]，
     * 而 `collect + tapNode` 要再走一趟全树（实测 1.4s），`tapPoint` 又固定点正中
     * （每个浮标都点几何中心，是很明显的机器特征）。这里把两者拼起来：
     * 矩形来自刚抓的快照，落点在矩形内随机，与 [tapNode] 共用同一条手势通道。
     *
     * ⚠️ 同 [tapPoint]，这条路径**绕过了付费守卫**——调用方必须已经从快照文案确认过语义。
     */
    suspend fun tapRect(rect: Rect, label: String = "矩形点击"): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) {
            LogStore.w(TAG, "矩形无有效尺寸，拒绝点击：$label")
            return false
        }
        val point = humanizer.pointIn(rect)
        val ok = dispatchTap(point)
        LogStore.d(TAG, "$label @ ($point) 矩形=[${rect.left},${rect.top}][${rect.right},${rect.bottom}] → $ok")
        return ok
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

    /**
     * 在指定区域内找一个**可滚动容器**。
     *
     * 存在的意义：人少的直播间不会浮「N条新消息」浮标（抖音要攒够几条才浮），
     * 想让列表往最新方向再走一段就只能自己滚。而**绝对不能用整屏手势滑**——
     * 抖音直播间的整页是竖滑 ViewPager，「手指从下往上」直接切到下一个直播间
     * （docs/M2 §12.2 记着 550px 就切房）。用无障碍的 SCROLL 动作作用在评论容器上，
     * 走的是控件内部滚动，不产生任何全局手势，因此不可能触发切房。
     *
     * 三条筛选，缺一不可（否则可能滚到 ViewPager 上）：
     * 1. `isScrollable`；
     * 2. 中心点落在 [area] 内（评论区范围内）；
     * 3. 高度不超过屏高的 [maxHeightRatio] —— 整页容器高度接近满屏，必须排除。
     */
    fun scrollableIn(area: Rect, maxHeightRatio: Float = 0.55f): AccessibilityNodeInfo? {
        val start = root() ?: return null
        val maxHeight = (screenHeight() * maxHeightRatio).toInt()
        return findBy(start) { node ->
            if (!node.isScrollable) return@findBy false
            val b = boundsOf(node)
            if (b.width() <= 0 || b.height() <= 0 || b.height() > maxHeight) return@findBy false
            area.contains(b.centerX(), b.centerY())
        }
    }

    /**
     * 让一个容器向内滚动一屏（末尾方向）。
     *
     * 走 `ACTION_SCROLL_FORWARD` 而不是手势：手势会落到全局 ViewPager 上切直播间，
     * 而 SCROLL 动作只发给目标控件，安全且可重复。
     *
     * @return true = 系统接受了动作（注意：**已滚到底时也可能返回 true 但内容不动**，
     *         所以调用方必须自己比对滚动前后的内容，不能只信返回值）
     */
    suspend fun scrollForward(node: AccessibilityNodeInfo, label: String): Boolean {
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        LogStore.d(TAG, "滚动 $label 向末尾 → $ok")
        if (ok) invalidateSnapshot()
        return ok
    }

    /** 多段折线滑动：段数与横向漂移都随机，避免出现笔直的机器轨迹 */
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {        val segments = humanizer.swipeSegments()
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
        val node = find(target) ?: run {
            LogStore.w(TAG, "未找到输入框 $target")
            return false
        }
        return typeText(node, text, target.toString()) { find(target) }
    }

    /**
     * 逐字输入到**已定位**的节点。
     *
     * [relocate] 用于节点中途失效时重新拿一次（直播间输入框会随评论面板刷新而重建）；
     * 传 null 表示无法重定位，失效即失败。
     *
     * 为什么单独开这个重载：直播间的输入框，文案「说点什么」命中的可能是提示 TextView，
     * 真正的 EditText 只能靠 `isEditable` 找（见 [findEditable]）。调用方自己定位好再传进来，
     * 比在这里猜「哪个才是输入框」可靠。
     */
    suspend fun typeText(
        node: AccessibilityNodeInfo,
        text: String,
        label: String,
        relocate: (() -> AccessibilityNodeInfo?)? = null
    ): Boolean {
        // 刻意声明为非空类型：循环里可能因界面刷新而重新定位，
        // 用可空 var 会让编译器在第二次迭代就失去非空智能转换（跨迭代赋值）。
        var current: AccessibilityNodeInfo = node
        // SET_TEXT 会改界面，逐字写完之后界面已不是快照里那副样子
        invalidateSnapshot()
        if (!current.isEditable) {
            LogStore.w(TAG, "$label 不是可编辑节点，仍尝试 SET_TEXT")
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
            var ok = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!ok) {
                // 节点可能因界面刷新而失效，重新定位一次再试
                val refreshed = relocate?.invoke()
                if (refreshed != null) {
                    current = refreshed
                    ok = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            if (!ok) {
                LogStore.e(TAG, "输入被拒绝，已输入「$accumulated」")
                return false
            }
            delay(humanizer.charDelayMs())
        }
        LogStore.d(TAG, "输入完成：\"$text\"（逐字 ${text.length} 次，目标 $label）")
        return true
    }

    // ==================== 其他 ====================

    fun back(): Boolean {
        invalidateSnapshot()
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

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        // 手势一定会改变界面，快照立刻作废，避免上层拿着旧界面做判断
        invalidateSnapshot()
        // 注入的触摸按坐标命中**最上层窗口**，而我们的悬浮窗就在最上层。
        // 面板展开（尤其再展开「已关人数」）会盖住评论行，2026-10-30 真机上就把
        // 面板底部的「关闭悬浮窗」按掉过一次。手势期间临时禁掉悬浮窗触摸，结束后立刻恢复。
        TouchGuard.suspendForGesture()
        try {
            return suspendCancellableCoroutine { cont ->
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
        } finally {
            TouchGuard.resumeAfterGesture()
        }
    }

    private fun textOf(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun descOf(node: AccessibilityNodeInfo): String? =
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "操作"

        /**
         * 单次遍历的节点上限。
         *
         * 800 曾经够用（直播间 647 个节点），但抖音「直播频道」预览页实测有 **973** 个节点，
         * 800 会在到达「点击进入直播间」这类深层节点之前就截断，表现为「明明屏幕上有这个按钮，
         * find 却返回 null」。按 900px 高的屏和 BFS 顺序，这个按钮的位置相当靠后。
         *
         * 提到 1200 的代价是大页面遍历慢一些（约 1.4s → 2s），只在非高频路径（进房、恢复）上付这笔钱。
         */
        const val MAX_NODES = 1200

        /** 遍历超过这个耗时就打警告（真机正常 <2s，播放中的视频页可能被系统节流到几十秒） */
        const val SLOW_SNAPSHOT_MS = 1_500L

        /** 滑动横向漂移相对纵向距离的比例 */
        const val DRIFT_RATIO = 0.03f

        /**
         * 点评论行时，纵向从行顶往下偏移的上限（px）。
         * 评论行高 32~117px 不等，取 centerY 会落进文字下方空白触发快捷操作栏；
         * 文字本身总在行顶附近，所以贴上部取点。24px 约等于一行小字号文字的中心。
         */
        const val TEXT_HIT_INSET = 24

        /** 双击两下之间的间隔（ms）。太短会被系统当成双指手势 */
        const val DOUBLE_TAP_GAP_MIN_MS = 150L
        const val DOUBLE_TAP_GAP_MAX_MS = 280L

        /**
         * 出现在控件的 text / content-desc 里就判定为「会花钱」。
         * 「小心心」是实测踩到的：抖音直播间 1 钻付费礼物，长得像点赞按钮。
         */
        val PAID_HINTS = listOf(
            "小心心", "支付", "充值", "购买", "打赏", "送礼",
            // 礼物面板里的按钮文案是「赠送」，实测选中一个 2999 钻的礼物后只剩这一颗按钮；
            // 漏掉它等于把「一键送出一个 2999 钻礼物」留在可点范围内。
            "赠送", "送礼物", "钻", "钱包", "余额"
        )

        /**
         * 快照缓存有效期（ms）。
         * 必须小于一个轮询周期（200ms~1s），否则风控弹窗会晚一步被发现。
         */
        const val SNAPSHOT_TTL_MS = 700L
    }
}
