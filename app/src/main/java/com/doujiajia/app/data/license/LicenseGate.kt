package com.doujiajia.app.data.license

/**
 * 授权门禁。
 *
 * **为什么需要它**：授权判定的唯一消费者原来是 `MainActivity` 的界面——
 * `btnStartService.isEnabled` 与 `toast("请先激活授权")` 两道，都在 UI 层。
 * 而真正启动任务的是 `EngineController.start()`，它**不校验授权**，
 * 于是悬浮窗面板的「重试」（`restartLast()`）和通知栏入口都能绕过界面把任务重新拉起来：
 * 授权已被服务端停用、或已过期，只要进程还活着就能继续跑。
 *
 * 所以这里把「授权是否有效」变成一个进程内可查询的单一事实来源，
 * 由启动入口（`EngineController`）直接消费，而不是靠界面自觉。
 *
 * 注意：这只是**客户端门禁**，不构成安全边界——真正的判定永远在服务端。
 * 它的作用是「不让已经明确失效的授权继续驱动自动化」，而不是「防止有人破解客户端」。
 */
object LicenseGate {

    @Volatile
    private var provider: LicenseProvider? = null

    /** 由 `MainActivity` 在创建 `RemoteLicenseProvider` 时注入（构造时调用一次即可） */
    fun attach(provider: LicenseProvider) {
        this.provider = provider
    }

    /** 当前是否已授权。**未注入时视为未授权**——宁可拦住，不能放行。 */
    fun isAuthorized(): Boolean = provider?.state?.value?.authorized == true

    /** 给用户看的拒绝原因（未授权/离线超期/被停用…） */
    fun reason(): String {
        val current = provider ?: return "授权尚未初始化"
        val status = current.state.value
        if (status.authorized) return "授权有效"
        return status.message?.takeIf { it.isNotBlank() } ?: "授权无效"
    }
}
