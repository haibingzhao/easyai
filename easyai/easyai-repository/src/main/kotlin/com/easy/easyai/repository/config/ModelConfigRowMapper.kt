package com.easy.easyai.repository.config

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.repository.database.Tables
import org.jetbrains.exposed.v1.core.ResultRow
import tools.jackson.databind.ObjectMapper

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
        ?.let { objectMapper.readValue(it, ModelCapabilities::class.java) }

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
