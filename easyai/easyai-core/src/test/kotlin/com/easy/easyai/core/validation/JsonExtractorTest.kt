package com.easy.easyai.core.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [JsonExtractor] fence handling: a fenced payload must be picked out of surrounding
 * prose whatever its info string says, and a fence that carries no JSON must not cost the answer
 * the model gave outside the fence.
 */
class JsonExtractorTest {

    @Test
    fun `picks fenced object out of surrounding prose`() {
        val text = "分析如下：\n```json\n{\"risk\":\"high\"}\n```\n以上结论基于命令输出。"
        assertEquals("""{"risk":"high"}""", JsonExtractor.extract(text))
    }

    @Test
    fun `ignores the fence info string whatever its case or dialect`() {
        listOf("```JSON", "```jsonc", "```json5", "```", "``` json ").forEach { opener ->
            val text = "答案 $opener\n{\"a\":1}\n``` 完"
            assertEquals("""{"a":1}""", JsonExtractor.extract(text), "opener=$opener")
        }
    }

    @Test
    fun `takes the first fence that parses and keeps earlier prose out`() {
        val text = "```text\nnot json\n```\n```json\n{\"b\":2}\n```"
        assertEquals("""{"b":2}""", JsonExtractor.extract(text))
    }

    @Test
    fun `skips a malformed fence and still returns the next fenced payload`() {
        val text = "```json\n{\"a\":1,}\n```\n```json\n{\"b\":2}\n```"
        assertEquals("""{"b":2}""", JsonExtractor.extract(text))
    }

    @Test
    fun `does not read a scalar quoted string as the fenced answer`() {
        val text = "```json\n\"只是一句话\"\n```\n真正的结果在框外：{\"a\":1}"
        assertEquals("""{"a":1}""", JsonExtractor.extract(text))
    }

    @Test
    fun `falls back to bare json when the only fence holds code`() {
        val text = "参考 ```python\nprint(1)\n```\n{\"risk\":\"high\"}"
        assertEquals("""{"risk":"high"}""", JsonExtractor.extract(text))
    }

    @Test
    fun `reads fenced array and unescaped braces inside strings`() {
        val text = "```json\n[{\"cmd\":\"rm -rf {\"}]\n```"
        assertEquals("""[{"cmd":"rm -rf {"}]""", JsonExtractor.extract(text))
    }

    @Test
    fun `extracts bare json without any fence`() {
        assertEquals("""{"a":1}""", JsonExtractor.extract("  {\"a\":1} "))
        assertEquals("""[1,2]""", JsonExtractor.extract("[1,2]"))
    }

    @Test
    fun `returns null when nothing parses as json`() {
        assertNull(JsonExtractor.extract("抱歉，我无法判断风险。"))
        assertNull(JsonExtractor.extract("```json\n{\"a\":1,}\n```"))
    }
}
