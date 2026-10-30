package com.doujiajia.app.engine

/**
 * 注入手势期间，把悬浮窗临时设为「不可触摸」。
 *
 * 为什么需要：`dispatchGesture` 注入的触摸和真手指一样按坐标命中**最上层窗口**，
 * 而我们的悬浮窗就在最上层。面板展开时纵向可以盖到评论区（实测默认展开底边约 1485px，
 * 再展开「已关人数」还要下延 150dp），而 M2 点评论行的坐标正好在 y≈1700~2000。
 *
 * 2026-10-30 真机就发生了这件事（日志 `R-20261030-80BF03`）：
 * ```
 * 23:08:53 展开已关名单：共 9 人          ← 面板被撑高到评论行区域
 * 23:09:00 点击昵称区「* 肖你不可」@ (53,1851)   ← 这个点落在面板底部的「关闭悬浮窗」上
 * 23:09:01 悬浮窗: 用户关闭悬浮窗        ← 悬浮窗整个消失，引擎还在跑
 * ```
 * 之后 3 分钟里引擎在跑但没有任何面板可看，用户只能回 App 里点暂停/继续。
 *
 * 手势本身只持续几十毫秒，这期间禁掉悬浮窗的触摸对用户没有实际影响；
 * 反过来却能让「点评论」永远不可能点到我们自己的按钮。
 *
 * 实现成回调而不是直接引用 `FloatWindowService`：引擎层不该依赖服务层，
 * 注册/注销的活交给服务自己做（见 `FloatWindowService.onCreate/onDestroy`）。
 */
object TouchGuard {

    @Volatile
    private var suspendHook: (() -> Unit)? = null

    @Volatile
    private var resumeHook: (() -> Unit)? = null

    fun register(onSuspend: () -> Unit, onResume: () -> Unit) {
        suspendHook = onSuspend
        resumeHook = onResume
    }

    fun unregister() {
        suspendHook = null
        resumeHook = null
    }

    /** 手势即将注入：悬浮窗暂时不接收触摸。没有悬浮窗时是空操作。 */
    fun suspendForGesture() {
        runCatching { suspendHook?.invoke() }
    }

    /** 手势结束：恢复触摸。**必须放在 finally 里**，否则面板会永久点不动。 */
    fun resumeAfterGesture() {
        runCatching { resumeHook?.invoke() }
    }
}
