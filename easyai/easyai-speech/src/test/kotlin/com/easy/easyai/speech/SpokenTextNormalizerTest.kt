package com.easy.easyai.speech

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fidelity gates compare *tokens*, not characters, so these are the anchors that decide whether a
 * rewrite is accepted. A wrong value here does not fail loudly — it silently lets a wrong number
 * through, or rejects a correct rewrite and falls back to the source sentence.
 */
class SpokenTextNormalizerTest {

    private fun numbers(text: String) = SpokenTextNormalizer.numericTokens(text)

    @Test
    fun `chinese large units nest instead of scaling what came before`() {
        // `一亿两千万` is 1×10^8 + 2×10^7. Treating 万 as "multiply the running total" gives 1.0002e12,
        // which then never matches the written form and forces a fallback on every long number.
        assertEquals(listOf("100000000"), numbers("一亿"))
        assertEquals(listOf("120000000"), numbers("一亿两千万"))
        assertEquals(listOf("100000"), numbers("十万"))
        assertEquals(listOf("12000"), numbers("一万二千"))
        assertEquals(listOf("1200"), numbers("一千二百"))
        assertEquals(listOf("1000000000000"), numbers("一万亿"))
    }

    @Test
    fun `chinese and arabic forms of the same number compare equal`() {
        assertEquals(numbers("20"), numbers("二十"))
        assertEquals(numbers("105"), numbers("一百零五"))
        assertEquals(numbers("2"), numbers("两"))
        assertEquals(numbers("0.5"), numbers("零点五"))
        // A percentage normalizes to its fraction, so both spellings have to land on 0.5.
        assertEquals(listOf("0.5"), numbers("50%"))
        assertEquals(numbers("50%"), numbers("百分之五十"))
        assertEquals(numbers("-3"), numbers("负三"))
    }

    @Test
    fun `url hosts and identifiers stay visible as anchors`() {
        val anchors = SpokenTextNormalizer.termAnchors("见 https://example.com/a 里的 user_id 字段")
        assertTrue(anchors.contains("example.com"), "anchors were $anchors")
        assertTrue(anchors.contains("user_id"), "anchors were $anchors")
    }

    /** Dates and clock times: the anchors that used to make every datetime rewrite fall back. */
    @Nested
    inner class CalendarsAndClocks {

        @Test
        fun `a hyphenated date is a date, not three negative numbers`() {
            // `-` is also the negative sign, so `2026-10-31` used to tokenize as 2026, -10, -31 and
            // no natural rewrite could ever match it.
            assertEquals(listOf("date:2026-10-31"), numbers("2026-10-31"))
            assertEquals(listOf("date:2026-04-05"), numbers("2026/4/5"))
        }

        @Test
        fun `a written datetime and its spoken form carry the same anchors`() {
            val written = listOf("date:2026-10-31", "time:07:18")
            assertEquals(written, numbers("2026-10-31 07:18"))
            assertEquals(written, numbers("二零二六年十月三十一日七点十八分"))
            assertEquals(written, numbers("2026年10月31日7点18分"))
        }

        @Test
        fun `a spoken date may drop the year but never the month or day`() {
            assertTrue(SpokenTextNormalizer.numbersPreserved("2026-10-31 07:18", "10月31日七点十八分"))
            assertFalse(SpokenTextNormalizer.numbersPreserved("2026-10-31 07:18", "11月1日七点十八分"))
            // The wildcard is one-directional: a year the source never stated is an invented fact.
            assertFalse(SpokenTextNormalizer.numbersPreserved("10月31日", "2026年10月31日"))
        }

        @Test
        fun `a clock time is not a digit-by-digit reading of its digits`() {
            assertEquals(listOf("time:07:18"), numbers("07:18"))
            assertEquals(listOf("time:07:18:30"), numbers("07:18:30"))
            assertFalse(SpokenTextNormalizer.numbersPreserved("07:18", "零点七一八"))
        }

        @Test
        fun `a decimal stays a decimal and a clock stays a clock`() {
            // `三点五` is 3.5, so a bare minute is only read as a clock when it carries a unit.
            assertEquals(listOf("3.5"), numbers("三点五"))
            assertEquals(listOf("0.5"), numbers("零点五"))
            assertEquals(listOf("time:10:30"), numbers("十点半"))
            assertEquals(listOf("time:07:18"), numbers("七点十八"))
            assertEquals(emptyList(), numbers("有一点需要注意"))
        }

        @Test
        fun `the meridiem decides the hour`() {
            assertEquals(listOf("time:07:18"), numbers("上午七点十八分"))
            assertEquals(listOf("time:19:18"), numbers("晚上七点十八分"))
            assertEquals(listOf("time:00:30"), numbers("凌晨十二点半"))
        }

        @Test
        fun `a port is not a clock`() {
            assertEquals(listOf("8080"), numbers("端口 x:8080 打开"))
        }

        @Test
        fun `a known zone may be spoken in Chinese`() {
            val source = "2026-10-31 07:18（Asia/Shanghai）"
            assertEquals(emptyList(), SpokenTextNormalizer.missingTermAnchors(source, "十月三十一日七点十八分，上海时区"))
            assertEquals(emptyList(), SpokenTextNormalizer.missingTermAnchors(source, "十月三十一日七点十八分，北京时间"))
            assertEquals(emptyList(), SpokenTextNormalizer.missingTermAnchors(source, "十月三十一日七点十八分，Asia/Shanghai"))
            // Anything outside the alias table keeps the literal anchor, so `user_id`-style rules hold.
            assertEquals(
                listOf("Europe/Berlin"),
                SpokenTextNormalizer.missingTermAnchors("Europe/Berlin 时间", "柏林时间")
            )
        }
    }

