package com.yingti.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun parsesRealStyleNotes() {
        val lines = releaseNoteLines(
            """
            ## 新增
            - 应用内**更新检查**与镜像测速
            - 修复 `token` 过期提示

            ---

            普通段落保持原样
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NoteLine.Heading("新增"),
                NoteLine.Bullet("应用内更新检查与镜像测速"),
                NoteLine.Bullet("修复 token 过期提示"),
                NoteLine.Divider,
                NoteLine.Paragraph("普通段落保持原样"),
            ),
            lines,
        )
    }

    @Test
    fun headingLevelsAndNonHeadings() {
        assertEquals(listOf(NoteLine.Heading("t")), releaseNoteLines("###### t"))
        assertEquals(listOf(NoteLine.Paragraph("#tag")), releaseNoteLines("#tag"))
        assertEquals(listOf(NoteLine.Paragraph("####### seven")), releaseNoteLines("####### seven"))
    }

    @Test
    fun dashLinesSplitBulletsAndDividers() {
        assertEquals(listOf(NoteLine.Divider), releaseNoteLines("-----"))
        assertEquals(listOf(NoteLine.Paragraph("--")), releaseNoteLines("--"))
        assertEquals(listOf(NoteLine.Bullet("x")), releaseNoteLines("* x"))
    }
}
