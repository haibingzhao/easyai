package com.easy.easyai.repository.database

import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.statements.SuspendStatementInterceptor
import org.slf4j.LoggerFactory

internal object TransactionEvents {
    private val logger = LoggerFactory.getLogger(TransactionEvents::class.java)

    fun afterCommit(transaction: R2dbcTransaction, action: () -> Unit) {
        transaction.registerInterceptor(object : SuspendStatementInterceptor {
            private var pending = true

            override suspend fun afterCommit(transaction: R2dbcTransaction) {
                if (!pending) return
                pending = false
                val outer = transaction.outerTransaction
                if (outer != null) {
                    // A savepoint commit is not durable; defer until the enclosing transaction commits.
                    afterCommit(outer, action)
                } else {
                    runCatching(action).onFailure {
                        logger.warn("Failed to publish transaction change after commit", it)
                    }
                }
            }

            override suspend fun afterRollback(transaction: R2dbcTransaction) {
                pending = false
            }
        })
    }
}
