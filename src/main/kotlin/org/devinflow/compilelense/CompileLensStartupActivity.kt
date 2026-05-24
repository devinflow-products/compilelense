package org.devinflow.compilelense

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import org.devinflow.compilelense.scan.CompileLensScanCoordinator
import org.devinflow.compilelense.scan.CompileLensScanService

/**
 * Starts the scan service and schedules a workspace-wide scan once every open project is indexed.
 */
class CompileLensStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AppExecutorUtil.getAppExecutorService().submit {
            CompileLensScanService.getInstance(project)
            CompileLensScanCoordinator.runWhenAllProjectsSmart(project) {
                CompileLensScanCoordinator.refreshAll(project, restartDaemon = true)
            }
            // Full rebuild + scan runs when the user opens the CompileLens tool window.
        }
    }
}
