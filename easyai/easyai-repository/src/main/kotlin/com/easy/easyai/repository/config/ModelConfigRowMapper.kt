package com.easy.easyai.repository.config

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.repository.database.Tables
import org.jetbrains.exposed.v1.core.ResultRow
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

// Capabilities JSON may contain enum values written by a newer build; degrade them to null
// (i.e. legacy default semantics) instead of failing the whole config row on read.
// Jackson ignores unknown fields by default, but unknown enum values throw unless this feature is on.
private val tolerantMappers = ConcurrentHashMap<ObjectMapper, ObjectMapper>()

internal fun capabilitiesMapper(base: ObjectMapper): ObjectMapper =
    tolerantMappers.computeIfAbsent(base) {
        // JsonMapper.rebuild() has a concrete Builder type; ObjectMapper.rebuild() is generic
        // and Kotlin cannot infer its self-referential bounds.
        (it as JsonMapper).rebuild()
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .build()
    }

/**
 * Shared row mapper for ModelProviderConfig.
 * Eliminates duplication between R2dbcModelConfigStore and R2dbcModelConfigGroupStore.
 */
internal fun mapToModelProviderConfig(row: ResultRow, objectMapper: ObjectMapper): ModelProviderConfig {
    val optionsJson = row[Tables.ModelProviderConfigTable.options]
    val options: ModelOptions? = optionsJson?.takeIf { it.isNotBlank() }
        ?.let { objectMapper.readValue(it, ModelOptions::class.java) }

    val capabilitiesJson = row[Tables.ModelProviderConfigTable.capabilities]
    val capabilities: ModelCapabilities? = capabilitiesJson?.takeIf { it.isNotBlank() }
        ?.let { capabilitiesMapper(objectMapper).readValue(it, ModelCapabilities::class.java) }

    return ModelProviderConfig(
        id = row[Tables.ModelProviderConfigTable.id],
        name = row[Tables.ModelProviderConfigTable.name],
        protocol = Protocol.valueOf(row[Tables.ModelProviderConfigTable.protocol]),
        isCustom = row[Tables.ModelProviderConfigTable.isCustom],
        baseUrl = row[Tables.ModelProviderConfigTable.baseUrl],
        apiKey = row[Tables.ModelProviderConfigTable.apiKey],
        modelId = row[Tables.ModelProviderConfigTable.modelId],
        modelName = row[Tables.ModelProviderConfigTable.modelName],
        isCustomModel = row[Tables.ModelProviderConfigTable.isCustomModel],
        enabled = row[Tables.ModelProviderConfigTable.enabled],
        options = options,
        timeoutSeconds = row[Tables.ModelProviderConfigTable.timeoutSeconds],
        capabilities = capabilities,
        groupId = row[Tables.ModelProviderConfigTable.groupId]
    )
}
