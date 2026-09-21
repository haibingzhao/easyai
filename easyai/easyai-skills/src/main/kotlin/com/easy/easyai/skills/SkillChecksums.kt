package com.easy.easyai.skills

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Content fingerprints used by the skill catalog.
 *
 * The `skill.checksum` column stores the SHA-256 of the **raw SKILL.md bytes** — attachments
 * deliberately excluded, since they never enter the retrieval index and must not force a
 * re-index on their own.
 *
 * The [sha256Hex] overload for [String] pins UTF-8 explicitly: this function feeds
 * [SkillScopeResolver.projectHashOf], whose output is a column of the `(user_id, name,
 * project_hash)` UNIQUE index. Letting the JVM default charset decide would let the same
 * non-ASCII project path hash differently on Linux/macOS (UTF-8) vs Windows (CP1252), splitting
 * one workspace into two catalog identities.
 *
 * Hex encoding uses [HexFormat] rather than `joinToString("%02x")`: single allocation, no locale
 * sensitivity, and JDK 17+ is the module floor.
 */
internal object SkillChecksums {

    private val HEX: HexFormat = HexFormat.of()

    /** Lowercase hex SHA-256 of [bytes]. */
    @JvmStatic
    fun sha256Hex(bytes: ByteArray): String = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase hex SHA-256 of a UTF-8 encoded [text]. */
    @JvmStatic
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}
