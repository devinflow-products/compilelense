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
        merge(project, issues)
    }

    fun merge(project: Project, issues: List<UncompiledIssue>) {
        if (project.isDisposed || issues.isEmpty()) return
        val key = project.locationHash
        val existing = errorsByProject[key].orEmpty()
        val combined = (existing + issues).distinctBy {
            "${CompileLensPaths.normalize(it.virtualFilePath)}:${it.lineNumber}:${it.issueDetail}"
        }
        errorsByProject[key] = combined
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
        val normalized = CompileLensPaths.normalize(filePath)
        val updated = current.filterNot { CompileLensPaths.normalize(it.virtualFilePath) == normalized }
        if (updated.size == current.size) return
        if (updated.isEmpty()) {
            errorsByProject.remove(key)
        } else {
            errorsByProject[key] = updated
        }
    }
}
