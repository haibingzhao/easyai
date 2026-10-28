package com.easy.easyai.web.controller

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.domain.MemoryCategorySpec
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * REST controller exposing the active domain's memory category taxonomy.
 *
 * The frontend calls this once at startup to populate memory category dropdowns and
 * consolidation controls. Knowledge categories are not served here: article `category`
 * is a free-form tag, and the knowledge picker is a host-console preset.
 */
@RestController
@RequestMapping("/api/system")
class CategoryController(
    private val domainCatalog: DomainCatalog
) {

    @GetMapping("/categories")
    fun getCategories(): Mono<CategoryResponseDto> {
        return Mono.just(
            CategoryResponseDto(
                domain = domainCatalog.activeDomain,
                memory = domainCatalog.active().map { it.toDto() }
            )
        )
    }

    private fun MemoryCategorySpec.toDto(): CategorySpecDto =
        CategorySpecDto(code = code, labelKey = labelKey, description = description)
}

data class CategorySpecDto(
    val code: String,
    val labelKey: String,
    val description: String
)

data class CategoryResponseDto(
    val domain: String,
    val memory: List<CategorySpecDto>
)
