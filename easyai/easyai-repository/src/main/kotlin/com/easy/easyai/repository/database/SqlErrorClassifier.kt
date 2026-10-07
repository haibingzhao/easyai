package com.easy.easyai.repository.database

/**
 * Classifies driver-level SQL failures so callers can react to a specific constraint
 * instead of swallowing every error.
 *
 * R2DBC drivers wrap the vendor error differently per database (H2, PostgreSQL), so the
 * check walks the whole cause chain and matches on the SQLSTATE / message text.
 */
internal object SqlErrorClassifier {

    /** True when [error] (or any of its causes) is a unique constraint / duplicate key violation. */
    fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (message.contains("23505") || message.contains("unique index") ||
                message.contains("unique constraint") || message.contains("duplicate key")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
