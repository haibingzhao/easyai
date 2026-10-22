package com.easy.easyai.api.model

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReasoningProfilesTest {

    @Nested
    inner class `vendor families` {

        @Test
        fun `qwen3_8 is toggleable with xhigh default`() {
            val p = ReasoningProfiles.resolve("qwen3.8-max")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
            assertEquals(listOf("low", "medium", "xhigh"), p.effortLevels)
            assertTrue(p.defaultThinking)
            assertEquals("xhigh", p.defaultEffort)
        }

        @Test
        fun `qwen3_7 is toggleable without an effort knob`() {
            val p = ReasoningProfiles.resolve("qwen3.7-plus")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
            assertEquals(emptyList(), p.effortLevels)
            assertTrue(p.defaultThinking)
        }

        @Test
        fun `glm-5_3 is always on and defaults to max`() {
            val p = ReasoningProfiles.resolve("glm-5.3")
            assertEquals(ReasoningMode.ALWAYS_ON, p.mode)
            assertEquals(listOf("low", "high", "max"), p.effortLevels)
            assertEquals("max", p.defaultEffort)
            assertTrue(p.defaultThinking)
        }

        @Test
        fun `glm-5_2 stays toggleable`() {
            val p = ReasoningProfiles.resolve("glm-5.2")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
            assertEquals(listOf("high", "max"), p.effortLevels)
            assertEquals("high", p.defaultEffort)
        }

        @Test
        fun `deepseek-v4 uses high and max`() {
            val p = ReasoningProfiles.resolve("deepseek-v4-pro")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
            assertEquals(listOf("high", "max"), p.effortLevels)
            assertEquals("high", p.defaultEffort)
        }

        @Test
        fun `dated deepseek variants accept low as well`() {
            assertEquals(
                listOf("low", "high", "max"),
                ReasoningProfiles.resolve("deepseek-v4-flash-0731").effortLevels
            )
            assertEquals(
                listOf("low", "high", "max"),
                ReasoningProfiles.resolve("deepseek-v4-pro-0813").effortLevels
            )
        }

        @Test
        fun `qwen3-vl defaults thinking off and has no effort knob`() {
            val p = ReasoningProfiles.resolve("qwen3-vl-plus")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
            assertEquals(emptyList(), p.effortLevels)
            assertEquals(false, p.defaultThinking)
        }
    }

    @Nested
    inner class `normalization and fallback` {

        @Test
        fun `vendor prefix and case are stripped`() {
            val p = ReasoningProfiles.resolve("Bailian/Qwen3.8-Max")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
            assertEquals("xhigh", p.defaultEffort)
        }

        @Test
        fun `version suffix still matches the family`() {
            val p = ReasoningProfiles.resolve("qwen3.8-max-2026-09-01")
            assertEquals(ReasoningMode.TOGGLEABLE, p.mode)
        }

        @Test
        fun `unknown model with a catalog reasoning flag stays unknown`() {
            // The vendor flag cannot tell "toggleable" from "always on", so the UI keeps both editable.
            assertEquals(ReasoningMode.UNKNOWN, ReasoningProfiles.resolve("custom-llm-v2", catalogThinking = true).mode)
        }

        @Test
        fun `unknown model without reasoning is none`() {
            val p = ReasoningProfiles.resolve("custom-llm-v2", catalogThinking = false)
            assertEquals(ReasoningMode.NONE, p.mode)
            assertEquals(ReasoningProfile.SOURCE_CATALOG, p.source)
        }

        @Test
        fun `unknown model with no catalog row is unknown`() {
            assertEquals(ReasoningMode.UNKNOWN, ReasoningProfiles.resolve("custom-llm-v2", catalogThinking = null).mode)
        }
    }
}
