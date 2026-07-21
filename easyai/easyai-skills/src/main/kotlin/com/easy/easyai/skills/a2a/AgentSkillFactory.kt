package com.easy.easyai.skills.a2a

import com.easy.easyai.skills.SkillInfo

/**
 * Factory interface for converting SkillInfo → AgentSkill for A2A AgentCard.
 */
interface AgentSkillFactory {
    fun fromSkills(skills: List<SkillInfo>, namespace: String): List<AgentSkill>
}

/**
 * Default implementation: maps SkillInfo fields to AgentSkill fields.
 * ID is generated as "{namespace}_{skill.name}".
 */
class DefaultAgentSkillFactory : AgentSkillFactory {
    override fun fromSkills(skills: List<SkillInfo>, namespace: String): List<AgentSkill> =
        skills.map { skill ->
            AgentSkill(
                id = "${namespace}_${skill.name}",
                name = skill.name,
                description = skill.description ?: "",
                tags = skill.tags.toList(),
                examples = skill.examples.toList().ifEmpty { null },
            )
        }
}