package com.easy.easyai.web.controller

import com.easy.easyai.autoconfigure.core.EasyAiProperties
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.*
import com.easy.easyai.web.model.*
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.time.LocalDate

@RestController
@RequestMapping("/api/memories")
class MemoryController(
    @Autowired(required = false) private val memoryStore: MemoryStore?,
    private val properties: EasyAiProperties
) {

    /** Build a minimal AgentContext for MemoryStore calls from HTTP requests. */
    private fun controllerContext(): AgentContext = AgentContext(
        agentId = "memory-controller",
        projectPath = Path.of(properties.workDir).resolve(properties.memory.projectDir)
    )

    @GetMapping
    fun listMemories(
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) type: String?
    ): Mono<List<MemoryEntryDto>> {
        return mono {
            val store = memoryStore ?: return@mono emptyList()
            val ctx = controllerContext()
            val memoryScope = parseScope(scope)
            val memoryType = type?.let { parseType(it) }
            store.list(ctx, memoryScope, memoryType).map { it.toDto(memoryScope) }
        }
    }

    @GetMapping("/{name}")
    fun getMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String
    ): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw IllegalStateException("Memory system is not enabled")
            val ctx = controllerContext()
            val memoryScope = parseScope(scope)
            store.findByName(ctx, name, memoryScope)?.toDto(memoryScope)
                ?: throw IllegalArgumentException("Memory not found: $name")
        }
    }

    @PostMapping
    fun createOrUpdateMemory(@RequestBody request: CreateMemoryRequest): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw IllegalStateException("Memory system is not enabled")
            val ctx = controllerContext()
            // Validate name to prevent path traversal and invalid file names
            require(request.name.isNotBlank()) { "Memory name must not be blank" }
            require(!request.name.contains("/") && !request.name.contains("\\") && !request.name.contains("..")) {
                "Memory name must not contain path separators or '..'"
            }
            val memoryScope = parseScope(request.scope)
            val memoryType = parseType(request.type)
            // Preserve original created date if updating an existing entry
            val existing = store.findByName(ctx, request.name, memoryScope)
            val entry = MemoryEntry(
                name = request.name,
                description = request.description,
                type = memoryType,
                content = request.content,
                path = "${memoryType.dirName}/${request.name}.md",
                keywords = request.keywords,
                created = existing?.created ?: LocalDate.now(),
                updated = LocalDate.now()
            )
            store.write(ctx, entry, memoryScope)
            entry.toDto(memoryScope)
        }
    }

    @DeleteMapping("/{name}")
    fun deleteMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String
    ): Mono<Map<String, Boolean>> {
        return mono {
            val store = memoryStore ?: throw IllegalStateException("Memory system is not enabled")
            val ctx = controllerContext()
            val memoryScope = parseScope(scope)
            val entry = store.findByName(ctx, name, memoryScope)
                ?: throw IllegalArgumentException("Memory not found: $name")
            val deleted = store.delete(ctx, entry.path, memoryScope)
            mapOf("deleted" to deleted)
        }
    }

    @DeleteMapping
    fun deleteAllMemories(
        @RequestParam(defaultValue = "global") scope: String
    ): Mono<Map<String, Int>> {
        return mono {
            val store = memoryStore ?: throw IllegalStateException("Memory system is not enabled")
            val ctx = controllerContext()
            val memoryScope = parseScope(scope)
            val count = store.deleteAll(ctx, memoryScope)
            mapOf("deleted" to count)
        }
    }

    @GetMapping("/config")
    fun getConfig(): Mono<MemoryConfigDto> {
        return mono {
            MemoryConfigDto(
                enabled = properties.memory.enabled,
                globalDir = properties.memory.globalDir,
                projectDir = properties.memory.projectDir
            )
        }
    }

    @PutMapping("/config")
    fun updateConfig(@RequestBody request: UpdateMemoryConfigRequest): Mono<MemoryConfigDto> {
        return mono {
            if (request.enabled != null) {
                throw IllegalArgumentException("'enabled' cannot be changed at runtime; requires application restart")
            }
            MemoryConfigDto(
                enabled = properties.memory.enabled,
                globalDir = properties.memory.globalDir,
                projectDir = properties.memory.projectDir
            )
        }
    }

    private fun parseScope(scope: String): MemoryScope = when (scope.lowercase()) {
        "global" -> MemoryScope.GLOBAL
        "project" -> MemoryScope.PROJECT
        else -> throw IllegalArgumentException("Invalid scope: $scope (expected 'global' or 'project')")
    }

    private fun parseType(type: String): MemoryType =
        MemoryType.fromDirName(type)
            ?: throw IllegalArgumentException("Invalid memory type: $type (expected one of: ${MemoryType.entries.joinToString { it.dirName }})")

    private fun MemoryEntry.toDto(scope: MemoryScope): MemoryEntryDto = MemoryEntryDto(
        name = name,
        description = description,
        type = type.dirName,
        scope = scope.name.lowercase(),
        content = content,
        keywords = keywords,
        created = created?.toString(),
        updated = updated?.toString()
    )
}
