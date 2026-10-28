package com.easy.easyai.web.controller

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.domain.DomainCatalogContributor
import com.easy.easyai.core.domain.MemoryCategorySpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests [CategoryController]: the response carries the active domain's memory taxonomy only.
 * Knowledge categories are a host-console preset, so nothing here may re-publish them — that is
 * what keeps another application's knowledge codes out of the framework.
 */
class CategoryControllerTest {

    private val homeCatalog = DomainCatalog(
        listOf(object : DomainCatalogContributor {
            override val domain = "home"
            override val memoryCategories = listOf(
                MemoryCategorySpec("family_preferences", "Family Preferences", "What the household prefers"),
                MemoryCategorySpec("other", "Other", "Uncategorised memories")
            )
        }),
        activeDomain = "home"
    )

    @Test
    fun `response carries the active domain and its memory categories`() {
        val response = CategoryController(homeCatalog).getCategories().block()!!

        assertEquals("home", response.domain)
        assertEquals(listOf("family_preferences", "other"), response.memory.map { it.code })
        assertEquals(listOf("Family Preferences", "Other"), response.memory.map { it.labelKey })
    }

    @Test
    fun `no knowledge categories are published`() {
        val json = CategoryController(homeCatalog).getCategories().block()!!
            .let { com.easy.easyai.common.util.SharedObjectMapper.instance.writeValueAsString(it) }

        assertFalse(json.contains("knowledge"), json)
    }
}
