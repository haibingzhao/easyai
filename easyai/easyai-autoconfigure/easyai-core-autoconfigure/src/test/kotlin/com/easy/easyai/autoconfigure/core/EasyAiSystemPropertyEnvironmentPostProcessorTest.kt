package com.easy.easyai.autoconfigure.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EasyAiSystemPropertyEnvironmentPostProcessorTest {

    private val processor = EasyAiSystemPropertyEnvironmentPostProcessor()
    private val environment = mockk<ConfigurableEnvironment>(relaxed = true)
    private val application = mockk<SpringApplication>(relaxed = true)
    private val propertySources = MutablePropertySources()

    @BeforeEach
    fun setUp() {
        // Clear any pre-existing system properties
        System.clearProperty("otel.exporter.otlp.protocol")
        System.clearProperty("custom.test.property")
        
        every { environment.propertySources } returns propertySources
    }

    @AfterEach
    fun tearDown() {
        // Clean up system properties set during the test
        System.clearProperty("otel.exporter.otlp.protocol")
        System.clearProperty("custom.test.property")
    }

    @Test
    fun `test easyai system properties are registered to System Properties`() {
        // Prepare test data
        val props = mapOf(
            "easyai.system.otel.exporter.otlp.protocol" to "http/protobuf",
            "easyai.system.custom.test.property" to "test-value",
            "other.property" to "should-not-be-registered"
        )
        propertySources.addFirst(MapPropertySource("test-properties", props))

        // Execute the processor
        processor.postProcessEnvironment(environment, application)

        // Verify system properties are correctly set
        assertEquals("http/protobuf", System.getProperty("otel.exporter.otlp.protocol"))
        assertEquals("test-value", System.getProperty("custom.test.property"))
        assertNull(System.getProperty("other.property"))
    }

    @Test
    fun `test existing system properties are not overwritten`() {
        // Pre-set a system property
        System.setProperty("otel.exporter.otlp.protocol", "existing-value")

        val props = mapOf(
            "easyai.system.otel.exporter.otlp.protocol" to "new-value"
        )
        propertySources.addFirst(MapPropertySource("test-properties", props))

        // Execute the processor
        processor.postProcessEnvironment(environment, application)

        // Verify existing value is not overwritten
        assertEquals("existing-value", System.getProperty("otel.exporter.otlp.protocol"))
    }

    @Test
    fun `test no easyai system properties does nothing`() {
        val props = mapOf(
            "other.property1" to "value1",
            "another.property" to "value2"
        )
        propertySources.addFirst(MapPropertySource("test-properties", props))

        // Execute the processor
        processor.postProcessEnvironment(environment, application)

        // Verify no system properties were set
        assertNull(System.getProperty("otel.exporter.otlp.protocol"))
        assertNull(System.getProperty("custom.test.property"))
    }

    @Test
    fun `test empty prefix properties are handled correctly`() {
        val props = mapOf(
            "easyai.system." to "empty-key-value"
        )
        propertySources.addFirst(MapPropertySource("test-properties", props))

        // Execute the processor - should not throw; empty keys should be skipped
        processor.postProcessEnvironment(environment, application)
    }
}
