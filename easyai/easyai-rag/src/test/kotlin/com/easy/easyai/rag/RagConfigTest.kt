package com.easy.easyai.rag

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests [RagConfig] file handling: editing `rag.json` must take effect without a restart,
 * while repeated loads of an unchanged file stop hitting the disk and re-parsing.
 */
class RagConfigTest {

    @Test
    fun `missing config file yields the default config`(@TempDir dir: Path) = runTest {
        val config = RagConfig.load(dir.resolve("rag.json"))

        assertTrue(config.enabled)
        assertEquals("http://localhost:8020", config.baseUrl)
    }

    @Test
    fun `config is reloaded when the file changes`(@TempDir dir: Path) = runTest {
        val file = dir.resolve("rag.json")

        RagConfig.save(RagConfig(baseUrl = "http://rag.internal:9000"), file)
        assertEquals("http://rag.internal:9000", RagConfig.load(file).baseUrl)

        // An edit that bypasses save() (hand-editing the file) is detected by modification time
        Files.writeString(file, """{"enabled":true,"baseUrl":"http://hand-edited:1234"}""")
        file.toFile().setLastModified(file.toFile().lastModified() + 5_000)
        assertEquals("http://hand-edited:1234", RagConfig.load(file).baseUrl)
    }

    @Test
    fun `repeated loads of an unchanged file reuse the parsed instance`(@TempDir dir: Path) = runTest {
        val file = dir.resolve("rag.json")
        RagConfig.save(RagConfig(topK = 9), file)

        val first = RagConfig.load(file)
        val second = RagConfig.load(file)

        assertSame(first, second)
        assertEquals(9, second.topK)
    }

    @Test
    fun `unparsable content degrades to the default config`(@TempDir dir: Path) = runTest {
        val file = dir.resolve("rag.json")
        Files.writeString(file, "this is not json")

        assertEquals(RagConfig(), RagConfig.load(file))
    }
}
