package com.easy.easyai.web.controller

import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.skills.command.extractHints
import com.easy.easyai.core.command.UserCommandDefinition
import com.easy.easyai.web.security.getCurrentUserId
import com.fasterxml.jackson.annotation.JsonInclude
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * REST controller for user-defined slash command CRUD.
 *
 * Endpoints:
 * - GET    /api/user-commands      - List all commands for current user
 * - GET    /api/user-commands/{id} - Get single command
 * - POST   /api/user-commands      - Create command
 * - PUT    /api/user-commands/{id} - Update command
 * - DELETE /api/user-commands/{id} - Delete command
 */
@RestController
@RequestMapping("/api/user-commands")
class UserCommandController(
    private val userCommandStore: AsyncUserCommandStore,
) {

    @GetMapping
    fun list(): Mono<List<UserCommandDto>> = mono {
        val userId = getCurrentUserId()
        userCommandStore.findAll(userId).map { it.toDto() }
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): Mono<UserCommandDto> = mono {
        val userId = getCurrentUserId()
        val cmd = userCommandStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Command not found: $id")
        cmd.toDto()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: UserCommandCreateRequest): Mono<UserCommandDto> = mono {
        val userId = getCurrentUserId()
        // Check name uniqueness
        val existing = userCommandStore.findByName(request.name, userId)
        if (existing != null && existing.userId == userId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Command '${request.name}' already exists")
        }
        val hints = extractHints(request.template)
        val command = UserCommandDefinition(
            id = "",
            name = request.name,
            description = request.description,
            aliases = request.aliases,
            template = request.template,
            hints = hints,
        )
        userCommandStore.save(command, userId).toDto()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: String, @RequestBody request: UserCommandCreateRequest): Mono<UserCommandDto> = mono {
        val userId = getCurrentUserId()
        val existing = userCommandStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Command not found: $id")
        // Check name uniqueness if name changed
        if (request.name != existing.name) {
            val nameConflict = userCommandStore.findByName(request.name, userId)
            if (nameConflict != null && nameConflict.userId == userId) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Command '${request.name}' already exists")
            }
        }
        val hints = extractHints(request.template)
        val updated = existing.copy(
            name = request.name,
            description = request.description,
            aliases = request.aliases,
            template = request.template,
            hints = hints,
        )
        userCommandStore.update(updated, userId).toDto()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String): Mono<Void> = mono {
        val userId = getCurrentUserId()
        userCommandStore.findById(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Command not found: $id")
        userCommandStore.delete(id, userId)
    }.then()

    private fun UserCommandDefinition.toDto() = UserCommandDto(
        id = id,
        name = name,
        description = description,
        aliases = aliases,
        template = template,
        hints = hints,
    )

}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserCommandDto(
    val id: String,
    val name: String,
    val description: String?,
    val aliases: List<String>,
    val template: String,
    val hints: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserCommandCreateRequest(
    val name: String,
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val template: String = "",
)
