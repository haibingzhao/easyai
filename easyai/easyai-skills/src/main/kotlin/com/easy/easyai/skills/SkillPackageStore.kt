package com.easy.easyai.skills

import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.ObjectStorageResolver
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Per-owner access point to the skill package object storage.
 *
 * Resolution mirrors chat attachments: the owner's configured storage (user row, then `system`
 * row via [ObjectStorageResolver]) wins; with nothing configured, packages fall back to
 * [localFallback] — supplied by the wiring layer (a local directory under the skill root) so
 * single-machine/desktop deployments keep full sync semantics.
 */
class SkillPackageStore(
    private val resolver: ObjectStorageResolver?,
    private val localFallback: ObjectStorage?
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun storageFor(owner: String): ObjectStorage {
        resolver?.resolve(owner)?.let { return it }
        return localFallback ?: error("No object storage configured and no local fallback available")
    }

    /** `skills/{owner}/{name}.zip` — names are sanitized; the catalog row is authoritative for the key. */
    fun keyFor(owner: String, name: String): String =
        "$KEY_DIR/${SkillPaths.safeSegment(owner)}/${SkillPaths.safeSegment(name)}$ZIP_SUFFIX"

    /** Delete the package, best-effort: the catalog row has already been removed when this runs. */
    suspend fun deleteQuietly(owner: String, objectKey: String) {
        if (objectKey.isBlank()) return
        try {
            storageFor(owner).delete(objectKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not delete skill package {}: {}", objectKey, e.message)
        }
    }

    companion object {
        const val KEY_DIR = "skills"
        const val ZIP_SUFFIX = ".zip"

        fun isPackageKey(key: String?): Boolean =
            !key.isNullOrBlank() && key.startsWith("$KEY_DIR/") && key.endsWith(ZIP_SUFFIX)

        /** Refuse a key outside the skills namespace before any storage call. */
        fun requirePackageKey(key: String): String {
            if (!isPackageKey(key)) throw ObjectStorageException("Not a skill package key: $key")
            return key
        }
    }
}
