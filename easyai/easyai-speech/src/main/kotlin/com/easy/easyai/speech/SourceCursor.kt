package com.easy.easyai.speech

/**
 * Maps a list of planned segments back onto the original text, one segment at a time.
 *
 * The only tolerance is whitespace: a director routinely reflows line breaks and re-wraps a copied
 * sentence, which changes bytes without changing content. Every other character has to match, so a
 * plan that drops a word fails at the segment where the gap appears instead of at the end of the
 * document — which is what makes per-segment validation possible for an incremental plan.
 *
 * The slice handed back is cut from the *original* text, not from the model's copy, so retrying a
 * failed segment speaks exactly what the user can read.
 */
internal class SourceCursor(source: String) {

    private val original: String = source
    private val normalized: String
    /** `offsets[i]` is the index in [original] of the i-th normalized character. */
    private val offsets: IntArray
    private var cursor: Int = 0
    /** End of the last returned slice in [original]; see [peek]. */
    private var lastEnd: Int = 0

    init {
        val text = StringBuilder(source.length)
        val map = ArrayList<Int>(source.length)
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (char.isWhitespace()) {
                val runStart = index
                while (index < source.length && source[index].isWhitespace()) index++
                // A leading or trailing run carries no searchable character; interior runs keep one.
                if (text.isNotEmpty() && index < source.length) {
                    text.append(' ')
                    map.add(runStart)
                }
            } else {
                text.append(char)
                map.add(index)
                index++
            }
        }
        normalized = text.toString()
        offsets = map.toIntArray()
    }

    /**
     * Consume the next segment and return the authoritative source slice, or null when the segment
     * does not continue the source: not found, out of order, overlapping, or leaving a substantive
     * gap. Whitespace-only gaps are allowed because they are not content.
     */
    fun next(declaredSourceText: String): String? {
        val needle = normalize(declaredSourceText)
        if (needle.isEmpty()) return null
        val at = normalized.indexOf(needle, cursor)
        if (at < 0) return null
        if (normalized.substring(cursor, at).isNotBlank()) return null
        val end = at + needle.length
        val startIndex = offsets[at]
        val endIndex = if (end < offsets.size) offsets[end] else original.length
        cursor = end
        lastEnd = endIndex
        return original.substring(startIndex, endIndex).trim()
    }

    /**
     * The raw character immediately after the slice [next] just returned, or null at the end.
     *
     * Deliberately not whitespace-skipping: whitespace is exactly what proves a boundary did *not*
     * cut a token, so `"the user"` + `"id is 5"` must not look like one spliced identifier.
     *
     * Needed to see a boundary that falls *inside* a token: `"上限是2"` + `"0个"` passes every
     * per-segment number check while the audio says "two" then "zero" instead of "twenty".
     */
    fun peek(): Char? = original.getOrNull(lastEnd)

    /** True once the consumed slices account for the whole source. */
    fun complete(): Boolean = normalized.substring(cursor).isBlank()

    /** Normalized characters consumed so far; used only for diagnostics. */
    val consumed: Int get() = cursor

    companion object {
        /** Collapse whitespace runs to one space and trim — the comparison form of a segment. */
        @JvmStatic
        fun normalize(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                if (text[index].isWhitespace()) {
                    val runStart = index
                    while (index < text.length && text[index].isWhitespace()) index++
                    if (out.isNotEmpty() && index < text.length) out.append(' ')
                } else {
                    out.append(text[index])
                    index++
                }
            }
            return out.toString()
        }
    }
}
