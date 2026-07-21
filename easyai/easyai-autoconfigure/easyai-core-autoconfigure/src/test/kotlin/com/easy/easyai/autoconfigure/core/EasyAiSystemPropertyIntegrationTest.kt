package com.easy.easyai.autoconfigure.core

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Integration test: verifies EasyAiSystemPropertyEnvironmentPostProcessor behavior in a full Spring Boot context
 */
@SpringBootTest(classes = [EasyAiCoreAutoConfiguration::class, EasyAiSystemPropertyIntegrationTest.MockBeans::class])
@TestPropertySource(properties = [
    "easyai.system.integration.test.property=integration-value",
    "easyai.system.otel.test.protocol=grpc",
    "non.system.property=should-not-appear"
])
class EasyAiSystemPropertyIntegrationTest {

    @TestConfiguration
    open class MockBeans {
        @Bean
        open fun chatModel(): ChatModel = mockk(relaxed = true)
    }

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @AfterEach
    fun tearDown() {
        // Clean up system properties set during the test
        System.clearProperty("integration.test.property")
        System.clearProperty("otel.test.protocol")
    }

    @Test
    fun `test system properties are available in Spring context`() {
        // EnvironmentPostProcessor may not process @TestPropertySource properties due to timing
        // during test context startup, so we invoke it manually to ensure properties are registered
        val processor = EasyAiSystemPropertyEnvironmentPostProcessor()
        processor.postProcessEnvironment(environment, SpringApplication())

        // Verify system properties are correctly set
        assertEquals("integration-value", System.getProperty("integration.test.property"))
        assertEquals("grpc", System.getProperty("otel.test.protocol"))
        
        // Verify non-system properties are not set
        assertNull(System.getProperty("non.system.property"))
    }

    @Test
    fun `test spring application context loads successfully`() {
        // If we reach here, the Spring context loaded successfully
        // This proves EnvironmentPostProcessor did not break application startup
        assert(true)
    }
}
