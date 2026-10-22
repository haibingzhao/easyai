package com.easy.easyai.web.controller

import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentEnv
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.ChatPreference
import com.easy.easyai.core.agent.ChatPreferenceStore
import com.easy.easyai.web.security.currentGroupOwners
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveReadOwner
import com.easy.easyai.web.security.resolveWriteOwner
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * Per-owner Chat surface preferences (frontend Settings → Chat Agents).
 *
 * - GET /api/chat-preferences            — effective preference for the current login (self → group → built-in)
 * - GET /api/chat-preferences?scope=...  — the named bucket's own row, for editing (members may read group)
 * - PUT /api/chat-preferences            — replace the caller's own row
 * - PUT /api/chat-preferences?scope=group — replace the shared bucket (group owner only)
 *
 * `defaultAgentId` is the agent a fresh chat starts with; `hiddenAgentIds` keeps agents out of the
 * composer's agent picker. The backend validates both against the caller's visibility set so a stale
 * id cannot silently disable the picker, and refuses a configuration that hides every selectable
 * agent. A blank request (no default, no hidden ids) deletes the row, restoring built-in defaults.
 *
 * The write response reports the bucket that was written (`source`), not the effective layer: the
 * editor must keep showing the row it just saved even when a personal row shadows a group one. The
 * `effectiveSource` field still reports which layer actually wins for the caller.
 */
@RestController
@RequestMapping("/api/chat-preferences")
class ChatPreferenceController(
    private val agentStore: AsyncAgentStore,
    @param:Autowired(required = false)
    private val preferenceStore: ChatPreferenceStore? = null
) {

    @GetMapping
    fun get(@RequestParam(required = false) scope: String? = null): Mono<ChatPreferenceDto> = mono {
        val store = preferenceStore ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        if (scope.isNullOrBlank()) {
            effectiveDto(store, userId)
        } else {
            // Reads may target the group bucket as a member; only writes are owner-gated.
            storedDto(store, resolveReadOwner(parseAssetScope(scope)), userId)
        }
    }

    @PutMapping
    fun save(
        @RequestBody request: SaveChatPreferenceRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ChatPreferenceDto> = mono {
        val store = preferenceStore ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        val owner = resolveWriteOwner(parseAssetScope(scope))
        val visibleOwners = currentOwners()
        // A personal preference may reference the caller's whole visibility set. A group row applies
        // to members, so its default and hidden ids must resolve through the group and shared layers
        // only — an id from the owner's personal bucket would not exist for anyone else.
        val referenceOwners = if (owner == userId) visibleOwners else visibleOwners - userId

        val hidden = request.hiddenAgentIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val defaultAgentId = request.defaultAgentId?.trim()?.takeIf { it.isNotEmpty() }

        if (defaultAgentId != null && defaultAgentId in hidden) {
            throw badRequest("The default chat agent cannot also be hidden: $defaultAgentId")
        }

        if (defaultAgentId != null) {
            val agent = agentStore.findById(defaultAgentId, referenceOwners)
                ?: throw badRequest("Default chat agent not found: $defaultAgentId")
            if (!agent.isSelectableInChat()) {
                throw badRequest(
                    "Default chat agent '$defaultAgentId' is not an enabled chat agent (type=${agent.agentType}, context=${agent.agentContext})"
                )
            }
        }

        if (hidden.isNotEmpty()) {
            val found = agentStore.findByIds(hidden, referenceOwners)
            val missing = hidden.filter { it !in found }
            if (missing.isNotEmpty()) {
                throw badRequest("Hidden chat agents not found: ${missing.joinToString(", ")}")
            }
            // Hiding a sub-agent or a disabled / non-chat agent would have no effect today but starts
            // hiding it the moment it becomes selectable, so reject the id instead of storing a trap.
            val notSelectable = found.values.filter { !it.isSelectableInChat() }
            if (notSelectable.isNotEmpty()) {
                throw badRequest(
                    "Hidden entries must be enabled chat agents: ${notSelectable.joinToString(", ") { it.id }}"
                )
            }
        }

        val remaining = agentStore.findChatAgents(referenceOwners)
            .filter { it.isSelectableInChat() && it.id !in hidden }
        if (remaining.isEmpty()) {
            throw badRequest("At least one chat agent must remain visible")
        }

        if (defaultAgentId == null && hidden.isEmpty()) {
            store.delete(owner)
        } else {
            store.save(
                ChatPreference(
                    userId = owner,
                    defaultAgentId = defaultAgentId,
                    hiddenAgentIds = hidden
                )
            )
        }
        // Report the bucket that was written, so the editor keeps rendering the row it just saved.
        // A personal row shadowing a just-saved group row is reported separately via effectiveSource.
        storedDto(store, owner, userId)
    }

    /** Resolve the preference in force for [userId] and tag the layer that supplied it. */
    private suspend fun effectiveDto(store: ChatPreferenceStore, userId: String): ChatPreferenceDto {
        val row = store.getEffective(currentGroupOwners())
        val source = layerOf(row, userId)
        return ChatPreferenceDto(
            defaultAgentId = row?.defaultAgentId,
            hiddenAgentIds = row?.hiddenAgentIds.orEmpty(),
            effectiveSource = source,
            source = source
        )
    }

    /** The named bucket's own row plus the layer that actually wins for [callerId] right now. */
    private suspend fun storedDto(
        store: ChatPreferenceStore,
        owner: String,
        callerId: String
    ): ChatPreferenceDto {
        val row = store.get(owner)
        return ChatPreferenceDto(
            defaultAgentId = row?.defaultAgentId,
            hiddenAgentIds = row?.hiddenAgentIds.orEmpty(),
            effectiveSource = layerOf(store.getEffective(currentGroupOwners()), callerId),
            source = layerOf(row, callerId)
        )
    }

    private fun layerOf(row: ChatPreference?, callerId: String): String = when {
        row == null -> SOURCE_DEFAULT
        row.userId == callerId -> SOURCE_USER
        else -> SOURCE_GROUP
    }

    private fun AgentDefinition.isSelectableInChat(): Boolean =
        enabled && agentType != AgentType.SUBAGENT &&
            (agentContext == AgentEnv.CHAT || agentContext == AgentEnv.BOTH)

    private fun databaseDisabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Chat preferences require the database (set easyai.r2dbc.enabled=true); it is the only place they are stored"
    )

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_GROUP = "group"
        const val SOURCE_DEFAULT = "default"
    }
}

data class ChatPreferenceDto(
    val defaultAgentId: String?,
    val hiddenAgentIds: List<String>,
    /** Which layer wins for the caller right now: user | group | default. */
    val effectiveSource: String,
    /** Which layer supplied the returned values: user | group | default. Differs for a scoped read. */
    val source: String = effectiveSource
)

data class SaveChatPreferenceRequest(
    val defaultAgentId: String? = null,
    val hiddenAgentIds: List<String> = emptyList()
)
