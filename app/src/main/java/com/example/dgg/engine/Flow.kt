package com.example.dgg.engine

import android.content.Context
import android.os.SystemClock
import com.example.dgg.data.Settings
import com.example.dgg.engine.risk.AnomalyGuard
import com.example.dgg.engine.risk.Humanizer
import kotlinx.coroutines.delay

/**
 * 业务闭环。
 *
 * M1 的引擎只能跑静态步骤列表，表达不了「读到什么决定做什么」。M2 起业务一律实现本接口，
 * 引擎只负责生命周期、状态发布与风控兜底，具体流程完全交给 Flow。
 *
 * 两种异常是约定好的信号，业务层用它们表达「停」的原因：
 * - [AnomalyStopException] → 引擎置 `STOPPED_ANOMALY`（风控停机，**不重试**）
 * - [FlowAbortException]   → 引擎置 `ERROR`（做不到，带原因）
 */
interface Flow {

    val name: String

    suspend fun run(ctx: FlowContext)
}

/** 命中风控。引擎捕获后立刻停机，禁止任何形式的重试。 */
class AnomalyStopException(val hit: AnomalyGuard.Hit) :
    Exception("命中风控提示 $hit")

/** 流程做不到（定位失效、额度用尽、前置条件不满足），带可读原因。 */
class FlowAbortException(reason: String) : Exception(reason)

/**
 * Flow 能用到的一切。
 *
 * 刻意不做成「万能上下文」：只有这些能力，业务代码碰不到引擎内部状态，
 * 也就没法绕过暂停、停止、风控检查。
 */
class FlowContext(
    val appContext: Context,
    val actions: Actions,
    val detector: PageDetector,
    val humanizer: Humanizer,
    val config: LocatorConfig,
    val settings: Settings,
    private val mutate: ((EngineProgress) -> EngineProgress) -> Unit,
    private val stopFlag: () -> Boolean,
    private val resumeWaiter: suspend () -> Unit
) {

    fun publish(
        stepLabel: String? = null,
        page: PageState? = null,
        message: String? = null,
        followed: Int? = null,
        target: Int? = null,
        counterLabel: String? = null,
        stepIndex: Int? = null,
        stepTotal: Int? = null
    ) {
        mutate { p ->
            p.copy(
                stepLabel = stepLabel ?: p.stepLabel,
                page = page ?: p.page,
                message = message ?: p.message,
                followed = followed ?: p.followed,
                target = target ?: p.target,
                counterLabel = counterLabel ?: p.counterLabel,
                stepIndex = stepIndex ?: p.stepIndex,
                stepTotal = stepTotal ?: p.stepTotal,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * 查风控；命中直接抛出，流程无从忽略。
     *
     * 走快照而不是自己再遍历一遍：连续动作之间界面没变，快照还能用（700ms 内），
     * 省下的是实打实的时间——一趟全树遍历在这台真机的直播间里要 1.4 秒，
     * 而每次 [settle] 都会查一次。
     */
    suspend fun guard() {
        AnomalyGuard.check(actions.snapshot())?.let { throw AnomalyStopException(it) }
    }

    /**
     * 随机化等待，**等待结束后自动再查一次风控**。
     * 把检查和等待绑在一起，是为了让「业务代码忘了查」这件事不可能发生。
     */
    suspend fun settle(baseMs: Long) {
        delay(humanizer.delayMs(baseMs))
        guard()
    }

    suspend fun awaitResume() = resumeWaiter()

    fun stopped(): Boolean = stopFlag()

    suspend fun waitForPage(page: PageState, timeoutMs: Long, pollMs: Long = 200): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (stopped()) return false
            val detection = detector.detect()
            publish(page = detection.page)
            if (detection.page == page) return true
            delay(humanizer.delayMs(pollMs))
        }
        return false
    }
}
