package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Interface for validating agent config at different concern levels.
 *
 * [owners] is the caller's full read-visibility set (self → group → system); it defaults to just
 * [userId] so single-owner callers are unaffected. Existence checks that consult group-shareable
 * assets (skills today) resolve against [owners] so a member can reference their group's resources.
 */
interface AgentConfigValidator {
    suspend fun validate(
        request: AgentCreateRequest,
        userId: String,
        owners: Collection<String> = listOf(userId)
    ): List<ConfigValidationError>
}
