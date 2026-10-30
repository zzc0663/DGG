package com.doujiajia.app.engine

import android.content.Context
import com.doujiajia.app.data.LogStore
import com.doujiajia.app.data.SettingsStore
import com.doujiajia.app.data.license.LicenseGate
import com.doujiajia.app.engine.risk.AnomalyGuard
import com.doujiajia.app.engine.risk.Humanizer
import com.doujiajia.app.service.DggAccessibilityService
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
 * 任务引擎：开停控制 + 状态发布 + 风控兜底。
 *
 * 具体跑什么由 [Flow] 决定（M1 的步骤序列、M2 的直播间关注闭环都是 Flow）。
 * 引擎自己只死守三条不可绕过的约束：
 * 1. 启动前、以及 Flow 每次 [FlowContext.settle] 之后都查风控，命中即停、**不重试**；
 * 2. 终态一旦判定，不被后续 `stop()` 覆盖（否则「风控停机」会被洗成「已停止」）；
 * 3. 暂停期间不产生任何风控检查之外的界面动作（动作全部发生在 `awaitResume()` 之后）。
 *
 * 单例：主界面、前台服务、悬浮窗读同一份 [progress]。
 */
object TaskEngine {

    private const val TAG = "任务引擎"
    private const val PAUSE_POLL_MS = 200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _progress = MutableStateFlow(EngineProgress())
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    private var job: Job? = null
    private val finished = AtomicBoolean(true)

    /**
     * 上一次启动的流程。终态后「重试」要用它重新开一轮，
     * 用户不必回 App 再点一次「开始服务」（那还得重新进直播间）。
     *
     * 三个 Flow 都是无状态的（所有变量都在 `run()` 里），实例可以直接复用。
     */
    @Volatile
    var lastFlow: Flow? = null
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    val isRunning: Boolean get() = job?.isActive == true

    /** 是否处于暂停（手动暂停与自愈暂停共用这一个标志，见 [pauseForRecovery]） */
    val isPausedNow: Boolean get() = paused

    // ==================== 生命周期 ====================

    fun start(context: Context, flow: Flow) {
        if (job?.isActive == true) {
            LogStore.w(TAG, "任务已在运行，忽略重复启动")
            return
        }
        // 第二道授权门（第一道在 `EngineController.start`）。
        //
        // 这里才是真正开协程的地方：将来若有人新增调用点（或写调试脚本）直接调它，
        // 「没授权不许跑」就不该靠调用方自觉。用户 2026-10-31 的要求是
        // **没有授权禁止任何运行**，所以在这个最后关口再挡一次，代价只是两次 volatile 读。
        if (!LicenseGate.isAuthorized()) {
            val reason = LicenseGate.reason()
            LogStore.e(TAG, "拒绝启动：授权无效（$reason）")
            publish(EngineState.ERROR, "授权无效：$reason")
            return
        }
        val service = DggAccessibilityService.current
        if (service == null) {
            LogStore.e(TAG, "无障碍服务未连接，拒绝启动任务")
            publish(EngineState.ERROR, "无障碍服务未连接，请先在系统设置中开启")
            return
        }

        val appContext = context.applicationContext
        val settings = SettingsStore.get(appContext).current
        val humanizer = Humanizer(settings.jitterPercent)
        val actions = Actions(service, humanizer)
        val config = LocatorConfigLoader.load(appContext)
        val detector = PageDetector(config, actions)

        val ctx = FlowContext(
            appContext = appContext,
            actions = actions,
            detector = detector,
            humanizer = humanizer,
            config = config,
            settings = settings,
            mutate = { transform -> _progress.value = transform(_progress.value) },
            stopFlag = { stopRequested },
            resumeWaiter = { awaitResume() },
            pauseAuto = { reason -> pauseForRecovery(reason) },
            resumeAuto = { resume() },
            pausedFlag = { paused }
        )

        lastFlow = flow
        paused = false
        stopRequested = false
        finished.set(false)
        _progress.value = EngineProgress(
            state = EngineState.RUNNING,
            target = settings.followTarget,
            message = "启动中",
            updatedAt = System.currentTimeMillis()
        )
        LogStore.i(
            TAG,
            "启动流程「${flow.name}」：目标 ${settings.followTarget}，每日上限 ${settings.dailyLimit}，" +
                "抖动 ±${settings.jitterPercent}%，扫描间隔 ${settings.scanIntervalLabel}"
        )

        job = scope.launch {
            try {
                AnomalyGuard.check(actions.root())?.let { throw AnomalyStopException(it) }
                flow.run(ctx)
                finish(EngineState.STOPPED, if (stopRequested) "已手动停止" else "流程执行完毕")
            } catch (e: AnomalyStopException) {
                finish(EngineState.STOPPED_ANOMALY, "命中风控提示 ${e.hit}，已停止且不做重试")
            } catch (e: FlowAbortException) {
                finish(EngineState.ERROR, e.message ?: "流程中止")
            } catch (e: CancellationException) {
                LogStore.i(TAG, "任务协程被取消：${e.message}")
                throw e
            } catch (t: Throwable) {
                finish(EngineState.ERROR, "引擎异常：${t.message}")
            }
        }
    }

