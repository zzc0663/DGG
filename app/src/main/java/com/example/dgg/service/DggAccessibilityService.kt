package com.example.dgg.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.dgg.data.LogStore
import java.io.File
import java.util.Locale

/**
 * 无障碍服务 —— 整个自动化能力的根。
 *
 * 三个设计决定：
 *
 * 1. **不做业务**。这里只负责「持有 service 实例」和「统计事件量」。
 *    业务全部在 [com.example.dgg.engine.TaskEngine] 里，由它主动拉节点树。
 *
 * 2. **页面判定不依赖事件回调**。抖音的 `typeWindowContentChanged` 事件密度极高
 *    （直播弹幕、推荐流每秒多次），拿事件做状态机驱动会被淹没，而且窗口切换时事件
 *    可能丢失。这里只统计事件数用于排查，判定一律走轮询（200ms 一次）。
 *
 * 3. **[current] 用 volatile 单例**。引擎、悬浮窗、前台服务都需要拿它去调
 *    `rootInActiveWindow` / `dispatchGesture`，而 service 的生命周期由系统管理，
 *    没法通过构造注入。为空就代表「用户关掉了无障碍」，引擎必须拒绝启动。
 */
class DggAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        // 双入口启动调试通道：HyperOS 覆盖安装/强杀后不一定回调 onServiceConnected
        DebugChannel.startIfNeeded(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        eventCount = 0L
        lastEventAt = 0L
        LogStore.i(TAG, "无障碍服务已连接，能力=${describeCapabilities()}")
        DebugChannel.startIfNeeded(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        eventCount++
        lastEventAt = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        LogStore.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LogStore.w(TAG, "无障碍服务已断开（用户在设置中关闭，或被系统回收）")
        current = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /**
     * 启动时把实际生效的能力打出来。
     * 这两项如果为 false，后面所有定位都会失败，而且失败原因藏在系统里看不出来，
     * 所以必须在连接的那一刻就落到日志里。
     */
    private fun describeCapabilities(): String {
        val caps = runCatching { serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
        val window = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        val gesture = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        return "读窗口=$window 手势=$gesture"
    }

    companion object {
        private const val TAG = "无障碍服务"

        /** 当前已连接的服务实例；null 表示无障碍未开启 */
        @Volatile
        var current: DggAccessibilityService? = null
            private set

        /** 累计收到的事件数，仅用于日志排查 */
        @Volatile
        var eventCount: Long = 0L
            private set

        @Volatile
        var lastEventAt: Long = 0L
            private set

        fun isConnected(): Boolean = current != null
    }
}
