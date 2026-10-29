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
        stepTotal: Int? = null,
        /** 养号专用：点赞/收藏的累计数。传 null 表示不改（其余流程不关心这两个数） */
        liked: Int? = null,
        collected: Int? = null
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
                liked = liked ?: p.liked,
                collected = collected ?: p.collected,
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

    /**
     * 长等待（分钟级）用这个：分片执行，每片之间检查停止/暂停，结束后查一次风控。
     *
     * 直接用 [settle] 等 60 分钟是不行的——一次 `delay` 期间收不到暂停，
     * 用户点「暂停」要等满 60 分钟才生效。分片还有个好处：等待期间能顺便
     * 把剩余时间发到通知栏/悬浮窗，不然界面上一小时都是同一句话。
     *
     * @param capMs 抖动后的上限。抖动是 ±30% 的话 60 分钟能漂到 78 分钟，
     *              而界面上承诺的上限是 60 分钟，所以夹住。
     * @param jitter 是否再叠一层 ±jitter% 抖动。
     *              养号的"停留时长"**必须传 false**：调用方已经用 `humanizer.longIn(min,max)`
     *              掷过骰了，这里再抖一次就成了「随机叠随机」——实际区间会溢出去，
     *              日志上写"1.5~4 秒"而实测到 5 秒，排查时会以为随机数坏了。
     * @param shouldInterrupt 每片结束时问一次，返回 true 就提前结束等待。
     *              给「间隔可设到 1 小时」的场景用：用户在等待期间把目标数调小，
     *              不该让他在原地等满这一小时才看到效果。
     */
    suspend fun settleLong(
        baseMs: Long,
        capMs: Long = Long.MAX_VALUE,
        chunkMs: Long = 1_000L,
        jitter: Boolean = true,
        shouldInterrupt: (() -> Boolean)? = null
    ) {
        var left = (if (jitter) humanizer.delayMs(baseMs) else baseMs.coerceAtLeast(1L)).coerceAtMost(capMs)
        var sincePublish = 0L
        while (left > 0) {
            val slice = minOf(chunkMs, left)
            delay(slice)
            left -= slice
            sincePublish += slice
            if (stopped()) return
            // 未暂停时立即返回；暂停期间挂起在这里，恢复后接着等
            awaitResume()
            if (stopped()) return
            if (shouldInterrupt?.invoke() == true) return
            if (sincePublish >= PUBLISH_EVERY_MS && left > 0) {
                sincePublish = 0
                publish(message = "等待下次扫描，还有 ${left / 1000}s")
            }
        }
        guard()
    }

    private companion object {
        const val PUBLISH_EVERY_MS = 10_000L
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
