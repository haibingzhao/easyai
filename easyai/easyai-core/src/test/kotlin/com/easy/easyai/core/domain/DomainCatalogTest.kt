package com.easy.easyai.core.domain

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.ContextReferences
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [DomainCatalog] resolution: the framework's own coding default, host-contributed domains,
 * and the misconfigurations that must fail at startup instead of degrading to another domain's
 * taxonomy.
 */
class DomainCatalogTest {

    private fun contributor(domainName: String, vararg codes: String) = object : DomainCatalogContributor {
        override val domain: String = domainName
        override val memoryCategories: List<MemoryCategorySpec> = codes.map { code ->
            MemoryCategorySpec(code, code.replaceFirstChar { it.uppercase() }, "desc $code")
        }
    }

    @Test
    fun `coding is available without any contributor`() {
        val catalog = DomainCatalog(emptyList(), DomainCatalog.CODING_DOMAIN)
        assertEquals(CodingDomainCatalog.memoryCategories, catalog.active())
        assertTrue(catalog.memoryCodes().contains("other"))
    }

    @Test
    fun `a host contributor answers for its own domain`() {
        val trading = contributor(
            "trading", "user_preferences", "market_insights", "strategy_lessons", "risk_rules", "other"
        )
        val catalog = DomainCatalog(listOf(trading), "trading")

        assertEquals("trading", catalog.activeDomain)
        assertEquals(listOf("user_preferences", "market_insights", "strategy_lessons", "risk_rules", "other"),
            catalog.memoryCodes())
        assertEquals(MemoryType("market_insights"), catalog.memoryType("  Market_Insights "))
        assertNull(catalog.memoryType("development_standards"))
    }

    @Test
    fun `the domain is matched case-insensitively`() {
        val catalog = DomainCatalog(listOf(contributor("home", "other")), "HOME")
        assertEquals("home", catalog.activeDomain)
        assertEquals(listOf("other"), catalog.memoryCodes())
    }

    @Nested
    inner class `memory type json compatibility` {

        private val mapper = SharedObjectMapper.instance

        @Test
        fun `legacy enum names and host category codes deserialize from strings`() {
            val json = """
                {
                  "memories": [
                    {"name":"market","description":"macro","type":"MARKET_INSIGHTS","scope":"GLOBAL"},
                    {"name":"family","description":"preference","type":"family_preferences","scope":"PROJECT"}
                  ],
                  "rules": []
                }
            """.trimIndent()

            val references = mapper.readValue(json, ContextReferences::class.java)

            assertEquals(
                listOf(MemoryType("market_insights"), MemoryType("family_preferences")),
                references.memories.map { it.type }
            )
            assertEquals(listOf(MemoryScope.GLOBAL, MemoryScope.PROJECT), references.memories.map { it.scope })
        }

        @Test
        fun `memory type serializes as its stable storage code`() {
            val type = MemoryType("strategy_lessons")

            val json = mapper.writeValueAsString(type)

            assertEquals("\"strategy_lessons\"", json)
            assertEquals(type, mapper.readValue(json, MemoryType::class.java))
        }
    }

    @Nested
    inner class `startup failures` {

        @Test
        fun `an unresolvable domain names the property and the registered domains`() {
            val e = assertFailsWith<IllegalStateException> {
                DomainCatalog(listOf(contributor("trading", "other")), "home")
            }
            assertTrue(e.message!!.contains("easyai.domain"), e.message!!)
            assertTrue(e.message!!.contains("trading"), e.message!!)
            assertTrue(e.message!!.contains("DomainCatalogContributor"), e.message!!)
        }

        @Test
        fun `a domain declared twice fails`() {
            assertFailsWith<IllegalStateException> {
                DomainCatalog(
                    listOf(contributor("home", "other"), contributor("home", "other")),
                    "home"
                )
            }
        }

        @Test
        fun `a host cannot redeclare coding`() {
            assertFailsWith<IllegalStateException> {
                DomainCatalog(listOf(contributor("coding", "other")), "coding")
            }
        }

        @Test
        fun `a domain without the fallback category fails`() {
            val e = assertFailsWith<IllegalStateException> {
                DomainCatalog(listOf(contributor("home", "family_preferences")), "home")
            }
            assertTrue(e.message!!.contains("other"), e.message!!)
        }

        @Test
        fun `a duplicate category inside one domain fails`() {
            assertFailsWith<IllegalStateException> {
                DomainCatalog(listOf(contributor("home", "other", "other")), "home")
            }
        }

        @Test
        fun `a category code that is not a directory name fails`() {
            assertFailsWith<IllegalArgumentException> {
                DomainCatalog(listOf(contributor("home", "../escape", "other")), "home")
            }
        }
    }
}
