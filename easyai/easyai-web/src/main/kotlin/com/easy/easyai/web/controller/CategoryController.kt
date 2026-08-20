package com.easy.easyai.web.controller

import com.easy.easyai.core.domain.CategorySpec
import com.easy.easyai.core.domain.DomainCatalog
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * REST controller exposing the category taxonomy for the active domain.
 *
 * The frontend calls this once at startup to populate memory/knowledge
 * category dropdowns. Response reflects [DomainCatalog.active] which is
 * set at application startup from `easyai.domain`.
 */
@RestController
@RequestMapping("/api/system")
class CategoryController {

    @GetMapping("/categories")
    fun getCategories(): Mono<CategoryResponseDto> {
        val cats = DomainCatalog.active()
        return Mono.just(
            CategoryResponseDto(
                domain = cats.domain,
                knowledge = cats.knowledge.map { it.toDto() },
                memory = cats.memory.map { it.toDto() }
            )
        )
    }

    private fun CategorySpec.toDto(): CategorySpecDto =
        CategorySpecDto(code = code, labelKey = labelKey, description = description)
}

data class CategorySpecDto(
    val code: String,
    val labelKey: String,
    val description: String
)

data class CategoryResponseDto(
    val domain: String,
    val knowledge: List<CategorySpecDto>,
    val memory: List<CategorySpecDto>
)
