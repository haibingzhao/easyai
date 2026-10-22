package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Per-chat overrides of two model options, sent by the composer's model picker.
 *
 * The preference itself lives in the browser; this object travels with each chat request and is
 * recorded on the session so resume paths and the history token bar keep the same values.
 * Absent fields keep the model config's value — an empty object means "no overrides".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelOverrides(
    val effort: String? = null,
    val contextToken: Int? = null
) {
    fun isEmpty(): Boolean = effort == null && contextToken == null

    companion object {
        val EMPTY = ModelOverrides()
    }
}
