package com.example.dgg.ui

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.dgg.R
import com.example.dgg.data.LogStore
import com.example.dgg.data.PermissionGuard
import com.example.dgg.data.PresetTags
import com.example.dgg.data.Settings
import com.example.dgg.data.SettingsStore
import com.example.dgg.data.license.LicenseProvider
import com.example.dgg.data.license.LicenseStatus
import com.example.dgg.data.log.LogUploader
import com.example.dgg.data.log.LogUploadReceipt
import com.example.dgg.data.log.LogUploadState
import com.example.dgg.data.log.RemoteLogUploadProvider
import com.example.dgg.data.license.RemoteLicenseProvider
import com.example.dgg.data.ota.OtaInstaller
import com.example.dgg.data.ota.OtaManager
import com.example.dgg.data.ota.OtaManifest
import com.example.dgg.data.ota.OtaState
import com.example.dgg.data.ota.RemoteOtaProvider
import com.example.dgg.databinding.ActivityMainBinding
import com.example.dgg.databinding.ContentAutoFollowBinding
import com.example.dgg.engine.Actions
import com.example.dgg.engine.EngineProgress
import com.example.dgg.engine.EngineState
import com.example.dgg.engine.Flow
import com.example.dgg.engine.LocatorConfigLoader
import com.example.dgg.engine.NurtureTags
import com.example.dgg.engine.PageDetector
import com.example.dgg.engine.SmokeFlow
import com.example.dgg.engine.TaskEngine
import com.example.dgg.engine.withLiveTarget
import com.example.dgg.engine.flow.FollowFromLiveFlow
import com.example.dgg.engine.flow.LiveInteractFlow
import com.example.dgg.engine.flow.NurtureFlow
import com.example.dgg.engine.risk.Humanizer
import com.example.dgg.service.DebugChannel
import com.example.dgg.service.OtaService
import com.example.dgg.service.DggAccessibilityService
import com.example.dgg.service.EngineController
import com.example.dgg.service.FloatWindowService
import com.example.dgg.util.AppPermissions
import com.example.dgg.util.DouyinLauncher
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：三个页签 + 参数双向绑定 + 权限状态实时渲染 + 引擎状态跟随。
 *
 * 「开始服务」只有一个按钮，按**当前页签**分发流程（M3 起）：
 * - 自动关注 → [FollowFromLiveFlow]：读直播间评论 → 随机挑人 → 关注（M2）
 * - 养号助手 → 尚未实现，明确拒绝启动（M4）
 * - 直播互动 → [LiveInteractFlow]：随机间隔发评论 / 自动点赞 / 申请连麦（M3）
 *
 * 两条流程都要求用户自己先进好直播间：脚本不搜索、不找直播间、不切直播间。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    /** 正式授权：服务端激活 + 启动握手；在 onCreate 里用 Activity Context 初始化 */
    private lateinit var license: LicenseProvider
    private var activationDialog: AlertDialog? = null
    private var updateDialog: AlertDialog? = null
    private var lastPromptedVersion = 0
    private var lastOtaCheck = 0L

    /** 权限恢复向导的待办队列（无障碍 → 悬浮窗 → 通知），空表示当前没有流程在跑 */
    private val pendingRestore = ArrayDeque<PermissionGuard.Kind>()

    /** 本次进程内只提醒一次，避免每次 onResume 都弹 */
    private var permissionLossWarned = false

    private lateinit var tabs: List<TextView>
    private lateinit var indicators: List<View>
    private lateinit var pages: List<View>

    /**
     * 当前页签下标。
     *
     * 「开始服务」是三个页签共用的一个按钮，所以它必须知道自己在替哪个页签启动流程。
     * 之前没记这个值，结果是无论在哪个页签点开始，跑的都是直播间关注——
     * 用户在「直播互动」页填了评论参数、点开始，实际执行的是关注别人，属于最坏的一类错配。
     */
    private var currentTab = 0

    /** 收集引擎状态用的作用域；Activity 销毁时整体取消，避免把 binding 泄漏给常驻的单例 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null

    private val modeDescriptions = listOf(
        "刷视频养号：自己上下滑推荐流，按随机节奏观看，命中标签时概率点赞（不会涨粉，也不关注任何人）",
        "垂直养号：按行业关键词持续互动，账号垂直度更高（暂未开放）",
        "首页连播：只刷视频不做任何互动，最保守的挂时长方式"
    )

    private val commentModeDescriptions = listOf(
        "启动后将自动复制直播间中的最新评论并发送",
        "使用预设好的评论内容，按发送间隔轮换发出"
    )

    /**
     * API 33+ POST_NOTIFICATIONS 是运行时权限，不申请则通知一条都发不出来，
     * 前台服务的「常驻可见」承诺也就无从谈起。这里做运行时申请，申请不到再引导去设置页。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            LogStore.i(TAG, "通知权限申请结果：${if (granted) "已授予" else "被拒绝"}")
            refreshPermissionState()
            if (!granted) toast("通知被拒绝，可稍后在系统设置中手动开启")
            // 恢复向导里通知是最后一项；它走的是系统权限弹窗（不是 Activity），
            // 所以推进队列必须挂在这个回调上，onResume 不一定触发
            if (pendingRestore.isNotEmpty()) advanceRestore(skipCurrent = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = SettingsStore.get(this)
        license = RemoteLicenseProvider(this)
        LogStore.i(TAG, "主界面创建，参数已加载（目标=${store.current.followTarget}，日上限=${store.current.dailyLimit}）")

        // 日志上报要求日志必须落盘（release 也要），DebugChannel 只在 debug 包 attach 过
        LogStore.attachFileSink(File(filesDir, LOG_FILE_NAME))

        // 调试通道的另一入口：服务连接回调在 HyperOS 上不保证触发，界面创建是可靠的兜底
        DebugChannel.startIfNeeded(this)

        setupTabs()
        setupPermissionCards()
        bindAllSettings()
        setupActions()
        setupLicense()
        setupOta()
        setupLogUpload()
        setupEngineObserver()
    }

    override fun onResume() {
        super.onResume()
        // 抖关关在前台：任务到终态时悬浮面板不再自动展开（见 FloatWindowService.observe），
        // 同时把「在抖音里展开着看结论」的那块面板收起来，别挡参数区。
        AppVisibility.onResume()
        FloatWindowService.collapseIfExpanded()
        // 从系统设置页返回后必须重新读一次，否则用户授权了却仍显示未授权
        val state = refreshPermissionState()
        // 悬浮面板上也能改「目标关注数量 / 每日上限」，而本界面的输入框只在 onCreate 绑定一次。
        // 不同步的话两个数字会打架：用户从抖音回来看到旧值，点一下再失焦，
        // bindRangedIntField 的失焦回调还会把旧值写回去，把刚才在面板上设的值覆盖掉。
        syncEditableSettings()
        // 状态条的分母同样依赖设置值，设置变了要重画一次（collect 只在引擎状态变化时触发）
        renderEngine(TaskEngine.progress.value)
        // 恢复向导：刚从设置页回来 → 推进到下一项（每项只跳一次，避免在设置页之间来回打转）
        if (pendingRestore.isNotEmpty()) {
            advanceRestore(skipCurrent = true)
            return
        }
        // 权限被升级/重装/系统清理重置时，主动喊一声——这几个权限没了应用是静默残废的
        warnIfPermissionsLost(state)
    }

    /**
     * 把界面输入框回填成 [SettingsStore] 的当前值。
     *
     * 覆盖的是「可能在别处被改」的数字项：目标关注数量、每日上限（悬浮面板的「设置关注」改前者）。
     * 只回填**没有焦点且内容不同**的框——用户正在某个框里打字时不能抢他的输入。
     */
    private fun syncEditableSettings() {
        val s = store.current
        syncIntField(binding.contentAuto.etFollowTarget, s.followTarget)
        syncIntField(binding.contentAuto.etDailyLimit, s.dailyLimit)
    }

    private fun syncIntField(edit: EditText, value: Int) {
        if (edit.hasFocus()) return
        if (edit.text?.toString()?.toIntOrNull() == value) return
        edit.setText(value.toString())
        edit.setSelection(edit.text?.length ?: 0)
    }

    override fun onPause() {
        super.onPause()
        // 只有确认「界面不在前台」之后，终态面板才允许自动展开（见 FloatWindowService.observe）
        AppVisibility.onPause()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        progressJob = null
        activationDialog?.dismiss()
        updateDialog?.dismiss()
        uiScope.cancel()
        super.onDestroy()
    }

    // ==================== 页签 ====================

    private fun setupTabs() {
        tabs = listOf(binding.tabAuto, binding.tabNurture, binding.tabLive)
        indicators = listOf(binding.indicatorAuto, binding.indicatorNurture, binding.indicatorLive)
        pages = listOf(binding.contentAuto.root, binding.contentNurture.root, binding.contentLive.root)

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectTab(index) }
        }
        selectTab(0, log = false)
    }

    private fun selectTab(index: Int, log: Boolean = true) {
        currentTab = index
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            tab.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.brand_red else R.color.text_primary
                )
            )
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            indicators[i].visibility = if (selected) View.VISIBLE else View.INVISIBLE
            pages[i].visibility = if (selected) View.VISIBLE else View.GONE
        }
        if (log) LogStore.d(TAG, "切换页签 → ${TAB_NAMES.getOrElse(index) { index.toString() }}")
    }

    // ==================== 权限卡片 ====================

    private fun setupPermissionCards() {
        val cards = listOf(
            binding.contentAuto.permCard,
            binding.contentNurture.permCard,
            binding.contentLive.permCard
        )
        cards.forEach { card ->
            card.onPermissionClick = { kind -> openPermission(kind) }
            card.onKeepAliveClick = {
                LogStore.i(TAG, "跳转「忽略电池优化」白名单设置")
                AppPermissions.openBatteryOptimizationSettings(this)
            }
        }
    }

    private fun refreshPermissionState(): AppPermissions.State {
        val state = AppPermissions.state(this)
        listOf(
            binding.contentAuto.permCard,
            binding.contentNurture.permCard,
            binding.contentLive.permCard
        ).forEach { it.render(state) }
        // 记住「曾经授予过」，升级/重装后丢权时才有依据提醒（见 PermissionGuard）
        PermissionGuard.remember(this, state)

        LogStore.d(
            TAG,
            "权限状态：无障碍=${state.accessibility} 悬浮窗=${state.overlay} " +
                "通知=${state.notification} 电池优化忽略=${state.batteryUnrestricted}"
        )
        return state
    }

    /**
     * 权限丢失提醒 + 逐项恢复向导。
     *
     * 为什么必须做成"常驻检查"而不是一次性提示：这三项权限丢掉之后应用不会崩，
     * 只会**静默失效**（点开始服务报「无障碍未开启」、悬浮窗不见了、通知栏没有进度）。
     * 用户很容易以为是自己操作错了，而真正的原因发生在几天前的某次升级。
     */
    private fun warnIfPermissionsLost(state: AppPermissions.State) {
        if (permissionLossWarned) return
        val lost = PermissionGuard.lost(this, state)
        if (lost.isEmpty()) return
        permissionLossWarned = true
        LogStore.w(
            TAG,
            "权限已失效：${lost.joinToString("、") { it.label }}（${PermissionGuard.describe(this)}）"
        )
        val message = buildString {
            append("下列权限曾经授予过，现在失效了（应用升级、重装、强制停止或系统清理都可能造成）：\n\n")
            lost.forEach { append("· ").append(it.label).append("：").append(it.why).append('\n') }
            append("\n不恢复的话，脚本启动后会直接失败或静默失效。")
        }
        AlertDialog.Builder(this)
            .setTitle("权限被重置了")
            .setMessage(message)
            .setPositiveButton("逐项恢复") { _, _ -> startRestoreQueue(lost) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startRestoreQueue(kinds: List<PermissionGuard.Kind>) {
        pendingRestore.clear()
        pendingRestore.addAll(kinds)
        advanceRestore(skipCurrent = false)
    }

    /**
     * 推进恢复队列。
     *
     * @param skipCurrent true 表示"刚跳过的那一项已经试过了"（从设置页返回/权限弹窗关闭时）——
     *   失败也不重跳，否则用户拒绝授权就会被无限弹回设置页。剩下的项用户可以点卡片单独处理。
     */
    private fun advanceRestore(skipCurrent: Boolean) {
        if (skipCurrent && pendingRestore.isNotEmpty()) pendingRestore.removeFirst()
        while (pendingRestore.isNotEmpty()) {
            val kind = pendingRestore.first()
            if (isPermissionGranted(kind)) {
                pendingRestore.removeFirst()
                continue
            }
            LogStore.i(TAG, "恢复向导：跳转「${kind.label}」")
            openPermission(
                when (kind) {
                    PermissionGuard.Kind.ACCESSIBILITY -> PermissionCardView.Kind.ACCESSIBILITY
                    PermissionGuard.Kind.OVERLAY -> PermissionCardView.Kind.OVERLAY
                    PermissionGuard.Kind.NOTIFICATION -> PermissionCardView.Kind.NOTIFICATION
                }
            )
            return
        }
        val state = refreshPermissionState()
        if (PermissionGuard.lost(this, state).isEmpty()) {
            LogStore.i(TAG, "权限恢复完成：无障碍/悬浮窗/通知三项齐全")
            toast("权限已全部恢复")
        } else {
            toast("还有权限未恢复，可在「权限设置」里单独授权")
        }
    }

    private fun isPermissionGranted(kind: PermissionGuard.Kind): Boolean {
        val state = AppPermissions.state(this)
        return when (kind) {
            PermissionGuard.Kind.ACCESSIBILITY -> state.accessibility
            PermissionGuard.Kind.OVERLAY -> state.overlay
            PermissionGuard.Kind.NOTIFICATION -> state.notification
        }
    }

    private fun openPermission(kind: PermissionCardView.Kind) {
        when (kind) {
            PermissionCardView.Kind.ACCESSIBILITY -> {
                LogStore.i(TAG, "跳转无障碍设置页")
                toast("请在列表中找到「抖关关 自动化」并开启")
                AppPermissions.openAccessibilitySettings(this)
            }

            PermissionCardView.Kind.OVERLAY -> {
                LogStore.i(TAG, "跳转悬浮窗权限设置页")
                AppPermissions.openOverlaySettings(this)
            }

            PermissionCardView.Kind.NOTIFICATION -> {
                val granted = AppPermissions.state(this).notification
                if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    LogStore.i(TAG, "申请运行时通知权限 POST_NOTIFICATIONS")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    LogStore.i(TAG, "跳转通知设置页")
                    AppPermissions.openNotificationSettings(this)
                }
            }
        }
    }

    // ==================== 参数双向绑定 ====================

    private fun bindAllSettings() {
        val s = store.current
        bindAutoFollow(s)
        bindNurture(s)
        bindLive(s)
    }

    private fun bindAutoFollow(s: Settings) {
        val c = binding.contentAuto

        bindSegmented(
            listOf(c.segManualCal, c.segAutoCal), s.calMode, grouped = true
        ) { index -> store.edit { it.copy(calMode = index) } }

        val followMin = Settings.FOLLOW_COUNT_MIN
        val followMax = Settings.FOLLOW_COUNT_MAX
        bindRangedIntField(c.etFollowTarget, s.followTarget, followMin, followMax) { v ->
            store.edit { it.copy(followTarget = v) }
            LogStore.i(TAG, "目标关注数量 = $v")
            // 状态条上的分母就是它：不在这里重画，改完的数字要等下一次引擎状态变化才出现，
            // 观感就是「改了没反应」（悬浮面板的「设置关注」同理，那边已改成直接读设置）
            renderEngine(TaskEngine.progress.value)
        }
        bindRangedIntField(c.etDailyLimit, s.dailyLimit, followMin, followMax) { v ->
            store.edit { it.copy(dailyLimit = v) }
            LogStore.i(TAG, "每日关注上限 = $v")
        }
        bindIntField(c.etJitter, s.jitterPercent) { v -> store.edit { it.copy(jitterPercent = v) } }

        // 扫描间隔 = 数值 + 单位，单位切换时按总时长换算
        bindScanInterval(c)

        bindSwitch(c.swIdleLive, s.idleLive) { v ->
            store.edit { it.copy(idleLive = v) }
            LogStore.i(TAG, "空闲时直播互动 = $v")
        }
        bindSwitch(c.swApplyMic, s.applyMic) { v -> store.edit { it.copy(applyMic = v) } }
        bindSwitch(c.swContinueAfterMic, s.continueAfterMic) { v ->
            store.edit { it.copy(continueAfterMic = v) }
        }
        bindSwitch(c.swIntervalRest, s.intervalRest) { v -> store.edit { it.copy(intervalRest = v) } }

        bindIntField(c.etRestAfterN, s.restAfterN) { v -> store.edit { it.copy(restAfterN = v) } }
        bindIntField(c.etRestSeconds, s.restSeconds) { v -> store.edit { it.copy(restSeconds = v) } }
    }

    private fun bindNurture(s: Settings) {
        val c = binding.contentNurture

        bindRangedIntField(
            c.etRunMinutes, s.runMinutes, Settings.RUN_MINUTES_MIN, Settings.RUN_MINUTES_MAX
        ) { v -> store.edit { it.copy(runMinutes = v) } }

        bindSegmented(
            listOf(c.segModeIdle, c.segModeVertical, c.segModeHome), s.nurtureMode, grouped = false
        ) { index ->
            store.edit { it.copy(nurtureMode = index) }
            c.tvModeDesc.text = modeDescriptions.getOrElse(index) { "" }
        }

        bindSegmented(
            listOf(c.segPaceSlow, c.segPaceNormal, c.segPaceFast), s.nurturePace, grouped = false
        ) { index ->
            store.edit { it.copy(nurturePace = index) }
        }

        bindSlider(c.sliderLike, c.tvPctLike, s.probLike) { v -> store.edit { it.copy(probLike = v) } }
        bindSlider(c.sliderCollect, c.tvPctCollect, s.probCollect) { v ->
            store.edit { it.copy(probCollect = v) }
        }
        bindSlider(c.sliderComment, c.tvPctComment, s.probComment) { v ->
            store.edit { it.copy(probComment = v) }
        }
        bindSlider(c.sliderShare, c.tvPctShare, s.probShare) { v -> store.edit { it.copy(probShare = v) } }

        renderTagSummary(s.nurtureTags)
        c.tvTagEdit.setOnClickListener { showTagEditor() }
    }

    /** 「文案标签加权」那一行只显示概况，具体编辑在弹窗里 */
    private fun renderTagSummary(raw: String) {
        val parsed = NurtureTags.parse(raw)
        binding.contentNurture.tvTagSummary.text = when {
            parsed.rules.isEmpty() -> "文案标签加权（未配置）"
            else -> "文案标签加权（${parsed.rules.size} 条，最高 +${parsed.rules.maxOf { it.bonusPercent }}%）"
        }
    }

    /**
     * 标签编辑弹窗。
     *
     * 第一次打开时预填一份示例（[PresetTags]），但**不会**自动写回设置——
     * 用户清空标签是明确的意图，下次再打开不该又冒出来。
     */
    private fun showTagEditor() {
        val current = store.current.nurtureTags
        val seed = current.ifBlank { PresetTags.EXAMPLES }

        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(seed)
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 14
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            hint = "一行一条，例如：健身 / 蛋白粉 80"
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = ContextCompat.getDrawable(context, R.drawable.bg_input)
        }
        val container = FrameLayout(this).apply {
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("视频文案标签")
            .setMessage("命中标签会放大点赞概率：基线 × （1 + 加成）。一行一条，权重可省略（默认 100%）。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text?.toString().orEmpty()
                store.edit { it.copy(nurtureTags = raw) }
                renderTagSummary(raw)
                val parsed = NurtureTags.parse(raw)
                val skipped = parsed.invalidLines.size
                LogStore.i(TAG, "标签已更新：${parsed.rules.size} 条生效，跳过 $skipped 行")
                toast(
                    when {
                        parsed.rules.isEmpty() && raw.isBlank() -> "已清空标签，点赞按基线概率"
                        parsed.rules.isEmpty() -> "没有可用标签（$skipped 行都没看懂）"
                        skipped > 0 -> "已保存 ${parsed.rules.size} 条，$skipped 行看不懂已跳过"
                        else -> "已保存 ${parsed.rules.size} 条标签"
                    }
                )
            }
            .setNeutralButton("清空") { _, _ ->
                store.edit { it.copy(nurtureTags = "") }
                renderTagSummary("")
                toast("已清空标签")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun bindLive(s: Settings) {
        val c = binding.contentLive

        bindSegmented(
            listOf(c.segEnterManual, c.segEnterRecommend), s.enterMode, grouped = false
        ) { index -> store.edit { it.copy(enterMode = index) } }

        bindSwitch(c.swMicRequest, s.micRequest) { v -> store.edit { it.copy(micRequest = v) } }
        bindIntField(c.etCommentGapMin, s.commentGapMin) { v -> store.edit { it.copy(commentGapMin = v) } }
        bindIntField(c.etCommentGapMax, s.commentGapMax) { v -> store.edit { it.copy(commentGapMax = v) } }
        bindSwitch(c.swLikeOnComment, s.likeOnComment) { v -> store.edit { it.copy(likeOnComment = v) } }
        bindIntField(c.etLikeTrigger, s.likeTrigger) { v -> store.edit { it.copy(likeTrigger = v) } }
        bindIntField(c.etLikeCountMin, s.likeCountMin) { v -> store.edit { it.copy(likeCountMin = v) } }
        bindIntField(c.etLikeCountMax, s.likeCountMax) { v -> store.edit { it.copy(likeCountMax = v) } }

        bindSegmented(
            listOf(c.segCommentCopy, c.segCommentPreset), s.commentMode, grouped = false
        ) { index ->
            store.edit { it.copy(commentMode = index) }
            c.tvCommentModeDesc.text = commentModeDescriptions.getOrElse(index) { "" }
        }
    }

    // ---- 绑定原语 ----

    private fun bindIntField(edit: EditText, value: Int, onChange: (Int) -> Unit) {
        edit.setText(value.toString())
        edit.doAfterTextChanged { text ->
            val v = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            onChange(v)
        }
    }

    /**
     * 带范围的数字输入框（关注数量 / 关注上限）。
     *
     * 三处细节：
     * 1. **长度过滤**先挡一道（10000 最多 5 位），避免「99999」这种输入一路写进参数；
     * 2. 输入中超出上限**立即夹取并回写**，不让非法值停留在设置里；
     * 3. 失焦时兜底——用户可能把内容清空或留下「0」，此时填回合法值并提示。
     *    没有这一条，清空输入框后参数会停在旧值，界面和设置不一致。
     */
    private fun bindRangedIntField(
        edit: EditText,
        value: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ) {
        edit.setText(value.coerceIn(min, max).toString())
        edit.filters = arrayOf(InputFilter.LengthFilter(max.toString().length))

        edit.doAfterTextChanged { text ->
            val raw = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            val v = raw.coerceIn(min, max)
            if (v != raw) {
                edit.setText(v.toString())
                edit.setSelection(edit.text?.length ?: 0)
                return@doAfterTextChanged   // setText 会再触发本回调，夹取后的值在那里写入
            }
            onChange(v)
        }
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val raw = edit.text?.toString()?.toIntOrNull()
            val v = (raw ?: min).coerceIn(min, max)
            if (raw != v) {
                edit.setText(v.toString())
                toast("已调整为 $min ~ $max 范围内的 $v")
            }
            onChange(v)
        }
    }

    /**
     * 扫描间隔 = 数值 + 单位。
     *
     * 数值上限跟着单位走（秒 ≤3600，分钟 ≤60），所以这里不用通用的
     * [bindRangedIntField]——它的 max 是一次性常量。
     * 切单位时按**总时长**换算（30 秒 → 分钟 = 0 被夹成 1 分钟），
     * 不做「换单位就重置成默认值」那种丢用户配置的事。
     */
    private fun bindScanInterval(c: ContentAutoFollowBinding) {
        val s = store.current
        c.etScanInterval.setText(s.scanIntervalValue.toString())

        val commit: (Int) -> Unit = { v ->
            store.edit { it.copy(scanIntervalValue = v) }
            LogStore.i(TAG, "扫描间隔 = ${store.current.scanIntervalLabel}")
        }
        fun maxOf(unit: Int) = Settings.scanValueMax(unit)

        c.etScanInterval.doAfterTextChanged { text ->
            val raw = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            val v = raw.coerceIn(Settings.MIN_SCAN_SECONDS.toInt(), maxOf(store.current.scanIntervalUnit))
            if (v != raw) {
                c.etScanInterval.setText(v.toString())
                c.etScanInterval.setSelection(c.etScanInterval.text?.length ?: 0)
                return@doAfterTextChanged
            }
            commit(v)
        }
        c.etScanInterval.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val max = maxOf(store.current.scanIntervalUnit)
            val raw = c.etScanInterval.text?.toString()?.toIntOrNull()
            val v = (raw ?: Settings.DEFAULT_SCAN_VALUE).coerceIn(Settings.MIN_SCAN_SECONDS.toInt(), max)
            if (raw != v) {
                c.etScanInterval.setText(v.toString())
                toast("已调整为 1 ~ $max 范围内的 $v")
            }
            commit(v)
        }

        bindSegmented(
            listOf(c.segIntervalSec, c.segIntervalMin), s.scanIntervalUnit, grouped = false
        ) { unit ->
            if (unit == store.current.scanIntervalUnit) return@bindSegmented
            val totalSeconds = store.current.scanTotalSeconds()
            val newVal = if (unit == Settings.UNIT_MINUTE) {
                (totalSeconds / 60).toInt().coerceIn(1, maxOf(Settings.UNIT_MINUTE))
            } else {
                totalSeconds.coerceIn(Settings.MIN_SCAN_SECONDS, Settings.MAX_SCAN_SECONDS).toInt()
            }
            store.edit { it.copy(scanIntervalUnit = unit, scanIntervalValue = newVal) }
            c.etScanInterval.setText(newVal.toString())
            LogStore.i(
                TAG,
                "扫描间隔单位切换为${if (unit == Settings.UNIT_MINUTE) "分钟" else "秒"}，" +
                    "数值 $newVal（${store.current.scanIntervalLabel}）"
            )
        }
    }

    private fun bindSwitch(sw: SwitchMaterial, checked: Boolean, onChange: (Boolean) -> Unit) {
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
    }

    private fun bindSlider(slider: Slider, label: TextView, value: Int, onChange: (Int) -> Unit) {
        val clamped = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        slider.value = clamped
        label.text = "${clamped.toInt()}%"
        slider.addOnChangeListener { _, v, fromUser ->
            val pct = v.toInt()
            label.text = "$pct%"
            if (fromUser) onChange(pct)
        }
    }

    private fun bindSegmented(
        views: List<TextView>,
        initial: Int,
        grouped: Boolean,
        onChange: (Int) -> Unit
    ) {
        fun render(selected: Int) {
            views.forEachIndexed { index, view ->
                val on = index == selected
                view.setBackgroundResource(
                    when {
                        on -> R.drawable.bg_segment_selected
                        grouped -> R.drawable.bg_segment_normal
                        else -> R.drawable.bg_chip_normal
                    }
                )
                view.setTextColor(
                    ContextCompat.getColor(this, if (on) R.color.white else R.color.text_primary)
                )
                view.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        views.forEachIndexed { index, view ->
            view.setOnClickListener {
                render(index)
                onChange(index)
            }
        }
        render(initial.coerceIn(0, views.lastIndex))
    }

    // ==================== 授权信息 ====================

    private fun setupLicense() {
        uiScope.launch {
            license.state.collect { status ->
                renderLicense(status)
                if (status.authorized) {
                    maybeAutoCheckOta()
                } else if (status.source == "REMOTE" &&
                    status.message?.startsWith("未激活") == true
                ) {
                    showActivationDialog()
                }
            }
        }
        uiScope.launch { license.initialize() }
    }

    private fun renderLicense(status: LicenseStatus) {
        val c = binding.contentAuto

        c.tvLicenseStatus.text = when {
            !status.authorized -> status.message ?: "未授权"
            status.offline -> "已授权（离线模式）"
            status.source == "LOCAL_PLACEHOLDER" -> "已授权（本地占位）"
            else -> "已授权"
        }
        c.tvLicenseStatus.setTextColor(
            ContextCompat.getColor(this, if (status.authorized) R.color.success_green else R.color.brand_red)
        )
        c.tvLicenseStatus.setTypeface(null, Typeface.BOLD)
        c.tvLicenseStatus.setOnClickListener {
            if (!license.state.value.authorized) showActivationDialog()
        }

        c.tvLicenseExpire.text = status.expireAt?.let { millis ->
            val days = status.remainingDays
            if (days != null) "${formatDateTime(millis)}（剩余 $days 天）" else formatDateTime(millis)
        } ?: "—"
        c.tvLicenseCode.text = status.code?.let { maskCode(it) } ?: "—"

        if (status.offline && status.message != null) {
            LogStore.i(TAG, "授权进入离线模式：${status.message}")
        }
        binding.btnStartService.isEnabled =
            status.authorized || TaskEngine.progress.value.state.isActive
    }

    private fun showActivationDialog() {
        if (isFinishing || activationDialog?.isShowing == true) return
        val input = EditText(this).apply {
            hint = "DGG-XXXXX-XXXXX-XXXXX-XXXXX"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(64))
            setSingleLine(true)
            setPadding(28, 18, 28, 18)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("激活授权")
            .setMessage("输入管理员提供的授权密钥。激活后本设备会绑定到该授权。")
            .setView(input)
            .setPositiveButton("激活", null)
            .setNegativeButton("稍后", null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val code = input.text?.toString()?.trim().orEmpty()
                if (code.isBlank()) {
                    input.error = "请输入授权密钥"
                    return@setOnClickListener
                }
                button.isEnabled = false
                uiScope.launch {
                    val result = license.activate(code)
                    result.fold(
                        onSuccess = {
                            dialog.dismiss()
                            toast("激活成功")
                        },
                        onFailure = { error ->
                            button.isEnabled = true
                            toast(error.message ?: "激活失败")
                            LogStore.w(TAG, "激活失败：${error.message}")
                        }
                    )
                }
            }
        }
        dialog.setOnDismissListener { activationDialog = null }
        activationDialog = dialog
        dialog.show()
    }

    private fun formatDateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun maskCode(code: String): String = when {
        code.contains("•") -> code
        code.length <= 4 -> "••••"
        else -> code.take(4) + "••••" + code.takeLast(4)
    }

    // ==================== OTA ====================

    private fun setupOta() {
        OtaManager.installProvider(RemoteOtaProvider(this))
        // 先恢复「上次已经下好、还没装」的状态，再订阅：冷启动时入口不该消失
        OtaManager.restoreReady(this)
        uiScope.launch {
            OtaManager.state.collect { renderOtaState(it) }
        }
        binding.tvUpdate.setOnClickListener { checkOta(manual = true) }
    }

    /** 授权可用后自动检查；6 小时内不重复，失败静默。 */
    private fun maybeAutoCheckOta() {
        val now = System.currentTimeMillis()
        if (now - lastOtaCheck < OtaManager.MIN_CHECK_INTERVAL_MS) return
        lastOtaCheck = now
        uiScope.launch {
            val manifest = OtaManager.check(this@MainActivity, manual = false)
            if (manifest != null) showUpdatePrompt(manifest)
        }
    }

    private fun checkOta(manual: Boolean) {
        uiScope.launch {
            val manifest = OtaManager.check(this@MainActivity, manual)
            if (manifest != null) {
                showUpdatePrompt(manifest)
            } else if (manual) {
                toast("已是最新版本，或暂时无法连接更新服务")
            }
        }
    }

    private fun renderOtaState(state: OtaState) {
        when (state) {
            is OtaState.Available -> {
                binding.tvUpdate.visibility = View.VISIBLE
                binding.tvUpdate.text = "升级 ${state.manifest.versionName}"
                binding.tvUpdate.setOnClickListener { showUpdatePrompt(state.manifest) }
            }

            is OtaState.Downloading -> {
                binding.tvUpdate.visibility = View.VISIBLE
                val pct = if (state.total > 0) (state.downloaded * 100 / state.total) else 0
                binding.tvUpdate.text = "下载 $pct%"
            }

            is OtaState.Ready -> {
                binding.tvUpdate.visibility = View.VISIBLE
                binding.tvUpdate.text = "安装 ${state.manifest.versionName}"
                binding.tvUpdate.setOnClickListener {
                    OtaInstaller.install(this, state.file).onFailure { toast(it.message ?: "安装失败") }
                }
            }

            is OtaState.Failed -> {
                binding.tvUpdate.visibility = View.VISIBLE
                binding.tvUpdate.text = "重试升级"
                binding.tvUpdate.setOnClickListener { checkOta(manual = true) }
                LogStore.w(TAG, "OTA 失败：${state.message}")
            }

            else -> {
                binding.tvUpdate.visibility = View.GONE
                binding.tvUpdate.setOnClickListener { checkOta(manual = true) }
            }
        }
    }

    private fun showUpdatePrompt(manifest: OtaManifest) {
        if (isFinishing || updateDialog?.isShowing == true) return
        lastPromptedVersion = manifest.versionCode
        val message = buildString {
            append("新版本 ${manifest.versionName}（${manifest.versionCode}）")
            if (manifest.size > 0) append("\n大小：${formatBytes(manifest.size)}")
            if (manifest.changelog.isNotBlank()) {
                append("\n\n")
                append(manifest.changelog.take(800))
            }
            append("\n\n更新会重启 App；任务运行中请先停止任务。")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("立即更新") { _, _ -> downloadUpdate(manifest) }
            .setNegativeButton("稍后", null)
            .setNeutralButton("忽略此版本") { _, _ ->
                OtaManager.ignoreVersion(this, manifest.versionCode)
                binding.tvUpdate.visibility = View.GONE
            }
            .create()
        dialog.setOnDismissListener { updateDialog = null }
        updateDialog = dialog
        dialog.show()
    }

    /**
     * 下载交给前台服务，**不在 Activity 里跑**。
     *
     * 之前这里是 `uiScope.launch { OtaManager.downloadAndVerify(...) }`，切后台即被冻结、
     * Activity 重建就被取消；而下载恰好是用户最容易切走的环节（十几 MB、几十秒）。
     * 现在服务持有下载，界面只通过 `OtaManager.state` 看进度；下完不会自动安装，
     * 用户在通知里或界面上点「安装」才调起系统安装器。
     */
    private fun downloadUpdate(manifest: OtaManifest) {
        OtaService.start(this, manifest)
        toast("已在后台下载 ${manifest.versionName}，可在通知栏查看进度")
    }

    /**
     * 字节数格式化。
     *
     * `< 1 KB` 必须走 B 分支：日志可能只有几百字节（刚启动就点上传），
     * 原来的 `bytes / 1024` 会显示「0 KB」，用户会以为根本没传上去。
     */
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    // ==================== 动作 ====================

    private fun setupActions() {
        // 「日志」不再是查看入口：点一下就把日志上传到云端，回一个编号给用户报障用。
        // 本地查看已关闭（2026-10-28 要求），避免用户把日志截图外传或改文件。
        binding.tvLog.setOnClickListener { uploadLogs() }

        binding.tvUnbind.setOnClickListener {
            uiScope.launch {
                val result = license.unbind()
                val message = result.exceptionOrNull()?.message ?: "已解绑当前设备，重新打开 App 可输入新密钥"
                LogStore.w(TAG, "解绑请求：$message")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("解绑")
                    .setMessage(message)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }

        binding.btnStartService.setOnClickListener {
            when (TaskEngine.progress.value.state) {
                EngineState.RUNNING -> {
                    TaskEngine.pause()
                    LogStore.i(TAG, "点击「暂停」")
                }

                EngineState.PAUSED, EngineState.PAUSED_WAIT -> {
                    TaskEngine.resume()
                    LogStore.i(TAG, "点击「继续」（当前 ${TaskEngine.progress.value.state.label}）")
                }

                EngineState.ERROR -> {
                    LogStore.i(TAG, "点击「重试」，用上一次的流程重新开始")
                    EngineController.restartLast(this)
                        .onFailure { toast(it.message ?: "重试失败") }
                }

                else -> confirmAndStart()
            }
        }

        binding.btnOpenDouyin.setOnClickListener {
            if (DouyinLauncher.launch(this)) {
                LogStore.i(TAG, "已拉起抖音（${DouyinLauncher.PACKAGE}）")
            } else {
                LogStore.w(TAG, "未安装抖音，引导至下载页")
                toast("未检测到抖音，正在打开下载页")
                if (!DouyinLauncher.openMarket(this)) {
                    toast("无法打开下载页，请手动安装抖音")
                }
            }
        }

        binding.fabMenu.setOnClickListener { showSelfCheckMenu() }

        // 「去配置」「评论配置」都跳到直播互动页
        binding.contentAuto.tvGoConfig.setOnClickListener {
            selectTab(2)
            toast("已切换到「直播互动」，评论参数在底部")
        }
        binding.contentNurture.tvCommentConfig.setOnClickListener { selectTab(2) }
    }

    // ==================== 引擎 ====================

    private fun setupEngineObserver() {
        progressJob = uiScope.launch {
            TaskEngine.progress.collect { renderEngine(it) }
        }
    }

    /** 引擎状态 → 状态条 + 主按钮文案。终态只在异常时保留，正常停止不留残影。 */
    private fun renderEngine(progress: EngineProgress) {
        val active = progress.state.isActive
        val needAttention = progress.state == EngineState.STOPPED_ANOMALY ||
            progress.state == EngineState.ERROR

        binding.engineStatusBar.visibility = if (active || needAttention) View.VISIBLE else View.GONE
        // 分母取**当前设置**：progress.target 是任务启动瞬间的快照，用户在悬浮面板上改了
        // 「设置关注」它不会跟着变，状态条就一直停在旧数字上（2026-10-29 真机复现）。
        // target == 0 是流程声明「没有总量目标」（直播互动 / 养号），这时保留原样只看计数。
        binding.tvEngineStatus.text = progress.withLiveTarget(store.current.followTarget).brief()
        binding.engineDot.setBackgroundResource(
            when {
                progress.state == EngineState.PAUSED ||
                    progress.state == EngineState.PAUSED_WAIT -> R.drawable.bg_dot_amber
                active -> R.drawable.bg_dot_green
                else -> R.drawable.bg_dot_red
            }
        )

        binding.tvEngineAction.text = if (active) "停止" else "关闭"
        binding.tvEngineAction.setOnClickListener {
            if (TaskEngine.progress.value.state.isActive) {
                EngineController.stop(this, "界面状态条停止")
            } else {
                binding.engineStatusBar.visibility = View.GONE
            }
        }

        binding.btnStartService.text = when (progress.state) {
            EngineState.RUNNING -> "暂停"
            // 手动暂停与自愈暂停（等待恢复）共用同一颗按钮
            EngineState.PAUSED, EngineState.PAUSED_WAIT -> "继续"
            EngineState.ERROR -> "重试"
            else -> "开始服务"
        }
        binding.btnStartService.isEnabled = license.state.value.authorized || active
    }

    // ==================== 启动分发 ====================

    /**
     * 按**当前页签**决定启动哪个流程。
     *
     * 「开始服务」只有一个按钮，三个页签共用。不按页签分发的话，用户在「直播互动」页
     * 填完评论参数点开始，实际跑的是关注流程——参数全部无效，行为完全错配。
     */
    private fun confirmAndStart() {
        if (!license.state.value.authorized) {
            toast("请先激活授权")
            showActivationDialog()
            return
        }
        when (currentTab) {
            TAB_AUTO -> confirmFollow()
            TAB_NURTURE -> confirmNurture()
            TAB_LIVE -> confirmLiveInteract()
        }
    }

    /** 「开始服务」= M2 主业务：直播间评论随机关注 */
    private fun confirmFollow() {
        val s = store.current
        AlertDialog.Builder(this)
            .setTitle("开始关注")
            .setMessage(
                "开始前请确认：你已经在抖音里手动进入目标直播间，并停在直播间画面。\n\n" +
                    "脚本会循环执行：读直播间评论 → 随机挑一个人 → 点开资料卡 → 关注 → 回直播间，" +
                    "每次关注之间间隔约 ${s.scanIntervalLabel}（叠加 ±${s.jitterPercent}% 抖动）。\n\n" +
                    "达到「目标关注数量 ${s.followTarget}」或「今日上限 ${s.dailyLimit}」时停止。\n\n" +
                    "不会私信、不会点赞、不会刷评论，也不会自己去找直播间。\n\n" +
                    "运行期间屏幕会被自动操作，请勿手动干扰；随时可用悬浮窗或通知栏停止。"
            )
            .setPositiveButton("开始") { _, _ -> startFlow(FollowFromLiveFlow(), "已启动直播关注") }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「开始服务」= M3 主业务：直播间互动（发评论 / 点赞 / 连麦） */
    private fun confirmLiveInteract() {
        val s = store.current
        val mode = if (s.commentMode == 1) "预设评论" else "复制最新评论"
        AlertDialog.Builder(this)
            .setTitle("开始直播互动")
            .setMessage(
                "开始前请确认：你已经在抖音里手动进入目标直播间，并停在直播间画面。\n\n" +
                    "脚本会循环执行：每 ${s.commentGapMin}~${s.commentGapMax} 秒发一条评论（$mode），" +
                    "每发 ${s.likeTrigger} 条自动点赞 ${s.likeCountMin}~${s.likeCountMax} 次" +
                    (if (s.micRequest) "，并申请一次连麦" else "") + "。\n\n" +
                    "没有总量目标，会一直跑到你手动停止。一旦出现「评论过快」一类的平台提示，立刻停机且不重试。\n\n" +
                    "运行期间屏幕会被自动操作，请勿手动干扰；随时可用悬浮窗或通知栏停止。"
            )
            .setPositiveButton("开始") { _, _ -> startFlow(LiveInteractFlow(), "已启动直播互动") }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「开始服务」= M4 主业务：主动刷推荐流 + 随机停留 + 文案标签加权点赞 */
    private fun confirmNurture() {
        val s = store.current
        if (s.nurtureMode == Settings.NURTURE_VERTICAL) {
            toast("「垂直养号」还没开放，请选「刷视频养号」或「首页连播」")
            return
        }

        val browseOnly = s.nurtureMode == Settings.NURTURE_HOME
        val tagCount = NurtureTags.parse(s.nurtureTags).rules.size
        val pace = when (s.nurturePace) {
            Settings.PACE_SLOW -> "慢"
            Settings.PACE_FAST -> "快"
            else -> "标准"
        }
        val minutes = s.runMinutes.coerceIn(Settings.RUN_MINUTES_MIN, Settings.RUN_MINUTES_MAX)

        val interactPart = if (browseOnly) {
            "这是「首页连播」：只刷视频，不做任何互动。\n\n"
        } else {
            "点赞基线 ${s.probLike}%（命中标签时按「基线×(1+加成)」放大，最高 60%），" +
                "收藏基线 ${s.probCollect}%；当前 ${tagCount} 条文案标签生效。\n\n"
        }

        AlertDialog.Builder(this)
            .setTitle("开始养号")
            .setMessage(
                "开始前请确认：抖音已打开，并停在「能看到右侧点赞按钮的视频画面」。" +
                    "不在的话脚本会试着点「首页」→「推荐」自己进去，进不去会明确中止，不会瞎猜。\n\n" +
                    "运行中会：自动上下滑推荐流，每条视频按「$pace」节奏随机停留 1.5~90 秒" +
                    "（多数 2~12 秒），每 20~40 屏休息一次，偶尔回看、偶尔看一眼评论。\n\n" +
                    interactPart +
                    "遇到直播推荐卡、商品卡一律直接滑走，绝不点击。\n\n" +
                    "⚠ 这个功能不涨粉：它不关注任何人，只把账号养得像个正常活跃用户。" +
                    "到「运行时长 $minutes 分钟」自动停，随时可用悬浮窗停止。"
            )
            .setPositiveButton("开始") { _, _ -> startFlow(NurtureFlow(), "已启动刷视频养号") }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 尚未实现的功能页签：宁可明确说「没有」，也不要跑一个参数完全无关的流程 */
    private fun showUnavailable(tabName: String, milestone: String) {
        LogStore.w(TAG, "「$tabName」尚未实现（计划 $milestone），已拒绝启动")
        AlertDialog.Builder(this)
            .setTitle("暂未开放")
            .setMessage(
                "「$tabName」计划在 $milestone 实现，当前版本点「开始服务」不会启动任何流程。\n\n" +
                    "现在可用的是：\n·「自动关注」——直播间评论随机关注\n" +
                    "·「直播互动」——随机间隔发评论、自动点赞、申请连麦"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun startFlow(flow: Flow, successHint: String) {
        LogStore.i(TAG, "启动流程「${flow.name}」")
        EngineController.start(this, flow)
            .onSuccess { toast(successHint) }
            .onFailure { error -> showStartFailure(error.message ?: "启动失败") }
    }

    private fun showStartFailure(reason: String) {
        LogStore.e(TAG, "启动被拒绝：$reason")
        AlertDialog.Builder(this)
            .setTitle("无法开始")
            .setMessage(
                buildString {
                    append(reason)
                    append("\n\n")
                    if (!DggAccessibilityService.isConnected()) {
                        append("请先点「无障碍」一栏的「去授权」，在系统设置中找到「抖关关 自动化」并开启。")
                    } else {
                        append("详细原因见运行日志。")
                    }
                }
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 悬浮菜单：排障入口。「搜索链路自检」M2 之后仍然有用，可单独验证定位是否还活着 */
    private fun showSelfCheckMenu() {
        val items = arrayOf(
            "识别当前页面（只读，不发任何操作）",
            "搜索链路自检（首页 → 搜索 → 用户 tab）",
            "打开系统无障碍设置",
            "上传运行日志到云端"
        )
        AlertDialog.Builder(this)
            .setTitle("自检")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> detectCurrentPage()
                    1 -> startFlow(SmokeFlow.build(), "自检流程已启动（只验定位，不关注任何人）")
                    2 -> openPermission(PermissionCardView.Kind.ACCESSIBILITY)
                    else -> uploadLogs()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 日志上报 ====================

    private fun setupLogUpload() {
        LogUploader.installProvider(RemoteLogUploadProvider())
        uiScope.launch {
            LogUploader.state.collect { renderLogUpload(it) }
        }
    }

    /**
     * 「日志」按钮 = 一键上报。
     *
     * 设计要点（用户明确要求：**不允许本地查看，点击就上传**）：
     * - 不做二次确认：点了就传，减少一步；正文只取最近 512KB（见 [LogCollector.MAX_BYTES]）；
     * - 结果必须回一个**编号**：用户报障时报这个号，客服才能在云端定位到这份日志；
     * - 失败如实说失败，不静默重试（用户主动操作，静默等于骗人）。
     */
    private fun uploadLogs() {
        if (LogUploader.state.value is LogUploadState.Uploading) {
            toast("正在上传，请稍候")
            return
        }
        LogStore.i(TAG, "点击「日志」→ 收集并上传到云端（本地查看已关闭）")
        uiScope.launch {
            LogUploader.upload(this@MainActivity).fold(
                onSuccess = { receipt -> showUploadReceipt(receipt) },
                onFailure = { error -> toast("日志上传失败：${error.message ?: "网络异常"}") }
            )
        }
    }

    private fun showUploadReceipt(receipt: LogUploadReceipt) {
        val statusText = when (receipt.status) {
            LogUploadReceipt.STATUS_QUEUED -> "已受理（排队入库）"
            LogUploadReceipt.STATUS_FILTERED -> "已收到（被过滤，请联系客服）"
            else -> "已入库"
        }
        val detail = buildString {
            append("编号：").append(receipt.reportId).append('\n')
            append("状态：").append(statusText).append('\n')
            if (receipt.size > 0) append("大小：").append(formatBytes(receipt.size)).append('\n')
            if (receipt.expiresAt > 0) {
                append("云端保留至：").append(formatDateTime(receipt.expiresAt)).append('\n')
            }
            append("\n把编号告知客服即可定位这份日志。")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("日志已上传")
            .setMessage(detail)
            .setPositiveButton("知道了", null)
        // 服务端给了工单链接才显示入口：字段是可选的，没有就不摆一个点不动的按钮
        receipt.ticketUrl?.let { url ->
            builder.setNeutralButton("查看工单") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { toast("无法打开链接：$url") }
            }
        }
        builder.show()
    }

    /** 「日志」按钮文案跟随上传状态：上传中变成「上传中…」，避免用户重复点 */
    private fun renderLogUpload(state: LogUploadState) {
        binding.tvLog.text = when (state) {
            is LogUploadState.Uploading -> "上传中…"
            else -> "日志"
        }
        binding.tvLog.isEnabled = state !is LogUploadState.Uploading
    }

    /**
     * 单次页面识别。这是排障的核心工具：定位失效时先看「它到底认为自己在哪个页面、
     * 命中了哪几条证据」，比猜抖音改了什么快得多。
     */
    private fun detectCurrentPage() {
        val service = DggAccessibilityService.current
        if (service == null) {
            toast("无障碍服务未开启，无法读取界面")
            LogStore.w(TAG, "页面识别被拒绝：无障碍服务未连接")
            return
        }

        uiScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                runCatching {
                    val config = LocatorConfigLoader.load(this@MainActivity)
                    val actions = Actions(service, Humanizer(store.current.jitterPercent))
                    val detection = PageDetector(config, actions).detect()
                    Triple(detection, service.rootInActiveWindow?.packageName?.toString(), config.pages.size)
                }
            }

            snapshot.onSuccess { (detection, pkg, pageCount) ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("页面识别结果")
                    .setMessage(
                        buildString {
                            append("判定：${detection.page.name}\n")
                            append("得分：${detection.score}（阈值 ${detection.threshold}）\n")
                            append("最接近：${detection.pageKey.ifBlank { "—" }}\n")
                            append("前台包名：${pkg ?: "未知"}\n")
                            append("页面规则数：$pageCount\n\n")
                            append("命中证据：\n")
                            append(detection.hits.joinToString("\n").ifBlank { "无" })
                        }
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }.onFailure {
                LogStore.e(TAG, "页面识别异常：${it.message}")
                toast("页面识别异常：${it.message}")
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "主界面"

        /** 落盘日志文件名。与 DebugChannel 里的 CMD_FILE/LOG_FILE 同一目录，联调用同一个文件 */
        private const val LOG_FILE_NAME = "runtime.log"

        private val TAB_NAMES = listOf("自动关注", "养号助手", "直播互动")

        private const val TAB_AUTO = 0
        private const val TAB_NURTURE = 1
        private const val TAB_LIVE = 2
    }
}
