package com.easy.easyai.speech

import java.math.BigDecimal
import java.math.MathContext
import java.security.MessageDigest

/**
 * Deterministic anchors used to prove that a spoken rewrite did not lose source facts.
 *
 * Two kinds of anchor are extracted, in document order:
 *  - **numbers**, normalized to their value, so `20`, `二十` and `两` are the same fact;
 *  - **dates and clock times**, normalized to `date:2026-10-31` / `time:07:18`.
 *
 * Calendars are matched before numbers and their spans are masked, because a hyphenated date is not
 * a subtraction. `2026-10-31` used to tokenize as `2026, -10, -31` (the sign prefix in
 * [NUMBER_PATTERN] ate the hyphens), so every natural rewrite — `二零二六年十月三十一日`,
 * `2026年10月31日` — looked like it had changed the numbers, and the segment fell back to reading the
 * raw ISO string. `07:18` had the same problem from the other side: its spoken form is `七点十八分`,
 * which used to be dropped entirely (`十` is not a digit-by-digit decimal fraction).
 *
 * A spoken date may drop the year — repeating the year is what people skip, and the year is not a
 * new fact — but it may never drop the month or day, and it may never invent a year the source did
 * not state. That is checked by [numbersPreserved], not by list equality.
 *
 * Inline emotion and paralinguistic tags (`[excited]`, `[clears throat]`) are **not** markup: the
 * vendor's TTS reads them straight out of the text. [unexpectedMarkup] therefore judges a bracket
 * against the model's declared vocabulary ([ResolvedSpeechCapabilities.spokenTags]) rather than
 * rejecting every bracket, and [stripTags] masks them before numbers and terms are compared.
 */
object SpokenTextNormalizer {

    private val NUMBER_PATTERN = Regex(
        "(?:百分之|千分之)?(?:正负|[±+])?(?:负|-)?(?:\\d+(?:\\.\\d+)?%?|" +
            "[零〇一二两三四五六七八九十百千万亿点半]+)"
    )
    private val URL_PATTERN = Regex("https?://([^/\\s]+)", RegexOption.IGNORE_CASE)
    private val IDENTIFIER_PATTERN = Regex(
        "\\b[A-Za-z_][A-Za-z0-9_]*(?:[._~/][A-Za-z0-9_]+)+\\b|" +
            "\\b[a-z]+(?:[A-Z][A-Za-z0-9]*)+\\b|\\b[A-Za-z]+_[A-Za-z0-9_]+\\b"
    )
    private val XML_MARKUP_PATTERN = Regex("</?[A-Za-z][^>]*>")
    // A bracketed tag: `[sad]`, `[laughing]`, and the multi-word kinds the vendor also publishes
    // (`[clears throat]`, `[very slowly]`, `[deep and loud shouting]`). The name must start with a
    // letter, so `[0]` stays a literal subscript rather than a tag.
    //
    // A Markdown link keeps its bracket as text (`[Docs](https://…)`), so a bracketed word followed
    // by an opening parenthesis is a link, not a tag. Everything else in brackets is markup: a false
    // positive only costs a fallback to the source sentence.
    private val BRACKET_TAG = Regex("\\[[A-Za-z][A-Za-z0-9 _-]{0,39}](?!\\()")

    /** A calendar date: `2026-10-31`, `2026/10/31`, `2026.10.31`. */
    private val ISO_DATE = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")

    /** A wall clock: `07:18` or `7:18:30`. The lookarounds keep a `x:8080` port out of it. */
    private val CLOCK = Regex("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)")

    /** Every character a spoken numeral may be built from; `点` is excluded because it separates. */
    private const val NUMERAL = "[〇零一二三四五六七八九十]"

    private const val MERIDIEM = "(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|夜里)"

    /** `2026年10月31日`, `二零二六年十月三十一日`. */
    private val SPOKEN_DATE = Regex(
        "(\\d{4}|$NUMERAL{2,4})\\s*年\\s*(\\d{1,2}|$NUMERAL{1,3})\\s*月\\s*" +
            "(\\d{1,2}|$NUMERAL{1,3})\\s*[日号]"
    )

