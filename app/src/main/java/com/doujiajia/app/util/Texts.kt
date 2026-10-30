package com.doujiajia.app.util

/**
 * 界面文案清洗。
 *
 * 实测抖音的评论/昵称文本以各种**不可见字符**开头或夹带（最常见是 U+200E LEFT-TO-RIGHT MARK，
 * 形如 `‎紫丽丽：…`）。不清理会有两个后果，M2 上都踩过：
 * 1. 昵称带着零宽字符落盘，同一个人换条评论就被当成新面孔，去重失效；
 * 2. 用昵称回查节点时两边字符串不一致，永远定位不到。
 *
 * 抽成公共工具是因为 M2（关注）和 M3（评论）都要做同一件事——复制评论时同样要先洗一遍，
 * 否则会把看不见的控制字符一起发到直播间里。
 */
object Texts {

    fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c.code < 0x20 -> {}                       // 控制字符
                c.code in 0x200B..0x200F -> {}             // 零宽空格 / LRM / RLM
                c.code in 0x202A..0x202E -> {}             // 方向控制
                c.code in 0x2060..0x2069 -> {}
                c.code == 0xFEFF -> {}                     // BOM
                else -> sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    /** 去掉中文/英文冒号之前的部分，返回冒号后的正文；没有冒号返回 null */
    fun afterColon(line: String): String? {
        val cleaned = sanitize(line)
        val index = cleaned.indexOfFirst { it == '：' || it == ':' }
        if (index < 0 || index == cleaned.lastIndex) return null
        return sanitize(cleaned.substring(index + 1))
    }

    /** 取冒号之前的部分（昵称）；没有冒号返回 null */
    fun beforeColon(line: String): String? {
        val cleaned = sanitize(line)
        val index = cleaned.indexOfFirst { it == '：' || it == ':' }
        if (index <= 0) return null
        return sanitize(cleaned.substring(0, index))
    }
}
