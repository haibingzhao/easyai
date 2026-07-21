package com.easy.easyai.common.core.model

@JvmInline
value class Semver(val value: String = DEFAULT_VERSION) {
    init {
        require(value.isNotBlank()) { "Semver must not be blank" }
        require(value.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[a-zA-Z0-9]+)?$"))) {
            "Semver must be in the format X.Y.Z or X.Y.Z-alpha"
        }
    }

    constructor(major: Int = 0, minor: Int = 1, patch: Int = 0) : this(
        "$major.$minor.$patch",
    )

    override fun toString(): String = value

    companion object {
        const val DEFAULT_VERSION = "0.1.0-SNAPSHOT"

        @JvmStatic
        fun of(major: Int, minor: Int, patch: Int): Semver = Semver(major, minor, patch)
    }
}

/**
 * A versioned asset has a name and a version.
 * The combination should be unique, but there is also an id.
 * Each version is immutable.
 */
interface AssetCoordinates : Named {

    /**
     * Provider of the asset. New versions can be added with the same name.
     */
    val provider: String

    /**
     * Name of the asset.
     * New versions can be added with the same name.
     * The combination of provider and name should be unique.
     */
    override val name: String

    val version: Semver

}