    @Test
    fun `a markdown link is text, not an emotion tag`() {
        // `[Docs](https://…)` is what a reply legitimately looks like after Markdown cleaning, and
        // flagging it as markup would fall a correct segment back to its source sentence.
        assertFalse(SpokenTextNormalizer.containsRawMarkup("详见 [Docs](https://example.com)"))
        assertTrue(SpokenTextNormalizer.containsRawMarkup("她轻声说 [Sad] 然后就走了"))
        assertTrue(SpokenTextNormalizer.containsRawMarkup("<speak>你好</speak>"))
    }

    /**
     * Inline tags the vendor publishes, and the whitelist that separates them from markup.
     *
     * A declared tag is plain text the engine understands, so it must not cost a fallback; an
     * undeclared one is markup the engine would read out loud, so it must.
     */
    @Nested
    inner class InlineTags {

        private val allowed = listOf("excited", "clears throat", "very slowly", "laughing")

        @Test
        fun `a declared tag is not markup`() {
            assertEquals(
                emptyList(),
                SpokenTextNormalizer.unexpectedMarkup("[excited]今天不错[clears throat]", allowed)
            )
            assertFalse(SpokenTextNormalizer.containsUnexpectedMarkup("[very slowly]我们开始吧", allowed))
        }

        @Test
        fun `a tag the model does not declare is markup, single-word or not`() {
            assertEquals(listOf("[shouting]"), SpokenTextNormalizer.unexpectedMarkup("[shouting]住手", allowed))
            assertEquals(
                listOf("[deep and loud shouting]"),
                SpokenTextNormalizer.unexpectedMarkup("[deep and loud shouting]住手", allowed)
            )
        }

        @Test
        fun `ssml is markup whatever the whitelist says`() {
            assertEquals(listOf("<break/>"), SpokenTextNormalizer.unexpectedMarkup("前<break/>后", allowed))
        }

        @Test
        fun `a bracketed link is still text`() {
            assertEquals(
                emptyList(),
                SpokenTextNormalizer.unexpectedMarkup("详见 [Docs](https://example.com)", allowed)
            )
        }

        @Test
        fun `tags do not disturb the number and term anchors`() {
            assertTrue(
                SpokenTextNormalizer.numbersPreserved(
                    "2026-10-31 07:18",
                    "[serious]二零二六年十月三十一日七点十八分"
                )
            )
            // Masking keeps offsets, so digits on either side of a tag cannot fuse into one token.
            assertEquals(
                listOf("2026", "31"),
                SpokenTextNormalizer.numericTokens(SpokenTextNormalizer.stripTags("[sad]2026[sad]31"))
            )
            assertEquals(emptyList(), SpokenTextNormalizer.missingTermAnchors("user_id 字段", "[serious]user_id 字段"))
        }

        @Test
        fun `the opening tag is reported without its brackets`() {
            assertEquals("excited", SpokenTextNormalizer.leadingTag("[excited]我们开始吧"))
            assertEquals("very slowly", SpokenTextNormalizer.leadingTag("  [very slowly] 我们开始吧"))
            assertNull(SpokenTextNormalizer.leadingTag("我们开始吧[excited]"))
        }
    }
}
