package com.doujiajia.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案标签与概率上限的验收测试。
 *
 * 这里只测纯函数（`NurtureTags` 不依赖任何 Android API），所以能跑在 JVM 单元测试里。
 * 覆盖 `docs/M4-养号助手.md` 点名的两条硬约束：
 *
 * 1. `base=60` 命中标签后**必须停在 60%**（夹取），不是 `60×(1+180%)=168%`；
 * 2. 收藏拉到 `40` 命中标签后**必须停在 40%**（`MAX_LIKE_PERCENT` 与
 *    `MAX_INTERACT_PERCENT` 两个常量不能串）。
 */
class NurtureTagsTest {

    private val weak = listOf(TagRule("健身", 100))
    private val extreme = listOf(TagRule("健身", 200), TagRule("减脂", 200))

    @Test
    fun `点赞上限 60 —— base 60 命中 +100 仍为 60`() {
        assertEquals(60, NurtureTags.likeProbability(60, weak))
    }

    @Test
    fun `点赞上限 60 —— base 60 命中 +400 仍为 60`() {
        // 未夹取时是 60×5 = 300；这条用例专门抓「忘了 coerceIn」
        assertEquals(60, NurtureTags.likeProbability(60, extreme))
    }

    @Test
    fun `收藏上限 40 —— base 40 命中 +400 仍为 40`() {
        assertEquals(40, NurtureTags.interactProbability(40, extreme))
    }

    @Test
    fun `base 为 0 时标签不能推翻用户意图`() {
        // 乘法模型的核心理由：用户把点赞拉到 0，标签再多加成也不能变成会点赞
        assertEquals(0, NurtureTags.likeProbability(0, extreme))
        assertEquals(0, NurtureTags.interactProbability(0, extreme))
    }

    @Test
    fun `正常放大是乘法`() {
        assertEquals(20, NurtureTags.likeProbability(10, weak))          // 10×2
        assertEquals(25, NurtureTags.likeProbability(10, listOf(TagRule("a", 50), TagRule("b", 100))))
        assertEquals(40, NurtureTags.likeProbability(40, emptyList()))   // 无命中不变
    }

    @Test
    fun `入参超范围也要夹住`() {
        // 界面滑块坏了 / 老版本设置残留，都不能算出 >60 的点赞概率
        assertEquals(60, NurtureTags.likeProbability(200, emptyList()))
        assertEquals(0, NurtureTags.likeProbability(-10, emptyList()))
    }

    @Test
    fun `解析逐行容错 —— 一行非法只跳这一行`() {
        val raw = """
            健身
            HIIT 80%
            # 注释
            // 也是注释

            减脂 50%
        """.trimIndent()
        val result = NurtureTags.parse(raw)
        assertEquals(listOf("健身", "HIIT", "减脂"), result.rules.map { it.word })
        assertEquals(100, result.rules[0].bonusPercent)
        assertEquals(80, result.rules[1].bonusPercent)
        assertEquals(50, result.rules[2].bonusPercent)
        assertTrue(result.invalidLines.isEmpty())
    }

    @Test
    fun `同名标签后者覆盖前者`() {
        val result = NurtureTags.parse("健身 50\n健身 120")
        assertEquals(1, result.rules.size)
        assertEquals(120, result.rules[0].bonusPercent)
    }

    @Test
    fun `匹配忽略大小写且同时能命中多个`() {
        val rules = NurtureTags.parse("HIIT\n减脂\n健身").rules
        assertEquals(2, NurtureTags.match("hiit 减脂 20 分钟", rules).size)
        assertEquals(0, NurtureTags.match("完全无关的文案", rules).size)
        assertEquals(0, NurtureTags.match("", rules).size)
    }

    @Test
    fun `空配置不抛异常`() {
        assertEquals(0, NurtureTags.parse(null).rules.size)
        assertEquals(0, NurtureTags.parse("   ").rules.size)
        assertEquals("无命中", NurtureTags.describe(emptyList()))
    }
}
