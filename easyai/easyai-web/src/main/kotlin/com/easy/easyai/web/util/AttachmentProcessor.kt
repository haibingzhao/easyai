package com.easy.easyai.web.util

import com.easy.easyai.core.model.ContentBlock
import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.web.model.ChatAttachment
import com.easy.easyai.web.service.FileStorageService
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Exception thrown when attachment validation fails.
 * Callers decide how to handle the error (SSE error flow, HTTP 400, etc.).
 */
class AttachmentValidationException(message: String) : IllegalArgumentException(message)

/**
 * Shared utility for validating and decoding image attachments.
 * Extracted from ChatStreamService to be reused by both chatFlow and addQueuedMessage.
 */
object AttachmentProcessor {
    private val logger = LoggerFactory.getLogger(javaClass)

    const val MAX_IMAGE_BASE64_BYTES = 8 * 1024 * 1024
    const val MAX_IMAGE_DECODED_BYTES = 6 * 1024 * 1024
    /** Maximum file size for inline @ file references (10 MB) */
    private const val MAX_INLINE_FILE_BYTES = 10L * 1024 * 1024

    val SUPPORTED_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    val SUPPORTED_TEXT_MIMES = setOf(
        "text/plain", "text/markdown", "text/csv", "text/html", "text/css",
        "text/xml", "text/yaml", "application/json", "application/xml",
        "application/yaml", "application/x-yaml"
    )

    val SUPPORTED_TEXT_EXTENSIONS = setOf(
        ".txt", ".md", ".json", ".xml", ".yml", ".yaml", ".csv",
        ".html", ".css", ".js", ".ts", ".jsx", ".tsx", ".py", ".java",
        ".kt", ".go", ".rs", ".rb", ".sh", ".sql", ".toml", ".ini", ".cfg"
    )

    private fun isSupportedMimeType(mimeType: String, fileName: String): Boolean {
        if (mimeType.startsWith("image/")) return true
        if (mimeType in SUPPORTED_TEXT_MIMES) return true
        if (mimeType.startsWith("text/")) return true
        val ext = fileName.lastIndexOf('.').let { if (it >= 0) fileName.substring(it).lowercase() else "" }
        return ext in SUPPORTED_TEXT_EXTENSIONS
    }

    /**
     * Validate and decode image attachments (legacy base64 path).
     * @return Map of ChatAttachment to decoded ByteArray for successfully validated images.
     * @throws AttachmentValidationException when validation fails (size limit, invalid base64).
     */
    fun decodeImageAttachments(attachments: List<ChatAttachment>?): Map<ChatAttachment, ByteArray> {
        val imageAttachments = attachments?.filter { it.mimeType in SUPPORTED_IMAGE_MIMES }.orEmpty()
        if (imageAttachments.isEmpty()) return emptyMap()

        val decodedImages = mutableMapOf<ChatAttachment, ByteArray>()
        for (img in imageAttachments) {
            if (img.data == null) continue
            if (img.data.length > MAX_IMAGE_BASE64_BYTES) {
                throw AttachmentValidationException("Image '${img.name}' exceeds the 8 MB size limit")
            }
            try {
                val bytes = Base64.getDecoder().decode(img.data)
                if (bytes.size > MAX_IMAGE_DECODED_BYTES) {
                    throw AttachmentValidationException("Image '${img.name}' exceeds the 6 MB decoded size limit")
                }
                decodedImages[img] = bytes
            } catch (e: IllegalArgumentException) {
                if (e is AttachmentValidationException) throw e
                throw AttachmentValidationException("Image '${img.name}' contains invalid base64 data")
            }
        }
        return decodedImages
    }

