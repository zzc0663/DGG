package com.example.dgg.engine

import android.content.Context
import android.os.SystemClock
import com.example.dgg.data.LogStore
import com.example.dgg.data.Settings
import com.example.dgg.data.SettingsStore
import com.example.dgg.engine.risk.AnomalyGuard
import com.example.dgg.engine.risk.Humanizer
import com.example.dgg.service.DggAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务引擎：步骤序列执行 + 熔断 + 暂停/恢复/停止。
 *
 * 三条硬性约束（计划 §3）都实现在这里：
 * 1. **每步之前查风控**（[AnomalyGuard]），命中立即终止，不重试；
 * 2. **步间延迟带抖动**（[Humanizer.delayMs]），不用固定间隔；
 * 3. **连续失败熔断**，避免在定位失效时无限重试把账号拖进风控。
 *
 * 单例：前台服务、悬浮窗、主界面读同一份 [progress]。
 */
object TaskEngine {

    private const val TAG = "任务引擎"
    private const val MAX_ATTEMPTS_PER_STEP = 3
    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val FAILURE_BACKOFF_MS = 800L
    private const val POLL_INTERVAL_MS = 200L
    private const val PAUSE_POLL_MS = 200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _progress = MutableStateFlow(EngineProgress())
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    private var job: Job? = null
    private val finished = AtomicBoolean(true)

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    val isRunning: Boolean get() = job?.isActive == true

    // ==================== 生命周期 ====================

    fun start(context: Context, steps: List<Step>) {
        if (job?.isActive == true) {
            LogStore.w(TAG, "任务已在运行，忽略重复启动")
            return
        }
        val service = DggAccessibilityService.current
        if (service == null) {
            LogStore.e(TAG, "无障碍服务未连接，拒绝启动任务")
            publish(EngineState.ERROR, "无障碍服务未连接，请先在系统设置中开启")
            return
        }
        if (steps.isEmpty()) {
            LogStore.e(TAG, "步骤列表为空，拒绝启动")
            publish(EngineState.ERROR, "步骤列表为空")
            return
        }

        val appContext = context.applicationContext
        val settings = SettingsStore.get(appContext).current
        val humanizer = Humanizer(settings.jitterPercent)
        val actions = Actions(service, humanizer)
        val config = LocatorConfigLoader.load(appContext)
        val detector = PageDetector(config, actions)

        paused = false
        stopRequested = false
        finished.set(false)
        _progress.value = EngineProgress(
            state = EngineState.RUNNING,
            stepTotal = steps.size,
            target = settings.followTarget,
            message = "启动中",
            updatedAt = System.currentTimeMillis()
        )
        LogStore.i(
            TAG,
            "启动任务：${steps.size} 步，抖动 ±${settings.jitterPercent}%，目标 ${settings.followTarget}，" +
                "扫描间隔 ${settings.scanIntervalMs}ms"
        )
        job = scope.launch { runFlow(actions, detector, humanizer, config, settings, steps) }
    }

    fun pause() {
        if (job?.isActive != true) {
            LogStore.w(TAG, "当前无运行任务，忽略暂停")
            return
        }
        paused = true
        _progress.value = _progress.value.copy(state = EngineState.PAUSED, message = "已暂停", updatedAt = System.currentTimeMillis())
        LogStore.i(TAG, "任务暂停")
    }

    fun resume() {
        if (job?.isActive != true) {
            LogStore.w(TAG, "当前无运行任务，忽略恢复")
            return
        }
        paused = false
        _progress.value = _progress.value.copy(state = EngineState.RUNNING, message = "已恢复", updatedAt = System.currentTimeMillis())
        LogStore.i(TAG, "任务恢复")
    }

    fun stop(reason: String = "手动停止") {
        val wasRunning = job?.isActive == true
        stopRequested = true
        paused = false
        job?.cancel()
        if (wasRunning) {
            finish(EngineState.STOPPED, reason)
        } else {
            // 已经处于终态（例如刚因风控停机）时不能再用 STOPPED 覆盖：
            // 那会把「风控停机」这条最重要的结论洗成一句普通的「已停止」。
            LogStore.d(TAG, "当前无运行任务，忽略停止请求（保持 ${_progress.value.state.label}）")
        }
    }

    // ==================== 主循环 ====================

