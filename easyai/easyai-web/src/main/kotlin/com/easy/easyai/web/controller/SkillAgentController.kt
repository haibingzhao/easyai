package com.easy.easyai.web.controller

import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.web.model.ChatStreamEvent
import com.easy.easyai.web.model.SkillAgentRequest
import com.easy.easyai.web.model.SkillArchiveInstallRequest
import com.easy.easyai.web.model.SkillArchiveInstallResponse
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveWriteOwner
import com.easy.easyai.web.service.skillgen.SkillArchiveInstallService
import com.easy.easyai.web.service.skillgen.SkillInstallStreamService
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * SSE entry point of the "Install with Agent" panel.
 *
 * The owner bucket is derived exactly like [SkillController]'s write endpoints — never from the
 * request body — so the preview reports the same target the later upload will use. No session is
 * created; the panel history lives only in the browser.
 */
@RestController
@RequestMapping("/api/skills/agent")
class SkillAgentController(
    private val skillInstallStreamService: SkillInstallStreamService,
    private val skillArchiveInstallService: SkillArchiveInstallService,
    @param:Autowired(required = false) private val skillConfig: SkillConfig? = null
) {

    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestBody request: SkillAgentRequest): Flux<ServerSentEvent<ChatStreamEvent>> =
        mono {
            if (skillConfig?.enabled == false) throw skillSystemNotEnabled()
            val userId = getCurrentUserId()
            val owners = currentOwners()
            val writeOwner = resolveWriteOwner(parseAssetScope(request.scope))
            skillInstallStreamService.stream(userId, writeOwner, owners, request)
        }.flatMapMany { it }

    /**
     * Install the entries the user selected on an archive preview card. The bucket comes from
     * [request]'s scope through the same server-side resolution as the preview, never from a body
     * user id, so a card cannot install into someone else's layer.
     */
    @PostMapping("/install")
    fun install(@RequestBody request: SkillArchiveInstallRequest): Mono<SkillArchiveInstallResponse> =
        mono {
            if (skillConfig?.enabled == false) throw skillSystemNotEnabled()
            val writeOwner = resolveWriteOwner(parseAssetScope(request.scope))
            skillArchiveInstallService.install(writeOwner, request)
        }

    private fun skillSystemNotEnabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The skill system is not enabled (set easyai.skills.enabled=true, with easyai.r2dbc.enabled on)"
    )
}
