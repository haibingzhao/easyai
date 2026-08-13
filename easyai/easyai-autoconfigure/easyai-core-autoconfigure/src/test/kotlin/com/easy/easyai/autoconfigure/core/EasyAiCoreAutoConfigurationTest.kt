package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.memory.MemoryStore
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the "memory is RAG-only" decision at the core auto-configuration level:
 * - [EasyAiCoreAutoConfiguration] must not declare a file-based [MemoryStore] bean.
 * - [MemoryProperties] must not expose file path settings.
 *
 * The actual bean wiring (no MemoryStore without `easyai.rag.enabled=true`) is
 * covered by RagAutoConfigurationTest's condition-matrix tests.
 */
class EasyAiCoreAutoConfigurationTest {

    @Test
    fun `core auto-configuration no longer declares a memoryStore bean`() {
        val memoryStoreMethods = EasyAiCoreAutoConfiguration::class.java.declaredMethods
            .filter { MemoryStore::class.java.isAssignableFrom(it.returnType) }
        assertTrue(
            memoryStoreMethods.isEmpty(),
            "File-based memoryStore bean must not exist, found: $memoryStoreMethods"
        )
    }

    @Test
    fun `MemoryProperties no longer exposes file path settings`() {
        val fieldNames = MemoryProperties::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fieldNames.contains("enabled"))
        assertFalse(fieldNames.contains("globalDir"), "globalDir must be removed")
        assertFalse(fieldNames.contains("projectDir"), "projectDir must be removed")
    }
}
