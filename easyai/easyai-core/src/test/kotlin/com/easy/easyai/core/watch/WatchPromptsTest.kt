package com.easy.easyai.core.watch

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The finding schema is fed to providers as is. An empty `enum` is invalid in several of them, so a
 * watch without tags must get a schema that only admits an empty tags array instead.
 */
class WatchPromptsTest {

    @Test
    fun `a tag-less watch gets a schema that only admits an empty tag array`() {
        val schema = WatchPrompts.findingSchema(emptyList())
        assertTrue(schema.contains("\"maxItems\": 0"))
        assertFalse(schema.contains("\"enum\": []"))
    }

    @Test
    fun `the tag universe is encoded as the item enum`() {
        val schema = WatchPrompts.findingSchema(
            listOf(WatchTag("crossed", "已突破"), WatchTag("dropped", "已回落"))
        )
        assertTrue(schema.contains("\"enum\": [\"crossed\", \"dropped\"]"))
        assertTrue(schema.contains("\"maxItems\": 8"))
    }
}