    /** `10月31日` — the same date with the year spoken away. */
    private val SPOKEN_DATE_NO_YEAR = Regex(
        "(?<!年)(?<!$NUMERAL)(?<!\\d)(\\d{1,2}|$NUMERAL{1,3})\\s*月\\s*" +
            "(\\d{1,2}|$NUMERAL{1,3})\\s*[日号]"
    )

    /**
     * `七点十八分`, `十点半`, `七点整`, `上午七点十八分`.
     *
     * Only a clock that names a minute (or `半`/`整`/`钟`) is anchored. A bare `十点` is left out on
     * purpose: in Chinese prose it is as often "ten points" (`以下十点`) as ten o'clock, and anchoring
     * it would fail a correct rewrite such as `以下十个要点`. The cost is that a writer who says
     * `十一点` for `11:00` falls back to the source — the safe direction.
     *
     * The minute group is kept loose (`分` optional) and validated in [clockMinute], because `三点五`
     * is a decimal and must not be decoded as three-oh-five.
     */
    private val SPOKEN_CLOCK = Regex(
        "$MERIDIEM?\\s*(\\d{1,2}|$NUMERAL{1,3})\\s*[点時时]\\s*" +
            "(半|整|钟|(?:\\d{1,2}|$NUMERAL{1,3})\\s*分?)"
    )

    /** Hours past noon never stay as they are: `晚上七点` is 19:00, not 07:00. */
    private val PM_MERIDIEMS = setOf("下午", "傍晚", "晚上", "夜里")
    private val AM_MERIDIEMS = setOf("凌晨", "清晨", "早上", "上午")

    /**
     * IANA zones a rewrite may name in Chinese instead of repeating the identifier.
     *
     * Spelled out per zone rather than derived: "亚洲/上海" is not how anyone says `Asia/Shanghai`,
     * while "北京时间" is what the same zone is actually called out loud.
     */
    private val ZONE_ALIASES = mapOf(
        "asia/shanghai" to listOf("上海时区", "北京时间", "中国标准时间", "亚洲上海", "东八区", "utc+8", "gmt+8"),
        "asia/hong_kong" to listOf("香港时间", "香港时区"),
        "asia/tokyo" to listOf("日本时间", "东京时间"),
        "utc" to listOf("协调世界时", "世界标准时间")
    )

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private val SMALL_UNITS = mapOf('十' to 10L, '百' to 100L, '千' to 1_000L)

