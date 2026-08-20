package com.easy.easyai.web.controller

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.web.model.CreateMemoryRequest
import com.easy.easyai.web.model.MemoryConfigDto
import com.easy.easyai.web.model.MemoryEntryDto
import com.easy.easyai.web.model.UpdateMemoryConfigRequest
import com.easy.easyai.web.model.UpdateMemoryRequest
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.nio.file.Paths
import java.time.LocalDate

@RestController
@RequestMapping("/api/memories")
class MemoryController(
    @param:Autowired(required = false) private val memoryStore: MemoryStore?
) {

    @GetMapping
    fun listMemories(
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) maturity: String?,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<List<MemoryEntryDto>> {
        return mono {
            val store = memoryStore ?: return@mono emptyList()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            val memoryType = type?.let { parseType(it) }
            val maturityFilter = maturity?.let { MemoryMaturity.fromApiName(it) }
            store.list(memoryScope, owner, memoryType)
                .filter { maturityFilter == null || it.maturity == maturityFilter }
                .map { it.toDto(memoryScope) }
        }
    }

    @GetMapping("/{name}")
    fun getMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            store.findByName(name, memoryScope, owner)?.toDto(memoryScope)
                ?: throw IllegalArgumentException("Memory not found: $name")
        }
    }

    @PostMapping
    fun createOrUpdateMemory(@RequestBody request: CreateMemoryRequest): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            // Validate name to prevent path traversal and invalid file names
            require(request.name.isNotBlank()) { "Memory name must not be blank" }
            require(!request.name.contains("/") && !request.name.contains("\\") && !request.name.contains("..")) {
                "Memory name must not contain path separators or '..'"
            }
            val memoryScope = parseScope(request.scope)
            val owner = ownerContext(memoryScope, request.projectPath)
            val memoryType = parseType(request.type)
            val maturity = request.maturity?.let { MemoryMaturity.fromApiName(it) }
            val scenarios = request.scenarios.map { it.trim() }.filter { it.isNotEmpty() }
            // Preserve original created date if updating an existing entry
            val existing = store.findByName(request.name, memoryScope, owner)
            val entry = MemoryEntry(
                name = request.name,
                description = request.description,
                type = memoryType,
                content = request.content,
                path = "${memoryType.dirName}/${request.name}.md",
                keywords = request.keywords,
                created = existing?.created ?: LocalDate.now(),
                updated = LocalDate.now(),
                maturity = maturity,
                scenarios = scenarios
            )
            store.write(entry, memoryScope, owner)
            entry.toDto(memoryScope)
        }
    }

    @PutMapping("/{name}")
    fun updateMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null,
        @RequestBody request: UpdateMemoryRequest
    ): Mono<MemoryEntryDto> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            val existing = store.findByName(name, memoryScope, owner)
                ?: throw IllegalArgumentException("Memory not found: $name")
            val maturity = request.maturity?.let { MemoryMaturity.fromApiName(it) }
            val entry = existing.copy(
                description = request.description ?: existing.description,
                content = request.content ?: existing.content,
                keywords = request.keywords?.map { it.trim() }?.filter { it.isNotEmpty() } ?: existing.keywords,
                maturity = maturity ?: existing.maturity,
                scenarios = request.scenarios?.map { it.trim() }?.filter { it.isNotEmpty() } ?: existing.scenarios,
                updated = LocalDate.now()
            )
            store.write(entry, memoryScope, owner)
            entry.toDto(memoryScope)
        }
    }

    @DeleteMapping("/{name}")
    fun deleteMemory(
        @PathVariable name: String,
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<Map<String, Boolean>> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            val entry = store.findByName(name, memoryScope, owner)
                ?: throw IllegalArgumentException("Memory not found: $name")
            val deleted = store.delete(entry.path, memoryScope, owner)
            mapOf("deleted" to deleted)
        }
    }

    @DeleteMapping
    fun deleteAllMemories(
        @RequestParam(defaultValue = "global") scope: String,
        @RequestParam(required = false) projectPath: String? = null
    ): Mono<Map<String, Int>> {
        return mono {
            val store = memoryStore ?: throw memoryNotEnabled()
            val memoryScope = parseScope(scope)
            val owner = ownerContext(memoryScope, projectPath)
            val count = store.deleteAll(memoryScope, owner)
            mapOf("deleted" to count)
        }
    }

    @GetMapping("/config")
    fun getConfig(): Mono<MemoryConfigDto> {
        return mono {
            // Bean wiring guarantees store != null only when easyai.memory.enabled
            // and easyai.rag.enabled are both set, so this reflects real availability.
            MemoryConfigDto(enabled = memoryStore != null)
        }
    }

    @PutMapping("/config")
    fun updateConfig(@RequestBody request: UpdateMemoryConfigRequest): Mono<MemoryConfigDto> {
        return mono {
            if (request.enabled != null) {
                throw IllegalArgumentException("'enabled' cannot be changed at runtime; requires application restart")
            }
            MemoryConfigDto(enabled = memoryStore != null)
        }
    }

    private fun memoryNotEnabled(): ResponseStatusException =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Memory system is not enabled")

    /**
     * Build the ownership context for backend isolation. PROJECT scope
     * requires an explicit projectPath; without it the request is rejected.
     */
    private suspend fun ownerContext(scope: MemoryScope, projectPath: String?): MemoryOwnerContext {
        if (scope == MemoryScope.PROJECT && projectPath.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectPath is required for project scope")
        }
        return MemoryOwnerContext(
            userId = getCurrentUserId(),
            projectPath = projectPath?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        )
    }

    private fun parseScope(scope: String): MemoryScope = when (scope.lowercase()) {
        "global" -> MemoryScope.GLOBAL
        "project" -> MemoryScope.PROJECT
        else -> throw IllegalArgumentException("Invalid scope: $scope (expected 'global' or 'project')")
    }

    private fun parseType(type: String): MemoryType {
        val memoryType = MemoryType.fromDirName(type)
            ?: throw IllegalArgumentException("Invalid memory type: $type (expected one of: ${MemoryType.entriesFor(DomainCatalog.activeDomain).joinToString { it.dirName }})")
        val validTypes = MemoryType.entriesFor(DomainCatalog.activeDomain)
        if (memoryType !in validTypes) {
            throw IllegalArgumentException("Memory type '$type' is not available in the current domain (expected one of: ${validTypes.joinToString { it.dirName }})")
        }
        return memoryType
    }

    private fun MemoryEntry.toDto(scope: MemoryScope): MemoryEntryDto = MemoryEntryDto(
        name = name,
        description = description,
        type = type.dirName,
        scope = scope.name.lowercase(),
        content = content,
        keywords = keywords,
        maturity = maturity?.apiName,
        scenarios = scenarios,
        created = created?.toString(),
        updated = updated?.toString()
    )
}
