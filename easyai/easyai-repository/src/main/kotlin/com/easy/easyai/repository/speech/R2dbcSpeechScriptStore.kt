package com.easy.easyai.repository.speech

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.repository.database.Tables.SpeechScriptTable
import com.easy.easyai.repository.database.asyncTransaction
import com.easy.easyai.speech.SpeechCostParams
import com.easy.easyai.speech.SpeechDirectionParams
import com.easy.easyai.speech.SpeechOverrideParams
import com.easy.easyai.speech.SpeechProviderParams
import com.easy.easyai.speech.SpeechRowDefaults
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechScriptQuery
import com.easy.easyai.speech.SpeechScriptRecord
import com.easy.easyai.speech.SpeechScriptSort
import com.easy.easyai.speech.SpeechScriptStatus
import com.easy.easyai.speech.SpeechScriptStore
import com.easy.easyai.speech.SpeechScriptSummary
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechSynthesisParams
import com.easy.easyai.speech.SpeechTtsParams
import com.easy.easyai.speech.SpeechWarning
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * R2DBC implementation of [SpeechScriptStore].
 *
 * Ownership is strict per user: every read and write carries `user_id`, so a correctly guessed id
 * belonging to someone else reads as absent rather than as a permission error.
 *
 * The AUTO contract (one machine-saved row per target) is enforced here rather than by a unique
 * index, because H2 has no filtered index. Two plans for the same target racing each other can
 * therefore both insert; [findReusable] and [list] take the newest, which is the intended outcome.
 *
 * `segments_json` is read only by [get] and [findReusable]. [list] projects summary columns
 * explicitly, so a page of twenty 20 000-character plans does not travel through the wire.
 */
class R2dbcSpeechScriptStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : SpeechScriptStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun saveAuto(record: SpeechScriptRecord): SpeechScriptRecord = asyncTransaction(db) {
        val table = SpeechScriptTable
        val existingId = autoRowId(record.owner, record.targetType, record.targetId)