    @JvmStatic
    fun sourceDigest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "", prefix = "sha256:") { "%02x".format(it) }
    }

    /** Every anchor in [text], in the order it is spoken. */
    @JvmStatic
    fun numericTokens(text: String): List<String> = scan(text).map { it.token }

    /**
     * True when [spoken] preserves every number, date and clock time in [source].
     *
     * Deliberately not `numericTokens(source) == numericTokens(spoken)`: a spoken date may leave the
     * year out, and that is not a lost fact. A changed minute, a dropped day or an invented year is.
     */
    @JvmStatic
    fun numbersPreserved(source: String, spoken: String): Boolean {
        val expected = scan(source).map { it.token }
        // Tags are masked on the spoken side only: a rewrite may carry `[sad]`, the source may not
        // be assumed to be free of bracketed text, and blanking the source could hide a real number.
        val actual = scan(stripTags(spoken)).map { it.token }
        if (expected.size != actual.size) return false
        return expected.indices.all { equivalent(expected[it], actual[it]) }
    }

    /** URL hosts and code-like identifiers must remain visible in the spoken rewrite. */
    @JvmStatic
    fun termAnchors(text: String): List<String> = buildList {
        URL_PATTERN.findAll(text).forEach { add(it.groupValues[1].lowercase()) }
        IDENTIFIER_PATTERN.findAll(text).forEach { add(it.value) }
    }.distinct()

    /**
     * Anchors [source] carries that [spoken] lost.
     *
     * An IANA zone is the one anchor a rewrite may translate instead of repeating: `Asia/Shanghai`
     * spoken as "上海时区" keeps the fact, while `user_id` spoken as "用户编号" does not.
     */
    @JvmStatic
    fun missingTermAnchors(source: String, spoken: String): List<String> {
        // `[sad]` is not a lost identifier, so the spoken side is read without its tags.
        val lower = stripTags(spoken).lowercase()
        return termAnchors(source).filterNot { anchor ->
            lower.contains(anchor.lowercase()) || zoneNamedInChinese(anchor, lower)
        }
    }

    /**
     * Markup that [text] carries but [allowed] does not explain, in document order.
     *
     * The vendor publishes two families of inline tag (control and paralinguistic) and writes them
     * straight into the text it synthesizes, so a rewrite is allowed to carry the ones the model
     * declares. Anything else — XML, SSML, an invented tag, a bracketed annotation — is markup the
     * engine would either reject or read out loud, and it makes the segment fall back to source.
     *
     * @param allowed bracket-free tag names, e.g. `["excited", "clears throat"]`.
     */
    @JvmStatic
    fun unexpectedMarkup(text: String, allowed: Collection<String>): List<String> {
        val allowedNames = allowed.mapTo(HashSet()) { ResolvedSpeechCapabilities.normalizeTag(it) }
        return buildList {
            XML_MARKUP_PATTERN.findAll(text).forEach { add(it.value) }
            BRACKET_TAG.findAll(text).forEach { match ->
                val name = match.value.removePrefix("[").removeSuffix("]").trim()
                if (name !in allowedNames) add(match.value)
            }
        }
    }

    /** True when [text] carries markup [allowed] does not explain; see [unexpectedMarkup]. */
    @JvmStatic
    fun containsUnexpectedMarkup(text: String, allowed: Collection<String>): Boolean =
        unexpectedMarkup(text, allowed).isNotEmpty()

    /**
     * Any markup at all, whitelist ignored.
     *
     * Kept for callers that have no model capabilities to compare against; a caller that does should
     * use [unexpectedMarkup] instead, because a declared tag is not a defect.
     */
    @JvmStatic
    fun containsRawMarkup(text: String): Boolean = containsUnexpectedMarkup(text, emptyList())

    /**
     * The bracket tag [text] opens with, bracket-free, or null when it opens with plain words.
     *
     * Used to decide whether a segment already sets its own style: `[excited]开场` does not also
     * need a segment-level emotion, and prepending one would override the tag the writer chose.
     */
    @JvmStatic
    fun leadingTag(text: String): String? {
        val trimmed = text.trimStart()
        val match = BRACKET_TAG.find(trimmed) ?: return null
        if (match.range.first != 0) return null
        return match.value.removePrefix("[").removeSuffix("]").trim()
    }

    /**
     * [text] with every tag blanked out, length preserved.
     *
     * Tags are masked rather than deleted so two numbers that were separated by a tag cannot fuse
     * into a different one, and so a diagnostic can still point at an offset in the original.
     */
    @JvmStatic
    fun stripTags(text: String): String = XML_MARKUP_PATTERN.replace(BRACKET_TAG.replace(text) { mask(it.value) }) {
        mask(it.value)
    }

    private fun mask(value: String): String = " ".repeat(value.length)

    private fun zoneNamedInChinese(anchor: String, lowerSpoken: String): Boolean =
        ZONE_ALIASES[anchor.lowercase()]?.any { lowerSpoken.contains(it) } == true

    /** One anchor and where it sits, so calendar spans can be masked before numbers are scanned. */
    private data class Hit(val start: Int, val end: Int, val token: String)

    private fun scan(text: String): List<Hit> {
        val spans = ArrayList<Hit>()
        ISO_DATE.findAll(text).forEach { match -> dateHit(match, hasYear = true)?.let(spans::add) }
        CLOCK.findAll(text).forEach { match ->
            val second = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
            clockToken(match.groupValues[1].toIntOrNull(), match.groupValues[2].toIntOrNull(), second)
                ?.let { spans += Hit(match.range.first, match.range.last + 1, it) }
        }
        SPOKEN_DATE.findAll(text).forEach { match -> dateHit(match, hasYear = true)?.let(spans::add) }
        SPOKEN_DATE_NO_YEAR.findAll(text).forEach { match -> dateHit(match, hasYear = false)?.let(spans::add) }
        SPOKEN_CLOCK.findAll(text).forEach { match -> clockHit(match)?.let(spans::add) }

        // Longest at the same start wins, so `二零二六年十月三十一日` is one date and not a date plus
        // the stray `十月三十一日` inside it; the shorter overlapping copy is dropped.
        val calendars = ArrayList<Hit>()
        for (hit in spans.sortedWith(compareBy<Hit> { it.start }.thenByDescending { it.end })) {
            if (calendars.isNotEmpty() && hit.start < calendars.last().end) continue
            calendars += hit
        }

        // Numbers are scanned on a masked copy: source offsets stay valid, and the digits and hyphens
        // a calendar already accounted for cannot be counted a second time as negative numbers.
        val masked = text.toCharArray().also { chars ->
            calendars.forEach { hit -> for (index in hit.start until hit.end) chars[index] = ' ' }
        }.concatToString()
        val numbers = NUMBER_PATTERN.findAll(masked).mapNotNull { match ->
            normalizeNumber(match.value)?.let { Hit(match.range.first, match.range.last + 1, it) }
        }
        return (calendars + numbers).sortedBy { it.start }
    }

    private fun dateHit(match: MatchResult, hasYear: Boolean): Hit? {
        val offset = if (hasYear) 1 else 0
        val year = if (hasYear) intValue(match.groupValues[1]) else null
        val month = intValue(match.groupValues[1 + offset]) ?: return null
        val day = intValue(match.groupValues[2 + offset]) ?: return null
        if (hasYear && year == null) return null
        if (month !in 1..12 || day !in 1..31) return null
        val token = if (year == null) {
            "date:*-" + pad(month, 2) + "-" + pad(day, 2)
        } else {
            "date:" + pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2)
        }
        return Hit(match.range.first, match.range.last + 1, token)
    }

    private fun clockHit(match: MatchResult): Hit? {
        val meridiem = match.groupValues[1].takeIf { it.isNotEmpty() }
        val hour = intValue(match.groupValues[2]) ?: return null
        val minute = clockMinute(match.groupValues[3]) ?: return null
        return clockToken(to24Hour(hour, meridiem), minute, null)
            ?.let { Hit(match.range.first, match.range.last + 1, it) }
    }

    /**
     * The minute of a spoken clock, or null when this is not a clock at all.
     *
     * `七点十八` and `七点十八分` are times; `三点五` is a decimal. A minute written without `分` is
     * therefore only accepted when it carries a unit (`十`/`百`) — a digit-by-digit decimal fraction
     * never does. That is why `七点十八` used to disappear from the comparison instead of reading 7:18.
     */
    private fun clockMinute(raw: String): Int? {
        val text = raw.trim()
        when (text) {
            "半" -> return 30
            "整", "钟" -> return 0
        }
        val hasMinuteMark = text.endsWith("分")
        val digits = text.removeSuffix("分").trim()
        if (digits.isEmpty()) return null
        val value = intValue(digits) ?: return null
        if (value !in 0..59) return null
        if (!hasMinuteMark && digits.none { it == '十' || it == '百' }) return null
        return value
    }

    private fun to24Hour(hour: Int, meridiem: String?): Int? {
        if (hour !in 0..24) return null
        return when {
            meridiem == null -> hour
            meridiem in PM_MERIDIEMS && hour in 1..11 -> hour + 12
            meridiem in AM_MERIDIEMS && hour == 12 -> 0
            else -> hour
        }
    }

    private fun clockToken(hour: Int?, minute: Int?, second: Int?): String? {
        if (hour == null || minute == null) return null
        if (hour !in 0..24 || minute !in 0..59) return null
        if (second != null && second !in 0..59) return null
        val time = pad(hour, 2) + ":" + pad(minute, 2)
        return "time:" + if (second == null) time else "$time:" + pad(second, 2)
    }

    /** A spoken date may drop the year; nothing else may change. */
    private fun equivalent(source: String, spoken: String): Boolean {
        if (source == spoken) return true
        if (!source.startsWith("date:") || !spoken.startsWith("date:*-")) return false
        return source.substringAfter('-') == spoken.substringAfter('-')
    }

    private fun intValue(token: String): Int? =
        if (token.all { it.isDigit() }) token.toIntOrNull() else parseChineseInteger(token)?.toInt()

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    private fun normalizeNumber(raw: String): String? {
        var token = raw
        var divisor = BigDecimal.ONE
        var sign = ""
        when {
            token.startsWith("百分之") -> {
                divisor = BigDecimal(100)
                token = token.removePrefix("百分之")
            }
            token.startsWith("千分之") -> {
                divisor = BigDecimal(1_000)
                token = token.removePrefix("千分之")
            }
            token.endsWith("%") -> {
                divisor = BigDecimal(100)
                token = token.removeSuffix("%")
            }
        }
        when {
            token.startsWith("正负") || token.startsWith("±") -> {
                sign = "±"
                token = token.removePrefix("正负").removePrefix("±")
            }
            token.startsWith("负") || token.startsWith("-") -> {
                sign = "-"
                token = token.removePrefix("负").removePrefix("-")
            }
            token.startsWith("+") -> token = token.removePrefix("+")
        }
        val value = if (token.any { it.isDigit() }) {
            token.toBigDecimalOrNull()
        } else {
            parseChineseNumber(token)
        } ?: return null
        val normalized = value.divide(divisor, MathContext.DECIMAL128).stripTrailingZeros().toPlainString()
        return sign + normalized
    }

    private fun parseChineseNumber(raw: String): BigDecimal? {
        if (raw.isEmpty()) return null
        if (raw == "半") return BigDecimal("0.5")
        val pointParts = raw.split('点')
        if (pointParts.size > 2) return null
        val integer = parseChineseInteger(pointParts[0]) ?: return null
        if (pointParts.size == 1) return integer
        val fraction = pointParts[1]
        if (fraction.isEmpty() || fraction.any { DIGITS[it] == null }) return null
        return BigDecimal("${integer.toPlainString()}.${fraction.map { DIGITS.getValue(it) }.joinToString("")}")
    }

    private fun parseChineseInteger(raw: String): BigDecimal? {
        if (raw.isEmpty()) return BigDecimal.ZERO
        // 亿 and 万 are the only units that scale everything to their left, and they nest:
        // "一亿两千万" is 1×10^8 + 2×10^7, not (1×10^8 + 2000)×10^4. Splitting at the highest unit
        // first, then parsing each half the same way, is what keeps that nesting right.
        val yi = raw.indexOf('亿')
        if (yi >= 0) {
            val high = if (yi == 0) BigDecimal.ONE else parseChineseInteger(raw.substring(0, yi)) ?: return null
            val low = parseChineseInteger(raw.substring(yi + 1)) ?: return null
            return high.multiply(BigDecimal(100_000_000)).add(low)
        }
        val wan = raw.indexOf('万')
        if (wan >= 0) {
            val high = if (wan == 0) BigDecimal.ONE else parseChineseInteger(raw.substring(0, wan)) ?: return null
            val low = parseChineseInteger(raw.substring(wan + 1)) ?: return null
            return high.multiply(BigDecimal(10_000)).add(low)
        }
        if (raw.none { SMALL_UNITS.containsKey(it) }) {
            val digits = raw.map { DIGITS[it] ?: return null }
            return digits.joinToString("").toBigDecimalOrNull()
        }
        var section = 0L
        var number = 0L
        raw.forEach { char ->
            DIGITS[char]?.let { digit ->
                number = digit.toLong()
                return@forEach
            }
            val unit = SMALL_UNITS[char] ?: return null
            if (number == 0L) number = 1
            section += number * unit
            number = 0
        }
        return BigDecimal(section + number)
    }
}
