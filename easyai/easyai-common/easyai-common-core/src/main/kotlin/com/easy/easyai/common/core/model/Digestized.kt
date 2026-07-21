package com.easy.easyai.common.core.model

/**
 * Interface for objects that require digest computation.
 */
interface Digestized {

    /**
     * The digest of the object.
     * This is a hash of the object's contents.
     */
    val digest: String?

}
