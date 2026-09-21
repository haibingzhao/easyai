package com.easy.easyai.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the two properties the catalog depends on:
 *
 * 1. **UTF-8 pinning (M2)** — [SkillChecksums.sha256Hex] for [String] must not read the platform
 *    default charset. `projectHashOf` feeds a UNIQUE index column, so the same non-ASCII path
 *    hashing differently on Linux (UTF-8) vs Windows (CP1252) would split one workspace into two
 *    catalog identities.
 *
 * 2. **Lowercase hex** — [java.util.HexFormat.of] defaults to lowercase; the catalog stores what
 *    this returns verbatim, and equality checks in [SkillIndexer] are string-based.
 */
class SkillChecksumsTest {

    @Test
    fun `string overload matches explicit UTF-8 encoding`() {
        val text = "café/naïve/日本語-path"
        val expected = SkillChecksums.sha256Hex(text.toByteArray(Charsets.UTF_8))
        assertEquals(expected, SkillChecksums.sha256Hex(text),
            "the String overload must pin UTF-8, not the platform default")
    }

    @Test
    fun `non-ASCII text produces a different hash than its lossy ASCII transliteration`() {
        // If the JVM default charset were CP1252 and the encoding silently dropped the accents, both
        // inputs would hash to the same value on that platform. Under pinned UTF-8 they never do.
        val accent = "café"
        val plain = "cafe"
        assertNotEquals(SkillChecksums.sha256Hex(plain), SkillChecksums.sha256Hex(accent))
    }

    @Test
    fun `output is 64 lowercase hex characters`() {
        val hash = SkillChecksums.sha256Hex("anything")
        assertEquals(64, hash.length, "SHA-256 hex is 32 bytes * 2 chars")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' },
            "the catalog stores lowercase; HexFormat.of() defaults to lowercase and must not be upper-cased: $hash")
    }

    @Test
    fun `same input hashes identically across calls`() {
        val text = "deterministic"
        assertEquals(SkillChecksums.sha256Hex(text), SkillChecksums.sha256Hex(text))
    }
}
