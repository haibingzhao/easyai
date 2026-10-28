package com.easy.easyai.web.service

import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.knowledge.KnowledgeTree
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class AccessibleKnowledgeFolder(
    val folder: KnowledgeFolder,
    val canManage: Boolean,
    /** True for the bucket new content lands in; the other visible bucket is read-only for writes. */
    val writable: Boolean,
)

/**
 * Folder tree operations.
 *
 * Folders are pure database metadata: create/rename/delete never touch object storage or the RAG
 * index, and the display path is derived from the parent chain on read.
 */
@Service
class KnowledgeFolderService(
    private val folderStore: KnowledgeFolderStore,
    private val articleStore: KnowledgeArticleStore,
    private val accessPolicy: KnowledgeAccessPolicy,
    @param:Value("\${easyai.knowledge.shared-within-group:false}") private val sharedWithinGroup: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun list(context: KnowledgeAccessContext): List<AccessibleKnowledgeFolder> {
        if (!accessPolicy.canView(context)) return emptyList()
        val buckets = visibleBuckets(context)
        val canManage = accessPolicy.canManageFolders(context)
        val canCreate = accessPolicy.canCreate(context)
        val writeOwner = writeOwner(context)
        return folderStore.listByBuckets(buckets)
            .map {
                AccessibleKnowledgeFolder(
                    folder = it,
                    canManage = canManage && it.userId in buckets,
                    writable = canCreate && it.userId == writeOwner,
                )
            }
    }

    suspend fun create(
        context: KnowledgeAccessContext,
        parentId: String?,
        name: String,
    ): KnowledgeFolder {
        requireManageFolders(context)
        val ownerId = writeOwner(context)
        val normalized = name.trim()
        KnowledgePathValidator.validateFolderName(normalized)

        val parent = parentId?.takeIf { it.isNotBlank() }?.let { id ->
            val folder = folderStore.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found: $id")
            if (folder.userId != ownerId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent folder belongs to another space")
            }
            folder
        }

        val siblings = folderStore.listByBuckets(listOf(ownerId))
        if (siblings.any { it.parentId == parent?.id && it.name == normalized }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A folder with the same name already exists")
        }

        val now = System.currentTimeMillis()
        val folder = KnowledgeFolder(
            id = UUID.randomUUID().toString(),
            userId = ownerId,
            parentId = parent?.id,
            name = normalized,
            isDefault = false,
            createdBy = context.userId,
            createdAt = now,
            updatedAt = now,
        )
        try {
            folderStore.save(folder)
        } catch (e: Exception) {
            if (isUniqueViolation(e)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "A folder with the same name already exists")
            }
            throw e
        }
        logger.info("Created knowledge folder: id={}, bucket={}, name={}", folder.id, ownerId, normalized)
        return folder
    }

    suspend fun rename(
        context: KnowledgeAccessContext,
        id: String,
        name: String,
    ): KnowledgeFolder {
        val folder = requireManagedFolder(context, id)
        val normalized = name.trim()
        KnowledgePathValidator.validateFolderName(normalized)
        if (folder.name == normalized) return folder

        val siblings = folderStore.listByBuckets(listOf(folder.userId))
        if (siblings.any { it.id != id && it.parentId == folder.parentId && it.name == normalized }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A folder with the same name already exists")
        }

        val renamed = folder.copy(name = normalized, updatedAt = System.currentTimeMillis())
        try {
            folderStore.save(renamed)
        } catch (e: Exception) {
            if (isUniqueViolation(e)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "A folder with the same name already exists")
            }
            throw e
        }
        logger.info("Renamed knowledge folder: id={}, name={}", id, normalized)
        return renamed
    }

    suspend fun delete(context: KnowledgeAccessContext, id: String) {
        val folder = requireManagedFolder(context, id)
        if (folder.isDefault) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The default folder cannot be deleted")
        }

        val bucketFolders = folderStore.listByBuckets(listOf(folder.userId))
        val subfolderCount = bucketFolders.count { it.parentId == id }
        val subtree = KnowledgeTree.subtreeIds(id, bucketFolders)
        val articleCount = articleStore.findAllInBuckets(listOf(folder.userId))
            .toList()
            .count { it.folderId in subtree }

        if (subfolderCount > 0 || articleCount > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Folder is not empty: $articleCount articles, $subfolderCount subfolders",
            )
        }
        // The counts above are only for the error message; the store re-checks and deletes in one
        // transaction, and the folder_id foreign key is the backstop for a write that still slips in.
        val deleted = try {
            folderStore.deleteIfEmpty(id, subtree)
        } catch (e: Exception) {
            if (isForeignKeyViolation(e)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Folder is not empty")
            }
            throw e
        }
        if (!deleted) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Folder changed while it was being deleted; retry")
        }
        logger.info("Deleted knowledge folder: id={}", id)
    }

    /** Target folder for article writes; a missing [folderId] means the bucket default. */
    suspend fun requireFolder(
        ownerId: String,
        folderId: String?,
        createdBy: String? = null,
    ): KnowledgeFolder {
        if (folderId.isNullOrBlank()) return ensureDefault(ownerId, createdBy)
        val folder = folderStore.findById(folderId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found: $folderId")
        if (folder.userId != ownerId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Folder belongs to another space")
        }
        return folder
    }

    /** Resolve a display path (`Family/Recipes`) inside one bucket. */
    suspend fun resolvePath(ownerId: String, path: String): KnowledgeFolder? =
        KnowledgeTree.resolve(ownerId, path, folderStore.listByBuckets(listOf(ownerId)))

    /** Every folder in the given buckets; callers derive paths with [KnowledgeTree]. */
    suspend fun foldersIn(buckets: Collection<String>): List<KnowledgeFolder> =
        folderStore.listByBuckets(buckets)

    /** The bucket's default ("Uncategorized") folder, created (or adopted) on first use. */
    suspend fun ensureDefault(ownerId: String, createdBy: String?): KnowledgeFolder {
        folderStore.findDefault(ownerId)?.let { return it }

        val now = System.currentTimeMillis()
        val roots = folderStore.listByBuckets(listOf(ownerId)).filter { it.parentId == null }
        val sameName = roots.firstOrNull { it.name == DEFAULT_FOLDER_NAME }
        if (sameName != null) {
            val adopted = if (sameName.isDefault) sameName else sameName.copy(isDefault = true, updatedAt = now)
            if (!sameName.isDefault) folderStore.save(adopted)
            return adopted
        }

        val folder = KnowledgeFolder(
            id = UUID.randomUUID().toString(),
            userId = ownerId,
            parentId = null,
            name = DEFAULT_FOLDER_NAME,
            isDefault = true,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
        )
        return try {
            folderStore.save(folder)
            folder
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
            // Concurrent creation: adopt whichever row won the unique name.
            folderStore.findDefault(ownerId)
                ?: folderStore.listByBuckets(listOf(ownerId))
                    .firstOrNull { it.parentId == null && it.name == DEFAULT_FOLDER_NAME }
                ?: throw e
        }
    }

    /** Get or create a direct child folder by name (used by folder uploads). */
    suspend fun ensureChild(
        ownerId: String,
        parent: KnowledgeFolder,
        name: String,
        createdBy: String?,
    ): KnowledgeFolder {
        KnowledgePathValidator.validateFolderName(name)
        val children = folderStore.listByBuckets(listOf(ownerId)).filter { it.parentId == parent.id }
        children.firstOrNull { it.name == name }?.let { return it }

        val now = System.currentTimeMillis()
        val folder = KnowledgeFolder(
            id = UUID.randomUUID().toString(),
            userId = ownerId,
            parentId = parent.id,
            name = name,
            isDefault = false,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
        )
        return try {
            folderStore.save(folder)
            folder
        } catch (e: Exception) {
            if (!isUniqueViolation(e)) throw e
            folderStore.listByBuckets(listOf(ownerId))
                .firstOrNull { it.parentId == parent.id && it.name == name }
                ?: throw e
        }
    }

    private suspend fun requireManagedFolder(context: KnowledgeAccessContext, id: String): KnowledgeFolder {
        val folder = folderStore.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found: $id")
        if (folder.userId !in visibleBuckets(context) || !accessPolicy.canView(context)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Folder not found: $id")
        }
        requireManageFolders(context)
        return folder
    }

    private suspend fun requireManageFolders(context: KnowledgeAccessContext) {
        if (!accessPolicy.canManageFolders(context)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No permission to manage knowledge folders")
        }
    }

    private fun visibleBuckets(context: KnowledgeAccessContext): List<String> =
        listOfNotNull(context.userId.takeIf { it.isNotBlank() }, context.groupUserId?.takeIf { it.isNotBlank() })
            .distinct()

    private fun writeOwner(context: KnowledgeAccessContext): String =
        KnowledgeOwnership.ownerId(context.userId, context.groupUserId, sharedWithinGroup)

    companion object {
        const val DEFAULT_FOLDER_NAME = "未分类"
    }
}
