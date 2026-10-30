package com.doujiajia.app.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan

/**
 * 确认弹窗的「关键信息」排版工具：把文案里的 `**xx**` 渲染成粗体。
 *
 * 为什么用标记而不是在调用处手拼 `SpannableString`：三个确认弹窗一共十几处粗体，
 * 手拼 `setSpan` 要自己算下标，改一个字下标就全错位，且错位不会崩、只会静默加粗错地方。
 * 用标记的话文案本身就是最终形态，改文案不用碰任何下标逻辑。
 *
 * 文案里本来不出现 `*`，所以不存在歧义，也不需要在正文里转义。
 *
 * ⚠️ 必须逐段 append，**不要**先拼成完整字符串再按偏移 `setSpan`：
 * 去掉 `**` 之后每段长度都变，偏移会漂。
 */
object PromptText {

    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

    /** 解析 `**…**` 标记，返回可交给 `AlertDialog.setMessage(CharSequence)` 的富文本 */
    fun of(text: String): CharSequence {
        val out = SpannableStringBuilder()
        var last = 0
        for (match in BOLD.findAll(text)) {
            if (match.range.first > last) out.append(text, last, match.range.first)
            val start = out.length
            out.append(match.groupValues[1])
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            last = match.range.last + 1
        }
        if (last < text.length) out.append(text, last, text.length)
        return out
    }
}
