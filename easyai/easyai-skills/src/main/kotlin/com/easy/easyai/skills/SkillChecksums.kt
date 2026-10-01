package com.easy.easyai.skills

import com.easy.easyai.skills.SkillChecksums.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.*

/**
 * Content fingerprints used by the skill catalog.
 *
 * The `skill.checksum` column stores the SHA-256 of a **whole-directory digest**: every regular
 * file's sanitized relative path and content bytes, walked in sorted path order under the skill
 * directory. Attachments count because the object-storage zip is a verbatim copy of the tree —
 * an un-synced attachment would otherwise be lost on restore. The cost is one extra re-index when
 * only an attachment changed, which is accepted.
 *
 * The [sha256Hex] overload for [String] pins UTF-8 explicitly so the same bytes hash identically
 * on every platform (JVM default charset would split Linux/macOS UTF-8 from Windows CP1252).
 *
 * Hex encoding uses [HexFormat] rather than `joinToString("%02x")`: single allocation, no locale
 * sensitivity, and JDK 17+ is the module floor.
 */
internal object SkillChecksums {

    private val HEX: HexFormat = HexFormat.of()
    private val SEPARATOR = byteArrayOf(0)

    /** Lowercase hex SHA-256 of [bytes]. */
    @JvmStatic
    fun sha256Hex(bytes: ByteArray): String = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase hex SHA-256 of a UTF-8 encoded [text]. */
    @JvmStatic
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * Deterministic digest of one skill directory: `sha256( concat( "relpath\0" + fileBytes + "\0" ) )`
     * over all regular files in sorted relative-path order. Null when the directory or its
     * `SKILL.md` is absent (the caller treats that as "not installed", never as "empty content").
     */
    @JvmStatic
    suspend fun dirDigest(dir: Path): String? = withContext(Dispatchers.IO) {
        val skillFile = dir.resolve(SkillPaths.SKILL_FILE_NAME)
        if (!Files.isDirectory(dir) || !Files.isRegularFile(skillFile)) return@withContext null
        val digest = MessageDigest.getInstance("SHA-256")
        val paths = mutableListOf<Path>()
        try {
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = p.fileName?.toString()
                    return if (p != dir && name != null && name in IGNORED_SCAN_DIRS) {
                        FileVisitResult.SKIP_SUBTREE
                    } else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) paths.add(dir.relativize(file))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    // An unreadable file is an unreadable skill, not a skipped one: fail the digest.
                    throw java.io.UncheckedIOException(exc)
                }
            })
            paths.sortBy { it.joinToString("/") }
            paths.forEach { rel ->
                digest.update(rel.joinToString("/").toByteArray(Charsets.UTF_8))
                digest.update(SEPARATOR)
                Files.newInputStream(dir.resolve(rel)).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.update(SEPARATOR)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext null
        }
        HEX.formatHex(digest.digest())
    }

    private val IGNORED_SCAN_DIRS = setOf(".git", "node_modules", "__pycache__", ".venv")
}
