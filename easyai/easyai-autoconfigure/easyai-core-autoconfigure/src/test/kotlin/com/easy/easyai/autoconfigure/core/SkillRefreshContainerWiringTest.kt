package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.skills.RefreshSkillsToolBuilder
import com.easy.easyai.skills.SkillRefreshService
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
 * The unit tests around `refresh_skills` construct the builder by hand, which cannot catch the failure
 * mode that would leave the whole feature dead on arrival: a tool the agent never gets to call because
 * no container ever offered its builder. So this asserts the wiring from the other side — the builder is
 * picked up by the autoconfiguration's component scan (no extra `@Bean`, no edit to
 * `AutoConfiguration.imports`), while the catalog/index services behind it stay gated on
 * `easyai.skills.rag.enabled`, which is off by default.
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
    fun `the catalog and index services stay out of the container while skill rag is off`() {
        assertNull(
            context.getBeanProvider(SkillRefreshService::class.java).getIfAvailable(),
            "without the flag there is no catalog and no index to keep in step"
        )
        assertNull(
            context.getBeanProvider(SkillIndexStartupRunner::class.java).getIfAvailable(),
            "the listener exists only to run the refresh chain"
        )
    }
}
