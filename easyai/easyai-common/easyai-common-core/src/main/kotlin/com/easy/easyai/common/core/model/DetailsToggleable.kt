package com.easy.easyai.common.core.model

/**
 * Interface for objects where we can request more detailed results
 */
interface DetailsToggleable {

    /**
     * Whether to show detailed results. Not all users have permissions to ask for this.
     */
    val showDetails: Boolean
}
