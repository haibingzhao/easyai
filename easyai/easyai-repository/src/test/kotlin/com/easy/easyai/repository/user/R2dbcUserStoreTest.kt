package com.easy.easyai.repository.user

import com.easy.easyai.auth.model.User
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [R2dbcUserStore] is the single source of application ownership: a username is unique inside one
 * `owner_app`, and every lookup is scoped by it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcUserStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///user_store_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcUserStore(db)

    private fun user(username: String, ownerApp: String, displayName: String = username) = User(
        id = UUID.randomUUID().toString(),
        ownerApp = ownerApp,
        username = username,
        displayName = displayName,
        passwordHash = "hash"
    )

    @Nested
    inner class `ownership scoping` {

        @Test
        fun `the same username can be registered by two applications`() = runTest {
            val home = user("admin", "home-api", displayName = "Home Admin")
            val trading = user("admin", "trading-api", displayName = "Trading Admin")

            store().save(home)
            store().save(trading)

            assertEquals(home, store().findByUsername("admin", "home-api"))
            assertEquals(trading, store().findByUsername("admin", "trading-api"))
        }

        @Test
        fun `registering the same username twice inside one application is rejected`() = runTest {
            store().save(user("dup-alice", "home-api"))

            assertThrows<Exception> { store().save(user("dup-alice", "home-api")) }
        }

        @Test
        fun `findById does not cross the application boundary`() = runTest {
            val alice = user("id-alice", "home-api")
            store().save(alice)

            assertNotNull(store().findById(alice.id, "home-api"))
            assertNull(store().findById(alice.id, "trading-api"))
        }

        @Test
        fun `findByUsername does not cross the application boundary`() = runTest {
            store().save(user("name-alice", "home-api"))

            assertNull(store().findByUsername("name-alice", "trading-api"))
        }

        @Test
        fun `count only counts accounts of one application`() = runTest {
            store().save(user("count-alice", "count-home"))
            store().save(user("count-bob", "count-home"))
            store().save(user("count-carol", "count-trading"))

            assertEquals(2, store().count("count-home"))
            assertEquals(1, store().count("count-trading"))
            assertEquals(0, store().count("count-agent"))
        }

        @Test
        fun `update never moves an account to another application`() = runTest {
            val alice = user("update-alice", "home-api")
            store().save(alice)

            store().update(alice.copy(ownerApp = "trading-api", displayName = "Renamed"))

            val persisted = store().findById(alice.id, "home-api")
            assertEquals("Renamed", persisted?.displayName)
            assertEquals("home-api", persisted?.ownerApp)
            assertNull(store().findById(alice.id, "trading-api"))
        }

        @Test
        fun `save persists the owning application`() = runTest {
            val alice = user("save-alice", "trading-api")
            store().save(alice)

            val persisted = suspendTransaction(db) {
                Tables.UserTable.selectAll()
                    .where { Tables.UserTable.id eq alice.id }
                    .limit(1)
                    .firstOrNull()
            }

            assertNotNull(persisted)
            assertEquals("trading-api", persisted[Tables.UserTable.ownerApp])
        }
    }
}
