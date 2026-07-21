package com.easy.easyai.common.core.model

/**
 * Implemented by something (like an entity) that has a set of physical chunk ids for its provenance
 */
interface Sourced {

    /**
     * The set of persistent chunk ids that this entity is sourced from.
     * Source documents can be resolved from these chunk ids.
     * Can be empty if the entity is not backed by chunks but (for example)
     * by a database record.
     */
    val chunkIds: Set<String>
}
