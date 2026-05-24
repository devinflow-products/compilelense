package org.devinflow.compilelense.scan

import com.intellij.openapi.project.Project
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the latest compiler (javac) errors from a project rebuild until the next rebuild.
 */
internal object CompileLensBuildErrorCache {

    private val errorsByProject = ConcurrentHashMap<String, List<UncompiledIssue>>()

    fun store(project: Project, issues: List<UncompiledIssue>) {
        if (project.isDisposed) return
        errorsByProject[project.locationHash] = issues
    }

    fun get(project: Project): List<UncompiledIssue> =
        if (project.isDisposed) emptyList() else errorsByProject[project.locationHash].orEmpty()

    fun clear(project: Project) {
        errorsByProject.remove(project.locationHash)
    }

    fun removeFile(project: Project, filePath: String) {
        if (project.isDisposed) return
        val key = project.locationHash
        val current = errorsByProject[key] ?: return
        val updated = current.filterNot { it.virtualFilePath == filePath }
        if (updated.size == current.size) return
        if (updated.isEmpty()) {
            errorsByProject.remove(key)
        } else {
            errorsByProject[key] = updated
        }
    }
}
