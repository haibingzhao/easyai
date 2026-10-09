package com.easy.easyai.repository.config

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group sharing turns the model-config read from "mine + system" into "mine + my group's + system".
 * The security-critical invariant: a member of group A must never see group B's rows, and the
 * single-id read form must keep its exact pre-group meaning (self + system only).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcModelConfigStoreGroupReadTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///model_cfg_group_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcModelConfigStore(db)

    private fun cfg(id: String) = ModelProviderConfig(
        id = id, name = id, protocol = Protocol.OPENAI, isCustom = true, modelId = "m-$id"
    )

    private fun uid(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    @Test
    fun `owners read sees self and group and system but never another group`() = runTest {
        val s = store()
        val self = uid("alice")
        val group = uid("grpA")
        val otherGroup = uid("grpB")

        s.saveConfig(cfg("c-self-$self"), self)
        s.saveConfig(cfg("c-grpA-$group"), group)
        s.saveConfig(cfg("c-sys-$self"), "system")
        s.saveConfig(cfg("c-grpB-$otherGroup"), otherGroup)

        val visible = s.getModelConfigs(ModelType.CHAT, listOf(self, group, "system")).map { it.id }.toSet()

        assertTrue("c-self-$self" in visible, "own row visible")
        assertTrue("c-grpA-$group" in visible, "group bucket row visible to a member")
        assertTrue("c-sys-$self" in visible, "shared system row visible")
        assertFalse("c-grpB-$otherGroup" in visible, "another group's row must be isolated")
    }

    @Test
    fun `single-id read still means self plus system only`() = runTest {
        val s = store()
        val self = uid("bob")
        val group = uid("grpA")

        s.saveConfig(cfg("c2-self-$self"), self)
        s.saveConfig(cfg("c2-grp-$group"), group)

        val visible = s.getModelConfigs(ModelType.CHAT, self).map { it.id }.toSet()

        assertTrue("c2-self-$self" in visible)
        assertFalse("c2-grp-$group" in visible, "a plain self read must not pull in the group bucket")
    }

    @Test
    fun `getConfig by owners finds a group-owned row that a self read cannot`() = runTest {
        val s = store()
        val self = uid("carol")
        val group = uid("grpA")
        val id = "c3-grp-$group"
        s.saveConfig(cfg(id), group)

        assertNotNull(s.getConfig(id, listOf(self, group, "system")))
        assertNull(s.getConfig(id, self), "self-only visibility must not resolve a group-owned row")
    }
}
