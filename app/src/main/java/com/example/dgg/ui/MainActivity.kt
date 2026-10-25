package com.example.dgg.ui

import android.Manifest
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.dgg.R
import com.example.dgg.data.LogStore
import com.example.dgg.data.Settings
import com.example.dgg.data.SettingsStore
import com.example.dgg.data.license.LicenseProvider
import com.example.dgg.data.license.LocalLicenseProvider
import com.example.dgg.databinding.ActivityMainBinding
import com.example.dgg.engine.Actions
import com.example.dgg.engine.EngineProgress
import com.example.dgg.engine.EngineState
import com.example.dgg.engine.LocatorConfigLoader
import com.example.dgg.engine.PageDetector
import com.example.dgg.engine.SmokeFlow
import com.example.dgg.engine.TaskEngine
import com.example.dgg.engine.risk.Humanizer
import com.example.dgg.service.DggAccessibilityService
import com.example.dgg.service.EngineController
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：三个页签 + 参数双向绑定 + 权限状态实时渲染 + 引擎状态跟随。
 *
 * 边界（M1）：界面上「开始服务」启动的是**自检流程**——拉起抖音、搜索关键词、
 * 切到「用户」tab，用来验证定位与操作链路是否成立。它不会关注任何人，
 * 自动关注闭环在 M2 接入。这个区别在弹窗里明说，不做含糊的成功提示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    /** 服务端未接入，当前注入本地占位实现 */
    private val license: LicenseProvider = LocalLicenseProvider()

    private lateinit var tabs: List<TextView>
    private lateinit var indicators: List<View>
    private lateinit var pages: List<View>

    /** 收集引擎状态用的作用域；Activity 销毁时整体取消，避免把 binding 泄漏给常驻的单例 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null

    private val modeDescriptions = listOf(
        "挂机涨粉：搜索关键词用户，浏览粉丝列表互动涨粉",
        "垂直养号：按行业关键词持续互动，账号垂直度更高",
        "首页连播：从推荐流进入直播间，模拟真实浏览"
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
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = SettingsStore.get(this)
        LogStore.i(TAG, "主界面创建，参数已加载（目标=${store.current.followTarget}，日上限=${store.current.dailyLimit}）")

        setupTabs()
        setupPermissionCards()
        bindAllSettings()
        setupActions()
        renderLicense()
        setupEngineObserver()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后必须重新读一次，否则用户授权了却仍显示未授权
        refreshPermissionState()
    }

    override fun onDestroy() {
        progressJob?.cancel()
        progressJob = null
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

    private fun refreshPermissionState() {
        val state = AppPermissions.state(this)
        listOf(
            binding.contentAuto.permCard,
            binding.contentNurture.permCard,
            binding.contentLive.permCard
        ).forEach { it.render(state) }

        LogStore.d(
            TAG,
            "权限状态：无障碍=${state.accessibility} 悬浮窗=${state.overlay} " +
                "通知=${state.notification} 电池优化忽略=${state.batteryUnrestricted}"
        )
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

        bindIntField(c.etFollowTarget, s.followTarget) { v -> store.edit { it.copy(followTarget = v) } }
        bindIntField(c.etScanInterval, s.scanIntervalMs) { v -> store.edit { it.copy(scanIntervalMs = v) } }
        bindIntField(c.etDailyLimit, s.dailyLimit) { v -> store.edit { it.copy(dailyLimit = v) } }
        bindIntField(c.etJitter, s.jitterPercent) { v -> store.edit { it.copy(jitterPercent = v) } }

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

        bindIntField(c.etRunMinutes, s.runMinutes) { v -> store.edit { it.copy(runMinutes = v) } }

        bindSegmented(
            listOf(c.segModeIdle, c.segModeVertical, c.segModeHome), s.nurtureMode, grouped = false
        ) { index ->
            store.edit { it.copy(nurtureMode = index) }
            c.tvModeDesc.text = modeDescriptions.getOrElse(index) { "" }
        }

        bindSlider(c.sliderLike, c.tvPctLike, s.probLike) { v -> store.edit { it.copy(probLike = v) } }
        bindSlider(c.sliderCollect, c.tvPctCollect, s.probCollect) { v ->
            store.edit { it.copy(probCollect = v) }
        }
        bindSlider(c.sliderComment, c.tvPctComment, s.probComment) { v ->
            store.edit { it.copy(probComment = v) }
        }
        bindSlider(c.sliderShare, c.tvPctShare, s.probShare) { v -> store.edit { it.copy(probShare = v) } }
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

    private fun renderLicense() {
        val status = license.status()
        val c = binding.contentAuto

        c.tvLicenseStatus.text = when {
            !status.authorized -> "未授权"
            status.source == LocalLicenseProvider.SOURCE -> "已授权（本地占位）"
            else -> "已授权"
        }
        c.tvLicenseStatus.setTextColor(
            ContextCompat.getColor(this, if (status.authorized) R.color.success_green else R.color.brand_red)
        )
        c.tvLicenseStatus.setTypeface(null, Typeface.BOLD)

        c.tvLicenseExpire.text = status.expireAt?.let { formatDateTime(it) } ?: "—"
        c.tvLicenseCode.text = status.code?.let { maskCode(it) } ?: "—"
    }

    private fun formatDateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    private fun maskCode(code: String): String = when {
        code.length <= 4 -> "••••"
        else -> code.take(4) + "••••" + code.takeLast(4)
    }

    // ==================== 动作 ====================

    private fun setupActions() {
        binding.tvLog.setOnClickListener {
            LogStore.i(TAG, "打开运行日志")
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.tvUnbind.setOnClickListener {
            val result = license.unbind()
            val message = result.exceptionOrNull()?.message ?: "已解绑"
            LogStore.w(TAG, "解绑请求：$message")
            AlertDialog.Builder(this)
                .setTitle("解绑")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }

        binding.btnStartService.setOnClickListener {
            when (TaskEngine.progress.value.state) {
                EngineState.RUNNING -> {
                    TaskEngine.pause()
                    LogStore.i(TAG, "点击「暂停」")
                }

                EngineState.PAUSED -> {
                    TaskEngine.resume()
                    LogStore.i(TAG, "点击「继续」")
                }

                else -> confirmAndStartSmokeFlow()
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
        binding.tvEngineStatus.text = progress.brief()
        binding.engineDot.setBackgroundResource(
            when {
                progress.state == EngineState.PAUSED -> R.drawable.bg_dot_amber
                active -> R.drawable.bg_dot_green
                else -> R.drawable.bg_dot_red
            }
        )

        binding.tvEngineAction.text = if (active) "停止" else "关闭"
        binding.tvEngineAction.setOnClickListener {
            if (TaskEngine.progress.value.state.isActive) {
                EngineController.stop("界面状态条停止")
            } else {
                binding.engineStatusBar.visibility = View.GONE
            }
        }

        binding.btnStartService.text = when (progress.state) {
            EngineState.RUNNING -> "暂停"
            EngineState.PAUSED -> "继续"
            else -> "开始服务"
        }
    }

    /**
     * 「开始服务」在 M1 阶段只跑自检流程。必须先说清楚它不会关注任何人——
     * 让用户以为已经在涨粉，是这类工具最不能犯的错。
     */
    private fun confirmAndStartSmokeFlow() {
        AlertDialog.Builder(this)
            .setTitle("开始服务（M1 自检）")
            .setMessage(
                "当前阶段接入的是自检流程：拉起抖音 → 搜索关键词「${SmokeFlow.DEFAULT_KEYWORD}」" +
                    " → 切到「用户」tab。\n\n" +
                    "它只验证界面定位与操作链路是否成立，不会替你关注任何人——" +
                    "自动关注流程在 M2 接入。\n\n" +
                    "运行期间屏幕会被自动操作，请勿手动干扰；随时可用悬浮窗或通知栏停止。"
            )
            .setPositiveButton("开始") { _, _ -> startSmokeFlow() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startSmokeFlow() {
        LogStore.i(TAG, "启动 M1 自检流程")
        EngineController.start(this, SmokeFlow.build())
            .onSuccess {
                toast("自检流程已启动，请勿手动操作抖音")
            }
            .onFailure { error ->
                val reason = error.message ?: "启动失败"
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
    }

    /** 悬浮菜单：M1 阶段只放排障要用的三件事，不做花架子 */
    private fun showSelfCheckMenu() {
        val items = arrayOf("识别当前页面（只读，不发任何操作）", "打开系统无障碍设置", "查看运行日志")
        AlertDialog.Builder(this)
            .setTitle("自检")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> detectCurrentPage()
                    1 -> openPermission(PermissionCardView.Kind.ACCESSIBILITY)
                    else -> startActivity(Intent(this, LogActivity::class.java))
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        private val TAB_NAMES = listOf("自动关注", "养号助手", "直播互动")
    }
}
