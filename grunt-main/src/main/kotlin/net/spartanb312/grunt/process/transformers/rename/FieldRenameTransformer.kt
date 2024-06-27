package net.spartanb312.grunt.process.transformers.rename

import net.spartanb312.grunt.config.value
import net.spartanb312.grunt.process.Transformer
import net.spartanb312.grunt.process.hierarchy.FastHierarchy
import net.spartanb312.grunt.process.resource.NameGenerator
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.process.transformers.rename.MethodRenameTransformer.transform
import net.spartanb312.grunt.utils.count
import net.spartanb312.grunt.utils.extensions.isAnnotation
import net.spartanb312.grunt.utils.isExcludedIn
import net.spartanb312.grunt.utils.isNotExcludedIn
import net.spartanb312.grunt.utils.logging.Logger
import net.spartanb312.grunt.utils.nextBadKeyword
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.system.measureTimeMillis

/**
 * Renaming fields
 * Last update on 2024/06/27
 */
object FieldRenameTransformer : Transformer("FiledRename", Category.Renaming) {

    private val dictionary by value("Dictionary", "Alphabet")
    private val randomKeywordPrefix by value("RandomKeywordPrefix", false)
    private val prefix by value("Prefix", "")
    private val exclusion by value("Exclusion", listOf())
    private val excludedName by value("ExcludedName", listOf("INSTANCE", "Companion"))

    private val malPrefix = (if (randomKeywordPrefix) "$nextBadKeyword " else "") + prefix

    override fun ResourceCache.transform() {
        Logger.info(" - Renaming fields...")

        Logger.info("    Building hierarchy graph...")
        val hierarchy = FastHierarchy(this)
        val buildTime = measureTimeMillis {
            hierarchy.build()
        }
        Logger.info("    Took ${buildTime}ms to build ${hierarchy.size} hierarchies")

        val mappings = HashMap<String, String>()
        nonExcluded.asSequence()
            .filter { !it.isAnnotation && it.name.isNotExcludedIn(exclusion) }
            .forEach { classNode ->
                val dictionary = NameGenerator.getByName(dictionary)
                val info = hierarchy.getHierarchyInfo(classNode)
                if (!info.missingDependencies) {
                    for (fieldNode in classNode.fields) {
                        if (fieldNode.name.isExcludedIn(excludedName)) continue
                        if (hierarchy.isPrimeField(classNode, fieldNode)) {
                            val key = classNode.name + "." + fieldNode.name
                            val newName = malPrefix + dictionary.nextName()
                            mappings[key] = newName
                            // Apply for children
                            info.children.forEach { c ->
                                if (c is FastHierarchy.HierarchyInfo) {
                                    val childKey = c.classNode.name + "." + fieldNode.name
                                    mappings[childKey] = newName
                                }
                            }
                        } else continue
                    }
                }
            }


        Logger.info("    Applying remapping for fields...")
        applyRemap("fields", mappings)

        Logger.info("    Renamed ${mappings.size} fields")
    }

    fun FastHierarchy.isPrimeField(owner: ClassNode, field: FieldNode): Boolean {
        val ownerInfo = getHierarchyInfo(owner)
        if (ownerInfo.missingDependencies) return false
        return ownerInfo.parents.none { p ->
            if (p is FastHierarchy.HierarchyInfo) {
                p.classNode.fields.any { it.name == field.name && it.desc == field.desc }
            } else true//Missing dependencies
        }
    }

}