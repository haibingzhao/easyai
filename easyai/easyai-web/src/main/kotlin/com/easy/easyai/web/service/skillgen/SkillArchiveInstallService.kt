package com.easy.easyai.web.service.skillgen

import com.easy.easyai.skills.SkillAddResult
import com.easy.easyai.skills.SkillArchiveCandidate
import com.easy.easyai.skills.SkillArchiveInspector
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.skills.SkillUpload
import com.easy.easyai.web.model.SkillArchiveEntryRequest
import com.easy.easyai.web.model.SkillArchiveInstallRequest
import com.easy.easyai.web.model.SkillArchiveInstallResponse
import com.easy.easyai.web.model.SkillArchiveInstallResult
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Installs selected skills from an archive the user already previewed.
 *
 * The card only carries `url + sha256 + entryPath + name`; the bytes are downloaded again here and
 * pinned to that hash, so an archive that changed between preview and click is refused instead of
 * silently installing different content. A single download serves every entry in the batch, and
 * entries are installed sequentially: one failure never rolls back the others, and each result is
 * reported on its own card.
 */
@Service
class SkillArchiveInstallService(
    @param:Autowired(required = false) private val skillRefreshService: SkillRefreshService? = null,
    @param:Autowired(required = false) private val skillConfig: SkillConfig? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val fetcher = SkillArchiveFetcher()
    private val inspector = SkillArchiveInspector()

    suspend fun install(owner: String, request: SkillArchiveInstallRequest): SkillArchiveInstallResponse {
        if (skillRefreshService == null) throw unavailable()
        validateEntries(request)
        val fetched = try {
            fetcher.fetch(request.url.trim(), archiveCap())
        } catch (e: SkillArchiveException) {
            throw badRequest("${e.code}: ${e.message}")
        }
        return installVerified(owner, fetched.bytes, request)
    }

    /**
     * Verifies [bytes] against the pinned hash and installs [request]'s entries. Separate from
     * [install] so tests can exercise the batch semantics without network access.
     */
    internal suspend fun installVerified(
        owner: String,
        bytes: ByteArray,
        request: SkillArchiveInstallRequest
    ): SkillArchiveInstallResponse {
        val service = skillRefreshService ?: throw unavailable()
        validateEntries(request)
        val maxBytes = archiveCap()
        if (bytes.size.toLong() > maxBytes) {
            throw badRequest("${SkillArchiveCodes.TOO_LARGE}: the archive exceeds the $maxBytes-byte limit")
        }
        val expected = request.sha256.trim()
        if (expected.isEmpty()) {
            throw badRequest("${SkillArchiveCodes.FETCH_FAILED}: sha256 is required; preview the archive again")
        }
        val actual = inspector.sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw badRequest(
                "${SkillArchiveCodes.FETCH_FAILED}: the archive at ${request.url} changed since the preview " +
                    "(sha256 mismatch). Preview it again before installing."
            )
        }
        // Install names double as renames: a skill previewed under a new name must be scanned under
        // that same name here, or the original frontmatter would trip spec.name-mismatch.
        val renames = request.entries.associate { it.entryPath to it.name.trim() }
        val candidates = try {
            inspector.inspect(bytes, maxBytes, renames).associateBy { it.entryPath }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw badRequest("${SkillArchiveCodes.NOT_A_ZIP}: could not unpack ${request.url}: ${e.message}")
        }
        val results = request.entries.map { entry ->
            installOne(service, owner, entry, candidates[entry.entryPath])
        }
        logger.info(
            "Archive install for owner '{}': {} installed, {} conflict, {} invalid, {} missing",
            owner,
            results.count { it.outcome == OUTCOME_INSTALLED },
            results.count { it.outcome == OUTCOME_CONFLICT },
            results.count { it.outcome == OUTCOME_INVALID },
            results.count { it.outcome == OUTCOME_MISSING }
        )
        return SkillArchiveInstallResponse(results)
    }

    private suspend fun installOne(
        service: SkillRefreshService,
        owner: String,
        entry: SkillArchiveEntryRequest,
        candidate: SkillArchiveCandidate?
    ): SkillArchiveInstallResult {
        val name = entry.name.trim()
        if (!isValidSkillName(name)) {
            return result(entry, OUTCOME_INVALID, "The name '$name' is not a path-safe slug.")
        }
        if (candidate == null) {
            return result(
                entry, OUTCOME_MISSING,
                "The archive no longer contains entryPath '${entry.entryPath}'. Preview it again."
            )
        }
        if (candidate.invalidReason != null) {
            return result(entry, OUTCOME_INVALID, candidate.invalidReason)
        }
        if (candidate.scan.blocking) {
            val blocked = candidate.scan.findings.filter { it.blocking }.take(5).joinToString("; ") { it.id }
            return result(entry, OUTCOME_INVALID, "Blocked by the server scan: $blocked")
        }
        return try {
            when (val added = service.addUploaded(owner, name, SkillUpload.FromFiles(candidate.files))) {
                is SkillAddResult.Added -> result(entry, OUTCOME_INSTALLED, null)
                is SkillAddResult.NameConflict -> result(entry, OUTCOME_CONFLICT, added.message)
                is SkillAddResult.Invalid -> result(entry, OUTCOME_INVALID, added.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Installing '{}' from '{}' failed: {}", name, entry.entryPath, e.message)
            result(entry, OUTCOME_INVALID, e.message ?: "The skill could not be installed")
        }
    }

    private fun result(
        entry: SkillArchiveEntryRequest,
        outcome: String,
        message: String?
    ): SkillArchiveInstallResult = SkillArchiveInstallResult(
        entryPath = entry.entryPath,
        name = entry.name.trim(),
        outcome = outcome,
        message = message
    )

    private fun validateEntries(request: SkillArchiveInstallRequest) {
        if (request.url.isBlank()) throw badRequest("url is required")
        if (request.entries.isEmpty()) throw badRequest("entries must list at least one skill")
        if (request.entries.size > MAX_ENTRIES) {
            throw badRequest("At most $MAX_ENTRIES skills can be installed from one archive")
        }
        val duplicates = request.entries.groupingBy { it.entryPath }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw badRequest("Duplicate entryPath values: ${duplicates.joinToString(", ")}")
        }
    }

    private fun archiveCap(): Long =
        (skillConfig?.packageMaxBytes ?: SKILL_ARCHIVE_MAX_BYTES).coerceAtMost(SKILL_ARCHIVE_MAX_BYTES)

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private fun unavailable(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The skill system is not enabled (set easyai.skills.enabled=true, with easyai.r2dbc.enabled on)"
    )

    private companion object {
        private const val MAX_ENTRIES = 20
        private const val OUTCOME_INSTALLED = "installed"
        private const val OUTCOME_CONFLICT = "conflict"
        private const val OUTCOME_INVALID = "invalid"
        private const val OUTCOME_MISSING = "missing"
    }
}
