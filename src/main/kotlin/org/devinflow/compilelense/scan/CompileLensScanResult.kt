package org.devinflow.compilelense.scan

import com.intellij.openapi.vfs.VirtualFile
import org.devinflow.compilelense.model.UncompiledIssue

internal data class CompileLensScanResult(
    val issues: List<UncompiledIssue>,
    val scannedFilePaths: Set<String>,
    val incremental: Boolean,
    val daemonRestartsByProject: Map<ProjectRef, List<VirtualFile>> = emptyMap(),
    /**
     * Whether any open project has at least one configured source root. If false the
     * dashboard surfaces a hint asking the user to import the folder as a build project
     * or mark a directory as Sources Root, rather than showing an empty table.
     * Defaults to true so failure / fallback paths don't trigger the hint.
     */
    val hasJavaSourceRoots: Boolean = true,
) {
    data class ProjectRef(val project: com.intellij.openapi.project.Project)
}
