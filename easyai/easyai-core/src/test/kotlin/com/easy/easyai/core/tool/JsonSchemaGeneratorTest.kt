package com.easy.easyai.core.tool

import com.easy.easyai.api.llm.schema.JsonSchemaGenerator
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

// Plain class, not a data class: array-valued componentN/equals would only add warnings.
class PPrimitiveArrays(val ints: IntArray, val longs: LongArray, val flags: BooleanArray, val names: Array<String>)

/**
 * Element-type guard for [JsonSchemaGenerator], covering the shapes the frozen snapshots in
 * [JsonSchemaGeneratorParityTest] do not: primitive arrays carry no Kotlin type arguments, so their
 * element type has to come from the JVM component class instead of falling back to `object`.
 */
class JsonSchemaGeneratorTest {

    private val schema = JsonMapper.builder().build()
        .readTree(JsonSchemaGenerator.generateForType(PPrimitiveArrays::class.java))

    private fun itemsType(property: String): String =
        schema.path("properties").path(property).path("items").path("type").asString()

    @Test
    fun `primitive arrays describe their scalar element type`() {
        assertEquals("integer", itemsType("ints"))
        assertEquals("integer", itemsType("longs"))
        assertEquals("boolean", itemsType("flags"))
    }

    @Test
    fun `boxed arrays keep using the declared type argument`() {
        assertEquals("string", itemsType("names"))
    }
}
