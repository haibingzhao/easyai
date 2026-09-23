package com.easy.easyai.skills

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Composite identity of one skill in the in-memory snapshot.
 *
 * [projectPath] == null means GLOBAL: a home-directory skill or an explicitly configured shared
 * source. The value is always what [SkillScopeResolver] derived from the skill's location — the
 * registry keeps no separate scope truth. Unknown install roots cannot be registered.
 */
data class SkillKey(val name: String, val projectPath: Path?)

/**
 * Interface for managing skill lifecycle: registration, lookup, filtering.
 */
interface SkillRegistry {
    /** Register one discovered skill; throws [IllegalArgumentException] for an unknown install root. */
    fun register(skill: SkillInfo)

    /**
     * Resolve one skill for a request rooted at [projectPath]: only that exact PROJECT hit wins,
     * with GLOBAL as fallback. This is a filesystem lookup, not a user authorization decision.
     */
    fun get(name: String, projectPath: Path?): SkillInfo?

    /** Every registered skill, across all granularities. Catalog bookkeeping and access resolution only. */
    fun all(): List<SkillInfo>

    /** This exact project's skills plus GLOBAL, project first by name; does not authorize a user. */
    fun visibleFor(projectPath: Path?): List<SkillInfo>

    /** Drop one skill from the in-memory snapshot, returning what was removed. */
    fun remove(name: String, projectPath: Path?): SkillInfo?

    /**
     * Re-read the skill directories and publish what is there now, reporting what moved.
     *
     * A skill written by the `write` tool is only on disk until this runs: registration, the catalog
     * claim and the search index all key off the discovered snapshot. [projectRoots] extends the set
     * of workspace roots to scan — the requesting session's project, and every root the DB says has
     * skills, which is not necessarily where the server was started. The scan set only grows: a
     * re-scan that passes fewer roots still re-reads everything known, so nothing is pruned just
     * because one caller asked about one project.
     */
    fun rescan(projectRoots: Set<Path>): RegistryDelta

    /** Every project root this process has ever registered a skill from, plus the ones handed to [rescan]. */
    fun knownProjectRoots(): Set<Path>

    /**
     * Every skill directory this process has seen. Additive by design: a re-scan that drops a skill
     * does not revoke the record of where it was found, since nothing authorises against this set.
     */
    fun dirs(): Set<Path>

    /**
     * Whether the registry has completed at least one disk scan.
     *
     * Startup wiring uses this to decide whether an on-ready pass must trigger a fresh scan or can
     * reuse the one the registry already performed on first access. Read methods transparently
     * trigger the initial scan on first call, so `false` here means "nothing has asked yet".
     */
    fun hasCompletedInitialScan(): Boolean
}

/**
 * What one re-scan changed, shaped so a caller can tell the model whether its new file was picked up.
 *
 * @param added names present now but not before
 * @param removed names that were registered and whose SKILL.md is gone
 * @param total skills registered after the pass
 * @param addedKeys the [SkillKey]s behind [added] — claiming a catalog row must know *which*
 *   granularity is new, because the same name can be added in one project while surviving in another
 * @param removedKeys the [SkillKey]s behind [removed]
 * @param updatedKeys keys that survived the pass but whose parsed source changed (content,
 *   description, or location) — refresh reporting must distinguish body updates from no-ops
 */
data class RegistryDelta(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val total: Int = 0,
    val addedKeys: List<SkillKey> = emptyList(),
    val removedKeys: List<SkillKey> = emptyList(),
    val updatedKeys: List<SkillKey> = emptyList()
) {
    /** Whether anything about the visible skill set changed. */
    val changed: Boolean
        get() = added.isNotEmpty() || removed.isNotEmpty() || updatedKeys.isNotEmpty()
}

/**
 * ConcurrentHashMap-backed default implementation, keyed by [SkillKey] so same-named skills of
 * different workspaces coexist.
 *
 * The first disk scan is **lazy**: whichever comes first between a read method and a [rescan] call
 * performs it. That lets the `SkillIndexStartupRunner` be the only startup scan when the retrieval
 * chain is wired (its `reconcileAllOwners` calls `rescan` with catalog-derived roots), while
 * keeping the no-RAG deployment working off the first `all()`/`get()` request. The old constructor
 * scan meant both paths scanned the disk twice at boot.
 *
 * A single [ReentrantLock] serialises [rescan] against itself and against [register], so a
 * concurrent external register cannot slip between the rescan's snapshot of `previous` keys and
 * its prune step (which would otherwise wipe the freshly-registered skill).
 */
