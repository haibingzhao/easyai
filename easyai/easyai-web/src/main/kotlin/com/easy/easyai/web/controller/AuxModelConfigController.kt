package com.easy.easyai.web.controller

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * REST controller for per-user auxiliary model choices (frontend Settings → Task Models).
 *
 * One endpoint pair serves every [AuxModelTask]: adding a purpose is a backend enum change, not a
 * new route. A blank/absent choice means the task falls back to its default (for compaction, the
 * chat-session model).
 *
 * - GET /api/aux-models            - Every task with the user's current choice and layer in force
 * - PUT /api/aux-models/{taskKey}  - Set (or, with a blank id, clear) the model for one task
 *
 * The owner comes from the security context only. Both answer 503 when persistence is absent
 * (`easyai.r2dbc.enabled=false`), since the database is the only place a choice can be stored.
 */
@RestController
@RequestMapping("/api/aux-models")
class AuxModelConfigController(
    @param:Autowired(required = false)
    private val settingsStore: AuxModelSettingsStore? = null,
    @param:Autowired(required = false)
    private val configStore: ModelProviderConfigStore? = null,
    @param:Autowired(required = false)
    private val resolver: AuxModelResolver? = null
) {

    @GetMapping
    fun list(): Mono<List<AuxModelConfigDto>> = mono {
        val store = settingsStore ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        val byTask = store.getAll(userId).associateBy { it.taskKey }
        AuxModelTask.entries.map { task ->
            val modelConfigId = byTask[task.key]?.modelConfigId.orEmpty()
            toDto(task, modelConfigId)
        }
    }

    @PutMapping("/{taskKey}")
    fun save(
        @PathVariable taskKey: String,
        @RequestBody request: SaveAuxModelRequest
    ): Mono<AuxModelConfigDto> = mono {
        val store = settingsStore ?: throw databaseDisabled()
        val task = AuxModelTask.fromKey(taskKey)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown task key: $taskKey")
        val userId = getCurrentUserId()
        val modelConfigId = request.modelConfigId?.trim().orEmpty()

        if (modelConfigId.isBlank()) {
            // Clearing the choice returns the task to its default.
            store.delete(userId, task)
        } else {
            val configs = configStore ?: throw databaseDisabled()
            if (configs.getConfig(modelConfigId, userId) == null) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Model config not found: $modelConfigId")
            }
            store.save(userId, task, modelConfigId)
        }
        resolver?.refresh(userId, task)
        toDto(task, modelConfigId)
    }

    private fun databaseDisabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Task model settings require the database (set easyai.r2dbc.enabled=true); it is the only place they are stored"
    )

    private fun toDto(task: AuxModelTask, modelConfigId: String): AuxModelConfigDto = AuxModelConfigDto(
        taskKey = task.key,
        modelConfigId = modelConfigId,
        effectiveSource = if (modelConfigId.isBlank()) SOURCE_DEFAULT else SOURCE_USER
    )

    private companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_DEFAULT = "default"
    }
}

/** One task's current choice; a blank [modelConfigId] means it follows the default model. */
data class AuxModelConfigDto(
    val taskKey: String,
    val modelConfigId: String,
    /** Which layer is in force: user | default. */
    val effectiveSource: String
)

/** Save draft; a null/blank [modelConfigId] clears the choice and reverts to the default. */
data class SaveAuxModelRequest(
    val modelConfigId: String? = null
)
