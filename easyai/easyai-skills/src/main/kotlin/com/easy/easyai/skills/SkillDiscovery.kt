package com.easy.easyai.skills

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.walk

interface SkillDiscovery {
    fun discoverFromPaths(paths: List<Path>): List<SkillInfo>
    fun discoverFromHome(homeDir: Path, dirs: List<String>): List<SkillInfo>
    fun discoverFromUrl(url: String): List<SkillInfo>
    fun discoverByWalkingUp(workDir: Path, skillDirNames: List<String>): List<SkillInfo>
}

class DefaultSkillDiscovery : SkillDiscovery {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun discoverFromPaths(paths: List<Path>): List<SkillInfo> =
        paths.flatMap { dir -> scanDirectory(dir) }

    override fun discoverFromHome(homeDir: Path, dirs: List<String>): List<SkillInfo> {
        val discovered = mutableListOf<SkillInfo>()
        for (relativeDir in dirs) {
            val fullPath = homeDir.resolve(relativeDir)
            if (fullPath.exists() && Files.isDirectory(fullPath)) {
                discovered.addAll(scanDirectory(fullPath))
            }
        }
        return discovered
    }

    override fun discoverFromUrl(url: String): List<SkillInfo> {
        logger.warn("Remote skill discovery from URL '{}' is not yet implemented", url)
        return emptyList()
    }

    override fun discoverByWalkingUp(workDir: Path, skillDirNames: List<String>): List<SkillInfo> {
        val discovered = mutableListOf<SkillInfo>()
        val seen = mutableSetOf<Path>()
        var current: Path? = workDir.toAbsolutePath().normalize()
        while (current != null) {
            for (dirName in skillDirNames) {
                val candidate = current.resolve(dirName)
                if (candidate.exists() && Files.isDirectory(candidate)) {
                    try {
                        if (seen.add(candidate.toRealPath())) {
                            logger.debug("Scanning skills from ancestor directory: {}", candidate)
                            discovered.addAll(scanDirectory(candidate))
                        }
                    } catch (e: Exception) {
                        logger.debug("Failed to resolve real path for {}: {}", candidate, e.message)
                    }
                }
            }
            current = current.parent
        }

        return discovered
    }

    internal fun scanDirectory(dir: Path): List<SkillInfo> {
        if (!dir.exists() || !Files.isDirectory(dir)) {
            logger.debug("Skipping non-existent or non-directory path: {}", dir)
            return emptyList()
        }
        return dir.walk()
            .filter { it.toString().endsWith("SKILL.md") }
            .mapNotNull { path ->
                try {
                    SkillLoader.parse(path)
                } catch (e: Exception) {
                    logger.warn("Failed to parse SKILL.md at {}: {}", path, e.message)
                    null
                }
            }
            .toList()
    }
}