    fun pause() {
        hold(EngineState.PAUSED, "已暂停", "任务暂停")
    }

    /**
     * 自愈暂停：流程发现「现在读不到界面，但过一会儿可能会好」时调用。
     *
     * 与 [pause] 共用 `paused` 标志——用户在悬浮窗/主界面点「继续」时走的是同一个
     * [resume]，「跟手动暂停一个逻辑」；区别只有状态文案和「会不会自己恢复」。
     *
     * 调用方（[com.doujiajia.app.engine.flow.RecoveryWait]）不调 [awaitResume]，
     * 而是自己循环探测并轮询 [isPausedNow]，这样用户手动恢复也能立刻被感知。
     */
    fun pauseForRecovery(reason: String) {
        hold(EngineState.PAUSED_WAIT, reason, "任务进入等待恢复：$reason")
    }

    private fun hold(state: EngineState, message: String, log: String) {
        if (job?.isActive != true) {
            LogStore.w(TAG, "当前无运行任务，忽略暂停")
            return
        }
        paused = true
        _progress.value = _progress.value.copy(
            state = state,
            message = message,
            updatedAt = System.currentTimeMillis()
        )
        LogStore.i(TAG, log)
    }

    fun resume() {
        if (job?.isActive != true) {
            LogStore.w(TAG, "当前无运行任务，忽略恢复")
            return
        }
        paused = false
        _progress.value = _progress.value.copy(
            state = EngineState.RUNNING,
            message = "已恢复",
            updatedAt = System.currentTimeMillis()
        )
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
            // 已处于终态（例如刚因风控停机）时不能用 STOPPED 覆盖：
            // 那会把「风控停机」这条最重要的结论洗成一句普通的「已停止」。
            LogStore.d(TAG, "当前无运行任务，忽略停止请求（保持 ${_progress.value.state.label}）")
        }
    }

    // ==================== 内部 ====================

    private suspend fun awaitResume() {
        if (!paused) return
        LogStore.i(TAG, "任务处于暂停，等待恢复")
        while (paused && !stopRequested) delay(PAUSE_POLL_MS)
        if (!stopRequested) LogStore.i(TAG, "任务已恢复")
    }

    private fun finish(state: EngineState, message: String) {
        if (!finished.compareAndSet(false, true)) return
        _progress.value = _progress.value.copy(
            state = state,
            message = message,
            updatedAt = System.currentTimeMillis()
        )
        when (state) {
            EngineState.STOPPED_ANOMALY -> LogStore.e(TAG, "任务终止：$message")
            EngineState.ERROR -> LogStore.e(TAG, "任务异常终止：$message")
            else -> LogStore.i(TAG, "任务结束：$message")
        }
    }

    private fun publish(state: EngineState, message: String) {
        _progress.value = _progress.value.copy(
            state = state,
            message = message,
            updatedAt = System.currentTimeMillis()
        )
        LogStore.i(TAG, message)
    }
}
