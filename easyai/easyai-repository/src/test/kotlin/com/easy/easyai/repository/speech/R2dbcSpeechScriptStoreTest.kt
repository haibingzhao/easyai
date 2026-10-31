package com.easy.easyai.repository.speech

import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.speech.SpeechCostParams
import com.easy.easyai.speech.SpeechProviderParams
import com.easy.easyai.speech.SpeechScript
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
        text: String = "今天杭州有雨，出门记得带伞。"
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
        mode = "director",
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
            mode = "director",
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
        directionParams = null
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
    fun `findAutoId resolves the row saveAuto would keep`() = runTest {
        val store = R2dbcSpeechScriptStore(db)

        assertNull(store.findAutoId("alice", "session_message", "msg-find"))
        val saved = store.saveAuto(record(id = "script-find", targetId = "msg-find"))

        assertEquals(saved.id, store.findAutoId("alice", "session_message", "msg-find"))
        assertNull(store.findAutoId("bob", "session_message", "msg-find"))
    }
}
