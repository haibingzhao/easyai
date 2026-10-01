package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.streams.asSequence

/**
 * Composite identity of one skill in the in-memory snapshot: the owning user (or `system` for
 * the shared layer) plus the skill name. A name is unique within one owner; the same name under
 * two owners is two distinct entries, and a user's own skill shadows the shared one at the view
 * layer ([SkillModelView]).
 */
data class SkillKey(val owner: String, val name: String)

/**
 * Interface for managing skill lifecycle: registration, lookup, filtering.
 */
interface SkillRegistry {
    /** Register one discovered skill under [owner]; the sync layer resolves the owner before calling. */
    fun register(owner: String, skill: SkillInfo)

    /**
     * Resolve one skill of [owner] by name. This is an in-memory lookup, not a user
     * authorization decision — authorization is the catalog gate's job.
     */
    fun get(owner: String, name: String): SkillInfo?

    /** The owner's own skills plus the shared `system` skills, the owner's winning by name. */
    fun visibleFor(owner: String): List<SkillInfo>

    /** Drop one skill from the in-memory snapshot, returning what was removed. */
    fun remove(owner: String, name: String): SkillInfo?

    /**
     * Merge authoritative owner ids for canonical root paths (catalog `root_path` → `user_id`).
     *
     * Directory names are sanitized segments that can differ from the owner id, so scans must ask
     * here before guessing from the name. Entries are never removed: a root that stops being
     * pinned keeps its last known owner, which is still the correct guess.
     */
    fun pinOwners(pinners: Map<String, String>)

    /**
     * Re-read the given owner roots (plus every root this process already knows) and publish what
     * is there now, reporting what moved.
     *
     * A skill written by the `write` tool is only on disk until this runs: registration, the
     * catalog claim and the search index all key off the discovered snapshot. The scan set only
     * grows: a re-scan that passes fewer roots still re-reads everything known.
     */
    fun rescan(ownerRoots: Set<Path>): RegistryDelta
}

/**
 * What one re-scan changed, shaped so a caller can tell the model whether its new file was picked up.
 *
 * @param added keys present now but not before
 * @param removed keys that were registered and whose SKILL.md is gone
 * @param total skills registered after the pass
 * @param updatedKeys keys that survived the pass but whose parsed source changed (content,
 *   description, or location) — refresh reporting must distinguish body updates from no-ops
 */
data class RegistryDelta(
    val added: List<SkillKey> = emptyList(),
    val removed: List<SkillKey> = emptyList(),
    val total: Int = 0,
    val updatedKeys: List<SkillKey> = emptyList()
)

