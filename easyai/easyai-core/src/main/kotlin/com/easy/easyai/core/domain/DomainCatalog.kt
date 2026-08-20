package com.easy.easyai.core.domain

/**
 * Describes a single category within a domain (knowledge or memory).
 *
 * @param code machine-readable identifier (e.g. "architecture")
 * @param labelKey i18n dictionary key for the display label (e.g. "Architecture");
 *                 must match the frontend i18n key space
 * @param description human-readable description of the category
 */
data class CategorySpec(
    val code: String,
    val labelKey: String,
    val description: String
)

/**
 * The full set of categories available for a given domain.
 *
 * @param domain domain identifier (e.g. "coding", "trading")
 * @param knowledge categories for knowledge base documents
 * @param memory categories for agent memory entries
 */
data class DomainCategories(
    val domain: String,
    val knowledge: List<CategorySpec>,
    val memory: List<CategorySpec>
)

/**
 * Global registry of domain-specific categories. Set-once at application startup
 * via [activeDomain]; unknown domain values fall back to "coding".
 *
 * This is a lightweight global object (similar to SharedObjectMapper) — no Spring
 * dependency, no DI needed. The [activeDomain] field is `@Volatile` for safe
 * cross-thread reads after startup initialisation.
 */
object DomainCatalog {

    /** The currently active domain. Defaults to "coding"; set once at startup. */
    @Volatile
    var activeDomain: String = "coding"

    // ---- Knowledge categories per domain ----

    private val CODING_KNOWLEDGE = listOf(
        CategorySpec("overview", "Overview", "General project overview"),
        CategorySpec("architecture", "Architecture", "Architecture design and module layout"),
        CategorySpec("tech_stack", "Tech Stack", "Technology stack and dependencies"),
        CategorySpec("conventions", "Conventions", "Coding conventions and standards"),
        CategorySpec("setup_commands", "Setup & Commands", "Environment setup and build commands"),
        CategorySpec("other", "Other", "Uncategorised documents")
    )

    private val TRADING_KNOWLEDGE = listOf(
        CategorySpec("overview", "Overview", "General project overview"),
        CategorySpec("architecture", "Architecture", "Architecture design and module layout"),
        CategorySpec("market_data", "Market Data", "Market data sources and feeds"),
        CategorySpec("broker_connectors", "Broker Connectors", "Broker connectivity and order routing"),
        CategorySpec("strategy", "Strategy & Research", "Strategy research and development"),
        CategorySpec("risk_compliance", "Risk & Compliance", "Risk management and compliance rules"),
        CategorySpec("other", "Other", "Uncategorised documents")
    )

    // ---- Memory categories per domain ----

    private val CODING_MEMORY = listOf(
        CategorySpec("user_preferences", "User Preferences", "User preferences and personal settings"),
        CategorySpec("project_information", "Project Information", "Active project context and decisions"),
        CategorySpec("development_standards", "Development Standards", "Coding and process standards"),
        CategorySpec("task_summary", "Task Summary", "Completed task retrospectives"),
        CategorySpec("experience_lessons", "Experience & Lessons", "Reusable experience and lessons"),
        CategorySpec("other", "Other", "Uncategorised memories")
    )

    private val TRADING_MEMORY = listOf(
        CategorySpec("user_preferences", "User Preferences", "User preferences and personal settings"),
        CategorySpec("project_information", "Project Information", "Active project context and decisions"),
        CategorySpec("task_summary", "Task Summary", "Completed task retrospectives"),
        CategorySpec("experience_lessons", "Experience & Lessons", "Reusable experience and lessons"),
        CategorySpec("other", "Other", "Uncategorised memories"),
        CategorySpec("market_insights", "Market Insights", "Market observations and macro views"),
        CategorySpec("strategy_lessons", "Strategy Lessons", "Strategy back-test lessons and insights"),
        CategorySpec("risk_rules", "Risk Rules", "Risk management red lines and rules")
    )

    private val DOMAINS: Map<String, DomainCategories> = mapOf(
        "coding" to DomainCategories("coding", CODING_KNOWLEDGE, CODING_MEMORY),
        "trading" to DomainCategories("trading", TRADING_KNOWLEDGE, TRADING_MEMORY)
    )

    private val DEFAULT = DOMAINS.getValue("coding")

    /** Return the categories for the given [domain], falling back to "coding" if unknown. */
    @JvmStatic
    fun forDomain(domain: String): DomainCategories =
        DOMAINS[domain.lowercase()] ?: DEFAULT

    /** Return the categories for the currently active domain. */
    @JvmStatic
    fun active(): DomainCategories = forDomain(activeDomain)
}
