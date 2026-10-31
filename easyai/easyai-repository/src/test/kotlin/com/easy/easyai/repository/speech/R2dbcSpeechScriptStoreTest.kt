package com.easy.easyai.repository.speech

import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.speech.SpeechCostParams
import com.easy.easyai.speech.SpeechProviderParams
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechScriptGenerationStatus
import com.easy.easyai.speech.SpeechScriptQuery
import com.easy.easyai.speech.SpeechScriptRecord
import com.easy.easyai.speech.SpeechScriptStatus
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechSynthesisParams
import com.easy.easyai.speech.SpeechTtsParams
import com.easy.easyai.speech.SpokenTextNormalizer
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** H2 coverage for the metadata SQL that the archive's other tests can only see through a fake. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcSpeechScriptStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///speech_script_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun record(
        id: String = "script-1",
        owner: String = "alice",
        targetId: String = "msg-1",
        text: String = "今天杭州有雨，出门记得带伞。",
        mode: String = "director",
        generationStatus: SpeechScriptGenerationStatus? = SpeechScriptGenerationStatus.COMPLETED
    ) = SpeechScriptRecord(
        id = id,
        owner = owner,
        owners = listOf(owner),
        targetType = "session_message",
        targetId = targetId,
        sessionId = "sess-1",
        title = "A script",
        sourceText = text,
        sourceDigest = SpokenTextNormalizer.sourceDigest(text),
        mode = mode,
        status = SpeechScriptStatus.AUTO,
        revision = 1,
        script = SpeechScript(
            scriptId = id,
            sourceDigest = SpokenTextNormalizer.sourceDigest(text),
            mood = "平静",
            style = SpeechStyle.TEACHER,
            segments = listOf(
                SpeechSegment(
                    id = "seg-0",
                    index = 0,
                    sourceText = text,
                    spokenText = text,
                    voice = "voice-a"
                )
            )
        ),
        ttsParams = SpeechTtsParams(
            capturedAt = 0,
            mode = mode,
            provider = SpeechProviderParams(
                modelConfigId = "cfg",
                modelId = "qwen-audio-3.1-tts-flash",
                protocol = "dashscope"
            ),
            synthesis = SpeechSynthesisParams(
                format = "mp3",
                mimeType = "audio/mpeg",
                inputMode = "PLAIN_TEXT",
                timeoutSeconds = 30
            ),
            cost = SpeechCostParams(maxChars = 20_000, characters = text.length)
        ),
        directionParams = null,
        generationStatus = generationStatus
    )

    @Test
    fun `update persists a favourite toggle`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val saved = store.saveAuto(record())

        assertTrue(store.update(saved.copy(isFavorite = true), saved.revision))

        assertTrue(assertNotNull(store.get("alice", saved.id)).isFavorite)
    }

    @Test
    fun `a new plan keeps the row id, the favourite and the play counter`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val saved = store.saveAuto(record(id = "script-auto"))
        assertTrue(store.update(saved.copy(isFavorite = true), saved.revision))
        store.touchPlayed("alice", saved.id, 123L)

        val replanned = store.saveAuto(record(id = "script-auto", text = "换了一版回复。"))

        assertEquals(saved.id, replanned.id)
        val row = assertNotNull(store.get("alice", saved.id))
        assertTrue(row.isFavorite)
        assertEquals(1, row.playCount)
        assertEquals("换了一版回复。", row.sourceText)
    }

    @Test
    fun `only a completed script passes the reuse query`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val statuses = listOf<SpeechScriptGenerationStatus?>(
            null,
            SpeechScriptGenerationStatus.IN_PROGRESS,
            SpeechScriptGenerationStatus.CANCELLED,
            SpeechScriptGenerationStatus.FAILED,
            SpeechScriptGenerationStatus.COMPLETED
        )

        statuses.forEachIndexed { index, status ->
            val targetId = "msg-generation-$index"
            val saved = store.saveAuto(
                record(id = "script-generation-$index", targetId = targetId, generationStatus = status)
            )
            val digest = SpokenTextNormalizer.sourceDigest(saved.sourceText)

            val reusable = store.findReusable("alice", "session_message", targetId, "director", digest)

            if (status == SpeechScriptGenerationStatus.COMPLETED) {
                assertNotNull(reusable)
            } else {
                assertNull(reusable, "generation status $status must not be reusable")
            }
            assertEquals(status, store.get("alice", saved.id)?.generationStatus)
        }
    }

    @Test
    fun `abandoning an in-progress plan records the terminal state`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val saved = store.saveAuto(record(generationStatus = SpeechScriptGenerationStatus.IN_PROGRESS))
        val digest = SpokenTextNormalizer.sourceDigest(saved.sourceText)

        assertTrue(store.saveAbandoned(saved.copy(generationStatus = SpeechScriptGenerationStatus.CANCELLED)))

        val row = assertNotNull(store.get("alice", saved.id))
        assertEquals(SpeechScriptGenerationStatus.CANCELLED, row.generationStatus)
        assertEquals(saved.script.segments, row.script.segments)
        assertNull(store.findReusable("alice", "session_message", "msg-1", "director", digest))
    }

    @Test
    fun `abandoning never replaces a row completed meanwhile`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val saved = store.saveAuto(record(generationStatus = SpeechScriptGenerationStatus.IN_PROGRESS))
        store.saveAuto(saved.copy(generationStatus = SpeechScriptGenerationStatus.COMPLETED))

        val abandoned = saved.copy(
            generationStatus = SpeechScriptGenerationStatus.FAILED,
            script = saved.script.copy(segments = emptyList())
        )

        assertFalse(store.saveAbandoned(abandoned))

        val row = assertNotNull(store.get("alice", saved.id))
        assertEquals(SpeechScriptGenerationStatus.COMPLETED, row.generationStatus)
        assertTrue(row.script.segments.isNotEmpty())
        assertNotNull(
            store.findReusable(
                "alice",
                "session_message",
                "msg-1",
                "director",
                SpokenTextNormalizer.sourceDigest(saved.sourceText)
            )
        )
    }

    @Test
    fun `findAutoId resolves the row saveAuto would keep`() = runTest {
        val store = R2dbcSpeechScriptStore(db)

        assertNull(store.findAutoId("alice", "session_message", "msg-find", "director"))
        val saved = store.saveAuto(record(id = "script-find", targetId = "msg-find"))

        assertEquals(saved.id, store.findAutoId("alice", "session_message", "msg-find", "director"))
        assertNull(store.findAutoId("bob", "session_message", "msg-find", "director"))
    }

    @Test
    fun `a refined read and a directed plan of one reply are two rows`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val targetId = "msg-mode-split"

        val refined = store.saveAuto(record(id = "script-refined", targetId = targetId, mode = "refine"))
        val directed = store.saveAuto(record(id = "script-directed", targetId = targetId, mode = "director"))

        assertNotEquals(refined.id, directed.id)
        assertEquals("refine", store.get("alice", "script-refined")?.mode)
        assertEquals("director", store.get("alice", "script-directed")?.mode)
        assertEquals(2, store.list("alice", SpeechScriptQuery(targetType = "session_message", targetId = targetId)).size)
    }

    @Test
    fun `reuse never crosses modes for the same reply`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val targetId = "msg-mode-reuse"
        val saved = store.saveAuto(record(id = "script-only-refined", targetId = targetId, mode = "refine"))
        val digest = SpokenTextNormalizer.sourceDigest(saved.sourceText)

        assertNotNull(store.findReusable("alice", "session_message", targetId, "refine", digest))
        assertNull(
            store.findReusable("alice", "session_message", targetId, "director", digest),
            "a refined single segment must not be spoken as a directed plan"
        )
    }

    @Test
    fun `each mode keeps its own auto row when it is planned again`() = runTest {
        val store = R2dbcSpeechScriptStore(db)
        val targetId = "msg-mode-keep"

        val refined = store.saveAuto(record(id = "script-refine-id", targetId = targetId, mode = "refine"))
        val directed = store.saveAuto(record(id = "script-director-id", targetId = targetId, mode = "director"))
        val refinedAgain = store.saveAuto(
            record(id = "script-refine-id", targetId = targetId, mode = "refine", text = "换了一版回复。")
        )

        assertEquals(refined.id, refinedAgain.id)
        assertEquals("换了一版回复。", store.get("alice", refined.id)?.sourceText)
        assertEquals("今天杭州有雨，出门记得带伞。", store.get("alice", directed.id)?.sourceText)
    }
}