/**
 * ConcurrentHashMap-backed default implementation, keyed by [SkillKey] so same-named skills of
 * different owners coexist.
 *
 * The first disk scan is **lazy**: whichever comes first between a read method and a [rescan] call
 * performs it, listing `{rootDir}` one level to find owner roots. That lets the startup sync be the
 * only scan when the retrieval chain is wired, while keeping the no-catalog deployment working off
 * the first `get()`/`visibleFor()` request. A [register] call does *not* spend the scan: publishing
 * one skill says nothing about the rest of the disk, and suppressing the scan would hide every
 * other installed skill until an explicit [rescan].
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

    /**
     * Owner roots this registry has committed to re-reading. Monotonic on purpose — the prune step
     * trusts it: a skill only disappears when the root that hosted it was scanned again and the
     * file was not found.
     */
    private val knownRoots: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    /** Guards the compound "snapshot keys -> discover -> register -> prune" sequence in [rescan]. */
    private val scanLock = ReentrantLock()
    private val initialScanDone = AtomicBoolean(false)

    override fun rescan(ownerRoots: Set<Path>): RegistryDelta = scanLock.withLock {
        val previous = skills.toMap()
        val discovered = discoverAll(ownerRoots)
        // Register first, prune after: clearing would hand load_skill a snapshot with nothing in it.
        discovered.forEach { (owner, skill) -> registerInternal(owner, skill) }
        val current = discovered.mapTo(mutableSetOf()) { (owner, skill) -> SkillKey(owner, skill.name) }
        val removedKeys = sortedByKey(previous.keys - current)
        removedKeys.forEach { skills.remove(it) }
        val addedKeys = sortedByKey(current - previous.keys)
        // Survivors whose parsed source (content, description, location) changed on disk.
        val updatedKeys = sortedByKey(
            current.filterTo(mutableSetOf()) { it in previous.keys && skills[it] != previous[it] }
        )
        initialScanDone.set(true)
        val delta = RegistryDelta(
            added = addedKeys,
            removed = removedKeys,
            total = skills.size,
            updatedKeys = updatedKeys
        )
        logger.info(
            "Skill re-scan registered {} skill(s): added={}, updated={}, removed={}",
            delta.total, delta.added, delta.updatedKeys, delta.removed
        )
        if (discovered.isEmpty()) {
            logger.debug("No SKILL.md found under {}", config.rootDir)
        }
        delta
    }

    /** Read every known owner root once and return (owner, skill) pairs, touching no registry state. */
    private fun discoverAll(extraOwnerRoots: Set<Path>): List<Pair<String, SkillInfo>> {
        if (!config.enabled) {
            logger.info("Skill system is disabled")
            return emptyList()
        }
        val roots = mutableSetOf<Path>()
        roots += filesystemOwnerRoots()
        roots += knownRoots
        extraOwnerRoots.forEach { roots.add(it.toAbsolutePath().normalize()) }
        knownRoots.addAll(roots)

        val discovered = mutableListOf<Pair<String, SkillInfo>>()
        val rootDir = Path.of(config.rootDir).toAbsolutePath().normalize()
        for (root in roots) {
            val owner = discoverOwner(root, rootDir) ?: continue
            discovery.discoverOwnerRoot(root).forEach { discovered += owner to it }
        }
        return discovered
    }

    /** Owner id of [root]: the catalog-pinned `root_path` wins; otherwise the directory name under rootDir. */
    private fun discoverOwner(root: Path, rootDir: Path): String? {
        if (!SkillPaths.isWithin(rootDir, root)) return null
        ownerPinners[SkillPaths.canonicalize(root)]?.let { return it }
        val segment = root.fileName?.toString() ?: return null
        return if (SkillPaths.safeSegment(segment) == segment) segment else null
    }

    /** canonical root_path -> owner id. */
    private val ownerPinners = ConcurrentHashMap<String, String>()

    override fun pinOwners(pinners: Map<String, String>) {
        ownerPinners.putAll(pinners)
    }

    override fun register(owner: String, skill: SkillInfo) {
        // Take the scan lock so a concurrent rescan cannot prune this skill between its `previous`
        // snapshot and its prune step.
        scanLock.withLock { registerInternal(owner, skill) }
    }

    private fun registerInternal(owner: String, skill: SkillInfo) {
        val key = SkillKey(owner, skill.name)
        val existing = skills.put(key, skill)
        if (existing != null && existing.location != skill.location) {
            logger.warn("Skill {} re-registered from a different directory: {} -> {}", key, existing.location, skill.location)
        }
    }

    override fun get(owner: String, name: String): SkillInfo? {
        ensureInitialScan()
        return skills[SkillKey(owner, name)] ?: skills[SkillKey(SkillCatalogEntry.DEFAULT_USER_ID, name)]
    }

    override fun visibleFor(owner: String): List<SkillInfo> {
        ensureInitialScan()
        val system = SkillCatalogEntry.DEFAULT_USER_ID
        val best = LinkedHashMap<String, SkillInfo>()
        if (owner != system) {
            skills.filterKeys { it.owner == owner }.forEach { (key, skill) -> best[key.name] = skill }
        }
        skills.filterKeys { it.owner == system }.forEach { (key, skill) ->
            best.putIfAbsent(key.name, skill)
        }
        return best.values.sortedBy { it.name }
    }

    override fun remove(owner: String, name: String): SkillInfo? = skills.remove(SkillKey(owner, name))

    /**
     * Trigger the lazy first scan the first time any read method is called. Idempotent and
     * double-checked: only one thread does the work, the rest fall through once it is done.
     */
    private fun ensureInitialScan() {
        if (initialScanDone.get()) return
        scanLock.withLock {
            if (initialScanDone.get()) return
            val discovered = discoverAll(extraOwnerRoots = emptySet())
            discovered.forEach { (owner, skill) -> registerInternal(owner, skill) }
            initialScanDone.set(true)
            logger.info("Initial skill scan registered {} skill(s)", skills.size)
        }
    }

    /**
     * Immediate one-level listing of `{rootDir}` — owner directories are never nested deeper.
     * Dot directories are working state (the sync service stages restores inside an owner root).
     */
    private fun filesystemOwnerRoots(): Set<Path> {
        val rootDir = Path.of(config.rootDir)
        if (!Files.isDirectory(rootDir)) return emptySet()
        return try {
            Files.list(rootDir).use { stream ->
                stream.asSequence()
                    .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                    .toSet()
            }
        } catch (e: Exception) {
            logger.warn("Failed to list skill root {}: {}", rootDir, e.message)
            emptySet()
        }
    }

    /**
     * Owner-first alphabetical ordering: stable for logs and delta reporting. Extracted from the
     * previous private extension form to keep the module free of extension functions per the
     * workspace convention.
     */
    private fun sortedByKey(keys: Set<SkillKey>): List<SkillKey> =
        keys.sortedWith(compareBy({ it.owner }, { it.name }))
}
