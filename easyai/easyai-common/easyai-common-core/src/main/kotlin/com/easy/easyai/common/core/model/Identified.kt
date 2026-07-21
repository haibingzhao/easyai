package com.easy.easyai.common.core.model

/**
 * Interface for objects that can have a unique id,
 * but can also be in a transient state.
 * The object may or may not be persisted
 */
interface Identified {

    /**
     * The id of the object will be set once persisted
     */
    val id: String?
        get() = null

    /**
     * Is this object persistent? Not the same as whether it has been persisted
     */
    fun persistent(): Boolean

    companion object {

        operator fun invoke(id: String): Identified {
            return object : Identified {
                override val id = id
                override fun persistent(): Boolean = true
            }
        }
    }

}

/**
 * Identified object that is persistent
 */
interface Persistent : Identified {

    override fun persistent(): Boolean = true

}

interface Ephemeral : Identified {

    override fun persistent(): Boolean = false

}
