package com.easy.easyai.speech

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCursorTest {

    @Test
    fun `consumes sentences in order and returns the original bytes`() {
        val source = "第一句。第二句。"
        val cursor = SourceCursor(source)
        assertEquals("第一句。", cursor.next("第一句。"))
        assertEquals("第二句。", cursor.next("第二句。"))
        assertTrue(cursor.complete())
    }

    @Test
    fun `matches a reflowed copy but slices the untouched source`() {
        val source = "第一句。\n\n第二句。"
        val cursor = SourceCursor(source)
        // The director re-wrapped the paragraph; the slice handed on is still the original bytes.
        assertEquals(source, cursor.next("第一句。 第二句。"))
        assertTrue(cursor.complete())
    }

    @Test
    fun `ignores surrounding whitespace in a declared segment`() {
        val cursor = SourceCursor("第一句。第二句。")
        assertEquals("第一句。", cursor.next(" 第一句。 "))
        assertEquals("第二句。", cursor.next(" 第二句。 "))
        assertTrue(cursor.complete())
    }

    @Test
    fun `refuses a segment that skips text`() {
        val cursor = SourceCursor("第一句。第二句。第三句。")
        assertEquals("第一句。", cursor.next("第一句。"))
        // "第三句。" is present, but only after "第二句。" — taking it would silently drop a sentence.
        assertNull(cursor.next("第三句。"))
    }

    @Test
    fun `refuses a segment that is not in the source at all`() {
        val cursor = SourceCursor("原文")
        assertNull(cursor.next("改写后的句子"))
    }

    @Test
    fun `is incomplete until the whole source is consumed`() {
        val cursor = SourceCursor("第一句。第二句。")
        cursor.next("第一句。")
        assertFalse(cursor.complete())
        cursor.next("第二句。")
        assertTrue(cursor.complete())
    }

    @Test
    fun `normalization collapses whitespace only`() {
        assertEquals("a b c", SourceCursor.normalize(" a   b\n\tc "))
        assertEquals("一，二", SourceCursor.normalize("一，二"))
    }
}
