package com.example.dgg.service

import android.content.Context
import com.example.dgg.data.LogStore
import com.example.dgg.engine.Flow
import com.example.dgg.engine.TaskEngine
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
            if (DouyinLauncher.launch(app)) {
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
     * 各自的进度收集器会把它们自己关掉，这样「状态」只有一个来源，
     * 不会出现「引擎停了但通知还挂着在转」。
     */
    fun stop(reason: String = "手动停止") {
        LogStore.i(TAG, "停止任务：$reason")
        TaskEngine.stop(reason)
    }
}
