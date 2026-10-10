package com.easy.easyai.core.tool

import com.easy.easyai.api.llm.schema.JsonSchemaGenerator
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

// Representative shapes covering every construct easyai tool parameter classes use.
data class PSimple(
    val prompt: String,
    val n: Int? = null,
    val size: String? = null,
    val ratio: Double? = null,
    val flag: Boolean = false,
    val count: Long = 0
)

enum class PMode { FAST, BALANCED, QUALITY }
data class PInner(val id: Long, val label: String?)
data class PNested(
    val name: String,
    val mode: PMode,
    val tags: List<String>,
    val scores: List<Int>,
    val inner: PInner?,
    val meta: Map<String, String>? = null
)

data class PSharedChild(val a: String)
data class PShared(val x: PSharedChild, val y: PSharedChild)
data class PRec(val name: String, val child: PRec?)
data class PListItem(val content: String, val status: String = "pending", val priority: String = "medium")
data class PListObj(val todos: List<PListItem>)
enum class PStatus { OPEN, DONE }
data class PListEdge(
    val a: List<String>?,
    val b: List<PStatus>,
    val c: Map<String, Int>,
    val d: PStatus
)
data class PAllOptional(val x: String? = null, val y: Int? = null)

// Additional real-world shapes found across easyai tool parameter classes.
data class PSetAny(
    val ids: Set<String>,
    val attrs: Map<String, Any?>,
    val opt: PAllOptionalItem?
)
data class PAllOptionalItem(val a: String? = null, val b: Int? = null)
data class PNullableEnum(val mode: PMode?, val items: List<PAllOptionalItem>)

/**
 * Frozen snapshot guard for the hand-rolled [JsonSchemaGenerator].
 *
 * Each golden file under `src/test/resources/jsonschema/` was captured while spring-ai was still
 * on the classpath, after a parity check proved this generator reproduced spring-ai/victools output
 * byte-for-byte for every shape. Regressions in schema output now fail here instead of drifting
 * silently. If a deliberate change is made to the generator, refresh a snapshot with
 * `-Djsonschema.snapshot=true`.
 */
class JsonSchemaGeneratorParityTest {

    private val snapshotDir: Path = Path.of("src/test/resources/jsonschema")
    private val refresh: Boolean = System.getProperty("jsonschema.snapshot") == "true"

    private fun assertSnapshot(type: Class<*>) {
        val actual = JsonSchemaGenerator.generateForType(type)
        if (refresh) {
            Files.createDirectories(snapshotDir)
            Files.writeString(snapshotDir.resolve("${type.simpleName}.json"), actual)
            return
        }
        // Read from the classpath, not a relative path: surefire's working directory is the
        // module basedir under Maven, but any other runner (IDE, gradle) starts elsewhere.
        val expected = javaClass.getResourceAsStream("/jsonschema/${type.simpleName}.json")
            ?.reader(Charsets.UTF_8).use { it!!.readText() }
            ?: error("missing snapshot for ${type.simpleName}; run with -Djsonschema.snapshot=true")
        assertEquals(expected, actual, "schema drift for ${type.simpleName}")
    }

    @Test
    fun `matches frozen snapshots for all representative shapes`() {
        listOf(
            PSimple::class.java,
            PNested::class.java,
            PMode::class.java,
            PShared::class.java,
            PRec::class.java,
            PListObj::class.java,
            PListEdge::class.java,
            PAllOptional::class.java,
            PSetAny::class.java,
            PNullableEnum::class.java
        ).forEach(::assertSnapshot)
    }
}
