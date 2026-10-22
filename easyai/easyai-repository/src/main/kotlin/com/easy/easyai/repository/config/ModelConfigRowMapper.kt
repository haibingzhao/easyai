package com.easy.easyai.repository.config

import com.easy.easyai.api.model.ModelCapabilities
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.ReasoningMode
import com.easy.easyai.api.model.ReasoningProfile
import com.easy.easyai.api.model.ReasoningProfiles
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
 *
 * Rows written before reasoning profiles existed carry none. Filling the resolved matrix here —
 * rather than only in the UI's catalog lookup — makes the composer's gates and the adapters'
 * wire behavior read the same profile; the value is a read-time overlay, and the mapper itself
 * never writes it back.
 *
 * A stored `manual` declaration always wins. Derived profiles are re-resolved so vendor-matrix
 * updates reach existing rows; ids without a family rule keep whatever they stored, which is the
 * only place a catalog's NONE verdict lives.
 */
internal fun mapToModelProviderConfig(row: ResultRow, objectMapper: ObjectMapper): ModelProviderConfig {
    val optionsJson = row[Tables.ModelProviderConfigTable.options]
    val options: ModelOptions? = optionsJson?.takeIf { it.isNotBlank() }
        ?.let { objectMapper.readValue(it, ModelOptions::class.java) }

    val capabilitiesJson = row[Tables.ModelProviderConfigTable.capabilities]
    val storedCapabilities: ModelCapabilities? = capabilitiesJson?.takeIf { it.isNotBlank() }
        ?.let { capabilitiesMapper(objectMapper).readValue(it, ModelCapabilities::class.java) }

    val modelId = row[Tables.ModelProviderConfigTable.modelId]
    val capabilities = storedCapabilities
        ?.takeIf { it.reasoning?.source == ReasoningProfile.SOURCE_MANUAL }
        ?: ReasoningProfiles.resolve(modelId)
            .takeIf { it.mode != ReasoningMode.UNKNOWN }
            ?.let { (storedCapabilities ?: ModelCapabilities()).copy(reasoning = it) }
        ?: storedCapabilities

    return ModelProviderConfig(
        id = row[Tables.ModelProviderConfigTable.id],
        name = row[Tables.ModelProviderConfigTable.name],
        protocol = Protocol.valueOf(row[Tables.ModelProviderConfigTable.protocol]),
        isCustom = row[Tables.ModelProviderConfigTable.isCustom],
        baseUrl = row[Tables.ModelProviderConfigTable.baseUrl],
        apiKey = row[Tables.ModelProviderConfigTable.apiKey],
        modelId = modelId,
        modelName = row[Tables.ModelProviderConfigTable.modelName],
        isCustomModel = row[Tables.ModelProviderConfigTable.isCustomModel],
        enabled = row[Tables.ModelProviderConfigTable.enabled],
        options = options,
        timeoutSeconds = row[Tables.ModelProviderConfigTable.timeoutSeconds],
        capabilities = capabilities,
        groupId = row[Tables.ModelProviderConfigTable.groupId],
        modelType = parseModelType(row[Tables.ModelProviderConfigTable.modelType]),
        mediaOptions = row[Tables.ModelProviderConfigTable.mediaOptions]?.takeIf { it.isNotBlank() },
        isDefault = row[Tables.ModelProviderConfigTable.isDefault],
        userId = row[Tables.ModelProviderConfigTable.userId]
    )
}

// Pre-V11 rows or writes from an older build may carry an unknown type string; CHAT is the safe default.
private fun parseModelType(raw: String): ModelType =
    ModelType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ModelType.CHAT
