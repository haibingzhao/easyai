package com.easy.easyai.snapshot

import com.easy.easyai.common.util.ProcessExecutor
import com.easy.easyai.snapshot.model.FileChangeStatus
import com.easy.easyai.snapshot.model.FileDiff
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class GitSnapshotServiceGitProtocolTest {

    @TempDir
    lateinit var tempDir: Path
    private lateinit var project: Path
    private lateinit var service: GitSnapshotService
    private val sessionId = "protocol-test"

    @BeforeEach
    fun setUp() {
        project = Files.createDirectory(tempDir.resolve("project"))
        service = GitSnapshotService(Files.createDirectory(tempDir.resolve("data")))
    }

    @Nested
    inner class DiffStats {

        @Test
        fun `reports additions deletions modifications renames and binary files`() = runBlocking {
            write("deleted.txt", "removed\nlines\n")
            write("modified.txt", "before\nkeep\n")
            write("old.txt", "unique rename content\n")
            val baseline = service.ensureBaseline(project, sessionId)
            Files.delete(project.resolve("deleted.txt"))
            write("modified.txt", "after\nkeep\n")
            Files.move(project.resolve("old.txt"), project.resolve("renamed.txt"))
            write("added.txt", "new\nlines\n")
            Files.write(project.resolve("binary.bin"), byteArrayOf(0, 1, 2))
            val commit = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)

            assertDiffs(
                listOf(
                    FileDiff("added.txt", additions = 2, status = FileChangeStatus.ADDED),
                    FileDiff("binary.bin", status = FileChangeStatus.ADDED),
                    FileDiff("deleted.txt", deletions = 2, status = FileChangeStatus.DELETED),
                    FileDiff("modified.txt", additions = 1, deletions = 1),
                    FileDiff("renamed.txt", status = FileChangeStatus.RENAMED)
                ),
                service.diffStats(project, baseline, commit)
            )
        }

        @Test
        fun `preserves raw paths through additions modifications and deletions`() = runBlocking {
            val baseline = service.ensureBaseline(project, sessionId)
            val paths = unusualPaths(baseline)
            paths.forEach { write(it, "one\ntwo\n") }
            val added = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            assertDiffs(
                paths.map { FileDiff(it, additions = 2, status = FileChangeStatus.ADDED) },
                service.diffStats(project, baseline, added)
            )
            paths.forEach { write(it, "one\ntwo\nthree\n") }
            val modified = service.commitAs(project, sessionId, ChangeAuthor.USER)
            assertDiffs(
                paths.map { FileDiff(it, additions = 1) },
                service.diffStats(project, added, modified)
            )
            paths.forEach { Files.delete(project.resolve(it)) }
            val deleted = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            assertDiffs(
                paths.map { FileDiff(it, deletions = 3, status = FileChangeStatus.DELETED) },
                service.diffStats(project, modified, deleted)
            )
        }

        @Test
        fun `preserves raw rename destinations in stats and author maps`() = runBlocking {
            val paths = unusualPaths("a".repeat(40))
            paths.forEachIndexed { index, path -> write("old/$path", "unique content $index\n") }
            val baseline = service.ensureBaseline(project, sessionId)
            paths.forEach { Files.move(project.resolve("old/$it"), project.resolve(it)) }
            val renamed = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            assertDiffs(
                paths.map { FileDiff(it, status = FileChangeStatus.RENAMED) },
                service.diffStats(project, baseline, renamed)
            )
            assertEquals(
                listOf(paths.associateWith { "llm" }),
                service.fileAuthorsInChain(project, listOf(baseline to renamed))
            )
        }

        @Test
        fun `returns empty stats only for a successful empty diff`() = runBlocking<Unit> {
            val baseline = service.ensureBaseline(project, sessionId)
            assertTrue(service.diffStats(project, baseline, baseline).isEmpty())
            assertFailsWith<RuntimeException> {
                service.diffStats(project, "missing-commit", baseline)
            }
        }
    }

    @Nested
    inner class FileAuthorsInChain {

        @Test
        fun `replays last touching author for overlapping ranges and renames`() = runBlocking {
            write("deleted.txt", "remove\n")
            write("old.txt", "unique rename content\n")
            write("shared.txt", "baseline\n")
            val baseline = service.ensureBaseline(project, sessionId)
            write("shared.txt", "agent\n")
            write("added.txt", "agent added\n")
            val agent = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            write("shared.txt", "user\n")
            Files.delete(project.resolve("deleted.txt"))
            Files.move(project.resolve("old.txt"), project.resolve("renamed.txt"))
            val user = service.commitAs(project, sessionId, ChangeAuthor.USER)
            val latest = mapOf("shared.txt" to "user", "deleted.txt" to "user", "renamed.txt" to "user")
            assertEquals(
                listOf(
                    latest,
                    emptyMap(),
                    latest + ("added.txt" to "llm"),
                    mapOf("shared.txt" to "llm", "added.txt" to "llm")
                ),
                service.fileAuthorsInChain(
                    project, listOf(agent to user, agent to agent, baseline to user, baseline to agent)
                )
            )
        }

        @Test
        fun `distinguishes raw paths from headers without stripping path newlines`() = runBlocking {
            val baseline = service.ensureBaseline(project, sessionId)
            val paths = unusualPaths(baseline)
            paths.forEach { write(it, "agent\n") }
            val agent = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            val userPaths = listOf("\n\nleading.txt", "\ninside\nname.txt", "@@COMMIT:$baseline", baseline)
            userPaths.forEach { write(it, "user\n") }
            val user = service.commitAs(project, sessionId, ChangeAuthor.USER)
            assertEquals(
                listOf(
                    userPaths.associateWith { "user" },
                    paths.associateWith { if (it in userPaths) "user" else "llm" },
                    paths.associateWith { "llm" }
                ),
                service.fileAuthorsInChain(project, listOf(agent to user, baseline to user, baseline to agent))
            )
        }

        @Test
        fun `handles consecutive empty commits before and after file changes`() = runBlocking {
            val baseline = service.ensureBaseline(project, sessionId)
            val emptyFirst = emptyCommit(baseline)
            val emptySecond = emptyCommit(emptyFirst)
            write("\n", "agent\n")
            val changed = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            val emptyLast = emptyCommit(changed)
            assertEquals(
                listOf(emptyMap(), mapOf("\n" to "llm"), emptyMap(), mapOf("\n" to "llm")),
                service.fileAuthorsInChain(
                    project,
                    listOf(changed to emptyLast, emptySecond to changed, baseline to emptySecond, baseline to emptyLast)
                )
            )
        }

        @Test
        fun `does not fall back when the git range succeeds with empty stdout`() = runBlocking {
            write("file.txt", "baseline\n")
            val baseline = service.ensureBaseline(project, sessionId)
            val observed = spyk(service)
            assertEquals(
                listOf(emptyMap(), emptyMap()),
                observed.fileAuthorsInChain(project, listOf(baseline to baseline, baseline to baseline))
            )
            coVerify(exactly = 0) { observed.determineFileAuthors(any(), any(), any()) }
        }

        @Test
        fun `falls back per range after a real git failure and keeps valid ranges`() = runBlocking {
            val baseline = service.ensureBaseline(project, sessionId)
            write("file.txt", "agent\n")
            val agent = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            val observed = spyk(service)
            assertEquals(
                listOf(mapOf("file.txt" to "llm"), emptyMap()),
                observed.fileAuthorsInChain(project, listOf(baseline to agent, "missing-commit" to baseline))
            )
            coVerify(exactly = 1) { observed.determineFileAuthors(project, baseline, agent) }
            coVerify(exactly = 1) { observed.determineFileAuthors(project, "missing-commit", baseline) }
        }

        @Test
        fun `retains per range fallback for an endpoint outside the walked chain`() = runBlocking {
            val baseline = service.ensureBaseline(project, sessionId)
            write("main.txt", "agent\n")
            val agent = service.commitAs(project, sessionId, ChangeAuthor.LLM_AGENT)
            val otherBaseline = service.ensureBaseline(project, "other-session")
            write("branch.txt", "branch\n")
            val otherCommit = service.commitAs(project, "other-session", ChangeAuthor.USER)
            Files.delete(project.resolve("branch.txt"))
            write("main.txt", "user\n")
            val user = service.commitAs(project, sessionId, ChangeAuthor.USER)
            val observed = spyk(service)
            assertEquals(
                listOf(mapOf("main.txt" to "user"), mapOf("branch.txt" to "user"), mapOf("main.txt" to "llm")),
                observed.fileAuthorsInChain(
                    project, listOf(agent to user, otherBaseline to otherCommit, baseline to agent)
                )
            )
            coVerify(exactly = 1) { observed.determineFileAuthors(project, otherBaseline, otherCommit) }
            coVerify(exactly = 1) { observed.determineFileAuthors(any(), any(), any()) }
        }
    }

    private fun unusualPaths(hash: String): List<String> = listOf(
        "plain.txt", "\tleading.txt", "internal\ttab.txt", "\t", "\n",
        "\n\nleading.txt", "\ninside\nname.txt", "\"quoted\"", "literal\\n.txt", "中文\\n.txt",
        "old => literal.txt", "@@COMMIT:", "@@COMMIT:$hash", hash, "agent@easyai.dev"
    )

    private fun write(path: String, content: String) {
        val target = project.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private fun assertDiffs(expected: List<FileDiff>, actual: List<FileDiff>) {
        assertEquals(expected.sortedBy { it.path }, actual.sortedBy { it.path })
    }

    private suspend fun emptyCommit(parent: String): String {
        val hash = git("commit-tree", "$parent^{tree}", "-p", parent, "-m", "empty").trim()
        git("update-ref", "refs/easyai/session-$sessionId", hash)
        return hash
    }

    private suspend fun git(vararg args: String): String {
        val gitDir = service.getSnapshotRepoDir(project).resolve(".git")
        val result = assertNotNull(
            ProcessExecutor.execute(
                command = listOf("git", "--git-dir=$gitDir", "--work-tree=$project") + args,
                workDir = project,
                timeoutSeconds = 30,
                env = mapOf(
                    "GIT_AUTHOR_NAME" to "Test", "GIT_AUTHOR_EMAIL" to ChangeAuthor.USER.gitAuthorEmail,
                    "GIT_COMMITTER_NAME" to "Test", "GIT_COMMITTER_EMAIL" to ChangeAuthor.USER.gitAuthorEmail
                )
            )
        )
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout
    }
}
