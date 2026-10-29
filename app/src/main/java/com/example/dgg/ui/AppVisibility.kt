package com.example.dgg.ui

/**
 * 抖关关自己的界面是不是在前台。
 *
 * 只服务一个判断：**任务到终态时，悬浮面板要不要自动展开**。
 *
 * 以前是无条件展开的，结果抖关关自己在前台时，面板（纵向约 405~1485）正好盖住
 * 参数区，用户想改参数还得先点面板右上角 ⌃ 收起（2026-10-29 真机反馈）。
 * 终态结论在主界面底部状态条里本来就有，不需要再挡一遍。
 *
 * 为什么不用「读当前前台包名」：终态最常见的成因就是无障碍掉线、抖音被杀，
 * 这时候节点树读不到包名，判断本身不可靠。Activity 生命周期是本进程内确定的信息。
 */
object AppVisibility {

    @Volatile
    var isUiForeground: Boolean = false
        private set

    fun onResume() {
        isUiForeground = true
    }

    fun onPause() {
        isUiForeground = false
    }
}
