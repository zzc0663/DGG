package com.example.dgg.engine

import com.example.dgg.data.LogStore
import kotlinx.coroutines.delay

/**
 * 一个可执行步骤。
 *
 * 引擎只认这几种原语，线性业务都拆成步骤序列，这样「换业务」等于「换一个步骤列表」，
 * 不用动引擎本身。带分支/循环的业务（M2 起）走 [Flow] 直接实现。
 */
sealed interface Step {

    val label: String

    /** 等待进入某个页面（带超时） */
    data class WaitForPage(val page: PageState, val timeoutMs: Long = 8_000) : Step {
        override val label: String get() = "等待页面 ${page.name}（≤${timeoutMs}ms）"
    }

    /** 点击配置里的命名目标 */
    data class Click(val targetName: String) : Step {
        override val label: String get() = "点击 $targetName"
    }

    /** 向配置里的命名目标逐字输入 */
    data class Input(val targetName: String, val text: String) : Step {
        override val label: String get() = "输入「$text」→ $targetName"
    }

    data class Back(val times: Int = 1) : Step {
        override val label: String get() = "返回 ×$times"
    }

    data class Sleep(val ms: Long) : Step {
        override val label: String get() = "等待 ${ms}ms"
    }
}

/**
 * 线性流程：把步骤列表按顺序跑完，每步最多重试 [MAX_ATTEMPTS_PER_STEP] 次，
 * 连续 [MAX_CONSECUTIVE_FAILURES] 步失败就熔断。
 *
 * 熔断存在的意义：定位配置和抖音版本不匹配时，每一步都会失败，
 * 这时候最坏的结果不是「停」，而是「在风控眼皮底下无限重试」。
 */
class StepFlow(
    private val steps: List<Step>,
    override val name: String = "步骤流程"
) : Flow {

    override suspend fun run(ctx: FlowContext) {
        var consecutiveFailures = 0

        for ((index, step) in steps.withIndex()) {
            if (ctx.stopped()) return
            ctx.awaitResume()
            ctx.guard()

            val detection = ctx.detector.detect()
            ctx.publish(
                stepIndex = index + 1,
                stepTotal = steps.size,
                stepLabel = step.label,
                page = detection.page,
                message = step.label
            )
            LogStore.i(TAG, "步骤 ${index + 1}/${steps.size}：${step.label}｜当前页面 ${detection.page.name}")

            var ok = false
            var attempt = 0
            while (attempt < MAX_ATTEMPTS_PER_STEP && !ok && !ctx.stopped()) {
                attempt++
                ok = runCatching { execute(step, ctx) }
                    .onFailure { LogStore.e(TAG, "步骤「${step.label}」抛出异常：${it.message}") }
                    .getOrDefault(false)
                if (!ok) {
                    LogStore.w(TAG, "步骤「${step.label}」第 $attempt/$MAX_ATTEMPTS_PER_STEP 次未成功")
                    if (attempt < MAX_ATTEMPTS_PER_STEP) ctx.settle(FAILURE_BACKOFF_MS)
                }
            }

            consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
            if (!ok && consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw FlowAbortException(
                    "连续 $consecutiveFailures 个步骤未能完成，熔断停止（多半是定位配置与当前抖音版本不匹配）"
                )
            }

            if (index < steps.lastIndex) {
                ctx.settle(ctx.settings.scanIntervalMs.toLong().coerceIn(300, 8_000))
            }
        }
    }

    private suspend fun execute(step: Step, ctx: FlowContext): Boolean = when (step) {
        is Step.Click -> ctx.actions.clickByName(step.targetName, ctx.config)

        is Step.Input -> {
            val target = ctx.config.target(step.targetName)
            if (target == null) {
                LogStore.e(TAG, "定位配置缺少目标「${step.targetName}」")
                false
            } else {
                ctx.actions.typeText(target, step.text)
            }
        }

        is Step.Back -> {
            var allOk = true
            repeat(step.times) {
                if (!ctx.actions.back()) allOk = false
                delay(ctx.humanizer.delayMs(400))
            }
            allOk
        }

        is Step.Sleep -> {
            ctx.settle(step.ms)
            true
        }

        is Step.WaitForPage -> ctx.waitForPage(step.page, step.timeoutMs)
    }

    private companion object {
        const val TAG = "步骤流程"
        const val MAX_ATTEMPTS_PER_STEP = 3
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val FAILURE_BACKOFF_MS = 800L
    }
}

/**
 * 自检流程：首页 → 搜索入口 → 输入关键词 → 提交 → 结果页 → 切「用户」tab。
 *
 * 这是验证「定位 + 操作链路是否成立」的最短闭环，**不是**业务。
 * M2 的直播间评论关注不走这里，走 [com.example.dgg.engine.flow.FollowFromLiveFlow]。
 */
object SmokeFlow {

    const val DEFAULT_KEYWORD = "测试"

    fun build(keyword: String = DEFAULT_KEYWORD): Flow = StepFlow(
        listOf(
            Step.WaitForPage(PageState.HOME, timeoutMs = 8_000),
            Step.Click("search_entry"),
            Step.WaitForPage(PageState.SEARCH, timeoutMs = 5_000),
            Step.Input("search_input", keyword),
            Step.Click("search_confirm"),
            Step.WaitForPage(PageState.SEARCH_RESULT, timeoutMs = 8_000),
            Step.Click("user_tab"),
            Step.WaitForPage(PageState.SEARCH_RESULT, timeoutMs = 5_000)
        ),
        name = "搜索链路自检"
    )
}
