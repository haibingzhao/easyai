package com.easy.easyai.web.controller

import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * REST controller for Skill queries.
 *
 * Endpoints:
 * - GET /api/skills - List all available skills
 */
@RestController
@RequestMapping("/api/skills")
class SkillController(
    @param:Autowired(required = false)
    private val skillRegistry: SkillRegistry? = null
) {

    @GetMapping
    fun listSkills(): Mono<List<SkillDto>> {
        val registry = skillRegistry ?: return Mono.just(emptyList())
        return Mono.just(
            registry.all()
                .map { it.toDto() }
        )
    }

    private fun SkillInfo.toDto(): SkillDto = SkillDto(
        name = name,
        description = description,
        tags = tags.toList()
    )
}

data class SkillDto(
    val name: String,
    val description: String?,
    val tags: List<String> = emptyList()
)
