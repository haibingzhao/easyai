package com.easy.easyai.common.core.model

/**
 * Enable consistent usage among named objects
 */
interface Named {

    val name: String
}

interface Described {

    val description: String
}

interface NamedAndDescribed : Named, Described {

    companion object {
        /**
         * Creates a [NamedAndDescribed] instance with the given name and description.
         */
        operator fun invoke(name: String, description: String): NamedAndDescribed =
            NamedAndDescribedImpl(name, description)
    }
}

private data class NamedAndDescribedImpl(
    override val name: String,
    override val description: String,
) : NamedAndDescribed
