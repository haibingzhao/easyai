package com.easy.easyai.web.controller

import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.web.model.CreateProjectRequest
import com.easy.easyai.web.model.ProjectResponse
import com.easy.easyai.web.model.UpdateProjectRequest
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.FileStorageService
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.*

/**
 * REST controller for project management.
 *
 * Endpoints:
 * - POST /api/projects - Create a new project
 * - GET /api/projects - List all projects
 * - GET /api/projects/{id} - Get a single project
 * - PUT /api/projects/{id} - Update a project
 * - DELETE /api/projects/{id} - Delete a project
 */
@RestController
@RequestMapping("/api/projects")
class ProjectController(
    private val projectStore: AsyncProjectStore,
    private val sessionStore: AsyncSessionStore,
    @param:Autowired(required = false)
    private val fileStorageService: FileStorageService? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Create a new project.
     * Validates that the path is not already registered.
     */
    @PostMapping(value = ["", "/"])
    fun createProject(
        @RequestBody request: CreateProjectRequest
    ): Mono<ResponseEntity<Any>> {
        return mono {
            // Manual validation
            if (request.name.isBlank()) {
                return@mono ResponseEntity.badRequest()
                    .body(mapOf("error" to "Project name is required"))
            }
            if (request.path.isBlank()) {
                return@mono ResponseEntity.badRequest()
                    .body(mapOf("error" to "Project path is required"))
            }

            // Normalize path
            val normalizedPath = request.path.trimEnd('/')

            // Check if path already exists
            val userId = getCurrentUserId()
            val existing = projectStore.findByPath(normalizedPath, userId)
            if (existing != null) {
                logger.warn("Path already registered: {}", normalizedPath)
                return@mono ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(mapOf("error" to "Path already registered: $normalizedPath"))
            }

            val project = ProjectInfo(
                id = UUID.randomUUID().toString(),
                name = request.name.trim(),
                path = normalizedPath,
                description = request.description?.trim()?.ifBlank { null }
            )
            projectStore.save(project, userId)

            ResponseEntity.status(HttpStatus.CREATED).body(toProjectResponse(project))
        }
    }

    /**
     * List projects with optional filtering.
     * @param limit Maximum number of recent projects to return (null = all)
     * @param search Filter by name or path (case-insensitive)
     */
    @GetMapping
    fun listProjects(
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) search: String?
    ): Flux<ProjectResponse> {
        // Note: userId needs to be extracted in a reactive way for Flux
        // Using reactor context via mono wrapper
        return mono { getCurrentUserId() }
            .flatMapMany { userId ->
                projectStore.findAll(limit, search, userId)
                    .asFlux()
                    .map { toProjectResponse(it) }
            }
    }

    /**
     * List subdirectories under a given path.
     * If path is empty or not provided, defaults to the system user home directory.
     * Hidden directories (starting with '.') are filtered out.
     * On macOS, detects TCC permission denial (e.g. ~/Downloads without "Files and Folders" access)
     * and returns `permissionDenied = true` so the frontend can guide the user to authorize.
     */
    @GetMapping("/list-directories")
    fun listDirectories(
        @RequestParam(required = false) path: String?
    ): Mono<Map<String, Any>> {
        return mono {
            val dirPath = path?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home")
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) {
                mapOf("currentPath" to dirPath, "directories" to emptyList<String>())
            } else {
                val files = dir.listFiles()
                val dirs = files
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.map { it.absolutePath }
                    ?.sorted()
                    ?: emptyList()
                val result = mutableMapOf<String, Any>(
                    "currentPath" to dir.absolutePath,
                    "directories" to dirs
                )
                // TCC detection: owner has read+execute permission but listing returned nothing
                if (files.isNullOrEmpty() && isMacOs && ownerHasReadPermission(dir)) {
                    result["permissionDenied"] = true
                }
                result
            }
        }
    }

    private val isMacOs: Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    /**
     * Check if the directory owner has read+execute permission according to POSIX attributes.
     * If POSIX says readable but listFiles() returns empty, the denial comes from macOS TCC.
     */
    private fun ownerHasReadPermission(dir: File): Boolean = try {
        val perms = Files.getPosixFilePermissions(dir.toPath())
        PosixFilePermission.OWNER_READ in perms && PosixFilePermission.OWNER_EXECUTE in perms
    } catch (_: Exception) {
        false
    }

    /**
     * Get a single project by ID.
     */
    @GetMapping("/{id}")
    fun getProject(@PathVariable id: String): Mono<ResponseEntity<ProjectResponse>> {
        return mono {
            val userId = getCurrentUserId()
            val project = projectStore.findById(id, userId)
            if (project != null) {
                ResponseEntity.ok(toProjectResponse(project))
            } else {
                ResponseEntity.notFound().build()
            }
        }
    }

    /**
     * Delete a project by ID.
     * Cascade deletes all sessions and messages belonging to the project in a single transaction.
     */
    @DeleteMapping("/{id}")
    fun deleteProject(@PathVariable id: String): Mono<ResponseEntity<Void>> {
        return mono {
            val userId = getCurrentUserId()
            // Verify ownership FIRST before any destructive operations
            val project = projectStore.findById(id, userId)
            if (project == null) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $id")
            }
            // Now safe to cascade delete
            // Clean up stored images for all sessions before deletion
            if (fileStorageService != null) {
                val sessionIds = sessionStore.findIdsByProjectId(id, userId)
                for (sid in sessionIds) {
                    fileStorageService.cleanupSession(sid)
                }
            }
            val deletedSessions = sessionStore.deleteByProjectId(id, userId)
            if (deletedSessions > 0) {
                logger.info("Cascade deleted {} sessions for project: {}", deletedSessions, id)
            }
            projectStore.delete(id, userId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    private fun toProjectResponse(project: ProjectInfo): ProjectResponse {
        return ProjectResponse(
            id = project.id,
            name = project.name,
            path = project.path,
            description = project.description,
            memoryAutoGeneration = project.memoryAutoGeneration,
            createdAt = project.createdAt.toEpochMilli(),
            updatedAt = project.updatedAt.toEpochMilli()
        )
    }

    /**
     * Update a project by ID.
     * Only provided fields are updated; omitted fields retain their current values.
     */
    @PutMapping("/{id}")
    fun updateProject(
        @PathVariable id: String,
        @RequestBody request: UpdateProjectRequest
    ): Mono<ResponseEntity<ProjectResponse>> {
        return mono {
            val userId = getCurrentUserId()
            val existing = projectStore.findById(id, userId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $id")

            // Validate name if provided
            request.name?.let {
                if (it.isBlank()) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name cannot be blank")
                }
            }

            val updated = existing.copy(
                name = request.name?.trim() ?: existing.name,
                description = request.description?.let { it.trim().ifBlank { null } } ?: existing.description,
                memoryAutoGeneration = request.memoryAutoGeneration ?: existing.memoryAutoGeneration,
                updatedAt = java.time.Instant.now()
            )
            projectStore.save(updated, userId)
            ResponseEntity.ok(toProjectResponse(updated))
        }
    }
}