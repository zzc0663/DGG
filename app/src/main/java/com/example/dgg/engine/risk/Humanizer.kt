package com.example.dgg.engine.risk

import android.graphics.Point
import android.graphics.Rect
import kotlin.random.Random

/**
 * 拟人化：给所有机械动作加上不可预测性（计划 §3.1 §3.2）。
 *
 * 原则：**不使用固定值**。延迟、点击落点、滑动段数、输入节奏全部取区间随机，
 * 且区间本身以配置的「延迟抖动（%）」为幅度。这不是美观问题，是封号概率问题。
 */
class Humanizer(private val jitterPercent: Int) {

    private val random = Random(System.nanoTime())

    /** 以 [baseMs] 为基准、±jitterPercent% 的随机延迟，下限 50ms */
    fun delayMs(baseMs: Long): Long {
        val base = baseMs.coerceAtLeast(MIN_DELAY_MS)
        val amplitude = (base * jitterPercent.coerceIn(0, 90) / 100.0).toLong()
        val low = (base - amplitude).coerceAtLeast(MIN_DELAY_MS)
        val high = (base + amplitude).coerceAtLeast(low + 1)
        return random.nextLong(low, high + 1)
    }

    /**
     * 在控件矩形内取一个点，**避开正中**并保留边距。
     * 每次都点正中心是最容易被行为模型识别的特征之一。
     */
    fun pointIn(bounds: Rect): Point {
        val insetX = (bounds.width() * EDGE_MARGIN).toInt().coerceAtLeast(1)
        val insetY = (bounds.height() * EDGE_MARGIN).toInt().coerceAtLeast(1)
        val left = bounds.left + insetX
        val right = (bounds.right - insetX).coerceAtLeast(left + 1)
        val top = bounds.top + insetY
        val bottom = (bounds.bottom - insetY).coerceAtLeast(top + 1)
        return Point(random.nextInt(left, right), random.nextInt(top, bottom))
    }

    /** 点击按压时长 40–120ms，人手不会每次都一样 */
    fun tapDurationMs(): Long = random.nextLong(40, 121)

    /** 滑动时长 300–600ms */
    fun swipeDurationMs(): Long = random.nextLong(300, 601)

    /** 滑动段数 3–6，用于构造多段折线而不是一条直线 */
    fun swipeSegments(): Int = random.nextInt(3, 7)

    /** 逐字输入的字间延迟 80–200ms */
    fun charDelayMs(): Long = random.nextLong(80, 201)

    /** 单段滑动的横向漂移（像素），让轨迹不是笔直的 */
    fun driftPx(amplitude: Int): Int =
        if (amplitude <= 0) 0 else random.nextInt(-amplitude, amplitude + 1)

    /** 概率判定，用于「概率跳过」等行为多样性 */
    fun chance(percent: Int): Boolean =
        random.nextInt(100) < percent.coerceIn(0, 100)

    private companion object {
        const val MIN_DELAY_MS = 50L

        /** 落点距离控件边缘的最小比例，避开正中与边界 */
        const val EDGE_MARGIN = 0.15f
    }
}
