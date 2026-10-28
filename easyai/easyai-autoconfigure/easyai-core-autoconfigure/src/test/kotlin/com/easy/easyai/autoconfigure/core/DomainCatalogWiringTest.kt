package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.domain.DomainCatalogContributor
import com.easy.easyai.core.domain.MemoryCategorySpec
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the one line that turns `easyai.domain` into a resolved taxonomy: the auto-configured
 * [DomainCatalog] must collect every host [DomainCatalogContributor] bean, and a domain nobody
 * declares has to fail the context instead of falling back to the coding categories.
 */
class DomainCatalogWiringTest {

    private fun providerOf(vararg contributors: DomainCatalogContributor): ObjectProvider<DomainCatalogContributor> {
        val provider = mockk<ObjectProvider<DomainCatalogContributor>>()
        every { provider.orderedStream() } returns contributors.toList().stream()
        return provider
    }

    private fun configuration(domain: String) =
        EasyAiCoreAutoConfiguration(EasyAiProperties(domain = domain))

    private val homeContributor = object : DomainCatalogContributor {
        override val domain = "home"
        override val memoryCategories = listOf(
            MemoryCategorySpec("household_rules", "Household Rules", "Rules of this household"),
            MemoryCategorySpec("other", "Other", "Uncategorised memories")
        )
    }

    @Test
    fun `the default domain needs no contributor`() {
        val catalog = configuration("coding").domainCatalog(providerOf())

        assertEquals("coding", catalog.activeDomain)
        assertTrue(catalog.memoryCodes().contains("development_standards"))
    }

    @Test
    fun `a host contributor is picked up`() {
        val catalog = configuration("home").domainCatalog(providerOf(homeContributor))

        assertEquals(listOf("household_rules", "other"), catalog.memoryCodes())
    }

    @Test
    fun `a domain no host declares fails the context`() {
        val e = assertFailsWith<IllegalStateException> {
            configuration("trading").domainCatalog(providerOf(homeContributor))
        }

        assertTrue(e.message!!.contains("easyai.domain"), e.message!!)
        assertTrue(e.message!!.contains("home"), e.message!!)
    }
}
