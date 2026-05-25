package org.devinflow.compilelense.scan

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which source files still need a daemon analysis pass vs. were analyzed with no errors.
 */
internal object CompileLensAnalysisSession {

    private val dirtyPathsByProject = ConcurrentHashMap<String, MutableSet<String>>()
    private val cleanPathsByProject = ConcurrentHashMap<String, MutableSet<String>>()
    private val analyzedPathsByProject = ConcurrentHashMap<String, MutableSet<String>>()

    fun invalidateWorkspace(project: Project) {
        val key = project.locationHash
        dirtyPathsByProject.remove(key)
        cleanPathsByProject.remove(key)
        analyzedPathsByProject.remove(key)
    }

    fun invalidateFile(project: Project, filePath: String) {
        val key = project.locationHash
        cleanPathsByProject.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.remove(filePath)
        dirtyPathsByProject.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(filePath)
    }

    fun markClean(project: Project, filePath: String) {
        val key = project.locationHash
        dirtyPathsByProject.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.remove(filePath)
        cleanPathsByProject.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(filePath)
    }

    fun clearCleanMark(project: Project, filePath: String) {
        cleanPathsByProject[project.locationHash]?.remove(filePath)
    }

    fun isDirty(project: Project, filePath: String): Boolean =
        dirtyPathsByProject[project.locationHash]?.contains(filePath) == true

    fun isDirtyInAnyProject(filePath: String): Boolean =
        dirtyPathsByProject.values.any { it.contains(filePath) }

    fun invalidateFileInWorkspace(filePath: String) {
        for (key in dirtyPathsByProject.keys) {
            cleanPathsByProject[key]?.remove(filePath)
            dirtyPathsByProject.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(filePath)
        }
    }

    fun isKnownClean(project: Project, filePath: String): Boolean =
        cleanPathsByProject[project.locationHash]?.contains(filePath) == true

    fun markAnalyzed(project: Project, filePath: String) {
        analyzedPathsByProject.computeIfAbsent(project.locationHash) { ConcurrentHashMap.newKeySet() }.add(filePath)
    }

    fun wasAnalyzed(project: Project, filePath: String): Boolean =
        analyzedPathsByProject[project.locationHash]?.contains(filePath) == true

    fun dirtyPaths(project: Project): Set<String> =
        dirtyPathsByProject[project.locationHash]?.toSet().orEmpty()

    fun hasDirty(project: Project): Boolean =
        dirtyPathsByProject[project.locationHash]?.isNotEmpty() == true

    fun clear(project: Project) {
        val key = project.locationHash
        dirtyPathsByProject.remove(key)
        cleanPathsByProject.remove(key)
        analyzedPathsByProject.remove(key)
    }
}
