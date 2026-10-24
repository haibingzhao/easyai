package com.easy.easyai.skills

import com.easy.easyai.core.storage.ObjectStorageException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inspector is what turns a downloaded vendor bundle into review cards. These tests pin the
 * discovery rules (multi-skill bundles, nested roots), the pre-scan rename, the per-entry caps and
 * the junk filtering — the parts that decide whether a legitimate archive previews or is refused.
 */
class SkillArchiveInspectorTest {

    private val inspector = SkillArchiveInspector()
    private val cap = 8L * 1024 * 1024

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { out ->
                entries.forEach { (name, content) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(content)
                    out.closeEntry()
                }
            }
        }.toByteArray()

    private fun text(path: String, content: String): Pair<String, ByteArray> =
        path to content.toByteArray()

    private fun skillMd(
        name: String,
        description: String = "Use when testing the archive inspector",
        body: String = "# $name\n\nFollow these steps."
    ): ByteArray = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        appendLine("tags: [demo]")
        appendLine("version: 1.0.0")
        appendLine("examples: [Show $name]")
        appendLine("---")
        appendLine()
        appendLine(body)
    }.toByteArray()

    @Nested
    inner class Discovery {

        @Test
        fun `a single root skill is one candidate`() = runTest {
            val candidates = inspector.inspect(
                zip(
                    text("SKILL.md", String(skillMd("demo"))),
                    text("references/guide.md", "# Guide")
                ),
                cap
            )

            val candidate = candidates.single()
            assertEquals("", candidate.entryPath)
            assertEquals("demo", candidate.name)
            assertEquals("Use when testing the archive inspector", candidate.description)
            assertEquals("1.0.0", candidate.version)
            assertEquals(listOf("demo"), candidate.tags)
            assertEquals(listOf("SKILL.md", "references/guide.md"), candidate.files.map { it.relativePath })
            assertFalse(candidate.scan.blocking)
            assertNull(candidate.invalidReason)
        }

        @Test
        fun `a two-level vendor bundle yields every skill sorted by entry path`() = runTest {
            val entries = buildList {
                repeat(3) { index ->
                    add(text("search-skills/search-$index/SKILL.md", String(skillMd("search-$index"))))
                }
                repeat(3) { index ->
                    add(text("anomaly-skills/anomaly-$index/SKILL.md", String(skillMd("anomaly-$index"))))
                }
                add(text("anomaly-skills/anomaly-0/references/notes.md", "notes"))
            }

            val candidates = inspector.inspect(zip(*entries.toTypedArray()), cap)

            assertEquals(6, candidates.size)
            assertEquals(
                listOf(
                    "anomaly-skills/anomaly-0",
                    "anomaly-skills/anomaly-1",
                    "anomaly-skills/anomaly-2",
                    "search-skills/search-0",
                    "search-skills/search-1",
                    "search-skills/search-2"
                ),
                candidates.map { it.entryPath }
            )
            assertEquals(2, candidates.first().files.size)
        }

        @Test
        fun `a nested SKILL md is part of the outer skill, not a second candidate`() = runTest {
            val candidates = inspector.inspect(
                zip(
                    text("SKILL.md", String(skillMd("outer"))),
                    text("examples/SKILL.md", String(skillMd("inner")))
                ),
                cap
            )

            val candidate = candidates.single()
            assertEquals("outer", candidate.name)
            assertContains(candidate.files.map { it.relativePath }, "examples/SKILL.md")
        }

        @Test
        fun `an archive without SKILL md has no candidates`() = runTest {
            assertTrue(inspector.inspect(zip(text("README.md", "# no skill")), cap).isEmpty())
        }

        @Test
        fun `repository junk is filtered out`() = runTest {
            val candidates = inspector.inspect(
                zip(
                    text("SKILL.md", String(skillMd("demo"))),
                    text("__MACOSX/._SKILL.md", "junk"),
                    text(".git/config", "junk"),
                    text(".DS_Store", "junk")
                ),
                cap
            )

            assertEquals(listOf("SKILL.md"), candidates.single().files.map { it.relativePath })
        }
    }

    @Nested
    inner class Renames {

        @Test
        fun `a rename is applied before the scan so it does not trip name mismatch`() = runTest {
            val candidates = inspector.inspect(
                zip(text("SKILL.md", String(skillMd("old-name")))),
                cap,
                mapOf("" to "new-name")
            )

            val candidate = candidates.single()
            assertEquals("new-name", candidate.name)
            assertFalse(candidate.scan.blocking)
            assertFalse(candidate.scan.findings.any { it.id == "spec.name-mismatch" })
        }

        @Test
        fun `an unknown entry path is ignored`() = runTest {
            val candidates = inspector.inspect(
                zip(text("SKILL.md", String(skillMd("demo")))),
                cap,
                mapOf("missing/path" to "other")
            )

            assertEquals("demo", candidates.single().name)
        }
    }

    @Nested
    inner class Verdicts {

        @Test
        fun `duplicate names mark the later entry invalid`() = runTest {
            val candidates = inspector.inspect(
                zip(
                    text("a/SKILL.md", String(skillMd("same"))),
                    text("b/SKILL.md", String(skillMd("same")))
                ),
                cap
            )

            assertEquals(2, candidates.size)
            assertNull(candidates[0].invalidReason)
            assertContains(candidates[1].invalidReason.orEmpty(), "already named 'same'")
        }

        @Test
        fun `binary assets warn without blocking`() = runTest {
            val candidates = inspector.inspect(
                zip(
                    text("SKILL.md", String(skillMd("demo"))),
                    "assets/logo.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)
                ),
                cap
            )

            val candidate = candidates.single()
            assertFalse(candidate.scan.blocking)
            val binary = candidate.scan.findings.single { it.id == "spec.binary-file" }
            assertEquals(SkillScanSeverity.HIGH, binary.severity)
            assertFalse(binary.blocking)
            assertFalse(candidate.scan.requiresAcknowledgement)
        }

        @Test
        fun `a skill over the file cap is marked invalid but does not hide the others`() = runTest {
            val entries = buildList {
                add(text("big/SKILL.md", String(skillMd("big"))))
                repeat(200) { index -> add(text("big/assets/file-$index.txt", "x")) }
                add(text("small/SKILL.md", String(skillMd("small"))))
            }

            val candidates = inspector.inspect(zip(*entries.toTypedArray()), cap)

            assertEquals(2, candidates.size)
            val big = candidates.single { it.name == "big" }
            assertContains(big.invalidReason.orEmpty(), "201 files")
            assertTrue(big.scan.blocking)
            assertNull(candidates.single { it.name == "small" }.invalidReason)
        }

        @Test
        fun `skills beyond the archive cap are reported without being scanned`() = runTest {
            val entries = (0 until 21).map { index ->
                text("s$index/SKILL.md", String(skillMd("skill-$index")))
            }

            val candidates = inspector.inspect(zip(*entries.toTypedArray()), cap)

            assertEquals(21, candidates.size)
            val rejected = candidates.filter { it.invalidReason?.contains("more than 20") == true }
            assertEquals(1, rejected.size)
            assertTrue(rejected.single().scan.blocking)
        }

        @Test
        fun `an unparsable SKILL md is surfaced instead of dropped`() = runTest {
            val candidates = inspector.inspect(
                zip(text("SKILL.md", "# no frontmatter at all")),
                cap
            )

            val candidate = candidates.single()
            assertContains(candidate.invalidReason.orEmpty(), "could not be parsed")
            assertTrue(candidate.scan.blocking)
        }
    }

    @Nested
    inner class Safety {

        @Test
        fun `zip slip is refused before discovery`() = runTest {
            assertFailsWith<ObjectStorageException> {
                inspector.inspect(zip(text("../evil.txt", "escape")), cap)
            }
        }

        @Test
        fun `the expansion cap is enforced`() = runTest {
            assertFailsWith<ObjectStorageException> {
                inspector.inspect(zip(text("SKILL.md", "x".repeat(2048))), 1024)
            }
        }

        @Test
        fun `the temporary tree is deleted even when unpacking fails`() = runTest {
            val tmp = Path.of(System.getProperty("java.io.tmpdir"))
            fun stages(): Long = Files.list(tmp).use { paths ->
                paths.filter { it.fileName.toString().startsWith("easyai-skill-archive-") }.count()
            }
            val before = stages()

            assertFailsWith<ObjectStorageException> {
                inspector.inspect(zip(text("../evil.txt", "escape")), cap)
            }

            assertEquals(before, stages())
        }
    }

    @Test
    fun `sha256 pins the exact archive bytes`() {
        val bytes = zip(text("SKILL.md", String(skillMd("demo"))))

        assertEquals(64, inspector.sha256Hex(bytes).length)
        assertTrue(inspector.sha256Hex(bytes).matches(Regex("[0-9a-f]{64}")))
    }
}