        val now = System.currentTimeMillis()
        if (existingId == null) {
            insertRow(record, now)
            record.copy(createdAt = if (record.createdAt > 0) record.createdAt else now, updatedAt = now)
        } else {
            // Keep the id: the preview panel or a render request may still be holding it.
            table.update({ table.id eq existingId }) {
                writeBody(record, it, revision = record.revision, now = now)
                it[title] = record.title.take(MAX_TITLE_CHARS)
            }
            record.copy(id = existingId, updatedAt = now)
        }
    }

    override suspend fun savePinned(record: SpeechScriptRecord): SpeechScriptRecord = asyncTransaction(db) {
        val now = System.currentTimeMillis()
        insertRow(record, now)
        record.copy(createdAt = if (record.createdAt > 0) record.createdAt else now, updatedAt = now)
    }

    override suspend fun findAutoId(
        owner: String,
        targetType: String,
        targetId: String?
    ): String? = asyncTransaction(db) { autoRowId(owner, targetType, targetId) }

    override suspend fun findReusable(
        owner: String,
        targetType: String,
        targetId: String?,
        digest: String
    ): SpeechScriptRecord? = asyncTransaction(db) {
        val table = SpeechScriptTable
        table.selectAll()
            .where {
                (table.userId eq owner) and
                    (table.targetType eq targetType) and
                    targetMatches(targetId) and
                    (table.sourceDigest eq digest) and
                    (table.status neq SpeechScriptStatus.ARCHIVED.name)
            }
            .orderBy(table.updatedAt to SortOrder.DESC)
            .limit(1)
            .map { it.toRecord() }
            .firstOrNull()
    }

    override suspend fun get(owner: String, id: String): SpeechScriptRecord? = asyncTransaction(db) {
        val table = SpeechScriptTable
        table.selectAll()
            .where { (table.id eq id) and (table.userId eq owner) }
            .limit(1)
            .map { it.toRecord() }
            .firstOrNull()
    }

    override suspend fun list(owner: String, query: SpeechScriptQuery): List<SpeechScriptSummary> =
        asyncTransaction(db) {
            val table = SpeechScriptTable
            val filters = mutableListOf<Op<Boolean>>()
            filters += table.userId eq owner
            // An archived row is listed only when the caller asks for that status explicitly.
            val status = query.status
            filters += if (status == null) {
                table.status neq SpeechScriptStatus.ARCHIVED.name
            } else {
                table.status eq status.name
            }
            query.targetType?.let { filters += table.targetType eq it }
            query.targetId?.let { filters += table.targetId eq it }
            query.sessionId?.let { filters += table.sessionId eq it }
            query.mode?.let { filters += table.mode eq it }
            if (query.favoriteOnly) filters += table.isFavorite eq true
            query.search?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
                // Wildcards are stripped, not escaped: a user typing `%` wants a literal `%`. The
                // escape character goes too — a trailing `\` would make PostgreSQL reject the pattern.
                val pattern = "%${term.replace("%", "").replace("_", "").replace("\\", "")}%"
                filters += ((table.title like pattern) or (table.sourceText like pattern))
            }

            val order = when (query.sort) {
                SpeechScriptSort.UPDATED -> table.updatedAt to SortOrder.DESC
                // PostgreSQL sorts NULLs first on DESC; "recently played" must not start with never played.
                SpeechScriptSort.LAST_PLAYED -> table.lastPlayedAt to SortOrder.DESC_NULLS_LAST
                SpeechScriptSort.PLAY_COUNT -> table.playCount to SortOrder.DESC
            }
            val where = filters.reduce { acc, next -> acc and next }

            table.select(
                table.id,
                table.title,
                table.targetType,
                table.targetId,
                table.sessionId,
                table.mode,
                table.status,
                table.revision,
                table.segmentCount,
                table.sourceChars,
                table.spokenChars,
                table.style,
                table.mood,
                table.isFavorite,
                table.playCount,
                table.lastPlayedAt,
                table.createdAt,
                table.updatedAt
            )
                .where { where }
                .orderBy(order)
                .limit(query.limit.coerceIn(1, MAX_LIMIT))
                .offset(query.offset.coerceAtLeast(0).toLong())
                .map { it.toSummary() }
                .toList()
        }

    override suspend fun update(record: SpeechScriptRecord, expectedRevision: Int): Boolean =
        asyncTransaction(db) {
            val table = SpeechScriptTable
            val now = System.currentTimeMillis()
            table.update({
                (table.id eq record.id) and
                    (table.userId eq record.owner) and
                    (table.revision eq expectedRevision) and
                    (table.status neq SpeechScriptStatus.ARCHIVED.name)
            }) {
                writeBody(record, it, revision = record.revision, now = now)
                it[title] = record.title.take(MAX_TITLE_CHARS)
                // Metadata, not body: writeBody is shared with saveAuto, and a new plan for the same
                // target must not clear a star the user set.
                it[isFavorite] = record.isFavorite
            } > 0
        }

    override suspend fun archive(owner: String, id: String): Boolean = asyncTransaction(db) {
        val table = SpeechScriptTable
        table.update({ (table.id eq id) and (table.userId eq owner) }) {
            it[status] = SpeechScriptStatus.ARCHIVED.name
            it[updatedAt] = System.currentTimeMillis()
        } > 0
    }

    override suspend fun touchPlayed(owner: String, id: String, at: Long): Unit = asyncTransaction(db) {
        val table = SpeechScriptTable
        val scope = { (table.id eq id) and (table.userId eq owner) }
        // Read-then-write inside one transaction: an element-wise increment would need the
        // expression builder in scope, and an off-by-one play counter is not worth that fight.
        val current = table.select(table.playCount)
            .where { scope() }
            .map { it[table.playCount] }
            .firstOrNull() ?: return@asyncTransaction
        table.update({ scope() }) {
            it[lastPlayedAt] = at
            it[playCount] = current + 1
            // Deliberately not `updatedAt`: listening is not editing, and bumping it would reorder
            // the library every time someone presses play.
        }
    }

    override suspend fun purgeAutoBefore(owner: String, before: Long): Int = asyncTransaction(db) {
        val table = SpeechScriptTable
        table.update({
            (table.userId eq owner) and
                (table.status eq SpeechScriptStatus.AUTO.name) and
                (table.updatedAt less before) and
                (table.isFavorite eq false)
        }) {
            it[status] = SpeechScriptStatus.ARCHIVED.name
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    override suspend fun archiveForTarget(targetType: String, targetId: String): Int = asyncTransaction(db) {
        val table = SpeechScriptTable
        table.update({
            (table.targetType eq targetType) and
            (table.targetId eq targetId) and
            (table.status neq SpeechScriptStatus.ARCHIVED.name)
        }) {
            it[status] = SpeechScriptStatus.ARCHIVED.name
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    override suspend fun archiveForSession(sessionId: String): Int = asyncTransaction(db) {
        val table = SpeechScriptTable
        table.update({
            (table.sessionId eq sessionId) and
                (table.status neq SpeechScriptStatus.ARCHIVED.name)
        }) {
            it[status] = SpeechScriptStatus.ARCHIVED.name
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    // ─── Row writing ─────────────────────────────────────────────────────────────

    private suspend fun insertRow(record: SpeechScriptRecord, now: Long) {
        val table = SpeechScriptTable
        table.insert {
            it[id] = record.id
            it[userId] = record.owner
            it[owners] = objectMapper.writeValueAsString(record.owners)
            it[title] = record.title.take(MAX_TITLE_CHARS)
            writeBody(record, it, revision = record.revision, now = now)
            it[isFavorite] = record.isFavorite
            it[playCount] = record.playCount
            it[lastPlayedAt] = record.lastPlayedAt
            it[createdAt] = if (record.createdAt > 0) record.createdAt else now
            it[updatedAt] = now
        }
    }

    /**
     * The mutable half of a row, in one place so an insert and a CAS update cannot drift apart — a
     * field added to one but not the other would silently lose data on auto-save.
     */
    private fun writeBody(
        record: SpeechScriptRecord,
        statement: UpdateBuilder<*>,
        revision: Int,
        now: Long
    ) {
        val table = SpeechScriptTable
        statement[table.targetType] = record.targetType
        statement[table.targetId] = record.targetId
        statement[table.sessionId] = record.sessionId
        statement[table.sourceText] = record.sourceText
        statement[table.sourceDigest] = record.sourceDigest
        statement[table.sourceChars] = record.sourceChars
        statement[table.spokenChars] = record.spokenChars
        statement[table.mode] = record.mode
        statement[table.status] = record.status.name
        statement[table.revision] = revision
        statement[table.segmentCount] = record.segmentCount
        statement[table.style] = record.script.style.name
        statement[table.mood] = record.script.mood.take(MAX_MOOD_CHARS)
        statement[table.segments] = objectMapper.writeValueAsString(record.script.segments)
        statement[table.warnings] = objectMapper.writeValueAsString(record.script.warnings)
        statement[table.ttsParams] = objectMapper.writeValueAsString(record.ttsParams)
        statement[table.directionParams] =
            record.directionParams?.let { objectMapper.writeValueAsString(it) }
        statement[table.updatedAt] = now
    }

    /** The id of the AUTO row this owner already has for the target, if any. */
    private suspend fun autoRowId(owner: String, targetType: String, targetId: String?): String? {
        val table = SpeechScriptTable
        return table.select(table.id)
            .where {
                (table.userId eq owner) and
                    (table.targetType eq targetType) and
                    targetMatches(targetId) and
                    (table.status eq SpeechScriptStatus.AUTO.name)
            }
            .orderBy(table.updatedAt to SortOrder.DESC)
            .limit(1)
            .map { it[table.id] }
            .firstOrNull()
    }

    /**
     * `target_id` is nullable and `col eq null` compiles to `= NULL` (never true), so the
     * manual-text target needs its own predicate.
     */
    private fun targetMatches(targetId: String?): Op<Boolean> {
        val column = SpeechScriptTable.targetId
        return if (targetId == null) column.isNull() else column eq targetId
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────────

    private fun ResultRow.toSummary(): SpeechScriptSummary {
        val table = SpeechScriptTable
        return SpeechScriptSummary(
            id = this[table.id],
            title = this[table.title],
            targetType = this[table.targetType],
            targetId = this[table.targetId],
            sessionId = this[table.sessionId],
            mode = this[table.mode],
            status = SpeechScriptStatus.fromKey(this[table.status]) ?: SpeechScriptStatus.AUTO,
            revision = this[table.revision],
            segmentCount = this[table.segmentCount],
            sourceChars = this[table.sourceChars],
            spokenChars = this[table.spokenChars],
            style = this[table.style],
            mood = this[table.mood],
            isFavorite = this[table.isFavorite],
            playCount = this[table.playCount],
            lastPlayedAt = this[table.lastPlayedAt],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }

    private fun ResultRow.toRecord(): SpeechScriptRecord {
        val table = SpeechScriptTable
        val id = this[table.id]
        val segments = readJson<List<SpeechSegment>>(id, "segments", this[table.segments]).orEmpty()
        val warnings = readJson<List<SpeechWarning>>(id, "warnings", this[table.warnings]).orEmpty()
        val script = SpeechScript(
            scriptId = id,
            revision = this[table.revision],
            sourceDigest = this[table.sourceDigest],
            mood = this[table.mood].orEmpty(),
            style = SpeechStyle.entries.firstOrNull { it.name == this[table.style] } ?: SpeechStyle.NARRATOR,
            segments = segments,
            warnings = warnings
        )
        return SpeechScriptRecord(
            id = id,
            owner = this[table.userId],
            owners = readJson<List<String>>(id, "owners", this[table.owners]) ?: listOf(this[table.userId]),
            targetType = this[table.targetType],
            targetId = this[table.targetId],
            sessionId = this[table.sessionId],
            title = this[table.title],
            sourceText = this[table.sourceText],
            sourceDigest = this[table.sourceDigest],
            mode = this[table.mode],
            status = SpeechScriptStatus.fromKey(this[table.status]) ?: SpeechScriptStatus.AUTO,
            revision = this[table.revision],
            script = script,
            ttsParams = readJson<SpeechTtsParams>(id, "tts parameters", this[table.ttsParams]) ?: EMPTY_PARAMS,
            directionParams = readJson<SpeechDirectionParams>(id, "direction parameters", this[table.directionParams]),
            isFavorite = this[table.isFavorite],
            playCount = this[table.playCount],
            lastPlayedAt = this[table.lastPlayedAt],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }

    /** A malformed column must not take a whole page down: log it and fall back. */
    private inline fun <reified T> readJson(id: String, what: String, json: String?): T? {
        if (json.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue(json, object : TypeReference<T>() {}) }
            .onFailure { logger.warn("Unreadable {} JSON for speech script '{}': {}", what, id, it.message) }
            .getOrNull()
    }

    companion object {
        /** Pages are small on purpose: this is a personal library, not a feed. */
        private const val MAX_LIMIT = 200

        private const val MAX_TITLE_CHARS = 200

        private const val MAX_MOOD_CHARS = 200

        /**
         * Reached only when `tts_params_json` is unreadable. It keeps the row loadable so the user
         * can still hear the script, and its shape says "unknown" rather than "no parameters".
         */
        private val EMPTY_PARAMS = SpeechTtsParams(
            capturedAt = 0,
            mode = "director",
            provider = SpeechProviderParams(
                modelConfigId = "",
                modelId = "unknown",
                protocol = "unknown"
            ),
            synthesis = SpeechSynthesisParams(
                format = "mp3",
                mimeType = "audio/mpeg",
                inputMode = "PLAIN_TEXT",
                timeoutSeconds = 0
            ),
            rowDefaults = SpeechRowDefaults(),
            overrides = SpeechOverrideParams(),
            perSegment = emptyList(),
            cost = SpeechCostParams(maxChars = 0, characters = 0)
        )
    }
}