    private suspend fun runFlow(
        actions: Actions,
        detector: PageDetector,
        humanizer: Humanizer,
        config: LocatorConfig,
        settings: Settings,
        steps: List<Step>
    ) {
        try {
            var consecutiveFailures = 0

            for ((index, step) in steps.withIndex()) {
                if (stopRequested) break
                awaitResume()

                // —— 硬性约束 1：每步之前查风控，命中即停，不重试 ——
                AnomalyGuard.check(actions.root())?.let { hit ->
                    finish(EngineState.STOPPED_ANOMALY, "命中风控提示 $hit，已停止且不做重试")
                    return
                }

                val detection = detector.detect()
                _progress.value = _progress.value.copy(
                    stepIndex = index + 1,
                    stepLabel = step.label,
                    page = detection.page,
                    updatedAt = System.currentTimeMillis()
                )
                LogStore.i(TAG, "步骤 ${index + 1}/${steps.size}：${step.label}｜当前页面 ${detection.page.name}")

                var ok = false
                var attempt = 0
                while (attempt < MAX_ATTEMPTS_PER_STEP && !ok && !stopRequested) {
                    attempt++
                    ok = runCatching { execute(step, actions, detector, config, humanizer) }
                        .onFailure { LogStore.e(TAG, "步骤「${step.label}」抛出异常：${it.message}") }
                        .getOrDefault(false)
                    if (!ok) {
                        LogStore.w(TAG, "步骤「${step.label}」第 $attempt/$MAX_ATTEMPTS_PER_STEP 次未成功")
                        if (attempt < MAX_ATTEMPTS_PER_STEP) delay(humanizer.delayMs(FAILURE_BACKOFF_MS))
                    }
                }

                consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
                _progress.value = _progress.value.copy(
                    consecutiveFailures = consecutiveFailures,
                    updatedAt = System.currentTimeMillis()
                )

                // —— 硬性约束 3：连续失败熔断 ——
                if (!ok && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    finish(
                        EngineState.ERROR,
                        "连续 $consecutiveFailures 个步骤未能完成，熔断停止（多半是定位配置与当前抖音版本不匹配）"
                    )
                    return
                }

                // —— 硬性约束 2：步间延迟取区间随机，不用固定值 ——
                if (index < steps.lastIndex) {
                    delay(humanizer.delayMs(settings.scanIntervalMs.toLong().coerceIn(300, 8_000)))
                }
            }

            finish(EngineState.STOPPED, if (stopRequested) "已手动停止" else "流程执行完毕")
        } catch (e: CancellationException) {
            LogStore.i(TAG, "任务协程被取消：${e.message}")
            throw e
        } catch (t: Throwable) {
            finish(EngineState.ERROR, "引擎异常：${t.message}")
        }
    }

    private suspend fun execute(
        step: Step,
        actions: Actions,
        detector: PageDetector,
        config: LocatorConfig,
        humanizer: Humanizer
    ): Boolean = when (step) {
        is Step.Click -> actions.clickByName(step.targetName, config)

        is Step.Input -> {
            val target = config.target(step.targetName)
            if (target == null) {
                LogStore.e(TAG, "定位配置缺少目标「${step.targetName}」")
                false
            } else {
                actions.typeText(target, step.text)
            }
        }

        is Step.Back -> {
            var allOk = true
            repeat(step.times) {
                if (!actions.back()) allOk = false
                delay(humanizer.delayMs(400))
            }
            allOk
        }

        is Step.Sleep -> {
            delay(humanizer.delayMs(step.ms))
            true
        }

        is Step.WaitForPage -> waitForPage(step.page, step.timeoutMs, detector, humanizer)
    }

    private suspend fun waitForPage(
        page: PageState,
        timeoutMs: Long,
        detector: PageDetector,
        humanizer: Humanizer
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (stopRequested) return false
            val detection = detector.detect()
            _progress.value = _progress.value.copy(page = detection.page, updatedAt = System.currentTimeMillis())
            if (detection.page == page) return true
            delay(humanizer.delayMs(POLL_INTERVAL_MS))
        }
        LogStore.w(TAG, "等待页面 ${page.name} 超时（${timeoutMs}ms），当前 ${detector.detect().summary()}")
        return false
    }

    private suspend fun awaitResume() {
        if (!paused) return
        LogStore.i(TAG, "任务处于暂停，等待恢复")
        while (paused && !stopRequested) delay(PAUSE_POLL_MS)
        if (!stopRequested) LogStore.i(TAG, "任务已恢复")
    }

    private fun finish(state: EngineState, message: String) {
        if (!finished.compareAndSet(false, true)) return
        _progress.value = _progress.value.copy(state = state, message = message, updatedAt = System.currentTimeMillis())
        when (state) {
            EngineState.STOPPED_ANOMALY -> LogStore.e(TAG, "任务终止：$message")
            EngineState.ERROR -> LogStore.e(TAG, "任务异常终止：$message")
            else -> LogStore.i(TAG, "任务结束：$message")
        }
    }

    private fun publish(state: EngineState, message: String) {
        _progress.value = _progress.value.copy(state = state, message = message, updatedAt = System.currentTimeMillis())
        LogStore.i(TAG, "$message")
    }
}
