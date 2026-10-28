package com.easy.easyai.web.service

import com.easy.easyai.common.util.isSafePathSegment
import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.snapshot.SnapshotService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Allocates and releases the per-session temporary workspace used when a user chats
 * without picking a project.
 *
 * A temporary workspace is a real [ProjectKind.TEMP] row in the project table pointing at
 * `dataDir/.temp-workspaces/userId/sessionId`, so every downstream consumer of
 * `AgentContext.projectPath` (shell cwd, file tools, shadow git checkpoints, skills,
 * sub-agents, permission rules) works unchanged. Without it those consumers fall back to
 * the backend process directory.
 *
 * The project id is derived deterministically from the session id, which makes concurrent
 * first-message requests converge on a single row instead of racing to create duplicates.
 */
class DefaultWorkspaceService(
    private val projectStore: AsyncProjectStore,
    private val sessionStore: AsyncSessionStore? = null,
    private val permissionRuleStore: PermissionRuleStore? = null,
    private val snapshotService: SnapshotService? = null,
    dataDir: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Base directory holding all temporary workspaces, one per session. */
    private val baseDir: Path = Path.of(dataDir).resolve(WORKSPACE_DIR_NAME)

    /**
     * Return the workspace bound to [sessionId], creating the directory and the project row
     * on first use. Idempotent: a second call for the same session returns the same pair.
     */
    suspend fun resolveOrCreate(sessionId: String, userId: String): Workspace {
        val projectId = tempProjectId(sessionId)
        projectStore.findById(projectId, userId)?.let {
            return Workspace(it.id, ensureDir(it.path), it.kind)
        }
        val dir = baseDir.resolve(idSegment(userId)).resolve(idSegment(sessionId))
        ensureDir(dir)
        val inserted = projectStore.saveIfAbsent(
            ProjectInfo(
                id = projectId,
                name = TEMP_PROJECT_NAME,
                path = dir.toString(),
                kind = ProjectKind.TEMP
            ),
            userId
        )
        if (inserted) {
            logger.info("Created temporary workspace {} for session {} (user {})", dir, sessionId, userId)
            return Workspace(projectId, dir, ProjectKind.TEMP)
        }
        // Concurrent first message on the same session: trust the persisted row over local values.
        val persisted = projectStore.findById(projectId, userId)
            ?: throw IllegalStateException("Temporary workspace $projectId is not available to this user")
        return Workspace(persisted.id, ensureDir(persisted.path), persisted.kind)
    }

    /**
     * Release the temporary workspace of a deleted session. Sessions sharing the workspace
     * (forks, team member sessions) keep it alive, so the scratch directory is only removed
     * once the last of them is gone.
     */
    suspend fun releaseForSession(sessionId: String, projectId: String, userId: String) {
        val siblings = sessionStore?.findIdsByProjectId(projectId, userId)
            ?.filter { it != sessionId } ?: emptyList()
        if (siblings.isNotEmpty()) {
            logger.info("Workspace {} still used by {} session(s); keeping files", projectId, siblings.size)
            return
        }
        releaseWorkspace(projectId, userId)
    }

    /**
     * Release a temporary workspace for good: permission rules, shadow git repository,
     * scratch directory and the project row. No-op for user-registered projects.
     */
    suspend fun releaseWorkspace(projectId: String, userId: String) {
        val project = projectStore.findById(projectId, userId) ?: return
        if (project.kind != ProjectKind.TEMP) {
            logger.warn("Refusing to release non-temporary workspace {}", projectId)
            return
        }
        val path = Path.of(project.path)
        permissionRuleStore?.deleteProjectRules(projectId)
        try {
            snapshotService?.removeWorkspace(path)
        } catch (e: Exception) {
            logger.warn("Failed to remove snapshot repo of workspace {}: {}", projectId, e.message)
        }
        withContext(Dispatchers.IO) {
            runCatching { path.toFile().deleteRecursively() }
                .onFailure { logger.warn("Failed to delete temporary workspace {}: {}", path, it.message) }
            pruneEmptyUserDir(path)
        }
        projectStore.delete(projectId, userId)
        logger.info("Released temporary workspace {} ({})", projectId, path)
    }

    /**
     * Release temporary workspaces no session refers to any more.
     *
     * A workspace row is created before the session row is persisted, so a first turn that is
     * cancelled, fails immediately, or dies with the process leaves a workspace nobody can ever
     * delete through the UI. Rows younger than the grace period are skipped: they may belong to a
     * turn that is still in flight.
     *
     * Liveness is the same query [releaseForSession] uses, so a workspace shared with forks or team
     * member sessions survives the deletion of the session it was named after.
     *
     * @return the number of workspaces released; 0 when session existence cannot be checked.
     */
    suspend fun sweepOrphanWorkspaces(): Int {
        val sessions = sessionStore ?: return 0
        val cutoff = Instant.now().minusMillis(SWEEP_GRACE_MS)
        var released = 0
        for (userId in projectStore.listDistinctUserIds()) {
            for (project in projectStore.findAll(userId = userId, includeTemp = true).toList()) {
                if (project.kind != ProjectKind.TEMP || project.createdAt.isAfter(cutoff)) continue
                try {
                    if (sessions.findIdsByProjectId(project.id, userId).isNotEmpty()) continue
                    releaseWorkspace(project.id, userId)
                    released++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Failed to release orphaned workspace {}: {}", project.id, e.message)
                }
            }
        }
        if (released > 0) {
            logger.info("Released {} orphaned temporary workspace(s)", released)
        }
        return released
    }

    /**
     * True when [path] points inside the temporary workspace root, whether or not its project row
     * is still there. Used to reject project-scoped memory writes without depending on how the
     * path was normalised when it reached the client.
     */
    fun isTemporaryPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val candidate = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() ?: return false
        val root = baseDir.toAbsolutePath().normalize()
        return candidate != root && candidate.startsWith(root)
    }

    /** Deterministic project id for a session's temporary workspace. */
    private fun tempProjectId(sessionId: String): String = "$TEMP_ID_PREFIX${idSegment(sessionId)}"

    private suspend fun ensureDir(path: String): Path {
        require(path.isNotBlank()) { "Workspace path is blank for a temporary workspace row" }
        return ensureDir(Path.of(path))
    }

    private suspend fun ensureDir(path: Path): Path = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(path)) {
            Files.createDirectories(path)
        }
        path
    }

    /** Drop the per-user parent directory once its last session directory is gone. */
    private fun pruneEmptyUserDir(workspacePath: Path) {
        val userDir = workspacePath.parent ?: return
        if (userDir == baseDir) return
        val remaining = runCatching { Files.list(userDir).use { it.count() } }.getOrDefault(1L)
        if (remaining == 0L) {
            runCatching { Files.deleteIfExists(userDir) }
                .onFailure { logger.warn("Failed to prune empty directory {}: {}", userDir, it.message) }
        }
    }

    /** Reject ids that are not plain tokens — these ids become filesystem path segments. */
    private fun idSegment(id: String): String {
        require(isSafePathSegment(id)) { "Identifier is not a safe path segment: $id" }
        return id
    }

    companion object {
        /**
         * Directory name under `easyai.data-dir` that holds every temporary workspace. Deliberately
         * dotted and specific so a user-registered project path can never collide with it — a plain
         * `project` dir would make [isTemporaryPath] misclassify a real repo living at
         * `{dataDir}/project/...` and reject its project-scoped memory writes.
         */
        const val WORKSPACE_DIR_NAME: String = ".temp-workspaces"

        /** Project id prefix marking a system-managed temporary workspace. */
        const val TEMP_ID_PREFIX: String = "tmp-"

        /** Placeholder name of a temporary workspace row; the UI renders its own label. */
        const val TEMP_PROJECT_NAME: String = "temp"

        /** Age a workspace must reach before the sweep may consider it orphaned. */
        private const val SWEEP_GRACE_MS: Long = 30 * 60 * 1000L
    }
}

/** A resolved workspace: which project owns it, where it lives, and what kind it is. */
data class Workspace(
    val projectId: String,
    val path: Path,
    val kind: ProjectKind
)
