package com.easy.easyai.web.service

import io.r2dbc.spi.R2dbcException
import java.sql.SQLException

/** Postgres/H2 unique-violation SQLState. */
private const val UNIQUE_VIOLATION_SQLSTATE = "23505"

/** Postgres/H2 foreign-key-violation SQLState. */
private const val FOREIGN_KEY_VIOLATION_SQLSTATE = "23503"

/**
 * R2DBC drivers wrap the vendor error differently, so walk the whole cause chain. Exposed throws
 * [R2dbcException] (not a `java.sql.SQLException`), so both are checked; matching the English message
 * is only a fallback for a driver that sets neither.
 *
 * Used as the source of truth for concurrent insert races (folder names, upload file names).
 */
internal fun isUniqueViolation(error: Throwable): Boolean =
    hasSqlState(error, UNIQUE_VIOLATION_SQLSTATE) || anyCauseMessage(error) { message ->
        message.contains(UNIQUE_VIOLATION_SQLSTATE) ||
            message.contains("unique index") ||
            message.contains("unique constraint") ||
            message.contains("duplicate key")
    }

/** True when the database refused a write because a referenced row does not exist (or was deleted). */
internal fun isForeignKeyViolation(error: Throwable): Boolean =
    hasSqlState(error, FOREIGN_KEY_VIOLATION_SQLSTATE) || anyCauseMessage(error) { message ->
        message.contains(FOREIGN_KEY_VIOLATION_SQLSTATE) ||
            message.contains("foreign key constraint") ||
            message.contains("referential integrity")
    }

private fun hasSqlState(error: Throwable, sqlState: String): Boolean {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is R2dbcException -> if (current.sqlState == sqlState) return true
            is SQLException -> if (current.sqlState == sqlState) return true
        }
        current = current.cause
    }
    return false
}

private inline fun anyCauseMessage(error: Throwable, predicate: (String) -> Boolean): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (predicate(current.message.orEmpty().lowercase())) return true
        current = current.cause
    }
    return false
}