class DefaultSkillRegistry(
    private val discovery: SkillDiscovery,
    private val config: SkillConfig,
) : SkillRegistry {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val skills = ConcurrentHashMap<SkillKey, SkillInfo>()
    private val skillDirs = ConcurrentHashMap.newKeySet<Path>()

    /**
     * Project roots whose `<root>/.easyai/skills` tree this registry has committed to re-reading.
     * Monotonic on purpose — the prune step trusts it: a skill only disappears when the root that
     * hosted it was actually scanned again and the file was not found.
     */
    private val knownRoots = Collections.newSetFromMap(ConcurrentHashMap<Path, Boolean>())

    /** Guards the compound "snapshot keys -> discover -> register -> prune" sequence in [rescan]. */
    private val scanLock = ReentrantLock()
    private val initialScanDone = AtomicBoolean(false)

    override fun rescan(projectRoots: Set<Path>): RegistryDelta = scanLock.withLock {
        val previous = skills.toMap()
        val discovered = discoverAll(projectRoots)
        // Register first, prune after: clearing would hand load_skill a snapshot with nothing in it.
        discovered.forEach { registerInternal(it) }
        val current = discovered.mapTo(mutableSetOf()) { keyOf(it) }
        val removedKeys = sortedByKey(previous.keys - current)
        removedKeys.forEach { skills.remove(it) }
        val addedKeys = sortedByKey(current - previous.keys)
        // Survivors whose parsed source (content, description, location) changed on disk.
        val updatedKeys = sortedByKey(
            current.filterTo(mutableSetOf()) { it in previous.keys && skills[it] != previous[it] }
        )
        initialScanDone.set(true)
        val delta = RegistryDelta(
            added = addedKeys.map { it.name },
            removed = removedKeys.map { it.name },
            total = skills.size,
            addedKeys = addedKeys,
            removedKeys = removedKeys,
            updatedKeys = updatedKeys
        )
        logger.info(
            "Skill re-scan registered {} skill(s): added={}, updated={}, removed={}",
            delta.total, delta.addedKeys, delta.updatedKeys, delta.removedKeys
        )
        if (discovered.isEmpty()) {
            logger.debug("No SKILL.md found under {}", config.homeSkillDirs)
        }
        delta
    }

    /** Read every source once and return what was found, in source order, touching no registry state. */
    private fun discoverAll(extraProjectRoots: Set<Path>): List<SkillInfo> {
        if (!config.enabled) {
            logger.info("Skill system is disabled")
            return emptyList()
        }

        val discovered = mutableListOf<SkillInfo>()
        val workDir = Path.of(config.workDir).toAbsolutePath().normalize()

        // Discover from explicit config paths
        val configPaths = config.paths.map { resolvePath(it, workDir) }
        if (configPaths.isNotEmpty()) {
            val fromPaths = discovery.discoverFromPaths(configPaths)
            discovered += fromPaths
            configPaths.forEach { skillDirs.add(it) }
            logger.info("Discovered {} skills from config paths", fromPaths.size)
        }

        // Discover from home directories (~/.agents/skills, ~/.easyai/skills) — the GLOBAL bucket
        if (config.homeSkillDirs.isNotEmpty()) {
            val homeDir = Path.of(System.getProperty("user.home"))
            val fromHome = discovery.discoverFromHome(homeDir, config.homeSkillDirs)
            discovered += fromHome
            logger.info("Discovered {} skills from home directories", fromHome.size)
        }

        // Discover from every project root known to this process, plus the ones this pass added.
        // Roots are scanned directly (<root>/.easyai/skills); a vanished root costs one exists() check.
        val roots = mutableSetOf(workDir)
        roots += knownRoots
        extraProjectRoots.forEach { roots.add(it.toAbsolutePath().normalize()) }
        knownRoots += roots
        val rootCandidates = roots.flatMap { root -> config.homeSkillDirs.map { root.resolve(it) } }
        val fromRoots = discovery.discoverFromPaths(rootCandidates)
        discovered += fromRoots
        if (fromRoots.isNotEmpty()) {
            logger.info("Discovered {} skills from {} project roots", fromRoots.size, roots.size)
        }

        return discovered.filter { skill ->
            val recognised = SkillScopeResolver.classify(skill, config) != null
            if (!recognised) logger.warn("Rejecting skill with unknown install root: {}", skill.location)
            recognised
        }
    }

    override fun register(skill: SkillInfo) {
        // Take the scan lock so a concurrent rescan cannot prune this skill between its `previous`
        // snapshot and its prune step. External callers are rare (production code goes through
        // `rescan`), so the contention cost is negligible.
        scanLock.withLock { registerInternal(skill) }
        ensureInitialScanMarked()
    }

    private fun registerInternal(skill: SkillInfo) {
        val key = keyOf(skill)
        val existing = skills.put(key, skill)
        // Same key + different location means the file moved (or two roots resolve to one
        // granularity); worth reporting, but a plain re-scan re-reading the same file is not.
        if (existing != null && existing.location != skill.location) {
            logger.warn("Skill {} re-registered from a different directory: {} -> {}", key, existing.location, skill.location)
        }
        skill.location.parent?.let { skillDirs.add(it) }
        key.projectPath?.let { knownRoots.add(it) }
    }

    override fun get(name: String, projectPath: Path?): SkillInfo? {
        ensureInitialScan()
        for (root in SkillScopeResolver.candidateRoots(projectPath)) {
            skills[SkillKey(name, root)]?.let { return it }
        }
        return null
    }

    override fun remove(name: String, projectPath: Path?): SkillInfo? =
        skills.remove(SkillKey(name, projectPath?.toAbsolutePath()?.normalize()))

    override fun all(): List<SkillInfo> {
        ensureInitialScan()
        return skills.values.sortedBy { it.name }
    }

    override fun visibleFor(projectPath: Path?): List<SkillInfo> {
        ensureInitialScan()
        val candidates = SkillScopeResolver.candidateRoots(projectPath)
        if (candidates.size == 1) return visibleUnder(null)
        // Nearest granularity wins a same-named skill, GLOBAL is last.
        val rank = HashMap<Path?, Int>(candidates.size)
        candidates.forEachIndexed { index, root -> rank.putIfAbsent(root, index) }
        val best = HashMap<String, Pair<Int, SkillInfo>>()
        for ((key, skill) in skills) {
            val distance = rank[key.projectPath] ?: continue
            val incumbent = best[key.name]
            if (incumbent == null || distance < incumbent.first) best[key.name] = distance to skill
        }
        return best.values.map { it.second }.sortedBy { it.name }
    }

    private fun visibleUnder(root: Path?): List<SkillInfo> =
        skills.filterKeys { it.projectPath == root }.values.sortedBy { it.name }

    override fun knownProjectRoots(): Set<Path> {
        ensureInitialScan()
        return knownRoots.toSet()
    }

    override fun dirs(): Set<Path> {
        ensureInitialScan()
        return skillDirs.toSet()
    }

    override fun hasCompletedInitialScan(): Boolean = initialScanDone.get()

    /**
     * Trigger the lazy first scan the first time any read method is called. Idempotent and
     * double-checked: only one thread does the work, the rest fall through once it is done.
     */
    private fun ensureInitialScan() {
        if (initialScanDone.get()) return
        scanLock.withLock {
            if (initialScanDone.get()) return
            val discovered = discoverAll(extraProjectRoots = emptySet())
            discovered.forEach { registerInternal(it) }
            initialScanDone.set(true)
            logger.info("Initial skill scan registered {} skill(s)", skills.size)
            if (discovered.isEmpty()) {
                logger.debug("No SKILL.md found under {}", config.homeSkillDirs)
            }
        }
    }

    /**
     * An external [register] call also counts as "the registry is populated": the caller has taken
     * responsibility for the snapshot, so the lazy scan would only re-read what they just wrote.
     */
    private fun ensureInitialScanMarked() {
        initialScanDone.compareAndSet(false, true)
    }

    private fun keyOf(skill: SkillInfo): SkillKey =
        SkillKey(skill.name, SkillScopeResolver.resolve(skill, config).second)

    /**
     * GLOBAL first, then PROJECT sorted by name and path: stable ordering for logs and delta
     * reporting. Extracted from the previous private extension form to keep the module free of
     * extension functions per the workspace convention.
     */
    private fun sortedByKey(keys: Set<SkillKey>): List<SkillKey> =
        keys.sortedWith(compareBy({ it.projectPath != null }, { it.name }, { it.projectPath?.toString() ?: "" }))

    private fun resolvePath(pathStr: String, workDir: Path): Path {
        return when {
            pathStr.startsWith("~/") -> Path.of(System.getProperty("user.home")).resolve(pathStr.removePrefix("~/"))
            Path.of(pathStr).isAbsolute -> Path.of(pathStr)
            else -> workDir.resolve(pathStr)
        }
    }
}
