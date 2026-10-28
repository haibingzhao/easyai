package com.easy.easyai.core.domain

/**
 * The framework's own domain: coding-style agents (easyai-web-server, desktop, console).
 *
 * Every other deployment (trading, home, ...) contributes its taxonomy from its own
 * repository instead of extending this list.
 */
object CodingDomainCatalog : DomainCatalogContributor {

    override val domain: String = DomainCatalog.CODING_DOMAIN

    override val memoryCategories: List<MemoryCategorySpec> = listOf(
        MemoryCategorySpec(
            "user_preferences", "User Preferences", "User preferences and personal settings"
        ),
        MemoryCategorySpec(
            "project_information", "Project Information", "Active project context and decisions"
        ),
        MemoryCategorySpec(
            "development_standards", "Development Standards", "Coding and process standards"
        ),
        MemoryCategorySpec(
            "task_summary", "Task Summary", "Completed task retrospectives"
        ),
        MemoryCategorySpec(
            "experience_lessons", "Experience & Lessons", "Reusable experience and lessons"
        ),
        MemoryCategorySpec(
            DomainCatalog.OTHER_CODE, "Other", "Uncategorised memories"
        )
    )
}