    /**
     * Process attachments into [ContentBlock]s using the new file-reference approach.
     *
     * - Attachments with `filePath` (local files) → [FileRefContent] directly.
     * - Attachments with `data` (clipboard images) → saved to disk via [FileStorageService] → [FileRefContent].
     *
     * @return List of [FileRefContent] blocks.
     * @throws AttachmentValidationException when validation fails.
     */
    fun processAttachments(
        attachments: List<ChatAttachment>?,
        fileStorageService: FileStorageService,
        sessionId: String
    ): List<ContentBlock> {
        if (attachments.isNullOrEmpty()) return emptyList()

        val blocks = mutableListOf<ContentBlock>()
        for (att in attachments) {
            if (att.filePath != null) {
                // Validate file type is supported
                if (!isSupportedMimeType(att.mimeType, att.name)) {
                    throw AttachmentValidationException(
                        "Unsupported file type '${att.mimeType}' for '${att.name}'. Only images and text files are supported."
                    )
                }
                // Local file reference — use path directly
                blocks.add(FileRefContent(
                    filePath = att.filePath,
                    name = att.name,
                    mimeType = att.mimeType
                ))
            } else if (att.data != null) {
                if (att.mimeType !in SUPPORTED_IMAGE_MIMES) {
                    throw AttachmentValidationException(
                        "Unsupported base64 attachment type '${att.mimeType}' for '${att.name}'. Only images are supported for inline data."
                    )
                }
                // Clipboard image — save to disk first
                if (att.data.length > MAX_IMAGE_BASE64_BYTES) {
                    throw AttachmentValidationException("Image '${att.name}' exceeds the 8 MB size limit")
                }
                val bytes = try {
                    Base64.getDecoder().decode(att.data)
                } catch (_: IllegalArgumentException) {
                    throw AttachmentValidationException("Image '${att.name}' contains invalid base64 data")
                }
                if (bytes.size > MAX_IMAGE_DECODED_BYTES) {
                    throw AttachmentValidationException("Image '${att.name}' exceeds the 6 MB decoded size limit")
                }
                val extension = att.mimeType.substringAfterLast('/', "png")
                val filePath = fileStorageService.saveImage(sessionId, bytes, extension)
                blocks.add(FileRefContent(
                    filePath = filePath,
                    name = att.name,
                    mimeType = att.mimeType
                ))
            }
        }
        return blocks
    }

    /**
     * Unicode character used to wrap file references (matches frontend FILE_REF_CHAR).
     */
    private const val FILE_REF_CHAR = '\u201b'

    /**
     * Emoji prefix for folder references (matches frontend FOLDER_PREFIX).
     */
    private const val FOLDER_PREFIX = "\uD83D\uDCC1" // 📁

    /**
     * Regex to match inline file/folder references: ‛[name](path)‛
     * Group 1: name (may start with 📁 for folders)
     * Group 2: absolute file path
     */
    private val INLINE_REF_REGEX = Regex("""$FILE_REF_CHAR\[([^\]]+)]\(([\s\S]+?)\)$FILE_REF_CHAR""")

    /** Result of extracting inline file references from message text. */
    data class InlineRefResult(
        /** Cleaned message text with all inline refs removed. */
        val cleanedText: String,
        /** FileRefContent blocks for each valid file reference found. */
        val fileRefBlocks: List<FileRefContent>
    )

