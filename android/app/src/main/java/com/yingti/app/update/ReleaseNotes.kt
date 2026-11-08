package com.yingti.app.update

sealed interface NoteLine {
    data class Heading(val text: String) : NoteLine
    data class Bullet(val text: String) : NoteLine
    data class Paragraph(val text: String) : NoteLine
    data object Divider : NoteLine
}

/** Minimal Markdown-to-lines for release notes: headings, bullets, dividers, plain paragraphs. */
fun releaseNoteLines(markdown: String): List<NoteLine> {
    fun clean(text: String) = text.replace("**", "").replace("`", "").trim()
    return markdown.lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> null
            line.all { it == '-' } && line.length >= 3 -> NoteLine.Divider
            line.startsWith("#") -> {
                val heading = line.trimStart('#')
                if (line.length - heading.length <= 6 && heading.startsWith(" ")) {
                    NoteLine.Heading(clean(heading))
                } else NoteLine.Paragraph(clean(line))
            }
            line.startsWith("- ") || line.startsWith("* ") -> NoteLine.Bullet(clean(line.substring(2)))
            else -> NoteLine.Paragraph(clean(line))
        }
    }
}
