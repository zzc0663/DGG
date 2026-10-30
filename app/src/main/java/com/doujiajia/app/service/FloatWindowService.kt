package com.doujiajia.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.doujiajia.app.R
import com.doujiajia.app.data.FollowedStore
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.data.RoomStore
import com.doujiajia.app.data.SettingsStore
import com.doujiajia.app.databinding.ViewFloatStatusBinding
import com.doujiajia.app.engine.EngineProgress
import com.doujiajia.app.engine.EngineState
import com.doujiajia.app.engine.TaskEngine
import com.doujiajia.app.engine.TouchGuard
import com.doujiajia.app.engine.withLiveTarget
import android.view.inputmethod.InputMethodManager
import com.doujiajia.app.ui.AppVisibility
import com.doujiajia.app.util.CurrentRoomAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮窗：收起态是一条窄胶囊，展开态是控制面板。
 *
 * 为什么要展开面板：任务跑起来之后用户的注意力在抖音上。回抖加加看进度等于把主播画面切走，
 * 切回来还可能掉出直播间；通知栏要点两下才看得到、还遮着状态栏。所以「看进度 / 暂停 / 结束」
 * 这些高频操作必须就地完成——**点面板上的按钮不跳任何界面**。
 *
 * 两条与早期版本不同的约定：
 *
 * 1. **任务到终态时不撤悬浮窗**，而是自动展开面板显示结论（风控停机 / 异常原因都在 message 里）。
 *    早期实现里前台服务 shutdown 会顺手 stopService 本服务，结果用户点「结束」后悬浮窗直接消失，
 *    什么结论都看不到；更糟的是有人会以为「被踢回应用了」。现在只有用户点「关闭悬浮窗」才撤。
 *
 * 2. **普通 Service 而不是前台服务**。它和 [ControlService] 在同一个进程里，
 *    进程被前台服务钉住了，本服务实例就不会被单独回收；再开一个前台服务只为了多一条通知，
 *    反而干扰用户。
 *
 * 3. **没有悬浮窗权限就直接退出**，绝不尝试「用别的窗口类型绕过」。
 *    `TYPE_APPLICATION_OVERLAY` 在 API 26+ 是唯一合法途径，`TYPE_PHONE` 之类在新系统上会被直接拒绝。
 */
class FloatWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var binding: ViewFloatStatusBinding? = null
    private var params: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var idleJob: Job? = null

    /** 窗口存续期间「盯设置」的轮询任务（见 [startSettingsWatch]） */
    private var syncJob: Job? = null

    /** 「关闭悬浮窗」二次确认的截止时刻（uptimeMillis），0 = 未处于确认态 */
    private var closeConfirmUntil = 0L
    private var closeConfirmJob: Job? = null

    /**
     * 窗口当前是否接收触摸。引擎注入手势期间会被临时置 false（见 [TouchGuard]），
     * 避免自动化点击落到我们自己的按钮上。
     */
    @Volatile
    private var touchable = true

    /** 编辑目标数字期间窗口需要临时可聚焦；与 [touchable] 是**两个独立的位**，不能互相覆盖 */
    @Volatile
    private var focusableForEdit = false

    /** 上一次 render 时用的目标数，用来发现设置被别处改过；-1 表示还没画过 */
    private var lastRenderedTarget = -1

    /** 只在见到过活动态之后才允许因终态做收尾，避免收到初始 IDLE 就自杀 */
    @Volatile
    private var sawActive = false

    /** 面板是否展开。收起态只有一个胶囊，展开态才有按钮 */
    @Volatile
    private var expanded = false

    /** 「因编辑目标而暂停」的标记：确定/收起时据此恢复任务 */
    @Volatile
    private var wasRunningBeforeEdit = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 引擎注入手势时会把本窗口临时设为不可触摸；hitTest 只用于日志取证，见 TouchGuard.covers
        TouchGuard.register(
            onSuspend = { setTouchable(false) },
            onResume = { setTouchable(true) },
            hitTest = { x, y -> coversPoint(x, y) }
        )
    }

    /** 屏幕坐标是否落在这个悬浮窗的当前矩形里 */
    private fun coversPoint(x: Int, y: Int): Boolean {
        val root = binding?.root ?: return false
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + root.width &&
            y >= loc[1] && y <= loc[1] + root.height
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            LogStore.w(TAG, "没有悬浮窗权限，不创建小窗（请先在「悬浮窗」一栏授权）")
            stopSelf()
            return START_NOT_STICKY
        }
        attach()
        observe()
        return START_NOT_STICKY
    }

    // ==================== 窗口 ====================

    private fun attach() {
        if (binding != null) return

        val b = ViewFloatStatusBinding.inflate(LayoutInflater.from(this))
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = (dm.heightPixels * 0.12f).toInt()
        }

        b.floatExpand.setOnClickListener { setExpanded(true) }
        b.floatCollapse.setOnClickListener { setExpanded(false) }
        b.floatToggle.setOnClickListener { onToggle() }
        b.floatFinish.setOnClickListener { onFinish() }
        b.floatClose.setOnClickListener { onCloseClicked() }
        b.floatReset.setOnClickListener { onResetToday() }
        b.floatSetTarget.setOnClickListener { onToggleTargetEditor() }
        b.floatTargetOk.setOnClickListener { onApplyTarget() }
        b.floatShowNames.setOnClickListener { onToggleNames() }
        b.floatSaveRoom.setOnClickListener { onSaveRoom() }
        b.floatPickRoom.setOnClickListener { onToggleRooms() }

        // 拖动只挂在胶囊和面板标题上：挂在整块 root 会让面板里的按钮在滑动时误吞事件
        val drag = DragListener()
        b.floatPill.setOnTouchListener(drag)
        b.floatHeader.setOnTouchListener(drag)

        val added = runCatching { windowManager.addView(b.root, lp) }
            .onFailure { LogStore.e(TAG, "悬浮窗创建失败：${it.message}") }
            .isSuccess
        if (!added) {
            stopSelf()
            return
        }

        binding = b
        params = lp
        instance = this
        setExpanded(false, animate = false)
        render(TaskEngine.progress.value)
        // 窗口在就要盯设置：胶囊/面板上的目标数必须跟着参数页走（见 startSettingsWatch）
        startSettingsWatch()
        LogStore.i(TAG, "悬浮窗已创建 @ (${lp.x}, ${lp.y})")
    }

    private fun setExpanded(value: Boolean, animate: Boolean = true) {
        val b = binding ?: return
        if (expanded == value && animate) return
        expanded = value
        b.floatPill.visibility = if (value) View.GONE else View.VISIBLE
        b.floatPanel.visibility = if (value) View.VISIBLE else View.GONE
        // 收起时把编辑区/名单一并关掉并归还焦点（否则挡住抖音的触摸与返回键）
        if (!value) {
            b.floatTargetBox.visibility = View.GONE
            b.floatNamesBox.visibility = View.GONE
            setWindowFocusable(false)
            // 二次确认态不跨「收起/展开」：收起来就等于放弃了这次关闭意图
            closeConfirmUntil = 0L
            closeConfirmJob?.cancel()
            closeConfirmJob = null
            b.floatClose.text = CLOSE_TEXT
        }
        // 展开时面板自带背景，root 的胶囊底色要撤掉，否则圆角会漏出一圈
        b.floatRoot.setBackgroundResource(if (value) 0 else R.drawable.bg_float_pill)
        if (animate) LogStore.d(TAG, if (value) "悬浮面板已展开" else "悬浮面板已收起")
    }

    /**
     * 拖动。手指位移超过 4dp 才判定为拖动，否则交给子控件处理点击，
     * 避免「想点按钮结果整块被拖走了」。
     */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            val root = binding?.root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > dp(4) || abs(dy) > dp(4))) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(root, lp) }
                            .onFailure { LogStore.w(TAG, "悬浮窗拖动失败：${it.message}") }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return dragging
            }
            return false
        }
    }

    // ==================== 按钮 ====================

    private fun onToggle() {
        when (TaskEngine.progress.value.state) {
            EngineState.RUNNING -> {
                LogStore.i(TAG, "悬浮窗「暂停」被点击")
                TaskEngine.pause()
            }

            // 手动暂停与自愈暂停共用同一个 resume：等待恢复期间用户也可以直接接管
            EngineState.PAUSED, EngineState.PAUSED_WAIT -> {
                LogStore.i(TAG, "悬浮窗「继续」被点击（当前 ${TaskEngine.progress.value.state.label}）")
                TaskEngine.resume()
            }

            // 异常终止后给一次「重试」：不用回 App 重新点「开始服务」，也不用重新进直播间。
            // **风控停机不提供这个入口**（枚举里根本没列它）——命中风控必须由人来判断，
            // 自动重试是最容易被判成机器行为的操作。
            EngineState.ERROR -> {
                LogStore.i(TAG, "悬浮窗「重试」被点击，用同一流程重新开始")
                val result = EngineController.restartLast(this)
                if (result.isFailure) {
                    LogStore.w(TAG, "重试失败：${result.exceptionOrNull()?.message}")
                }
            }

            else -> LogStore.d(TAG, "当前无运行任务，忽略暂停/继续")
        }
    }

    /**
     * 「结束」只停引擎，**不撤悬浮窗、不拉起任何界面**。
     *
     * 早期实现是 `ControlService.stop()` + `stopSelf()`：悬浮窗立刻消失，
     * 用户既看不到「为什么停的」，也会因为画面突然变化而以为被踢回应用了。
     * 现在终态后面板自动展开显示结论，由用户自己点「关闭悬浮窗」撤掉。
     */
    private fun onFinish() {
        val state = TaskEngine.progress.value.state
        if (state.isActive) {
            LogStore.i(TAG, "悬浮窗「结束」被点击（当前 ${state.label}）")
            TaskEngine.stop("悬浮窗结束")
        } else {
            LogStore.i(TAG, "任务已是终态（${state.label}），关闭悬浮窗")
            stopSelf()
        }
    }

    /**
     * 「关闭悬浮窗」。**任务还在跑时要求二次确认**。
     *
     * 为什么必须加确认：关掉之后**没有任何入口能把它叫回来**——只能回 App 里停止再重新开始。
     * 2026-10-30 真机上就丢了：23:08:53 用户展开「已关人数」（面板被撑高到评论行区域），
     * 23:09:01 这颗整行宽的按钮被触发，悬浮窗消失，而引擎还在跑，
     * 之后 3 分钟用户只能回 App 里点暂停/继续（日志 `R-20261030-80BF03`）。
     * 触发它的可能是手指，也可能是引擎注入的点击（`TouchGuard` 已另外堵住后者）——
     * 两条路都要挡，因为「关掉就找不回来」这个代价太大。
     */
    private fun onCloseClicked() {
        val b = binding ?: return
        val running = TaskEngine.progress.value.state.isActive
        val now = SystemClock.uptimeMillis()
        if (running && now > closeConfirmUntil) {
            closeConfirmUntil = now + CLOSE_CONFIRM_WINDOW_MS
            b.floatClose.text = CLOSE_CONFIRM_TEXT
            LogStore.i(TAG, "任务运行中：关闭悬浮窗需要再点一次确认")
            closeConfirmJob?.cancel()
            closeConfirmJob = scope.launch {
                delay(CLOSE_CONFIRM_WINDOW_MS)
                closeConfirmUntil = 0L
                binding?.floatClose?.text = CLOSE_TEXT
            }
            return
        }
        LogStore.i(TAG, "用户关闭悬浮窗（任务${if (running) "仍在运行" else "已结束"}）")
        stopSelf()
    }

    private fun onResetToday() {
        val store = FollowedStore.get(this)
        store.resetToday()
        LogStore.i(TAG, "今日累计已手动重置（名单不清空，仍是 ${store.knownCount()} 人）")
        render(TaskEngine.progress.value)
    }

    // ==================== 设置关注 ====================

    /**
     * 切换「设置关注」输入行。
     *
     * 悬浮窗平时是 [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]，EditText 拿不到焦点、
     * 键盘也弹不出来；展开输入行期间必须临时把窗口切成可聚焦——代价是编辑期间
     * 返回键/触摸会先给悬浮窗，所以收起时务必切回去。
     *
     * 另一个代价必须知道：窗口抢焦点后无障碍视角里抖音就「不 active」了，
     * 正在跑的任务会读不到界面（实测表现为页面判定 UNKNOWN → 连续 4 轮采不到评论 → 中止）。
     * 所以展开时**自动暂停任务**，确定/收起时再恢复——用户在打字的这几秒，脚本本来也不该乱动。
     */
    private fun onToggleTargetEditor() {
        val b = binding ?: return
        val show = b.floatTargetBox.visibility != View.VISIBLE
        b.floatTargetBox.visibility = if (show) View.VISIBLE else View.GONE
        b.floatNamesBox.visibility = View.GONE
        setWindowFocusable(show)
        if (show) {
            // 见上：编辑期间任务必须停住，否则读不到界面会被当成异常停机
            if (TaskEngine.progress.value.state.isActive) {
                wasRunningBeforeEdit = true
                TaskEngine.pause()
                LogStore.i(TAG, "展开目标编辑，任务自动暂停")
            }
            b.floatTargetInput.setText(
                SettingsStore.get(this).current.followTarget.toString()
            )
            b.floatTargetInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            runCatching { imm.showSoftInput(b.floatTargetInput, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            resumeIfPausedByEdit()
        }
    }

    /** 编辑结束（确定/收起）后把「因编辑而暂停」的任务恢复回去 */
    private fun resumeIfPausedByEdit() {
        if (!wasRunningBeforeEdit) return
        wasRunningBeforeEdit = false
        if (TaskEngine.progress.value.state == EngineState.PAUSED) {
            TaskEngine.resume()
            LogStore.i(TAG, "目标编辑结束，任务自动恢复")
        }
    }

    // ==================== 收藏 / 选择直播间 ====================

    /**
     * 收藏当前直播间。
     *
     * 取的是直播间左上角的主播昵称（见 [CurrentRoomAnchor]）。
     * 关键细节：**面板展开着的时候抖音窗口不是 active**，系统只给裁剪后的节点树，
     * 昵称根本读不到（实测 root 只剩 2 层）。所以必须先把面板收起、等窗口
     * 恢复 active 再读，读完重新展开——用户看到的是面板闪一下。
     */
    private fun onSaveRoom() {
        scope.launch {
            val wasExpanded = expanded
            // 优先用 Flow 每轮记住的主播昵称（面板展开时实时树读不到，见 RoomStore.lastAnchor）；
            // 任务没在跑才走实时读（此时先把面板收起让抖音窗口恢复 active）
            val name = RoomStore.get(this@FloatWindowService).lastAnchor
                ?: run {
                    if (wasExpanded) setExpanded(false)
                    delay(600)
                    CurrentRoomAnchor.currentAnchorName(DggAccessibilityService.current)
                }
            if (name == null) {
                LogStore.w(TAG, "收藏直播间失败：当前读不到主播昵称（可能不在直播间）")
            } else {
                val added = RoomStore.get(this@FloatWindowService).add(name)
                if (added) {
                    LogStore.i(TAG, "已收藏直播间「$name」（共 ${RoomStore.get(this@FloatWindowService).all().size} 个）")
                } else {
                    LogStore.d(TAG, "「$name」已在收藏里")
                }
            }
            if (wasExpanded) {
                setExpanded(true)
                render(TaskEngine.progress.value)
            }
        }
    }

    /**
     * 展开/收起收藏列表。每个收藏项一行，点它 = 「选择这个直播间」：
     * 写入 pending 目标，下次点「开始服务」时脚本先自动进这个房间再开始干活。
     */
    private fun onToggleRooms() {
        val b = binding ?: return
        val show = b.floatRoomsBox.visibility != View.VISIBLE
        b.floatRoomsBox.visibility = if (show) View.VISIBLE else View.GONE
        b.floatNamesBox.visibility = View.GONE
        b.floatTargetBox.visibility = View.GONE
        if (!show) return

        val rooms = RoomStore.get(this).all()
        val list = b.floatRoomsList
        list.removeAllViews()
        if (rooms.isEmpty()) {
            list.addView(newRoomRow("还没有收藏的直播间。进入某个直播间后点「收藏直播间」"))
            return
        }
        val pending = RoomStore.get(this).takePendingTarget()
        if (pending != null) RoomStore.get(this).setPendingTarget(pending) // 读出来再放回去，只做展示

        rooms.forEach { room ->
            val mark = if (room.nickname == pending) " ✓已选择" else ""
            list.addView(
                newRoomRow("${room.nickname}$mark").apply {
                    setOnClickListener {
                        RoomStore.get(this@FloatWindowService).setPendingTarget(room.nickname)
                        LogStore.i(
                            TAG,
                            "已选择直播间「${room.nickname}」，下次点「开始服务」时自动进入"
                        )
                        // 重绘一遍列表把 ✓ 标记挪到新选中的行
                        onToggleRooms()
                        onToggleRooms()
                    }
                }
            )
        }
    }

    private fun newRoomRow(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@FloatWindowService, R.color.text_primary))
            textSize = 11f
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

    private fun onApplyTarget() {
        val b = binding ?: return
        val value = b.floatTargetInput.text?.toString()?.trim()?.toIntOrNull()
        if (value == null || value < 1 || value > 10_000) {
            b.floatTargetInput.error = "1 ~ 10000"
            return
        }
        SettingsStore.get(this).edit { it.copy(followTarget = value) }
        LogStore.i(TAG, "目标关注数量已改为 $value（悬浮面板实时修改，下一轮生效）")
        b.floatTargetBox.visibility = View.GONE
        setWindowFocusable(false)
        resumeIfPausedByEdit()
        render(TaskEngine.progress.value)
    }

    // ==================== 已关人数 ====================

    private fun onToggleNames() {
        val b = binding ?: return
        val show = b.floatNamesBox.visibility != View.VISIBLE
        b.floatNamesBox.visibility = if (show) View.VISIBLE else View.GONE
        b.floatTargetBox.visibility = View.GONE
        setWindowFocusable(false)
        if (show) {
            val store = FollowedStore.get(this)
            val names = store.allNames()
            val already = store.alreadyNames()
            b.floatNamesList.text = if (names.isEmpty() && already.isEmpty()) {
                "还没有关注过任何人"
            } else {
                buildString {
                    names.take(MAX_NAMES_SHOWN).forEachIndexed { i, n ->
                        if (i > 0) append('\n')
                        append("${i + 1}. $n")
                    }
                    if (names.size > MAX_NAMES_SHOWN) {
                        append("\n…… 共 ${names.size} 人")
                    }
                    // 下面这批**不是我们关注的**：打开资料卡时按钮已经是「取消关注」。
                    // 不分开列的话，用户会把它们算进「已关」里，数字就对不上手机上的实际关注数
                    // （2026-10-30 真机反馈的正是这个）。
                    if (already.isNotEmpty()) {
                        if (names.isNotEmpty()) append("\n──────────")
                        append("\n以下 ${already.size} 人打开时已关注，未重复关注：")
                        already.take(MAX_NAMES_SHOWN).forEach { append("\n· $it") }
                        if (already.size > MAX_NAMES_SHOWN) append("\n…… 共 ${already.size} 人")
                    }
                }
            }
            LogStore.d(TAG, "展开已关名单：本机关注 ${names.size} 人、原已关注 ${already.size} 人")
        }
    }

    /** 编辑输入框期间临时把窗口切成可聚焦；收起必须恢复，否则会挡住抖音的触摸与返回键 */
    private fun setWindowFocusable(focusable: Boolean) {
        if (focusableForEdit == focusable) return
        focusableForEdit = focusable
        applyWindowFlags()
    }

    /**
     * 引擎注入手势期间禁掉触摸。
     *
     * 只动 [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] 这一位，
     * 聚焦位由 [focusableForEdit] 单独管——两者混在一起写会互相覆盖
     * （早期 `setWindowFocusable` 直接 `lp.flags = want`，加上触摸位后就会把对方清掉）。
     */
    private fun setTouchable(value: Boolean) {
        if (touchable == value) return
        touchable = value
        applyWindowFlags()
    }

    private fun applyWindowFlags() {
        val lp = params ?: return
        val root = binding?.root ?: return
        var flags = if (focusableForEdit) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.flags == flags) return
        lp.flags = flags
        runCatching { windowManager.updateViewLayout(root, lp) }
            .onFailure { LogStore.w(TAG, "更新窗口属性失败：${it.message}") }
    }

    // ==================== 状态跟随 ====================

    private fun observe() {
        if (job != null) return
        job = scope.launch {
            var lastState = EngineState.IDLE
            TaskEngine.progress.collect { progress ->
                if (progress.state.isActive) sawActive = true

                // 新的一轮任务开始时把面板收回去：终态那一轮是展开着给人看结论的，
                // 不收起来会一直挡住抖音画面。
                if (progress.state.isActive && !lastState.isActive && expanded) setExpanded(false)
                lastState = progress.state

                render(progress)

                if (sawActive && !progress.state.isActive) {
                    // 终态：保留悬浮窗并把结论摊开给人看，绝不自动消失。
                    // 但**抖加加自己在前台时不展开**：面板纵向约 405~1485，正好盖住参数区，
                    // 用户想改参数还得先点 ⌃ 收起（2026-10-29 真机反馈）。
                    // 结论在主界面底部状态条里本来就有，不需要再挡一遍。
                    if (AppVisibility.isUiForeground) {
                        setExpanded(false)
                        LogStore.i(
                            TAG,
                            "任务已到终态（${progress.state.label}），App 在前台，不展开面板（状态条已有结论）"
                        )
                    } else {
                        setExpanded(true)
                        LogStore.i(TAG, "任务已到终态（${progress.state.label}），面板保留待用户关闭")
                    }
                }
            }
        }
        // 兜底：引擎压根没起来（前台服务 watchdog 会回收），别留一个永远「准备中」的空胶囊
        idleJob = scope.launch {
            delay(IDLE_SELF_CLOSE_MS)
            if (!sawActive && !TaskEngine.isRunning) {
                LogStore.i(TAG, "任务从未进入活动态，自动收起悬浮窗")
                stopSelf()
            }
        }
    }

    private fun render(progress: EngineProgress) {
        val b = binding ?: return

        val dot = when (progress.state) {
            EngineState.RUNNING -> R.drawable.bg_dot_green
            EngineState.PAUSED, EngineState.PAUSED_WAIT -> R.drawable.bg_dot_amber
            else -> R.drawable.bg_dot_red
        }
        b.floatDot.setBackgroundResource(dot)
        b.floatDotPanel.setBackgroundResource(dot)

        // 分母取**用户当前设置**，不取 progress.target。
        // progress.target 是流程启动时（以及上一轮 publish 时）冻结的快照：用户在面板上
        // 「设置关注」改了目标数，这个字段不会跟着变，界面上就是「输入人数后毫无变化」
        // （2026-10-29 真机复现：改成 300、点确定，大号计数仍是 0/200）。
        // target == 0 是流程**明确声明「没有总量目标」**（直播互动、养号都这么发），
        // 这时只能显示纯计数，不能拿关注目标凑个分母。
        val current = SettingsStore.get(this).current
        val live = progress.withLiveTarget(current.followTarget)
        val target = live.target

        b.floatStatus.text = shortStatus(live, target)
        b.floatState.text = progress.state.label
        // 按钮上直接带出当前目标值：即使计数还没刷新，用户也能立刻确认改动进去了
        b.floatSetTarget.text = if (target > 0) "设置 $target" else "设置关注"

        val label = progress.counterLabel.ifBlank { "已关注" }
        b.floatCounterLabel.text = label
        b.floatCounter.text = when {
            target > 0 -> "${progress.followed}/$target"
            progress.followed > 0 -> "${progress.followed}"
            else -> "—"
        }
        b.floatMessage.text = progress.message.ifBlank {
            if (progress.state.isActive) "" else "任务已结束"
        }

        val today = FollowedStore.get(this).todayCount()
        val store = FollowedStore.get(this)
        b.floatSession.text = "本场 ${progress.followed}"
        // 养号流程会带出点赞/收藏数（关注流程一直是 0）：有数就把这两格换掉，
        // 「今日 X」「已关 Y」对养号没有意义，继续显示反而让人以为脚本在关注别人。
        val interacting = progress.liked > 0 || progress.collected > 0
        b.floatToday.text = if (interacting) "赞 ${progress.liked}" else "今日 $today"
        b.floatKnown.text = if (interacting) "藏 ${progress.collected}" else "已关 ${store.knownCount()}"

        val active = progress.state.isActive
        // 三种暂停（手动 / 自愈等待）都显示「继续」；异常终止给「重试」；
        // 风控停机**不给任何恢复入口**，只留「关闭」。
        val canRetry = progress.state == EngineState.ERROR
        b.floatToggle.visibility = if (active || canRetry) View.VISIBLE else View.GONE
        b.floatToggle.text = when (progress.state) {
            EngineState.PAUSED, EngineState.PAUSED_WAIT -> "继续"
            EngineState.ERROR -> "重试"
            else -> "暂停"
        }
        b.floatFinish.text = if (active) "结束" else "关闭"

        lastRenderedTarget = target
    }

    /**
     * 窗口存续期间盯一眼设置。
     *
     * 「目标关注数量」在三个地方都能改：悬浮面板、主界面参数、以及下次启动时读设置。
     * 本服务拿不到 SharedPreferences 的变更通知，不主动对一次就会出现
     * 「面板上还写着 300、参数页里已经是 150」的互相打脸。
     *
     * 收起态也要盯：胶囊上就挂着 `状态 0/目标数`，那是用户唯一能看到的进度，
     * 显示旧值同样是在骗人。读的是 SharedPreferences 的内存缓存，1 秒一次的开销可以忽略，
     * 且只有值真的变了才重画，不会白刷 TextView。
     */
    private fun startSettingsWatch() {
        if (syncJob != null) return
        syncJob = scope.launch {
            while (true) {
                delay(SETTINGS_WATCH_MS)
                val p = TaskEngine.progress.value
                val shown = if (p.target > 0) {
                    SettingsStore.get(this@FloatWindowService).current.followTarget
                } else {
                    0
                }
                if (shown != lastRenderedTarget) {
                    LogStore.d(TAG, "检测到目标数在别处被改为 $shown，刷新悬浮窗")
                    render(p)
                }
            }
        }
    }

    private fun stopSettingsWatch() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * 胶囊很窄，只放最关键的三段：状态、进度。
     *
     * [target] 由 [render] 从当前设置传进来（不是 `progress.target`，理由见 render 里的注释）。
     */
    private fun shortStatus(progress: EngineProgress, target: Int): String = buildString {
        append(progress.state.label)
        if (target > 0) append(" ${progress.followed}/$target")
        else if (progress.followed > 0) append(" ${progress.followed}")
        if (progress.state == EngineState.RUNNING && progress.stepTotal > 0) {
            append(" · ${progress.stepIndex}/${progress.stepTotal}")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        job?.cancel()
        job = null
        idleJob?.cancel()
        idleJob = null
        closeConfirmJob?.cancel()
        closeConfirmJob = null
        stopSettingsWatch()
        scope.cancel()
        // 必须先注销：本服务死了，TouchGuard 不能再对着一个没有窗口的实例调开关
        TouchGuard.unregister()
        binding?.let { b -> runCatching { windowManager.removeView(b.root) } }
        binding = null
        params = null
        if (instance === this) instance = null
        LogStore.i(TAG, "悬浮窗已移除")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "悬浮窗"

        /**
         * 当前实例。仅用于**同进程**的一声招呼：抖加加界面回到前台时把面板收起来。
         * 不加锁、不做消息通道——服务与 Activity 必然同进程，一句引用最省事。
         */
        @Volatile
        private var instance: FloatWindowService? = null

        /**
         * App 回到前台时调用：把还展开着的面板收起来。
         *
         * 终态已经不在前台时展开了面板（那是给人在抖音里看结论用的），
         * 用户切回抖加加后它正好压在参数区上——这里补上「回来就收起」，
         * 与 observe 里「前台不展开」构成双保险。
         */
        fun collapseIfExpanded() {
            val service = instance ?: return
            if (!service.expanded) return
            LogStore.d(TAG, "App 回到前台，自动收起悬浮面板（避免盖住参数区）")
            service.setExpanded(false)
        }

        /** 引擎从未启动时，悬浮窗最多空挂这么久 */
        private const val IDLE_SELF_CLOSE_MS = 5_000L

        /** 「已关人数」名单最多展示条数（全量在日志里） */
        private const val MAX_NAMES_SHOWN = 40

        private const val CLOSE_TEXT = "关闭悬浮窗"
        private const val CLOSE_CONFIRM_TEXT = "再点一次：关闭后只能回 App 停止"

        /** 运行中关闭的二次确认窗口：超过这个时间没再点就撤销，避免长时间停在「一碰就关」的状态 */
        private const val CLOSE_CONFIRM_WINDOW_MS = 4_000L

        /** 悬浮窗「盯设置」的轮询间隔。只在值真的变了才重画，所以 1 秒一次几乎没有代价 */
        private const val SETTINGS_WATCH_MS = 1_000L

        fun start(context: Context) {
            val intent = Intent(context, FloatWindowService::class.java)
            runCatching { context.startService(intent) }
                .onFailure { LogStore.w(TAG, "悬浮窗启动失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatWindowService::class.java)) }
        }
    }
}