    /**
     * Extract inline @ file references from message text.
     *
     * The frontend encodes @ file selections as `‛[name](path)‛` in the message text.
     * This method parses those references, validates them, and returns:
     * - The cleaned text (refs stripped out)
     * - [FileRefContent] blocks for each valid file reference
     *
     * Folder references (prefixed with 📁) are stripped from text but not converted to
     * [FileRefContent] since they don't map to a single file.
     *
     * @param text the raw message text from the frontend
     * @param projectDir optional project directory for path validation; if null, no validation
     * @return [InlineRefResult] with cleaned text and file ref blocks
     */
    fun extractInlineFileRefs(text: String, projectDir: Path? = null): InlineRefResult {
        if (!text.contains(FILE_REF_CHAR)) return InlineRefResult(text, emptyList())

        val blocks = mutableListOf<FileRefContent>()
        val cleanedText = INLINE_REF_REGEX.replace(text) { match ->
            val rawName = match.groupValues[1]
            val filePath = match.groupValues[2]

            // Folder refs: strip from text but don't create a FileRefContent
            if (rawName.startsWith(FOLDER_PREFIX)) {
                return@replace ""
            }

            val name = rawName

            // Validate path
            val resolvedPath = try {
                Path.of(filePath).toAbsolutePath().normalize()
            } catch (_: Exception) {
                logger.warn("Inline file ref: invalid path, skipping: {}", filePath)
                return@replace ""
            }

            // Security: path must be within project directory (required — fail closed)
            if (projectDir == null) {
                logger.warn("Inline file ref: no project directory configured, skipping: {}", filePath)
                return@replace ""
            }
            if (!resolvedPath.startsWith(projectDir)) {
                logger.warn("Inline file ref: path outside project directory, skipping: {}", filePath)
                return@replace ""
            }

            // File must exist and be a regular file
            if (!Files.isRegularFile(resolvedPath)) {
                logger.warn("Inline file ref: file not found, skipping: {}", filePath)
                return@replace ""
            }

            // Size check
            val fileSize = try { Files.size(resolvedPath) } catch (_: Exception) { Long.MAX_VALUE }
            if (fileSize > MAX_INLINE_FILE_BYTES) {
                logger.warn("Inline file ref: file too large ({} bytes), skipping: {}", fileSize, filePath)
                return@replace ""
            }

            // Determine MIME type
            val mimeType = resolveMimeType(resolvedPath, name)

            blocks.add(FileRefContent(
                filePath = resolvedPath.toString(),
                name = name,
                mimeType = mimeType,
                source = "inline"
            ))

            "" // Remove the ref from text
        }

        return InlineRefResult(cleanedText, blocks)
    }

    /**
     * Build content blocks from message text, extracting inline @ file references.
     * Convenience method that combines text cleaning and block creation.
     *
     * @param messageText the raw message text from the frontend
     * @param projectDir optional project directory for path validation
     * @return list of [ContentBlock]s: one [TextContent] (if non-blank) + [FileRefContent]s
     */
    fun buildContentBlocks(messageText: String, projectDir: Path? = null): List<ContentBlock> {
        val result = extractInlineFileRefs(messageText, projectDir)
        val blocks = mutableListOf<ContentBlock>()
        if (result.cleanedText.isNotBlank()) {
            blocks.add(TextContent(result.cleanedText))
        }
        blocks.addAll(result.fileRefBlocks)
        return blocks
    }

    /**
     * Resolve MIME type for a file, using [Files.probeContentType] with fallback
     * to extension-based detection.
     */
    private fun resolveMimeType(path: Path, name: String): String {
        val probed = try { Files.probeContentType(path) } catch (_: Exception) { null }
        if (probed != null) return probed

        val ext = name.lastIndexOf('.').let { if (it >= 0) name.substring(it).lowercase() else "" }
        return when (ext) {
            ".png" -> "image/png"
            ".jpg", ".jpeg" -> "image/jpeg"
            ".gif" -> "image/gif"
            ".webp" -> "image/webp"
            ".md" -> "text/markdown"
            ".json" -> "application/json"
            ".xml" -> "application/xml"
            ".yml", ".yaml" -> "application/yaml"
            ".csv" -> "text/csv"
            ".html" -> "text/html"
            ".css" -> "text/css"
            ".js" -> "text/javascript"
            ".ts", ".tsx" -> "text/typescript"
            ".py" -> "text/x-python"
            ".java" -> "text/x-java-source"
            ".kt" -> "text/x-kotlin"
            ".go" -> "text/x-go"
            ".rs" -> "text/x-rust"
            ".rb" -> "text/x-ruby"
            ".sh" -> "text/x-shellscript"
            ".sql" -> "text/x-sql"
            else -> "text/plain"
        }
    }
}
