package com.easy.easyai.skills

import com.easy.easyai.core.storage.ObjectStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.streams.asSequence

/**
 * Zip codec for skill packages — the object-storage leg of the skill pipeline stores exactly one
 * zip per skill: the verbatim directory tree with relative entry names, `SKILL.md` at the root.
 *
 * Hard rules: symlinks and non-regular files are rejected on pack; entries are rejected on unpack
 * unless their names are relative, contain no `..` and resolve strictly inside the target
 * directory (zip-slip); total unpacked size is capped.
 */
internal object SkillPackages {

    private val logger = LoggerFactory.getLogger(SkillPackages::class.java)

    /** Pack [dir] into a zip, failing on symlinks or the [maxBytes] cap. */
    suspend fun pack(dir: Path, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val skillFile = dir.resolve(SkillPaths.SKILL_FILE_NAME)
        require(Files.isRegularFile(skillFile)) { "Skill directory has no SKILL.md: $dir" }
        val buffer = ByteArrayOutputStream()
        var entries = 0
        ZipOutputStream(buffer).use { zip ->
            Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(file)) {
                        throw ObjectStorageException("Skill packages must not contain symlinks: $file")
                    }
                    // Checked before the bytes are read: object storage buffers whole packages in
                    // memory, so an oversized file must be rejected by its stat, not after copying.
                    if (!attrs.isRegularFile) {
                        throw ObjectStorageException("Skill packages must contain regular files only: $file")
                    }
                    if (attrs.size() > maxBytes) {
                        throw ObjectStorageException("Skill file $file exceeds the ${maxBytes}B package cap")
                    }
                    val relative = dir.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(relative))
                    Files.newInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                    entries++
                    if (buffer.size().toLong() > maxBytes) {
                        throw ObjectStorageException(
                            "Skill package exceeds the ${maxBytes}B cap while packing $dir"
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    throw ObjectStorageException("Cannot read skill file $file: ${exc.message}")
                }
            })
        }
        val bytes = buffer.toByteArray()
        logger.debug("Packed {} file(s) from {} into {} zip bytes", entries, dir, bytes.size)
        bytes
    }

    /**
     * Unpack [bytes] into [targetDir], replacing its previous content atomically per file.
     * Rejects hostile entry names and an oversized expansion.
     */
    suspend fun unpack(bytes: ByteArray, targetDir: Path, maxBytes: Long): Unit = withContext(Dispatchers.IO) {
        val canonicalTarget = targetDir.toAbsolutePath().normalize()
        Files.createDirectories(canonicalTarget)
        var written = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                try {
                    if (entry.isDirectory) continue
                    if (name.contains('\\') || name.startsWith("/") || name.split('/').contains("..")) {
                        throw ObjectStorageException("Unsafe skill package entry: $name")
                    }
                    val out = canonicalTarget.resolve(name)
                    if (!out.startsWith(canonicalTarget) || out == canonicalTarget) {
                        throw ObjectStorageException("Unsafe skill package entry: $name")
                    }
                    Files.createDirectories(out.parent)
                    val tmp = Files.createTempFile(canonicalTarget, ".unpack-", ".tmp")
                    try {
                        written += Files.newOutputStream(tmp).use { sink ->
                            copyBounded(zip, sink, maxBytes, written, name)
                        }
                        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(tmp)
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
        if (written == 0L) throw ObjectStorageException("Skill package contains no files")
    }

    /**
     * Copy the current zip entry into [sink], aborting as soon as the [maxBytes] expansion budget is
     * exhausted — a hostile or corrupt entry must not land fully on disk before the cap is noticed.
     */
    private fun copyBounded(
        zip: ZipInputStream,
        sink: OutputStream,
        maxBytes: Long,
        alreadyWritten: Long,
        entryName: String
    ): Long {
        val budget = maxBytes - alreadyWritten
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > budget) {
                throw ObjectStorageException("Skill package entry $entryName expands beyond ${maxBytes}B — refused")
            }
            sink.write(buffer, 0, read)
        }
        return total
    }

    /** Recursively copy [source] into [target] (both directories; target is created). */
    suspend fun copyTree(source: Path, target: Path): Unit = withContext(Dispatchers.IO) {
        val canonicalTarget = target.toAbsolutePath().normalize()
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(canonicalTarget.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file)) {
                    throw ObjectStorageException("Skill source must not contain symlinks: $file")
                }
                val rel = source.relativize(file).toString()
                val out = canonicalTarget.resolve(rel)
                if (!out.startsWith(canonicalTarget)) {
                    throw ObjectStorageException("Unsafe skill source path: $rel")
                }
                Files.createDirectories(out.parent)
                Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Delete a directory tree; tolerant of absence (it is a cache eviction, not accounting).
     *
     * Runs under [NonCancellable]: callers invoke it from cleanup and compensation paths, where
     * abandoning a half-deleted directory would leave an unusable install behind.
     */
    suspend fun deleteTree(dir: Path): Unit = withContext(NonCancellable + Dispatchers.IO) {
        if (!Files.exists(dir)) return@withContext
        try {
            Files.walk(dir).use { stream ->
                // Deepest paths first so directories are only reached once their children are gone.
                stream.asSequence().sortedByDescending { it.nameCount }.forEach { candidate ->
                    try {
                        Files.deleteIfExists(candidate)
                    } catch (e: Exception) {
                        logger.warn("Could not delete {}: {}", candidate, e.message)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not walk skill directory {} for deletion: {}", dir, e.message)
        }
    }

    private const val COPY_BUFFER_SIZE = 8 * 1024
}
