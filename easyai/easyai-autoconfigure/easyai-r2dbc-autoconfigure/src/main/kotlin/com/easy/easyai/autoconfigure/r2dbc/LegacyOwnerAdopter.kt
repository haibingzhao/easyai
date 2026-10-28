package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.host.HostStartupGate
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import com.easy.easyai.repository.database.Tables.Project
import com.easy.easyai.repository.database.Tables.WatchTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Resolves rows that carry no `owner_app`, once, at startup.
 *
 * The column is stamped on insert, so a null owner can only mean "written before ownership was
 * recorded". Such a row is inert by design — no host fires a watch, parks a task or releases a
 * workspace it does not own — which is the safe choice but a silent one: without this pass, an
 * upgraded single-application deployment would simply stop firing its watches.
 *
 * With `easyai.host.adopt-legacy-rows` on (the default), every unowned row is claimed by this
 * application, which is exactly right when one application owns the database. When several share it,
 * only one may keep adoption on; the others turn it off and this class then *reports* what it left
 * alone, including the SQL to assign it, rather than letting an operator find out from missing
 * notifications.
 *
 * The pass runs asynchronously (it waits for the database to initialize), while the startup orphan
 * sweep is triggered right after context refresh. [HostStartupGate] is that ordering contract: the
 * sweep awaits [awaitReady] so it never scans before adoption, when the rows it must park still look
 * unclaimed and are skipped.
 */
class LegacyOwnerAdopter(
    private val db: R2dbcDatabase,
    private val host: HostIdentity,
    private val properties: HostProperties,
    private val dbInitializer: R2dbcDatabaseInitializer
) : SmartLifecycle, HostStartupGate {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (awaitDatabase()) {
                    try {
                        if (properties.adoptLegacyRows) adopt() else reportUnclaimed()
                    } catch (e: Exception) {
                        logger.error("Resolving unowned rows failed; they stay inert until an owner is assigned", e)
                    }
                    reportOtherOwners()
                }
            } finally {
                ready.complete(Unit)
            }
        }
    }

    override suspend fun awaitReady() {
        ready.await()
    }

    private suspend fun awaitDatabase(): Boolean {
        val timeoutMs = 30_000L
        val startedAt = System.currentTimeMillis()
        while (!dbInitializer.isInitialized()) {
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                logger.error(
                    "Timed out waiting for database initialization after {}ms; unowned rows stay inert",
                    timeoutMs
                )
                return false
            }
            delay(100.milliseconds)
        }
        return true
    }

    private suspend fun adopt() = suspendTransaction(db) {
        val watches = WatchTable.update({ WatchTable.ownerApp.isNull() }) {
            it[WatchTable.ownerApp] = host.appName
        }
        val tasks = AsyncTaskTable.update({ AsyncTaskTable.ownerApp.isNull() }) {
            it[AsyncTaskTable.ownerApp] = host.appName
        }
        // Only scratch workspaces: a real project is shared content and needs no owner.
        val workspaces = Project.update({
            Project.ownerApp.isNull() and (Project.kind eq ProjectKind.TEMP.value)
        }) {
            it[Project.ownerApp] = host.appName
        }
        if (watches + tasks + workspaces > 0) {
            logger.info(
                "Claimed unowned rows for '{}': {} watch(es), {} task(s), {} temp workspace(s)",
                host.appName, watches, tasks, workspaces
            )
        }
        Unit
    }

    private suspend fun reportUnclaimed() = suspendTransaction(db) {
        val watches = WatchTable.selectAll()
            .where { WatchTable.ownerApp.isNull() and (WatchTable.status eq WatchStatus.ACTIVE.name) }
            .count()
            .toInt()
        val tasks = AsyncTaskTable.selectAll()
            .where {
                AsyncTaskTable.ownerApp.isNull() and
                    (AsyncTaskTable.status inList listOf(AsyncTaskStatus.PENDING.name, AsyncTaskStatus.RUNNING.name))
            }
            .count()
            .toInt()
        val workspaces = Project.selectAll()
            .where { Project.ownerApp.isNull() and (Project.kind eq ProjectKind.TEMP.value) }
            .count()
            .toInt()
        if (watches + tasks + workspaces > 0) {
            logger.warn(
                "{} watch(es), {} task(s) and {} temp workspace(s) have no owner_app and are inert because " +
                    "adoption is off for '{}'. Assign them once it is clear which application owns them: " +
                    "UPDATE watch SET owner_app = '<app>' WHERE owner_app IS NULL; " +
                    "UPDATE async_task SET owner_app = '<app>' WHERE owner_app IS NULL; " +
                    "UPDATE project SET owner_app = '<app>' WHERE owner_app IS NULL AND kind = 'temp'",
                watches, tasks, workspaces, host.appName
            )
        }
        Unit
    }

    /**
     * Other owner names are normal on a shared database, but an owner that no longer runs (a rename,
     * a decommissioned host) is invisible everywhere else: its watches never fire, its tasks are
     * never parked, and no page shows why. Naming the owners once per boot makes that discoverable.
     */
    private suspend fun reportOtherOwners() {
        val owners = try {
            suspendTransaction(db) {
                val names = linkedSetOf<String>()
                names.addAll(
                    AsyncTaskTable.select(AsyncTaskTable.ownerApp).withDistinct()
                        .mapNotNull { it[AsyncTaskTable.ownerApp] }.toList()
                )
                names.addAll(
                    WatchTable.select(WatchTable.ownerApp).withDistinct()
                        .mapNotNull { it[WatchTable.ownerApp] }.toList()
                )
                names.addAll(
                    Project.select(Project.ownerApp).withDistinct()
                        .mapNotNull { it[Project.ownerApp] }.toList()
                )
                names - host.appName
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not list owner applications: {}", e.message)
            return
        }
        if (owners.isNotEmpty()) {
            logger.info(
                "Rows in this database belong to other applications: {}. A name here that no longer " +
                    "runs means its watches and tasks stay inert until reassigned.",
                owners.sorted().joinToString(", ")
            )
        }
    }

    override fun stop() {
        running.set(false)
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    /** After the database initializer and the agent seed; the order is not otherwise load-bearing. */
    override fun getPhase(): Int = Int.MIN_VALUE + 20
}
