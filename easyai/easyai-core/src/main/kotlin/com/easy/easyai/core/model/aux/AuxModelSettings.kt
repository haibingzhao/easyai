package com.easy.easyai.core.model.aux

/**
 * One user's model choice for a single [AuxModelTask], persisted in the `aux_model_settings`
 * table (one row per `(userId, taskKey)`) and editable from the frontend Settings page.
 *
 * [modelConfigId] references an existing `ModelProviderConfig` by ID. A blank value means the
 * task is unconfigured, so consumers fall back to their default (for compaction, the model
 * selected for the chat session).
 */
data class AuxModelSettings(
    val taskKey: String,
    val modelConfigId: String = ""
)
