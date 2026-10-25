package com.easy.easyai.repository.push

import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the `push_subscription` contract against a real H2 schema.
 *
 * The rebind case matters most: one browser endpoint must have exactly one owner, so a login
 * switch has to update the existing row (keeping its id / created_at) rather than insert a
 * second row or fail on the unique index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcPushSubscriptionStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        // MODE=MYSQL mirrors production (the desktop and default in-memory URLs both set it).
        // Exposed switches upsert handling to the MySQL function provider in this mode, so a
        // plain-H2 test would not exercise the rebind path the server actually runs.
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///push_subscription_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcPushSubscriptionStore(db)

    private fun uid(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun subscription(
        userId: String,
        endpoint: String = "https://web.push.apple.com/${UUID.randomUUID()}",
        userAgent: String? = "iPhone",
    ) = PushSubscription(
        userId = userId,
        endpoint = endpoint,
        p256dh = "BP-public-key",
        auth = "auth-secret",
        userAgent = userAgent,
    )

    @Nested
    inner class Upsert {

        @Test
        fun `a new endpoint gets an id and timestamps`() = runTest {
            val s = store()
            val user = uid("user")
            val endpoint = "https://web.push.apple.com/new-${UUID.randomUUID()}"
            s.upsert(subscription(user, endpoint))

            val saved = s.findByEndpoint(endpoint)
            assertNotNull(saved)
            assertEquals(true, saved.id.isNotBlank())
            assertEquals(true, saved.createdAt > 0)
            assertEquals(true, saved.updatedAt >= saved.createdAt)
        }

        @Test
        fun `the same endpoint signed in by another account is rebound in place`() = runTest {
            val s = store()
            val firstUser = uid("user")
            val secondUser = uid("user")
            val endpoint = "https://web.push.apple.com/rebind-${UUID.randomUUID()}"
            s.upsert(subscription(firstUser, endpoint))
            val first = requireNotNull(s.findByEndpoint(endpoint))
            s.upsert(subscription(secondUser, endpoint, userAgent = "iPad"))

            val loaded = s.findByEndpoint(endpoint)
            assertNotNull(loaded)
            assertEquals(first.id, loaded.id)
            assertEquals(first.createdAt, loaded.createdAt)
            assertEquals(secondUser, loaded.userId)
            assertEquals("iPad", loaded.userAgent)
            assertEquals(true, loaded.updatedAt >= first.updatedAt)
            assertNull(s.findByUserId(firstUser).firstOrNull())
        }

        @Test
        fun `the same endpoint re-subscribed keeps one row`() = runTest {
            val s = store()
            val user = uid("user")
            val endpoint = "https://web.push.apple.com/same-${UUID.randomUUID()}"
            s.upsert(subscription(user, endpoint))
            s.upsert(subscription(user, endpoint, userAgent = "iPhone 17"))

            val rows = s.findByUserId(user)
            assertEquals(1, rows.size)
            assertEquals("iPhone 17", rows.single().userAgent)
        }
    }

    @Nested
    inner class Reads {

        @Test
        fun `devices are listed most recently updated first`() = runTest {
            val s = store()
            val user = uid("user")
            val endpointA = "https://web.push.apple.com/a-${UUID.randomUUID()}"
            val endpointB = "https://web.push.apple.com/b-${UUID.randomUUID()}"
            s.upsert(subscription(user, endpointA))
            val first = requireNotNull(s.findByEndpoint(endpointA))
            Thread.sleep(5)
            s.upsert(subscription(user, endpointB))
            Thread.sleep(5)
            // Rebinding A makes it the most recent row.
            s.upsert(subscription(user, endpointA))
            val rebound = requireNotNull(s.findByEndpoint(endpointA))

            val rows = s.findByUserId(user)
            assertEquals(listOf(endpointA, endpointB), rows.map { it.endpoint })
            assertEquals(first.id, rebound.id)
            assertEquals(true, rebound.updatedAt > first.updatedAt)
        }

        @Test
        fun `the limit is applied and at least one row is requested`() = runTest {
            val s = store()
            val user = uid("user")
            repeat(3) { index -> s.upsert(subscription(user, "https://web.push.apple.com/limit-$index")) }

            assertEquals(2, s.findByUserId(user, 2).size)
            assertEquals(1, s.findByUserId(user, 0).size)
        }

        @Test
        fun `count and lookup are scoped to the account`() = runTest {
            val s = store()
            val user = uid("user")
            val endpoint = "https://web.push.apple.com/scoped-${UUID.randomUUID()}"
            s.upsert(subscription(user, endpoint))

            assertEquals(1, s.countByUserId(user))
            assertEquals(0, s.countByUserId(uid("other")))
            assertNotNull(s.findByEndpoint(endpoint))
            assertNull(s.findByEndpoint("https://web.push.apple.com/missing"))
        }
    }

    @Nested
    inner class Deletes {

        @Test
        fun `an account cannot delete another account's endpoint`() = runTest {
            val s = store()
            val owner = uid("owner")
            val endpoint = "https://web.push.apple.com/owned-${UUID.randomUUID()}"
            s.upsert(subscription(owner, endpoint))

            assertEquals(0, s.deleteByUserAndEndpoint(uid("other"), endpoint))
            assertNotNull(s.findByEndpoint(endpoint))

            assertEquals(1, s.deleteByUserAndEndpoint(owner, endpoint))
            assertNull(s.findByEndpoint(endpoint))
        }

        @Test
        fun `a dead endpoint is removed by row id`() = runTest {
            val s = store()
            val endpoint = "https://web.push.apple.com/dead-${UUID.randomUUID()}"
            s.upsert(subscription(uid("user"), endpoint))
            val row = requireNotNull(s.findByEndpoint(endpoint))

            assertEquals(1, s.deleteById(row.id))
            assertEquals(0, s.deleteById(row.id))
            assertNull(s.findByEndpoint(endpoint))
        }
    }
}
