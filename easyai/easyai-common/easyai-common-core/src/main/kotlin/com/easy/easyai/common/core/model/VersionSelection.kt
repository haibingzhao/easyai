package com.easy.easyai.common.core.model

/**
 * Identifies a version we want to use: name and version.
 * Needs to be resolved at runtime.
 */
data class VersionSelection(
    /**
     * Name of the versioned entity
     */
    val name: String,
    /**
     * Desired version of the entity. If not specified, the latest version is used.
     */
    val version: Int? = null,
    // Only needed for persistence, can be ignored otherwise
    private val id: String? = null,
) {

    companion object {
        fun require(v: Versioned) = VersionSelection(v.name, v.version)
    }
}
