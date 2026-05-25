package org.devinflow.compilelense.scan

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for compilation errors shown on the dashboard.
 * Startup baseline scans replace the full map; live incremental scans update it.
 */
@Service(Service.Level.PROJECT)
class CompilationErrorService(private val project: Project) : Disposable {

    private val issuesByFilePath = ConcurrentHashMap<String, List<UncompiledIssue>>()

    /** Clears dashboard rows only; preserves javac errors from the last compile. */
    fun clearDashboard() {
        issuesByFilePath.clear()
        CompileLensAnalysisSession.invalidateWorkspace(project)
    }

    fun clear() {
        clearDashboard()
        CompileLensBuildErrorCache.clear(project)
    }

    fun replaceAll(issues: List<UncompiledIssue>) {
        issuesByFilePath.clear()
        for (issue in issues) {
            issuesByFilePath.merge(issue.virtualFilePath, listOf(issue)) { existing, added ->
                existing + added
            }
        }
    }

    fun updateFile(filePath: String, issues: List<UncompiledIssue>) {
        if (issues.isEmpty()) {
            issuesByFilePath.remove(filePath)
        } else {
            issuesByFilePath[filePath] = issues
        }
    }

    fun removeFile(filePath: String) {
        updateFile(filePath, emptyList())
    }

    fun getAllIssues(): List<UncompiledIssue> =
        issuesByFilePath.values
            .flatten()
            .sortedWith(compareBy({ it.projectName }, { it.moduleName }, { it.fileName }, { it.lineNumber }))

    fun trackedFilePaths(): Set<String> = issuesByFilePath.keys.toSet()

    fun mergeIncremental(scannedPaths: Set<String>, issues: List<UncompiledIssue>) {
        if (scannedPaths.isEmpty()) {
            replaceAll(issues)
            return
        }
        val preserved = getAllIssues().filter { it.virtualFilePath !in scannedPaths }
        replaceAll(preserved + issues)
    }

    override fun dispose() {
        issuesByFilePath.clear()
    }

    companion object {
        fun getInstance(project: Project): CompilationErrorService =
            project.getService(CompilationErrorService::class.java)
    }
}
