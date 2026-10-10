package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.goal.GoalStore
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Boots [EasyAiCoreAutoConfiguration] with no `ChatModel` bean in the container.
 *
 * Models are resolved per session from a `ModelProviderConfig` through the protocol factories, so a
 * host has nothing to register — but `agentService` used to demand a `ChatModel` unconditionally,
 * which made every host fail to start once the spring-ai autoconfiguration that supplied that bean
 * (from a `placeholder-not-used` api-key) was gone.
 */
@SpringBootTest(classes = [EasyAiCoreAutoConfiguration::class, AgentServiceWithoutChatModelTest.MockBeans::class])
class AgentServiceWithoutChatModelTest {

    @TestConfiguration
    open class MockBeans {
        @Bean
        open fun goalStore(): GoalStore = mockk(relaxed = true)
    }

    @Autowired
    private lateinit var agentService: AgentService

    @Test
    fun `the agent service is created and simply has no fallback model`() {
        assertNotNull(agentService)
        assertNull(agentService.defaultChatModel, "no host bean: the fallback stays unset")
    }
}
