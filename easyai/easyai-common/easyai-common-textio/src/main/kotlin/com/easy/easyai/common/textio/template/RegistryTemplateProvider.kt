package com.easy.easyai.common.textio.template

/**
 * Provides 1:1 mapping of logical names to templates.
 */
data class RegistryTemplateProvider(
    private val templateRenderer: TemplateRenderer,
) : TemplateProvider {

    private val registry: MutableMap<String, CompiledTemplate> = mutableMapOf()

    fun withTemplate(logicalName: String, location: String): RegistryTemplateProvider {
        registry[logicalName] = templateRenderer.compileLoadedTemplate(location)
        return this
    }

    override fun resolveTemplate(logicalName: String): CompiledTemplate = registry[logicalName]
        ?: throw NoSuchTemplateException("Cannot find logical template $logicalName: known logical names are ${registry.keys}")
}
