package com.easy.easyai.speech

import com.easy.easyai.api.model.SpeechCapabilities
import com.easy.easyai.api.model.VoiceSpec
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Voice lookup, which is the boundary between what the director writes and what the renderer accepts.
 *
 * The catalog keys voices by an opaque `id`; the prompt shows a human label next to it. Every case here
 * is a way those two get confused in a real plan, and the rule is always the same: resolve to the
 * catalog's own id, or refuse and let the caller fall back to the default voice.
 */
class SpeechCapabilityResolverTest {

    private val resolver = SpeechCapabilityResolver()

    private fun capabilities(vararg voices: VoiceSpec, defaultVoice: String? = voices.firstOrNull()?.voice) =
        resolver.resolve(
            SpeechCapabilities(
                defaultVoice = defaultVoice,
                voices = voices.toList()
            )
        )

    private val huan = VoiceSpec(voice = "longanhuan_v3.1", name = "龙安欢_v3.1", group = "flagship")
    private val sisi = VoiceSpec(voice = "longsisi_v3.1", name = "龙思思_v3.1", group = "flagship")

    @Test
    fun `an exact id resolves to itself`() {
        assertEquals(huan, capabilities(huan, sisi).voice("longanhuan_v3.1"))
    }

    @Nested
    inner class DisplayLabels {

        @Test
        fun `the whole first prompt cell resolves to the id inside it`() {
            // The prompt line the director copies from: `id｜名称｜特质: …｜场景: …`
            val caps = capabilities(huan, sisi)
            val spec = assertNotNull(caps.voice("longanhuan_v3.1｜龙安欢_v3.1｜特质: 温柔,清澈｜场景: 陪伴"))
            assertEquals("longanhuan_v3.1", spec.voice)
        }

        @Test
        fun `an ascii separator is cut the same way as the full-width one`() {
            // A model retyping the line by hand sometimes uses `|`, and a plan that falls back over
            // punctuation would lose a voice choice for no reason.
            assertEquals("longanhuan_v3.1", capabilities(huan, sisi).voice("longanhuan_v3.1|龙安欢_v3.1")?.voice)
        }

        @Test
        fun `surrounding whitespace does not hide a voice`() {
            assertEquals("longsisi_v3.1", capabilities(huan, sisi).voice("  longsisi_v3.1 \n")?.voice)
        }

        @Test
        fun `a name on its own resolves when exactly one voice carries it`() {
            assertEquals("longanhuan_v3.1", capabilities(huan, sisi).voice("龙安欢_v3.1")?.voice)
        }

        @Test
        fun `an ambiguous name is refused instead of guessed`() {
            // Two entries sharing a display name are two different timbres; picking the first would
            // be a silent substitution, which is worse than the honest fallback.
            val twin = VoiceSpec(voice = "longanhuan_v3.6", name = "龙安欢")
            val named = VoiceSpec(voice = "longanhuan_v3.1", name = "龙安欢")
            assertNull(capabilities(named, twin).voice("龙安欢"))
        }

        @Test
        fun `a voice the model invents stays unresolved`() {
            assertNull(capabilities(huan, sisi).voice("longanhuan_v9.9｜龙安欢_v9.9"))
        }

        @Test
        fun `a blank voice stays unresolved`() {
            assertNull(capabilities(huan, sisi).voice("   "))
        }
    }

    @Nested
    inner class Canonicalization {

        @Test
        fun `the id is the part before the first separator, trimmed`() {
            assertEquals(
                "longanhuan_v3.1",
                ResolvedSpeechCapabilities.canonicalVoiceId("  longanhuan_v3.1｜龙安欢_v3.1｜特质: 温柔 ")
            )
        }

        @Test
        fun `a bare id is returned untouched`() {
            assertEquals("longanhuan_v3.1", ResolvedSpeechCapabilities.canonicalVoiceId("longanhuan_v3.1"))
        }
    }
}
