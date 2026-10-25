package com.example.dgg.engine

/**
 * 一个可执行步骤。
 *
 * 引擎只认这几种原语，具体业务（自动关注、直播互动、养号）都拆成步骤序列，
 * 这样「换业务」等于「换一个步骤列表」，不用动引擎本身。
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
 * M1 自检流程：首页 → 搜索入口 → 输入关键词 → 提交 → 结果页 → 切「用户」tab。
 *
 * 这是给无障碍底座跑通链路用的最短闭环，**不是**自动关注业务。
 * 真正的关注循环（进主页 → 关注 → 计数 → 休息）在 M2。
 *
 * 注意：这里的 [DEFAULT_KEYWORD] 是占位值。M2 会把关键词提到界面上可配置。
 */
object SmokeFlow {

    const val DEFAULT_KEYWORD = "测试"

    fun build(keyword: String = DEFAULT_KEYWORD): List<Step> = listOf(
        Step.WaitForPage(PageState.HOME, timeoutMs = 8_000),
        Step.Click("search_entry"),
        Step.WaitForPage(PageState.SEARCH, timeoutMs = 5_000),
        Step.Input("search_input", keyword),
        Step.Click("search_confirm"),
        Step.WaitForPage(PageState.SEARCH_RESULT, timeoutMs = 8_000),
        Step.Click("user_tab"),
        Step.WaitForPage(PageState.SEARCH_RESULT, timeoutMs = 5_000)
    )
}
