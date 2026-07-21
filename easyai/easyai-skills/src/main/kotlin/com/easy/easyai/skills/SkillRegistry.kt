package com.easy.easyai.skills

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for managing skill lifecycle: registration, lookup, filtering, formatting.
 */
interface SkillRegistry {
    fun register(skill: SkillInfo)
    fun get(name: String): SkillInfo?
    fun all(): List<SkillInfo>
    fun dirs(): Set<Path>

    /** Format skills for injection into system prompt. */
    fun format(verbose: Boolean = false): String
}

/**
 * ConcurrentHashMap-backed default implementation.
 * Auto-discovers skills on construction from config paths + home dirs.
 */
class DefaultSkillRegistry(
    private val discovery: SkillDiscovery,
    private val config: SkillConfig,
) : SkillRegistry {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val skills = ConcurrentHashMap<String, SkillInfo>()
    private val skillDirs = mutableSetOf<Path>()

    init {
        autoDiscover()
    }

    private fun autoDiscover() {
        if (!config.enabled) {
            logger.info("Skill system is disabled")
            return
        }

        // Discover from explicit config paths
        val workDir = Path.of(config.workDir).toAbsolutePath()
        val configPaths = config.paths.map { resolvePath(it, workDir) }
        if (configPaths.isNotEmpty()) {
            val discovered = discovery.discoverFromPaths(configPaths)
            discovered.forEach { register(it) }
            configPaths.forEach { skillDirs.add(it) }
            logger.info("Discovered {} skills from config paths", discovered.size)
        }

        // Discover from home directories (~/.agents/skills, ~/.easyai/skills)
        if (config.homeSkillDirs.isNotEmpty()) {
            val homeDir = Path.of(System.getProperty("user.home"))
            val discovered = discovery.discoverFromHome(homeDir, config.homeSkillDirs)
            discovered.forEach { register(it) }
            logger.info("Discovered {} skills from home directories", discovered.size)
        }

        // Discover from ancestor directories (walk up from workDir)
        if (config.homeSkillDirs.isNotEmpty()) {
            val discovered = discovery.discoverByWalkingUp(workDir, config.homeSkillDirs)
            discovered.forEach { register(it) }
            if (discovered.isNotEmpty()) {
                logger.info("Discovered {} skills from ancestor directories", discovered.size)
            }
        }

        logger.info("Total skills registered: {}", skills.size)
    }

    override fun register(skill: SkillInfo) {
        if (skills.containsKey(skill.name)) {
            logger.warn("Duplicate skill name '{}': replacing with {}", skill.name, skill.location)
        }
        skills[skill.name] = skill
        skillDirs.add(skill.location.parent)
    }

    override fun get(name: String): SkillInfo? = skills[name]

    override fun all(): List<SkillInfo> = skills.values.sortedBy { it.name }

    override fun dirs(): Set<Path> = skillDirs.toSet()

    override fun format(verbose: Boolean): String {
        val skillsWithDesc = skills.values.filter { !it.description.isNullOrBlank() }.sortedBy { it.name }
        if (skillsWithDesc.isEmpty()) return ""

        return if (verbose) {
            buildVerbose(skillsWithDesc)
        } else {
            buildConcise(skillsWithDesc)
        }
    }

    private fun buildVerbose(skillsList: List<SkillInfo>): String {
        val sb = StringBuilder("<available_skills>\n")
        for (skill in skillsList) {
            sb.append("  <skill>\n")
            sb.append("    <name>${skill.name}</name>\n")
            sb.append("    <description>${skill.description}</description>\n")
            sb.append("    <location>file://${skill.location}</location>\n")
            sb.append("  </skill>\n")
        }
        sb.append("</available_skills>")
        return sb.toString()
    }

    private fun buildConcise(skillsList: List<SkillInfo>): String {
        val sb = StringBuilder("## Available Skills\n")
        for (skill in skillsList) {
            sb.append("- **${skill.name}**: ${skill.description}\n")
        }
        return sb.toString()
    }

    private fun resolvePath(pathStr: String, workDir: Path): Path {
        return when {
            pathStr.startsWith("~/") -> Path.of(System.getProperty("user.home")).resolve(pathStr.removePrefix("~/"))
            Path.of(pathStr).isAbsolute -> Path.of(pathStr)
            else -> workDir.resolve(pathStr)
        }
    }
}