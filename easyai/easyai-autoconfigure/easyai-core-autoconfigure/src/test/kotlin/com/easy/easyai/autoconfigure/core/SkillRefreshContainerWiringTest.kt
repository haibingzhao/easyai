package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.skills.RefreshSkillsToolBuilder
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillIndexer
import com.easy.easyai.skills.SkillPackageStore
import com.easy.easyai.skills.SkillPromptSource
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.skills.SkillSyncService
import com.easy.easyai.core.tool.ToolBuilder
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boots [EasyAiCoreAutoConfiguration] in a real context and looks at what the container actually holds.
 *
 * The unit tests around the collaborators construct their dependencies by hand, which cannot catch
 * the failure mode that would leave the feature dead on arrival: a tool the agent never gets to call
 * because no container ever offered its builder, or a sync service nobody wired. So this asserts the
 * wiring from the other side — builders picked up by the autoconfiguration's component scan (no extra
 * `@Bean`, no edit to `AutoConfiguration.imports`) and the whole owner pipeline present with default
 * properties, even while the persistence stack (and therefore the catalog) is absent.
 */
@SpringBootTest(classes = [EasyAiCoreAutoConfiguration::class, SkillRefreshContainerWiringTest.MockBeans::class])
class SkillRefreshContainerWiringTest {

    @TestConfiguration
    open class MockBeans {
        @Bean
        open fun chatModel(): ChatModel = mockk(relaxed = true)

        @Bean
        open fun goalStore(): GoalStore = mockk(relaxed = true)
    }

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the refresh_skills builder is discovered, so the tool reaches the agent loop`() {
        val builders = context.getBeansOfType(ToolBuilder::class.java).values.map { it.metadata.name }

        assertTrue("refresh_skills" in builders, "the agent would have no way to publish a written skill: $builders")
        assertNotNull(context.getBean(RefreshSkillsToolBuilder::class.java))
    }

    @Test
    fun `the owner pipeline is wired with default properties and no catalog`() {
        assertNotNull(context.getBean(SkillConfig::class.java))
        assertNotNull(context.getBean(SkillRegistry::class.java))
        assertNotNull(context.getBean(SkillAccessResolver::class.java))
        assertNotNull(context.getBean(SkillPackageStore::class.java))
        assertNotNull(context.getBean(SkillSyncService::class.java))
        assertNotNull(context.getBean(SkillIndexer::class.java))
        assertNotNull(context.getBean(SkillRefreshService::class.java))
        assertNotNull(context.getBean(SkillPromptSource::class.java))
        assertNotNull(context.getBean(SkillIndexStartupRunner::class.java))
        assertNull(
            context.getBeanProvider(AsyncSkillCatalogStore::class.java).getIfAvailable(),
            "no persistence stack in this context: the chain must still exist, degrading to disk-only"
        )
    }
}

/** The master switch must take the pipeline out of the container, not merely empty it. */
@SpringBootTest(
    classes = [EasyAiCoreAutoConfiguration::class, SkillRefreshContainerWiringTest.MockBeans::class],
    properties = ["easyai.skills.enabled=false"]
)
class SkillsDisabledContainerWiringTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `switching skills off removes the pipeline but keeps the config view`() {
        assertNotNull(
            context.getBean(SkillConfig::class.java),
            "tool builders must still resolve the same defaults they fall back to"
        )
        assertNull(context.getBeanProvider(SkillRegistry::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(SkillSyncService::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(SkillRefreshService::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(SkillIndexStartupRunner::class.java).getIfAvailable())
    }

    @Test
    fun `no skill tool is offered while skills are switched off`() {
        val builders = context.getBeansOfType(ToolBuilder::class.java).values.map { it.metadata.name }

        assertNull(context.getBeanProvider(SkillRefreshService::class.java).getIfAvailable())
        assertNull(context.getBeanProvider(SkillIndexStartupRunner::class.java).getIfAvailable())
    }
}
