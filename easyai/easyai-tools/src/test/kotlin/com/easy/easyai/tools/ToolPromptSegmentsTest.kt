package com.easy.easyai.tools

import com.easy.easyai.tools.background.BackgroundTaskManagerRegistry
import com.easy.easyai.tools.background.RunBackgroundToolBuilder
import com.easy.easyai.tools.calc.ScriptCalcToolBuilder
import com.easy.easyai.tools.knowledge.KnowledgeSearchToolBuilder
import com.easy.easyai.tools.memory.MemorySearchToolBuilder
import com.easy.easyai.tools.visual.RenderVisualToolBuilder
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Content assertions for the system-prompt guidance segments that tools declare via
 * ToolMetadata.systemPromptSegment (moved out of PromptTemplateService so each tool owns
 * its own guidance).
 */
class ToolPromptSegmentsTest {

    private fun segment(value: String?): String {
        assertNotNull(value, "builder must declare a systemPromptSegment")
        return value
    }

    @Test
    fun `calc segment teaches on-demand time access without a concrete timestamp`() {
        val guidance = segment(ScriptCalcToolBuilder().metadata.systemPromptSegment)
        assertTrue(guidance.contains("## Current Time"), guidance)
        assertTrue(guidance.contains("ZonedDateTime.now().toString()"), guidance)
        assertFalse(guidance.contains("Current date and time:"), guidance)
    }

    @Test
    fun `memory segment covers stale entry update and remove rules without concrete dates`() {
        val guidance = segment(MemorySearchToolBuilder().metadata.systemPromptSegment)
        assertTrue(guidance.contains("## Memory"), guidance)
        assertTrue(guidance.contains("memory_search"), guidance)
        assertTrue(guidance.contains("### Keeping memories current"), guidance)
        assertTrue(guidance.contains("action='update'"), guidance)
        assertTrue(guidance.contains("action='remove'"), guidance)
        assertTrue(guidance.contains("SAME response"), guidance)
        // Staleness rules must stay free of concrete dates to keep the prompt cache-stable.
        assertFalse(Regex("\\d{4}").containsMatchIn(guidance), guidance)
    }

    @Test
    fun `knowledge segment guides parallel issuance together with memory search`() {
        val guidance = segment(KnowledgeSearchToolBuilder().metadata.systemPromptSegment)
        assertTrue(guidance.contains("## Knowledge Base"), guidance)
        assertTrue(guidance.contains("knowledge_search"), guidance)
        assertTrue(guidance.contains("SAME response"), guidance)
    }

    @Test
    fun `run_background segment teaches async execution via task tools`() {
        val builder = RunBackgroundToolBuilder(mockk<BackgroundTaskManagerRegistry>(relaxed = true))
        val guidance = segment(builder.metadata.systemPromptSegment)
        assertTrue(guidance.contains("## Background (Async) Tasks"), guidance)
        assertTrue(guidance.contains("run_background"), guidance)
        assertTrue(guidance.contains("task_status"), guidance)
    }

    /**
     * AgentLoopRunner sorts by promptSegmentOrder rather than trusting tool registration order, so
     * the system prompt prefix stays stable for LLM caching even if Spring bean ordering shifts.
     * Deliberately registers the builders out of order here.
     */
    @Test
    fun `segments sort into the declared order regardless of registration order`() {
        val registered = listOf(
            RenderVisualToolBuilder(),
            KnowledgeSearchToolBuilder(),
            RunBackgroundToolBuilder(mockk<BackgroundTaskManagerRegistry>(relaxed = true)),
            ScriptCalcToolBuilder(),
            MemorySearchToolBuilder()
        )

        val assembled = registered
            .sortedBy { it.metadata.promptSegmentOrder }
            .filter { it.metadata.systemPromptSegment != null }
            .map { it.metadata.name }

        assertEquals(
            listOf("calc", "memory_search", "knowledge_search", "run_background", "render_visual"),
            assembled
        )
    }
}
