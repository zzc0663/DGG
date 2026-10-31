package com.doujiajia.app.ui

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
import androidx.lifecycle.Lifecycle
import com.doujiajia.app.R
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.data.PermissionGuard
import com.doujiajia.app.data.PresetTags
import com.doujiajia.app.data.Settings
import com.doujiajia.app.data.SettingsStore
import com.doujiajia.app.data.license.LicenseGate
import com.doujiajia.app.data.license.LicenseProvider
import com.doujiajia.app.data.license.LicenseStatus
import com.doujiajia.app.data.log.LogUploader
import com.doujiajia.app.data.log.LogUploadReceipt
import com.doujiajia.app.data.log.LogUploadState
import com.doujiajia.app.data.log.RemoteLogUploadProvider
import com.doujiajia.app.data.license.RemoteLicenseProvider
import com.doujiajia.app.BuildConfig
import com.doujiajia.app.data.ota.OtaInstaller
import com.doujiajia.app.data.ota.OtaManager
import com.doujiajia.app.data.ota.OtaManifest
import com.doujiajia.app.data.ota.OtaState
import com.doujiajia.app.data.ota.RemoteOtaProvider
import com.doujiajia.app.databinding.ActivityMainBinding
import com.doujiajia.app.databinding.ContentAutoFollowBinding
import com.doujiajia.app.engine.Actions
import com.doujiajia.app.engine.EngineProgress
import com.doujiajia.app.engine.EngineState
import com.doujiajia.app.engine.Flow
import com.doujiajia.app.engine.LocatorConfigLoader
import com.doujiajia.app.engine.NurtureTags
import com.doujiajia.app.engine.PageDetector
import com.doujiajia.app.engine.SmokeFlow
import com.doujiajia.app.engine.TaskEngine
import com.doujiajia.app.engine.withLiveTarget
import com.doujiajia.app.engine.flow.FollowFromLiveFlow
import com.doujiajia.app.engine.flow.LiveInteractFlow
import com.doujiajia.app.engine.flow.NurtureFlow
import com.doujiajia.app.engine.risk.Humanizer
import com.doujiajia.app.service.DebugChannel
import com.doujiajia.app.service.OtaService
import com.doujiajia.app.service.DggAccessibilityService
import com.doujiajia.app.service.EngineController
import com.doujiajia.app.service.FloatWindowService
import com.doujiajia.app.util.AppPermissions
import com.doujiajia.app.util.DouyinLauncher
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

    /** 上一个 OTA 状态，用来判断「Ready 是不是刚下完」——只有刚下完才自动弹安装框 */
    private var lastOtaState: OtaState? = null

    /**
     * 下载完成但界面当时不在前台（用户在看抖音）时挂起，等回前台再弹。
     *
     * 不能在这里直接 `AlertDialog.show()`：Activity 不可见时弹窗会抛
     * `WindowManager$BadTokenException`（实测在后台下载完成的场景必现）。
     */
    private var pendingInstallPrompt: OtaState.Ready? = null

    /** 本次进程内自动弹过安装框的版本号，防止「稍后」之后每次回前台又弹 */
    private var installPromptedVersion = 0

    /**
     * 本次进程内是否已经自动弹过激活框（用户 2026-10-31：冷启动只弹一次）。
     *
     * 从系统设置页返回会触发 `refreshIfStale` → 状态重新变「未激活」，
     * 旧写法每次都弹。顶栏徽标已经常显状态且可点，取消过一次就够表态了。
     */
    private var activationPrompted = false

    /** 「授权进入离线模式」只记一次日志（renderLicense 现在会随回前台/每小时重跑） */
    private var offlineNoticeLogged = false

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
        // 只有**进程级冷启动**才自动弹激活框；转屏/系统回收后重建（savedInstanceState 非空）不再弹，
        // 否则用户在输入框里打字时转一下屏，对话框就重来一次（见 activationPrompted 的注释）
        activationPrompted = savedInstanceState != null
        // 把授权状态交给服务层门禁：EngineController.start() 直接查它，
        // 悬浮窗「重试」/通知栏入口就无法绕过界面把无效授权的任务重新拉起来
        LicenseGate.attach(license)
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
        // 从通知栏「安装」按钮进来的：此刻状态已 restoreReady 为 Ready，可以直接装
        handleInstallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 抖加加在前台：任务到终态时悬浮面板不再自动展开（见 FloatWindowService.observe），
        // 同时把「在抖音里展开着看结论」的那块面板收起来，别挡参数区。
        AppVisibility.onResume()
        FloatWindowService.collapseIfExpanded()
        // 从系统设置页返回后必须重新读一次，否则用户授权了却仍显示未授权
        val state = refreshPermissionState()
        // 之后在前台期间每 2 秒再对一次（见 permissionPoll 的注释）
        permissionHandler.removeCallbacks(permissionPoll)
        permissionHandler.postDelayed(permissionPoll, PERMISSION_POLL_MS)
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
        // 权限被升级/重装/系统清理重置时，主动喊一声——这几个权限没了应用是静默残废的。
        // **不能立刻喊**：无障碍的判据现在包含「服务是否真的连上」，而进程刚起来时系统还没把
        // 服务绑上（通常 1 秒内），立刻判会误报「权限被重置了」。给 2.5 秒宽限，到点再复查一次。
        permissionHandler.removeCallbacks(lossWarnRunnable)
        permissionHandler.postDelayed(lossWarnRunnable, PERMISSION_WARN_GRACE_MS)
        // 授权复核：只在 onCreate 握手一次的话，App 常驻前台几小时也不会发现授权被停用。
        // 6 小时阈值 + 内部 30 分钟失败节流，正常使用几乎不会产生额外请求。
        uiScope.launch { license.refreshIfStale(AUTHORIZATION_REFRESH_MS) }
        // 顺手把授权信息按「当前时间」重画一次：徽标上的剩余天数是用 expireAt 现算的，
        // 在后台放一夜回来必须重算，否则显示的还是昨天的天数
        renderLicense(license.state.value)
        permissionHandler.removeCallbacks(licenseRefresh)
        permissionHandler.postDelayed(licenseRefresh, LICENSE_BADGE_REFRESH_MS)
        // 后台下载完成、用户此刻才切回前台 → 在这里补弹「更新已下载完成」。
        // Ready 到达的那一刻界面若不可见，弹窗会抛 BadTokenException，所以只能挂起等这个时机。
        maybeShowInstallPrompt()
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
        // 后台不再轮询权限：不是前台就没人看这几个点，白耗电
        permissionHandler.removeCallbacks(permissionPoll)
        // 授权徽标同理：不在前台时没人看，回前台（onResume）会重算
        permissionHandler.removeCallbacks(licenseRefresh)
    }

    override fun onDestroy() {
        progressJob?.cancel()
        progressJob = null
        permissionHandler.removeCallbacks(permissionPoll)
        permissionHandler.removeCallbacks(licenseRefresh)
        activationDialog?.dismiss()
        updateDialog?.dismiss()
        uiScope.cancel()
        super.onDestroy()
    }

    // ==================== 页签 ====================

    private fun setupTabs() {
        // vc55：直播互动的 3 张参数卡并入「自动关注」页，页签从 3 个减到 2 个。
        // ⚠️ 这里改数量后，全项目的 selectTab(2) 都必须清掉，否则 pages[2] 直接越界崩。
        tabs = listOf(binding.tabAuto, binding.tabNurture)
        indicators = listOf(binding.indicatorAuto, binding.indicatorNurture)
        pages = listOf(binding.contentAuto.root, binding.contentNurture.root)

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
            binding.contentNurture.permCard
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
        // 只有真的变了才重画 + 打日志：这个方法现在每 2 秒被轮询调一次（见 permissionPoll），
        // 无条件打日志会把日志文件刷满，无条件重画也会和用户点按抢主线程。
        if (state != lastPermissionState) {
            lastPermissionState = state
            listOf(
                binding.contentAuto.permCard,
                binding.contentNurture.permCard
            ).forEach { it.render(state) }
            LogStore.d(
                TAG,
                "权限状态：无障碍=${state.accessibility} 悬浮窗=${state.overlay} " +
                    "通知=${state.notification} 电池优化忽略=${state.batteryUnrestricted}"
            )
        }
        // 记住「曾经授予过」，升级/重装后丢权时才有依据提醒（见 PermissionGuard）
        PermissionGuard.remember(this, state)
        return state
    }

    /**
     * 前台期间每 2 秒重读一次权限状态。
     *
     * 为什么不能只在 `onResume` 读：
     * - 无障碍的判据里有「服务是否真的连上」（见 [AppPermissions.isAccessibilityServiceEnabled]），
     *   而系统把服务重新绑上、或把服务悄悄解绑，都**不会**触发本界面的任何回调；
     * - 用户从设置页回来时，系统写 Secure 表与 Activity resume 谁先谁后没有保证，
     *   resume 那一刻读到的可能还是旧值。
     * 两个 Secure 读 + 一个实例判断，成本是毫秒级，比「显示错了让用户去点开始才发现」划算得多。
     */
    private val permissionPoll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            refreshPermissionState()
            permissionHandler.postDelayed(this, PERMISSION_POLL_MS)
        }
    }

    private val permissionHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 宽限期到了再判断「权限是否真的丢了」，避免把「服务还没绑上」误报成「权限被重置」 */
    private val lossWarnRunnable = Runnable { warnIfPermissionsLost(refreshPermissionState()) }

    /**
     * 每小时重画一次授权徽标。
     *
     * 徽标上的「剩 N 天」是用 `expireAt` 现算的，**不会自己变**：App 常驻前台跑一整夜，
     * 第二天早上还显示昨天的天数。回前台会重算一次（onResume），但那种「不退出也不切换」的
     * 挂机场景只能靠定时。一次字符串拼接 + setText，开销可忽略。
     */
    private val licenseRefresh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderLicenseBadge(license.state.value)
            permissionHandler.postDelayed(this, LICENSE_BADGE_REFRESH_MS)
        }
    }

    /** 上一份渲染过的状态，用来「变了才重画/才打日志」 */
    private var lastPermissionState: AppPermissions.State? = null

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
                toast("请在列表中找到「${getString(R.string.accessibility_service_label)}」并开启")
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

        // 扫描间隔 = 数值 + 单位，单位切换时按总时长换算
        bindScanInterval(c)

        // 三个开关（空闲互动 / 申请连麦 / 下麦继续）**尚未接入流程**，布局里已
        // `enabled=false`，这里刻意不绑监听：绑了反而是「能改设置但没人消费」的假象。
        // 接流程时再打开，见 Settings 里那段注释。
        bindIntervalRest(c, s)
    }

    /**
     * 定时休息：开关控制两个输入框是否可用（关掉只置灰，**不清空用户填的数**）。
     *
     * 两个框必须有范围：填 0 会让 `restAfterN` 参与取模、`restSeconds` 变成"休息 0 秒"，
     * 实际效果是休息被静默跳过，日志里看不出问题。
     * ⚠️ 秒数下限是 **1**，不是 5 —— min=5 会让「30 / 120」这类首位 1~4 的数字输不进去
     * （旧版逐键夹取，见 [bindRangedIntField] 的注释与 vc54 记录）。
     */
    private fun bindIntervalRest(c: ContentAutoFollowBinding, s: Settings) {
        bindRangedIntField(c.etRestAfterN, s.restAfterN, REST_AFTER_N_MIN, REST_AFTER_N_MAX) { v ->
            store.edit { it.copy(restAfterN = v) }
        }
        bindRangedIntField(c.etRestSeconds, s.restSeconds, REST_SECONDS_MIN, REST_SECONDS_MAX) { v ->
            store.edit { it.copy(restSeconds = v) }
        }
        renderIntervalRestEnabled(c, s.intervalRest)
        bindSwitch(c.swIntervalRest, s.intervalRest) { v ->
            store.edit { it.copy(intervalRest = v) }
            renderIntervalRestEnabled(c, v)
        }
    }

    /** 关掉开关时不只是置灰：给一行明说的提示，否则「灰的输入框」会被当成「坏了，不能设置」。 */
    private fun renderIntervalRestEnabled(c: ContentAutoFollowBinding, enabled: Boolean) {
        listOf(c.etRestAfterN, c.etRestSeconds).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
        c.tvRestOffHint.visibility = if (enabled) View.GONE else View.VISIBLE
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
        val c = binding.contentAuto

        bindSegmented(
            listOf(c.segEnterManual, c.segEnterRecommend), s.enterMode, grouped = false
        ) { index -> store.edit { it.copy(enterMode = index) } }

        bindSwitch(c.swMicRequest, s.micRequest) { v -> store.edit { it.copy(micRequest = v) } }
        // 这五个框原来是 [bindIntField]（**零约束**）：填 0 会得到「评论间隔 0 秒」= 刷屏、
        // 「点赞 0 个」= 点了不算数，而且全程没有任何提示。统一接上有范围的原语。
        bindRangedIntField(
            c.etCommentGapMin, s.commentGapMin, Settings.COMMENT_GAP_MIN, Settings.COMMENT_GAP_MAX
        ) { v -> store.edit { it.copy(commentGapMin = v) } }
        bindRangedIntField(
            c.etCommentGapMax, s.commentGapMax, Settings.COMMENT_GAP_MIN, Settings.COMMENT_GAP_MAX
        ) { v -> store.edit { it.copy(commentGapMax = v) } }
        bindSwitch(c.swLikeOnComment, s.likeOnComment) { v -> store.edit { it.copy(likeOnComment = v) } }
        bindRangedIntField(
            c.etLikeTrigger, s.likeTrigger, Settings.LIKE_TRIGGER_MIN, Settings.LIKE_TRIGGER_MAX
        ) { v -> store.edit { it.copy(likeTrigger = v) } }
        bindRangedIntField(
            c.etLikeCountMin, s.likeCountMin, Settings.LIKE_COUNT_MIN, Settings.LIKE_COUNT_MAX
        ) { v -> store.edit { it.copy(likeCountMin = v) } }
        bindRangedIntField(
            c.etLikeCountMax, s.likeCountMax, Settings.LIKE_COUNT_MIN, Settings.LIKE_COUNT_MAX
        ) { v -> store.edit { it.copy(likeCountMax = v) } }

        bindSegmented(
            listOf(c.segCommentCopy, c.segCommentPreset), s.commentMode, grouped = false
        ) { index ->
            store.edit { it.copy(commentMode = index) }
            c.tvCommentModeDesc.text = commentModeDescriptions.getOrElse(index) { "" }
        }
    }

    // ---- 绑定原语 ----

    /**
     * 带范围的数字输入框。
     *
     * 🔴 **输入过程中绝不夹取、绝不 `setText`** —— 这是 vc54 修的坑：
     * 旧实现在 `doAfterTextChanged` 里 `coerceIn` 后立刻回写，配合「休息秒数 min=5」，
     * 会让**首位小于 min 的数字根本输不进去**：想打 30，敲 `3` 当场被改成 5，光标被挪走，
     * 后面那个 `0` 落空。用户看到的就是「最大只能填 5」。日志 `R-20261030-774556` 里
     * 秒框一直是空的、运行时停在 55s（唯一首位 ≥5 的成功输入）就是这么来的。
     *
     * 现在的规则：
     * 1. 合法值**实时落库**（改目标数不用等失焦就生效）；
     * 2. 非法值只留在框里，**不写设置**；
     * 3. 失焦 / 键盘「完成」时夹取一次并 toast；框是空的就回填 [lastGood]
     *    （不写 0、也不回落到 min——用户清空只是想重打）。
     *
     * 长度过滤固定 6 位，只挡「连按不停」；超范围交给失焦夹取。
     * 用 `max.toString().length` 会把「1200」静默截成「120」，既不提示也不等于上限 600。
     */
    private fun bindRangedIntField(
        edit: EditText,
        value: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ) = bindRangedIntField(edit, value, { min }, { max }, onChange)

    /** 上限会随别的控件变（扫描间隔切秒/分钟）时用这个重载。 */
    private fun bindRangedIntField(
        edit: EditText,
        value: Int,
        minProvider: () -> Int,
        maxProvider: () -> Int,
        onChange: (Int) -> Unit
    ) {
        var lastGood = value
        edit.setText(lastGood.toString())
        edit.filters = arrayOf(InputFilter.LengthFilter(MAX_DIGITS))

        edit.doAfterTextChanged { text ->
            val raw = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (raw in minProvider()..maxProvider()) {
                lastGood = raw
                onChange(raw)
            }
        }

        fun commit() {
            val min = minProvider()
            val max = maxProvider()
            val raw = edit.text?.toString()?.toIntOrNull()
            val v = (raw ?: lastGood).coerceIn(min, max)
            // 归一化：空框、前导 0（"030"）、超范围都改回标准写法。这里没有输入法在打字，可以安全 setText。
            if (raw != v || edit.text?.toString() != v.toString()) {
                edit.setText(v.toString())
                edit.setSelection(edit.text?.length ?: 0)
            }
            if (raw != null && raw != v) toast("已调整为 $min ~ $max 范围内的 $v")
            lastGood = v
            onChange(v)
        }

        edit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
        edit.setOnEditorActionListener { _, _, _ -> commit(); false }
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

        // 上下限是动态的（秒 ≤3600 / 分钟 ≤60），走 provider 重载；
        // 切单位后不用重建绑定，[bindRangedIntField] 每次读写都会重新问一遍。
        bindRangedIntField(
            c.etScanInterval,
            s.scanIntervalValue,
            { Settings.MIN_SCAN_SECONDS.toInt() },
            { Settings.scanValueMax(store.current.scanIntervalUnit) }
        ) { v ->
            store.edit { it.copy(scanIntervalValue = v) }
            LogStore.i(TAG, "扫描间隔 = ${store.current.scanIntervalLabel}")
        }

        bindSegmented(
            listOf(c.segIntervalSec, c.segIntervalMin), s.scanIntervalUnit, grouped = false
        ) { unit ->
            if (unit == store.current.scanIntervalUnit) return@bindSegmented
            val totalSeconds = store.current.scanTotalSeconds()
            val newVal = if (unit == Settings.UNIT_MINUTE) {
                (totalSeconds / 60).toInt().coerceIn(1, Settings.scanValueMax(Settings.UNIT_MINUTE))
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
                } else if (!activationPrompted && status.source == "REMOTE" &&
                    status.message?.startsWith("未激活") == true
                ) {
                    // **冷启动只自动弹一次**。
                    //
                    // 顶栏的授权徽标已经常显状态且可点，重复弹只会打断用户：
                    // 从系统设置页返回会触发 `refreshIfStale` → 状态重新变「未激活」，
                    // 旧写法的每一次都会再弹一次。取消过就等于表态，之后由用户自己点。
                    activationPrompted = true
                    showActivationDialog()
                }
            }
        }
        uiScope.launch { license.initialize() }
    }

    private fun renderLicense(status: LicenseStatus) {
        // vc49：页内的「授权信息」卡已整卡删除，状态与天数只走顶栏徽标（[renderLicenseBadge]），
        // 这里只保留与界面无关的两件事：离线转换的日志去重、开始按钮的可用性。
        // 到期具体时间与密钥掩码移到了 [showActivationDialog] 的正文里（点徽标即可看到）。

        // 只在这条状态**第一次**出现时记一行。这个函数现在每次回前台、每小时都会跑，
        // 不加去重的话日志会被「授权进入离线模式」刷屏。
        if (status.offline && status.message != null && !offlineNoticeLogged) {
            offlineNoticeLogged = true
            LogStore.i(TAG, "授权进入离线模式：${status.message}")
        } else if (!status.offline) {
            offlineNoticeLogged = false
        }

        renderLicenseBadge(status)
        binding.btnStartService.isEnabled =
            status.authorized || TaskEngine.progress.value.state.isActive
    }

    /**
     * 顶栏授权徽标。
     *
     * 三条口径（用户 2026-10-31 明确）：
     * - **授权与否常显**；剩余天数**只在这个授权有效时**显示，未授权就只写「未授权」；
     * - 点它 → 输入授权密钥（已授权时是「更换」，正文会写明覆盖本机授权）；
     * - 离线宽限期用琥珀色 + 「离线」，提醒该联网同步，而不是让用户以为授权坏了。
     *
     * 天数的算法见 [licenseDaysLeft] —— **必须用 expireAt 本地算**，不能用服务端下发的
     * `remainingDays`：那是握手那一刻的快照，断网一周它还停在 365。
     */
    private fun renderLicenseBadge(status: LicenseStatus) {
        val days = licenseDaysLeft(status)
        val text: String
        val colorRes: Int
        when {
            !status.authorized -> {
                text = "未授权"
                colorRes = R.color.brand_red
            }
            days != null && days <= 0 -> {
                text = "已过期"
                colorRes = R.color.brand_red
            }
            status.source == "LOCAL_PLACEHOLDER" -> {
                text = "已授权（本地）"
                colorRes = R.color.success_green
            }
            days == null -> {
                // 拿不到到期时间：只报状态，不编造天数
                text = if (status.offline) "离线" else "已授权"
                colorRes = if (status.offline) R.color.warning_amber else R.color.success_green
            }
            status.offline -> {
                text = "离线 · 剩 $days 天"
                colorRes = R.color.warning_amber
            }
            else -> {
                text = "已授权 · 剩 $days 天"
                colorRes = R.color.success_green
            }
        }

        val badge = binding.tvLicenseBadge
        badge.text = text
        badge.setTextColor(ContextCompat.getColor(this, colorRes))
        badge.setTypeface(null, Typeface.BOLD)
        // 读屏 / 色弱用户靠这句拿到状态；也是我给真机做断言用的抓手（dump 里 grep content-desc）
        badge.contentDescription = "授权状态：$text，点击输入授权密钥"
        badge.setOnClickListener {
            showActivationDialog(replacing = license.state.value.authorized)
        }
    }

    /**
     * 距到期还有多少天（向上取整：距到期 23 小时算「剩 1 天」，不是「剩 0 天」）。
     *
     * 优先用 `expireAt`（绝对时间戳，与是否联网无关）；只有它缺失时才退回服务端的
     * `remainingDays` 快照。返回 null = 真的不知道。
     */
    private fun licenseDaysLeft(status: LicenseStatus): Long? {
        val expireAt = status.expireAt ?: return status.remainingDays
        val ms = expireAt - System.currentTimeMillis()
        return kotlin.math.ceil(ms / 86_400_000.0).toLong()
    }

    /**
     * 输入授权密钥。
     *
     * [replacing] = 当前**已授权**，这次是换发/换绑：标题与正文都要说清「会覆盖本机授权」，
     * 正按钮写「更换」而不是「激活」——否则用户会以为自己只是补一次激活。
     */
    private fun showActivationDialog(replacing: Boolean = false) {
        if (isFinishing || activationDialog?.isShowing == true) return
        val input = EditText(this).apply {
            // 服务端 KEY_LENGTH=11、纯数字（crypto.randomInt(10)，**首位可能是 0**）。
            // 所以这里既不能要求首位非 0，也不能按数值处理——前导 0 会被吃掉。
            // ⚠️ 11 位数字与手机号同形，提示必须点明，否则用户会把手机号输进来。
            hint = "11 位数字授权码（不是手机号）"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(KEY_LENGTH))
            setSingleLine(true)
            setPadding(28, 18, 28, 18)
        }
        val current = license.state.value
        // 页内授权卡删除后，这个弹窗是**唯一**能看到「到期时间」和「当前密钥」的地方，
        // 所以正文要把两样都带上：只有「剩余 N 天」不足以让用户核对是哪张授权到期。
        val currentDesc = buildString {
            append(licenseDaysLeft(current)?.let { "剩 $it 天" } ?: "到期时间未知")
            current.expireAt?.let { append("，到期 ").append(formatDateTime(it)) }
        }
        val currentCode = current.code?.let { maskCode(it) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (replacing) "更换授权密钥" else "激活授权")
            .setMessage(
                PromptText.of(
                    if (replacing) {
                        "当前已授权（$currentDesc）" +
                            (currentCode?.let { "，密钥 $it" } ?: "") +
                            "。输入新密钥会**覆盖**本机授权，激活后本设备绑定到新授权。"
                    } else {
                        "输入管理员提供的授权密钥。激活后本设备会绑定到该授权。"
                    }
                )
            )
            .setView(input)
            .setPositiveButton(if (replacing) "更换" else "激活", null)
            .setNegativeButton("稍后", null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                // 从微信/短信复制常带空格、全角字符或不可见字符，先规范化再判长度。
                // 本地只判「长度与字符集」，**不判真假**——真伪只有服务端知道。
                val code = input.text?.toString().orEmpty()
                    .replace(Regex("[\\s\\u00A0\\u3000-]"), "")
                if (code.isEmpty()) {
                    input.error = "请输入授权密钥"
                    return@setOnClickListener
                }
                if (!Regex("^\\d{$KEY_LENGTH}$").matches(code)) {
                    input.error = "授权码是 $KEY_LENGTH 位数字（旧格式密钥已停用，请联系管理员换发）"
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

    /**
     * 密钥掩码。
     *
     * 服务端 `maskKey()` 对数字密钥返回 `•••••••{last4}`（前 7 位已隐去），
     * 这里检测到 `•` 就原样用，**绝不再套一层**，否则界面会出现两段掩码。
     */
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

        // 头顶的版本号就是手动检查入口。
        //
        // 以前唯一的手动入口是右上角那个 `tv_update`，而它默认 GONE、只有「已经发现新版本」
        // 才显形——于是「没有更新」的时候，用户根本没有地方能主动查一次。
        // 版本号常驻显示，点它即查（不受 6 小时的自动检查间隔限制）。
        binding.tvVersion.text = versionLabel()
        binding.tvVersion.setOnClickListener { checkOta(manual = true) }
    }

    private fun versionLabel(): String = "v${BuildConfig.VERSION_NAME} · 检查更新"

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

    /**
     * 检查更新。
     *
     * `manual = true` 时必须有明确的反馈，且要**区分两种 null**：
     * - `OtaState.UpToDate` → 确实没有新版，说「已是最新版本」；
     * - 其余（`Idle`）→ 请求本身失败（连不上 / 服务端维护），说「连不上更新服务」。
     *
     * 这两句混成一句「已是最新版本，或暂时无法连接更新服务」是没法排查的：
     * 用户既不知道要不要重试，也不知道自己是不是已经是最新。
     */
    private fun checkOta(manual: Boolean) {
        if (manual) {
            // 头顶版本号临时变成「检查中…」，给一个即时反馈（网络慢时不然像是没反应）
            binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} · 检查中…"
        }
        uiScope.launch {
            try {
                val manifest = OtaManager.check(this@MainActivity, manual)
                if (manifest != null) {
                    showUpdatePrompt(manifest)
                } else if (manual) {
                    when (OtaManager.state.value) {
                        is OtaState.UpToDate -> toast("已是最新版本 v${BuildConfig.VERSION_NAME}")
                        else -> toast("连不上更新服务，请检查网络后重试")
                    }
                }
            } finally {
                binding.tvVersion.text = versionLabel()
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
                binding.tvUpdate.setOnClickListener { beginInstall(state) }

                // **只有「这次刚下完」才自动弹安装框**：状态从 Downloading/Verifying 转过来。
                // 冷启动 `restoreReady()` 恢复的 Ready（lastOtaState == null）只亮顶栏不弹 —
                // 否则用户每次打开 App 都会被一个「有包没装」的对话框拦住。
                val justFinished = lastOtaState is OtaState.Downloading ||
                    lastOtaState is OtaState.Verifying
                if (justFinished && installPromptedVersion != state.manifest.versionCode) {
                    pendingInstallPrompt = state
                    maybeShowInstallPrompt()
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
        // 最后赋值：上面的 Ready 分支要读「上一个状态」判断是不是刚下完
        lastOtaState = state
    }

    /**
     * 把挂起的安装提示弹出来（若界面此刻真的在前台）。
     *
     * 由两处触发：Ready 到达时（界面可见则立即弹）、以及 `onResume`（后台下载完成 → 用户切回来）。
     */
    private fun maybeShowInstallPrompt() {
        val state = pendingInstallPrompt ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (isFinishing || updateDialog?.isShowing == true) return
        pendingInstallPrompt = null
        installPromptedVersion = state.manifest.versionCode
        showInstallPrompt(state)
    }

    /**
     * 「更新已下载完成」的自动弹窗（vc50）。
     *
     * 为什么需要：下载完成后原来只把顶栏那个小字改成「安装 x.y.z」，
     * 而此刻用户多半在抖音里 —— 包下好了却没人知道，实测有设备挂着旧版本三天没升。
     * 现在下完就弹，且「立即安装」直接进系统安装器，不用再找入口。
     *
     * 三种前置状态分开处理，不要合成一句「安装失败」让用户猜：
     * - 任务运行中：安装会杀进程、打断自动化 ⇒ 只给「知道了」，让人先停任务；
     * - 未授权「安装未知应用」：给「去设置」，开启后回来再装（OtaInstaller 会负责跳转）；
     * - 其余：正常给「立即安装」。
     */
    private fun showInstallPrompt(state: OtaState.Ready) {
        if (isFinishing || updateDialog?.isShowing == true) return
        val manifest = state.manifest
        val appName = getString(R.string.app_name)
        val busy = TaskEngine.progress.value.state.isActive
        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            packageManager.canRequestPackageInstalls()

        val message = buildString {
            append("**${manifest.versionName}**（${manifest.versionCode}）已下载完成")
            if (manifest.size > 0) append("，${formatBytes(manifest.size)}")
            append("。\n\n")
            when {
                busy -> append("任务正在运行，现在升级会**中断自动化**。请先停止任务，再点顶部的「安装」。")
                !canInstall -> append("首次升级要先允许「$appName」**安装应用**。点「去设置」开启后回来再装。")
                else -> append("点「立即安装」打开系统安装器。装完 App 会重启，授权、权限、已关注名单都保留。")
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("更新已下载完成")
            .setMessage(PromptText.of(message))
            .setNegativeButton(if (busy) "知道了" else "稍后", null)
        when {
            busy -> Unit
            !canInstall -> builder.setPositiveButton("去设置") { _, _ -> beginInstall(state) }
            else -> builder.setPositiveButton("立即安装") { _, _ -> beginInstall(state) }
        }
        val dialog = builder.create()
        dialog.setOnDismissListener { updateDialog = null }
        updateDialog = dialog
        dialog.show()
        LogStore.i(TAG, "自动弹出安装提示：${manifest.versionName}(busy=$busy canInstall=$canInstall)")
    }

    /**
     * 真正调起系统安装器。
     *
     * 统一入口：顶栏「安装」、安装弹窗、通知栏「安装」都走这里，
     * 因为 [OtaInstaller] 里带着「任务运行中拒绝」和「未授权跳设置」两条判断，绕过去就会漏。
     */
    private fun beginInstall(state: OtaState.Ready) {
        OtaInstaller.install(this, state.file).onFailure { toast(it.message ?: "安装失败") }
    }

    /**
     * 通知栏「安装」按钮打开本界面时携带的版本号 → 直接进安装。
     *
     * 处理完立刻把 extra 摘掉：`singleTop` 下同一个 Intent 会被 `onNewIntent` 复用，
     * 不摘的话回前台/重建时会重复触发一次安装器。
     */
    private fun handleInstallIntent(intent: Intent?) {
        val want = intent?.getIntExtra(EXTRA_INSTALL_NOW, 0) ?: 0
        if (want <= 0) return
        intent?.removeExtra(EXTRA_INSTALL_NOW)
        val state = OtaManager.state.value
        if (state is OtaState.Ready && state.manifest.versionCode == want) {
            installPromptedVersion = want
            pendingInstallPrompt = null
            beginInstall(state)
        } else {
            LogStore.w(TAG, "通知里的安装请求已失效：want=$want state=$state")
            toast("安装包已失效，请重新检查更新")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
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

        // 直播互动是独立流程（不同时跑关注）。原来由「直播互动」页签的「开始服务」触发，
        // vc55 删页签后改成这张卡末尾的链接 —— 少了它 M3 就没有任何入口。
        binding.contentAuto.tvStartLive.setOnClickListener { confirmLiveInteract() }

        // 养号页的「评论配置 ›」：评论参数现在在自动关注页下半部分，切过去并滚到那张卡
        binding.contentNurture.tvCommentConfig.setOnClickListener {
            selectTab(0)
            val scroll = binding.contentAuto.root
            val card = binding.contentAuto.cardLiveParams
            scroll.post { scroll.smoothScrollTo(0, card.top) }
        }
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
            // 未授权时按钮是禁用的，文案要自己说明为什么点不动——
            // 灰按钮不给理由，用户只会以为 APP 坏了
            else -> if (license.state.value.authorized) "开始服务" else "请先激活授权"
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
            TAB_NURTURE -> confirmNurture()
            // 直播互动已不在页签里，入口是自动关注页那张卡的「只跑直播互动 ›」
            else -> confirmFollow()
        }
    }

    /**
     * 启动前的确认弹窗：**只放能影响决策的信息**（前提 / 参数 / 边界），粗体由 [PromptText] 解析。
     *
     * 为什么不再写 5 段长文（2026-10-30 用户要求「提示关键信息就好」）：
     * 旧文案 150~200 字，最要命的一条「先手动进直播间」被埋在第三段，
     * 而「读评论 → 挑人 → 点资料卡」这类过程描述对用户决策毫无价值——
     * 他要点「开始」时只想确认两件事：**现在能不能跑**、**这次跑的是什么参数**。
     */
    private fun showKeyConfirm(title: String, body: String, onStart: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(PromptText.of(body))
            .setPositiveButton("开始") { _, _ -> onStart() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 「开始服务」= M2 主业务：直播间评论随机关注 */
    private fun confirmFollow() {
        val s = store.current
        showKeyConfirm(
            "开始关注",
            "· **请先手动进入目标直播间，停在直播画面上**\n" +
                "· 目标 **${s.followTarget}** 人 · 今日上限 **${s.dailyLimit}** · " +
                "每次间隔约 **${s.scanIntervalLabel}**（随机浮动）\n" +
                "· 只做关注：不私信、不点赞、不刷评论，也不会自己找直播间\n" +
                "· 随时可用悬浮窗或通知栏停止"
        ) { startFlow(FollowFromLiveFlow(), "已启动直播关注") }
    }

    /** 「开始服务」= M3 主业务：直播间互动（发评论 / 点赞 / 连麦） */
    private fun confirmLiveInteract() {
        val s = store.current
        val mode = if (s.commentMode == 1) "预设评论" else "复制最新评论"
        val likeLine = "· 每 **${s.likeTrigger}** 条赞 **${s.likeCountMin}~${s.likeCountMax}** 次" +
            (if (s.micRequest) " · 申请连麦" else "")
        showKeyConfirm(
            "开始直播互动",
            "· **请先手动进入目标直播间，停在直播画面上**\n" +
                "· 每 **${s.commentGapMin}~${s.commentGapMax}** 秒发 1 条（$mode）\n" +
                likeLine + "\n" +
                "· 无总量目标，手动停止；遇「评论过快」类提示立刻停机"
        ) { startFlow(LiveInteractFlow(), "已启动直播互动") }
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

        val interactLine = if (browseOnly) {
            "· 只刷视频，不做任何互动（不点赞、不收藏）"
        } else {
            "· 点赞 **${s.probLike}%** · 收藏 **${s.probCollect}%** · **$tagCount** 条文案标签生效"
        }

        showKeyConfirm(
            "开始养号",
            "· **抖音已打开，停在有右侧点赞按钮的视频画面**\n" +
                "· 每条停留 **1.5~90 秒**（${pace}节奏，多数 2~12 秒），每 20~40 屏休息一次\n" +
                interactLine + "\n" +
                "· 运行 **$minutes** 分钟后自动停；**不关注任何人（不涨粉）**"
        ) { startFlow(NurtureFlow(), "已启动刷视频养号") }
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
                        append("请先点「无障碍」一栏的「去授权」，在系统设置中找到「${getString(R.string.accessibility_service_label)}」并开启。")
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
                    val actions = Actions(service, Humanizer(Settings.HUMANIZE_JITTER))
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

        /**
         * 通知栏「安装」按钮带过来的 versionCode。
         *
         * [OtaService] 发完成通知时写进 Intent，本界面读到就直接调起安装器 ——
         * 这样「下载完 → 点通知 → 装」只要一次点击，不必先进界面再找那个小字入口。
         */
        const val EXTRA_INSTALL_NOW = "install_now_version"

        /** 授权密钥长度。与服务端 `security.js` 的 `KEY_LENGTH` 必须一致 */
        private const val KEY_LENGTH = 11

        /** 授权复核周期：距上次成功握手超过这个时长，回前台时重握手一次 */
        private const val AUTHORIZATION_REFRESH_MS = 6 * 60 * 60 * 1000L

        /** 落盘日志文件名。与 DebugChannel 里的 CMD_FILE/LOG_FILE 同一目录，联调用同一个文件 */
        private const val LOG_FILE_NAME = "runtime.log"

        /**
         * 前台期间重读权限状态的间隔。
         *
         * 2 秒是「用户从设置页切回来、眼睛落在三个点上」的容忍上限；
         * 一次判断只读两个 Secure 值 + 一个实例引用，耗时可忽略。
         */
        private const val PERMISSION_POLL_MS = 2_000L

        /** 授权徽标「剩 N 天」的重算间隔（天数只按天变，一小时一次足够） */
        private const val LICENSE_BADGE_REFRESH_MS = 3_600_000L

        /**
         * 「权限被重置」提醒的宽限期。
         *
         * 无障碍服务由系统绑定，进程重启后要一点时间才连上；这段窗口里
         * `isAccessibilityServiceEnabled()` 是 false，立刻判会**每次冷启动都误报**。
         * 2.5 秒足够系统绑完，也短到用户还在界面上、看到提示不会莫名其妙。
         */
        private const val PERMISSION_WARN_GRACE_MS = 2_500L

        /**
         * 定时休息两个数字框的合法范围。
         *
         * 下界不取 0：`FollowFromLiveFlow` 的判据是
         * `intervalRest && restAfterN > 0 && followed % restAfterN == 0`，
         * 填 0 时那个 `> 0` 直接把整个休息分支短路掉 —— 开关明明开着却永不休息，
         * 日志里也看不出来。`restSeconds = 0` 同理，等于"休息 0 秒"。
         */
        private const val REST_AFTER_N_MIN = 1
        private const val REST_AFTER_N_MAX = 500

        /**
         * 休息秒数下限是 **1**（原为 5）。
         *
         * vc49 设成 5 时踩了个隐蔽的坑：数字框逐键夹取，「想打 30 敲 3」当场被改成 5，
         * 于是「任何首位 1~4 的数字都输不进去」，用户理解成「最大只能填 5」。
         * 夹取逻辑已在 vc54 改成失焦时执行，这里把下限放开到 1，不再有隐式约束。
         */
        private const val REST_SECONDS_MIN = 1
        private const val REST_SECONDS_MAX = 600

        /**
         * 数字框一律最多 6 位。
         *
         * 不按 `max.toString().length` 取：那样「1200」会被静默截成「120」，
         * 既不提示、也不等于上限 600，用户以为填进去了。超范围交给失焦时的夹取 + toast。
         */
        private const val MAX_DIGITS = 6

        private val TAB_NAMES = listOf("自动关注", "养号助手")

        private const val TAB_NURTURE = 1
    }
}
