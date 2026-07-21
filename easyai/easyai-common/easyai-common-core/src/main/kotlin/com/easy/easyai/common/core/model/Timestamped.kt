package com.easy.easyai.common.core.model

import java.time.Instant

/**
 * Enables consistent handling of timestamps across different types of data.
 */
interface Timestamped {

    val timestamp: Instant

    /**
     * Returns true if this object is newer than the other, based on their timestamps.
     */
    infix fun isLaterThan(other: Timestamped): Boolean {
        return this.timestamp.isAfter(other.timestamp)
    }
}
