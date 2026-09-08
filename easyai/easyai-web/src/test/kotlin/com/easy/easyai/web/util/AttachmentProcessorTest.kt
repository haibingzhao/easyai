package com.easy.easyai.web.util

import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.FolderRefContent
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.web.model.ChatAttachment
import com.easy.easyai.web.service.FileStorageService
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [AttachmentProcessor]: inline 📁 folder references must survive as
 * [FolderRefContent] blocks (instead of being silently dropped), and directory
 * attachments must round-trip back into [FolderRefContent].
 */
class AttachmentProcessorTest {

    private val fileStorageService = mockk<FileStorageService>(relaxed = true)

    /** ‛[name](path)‛ encoding matching the frontend buildFolderRef. */
    private fun folderRef(name: String, path: Path): String = "‛[\uD83D\uDCC1$name]($path)‛"

    /** ‛[name](path)‛ encoding matching the frontend buildFileRef. */
    private fun fileRef(name: String, path: Path): String = "‛[$name]($path)‛"

    @Nested
    inner class `extractInlineFileRefs` {

        @Test
        fun `converts folder refs to FolderRefContent blocks`(@TempDir projectDir: Path) {
            val dir = Files.createDirectories(projectDir.resolve("summary"))
            val text = "Save results to ${folderRef("summary", dir)} folder"

            val result = AttachmentProcessor.extractInlineFileRefs(text, projectDir)

            assertEquals("Save results to  folder", result.cleanedText)
            assertTrue(result.fileRefBlocks.isEmpty())
            val folder = result.folderRefBlocks.single()
            assertEquals("summary", folder.name)
            assertEquals(dir.toString(), folder.filePath)
        }

        @Test
        fun `strips folder refs outside project directory without blocks`(
            @TempDir projectDir: Path,
            @TempDir otherDir: Path
        ) {
            val dir = Files.createDirectories(otherDir.resolve("secret"))
            val text = "ref ${folderRef("secret", dir)} end"

            val result = AttachmentProcessor.extractInlineFileRefs(text, projectDir)

            assertTrue(result.folderRefBlocks.isEmpty())
            assertEquals("ref  end", result.cleanedText)
        }

        @Test
        fun `strips folder refs when no project directory is configured`(@TempDir tempDir: Path) {
            val dir = Files.createDirectories(tempDir.resolve("summary"))
            val text = "ref ${folderRef("summary", dir)} end"

            val result = AttachmentProcessor.extractInlineFileRefs(text, null)

            assertTrue(result.folderRefBlocks.isEmpty())
            assertEquals("ref  end", result.cleanedText)
        }

        @Test
        fun `records displayOffset of folder ref in cleaned text`(@TempDir projectDir: Path) {
            val dir = Files.createDirectories(projectDir.resolve("summary"))
            val text = "save to ${folderRef("summary", dir)} folder please"

            val result = AttachmentProcessor.extractInlineFileRefs(text, projectDir)

            assertEquals("save to  folder please", result.cleanedText)
            // "save to " is 8 chars — the chip sat at cleaned-text offset 8
            assertEquals(8, result.folderRefBlocks.single().displayOffset)
        }

        @Test
        fun `displayOffset accounts for earlier stripped file refs`(@TempDir projectDir: Path) {
            val file = projectDir.resolve("a.txt")
            Files.writeString(file, "x")
            val dir = Files.createDirectories(projectDir.resolve("d"))
            val text = "${fileRef("a.txt", file)} then ${folderRef("d", dir)} end"

            val result = AttachmentProcessor.extractInlineFileRefs(text, projectDir)

            assertEquals(" then  end", result.cleanedText)
            // " then " is 6 chars once the leading file ref is stripped
            assertEquals(6, result.folderRefBlocks.single().displayOffset)
        }

        @Test
        fun `still converts file refs to FileRefContent blocks`(@TempDir projectDir: Path) {
            val file = projectDir.resolve("note.txt")
            Files.writeString(file, "hello")
            val text = "read ${fileRef("note.txt", file)} please"

            val result = AttachmentProcessor.extractInlineFileRefs(text, projectDir)

            assertTrue(result.folderRefBlocks.isEmpty())
            val ref = result.fileRefBlocks.single()
            assertEquals("note.txt", ref.name)
            assertEquals("inline", ref.source)
            assertEquals(5, ref.displayOffset) // "read " is 5 chars in cleaned "read  please"
            assertEquals("read  please", result.cleanedText)
        }
    }

    @Nested
    inner class `buildContentBlocks` {

        @Test
        fun `includes folder ref blocks alongside text`(@TempDir projectDir: Path) {
            val dir = Files.createDirectories(projectDir.resolve("out"))

            val blocks = AttachmentProcessor.buildContentBlocks("put it in ${folderRef("out", dir)}", projectDir)

            assertEquals(2, blocks.size)
            assertIs<TextContent>(blocks[0])
            assertIs<FolderRefContent>(blocks[1])
        }
    }

    @Nested
    inner class `processAttachments` {

        @Test
        fun `directory attachment becomes FolderRefContent`(@TempDir tempDir: Path) {
            val dir = Files.createDirectories(tempDir.resolve("docs"))

            val blocks = AttachmentProcessor.processAttachments(
                listOf(ChatAttachment(name = "docs", mimeType = "application/x-directory", filePath = dir.toString())),
                fileStorageService,
                "session-1",
                anchorOffset = 42,
                projectDir = tempDir
            )

            val folder = blocks.single() as FolderRefContent
            assertEquals("docs", folder.name)
            assertEquals(dir.toString(), folder.filePath)
            assertEquals(42, folder.displayOffset)
        }

        @Test
        fun `rejects directory attachment outside project directory`(
            @TempDir projectDir: Path,
            @TempDir otherDir: Path
        ) {
            val dir = Files.createDirectories(otherDir.resolve("secret"))

            val e = assertFailsWith<AttachmentValidationException> {
                AttachmentProcessor.processAttachments(
                    listOf(ChatAttachment(name = "secret", mimeType = "application/x-directory", filePath = dir.toString())),
                    fileStorageService,
                    "session-1",
                    anchorOffset = 0,
                    projectDir = projectDir
                )
            }
            assertTrue(e.message!!.contains("inside the project directory"))
        }

        @Test
        fun `rejects directory attachment when no project directory is configured`(@TempDir tempDir: Path) {
            val dir = Files.createDirectories(tempDir.resolve("docs"))

            assertFailsWith<AttachmentValidationException> {
                AttachmentProcessor.processAttachments(
                    listOf(ChatAttachment(name = "docs", mimeType = "application/x-directory", filePath = dir.toString())),
                    fileStorageService,
                    "session-1",
                    anchorOffset = 0,
                    projectDir = null
                )
            }
        }

        @Test
        fun `file attachment still becomes FileRefContent`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("a.txt")
            Files.writeString(file, "x")

            val blocks = AttachmentProcessor.processAttachments(
                listOf(ChatAttachment(name = "a.txt", mimeType = "text/plain", filePath = file.toString())),
                fileStorageService,
                "session-1",
                anchorOffset = 7,
                projectDir = tempDir
            )

            val ref = blocks.single() as FileRefContent
            assertEquals(7, ref.displayOffset)
        }
    }
}
