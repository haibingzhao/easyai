package com.easy.easyai.common.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextWrapperTest {

    @Test
    fun `test visible width with ANSI codes`() {
        // ANSI codes should not count toward width
        val text = "\u001b[31mHello\u001b[0m"
        assertEquals(5, TextWrapper.visibleWidthWithAnsi(text))
    }

    @Test
    fun `test visible width with wide characters`() {
        // Chinese characters should be 2 columns each
        val text = "你好世界"
        assertEquals(8, TextWrapper.visibleWidthWithAnsi(text))
    }

    @Test
    fun `test visible width with mixed content`() {
        // Mixed ASCII and wide chars
        val text = "Hi你好"
        assertEquals(6, TextWrapper.visibleWidthWithAnsi(text)) // 2 + 4
    }

    @Test
    fun `test wrap short text no wrapping needed`() {
        val text = "Hello"
        val wrapped = TextWrapper.wrapText(text, 80)
        assertEquals(1, wrapped.size)
        assertEquals("Hello", wrapped[0])
    }

    @Test
    fun `test wrap text with literal newline`() {
        val text = "Line1\nLine2"
        val wrapped = TextWrapper.wrapText(text, 80)
        assertEquals(2, wrapped.size)
        assertEquals("Line1", wrapped[0])
        assertEquals("Line2", wrapped[1])
    }

    @Test
    fun `test wrap long text that exceeds width`() {
        val text = "This is a very long line that should be wrapped"
        val wrapped = TextWrapper.wrapText(text, 20)
        
        // Should wrap into multiple lines, each <= 20 visible chars
        assertTrue(wrapped.size > 1)
        wrapped.forEach { line ->
            assertTrue(TextWrapper.visibleWidthWithAnsi(line) <= 20)
        }
    }

    @Test
    fun `test calculate display lines simple case`() {
        val text = "Hello\nWorld"
        val lines = TextWrapper.calculateDisplayLines(text, 80)
        assertEquals(2, lines)
    }

    @Test
    fun `test calculate display lines with wrapping`() {
        // 40 char text, width 20 → should be 2 display lines
        val text = "1234567890123456789012345678901234567890"
        val lines = TextWrapper.calculateDisplayLines(text, 20)
        assertEquals(2, lines)
    }

    @Test
    fun `test calculate display lines with newlines and wrapping`() {
        // Two 30-char lines, width 20 → each wraps to 2 lines = 4 total
        val text = "123456789012345678901234567890\n123456789012345678901234567890"
        val lines = TextWrapper.calculateDisplayLines(text, 20)
        assertEquals(4, lines)
    }

    @Test
    fun `test wrap preserves ANSI codes`() {
        val text = "\u001b[31mRed text here\u001b[0m"
        val wrapped = TextWrapper.wrapText(text, 80)
        
        assertEquals(1, wrapped.size)
        // ANSI codes should be preserved
        assertTrue(wrapped[0].contains("\u001b[31m"))
        assertTrue(wrapped[0].contains("\u001b[0m"))
    }

    @Test
    fun `test wrap with tabs`() {
        val text = "Hello\tWorld"
        val wrapped = TextWrapper.wrapText(text, 80)
        
        assertEquals(1, wrapped.size)
        // Tab should be expanded to 3 spaces for width calculation
        val width = TextWrapper.visibleWidthWithAnsi(wrapped[0])
        assertTrue(width >= 11) // "Hello" (5) + tab (3) + "World" (5) = 13
    }

    @Test
    fun `test empty text`() {
        val wrapped = TextWrapper.wrapText("", 80)
        assertEquals(1, wrapped.size)
        assertEquals("", wrapped[0])
    }

    @Test
    fun `test zero width`() {
        val wrapped = TextWrapper.wrapText("Hello", 0)
        assertEquals(1, wrapped.size)
        assertEquals("", wrapped[0])
    }

    @Test
    fun `test word boundary wrapping`() {
        // Should prefer breaking at word boundaries
        val text = "Hello World Test"
        val wrapped = TextWrapper.wrapText(text, 10)
        
        // Each line should be <= 10 chars
        wrapped.forEach { line ->
            assertTrue(TextWrapper.visibleWidthWithAnsi(line) <= 10)
        }
    }

    private fun assertTrue(condition: Boolean, message: String = "") {
        if (!condition) {
            throw AssertionError(message)
        }
    }
}
