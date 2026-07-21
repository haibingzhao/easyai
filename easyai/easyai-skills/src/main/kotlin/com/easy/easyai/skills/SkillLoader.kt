package com.easy.easyai.skills

import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.readText
import org.slf4j.LoggerFactory

/**
 * Parses SKILL.md files: extracts YAML front matter and markdown body.
 */
object SkillLoader {

    private val logger = LoggerFactory.getLogger(SkillLoader::class.java)
    private val yamlMapper = YAMLMapper()

    /**
     * Parse a SKILL.md file at the given path.
     * @return SkillInfo with extracted metadata and content.
     * @throws IllegalArgumentException if the required 'name' field is missing.
     */
    fun parse(path: Path): SkillInfo {
        val content = path.readText()
        val (frontmatter, body) = extractFrontmatter(content)
        val name = frontmatter["name"] as? String
        if (name.isNullOrBlank()) {
            throw IllegalArgumentException("SKILL.md at $path missing required 'name' field")
        }
        val description = frontmatter["description"] as? String
        val tags = when (val raw = frontmatter["tags"]) {
            is List<*> -> raw.filterIsInstance<String>().toSet()
            is String -> setOf(raw)
            else -> emptySet()
        }
        val examples = when (val raw = frontmatter["examples"]) {
            is List<*> -> raw.filterIsInstance<String>().toSet()
            is String -> setOf(raw)
            else -> emptySet()
        }
        return SkillInfo(
            name = name,
            description = description,
            location = path.toAbsolutePath(),
            content = body.trim(),
            tags = tags,
            examples = examples,
        )
    }

    /**
     * Split content at `---` boundary, parse YAML frontmatter.
     * Returns (frontmatter map, markdown body).
     */
    fun extractFrontmatter(content: String): Pair<Map<String, Any?>, String> {
        val trimmed = content.trim()
        if (!trimmed.startsWith("---")) {
            return emptyMap<String, Any?>() to content
        }
        val endIndex = trimmed.indexOf("---", 3)
        if (endIndex == -1) {
            return emptyMap<String, Any?>() to content
        }
        val yamlContent = trimmed.substring(3, endIndex).trim()
        val body = trimmed.substring(endIndex + 3).trim()
        val map: Map<String, Any?> = try {
            yamlMapper.readValue(yamlContent)
        } catch (e: Exception) {
            logger.warn("Failed to parse YAML frontmatter: {}", e.message)
            emptyMap()
        }
        return map to body
    }
}