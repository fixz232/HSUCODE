package com.hsucode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentExportTest {
    @Test
    fun splitSlidesUsesMarkdownDividerAndKeepsSingleDocument() {
        assertEquals(listOf("第一张\n内容", "第二张"), DocumentExport.splitSlides("第一张\n内容\n---\n第二张"))
        assertEquals(listOf("普通正文"), DocumentExport.splitSlides("普通正文"))
    }

    @Test
    fun splitSlidesIgnoresEmptySections() {
        val slides = DocumentExport.splitSlides("---\n\n---\n有效内容\n---\n")
        assertEquals(1, slides.size)
        assertTrue(slides.single() == "有效内容")
    }
}
