package com.easy.easyai.common.core.model

import tools.jackson.databind.annotation.JsonDeserialize

/**
 * A versioned object has a name and a version.
 * The combination should be unique, but there is also an id
 * Each version is immutable.
 */
@JsonDeserialize(`as` = SimpleVersioned::class)
interface Versioned : Persistent {

    /**
     * A name should be stable.
     * New versions can be added with the same name.
     */
    val name: String

    /**
     * Our version of this, from 1
     */
    val version: Int

    /**
     * Just the versioned information from this object.
     * Allows a Versioned object to be serialized without excessive size from its fields
     */
    fun versionInfo(): Versioned = SimpleVersioned(this)

}

/**
 * Used in versionInfo() method and as Spring Data Neo4j projection.
 */
data class SimpleVersioned(
    override val name: String,
    override val version: Int,
    override val id: String? = null,
) : Versioned {

    constructor(versioned: Versioned) : this(versioned.name, versioned.version, versioned.id)

    override fun toString(): String =
        "$name v$version" + (id?.let { " ($it)" } ?: "")
}

class NoSuchVersionException(versionSelection: VersionSelection, note: String) :
    Exception("$note: No version ${versionSelection.version ?: "*"} of ${versionSelection.name} found")
