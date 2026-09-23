package com.opencode.remote.ui.chat

/**
 * Markdown → 可朗读纯文本：直接念 md 是灾难（井号星号链接代码块全给你念出来），
 * 先洗后念。效果=新闻联播腔：断句靠标点，无情绪，SSML/情感标记忽略。
 */
object TtsCleaner {

    fun clean(markdown: String): String {
        var t = markdown
        // 代码块整段跳过（念代码没人能听）
        t = Regex("```[\\s\\S]*?```").replace(t, "。以下代码已跳过。")
        // 行内代码去反引号
        t = t.replace("`", "")
        // 图片留 alt，链接留文本
        t = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)").replace(t, "\$1")
        t = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(t, "\$1")
        // 标题/引用/列表符号按行剥
        t = t.lines().joinToString("\n") { line ->
            var l = line.trimStart()
            l = l.replace(Regex("^#{1,6}\\s*"), "")
            l = l.replace(Regex("^>\\s?"), "")
            l = l.replace(Regex("^([-*+]|\\d+[.)])\\s+"), "")
            l
        }
        // 加粗倾斜删除线
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "\$1")
        t = t.replace(Regex("__(.+?)__"), "\$1")
        t = t.replace(Regex("~~(.+?)~~"), "\$1")
        t = t.replace(Regex("(?<!\\w)\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)"), "\$1")
        // HTML 标签
        t = Regex("<[^>]+>").replace(t, "")
        // 表格竖线→逗号
        t = t.replace("|", "，")
        // emoji strip (engines speak out emoji names)
        t = t.replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0F\\u200D\\u20E3]+"), "")
        // 空行压缩成句读
        t = t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("。")
        t = t.replace(Regex("。+"), "。")
        return t.trim('。', '，', '、', ' ', '\n').take(12000)
    }

    /** 按句切块（单块 ≤ max，避免引擎截断吞尾）。 */
    fun chunk(text: String, max: Int = 3500): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in text.split(Regex("(?<=[。！？!?])"))) {
            if (cur.length + sentence.length > max && cur.isNotEmpty()) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
            cur.append(sentence)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out.ifEmpty { listOf(text.take(max)) }
    }
}
