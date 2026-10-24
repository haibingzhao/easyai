package com.easy.easyai.skills

import com.easy.easyai.core.storage.ObjectStorageException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Archive hardening shared by browser uploads and agent-installed archives. */
class SkillPackagesTest {

    @TempDir lateinit var temp: Path

    private val cap = 8L * 1024 * 1024

    private fun zip(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { out ->
                entries.forEach { (name, content) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(content.toByteArray())
                    out.closeEntry()
                }
            }
        }.toByteArray()

    @Nested
    inner class Unpack {

        @Test
        fun `a plain archive lands under the target directory`() = runTest {
            val target = temp.resolve("unpacked")

            SkillPackages.unpack(zip("SKILL.md" to "body", "references/a.md" to "a"), target, cap)

            assertEquals("body", target.resolve("SKILL.md").readText())
            assertEquals("a", target.resolve("references/a.md").readText())
        }

        @Test
        fun `entries beyond the cap are refused`() = runTest {
            val entries = (0..2000).map { "file-$it.txt" to "x" }.toTypedArray()

            val error = assertFailsWith<ObjectStorageException> {
                SkillPackages.unpack(zip(*entries), temp.resolve("unpacked"), cap)
            }

            assertTrue(error.message!!.contains("2000"))
        }

        @Test
        fun `entries that normalise to the same path are refused`() = runTest {
            val error = assertFailsWith<ObjectStorageException> {
                SkillPackages.unpack(
                    zip("assets//logo.png" to "first", "./assets/logo.png" to "second"),
                    temp.resolve("unpacked"),
                    cap
                )
            }

            assertTrue(error.message!!.contains("Duplicate"))
            assertEquals("first", temp.resolve("unpacked/assets/logo.png").readText())
        }
    }
}
