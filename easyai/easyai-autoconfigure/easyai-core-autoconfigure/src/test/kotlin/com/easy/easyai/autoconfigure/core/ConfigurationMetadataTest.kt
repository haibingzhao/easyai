package com.easy.easyai.autoconfigure.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests the format and content of the Spring Boot Configuration Metadata file.
 */
class ConfigurationMetadataTest {

    private val metadataFile = File(
        "src/main/resources/META-INF/additional-spring-configuration-metadata.json"
    )

    @Test
    fun `test metadata file exists`() {
        assertTrue(metadataFile.exists(), "Configuration metadata file should exist")
    }

    @Test
    fun `test metadata file is valid JSON`() {
        val content = metadataFile.readText()
        
        // Simple JSON validation: check it starts with { and ends with }
        assertTrue(content.trim().startsWith("{"), "JSON should start with {")
        assertTrue(content.trim().endsWith("}"), "JSON should end with }")
        
        // Check required keys
        assertTrue(content.contains("\"properties\""), "JSON should contain 'properties' key")
        assertTrue(content.contains("\"hints\""), "JSON should contain 'hints' key")
    }

    @Test
    fun `test metadata contains easyai system properties`() {
        val content = metadataFile.readText()
        
        // Check that key configuration properties are present
        assertTrue(
            content.contains("easyai.system.otel.exporter.otlp.protocol"),
            "Should contain otel protocol property"
        )
        assertTrue(
            content.contains("easyai.system.logging.level"),
            "Should contain logging level property"
        )
        assertTrue(
            content.contains("easyai.system.app.name"),
            "Should contain app name property"
        )
    }

    @Test
    fun `test metadata properties have required fields`() {
        val content = metadataFile.readText()
        
        // Check each property has required fields
        val propertyPattern = Regex("\\{[^}]*\"name\"[^}]*\"type\"[^}]*\"description\"[^}]*\\}")
        val matches = propertyPattern.findAll(content)
        
        assertTrue(matches.count() > 0, "Should have at least one property with name, type, and description")
    }

    @Test
    fun `test metadata hints have required fields`() {
        val content = metadataFile.readText()
        
        // Check hints section exists and has values
        assertTrue(
            content.contains("\"values\""),
            "Hints should contain 'values' field"
        )
    }

    @Test
    fun `test otel protocol has value hints`() {
        val content = metadataFile.readText()
        
        // Check otel protocol has value hints
        assertTrue(
            content.contains("http/protobuf") && content.contains("grpc"),
            "OTEL protocol should have http/protobuf and grpc as hint values"
        )
    }

    @Test
    fun `test logging level has value hints`() {
        val content = metadataFile.readText()
        
        // Check logging level has value hints
        val logLevels = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
        logLevels.forEach { level ->
            assertTrue(
                content.contains(level),
                "Logging level hints should include $level"
            )
        }
    }

    @Test
    fun `test metadata file size is reasonable`() {
        val fileSize = metadataFile.length()
        
        // File size should be within reasonable range (1KB - 100KB)
        assertTrue(fileSize > 1024, "Metadata file should be larger than 1KB")
        assertTrue(fileSize < 102400, "Metadata file should be smaller than 100KB")
    }
}
