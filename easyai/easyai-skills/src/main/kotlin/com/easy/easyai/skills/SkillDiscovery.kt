package com.easy.easyai.skills

import org.slf4j.LoggerFactory
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists

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
        // Bounded on purpose: a skill lives at `<root>/<name>/SKILL.md`, so anything deeper is a vendored
        // tree, and `refresh_skills` re-reads these roots per call where the boot-time scan read them once.
        //
        // Uses `walkFileTree` + `preVisitDirectory` rather than `Files.walk` + `filterNot`: the
        // previous form still descended into `.git`, `node_modules`, `__pycache__` and allocated a
        // relativize() per visited path just to throw it away. Skipping at pre-visit keeps the walk
        // linear in the number of real skill files.
        val discovered = mutableListOf<SkillInfo>()
        return try {
            Files.walkFileTree(
                dir,
                java.util.EnumSet.noneOf(java.nio.file.FileVisitOption::class.java),
                MAX_SCAN_DEPTH,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (p == dir) return FileVisitResult.CONTINUE
                        val name = p.fileName?.toString()
                        return if (name != null && name in IGNORED_SCAN_DIRS) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(p: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        if (!p.fileName.toString().endsWith(SKILL_FILE_SUFFIX)) return FileVisitResult.CONTINUE
                        try {
                            discovered.add(SkillLoader.parse(p))
                        } catch (e: Exception) {
                            logger.warn("Failed to parse SKILL.md at {}: {}", p, e.message)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(p: Path, exc: java.io.IOException): FileVisitResult {
                        // One unreadable file must not abort the whole scan.
                        logger.debug("Failed to visit {}: {}", p, exc.message)
                        return FileVisitResult.CONTINUE
                    }
                }
            )
            discovered
        } catch (e: Exception) {
            logger.warn("Failed to scan skill directory {}: {}", dir, e.message)
            discovered
        }
    }

    companion object {
        private const val SKILL_FILE_SUFFIX = "SKILL.md"

        /** Depth relative to a scan root: one level of nesting below the skill directory itself. */
        private const val MAX_SCAN_DEPTH = 4

        /** Never a skill, always expensive to walk. */
        private val IGNORED_SCAN_DIRS = setOf(".git", "node_modules", "__pycache__")
    }
}
