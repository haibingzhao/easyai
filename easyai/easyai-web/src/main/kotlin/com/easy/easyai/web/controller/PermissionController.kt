package com.easy.easyai.web.controller

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.common.util.ProcessExecutor
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillPaths
import com.easy.easyai.web.model.*
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * REST controller for managing project-level permission rules and settings.
 *
 * Endpoints:
 * - GET /api/permission/rules/{projectId} - Get all rules for a project
 * - PUT /api/permission/rules/{projectId} - Save all rules (full replacement)
 * - POST /api/permission/rules/{projectId} - Add a single rule
 * - DELETE /api/permission/rules/{projectId} - Delete a specific rule
 * - GET /api/permission/tools/{projectId} - Get tools with their permission status
 * - GET /api/permission/settings/{projectId} - Get effective permission settings
 * - PATCH /api/permission/settings/{projectId} - Update a single setting
 * - GET /api/permission/project-structure/{projectId} - Get project directory tree
 * - POST /api/permission/create-directory - Create a directory inside the project tree
 * - GET /api/permission/server-platform - Get the backend host platform
 * - POST /api/permission/reveal-path - Reveal a project path in the macOS Finder
 */
@RestController
@RequestMapping("/api/permission")
class PermissionController(
    private val permissionService: PermissionService,
    private val ruleStore: PermissionRuleStore,
    private val toolRegistry: ToolRegistry,
    private val projectStore: AsyncProjectStore? = null,
    @param:Autowired(required = false) private val skillConfig: SkillConfig? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val IGNORE_DIRS = setOf(
            ".git", "node_modules", "target", ".idea", ".gradle",
            "build", "dist", ".next", "__pycache__", ".venv", "venv"
        )
        private const val MAX_SEARCH_RESULTS = 50
        private const val SEARCH_TIMEOUT_MS = 5000L
        private const val MAX_MEDIA_BYTES = 500L * 1024 * 1024

        private fun detectPlatform(): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> "macos"
                os.contains("win") -> "windows"
                os.contains("linux") -> "linux"
                else -> "unknown"
            }
        }

        private fun isValidDirectoryName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                name.none { it == '/' || it == '\\' || it == '\u0000' }
        // Extension → MIME whitelist. Files.probeContentType is unreliable across platforms
        // (returns null for mp4/mkv on many Linux hosts), so the served type is derived here.
        private val MEDIA_MIME_BY_EXT: Map<String, String> = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
            "svg" to "image/svg+xml", "avif" to "image/avif", "ico" to "image/x-icon",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
            "oga" to "audio/ogg", "aac" to "audio/aac", "m4a" to "audio/mp4",
            "flac" to "audio/flac",
            "mp4" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
            "m4v" to "video/mp4", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska"
        )
    }

    /**
     * Get all permission rules for a project.
     */
    @GetMapping("/rules/{projectId}")
    fun getRules(@PathVariable projectId: String): Mono<List<PermissionRuleDto>> {
        return mono {
            permissionService.getRules(projectId).map { it.toDto() }
        }
    }

    /**
     * Save all permission rules for a project (full replacement).
     */
    @PutMapping("/rules/{projectId}")
    fun saveRules(
        @PathVariable projectId: String,
        @RequestBody request: SaveRulesRequest
    ): Mono<Map<String, String>> {
        return mono {
            val rules = request.rules.map { it.toDomain() }
            ruleStore.saveRules(projectId, rules)
            mapOf("status" to "saved", "count" to rules.size.toString())
        }
    }

    /**
     * Add a single permission rule to a project.
     */
    @PostMapping("/rules/{projectId}")
    fun addRule(
        @PathVariable projectId: String,
        @RequestBody request: AddRuleRequest
    ): Mono<Map<String, String>> {
        return mono {
            val rule = request.toDomain()
            ruleStore.addRule(projectId, rule)
            mapOf("status" to "added")
        }
    }

    /**
     * Delete a specific permission rule from a project.
     */
    @DeleteMapping("/rules/{projectId}")
    fun deleteRule(
        @PathVariable projectId: String,
        @RequestParam permission: String,
        @RequestParam pattern: String
    ): Mono<Map<String, String>> {
        return mono {
            ruleStore.deleteRule(projectId, permission, pattern)
            mapOf("status" to "deleted")
        }
    }

    /**
     * Get all tools with their current permission rules for a project.
     * Aggregates tool metadata from ToolRegistry with permission rules.
     */
    @GetMapping("/tools/{projectId}")
    fun getToolPermissions(@PathVariable projectId: String): Mono<List<ToolPermissionInfoDto>> {
        return mono {
            val rules = permissionService.getRules(projectId)
            val tools = toolRegistry.getAllTools()

            tools.map { tool ->
                ToolPermissionInfoDto(
                    name = tool.name,
                    description = tool.description,
                    category = tool.permissionCategory,
                    rules = emptyList()
                )
            }
        }
    }

    /**
     * Get effective permission settings for a project (defaults + user rules).
     */
    @GetMapping("/settings/{projectId}")
    fun getSettings(@PathVariable projectId: String): Mono<PermissionSettingsDto> {
        return mono {
            val settings = permissionService.getEffectiveSettings(projectId)
            val projectPath = resolveProjectPath(projectId)
            PermissionSettingsDto(
                projectPath = projectPath ?: "",
                readFileProject = settings.readFileProject,
                readFileAll = settings.readFileAll,
                writeFileProject = settings.writeFileProject,
                writeFileAll = settings.writeFileAll,
                executeSafeCommands = settings.executeSafeCommands,
                executeAllCommands = settings.executeAllCommands,
                useBrowser = settings.useBrowser,
                useMcp = settings.useMcp,
                readOtherPaths = settings.readOtherPaths,
                writeOtherPaths = settings.writeOtherPaths,
                otherCommands = settings.otherCommands,
                aiCheckModelId = settings.aiCheckModelId
            )
        }
    }

    /**
     * Update a single permission setting (checkbox toggle).
     */
    @PatchMapping("/settings/{projectId}")
    fun updateSetting(
        @PathVariable projectId: String,
        @RequestBody request: UpdateSettingRequest
    ): Mono<Map<String, String>> {
        return mono {
            permissionService.updateSetting(projectId, request.key, request.value)
            mapOf("status" to "updated")
        }
    }

    /**
     * Get project directory structure for the file browser dropdown.
     * Scans up to 3 levels deep, excluding common ignore directories.
     */
    @GetMapping("/project-structure/{projectId}")
    fun getProjectStructure(@PathVariable projectId: String): Mono<List<FileNodeDto>> {
        return mono {
            val projectPath = resolveProjectPath(projectId)
            if (projectPath != null) {
                scanDirectory(Path.of(projectPath), Path.of(projectPath), 0, 3)
            } else {
                emptyList()
            }
        }
    }

    /**
     * Search files/folders by name within a project directory (recursive, real-time).
     * Returns a flat list of matching entries with absolute paths.
     * Results are capped at [MAX_SEARCH_RESULTS] and the walk is time-bounded.
     */
    @GetMapping("/search-files")
    fun searchFiles(
        @RequestParam projectId: String,
        @RequestParam query: String
    ): Mono<List<FileNodeDto>> {
        return mono {
            if (query.isBlank()) return@mono emptyList()
            val projectPath = resolveProjectPath(projectId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectId")
            val root = Path.of(projectPath).toAbsolutePath().normalize()
            if (!Files.isDirectory(root)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project path is not a valid directory: $projectId")
            }
            searchFilesRecursive(root, query, MAX_SEARCH_RESULTS)
        }
    }

    /**
     * Browse a directory on the server filesystem.
     * Returns one level of contents (files and subdirectories) with absolute paths.
     * When projectId is provided, path must be within the project directory.
     */
    @GetMapping("/browse-directory")
    fun browseDirectory(
        @RequestParam path: String,
        @RequestParam(required = false) projectId: String?
    ): Mono<List<FileNodeDto>> {
        return mono {
            val dirPath = Path.of(path).toAbsolutePath().normalize()
            if (!projectId.isNullOrBlank()) {
                val projectPath = resolveProjectPath(projectId)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectId")
                val projectDir = Path.of(projectPath).toAbsolutePath().normalize()
                require(dirPath.startsWith(projectDir)) {
                    "Access denied: path is outside project directory"
                }
            }
            require(Files.isDirectory(dirPath)) { "Not a directory: $path" }
            listDirectory(dirPath)
        }
    }

    /**
     * Create a new directory inside the project directory.
     * The parent path must be within the project directory and the name must be
     * a single path segment that does not exist yet.
     */
    @PostMapping("/create-directory")
    fun createDirectory(@RequestBody request: CreateDirectoryRequest): Mono<Map<String, String>> {
        return mono {
            val projectPath = resolveProjectPath(request.projectId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: ${request.projectId}")
            val projectDir = Path.of(projectPath).toAbsolutePath().normalize()
            val parentDir = Path.of(request.path).toAbsolutePath().normalize()
            if (!parentDir.startsWith(projectDir)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: path is outside project directory")
            }
            if (!Files.isDirectory(parentDir)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a directory: ${request.path}")
            }
            val name = request.name.trim()
            if (!isValidDirectoryName(name)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid directory name: ${request.name}")
            }
            val target = parentDir.resolve(name)
            if (Files.exists(target)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Already exists: $name")
            }
            Files.createDirectory(target)
            logger.info("Created directory: {}", target)
            mapOf("status" to "created", "path" to target.toString())
        }
    }

    /**
     * Report the backend host platform so the frontend can gate OS-specific
     * file-tree actions (e.g. "Reveal in Finder" only works on a macOS host).
     */
    @GetMapping("/server-platform")
    fun serverPlatform(): Mono<Map<String, String>> {
        return mono { mapOf("platform" to detectPlatform()) }
    }

    /**
     * Reveal a path in the macOS Finder via `open -R`.
     * macOS-only: other platforms answer 501 so the frontend hides the action.
     */
    @PostMapping("/reveal-path")
    fun revealPath(
        @RequestParam path: String,
        @RequestParam projectId: String
    ): Mono<Map<String, String>> {
        return mono {
            if (detectPlatform() != "macos") {
                throw ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Reveal in Finder is only supported on macOS")
            }
            val projectPath = resolveProjectPath(projectId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectId")
            val projectDir = Path.of(projectPath).toAbsolutePath().normalize()
            val target = Path.of(path).toAbsolutePath().normalize()
            if (!target.startsWith(projectDir)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: path is outside project directory")
            }
            if (!Files.exists(target)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Path not found: $path")
            }
            val result = ProcessExecutor.execute(listOf("open", "-R", target.toString()), timeoutSeconds = 5)
            if (result == null || result.exitCode != 0) {
                logger.warn("open -R failed for {}: {}", target, result?.stderr)
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to reveal path in Finder")
            }
            mapOf("status" to "revealed")
        }
    }

    /**
     * Read the content of a file on the server filesystem.
     * Returns file content as text along with MIME type and size.
     * Path must be within the caller's skill owner roots, the specified project directory, or
     * allowed by permission rules. Limited to files ≤ 1MB for safety.
     */
    @GetMapping("/read-file-content")
    fun readFileContent(
        @RequestParam path: String,
        @RequestParam(required = false) projectId: String?
    ): Mono<Map<String, Any>> {
        return mono {
            val filePath = validateReadablePath(path, projectId)
            val fileSize = Files.size(filePath)
            if (fileSize > 1_048_576) {
                throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large: $fileSize bytes (max 1MB)")
            }
            val content = try {
                Files.readString(filePath, StandardCharsets.UTF_8)
            } catch (_: java.nio.charset.MalformedInputException) {
                throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Binary file cannot be displayed as text")
            }
            val mimeType = Files.probeContentType(filePath) ?: "text/plain"
            mapOf("content" to content, "mimeType" to mimeType, "size" to fileSize)
        }
    }

    /**
     * Serve an audio/image/video file from the server filesystem as binary content.
     * Path must be within the specified project directory (or allowed by permission rules).
     * Restricted to extensions in [MEDIA_MIME_BY_EXT] and files ≤ 500MB; Range requests are
     * answered by the reactive resource writer, so playback transfers only requested bytes.
     */
    @GetMapping("/serve-media")
    fun serveMedia(
        @RequestParam path: String,
        @RequestParam(required = false) projectId: String?
    ): Mono<ResponseEntity<FileSystemResource>> {
        return mono {
            val filePath = validateReadablePath(path, projectId)
            val extension = filePath.fileName.toString().substringAfterLast('.', "").lowercase()
            val mimeType = MEDIA_MIME_BY_EXT[extension]
                ?: throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Not a servable media type: $extension")
            val fileSize = Files.size(filePath)
            if (fileSize > MAX_MEDIA_BYTES) {
                throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Media file too large: $fileSize bytes (max 500MB)")
            }
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(FileSystemResource(filePath))
        }
    }

    /**
     * Resolve and validate a file path for reading: must exist as a regular file and be either
     * within a skill owner root the caller may read (their own or the shared `system` layer), or
     * within the authenticated user's project directory, or allowed by permission rules.
     */
    private suspend fun validateReadablePath(path: String, projectId: String?): Path {
        val rawPath = Path.of(path)
        // Skill packages live outside any project directory; serve them straight from the owner root.
        val skillFile = resolveWithinSkillRoots(rawPath)
        if (skillFile != null) {
            if (!Files.isRegularFile(skillFile)) {
                logger.warn("File not found: {}", skillFile)
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: $path")
            }
            return skillFile
        }
        if (projectId.isNullOrBlank()) {
            logger.warn("Access denied: path outside project directory and no project given: {}", rawPath)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: path is outside project directory")
        }
        val projectPath = resolveProjectPath(projectId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectId")
        val projectDir = Path.of(projectPath).toAbsolutePath().normalize()
        // Resolve relative paths against the project directory; absolute paths used as-is
        val filePath = (if (rawPath.isAbsolute) rawPath else projectDir.resolve(rawPath)).normalize()
        if (!filePath.startsWith(projectDir) && !isPathAllowedByRules(projectId, filePath, projectDir)) {
            logger.warn("Access denied: path outside project directory: {} (project: {})", filePath, projectDir)
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: path is outside project directory")
        }
        if (!Files.isRegularFile(filePath)) {
            logger.warn("File not found: {}", filePath)
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: $path")
        }
        return filePath
    }

    /**
     * Resolve [path] to a canonical path inside a skill owner root the caller may read: their own
     * root or the shared `system` root — never another user's private skills. Returns null when the
     * skill system is disabled or the path is under neither root, so the caller falls back to
     * project-directory validation. Owner roots and containment come from [SkillPaths], the single
     * source of truth for skill directory layout.
     */
    private suspend fun resolveWithinSkillRoots(path: Path): Path? {
        val config = skillConfig ?: return null
        val userId = getCurrentUserId()
        val normalized = path.toAbsolutePath().normalize()
        val readable = SkillPaths.isWithin(SkillPaths.ownerRoot(config, userId), normalized) ||
            SkillPaths.isWithin(SkillPaths.ownerRoot(config, SkillCatalogEntry.DEFAULT_USER_ID), normalized)
        return if (readable) normalized else null
    }

    private suspend fun resolveProjectPath(projectId: String): String? {
        if (projectStore == null) return null
        val userId = getCurrentUserId()
        val project = projectStore.findById(projectId, userId) ?: return null
        return project.path
    }

    /**
     * Check if a path outside the project directory is allowed by the project's permission rules.
     * Reuses the same evaluation logic as the agent loop (file.read/file.write rules).
     */
    private suspend fun isPathAllowedByRules(projectId: String, filePath: Path, projectDir: Path): Boolean {
        val rules = ruleStore.loadRules(projectId)
        val args = mapOf("path" to filePath.toString())
        val readAllowed = permissionService.evaluateFilePermission(rules, projectDir, args, read = true)
        if (readAllowed.action == PermissionAction.ALLOW) return true
        val writeAllowed = permissionService.evaluateFilePermission(rules, projectDir, args, read = false)
        return writeAllowed.action == PermissionAction.ALLOW
    }

    private fun scanDirectory(root: Path, current: Path, depth: Int, maxDepth: Int): List<FileNodeDto> {
        if (depth >= maxDepth || !Files.isDirectory(current)) return emptyList()

        val ignoreDirs = IGNORE_DIRS

        return try {
            val entries = Files.list(current).use { stream ->
                stream.toList()
            }
            entries.sortedWith(
                compareBy<Path> { !Files.isDirectory(it) }.thenBy { it.fileName.toString() }
            ).mapNotNull { path ->
                val name = path.fileName.toString()
                val relativePath = root.relativize(path).toString()
                if (Files.isDirectory(path)) {
                    if (name in ignoreDirs) return@mapNotNull null
                    FileNodeDto(
                        name = name,
                        path = relativePath,
                        type = "directory",
                        children = scanDirectory(root, path, depth + 1, maxDepth)
                    )
                } else {
                    FileNodeDto(
                        name = name,
                        path = relativePath,
                        type = "file"
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * List one level of directory contents with absolute paths.
     * Used by the browse-directory endpoint.
     */
    private fun listDirectory(dir: Path): List<FileNodeDto> {
        val ignoreDirs = IGNORE_DIRS
        return try {
            Files.list(dir).use { stream -> stream.toList() }
                .sortedWith(compareBy<Path> { !Files.isDirectory(it) }.thenBy { it.fileName.toString() })
                .mapNotNull { p ->
                    val name = p.fileName.toString()
                    if (Files.isDirectory(p) && name in ignoreDirs) return@mapNotNull null
                    FileNodeDto(
                        name = name,
                        path = p.toAbsolutePath().toString(),
                        type = if (Files.isDirectory(p)) "directory" else "file"
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Recursively search for files/folders matching [query] (case-insensitive) under [root].
     * Walks up to [maxResults] matches and is time-bounded by [SEARCH_TIMEOUT_MS].
     * Returns a flat list of [FileNodeDto] with absolute paths.
     */
    private fun searchFilesRecursive(root: Path, query: String, maxResults: Int): List<FileNodeDto> {
        val lowerQuery = query.lowercase()
        val results = mutableListOf<FileNodeDto>()
        val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        val ignoreDirs = IGNORE_DIRS

        fun walk(dir: Path) {
            if (results.size >= maxResults) return
            if (System.currentTimeMillis() > deadline) return

            val entries = try {
                Files.list(dir).use { it.toList() }
            } catch (_: Exception) {
                return
            }

            // Sort: directories first, then alphabetically
            val sorted = entries.sortedWith(
                compareBy<Path> { !Files.isDirectory(it) }.thenBy { it.fileName.toString() }
            )

            for (entry in sorted) {
                if (results.size >= maxResults || System.currentTimeMillis() > deadline) return

                val name = entry.fileName.toString()

                // Skip ignored directories entirely
                if (Files.isDirectory(entry) && name in ignoreDirs) continue

                // Check if name matches query
                if (name.lowercase().contains(lowerQuery)) {
                    results.add(
                        FileNodeDto(
                            name = name,
                            path = entry.toAbsolutePath().toString(),
                            type = if (Files.isDirectory(entry)) "directory" else "file"
                        )
                    )
                }

                // Recurse into subdirectories
                if (Files.isDirectory(entry)) {
                    walk(entry)
                }
            }
        }

        walk(root)
        return results
    }
}

/**
 * Convert domain PermissionRule to DTO.
 */
private fun PermissionRule.toDto(): PermissionRuleDto = PermissionRuleDto(
    permission = permission,
    pattern = pattern,
    action = action.name
)

/**
 * Convert DTO to domain PermissionRule.
 */
private fun PermissionRuleDto.toDomain(): PermissionRule = PermissionRule(
    permission = permission,
    pattern = pattern,
    action = PermissionAction.valueOf(action.uppercase())
)

/**
 * Convert AddRuleRequest to domain PermissionRule.
 */
private fun AddRuleRequest.toDomain(): PermissionRule = PermissionRule(
    permission = permission,
    pattern = pattern,
    action = PermissionAction.valueOf(action.uppercase())
)
