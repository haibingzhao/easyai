package com.easy.easyai.api.llm.schema

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

/**
 * Generates the JSON Schema for a tool's parameter class, replacing Spring AI's
 * `util.json.schema.JsonSchemaGenerator` (which delegated to victools).
 *
 * The output is byte-for-byte compatible with the previous spring-ai/victools result for the
 * shapes easyai's tool parameter classes actually use — verified by a parity test that runs while
 * spring-ai is still on the classpath. Concretely it reproduces:
 * - Draft 2020-12 `$schema` at the root, `additionalProperties: false` on every object that has
 *   `properties`, and Jackson's `toPrettyString()` formatting (built with the same tools.jackson
 *   node types, so the serialization matches for free).
 * - Properties sorted alphabetically; `required` = non-nullable AND no default value.
 * - Kotlin nullable scalars/objects → `"type": ["X", "null"]`; enums → `{"type":"string","enum":…}`;
 *   `List/Set/Array` → `{"type":"array","items":…}`; `Map` → free-form `{"type":"object"}`.
 * - A nested type referenced more than once (or recursive) is hoisted to `$defs` and referenced via
 *   `$ref`; single-use nested types are inlined. A nullable `$ref` becomes
 *   `{"anyOf":[{"type":"null"},{"$ref":…}]}`; self-recursion references `"#"`.
 *
 * Only [Class] inputs are supported (that is all [com.easy.easyai.core.tool.BaseToolDefinition]
 * passes). Scalars keep their OpenAPI `format` (int32/int64/double/float) exactly as before.
 */
object JsonSchemaGenerator {

    private val nodeFactory = JsonNodeFactory.instance
    private const val SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema"

    fun generateForType(type: Class<*>): String {
        val root = type.kotlin
        val counts = LinkedHashMap<KClass<*>, Int>()
        val recursive = LinkedHashSet<KClass<*>>()
        val stack = ArrayList<KClass<*>>()
        stack.add(root)
        for (prop in propertiesOf(root)) {
            countRefs(prop.type, counts, recursive, stack)
        }
        // A nested type is hoisted to $defs when it is referenced more than once or is recursive
        // (recursion cannot be inlined). The root type is never hoisted; self-references use "#".
        val hoisted = counts.keys
            .filter { it != root && (counts.getValue(it) > 1 || it in recursive) }
            .toSet()

        val out = nodeFactory.objectNode()
        out.put("\$schema", SCHEMA_URI)
        if (hoisted.isNotEmpty()) {
            val defs = out.putObject("\$defs")
            for (kc in hoisted.sortedBy { it.java.simpleName }) {
                val defNode = defs.putObject(defKey(kc))
                if (kc.java.isEnum) {
                    buildEnum(kc, defNode, nullable = false)
                } else {
                    buildObject(kc, defNode, nullable = false, hoisted = hoisted, root = root)
                }
            }
        }
        if (root.java.isEnum) {
            buildEnum(root, out, nullable = false)
        } else {
            buildObject(root, out, nullable = false, hoisted = hoisted, root = root)
        }
        return out.toPrettyString()
    }

    private fun defKey(kc: KClass<*>): String = kc.java.simpleName

    private fun refPath(kc: KClass<*>): String = "#/\$defs/${defKey(kc)}"

    /** Walks the type graph counting references to each definable (enum/object) type. */
    private fun countRefs(
        type: KType,
        counts: MutableMap<KClass<*>, Int>,
        recursive: MutableSet<KClass<*>>,
        stack: MutableList<KClass<*>>
    ) {
        val kc = type.jvmErasure
        when {
            isScalar(kc) || isMap(kc) -> return
            isArrayLike(kc) -> elementType(type)?.let { countRefs(it, counts, recursive, stack) }
            else -> {
                counts[kc] = (counts[kc] ?: 0) + 1
                if (kc in stack) {
                    recursive.add(kc)
                    return
                }
                if (!kc.java.isEnum) {
                    stack.add(kc)
                    for (prop in propertiesOf(kc)) {
                        countRefs(prop.type, counts, recursive, stack)
                    }
                    stack.removeAt(stack.size - 1)
                }
            }
        }
    }

    private data class Prop(val name: String, val type: KType, val required: Boolean, val description: String?)

    private fun propertiesOf(kc: KClass<*>): List<Prop> {
        val ctor = kc.primaryConstructor
        val props = if (ctor != null) {
            ctor.parameters
                .filter { it.kind == KParameter.Kind.VALUE && it.name != null }
                .map {
                    Prop(
                        it.name!!,
                        it.type,
                        required = !it.type.isMarkedNullable && !it.isOptional,
                        description = descriptionOf(kc, it.name!!, it.annotations)
                    )
                }
        } else {
            kc.memberProperties.map {
                Prop(
                    it.name,
                    it.returnType,
                    required = !it.returnType.isMarkedNullable,
                    description = descriptionOf(kc, it.name, it.annotations)
                )
            }
        }
        return props.sortedBy { it.name }
    }

