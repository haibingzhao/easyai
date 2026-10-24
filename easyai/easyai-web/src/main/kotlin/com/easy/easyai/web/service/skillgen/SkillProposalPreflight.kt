package com.easy.easyai.web.service.skillgen

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** Hard conflicts refuse a preview; [warnings] only inform. */
internal data class SkillPreflight(val conflicts: List<String>, val warnings: List<String>)

/**
 * The name checks shared by the inline and archive install flows: a catalog row or an unclaimed
 * directory in the target bucket is a hard conflict, while a missing catalog/disk and a same-named
 * skill in another layer are reported but do not block.
 */
internal class SkillProposalPreflight(
    private val skillConfig: SkillConfig?,
    private val catalog: AsyncSkillCatalogStore?,
    private val resolver: SkillAccessResolver?,
    private val writeOwner: String,
    private val owners: Collection<String>
) {

    suspend fun check(name: String): SkillPreflight {
        val conflicts = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (catalog == null) {
            warnings.add("Skill catalog unavailable; installed-name checks were skipped.")
        } else {
            try {
                if (catalog.findByName(writeOwner, name) != null) {
                    conflicts.add("Skill '$name' already exists in the target bucket. Pick another name or delete the existing skill first.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                warnings.add("Skill catalog lookup failed; installed-name checks were skipped.")
            }
        }
        val config = skillConfig
        if (config == null) {
            warnings.add("Skill storage configuration unavailable; the install-directory check was skipped.")
        } else {
            val target = SkillPaths.installDir(SkillPaths.ownerRoot(config, writeOwner), name)
            val exists = try {
                withContext(Dispatchers.IO) { Files.exists(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (exists) {
                conflicts.add(
                    "A directory named '$name' already exists in the target bucket but is not claimed by a skill. " +
                        "Pick another name or remove that directory first."
                )
            }
        }
        try {
            val visible = resolver?.listScopedSkillsForOwners(owners).orEmpty()
            // Only a catalog row proves the name lives in another bucket; an unbound skill of a
            // catalog-less deployment would otherwise be reported as a shadow it cannot have.
            val shadows = visible.filter { scoped ->
                scoped.skill.name == name && scoped.catalogEntry != null && scoped.catalogEntry?.userId != writeOwner
            }
            if (shadows.isNotEmpty()) {
                warnings.add("A skill named '$name' exists in another layer; the target-bucket copy will shadow it.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            warnings.add("Shadow check failed; a same-named skill in another layer may be shadowed.")
        }
        return SkillPreflight(conflicts, warnings)
    }
}
