package com.easy.easyai.core.domain

import com.easy.easyai.core.memory.MemoryType
import org.slf4j.LoggerFactory

/**
 * One memory category declared by a domain catalog.
 *
 * @param code storage directory name and stable API value (e.g. "experience_lessons")
 * @param labelKey i18n dictionary key for the display label; host consoles translate it
 * @param description human-readable description of the category
 */
data class MemoryCategorySpec(
    val code: String,
    val labelKey: String,
    val description: String
)

/**
 * Host-provided memory taxonomy of one application domain.
 *
 * The framework only ships the [CodingDomainCatalog] default. Every other deployment
 * (`easyai.domain=trading|home|...`) must declare its own Spring bean implementing this
 * interface; a domain the active deployment cannot resolve fails startup instead of
 * silently falling back to coding categories.
 */
interface DomainCatalogContributor {
    /** Domain identifier this host answers for (e.g. "trading", "home"). */
    val domain: String

    /** Memory categories available to the LLM, the REST API and the UI in this domain. */
    val memoryCategories: List<MemoryCategorySpec>
}

/**
 * Resolved memory taxonomy for the active domain.
 *
 * Set-once at application startup; the instance is immutable afterwards. Memory writes
 * validate their `type` against [memoryCodes], while parsing stored entries stays tolerant
 * ([MemoryType.of]) so history written under another domain remains readable.
 */
class DomainCatalog(
    contributors: Collection<DomainCatalogContributor>,
    activeDomain: String
) {

    private val logger = LoggerFactory.getLogger(DomainCatalog::class.java)

    private val catalogs: Map<String, List<MemoryCategorySpec>>

    /** The active domain, lowercased. */
    val activeDomain: String

    init {
        this.activeDomain = activeDomain.trim().lowercase()
        val byDomain = LinkedHashMap<String, List<MemoryCategorySpec>>()
        for (contributor in listOf(CodingDomainCatalog) + contributors) {
            val domain = contributor.domain.trim().lowercase()
            check(domain.isNotEmpty()) {
                "DomainCatalogContributor ${contributor.javaClass.name} declares a blank domain"
            }
            val categories = contributor.memoryCategories
            check(categories.isNotEmpty()) {
                "Domain '$domain' declares no memory categories; at least '${OTHER_CODE}' is required"
            }
            categories.forEach { spec ->
                require(CODE_PATTERN.matches(spec.code)) {
                    "Domain '$domain' declares invalid memory category code '${spec.code}': " +
                        "codes must match ${CODE_PATTERN.pattern}"
                }
                require(spec.labelKey.isNotBlank()) {
                    "Domain '$domain' declares memory category '${spec.code}' without a labelKey"
                }
            }
            val duplicates = categories.groupingBy { it.code }.eachCount().filterValues { it > 1 }.keys
            check(duplicates.isEmpty()) {
                "Domain '$domain' declares duplicate memory categories: ${duplicates.joinToString()}"
            }
            check(categories.any { it.code == OTHER_CODE }) {
                "Domain '$domain' must declare the '$OTHER_CODE' memory category: it is the " +
                    "fallback used by memory flush and by parsing entries whose stored type is unknown"
            }
            check(byDomain.put(domain, categories) == null) {
                "Domain '$domain' is declared twice (${contributor.javaClass.name} collides with an " +
                    "earlier contributor); only one DomainCatalogContributor may own a domain"
            }
        }
        catalogs = byDomain
        check(this.activeDomain in byDomain) {
            "Unknown easyai.domain '${this.activeDomain}': no DomainCatalogContributor declares it. " +
                "Registered domains: ${byDomain.keys.joinToString()}. Add a @Component implementing " +
                "DomainCatalogContributor for '${this.activeDomain}' in this application, or change easyai.domain."
        }
        logger.info(
            "Memory domain catalog active: domain={}, categories={}",
            this.activeDomain,
            byDomain.getValue(this.activeDomain).size
        )
    }

    /** Memory categories of the active domain. */
    fun active(): List<MemoryCategorySpec> = catalogs.getValue(activeDomain)

    /** Strict lookup for input validation: only categories of the active domain resolve. */
    fun memoryType(code: String): MemoryType? {
        val normalized = code.trim().lowercase()
        return active().firstOrNull { it.code == normalized }?.let { MemoryType(normalized) }
    }

    /** Storage codes of the active domain's memory categories, in declaration order. */
    fun memoryCodes(): List<String> = active().map { it.code }

    companion object {
        const val CODING_DOMAIN = "coding"

        /** Fallback category every domain must declare. */
        const val OTHER_CODE = "other"

        /** Code shape shared with the memory storage directory names. */
        val CODE_PATTERN: Regex = Regex("^[a-z0-9_]+$")
    }
}
