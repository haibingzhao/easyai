package com.easy.easyai.autoconfigure.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 测试 Spring Boot Configuration Metadata 文件的格式和内容
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
        
        // 简单的 JSON 验证：检查是否以 { 开始，以 } 结束
        assertTrue(content.trim().startsWith("{"), "JSON should start with {")
        assertTrue(content.trim().endsWith("}"), "JSON should end with }")
        
        // 检查必需的关键字
        assertTrue(content.contains("\"properties\""), "JSON should contain 'properties' key")
        assertTrue(content.contains("\"hints\""), "JSON should contain 'hints' key")
    }

    @Test
    fun `test metadata contains easyai system properties`() {
        val content = metadataFile.readText()
        
        // 检查是否包含一些关键的配置项
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
        
        // 检查每个 property 是否有必需的字段
        val propertyPattern = Regex("\\{[^}]*\"name\"[^}]*\"type\"[^}]*\"description\"[^}]*\\}")
        val matches = propertyPattern.findAll(content)
        
        assertTrue(matches.count() > 0, "Should have at least one property with name, type, and description")
    }

    @Test
    fun `test metadata hints have required fields`() {
        val content = metadataFile.readText()
        
        // 检查 hints 部分是否存在且有 values
        assertTrue(
            content.contains("\"values\""),
            "Hints should contain 'values' field"
        )
    }

    @Test
    fun `test otel protocol has value hints`() {
        val content = metadataFile.readText()
        
        // 检查 otel protocol 是否有值提示
        assertTrue(
            content.contains("http/protobuf") && content.contains("grpc"),
            "OTEL protocol should have http/protobuf and grpc as hint values"
        )
    }

    @Test
    fun `test logging level has value hints`() {
        val content = metadataFile.readText()
        
        // 检查 logging level 是否有值提示
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
        
        // 文件大小应该在合理范围内（1KB - 100KB）
        assertTrue(fileSize > 1024, "Metadata file should be larger than 1KB")
        assertTrue(fileSize < 102400, "Metadata file should be smaller than 100KB")
    }
}
