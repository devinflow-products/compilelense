package org.devinflow.compilelense.maven

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.devinflow.compilelense.scan.CompileLensScanService
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Triggers a full-workspace rescan after a Maven import completes (reload / dependency changes).
 *
 * Registered against [MavenImportListener.TOPIC] in compilelense-maven.xml; one instance is
 * created per project that has the Maven plugin enabled.
 */
class CompileLensMavenSyncListener(private val project: Project) : MavenImportListener {

    override fun importFinished(
        importedProjects: MutableCollection<MavenProject>,
        newModules: MutableList<Module>,
    ) {
        if (project.isDisposed) return
        CompileLensScanService.getInstance(project).scheduleFullWorkspaceRescan("Maven import completed")
    }
}
