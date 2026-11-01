package com.doujiajia.app.engine

import android.content.Context
import com.doujiajia.app.data.InteractStore
import com.doujiajia.app.data.SettingsStore

/**
 * 把「进度快照里的分母」替换成「用户当前设置里的分母」。
 *
 * 为什么必须换：`EngineProgress.target` 是流程启动那一刻（以及上次 publish 时）冻结的**快照**。
 * 用户在面板上把目标改大改小，这个字段不会跟着变，界面就成了「输入人数后毫无变化」
 * （2026-10-29 真机复现：改成 300、点确定，大号计数仍是 0/200）。
 *
 * 为什么必须按 [TargetKind] 分流：三条业务线的额度存在不同字段里。2026-11-01 真机实测
 * M3 目标设 3 条，悬浮窗却显示 **3/200**——直接拿 `followTarget`（关注目标 200）当分母。
 * 用户看到的第一反应是「闸门没生效」。
 *
 * @return 0 表示「这个流程没有总量目标」，调用方应只显示纯计数
 */
fun EngineProgress.liveTargetOf(context: Context): Int {
    val current = SettingsStore.get(context).current
    return when (targetKind) {
        TargetKind.FOLLOW -> current.followTarget

        // 评论的分母不只受界面设置约束，还要被每日上限压住——这与流程里
        // `minOf(commentTarget, remainingToday)` 必须同口径，否则显示与实际能发的条数不一致。
        TargetKind.COMMENT -> {
            val left = InteractStore.get(context).remainingToday(current.commentDailyLimit)
            minOf(current.commentTarget, left).coerceAtLeast(0)
        }

        TargetKind.NONE -> 0
    }
}