    /**
     * Reads a property's `@JsonPropertyDescription`, checking the Kotlin-visible annotations
     * (constructor-parameter / property targets) and then the JVM backing field and getter — a
     * `@field:JsonPropertyDescription` lands only on the field.
     */
    private fun descriptionOf(kc: KClass<*>, name: String, annotations: List<Annotation>): String? {
        annotations.filterIsInstance<JsonPropertyDescription>().firstOrNull()?.value?.let {
            if (it.isNotEmpty()) return it
        }
        kc.java.declaredFields.firstOrNull { it.name == name }
            ?.getAnnotation(JsonPropertyDescription::class.java)
            ?.let { if (it.value.isNotEmpty()) return it.value }
        val getter = runCatching {
            kc.java.getMethod("get" + name.replaceFirstChar { c -> c.uppercaseChar() })
        }.getOrNull()
        getter?.getAnnotation(JsonPropertyDescription::class.java)?.let { if (it.value.isNotEmpty()) return it.value }
        return null
    }

    private fun buildObject(
        kc: KClass<*>,
        node: ObjectNode,
        nullable: Boolean,
        hoisted: Set<KClass<*>>,
        root: KClass<*>
    ) {
        putType(node, "object", nullable)
        val propsNode = node.putObject("properties")
        val required = ArrayList<String>()
        for (prop in propertiesOf(kc)) {
            val propNode = propsNode.putObject(prop.name)
            buildValue(prop.type, propNode, hoisted, root)
            prop.description?.let { propNode.put("description", it) }
            if (prop.required) {
                required.add(prop.name)
            }
        }
        if (required.isNotEmpty()) {
            val reqNode = node.putArray("required")
            required.forEach { reqNode.add(it) }
        }
        node.put("additionalProperties", false)
    }

    private fun buildEnum(kc: KClass<*>, node: ObjectNode, nullable: Boolean) {
        putType(node, "string", nullable)
        val enumNode = node.putArray("enum")
        kc.java.enumConstants.forEach { enumNode.add((it as Enum<*>).name) }
    }

    private fun buildValue(type: KType, node: ObjectNode, hoisted: Set<KClass<*>>, root: KClass<*>) {
        val nullable = type.isMarkedNullable
        val kc = type.jvmErasure
        when {
            isScalar(kc) -> {
                putType(node, requireNotNull(scalarType(kc)), nullable)
                scalarFormat(kc)?.let { node.put("format", it) }
            }
            isMap(kc) -> putType(node, "object", nullable)
            isArrayLike(kc) -> {
                putType(node, "array", nullable)
                val items = node.putObject("items")
                val et = elementType(type)
                if (et != null) {
                    buildValue(et, items, hoisted, root)
                } else {
                    items.put("type", "object")
                }
            }
            kc == root -> putRef(node, "#", nullable)
            kc.java.isEnum -> when {
                kc in hoisted -> putRef(node, refPath(kc), nullable)
                // A nullable inline enum uses anyOf:[{null},{string,enum}] (victools treats an
                // enum as a definition-like node), unlike nullable scalars/objects/arrays which
                // use the "type": ["X", "null"] array form.
                nullable -> {
                    val arr = node.putArray("anyOf")
                    arr.addObject().put("type", "null")
                    buildEnum(kc, arr.addObject(), nullable = false)
                }
                else -> buildEnum(kc, node, nullable = false)
            }
            else ->
                if (kc in hoisted) putRef(node, refPath(kc), nullable)
                else buildObject(kc, node, nullable, hoisted, root)
        }
    }

    private fun putType(node: ObjectNode, base: String, nullable: Boolean) {
        if (nullable) {
            val arr = node.putArray("type")
            arr.add(base)
            arr.add("null")
        } else {
            node.put("type", base)
        }
    }

    private fun putRef(node: ObjectNode, ref: String, nullable: Boolean) {
        if (nullable) {
            val arr = node.putArray("anyOf")
            arr.addObject().put("type", "null")
            arr.addObject().put("\$ref", ref)
        } else {
            node.put("\$ref", ref)
        }
    }

    /**
     * Element type of an array-like [type]. Primitive arrays (`IntArray`, `LongArray`, …) carry no
     * type arguments, so their component class is used instead; without this they would fall back
     * to `items: {"type": "object"}`.
     */
    private fun elementType(type: KType): KType? =
        type.arguments.firstOrNull()?.type
            ?: type.jvmErasure.java.componentType?.kotlin?.createType()

    private fun isArrayLike(kc: KClass<*>): Boolean =
        kc.java.isArray ||
            List::class.java.isAssignableFrom(kc.java) ||
            Set::class.java.isAssignableFrom(kc.java) ||
            Collection::class.java.isAssignableFrom(kc.java) ||
            Iterable::class.java.isAssignableFrom(kc.java)

    private fun isMap(kc: KClass<*>): Boolean = Map::class.java.isAssignableFrom(kc.java)

    private fun isScalar(kc: KClass<*>): Boolean = scalarType(kc) != null

    private fun scalarType(kc: KClass<*>): String? = when (kc) {
        String::class, CharSequence::class, Char::class, java.lang.String::class -> "string"
        Int::class, Long::class, Short::class, Byte::class -> "integer"
        Double::class, Float::class, Number::class, java.math.BigDecimal::class -> "number"
        Boolean::class -> "boolean"
        java.math.BigInteger::class -> "integer"
        else -> null
    }

    private fun scalarFormat(kc: KClass<*>): String? = when (kc) {
        Int::class, Short::class, Byte::class -> "int32"
        Long::class -> "int64"
        Double::class -> "double"
        Float::class -> "float"
        else -> null
    }
}
