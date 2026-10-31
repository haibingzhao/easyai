package com.easy.easyai.web.service

import com.easy.easyai.speech.SpeechScript
import org.springframework.stereotype.Component

/**
 * Short-lived, owner-scoped storage for generated read-aloud plans.
 *
 * A plan is worth keeping for three reasons, and only briefly:
 *  - the preview panel and the render request must refer to the *same* plan, or what the user edited
 *    is not what gets spoken;
 *  - an edited plan needs its previous revision to bump from;
 *  - the original text has to travel with the plan, because an edited script is re-validated against
 *    the source the server generated it from, never against text the client sends back.
 *
 * These are derived artifacts, so an entry is dropped on TTL and on capacity pressure. Nothing the
 * user explicitly saved lives here.
 */
@Component
class SpeechPlanStore {

    /** A stored plan plus the text it was built for; the text is what an edit is re-validated against. */
    data class Entry(
        val owner: String,
        val script: SpeechScript,
        val sourceText: String,
        val expiresAt: Long
    )

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    /** Store a plan and return it; the caller keeps whatever id the director assigned. */
    @Synchronized
    fun put(owner: String, script: SpeechScript, sourceText: String): SpeechScript {
        entries[script.scriptId] = Entry(owner, script, sourceText, System.currentTimeMillis() + TTL_MS)
        return script
    }

    /** Owner-scoped read: another account's id must not resolve, even if it is guessed correctly. */
    @Synchronized
    fun get(owner: String, scriptId: String): Entry? {
        val entry = entries[scriptId] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            entries.remove(scriptId)
            return null
        }
        return entry.takeIf { it.owner == owner }
    }

    /**
     * Replace a plan only if it is still at [expectedRevision].
     *
     * Two tabs (or a preview and a render racing each other) both read revision 1 and both submit an
     * edit; without this comparison the second write would silently discard the first one's segments.
     * Returns false when the plan has moved on or expired, so the caller can say so instead of
     * rendering a plan the user never saw.
     */
    @Synchronized
    fun putIfRevision(
        owner: String,
        script: SpeechScript,
        expectedRevision: Int,
        sourceText: String
    ): Boolean {
        val current = entries[script.scriptId] ?: return false
        if (current.expiresAt < System.currentTimeMillis()) {
            entries.remove(script.scriptId)
            return false
        }
        if (current.owner != owner || current.script.revision != expectedRevision) return false
        entries[script.scriptId] = Entry(owner, script, sourceText, System.currentTimeMillis() + TTL_MS)
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        /** Long enough to read a reply, edit its script and press play again. */
        const val TTL_MS = 10 * 60 * 1000L

        /** Bounded so a busy account cannot pin plans in memory for the whole JVM. */
        const val MAX_ENTRIES = 64
    }
}
