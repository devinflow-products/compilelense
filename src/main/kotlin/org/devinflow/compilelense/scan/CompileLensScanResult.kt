package org.devinflow.compilelense.scan

import com.intellij.openapi.vfs.VirtualFile
import org.devinflow.compilelense.model.UncompiledIssue

internal data class CompileLensScanResult(
    val issues: List<UncompiledIssue>,
    val scannedFilePaths: Set<String>,
    val incremental: Boolean,
    val daemonRestartsByProject: Map<ProjectRef, List<VirtualFile>> = emptyMap(),
) {
    data class ProjectRef(val project: com.intellij.openapi.project.Project)
}
