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
import com.example.dgg.util.AppPermissions
import com.example.dgg.util.DouyinLauncher
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：三个页签 + 参数双向绑定 + 权限状态实时渲染。
 *
 * M0 阶段的边界：界面与参数已经真实可用（改动落盘、冷启恢复、权限状态读系统），
 * 但「开始服务」尚未接入任务引擎——那属于 M1/M2。此处不做任何假装成功的提示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore

    /** 服务端未接入，当前注入本地占位实现 */
    private val license: LicenseProvider = LocalLicenseProvider()

    private lateinit var tabs: List<TextView>
    private lateinit var indicators: List<View>
    private lateinit var pages: List<View>

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
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后必须重新读一次，否则用户授权了却仍显示未授权
        refreshPermissionState()
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
                toast("无障碍服务将在 M1 实现，当前设置页中暂无本应用条目")
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
            LogStore.w(TAG, "点击「开始服务」，任务引擎尚未接入（M0 阶段）")
            AlertDialog.Builder(this)
                .setTitle("任务引擎未接入")
                .setMessage(
                    "当前为 M0 基础设施阶段：参数持久化、日志、权限引导、授权占位已可用。\n\n" +
                        "「开始服务」将在 M1（无障碍底座）与 M2（自动关注闭环）接通后真正启动任务。\n\n" +
                        "现在不会向抖音发出任何操作。"
                )
                .setPositiveButton("知道了", null)
                .show()
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

        binding.fabMenu.setOnClickListener { toast("菜单（待实现）") }

        // 「去配置」「评论配置」都跳到直播互动页
        binding.contentAuto.tvGoConfig.setOnClickListener {
            selectTab(2)
            toast("已切换到「直播互动」，评论参数在底部")
        }
        binding.contentNurture.tvCommentConfig.setOnClickListener { selectTab(2) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "主界面"
        private val TAB_NAMES = listOf("自动关注", "养号助手", "直播互动")
    }
}
