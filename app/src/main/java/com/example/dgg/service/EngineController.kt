package com.example.dgg.service

import android.content.Context
import com.example.dgg.data.LogStore
import com.example.dgg.data.license.LicenseGate
import com.example.dgg.engine.Flow
import com.example.dgg.engine.TaskEngine
import com.example.dgg.util.ActiveWindow
import com.example.dgg.util.AppPermissions
import com.example.dgg.util.DouyinLauncher

/**
 * 启动/停止的唯一入口。
 *
 * 三层都要起：前台服务（锁进程）→ 引擎（跑流程）→ 悬浮窗（给人看）。
 * 顺序不能反：先起引擎后起前台服务，中间那一小段时间进程没有前台身份，
 * 冷启动慢的机型上真的会被回收掉，表现为「点了开始，什么都没发生」。
 *
 * 失败必须返回具体原因，界面只负责展示——不允许任何一层「假装成功」。
 */
object EngineController {

    private const val TAG = "任务控制"

    fun start(context: Context, flow: Flow, launchDouyin: Boolean = true): Result<Unit> {
        val app = context.applicationContext

        // 授权门禁放最前面。
        //
        // 这里才是**唯一**能真正阻止任务运行的地方：界面上的 `btnStartService.isEnabled`
        // 与 `toast("请先激活授权")` 只覆盖「用户从主界面点开始」这一条路径，
        // 悬浮窗的「重试」（restartLast）、通知栏入口都绕得过。授权被停用/过期时，
        // 只要进程还在就能把任务重新拉起来——这是本轮补上的最大一个口子。
        if (!LicenseGate.isAuthorized()) {
            val reason = LicenseGate.reason()
            LogStore.e(TAG, "拒绝启动：授权无效（$reason）")
            return Result.failure(IllegalStateException("授权无效：$reason"))
        }

        if (!DggAccessibilityService.isConnected()) {
            LogStore.e(TAG, "拒绝启动：无障碍服务未连接")
            return Result.failure(IllegalStateException("无障碍服务未开启，无法读取抖音界面"))
        }
        if (!DouyinLauncher.isInstalled(app)) {
            LogStore.e(TAG, "拒绝启动：未安装抖音（${DouyinLauncher.PACKAGE}）")
            return Result.failure(IllegalStateException("未检测到抖音，无法开始"))
        }

        ControlService.start(app)

        if (launchDouyin) {
            // **已经在前台就不要再 launch**。
            //
            // 实测过的坑：用户已经停在一个直播间里，点「开始服务」时无条件
            // `getLaunchIntentForPackage().startActivity()` 会让抖音重走一遍启动流程
            // （LivePlayActivity 被换掉 → splash → 首页），用户精心选好的直播间当场没了，
            // 脚本随后只能在「不在直播间」里中止。抖音在前台时它本来就在正确的地方。
            val front = runCatching {
                ActiveWindow.root(DggAccessibilityService.current)?.packageName?.toString()
            }.getOrNull()
            if (front == DouyinLauncher.PACKAGE) {
                LogStore.i(TAG, "抖音已在前台，不再重复拉起（重复拉起会打断当前直播间）")
            } else if (DouyinLauncher.launch(app)) {
                LogStore.i(TAG, "已拉起抖音，等待目标界面就绪")
            } else {
                LogStore.w(TAG, "拉起抖音失败，将依赖用户手动切到抖音")
            }
        }

        TaskEngine.start(app, flow)
        if (!TaskEngine.isRunning) {
            ControlService.stop(app, "启动失败")
            return Result.failure(IllegalStateException("流程未能启动，原因见运行日志"))
        }

        if (AppPermissions.canDrawOverlays(app)) {
            FloatWindowService.start(app)
        } else {
            LogStore.w(TAG, "未授予悬浮窗权限，跳过状态小窗（不影响任务运行）")
        }

        return Result.success(Unit)
    }

    /**
     * 停止。只调引擎：进入终态后 [ControlService] 与 [FloatWindowService]
     * 各自的进度收集器会处理自己的去留，这样「状态」只有一个来源，
     * 不会出现「引擎停了但通知还挂着在转」。
     *
     * [context] 不为 null 表示是**从抖加加界面**点的停止：此时顺手撤掉悬浮窗，
     * 它的终态面板在这里是重复信息，还挡着用户自己的界面。
     * 悬浮窗上点「结束」不走这条路（直接调 TaskEngine），面板要保留给人看结论。
     */
    fun stop(context: Context? = null, reason: String = "手动停止") {
        LogStore.i(TAG, "停止任务：$reason")
        TaskEngine.stop(reason)
        if (context != null) FloatWindowService.stop(context)
    }

    /**
     * 用上一次的流程重新开一轮（悬浮窗终态的「重试」按钮）。
     *
     * 只该在 `ERROR` 上用：那表示「这次没做成」（抖音被拉走、定位一时失效），
     * 重试是合理的。**风控停机不提供这个入口**——命中验证码/操作频繁还自动重试，
     * 是最容易被判定成机器行为的事情。
     */
    fun restartLast(context: Context): Result<Unit> {
        val flow = TaskEngine.lastFlow
            ?: return Result.failure(IllegalStateException("没有可重试的流程，请回到 App 重新开始"))
        LogStore.i(TAG, "重试上一次流程「${flow.name}」")
        return start(context, flow)
    }
}
