package com.easy.easyai.common.core.model

/**
 * Implemented by objects that have a stable identifier,
 * regardless of whether they're persisted.
 * For example, the id is set before possible persistence.
 */
interface StableIdentified : Identified {

    override val id: String
}
